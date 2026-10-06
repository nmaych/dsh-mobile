package ai.deepseek.dshmobile.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Client for the DeepSeek Harness browser API.
 *
 * The Harness web server (`dsh web`) exposes three things this app uses:
 *
 *  1. **Token handshake.** `GET /?token=<processToken>` sets an authority-bound
 *     signed cookie and redirects to a clean URL. Every `/api` call and the
 *     WebSocket require that cookie; there is no bearer-token path.
 *
 *  2. **Unary RPC.** `POST /api/<namespace>/<method>` carrying
 *     `{"type":"client-request","rpcId":<uuid>,"method":<method>,
 *       "payload":{"args":{…}}}` and answering
 *     `{"type":"server-response","rpcId":<uuid>,"result":{"ok":true,"value":…}}`
 *     or `{"ok":false,"error":{"code","message","details"}}`.
 *
 *     `payload` must contain exactly one key, `args`, whose keys are the
 *     descriptor's *wire* names. The gateway rejects both extra and missing
 *     argument keys, so callers below pass exact wire names — note that
 *     `session/list` takes `_request` while other session methods take
 *     `request`.
 *
 *  3. **Streams.** One shared WebSocket at `/api/remote.mux`. A logical stream
 *     opens with `{"type":"open",streamId,endpoint,payload}` and receives
 *     `item` frames followed by `end` or `error`.
 */
class DshClient(private val prefs: Prefs) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ------------------------------------------------------------- cookie jar

    private val cookieJar = object : CookieJar {
        private val store = ConcurrentHashMap<String, Cookie>()

        init {
            // Restore a previously paired session so a restart stays connected.
            val raw = prefs.sessionCookie
            val host = prefs.serverUrl.toHttpUrlOrNull()?.host
            if (raw.isNotBlank() && host != null) {
                val first = raw.split(";").map { it.trim() }.firstOrNull { it.contains("=") }
                if (first != null) {
                    val i = first.indexOf('=')
                    val name = first.substring(0, i)
                    val value = first.substring(i + 1)
                    if (name.isNotBlank() && value.isNotBlank()) {
                        store[host] = Cookie.Builder()
                            .name(name)
                            .value(value)
                            .hostOnlyDomain(host)
                            .path("/")
                            .build()
                    }
                }
            }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) {
                store[url.host] = c
                persist()
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store[url.host]?.let { listOf(it) } ?: emptyList()

        fun cookieHeader(url: HttpUrl): String =
            store[url.host]?.let { "${it.name}=${it.value}" } ?: ""

        private fun persist() {
            prefs.sessionCookie = store.values.joinToString("; ") { "${it.name}=${it.value}" }
        }
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // streams are long-lived
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ------------------------------------------------------------------ errors

    class DshException(message: String, val code: String = "error") : Exception(message)

    // -------------------------------------------------------------- transport

    /**
     * Whether this origin is reached through the `dsh-mobile-connect` LAN gateway.
     *
     * The two paths differ only in how the request is authorised: through the
     * gateway the phone presents a device token and the gateway supplies the
     * Harness session; directly the phone holds the session cookie itself.
     */
    private fun isGateway(): Boolean = prefs.deviceToken.isNotBlank()

    /** Append the device token the gateway expects, when there is one. */
    private fun authorize(url: String): String {
        val token = prefs.deviceToken
        if (token.isBlank()) return url
        return if (url.contains('?')) "$url&t=$token" else "$url?t=$token"
    }

    // -------------------------------------------------------------- handshake

    /**
     * Exchange the token printed by `dsh web` for a session cookie.
     *
     * Only used on the direct path. Through the gateway the phone never sees a
     * Harness session, so there is nothing to exchange.
     *
     * @return the cookie header, or throws when the token is rejected.
     */
    suspend fun pair(origin: String, token: String): String = withContext(Dispatchers.IO) {
        val base = origin.trim().trimEnd('/')
        val url = "$base/?token=${java.net.URLEncoder.encode(token, "UTF-8")}"
        http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (resp.code !in 200..399) {
                throw DshException("握手失败：HTTP ${resp.code}", "handshake")
            }
            val parsed = base.toHttpUrlOrNull()
                ?: throw DshException("服务器地址无效：$base", "bad-origin")
            val cookie = cookieJar.cookieHeader(parsed)
            if (cookie.isBlank()) {
                throw DshException(
                    "服务器没有下发会话 Cookie。请确认链接完整，且来自最近一次 `dsh web` 启动。",
                    "no-cookie",
                )
            }
            cookie
        }
    }

    /** True when the stored credential still authenticates. */
    suspend fun verifySession(origin: String): Boolean =
        runCatching { listSessions(origin, limit = 1) }.isSuccess

    // -------------------------------------------------------------- unary RPC

    /**
     * Perform one unary Remote call.
     *
     * @param args the value placed at `payload.args`; its keys must be the
     *   descriptor's exact wire names.
     */
    suspend fun rpc(
        origin: String,
        namespace: String,
        method: String,
        args: JSONObject = JSONObject(),
    ): JSONObject = withContext(Dispatchers.IO) {
        val base = origin.trim().trimEnd('/')
        val rpcId = UUID.randomUUID().toString()
        // `method` carries the full endpoint; the gateway rejects a bare method
        // name with `gateway/bad-request: method "x" does not match endpoint …`.
        val body = JSONObject()
            .put("type", "client-request")
            .put("rpcId", rpcId)
            .put("method", "$namespace/$method")
            .put("payload", JSONObject().put("args", args))
            .toString()

        val req = Request.Builder()
            .url(authorize("$base/api/$namespace/$method"))
            .post(body.toRequestBody(JSON))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            when (resp.code) {
                401 -> throw DshException("会话已失效，请用 `dsh web` 的新链接重新配对。", "unauthorized")
                403 -> throw DshException("Host/Origin 校验未通过。请确认使用的是打印出的主机名。", "forbidden")
            }
            if (!resp.isSuccessful) {
                throw DshException("HTTP ${resp.code}: ${text.take(300)}", "http-${resp.code}")
            }
            parseResponse(text, rpcId)
        }
    }

    private fun parseResponse(text: String, expectedRpcId: String): JSONObject {
        val obj = runCatching { JSONObject(text) }
            .getOrElse { throw DshException("响应不是合法 JSON：${text.take(200)}", "bad-json") }

        if (obj.optString("type") != "server-response") {
            throw DshException("意外的响应信封：${text.take(200)}", "bad-envelope")
        }
        val got = obj.optString("rpcId")
        if (got != expectedRpcId) {
            throw DshException("rpcId 不匹配：期望 $expectedRpcId，收到 $got", "rpc-mismatch")
        }
        val result = obj.optJSONObject("result")
            ?: throw DshException("响应缺少 result", "bad-envelope")

        if (result.optBoolean("ok", false)) {
            return result.optJSONObject("value") ?: JSONObject()
        }
        val err = result.optJSONObject("error")
        throw DshException(
            err?.optString("message")?.takeIf { it.isNotBlank() } ?: "调用失败",
            err?.optString("code") ?: "error",
        )
    }

    // ------------------------------------------------------------- streaming

    private class StreamState(
        val inbox: Channel<JSONObject> = Channel(Channel.UNLIMITED),
    )

    private val streams = ConcurrentHashMap<String, StreamState>()
    @Volatile private var socket: WebSocket? = null

    private val _connectionState = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 8)
    val connectionState: SharedFlow<Boolean> = _connectionState.asSharedFlow()

    private fun wsUrl(origin: String): String {
        val o = origin.trim().trimEnd('/')
        val base = when {
            o.startsWith("https://") -> "wss://" + o.removePrefix("https://")
            o.startsWith("http://") -> "ws://" + o.removePrefix("http://")
            else -> "ws://$o"
        } + "/api/remote.mux"
        // Browsers cannot set headers on a WebSocket handshake, so the gateway
        // takes the device token from the query string; use the same shape here.
        return authorize(base)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            scope.launch { _connectionState.emit(true) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
            val state = streams[obj.optString("streamId")] ?: return
            when (obj.optString("type")) {
                "item" -> state.inbox.trySend(obj.optJSONObject("value") ?: JSONObject())
                "end" -> state.inbox.close()
                "error" -> {
                    val e = obj.optJSONObject("error")
                    state.inbox.close(
                        DshException(
                            e?.optString("message") ?: "stream error",
                            e?.optString("code") ?: "stream-error",
                        )
                    )
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "ws failure: ${t.message}")
            scope.launch { _connectionState.emit(false) }
            failAll(DshException("连接中断：${t.message}", "ws-failure"))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scope.launch { _connectionState.emit(false) }
            if (code != 1000) failAll(DshException("连接关闭：$code $reason", "ws-closed"))
        }
    }

    private fun failAll(error: Throwable) {
        socket = null
        for ((_, s) in streams) s.inbox.close(error)
        streams.clear()
    }

    private fun ensureSocket(origin: String): WebSocket {
        socket?.let { return it }
        val ws = http.newWebSocket(Request.Builder().url(wsUrl(origin)).build(), listener)
        socket = ws
        return ws
    }

    /**
     * Open one logical stream on the shared mux socket.
     *
     * @param endpoint `<namespace>/<method>`, e.g. `session/follow`.
     * @param args placed at `payload.args` with exact wire names.
     * @return a cold flow of decoded `value` items.
     */
    fun stream(
        origin: String,
        endpoint: String,
        args: JSONObject = JSONObject(),
    ): Flow<JSONObject> = flow {
        val streamId = UUID.randomUUID().toString()
        val state = StreamState()
        streams[streamId] = state

        val ws = ensureSocket(origin)
        ws.send(
            JSONObject()
                .put("type", "open")
                .put("streamId", streamId)
                .put("endpoint", endpoint)
                .put("payload", JSONObject().put("args", args))
                .toString()
        )

        try {
            for (item in state.inbox) emit(item)
        } finally {
            streams.remove(streamId)
            runCatching {
                ws.send(JSONObject().put("type", "cancel").put("streamId", streamId).toString())
            }
        }
    }

    // --------------------------------------------------------- session helpers

    data class SessionInfo(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val cwd: String?,
        val blank: Boolean,
        val running: Boolean,
    )

    /**
     * `session/list` → the session picker.
     *
     * `SessionSummary` carries no `title` field; the display name lives in
     * `projections.values.title`. Fall back to the `cwd` basename, then the id.
     */
    suspend fun listSessions(origin: String, limit: Int = 200): List<SessionInfo> {
        val value = rpc(origin, "session", "list", JSONObject().put("_request", JSONObject()))
        val items = value.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("sessionId")
            if (id.isBlank()) return@mapNotNull null
            val cwd = o.optString("cwd").takeIf { it.isNotBlank() }
            val projected = o.optJSONObject("projections")
                ?.optJSONObject("values")
                ?.optString("title")
                ?.takeIf { it.isNotBlank() }
            SessionInfo(
                id = id,
                title = projected ?: cwd?.let { baseName(it) } ?: id,
                updatedAt = o.optLong("updatedAt", 0L),
                cwd = cwd,
                blank = o.optBoolean("blank", false),
                running = o.optBoolean("running", false),
            )
        }.sortedByDescending { it.updatedAt }.take(limit)
    }

    private fun baseName(path: String): String =
        path.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifBlank { path }

    suspend fun createSession(origin: String, cwd: String? = null): String {
        val req = JSONObject()
        if (!cwd.isNullOrBlank()) req.put("cwd", cwd)
        val value = rpc(origin, "session", "create", JSONObject().put("request", req))
        return value.optString("sessionId").ifBlank {
            throw DshException("创建会话失败：服务器未返回 sessionId", "create-failed")
        }
    }

    /**
     * Send a prompt.
     *
     * @param mode `queue` for a normal turn, `steer` to redirect the running one.
     */
    suspend fun prompt(
        origin: String,
        sessionId: String,
        text: String,
        mode: String = "queue",
    ) {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        rpc(
            origin, "session", "prompt",
            JSONObject().put(
                "request",
                JSONObject()
                    .put("requestId", UUID.randomUUID().toString())
                    .put("sessionId", sessionId)
                    .put("mode", mode)
                    .put("content", content)
                    .put("clientTimeZone", java.util.TimeZone.getDefault().id),
            ),
        )
    }

    suspend fun cancel(origin: String, sessionId: String) {
        rpc(
            origin, "session", "cancel",
            JSONObject().put("request", JSONObject().put("sessionId", sessionId)),
        )
    }

    suspend fun renameSession(origin: String, sessionId: String, title: String) {
        rpc(
            origin, "session", "rename",
            JSONObject().put(
                "request",
                JSONObject().put("sessionId", sessionId).put("title", title),
            ),
        )
    }

    /** Full provider/model catalog, including per-model reasoning efforts. */
    suspend fun modelCatalog(origin: String): JSONObject =
        rpc(origin, "session", "modelCatalog")

    suspend fun selectModel(
        origin: String,
        sessionId: String,
        provider: String,
        model: String,
        reasoningEffort: String? = null,
    ) {
        val req = JSONObject()
            .put("sessionId", sessionId)
            .put("provider", provider)
            .put("model", model)
        if (!reasoningEffort.isNullOrBlank()) req.put("reasoningEffort", reasoningEffort)
        rpc(origin, "session", "selectModel", JSONObject().put("request", req))
    }

    /**
     * Follow a session's event log.
     *
     * The first item is normally a `snapshot` carrying the backlog; later items
     * are `event` frames and — because `assistantStream` is on — live
     * `assistant-stream` frames carrying token deltas.
     */
    fun followSession(
        origin: String,
        sessionId: String,
        maxMessages: Int = 200,
    ): Flow<JSONObject> = stream(
        origin, "session/follow",
        JSONObject().put(
            "request",
            JSONObject()
                .put("address", JSONObject().put("kind", "session").put("sessionId", sessionId))
                .put("assistantStream", true)
                .put("maxMessages", maxMessages)
                .put("turnWindow", JSONObject().put("minMessages", maxMessages).put("minTurns", 2)),
        ),
    )

    /** Backward paging, used to load older history. */
    suspend fun pageSession(
        origin: String,
        sessionId: String,
        throughSeq: Long,
        beforeSeq: Long? = null,
        maxMessages: Int = 100,
    ): JSONObject {
        val req = JSONObject()
            .put("address", JSONObject().put("kind", "session").put("sessionId", sessionId))
            .put("throughSeq", throughSeq)
            .put("maxMessages", maxMessages)
        if (beforeSeq != null) req.put("beforeSeq", beforeSeq)
        return rpc(origin, "session", "page", JSONObject().put("request", req))
    }

    companion object {
        private const val TAG = "DshClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
