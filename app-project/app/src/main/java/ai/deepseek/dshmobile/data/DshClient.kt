package ai.deepseek.dshmobile.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
import ai.deepseek.dshmobile.net.TransportErrors
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
            restore()
        }

        /** Re-seed from prefs, so a re-pair does not keep the old authority. */
        fun restore() {
            store.clear()
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
     * Turn a transport failure into something the user can act on.
     *
     * The wording lives in [ai.deepseek.dshmobile.net.TransportErrors] because the
     * same failure can arrive through three different back ends, and each needs
     * different advice: the remote path is a LAN gateway the desktop plugin
     * serves, the API path is an arbitrary internet host, and the updater is a
     * manifest URL. The [DshException] code is kept as the caller passed it, so
     * the mux retry in [stream] still recognises `ws-failure`/`ws-closed`.
     */
    private fun transportError(
        url: String,
        t: java.io.IOException,
        code: String = "transport",
    ): DshException =
        DshException(TransportErrors.message(url, t, TransportErrors.LAN_HINT), code)
            .also { it.initCause(t) }

    /** Append the device token the gateway expects, when there is one. */
    private fun authorize(url: String): String {
        val token = prefs.deviceToken
        if (token.isBlank()) return url
        return if (url.contains('?')) "$url&t=$token" else "$url?t=$token"
    }

    /**
     * Drop every cached transport fact.
     *
     * Called whenever the pairing changes. The mux socket and the restored
     * cookie are both bound to one authority, so reusing them after pairing with
     * a different desktop would send every request to the *old* host — which
     * looks like "the app is connected but nothing works".
     *
     * Registered streams are failed here rather than left to the socket's own
     * `onFailure`: that callback is guarded by a socket-identity check, and by the
     * time it fires this method has already cleared `socket`, so the guard would
     * discard it and every stream riding the old connection would wait forever.
     */
    @Synchronized
    fun reset() {
        val old = socket
        socket = null
        socketUrl = ""
        if (old != null) {
            for ((_, s) in streams) s.inbox.close(DshException("连接已重置", "ws-reset"))
            streams.clear()
            old.cancel()
        }
        cookieJar.restore()
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
        val resp = try {
            http.newCall(Request.Builder().url(url).get().build()).execute()
        } catch (t: java.io.IOException) {
            throw transportError(base, t, code = "handshake")
        }
        resp.use { response ->
            if (response.code !in 200..399) {
                throw DshException("握手失败：HTTP ${response.code}", "handshake")
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

        // A dead network must not surface as raw OkHttp text: the user cannot
        // act on "port 39694 ... after 20000ms".
        val resp = try {
            http.newCall(req).execute()
        } catch (t: java.io.IOException) {
            throw transportError(base, t)
        }
        resp.use { response ->
            val text = response.body?.string().orEmpty()
            when (response.code) {
                401 -> throw DshException("会话已失效，请用 `dsh web` 的新链接重新配对。", "unauthorized")
                403 -> throw DshException("Host/Origin 校验未通过。请确认使用的是打印出的主机名。", "forbidden")
            }
            if (!response.isSuccessful) {
                throw DshException("HTTP ${response.code}: ${text.take(300)}", "http-${response.code}")
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
        val code = err?.optString("code") ?: "error"
        val raw = err?.optString("message")?.takeIf { it.isNotBlank() } ?: "调用失败"
        throw DshException(serverMessage(code, raw), code)
    }

    /**
     * The server's own error text, in Chinese where a user can act on it.
     *
     * The gateway answers in English. Most of its codes are self-explanatory
     * enough to pass through, but the subagent ones are not: they name an
     * internal addressing scheme ("require their durable parent address") that
     * tells a phone user nothing about what to do next. Those get a sentence
     * that says what happened and what the app is doing about it.
     *
     * Anything unrecognised keeps the server's wording, which is the honest
     * fallback — a new code is more useful verbatim than a generic apology.
     */
    private fun serverMessage(code: String, raw: String): String = when (code) {
        // Raised when a *child* session is addressed as a plain top-level one.
        // The app derives the parent from `session/list`, so reaching this means
        // the list row did not carry one; the message says so rather than
        // repeating the server's internal wording.
        "session/agent-busy" ->
            "这是一个子代理会话，需要通过它的父会话来打开。请下拉刷新会话列表后重试。"

        "subagent/unauthorized" ->
            "子代理会话与它的父会话不匹配。请刷新会话列表后重试。"

        "subagent/catalog-diagnostic" ->
            "子代理会话的信息不可用，可能已被清理。请刷新会话列表。"

        "subagent/not-found" -> "子代理会话已不存在。请刷新会话列表。"

        "session/not-found" -> "会话已不存在。请刷新会话列表。"

        "gateway/cancelled" -> "请求已取消。"

        else -> raw
    }

    // ------------------------------------------------------------- streaming

    private class StreamState(
        val inbox: Channel<JSONObject> = Channel(Channel.UNLIMITED),
    )

    private val streams = ConcurrentHashMap<String, StreamState>()
    @Volatile private var socket: WebSocket? = null

    /**
     * The URL the live socket was opened for.
     *
     * Cached alongside the socket so a re-pair to a different authority cannot
     * silently keep talking to the previous desktop.
     */
    @Volatile private var socketUrl: String = ""

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
            // The socket URL is the only address in hand here, and it carries the
            // same host:port the user needs to be told about. The `ws-failure`
            // code is kept so the mux retry in `stream` still recognises it.
            //
            // Every throwable goes through the mapper, not just the IOException
            // ones: OkHttp hands a WebSocket listener its own RouteException (a
            // RuntimeException) for a failed connect, and echoing that text put
            // "failed to connect to /192.168.3.103 (port 19387) … after 20000ms"
            // straight back on screen.
            val error = DshException(
                TransportErrors.message(socketUrl, t, TransportErrors.LAN_HINT),
                "ws-failure",
            ).also { it.initCause(t) }
            failAll(error, webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scope.launch { _connectionState.emit(false) }
            // A *normal* close still means this socket is unusable. Leaving it
            // cached made every later stream open onto a dead connection and hang
            // forever, which is what "连接异常" looked like in practice.
            val error = if (code == 1000) {
                DshException("连接已关闭", "ws-closed")
            } else {
                DshException("连接关闭：$code $reason", "ws-closed")
            }
            failAll(error, webSocket)
        }
    }

    private fun failAll(error: Throwable, from: WebSocket? = null) {
        // Only the current socket may tear down the shared state: a late failure
        // callback from a socket that was already replaced must not close the
        // streams now running on its successor.
        if (from != null && socket !== from) return
        socket = null
        socketUrl = ""
        for ((_, s) in streams) s.inbox.close(error)
        streams.clear()
    }

    /**
     * The socket for this origin, opening one if needed.
     *
     * Synchronized because several logical streams can be opened at the same
     * moment (a session follow and a workspace follow, say). Without the lock both
     * would observe `socket == null` and each create a socket, orphaning one and
     * splitting the streams across two connections.
     */
    @Synchronized
    private fun ensureSocket(origin: String): WebSocket {
        val url = wsUrl(origin)
        val current = socket
        if (current != null && url == socketUrl) return current
        // A socket for a different URL (a re-pair) is dead weight, and the
        // streams riding it can never complete, so they are failed with it.
        if (current != null) {
            socket = null
            socketUrl = ""
            for ((_, s) in streams) s.inbox.close(DshException("连接已重置", "ws-reset"))
            streams.clear()
            current.cancel()
        }
        val ws = http.newWebSocket(Request.Builder().url(url).build(), listener)
        socket = ws
        socketUrl = url
        return ws
    }

    /** True when a stream failure is a transport problem that a retry can fix. */
    private fun isTransportFailure(t: Throwable): Boolean {
        val code = (t as? DshException)?.code ?: return false
        return code == "ws-failure" || code == "ws-closed"
    }

    /**
     * Open one logical stream on the shared mux socket.
     *
     * A transport failure is retried with linear backoff, because the mux socket
     * dies whenever the phone sleeps, changes network, or the desktop restarts —
     * and a phone that has to be manually refreshed after every screen lock is
     * indistinguishable from a broken app. Retrying is safe for every endpoint
     * used here: `session/follow` replays its backlog, which the caller dedupes
     * by `seq`, and `workspace/follow` re-sends its baseline.
     *
     * @param endpoint `<namespace>/<method>`, e.g. `session/follow`.
     * @param args placed at `payload.args` with exact wire names.
     * @return a cold flow of decoded `value` items.
     */
    fun stream(
        origin: String,
        endpoint: String,
        args: JSONObject = JSONObject(),
        retry: Boolean = true,
    ): Flow<JSONObject> = flow {
        var attempt = 0
        while (true) {
            val streamId = UUID.randomUUID().toString()
            val state = StreamState()

            // The socket is resolved *before* this stream is registered. Opening
            // one for a new origin cancels the previous socket, and that teardown
            // clears every registered stream — including this one, if it had
            // already been added, which would leave it waiting on an inbox that
            // nothing can ever write to.
            val ws = ensureSocket(origin)
            streams[streamId] = state

            // `send` returns false rather than throwing when the socket is
            // already closed. Without this check the stream would sit on an inbox
            // that can never receive anything: the socket's own teardown already
            // ran, before this stream existed.
            val opened = ws.send(
                JSONObject()
                    .put("type", "open")
                    .put("streamId", streamId)
                    .put("endpoint", endpoint)
                    .put("payload", JSONObject().put("args", args))
                    .toString()
            )
            if (!opened) {
                streams.remove(streamId)
                // Drop the socket too: the close callback may not have run yet, and
                // the retry would otherwise pick up the same dead socket and fail
                // again until the retry budget ran out.
                failAll(DshException("连接不可用", "ws-closed"), ws)
                state.inbox.close(DshException("连接不可用", "ws-closed"))
            }

            var failure: Throwable? = null
            try {
                for (item in state.inbox) emit(item)
            } catch (t: Throwable) {
                failure = t
            } finally {
                streams.remove(streamId)
                runCatching {
                    ws.send(JSONObject().put("type", "cancel").put("streamId", streamId).toString())
                }
            }

            val error = failure ?: return@flow
            // A cancelled collector is not a transport fault: rethrow so the
            // cancellation propagates instead of being retried forever.
            if (error is kotlinx.coroutines.CancellationException) throw error
            currentCoroutineContext().ensureActive()
            if (!retry || !isTransportFailure(error) || attempt >= MAX_STREAM_RETRIES) throw error

            attempt++
            Log.w(TAG, "stream $endpoint interrupted; reconnecting (attempt $attempt)")
            delay(RETRY_BASE_MS * attempt)
        }
    }

    // --------------------------------------------------------- session helpers

    /**
     * A session's address on the server.
     *
     * A session spawned as a subagent is *not* addressable as a plain session:
     * the server rejects `{"kind":"session"}` for it with
     * "subagent Sessions require their durable parent address", because the
     * child's log is owned by the parent delegation and cannot be read in
     * isolation. Such a session must be addressed by the
     * `{kind:"subagent", parentSessionId, childSessionId, mode}` union instead.
     */
    sealed interface SessionAddress {
        fun toJson(): JSONObject

        /** An ordinary top-level session. */
        data class Plain(val sessionId: String) : SessionAddress {
            override fun toJson(): JSONObject =
                JSONObject().put("kind", "session").put("sessionId", sessionId)
        }

        /**
         * A subagent child session.
         *
         * @param mode the delegation's delivery mode, from the `subagent`
         *   projection. `"unknown"` is accepted by the server for read
         *   operations (`session/page`, `session/follow`) and is the honest
         *   value when the projection has not been read.
         */
        data class Subagent(
            val parentSessionId: String,
            val childSessionId: String,
            val mode: String,
        ) : SessionAddress {
            override fun toJson(): JSONObject = JSONObject()
                .put("kind", "subagent")
                .put("parentSessionId", parentSessionId)
                .put("childSessionId", childSessionId)
                .put("mode", mode)

            /** True when this child can be prompted through `subagents/prompt`. */
            val continuable: Boolean get() = mode == "continuable"
        }
    }

    data class SessionInfo(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val cwd: String?,
        val blank: Boolean,
        val running: Boolean,
        /** `provider/model` the session last used, from its projections. */
        val model: String? = null,
        /** Non-null when this session is a subagent child. */
        val subagent: SessionAddress.Subagent? = null,
    ) {
        /** The address to use for every read/write against this session. */
        val address: SessionAddress
            get() = subagent ?: SessionAddress.Plain(id)
    }

    /**
     * `session/list` → the session picker.
     *
     * `SessionSummary` carries no `title` field; the display name lives in
     * `projections.values.title`. Fall back to the `cwd` basename, then the id.
     *
     * `origin == "subagent"` marks a child session, and its durable parent is
     * what every later call has to be addressed with — so it is read here, once,
     * rather than guessed at each call site.
     */
    suspend fun listSessions(origin: String, limit: Int = 200): List<SessionInfo> {
        val value = rpc(origin, "session", "list", JSONObject().put("_request", JSONObject()))
        val items = value.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("sessionId")
            if (id.isBlank()) return@mapNotNull null
            val cwd = o.optString("cwd").takeIf { it.isNotBlank() }
            val projections = o.optJSONObject("projections")
            val projected = projections
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
                model = SessionParser.modelSelectionOf(projections),
                subagent = subagentAddressOf(o, projections, id),
            )
        }.sortedByDescending { it.updatedAt }.take(limit)
    }

    /**
     * Build the durable address of a child session, or null for a top-level one.
     *
     * The mode comes from the `subagent` projection when the server reports it.
     * `one-shot` children are recorded as `one-shot` so the caller knows they
     * cannot be prompted again; a missing or unrecognised mode is `unknown`,
     * which the server accepts for reads.
     */
    private fun subagentAddressOf(
        summary: JSONObject,
        projections: JSONObject?,
        sessionId: String,
    ): SessionAddress.Subagent? {
        if (summary.optString("origin") != "subagent") return null
        val parent = summary.optString("parentSessionId").takeIf { it.isNotBlank() }
            ?: return null
        val sub = projections?.optJSONObject("values")?.optJSONObject("subagent")
        val rawMode = sub?.optString("mode").orEmpty()
        val mode = when (rawMode) {
            "continuable", "one-shot" -> rawMode
            else -> "unknown"
        }
        return SessionAddress.Subagent(
            parentSessionId = parent,
            childSessionId = sessionId,
            mode = mode,
        )
    }

    private fun baseName(path: String): String =
        path.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifBlank { path }

    /**
     * Create a session, optionally inside a workspace or an explicit directory.
     *
     * The descriptor accepts `workspaceId`, `cwd`, `sessionId` and
     * `agentPreset`; only the first two are useful here, and sending an unknown
     * key is rejected outright, so each is added only when it has a value.
     */
    suspend fun createSession(
        origin: String,
        cwd: String? = null,
        workspaceId: String? = null,
    ): String {
        val req = JSONObject()
        if (!workspaceId.isNullOrBlank()) req.put("workspaceId", workspaceId)
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

    // ------------------------------------------------------------------ models

    /** One selectable model, flattened from the catalog's provider groups. */
    data class ModelOption(
        val provider: String,
        val providerName: String,
        val id: String,
        val name: String,
        val description: String,
        val efforts: List<String>,
        val defaultEffort: String?,
    ) {
        /** Stable identity for a picker row. */
        val key: String get() = "$provider/$id"
    }

    data class ModelCatalog(
        val options: List<ModelOption>,
        val defaultProvider: String,
        val defaultModel: String,
        val failures: List<String>,
    )

    /**
     * Full provider/model catalog, including per-model reasoning efforts.
     *
     * `session/modelCatalog` takes no arguments and answers
     * `{default, routableProviders, groups[], failures[]}`. Groups whose provider
     * is not routable are still listed so the user can see why a model is
     * missing, but they are reported through `failures` rather than as choices.
     */
    suspend fun modelCatalog(origin: String): ModelCatalog {
        val value = rpc(origin, "session", "modelCatalog")
        val options = mutableListOf<ModelOption>()
        val groups = value.optJSONArray("groups") ?: JSONArray()
        for (i in 0 until groups.length()) {
            val group = groups.optJSONObject(i) ?: continue
            val provider = group.optString("id")
            if (provider.isBlank()) continue
            val providerName = group.optString("name").ifBlank { provider }
            val models = group.optJSONArray("models") ?: JSONArray()
            for (j in 0 until models.length()) {
                val m = models.optJSONObject(j) ?: continue
                val id = m.optString("id")
                if (id.isBlank()) continue
                val reasoning = m.optJSONObject("reasoning")
                val efforts = reasoning?.optJSONArray("efforts")?.let { arr ->
                    (0 until arr.length()).mapNotNull { k ->
                        arr.optJSONObject(k)?.optString("id")?.takeIf { it.isNotBlank() }
                    }
                }.orEmpty()
                options += ModelOption(
                    provider = provider,
                    providerName = providerName,
                    id = id,
                    name = m.optString("name").ifBlank { id },
                    description = m.optString("description"),
                    efforts = efforts,
                    defaultEffort = reasoning?.optString("defaultEffort")?.takeIf { it.isNotBlank() },
                )
            }
        }
        val failures = value.optJSONArray("failures")?.let { arr ->
            (0 until arr.length()).mapNotNull { k ->
                val f = arr.optJSONObject(k) ?: return@mapNotNull null
                val name = f.optString("name").ifBlank { f.optString("id") }
                val message = f.optString("message")
                if (message.isBlank()) null else "$name：$message"
            }
        }.orEmpty()
        val default = value.optJSONObject("default")
        return ModelCatalog(
            options = options,
            defaultProvider = default?.optString("provider").orEmpty(),
            defaultModel = default?.optString("model").orEmpty(),
            failures = failures,
        )
    }

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

    // -------------------------------------------------------------- workspaces

    data class Workspace(
        val id: String,
        val path: String,
        val title: String,
    ) {
        val label: String get() = title.ifBlank { baseNameOf(path) }
    }

    /**
     * The workspace list.
     *
     * There is no unary list method: `workspace/follow` is a stream whose first
     * frame is a `baseline` carrying every workspace plus the archived and
     * pinned session ids. The baseline is taken and the stream cancelled, which
     * is the whole read this app needs.
     */
    suspend fun listWorkspaces(origin: String): List<Workspace> {
        // `retry = false`: a failed read is reported, not silently retried into a
        // spinner that never resolves.
        return try {
            stream(origin, "workspace/follow", JSONObject(), retry = false).collect { frame ->
                if (frame.optString("type") == "baseline") {
                    val items = frame.optJSONObject("value")?.optJSONArray("items") ?: JSONArray()
                    // The baseline is the complete snapshot, so the stream is
                    // abandoned here rather than left open for the app's life.
                    throw WorkspaceBaselineReached(
                        (0 until items.length()).mapNotNull { i ->
                            val w = items.optJSONObject(i) ?: return@mapNotNull null
                            val id = w.optString("workspaceId")
                            if (id.isBlank()) return@mapNotNull null
                            Workspace(
                                id = id,
                                path = w.optString("path"),
                                title = w.optString("title"),
                            )
                        }
                    )
                }
            }
            emptyList()
        } catch (e: WorkspaceBaselineReached) {
            e.items
        }
    }

    /** Control-flow signal used to stop the `workspace/follow` stream early. */
    private class WorkspaceBaselineReached(val items: List<Workspace>) : Exception()

    /**
     * Register a directory as a workspace.
     *
     * @return the workspace, whether it already existed or was just created.
     */
    suspend fun createWorkspace(origin: String, path: String): Workspace {
        val value = rpc(
            origin, "workspace", "create",
            JSONObject().put("request", JSONObject().put("path", path)),
        )
        val w = value.optJSONObject("workspace")
        return Workspace(
            id = w?.optString("workspaceId").orEmpty(),
            path = w?.optString("path") ?: path,
            title = w?.optString("title").orEmpty(),
        )
    }

    // ------------------------------------------------------------------ usage

    data class UsageSnapshot(
        val total: TokenUsage,
        val contextWindow: Long?,
        val contextTokens: Long?,
    )

    /**
     * Read the session's token accounting.
     *
     * `session/projections` returns every registered projection; the two this app
     * shows are `tokenUsage` (running provider totals) and `contextPressure`
     * (prompt-side occupancy against the model's window). Both are optional and
     * ride the projection record, so a server without the token-meter plugin
     * simply yields nothing.
     */
    suspend fun sessionUsage(origin: String, sessionId: String): UsageSnapshot? {
        val value = rpc(
            origin, "session", "projections",
            JSONObject().put("request", JSONObject().put("sessionId", sessionId)),
        )
        if (!value.has("values") || value.isNull("values")) return null
        val values = value.optJSONObject("values") ?: return null
        val total = TokenUsage.fromProjection(values.optJSONObject("tokenUsage")) ?: TokenUsage()
        val pressure = values.optJSONObject("contextPressure")
        val window = pressure?.optLong("contextWindow", 0L) ?: 0L
        // `projectedTokens` is the sample plus the surface's movement since it was
        // taken, so it answers for the *next* request; `pressureTokens` is the
        // fallback when no movement has been folded yet.
        val projected = pressure?.optLong("projectedTokens", 0L) ?: 0L
        val raw = pressure?.optLong("pressureTokens", 0L) ?: 0L
        val context = if (projected > 0L) projected else raw
        return UsageSnapshot(
            total = total,
            contextWindow = window.takeIf { it > 0L },
            contextTokens = context.takeIf { it > 0L },
        )
    }

    /**
     * Follow a session's event log.
     *
     * The first item is normally a `snapshot` carrying the backlog; later items
     * are `event` frames and — because `assistantStream` is on — live
     * `assistant-stream` frames carrying token deltas.
     *
     * @param address a top-level session or a subagent child; the server rejects
     *   a plain `session` address for a child.
     */
    fun followSession(
        origin: String,
        address: SessionAddress,
        maxMessages: Int = 200,
    ): Flow<JSONObject> = stream(
        origin, "session/follow",
        JSONObject().put(
            "request",
            JSONObject()
                .put("address", address.toJson())
                .put("assistantStream", true)
                .put("maxMessages", maxMessages)
                .put("turnWindow", JSONObject().put("minMessages", maxMessages).put("minTurns", 2)),
        ),
    )

    /** Backward paging, used to load older history. */
    suspend fun pageSession(
        origin: String,
        address: SessionAddress,
        throughSeq: Long,
        beforeSeq: Long? = null,
        maxMessages: Int = 100,
    ): JSONObject {
        val req = JSONObject()
            .put("address", address.toJson())
            .put("throughSeq", throughSeq)
            .put("maxMessages", maxMessages)
        if (beforeSeq != null) req.put("beforeSeq", beforeSeq)
        return rpc(origin, "session", "page", JSONObject().put("request", req))
    }

    /**
     * Prompt a continuable subagent child.
     *
     * A child cannot be driven through `session/prompt`: the parent owns the
     * delegation, so the turn has to be queued on the child through
     * `subagents/prompt`, naming both ends of the pair.
     *
     * @param delivery `queue` for a normal turn, `steer` to redirect a running one.
     */
    suspend fun promptSubagent(
        origin: String,
        parentSessionId: String,
        childSessionId: String,
        text: String,
        delivery: String = "queue",
    ) {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        rpc(
            origin, "subagents", "prompt",
            JSONObject().put(
                "request",
                JSONObject()
                    .put("requestId", UUID.randomUUID().toString())
                    .put("parentSessionId", parentSessionId)
                    .put("childSessionId", childSessionId)
                    .put("mode", "continuable")
                    .put("delivery", delivery)
                    .put("content", content)
                    .put("clientTimeZone", java.util.TimeZone.getDefault().id),
            ),
        )
    }

    /**
     * Interrupt a continuable subagent child from its parent.
     *
     * The descriptor declares three *top-level* parameters
     * (`childSessionId`, `parentSessionId`, `mode`) — there is no `request`
     * wrapper, unlike `subagents/prompt` right above it. Nesting them under
     * `request` is a strict-codec rejection, so the two sibling methods do not
     * share a shape and this one is written out in full.
     */
    suspend fun interruptSubagent(
        origin: String,
        parentSessionId: String,
        childSessionId: String,
    ) {
        rpc(
            origin, "subagents", "interruptByParent",
            JSONObject()
                .put("childSessionId", childSessionId)
                .put("parentSessionId", parentSessionId)
                .put("mode", "continuable"),
        )
    }

    /**
     * Send a prompt to whatever session [address] names.
     *
     * A child session cannot be driven through `session/prompt` — the server
     * answers `session/agent-busy` ("…is owned by subagent routing") because the
     * parent owns the delegation. Routing here rather than at the call site keeps
     * every write going through the one place that knows the address's kind.
     *
     * @throws DshException with `subagent/not-continuable` for a `one-shot` child,
     *   which has no inbox left to accept a turn.
     */
    suspend fun prompt(
        origin: String,
        address: SessionAddress,
        text: String,
        mode: String = "queue",
    ) {
        when (address) {
            is SessionAddress.Plain -> prompt(origin, address.sessionId, text, mode)
            is SessionAddress.Subagent -> {
                if (!address.continuable) {
                    throw DshException(
                        "这是一次性（one-shot）子代理会话，已经结束，不能再发送消息。",
                        "subagent/not-continuable",
                    )
                }
                promptSubagent(
                    origin = origin,
                    parentSessionId = address.parentSessionId,
                    childSessionId = address.childSessionId,
                    text = text,
                    delivery = mode,
                )
            }
        }
    }

    /**
     * Stop whatever session [address] names.
     *
     * Same split as [prompt]: a child's turn is cancelled through
     * `subagents/interruptByParent`, which authorizes against the live
     * delegation rather than the child's own session id.
     */
    suspend fun cancel(origin: String, address: SessionAddress) {
        when (address) {
            is SessionAddress.Plain -> cancel(origin, address.sessionId)
            is SessionAddress.Subagent -> interruptSubagent(
                origin = origin,
                parentSessionId = address.parentSessionId,
                childSessionId = address.childSessionId,
            )
        }
    }

    companion object {
        private const val TAG = "DshClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** How many times a broken mux stream is re-opened before giving up. */
        private const val MAX_STREAM_RETRIES = 5

        /** Linear backoff step; the phone is normally back within a few seconds. */
        private const val RETRY_BASE_MS = 900L

        private fun baseNameOf(path: String): String =
            path.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifBlank { path }
    }
}
