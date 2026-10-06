package ai.deepseek.dshmobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.deepseek.dshmobile.DshApp
import ai.deepseek.dshmobile.data.Backend
import ai.deepseek.dshmobile.data.Block
import ai.deepseek.dshmobile.data.DshClient
import ai.deepseek.dshmobile.data.LiveAssistant
import ai.deepseek.dshmobile.data.Message
import ai.deepseek.dshmobile.data.Role
import ai.deepseek.dshmobile.data.SessionParser
import ai.deepseek.dshmobile.net.ChatApi
import ai.deepseek.dshmobile.net.GatewayClient
import ai.deepseek.dshmobile.net.StreamEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** A session row in the drawer. */
data class SessionRow(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val cwd: String?,
    val running: Boolean,
)

/** A desktop found by scanning the local network. */
data class DiscoveredServer(
    val baseUrl: String,
    val name: String,
    val deviceCount: Int,
)

data class ChatUiState(
    val connecting: Boolean = false,
    val messages: List<Message> = emptyList(),
    /** Provisional assistant draft fed by `assistant-stream` frames. */
    val liveDraft: Message? = null,
    val sessions: List<SessionRow> = emptyList(),
    val activeSessionId: String? = null,
    val activeTitle: String = "",
    val running: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val models: List<String> = emptyList(),
    val selectedModel: String = "",
    val backend: Backend = Backend.REMOTE,
    val connected: Boolean = false,
    val sessionCursor: Long = 0L,
    /** True while scanning the LAN for a desktop. */
    val searching: Boolean = false,
    val discovered: List<DiscoveredServer> = emptyList(),
    /** True when the current connection goes through the dsh-connect gateway. */
    val viaGateway: Boolean = false,
)

/**
 * Drives the chat screen for both back ends.
 *
 * REMOTE mode mirrors a running Harness: it follows the session event log over
 * the remote mux and renders the same transcript the desktop GUI shows. API mode
 * runs a local conversation straight against an OpenAI-compatible endpoint.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val dsh: DshClient = (app as DshApp).client
    private val prefs = (app as DshApp).prefs
    private val api = ChatApi()
    private val gateway = GatewayClient()

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var followJob: Job? = null
    private val seenSeqs = mutableSetOf<Long>()

    init {
        _state.value = _state.value.copy(
            backend = prefs.backend,
            selectedModel = prefs.apiModel,
            viaGateway = prefs.usesGateway,
        )
    }

    // ------------------------------------------------------------------ config

    val serverUrl: String get() = prefs.serverUrl
    val apiKey: String get() = prefs.apiKey
    val apiBaseUrl: String get() = prefs.apiBaseUrl
    val apiSystemPrompt: String get() = prefs.apiSystemPrompt
    val sessionCookie: String get() = prefs.sessionCookie
    val updateManifestUrl: String get() = prefs.updateManifestUrl

    fun setUpdateManifestUrl(url: String) {
        prefs.updateManifestUrl = url
    }

    fun setBackend(backend: Backend) {
        prefs.backend = backend
        _state.value = _state.value.copy(backend = backend, error = null)
        if (backend == Backend.REMOTE) refreshSessions()
    }

    // ----------------------------------------------------------------- pairing

    /**
     * Pair with the `dsh-connect` gateway using the 6-digit code the desktop
     * shows. This is the supported way to connect: no port forwarding, and the
     * phone never handles a Harness session.
     */
    fun pairWithCode(address: String, code: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(connecting = true, error = null, info = null)
            try {
                var origin = address.trim().trimEnd('/')
                if (origin.isEmpty()) throw IllegalArgumentException("请填写桌面端显示的连接地址")
                if (!origin.startsWith("http://") && !origin.startsWith("https://")) {
                    origin = "http://$origin"
                }
                // Accept "192.168.1.5" without a port by assuming the default.
                if (origin.removePrefix("http://").removePrefix("https://").contains(':').not()) {
                    origin = "$origin:${GatewayClient.DEFAULT_PORT}"
                }

                val token = gateway.pair(origin, code, deviceName())
                prefs.serverUrl = origin
                prefs.deviceToken = token
                prefs.sessionCookie = ""
                prefs.backend = Backend.REMOTE
                prefs.serverName = origin

                _state.value = _state.value.copy(
                    connecting = false,
                    connected = true,
                    backend = Backend.REMOTE,
                    info = "已连接到 $origin",
                )
                refreshSessions()
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    connecting = false,
                    connected = false,
                    error = t.message ?: "配对失败",
                )
            }
        }
    }

    /** Scan the local network for a desktop running the plugin. */
    fun discoverGateways() {
        viewModelScope.launch {
            _state.value = _state.value.copy(searching = true, error = null, discovered = emptyList())
            try {
                val ip = localIpv4()
                if (ip == null) {
                    _state.value = _state.value.copy(
                        searching = false,
                        error = "没有找到本机局域网地址。请确认手机已连上 Wi-Fi。",
                    )
                    return@launch
                }
                val found = gateway.discover(ip)
                _state.value = _state.value.copy(
                    searching = false,
                    discovered = found.map { DiscoveredServer(it.baseUrl, it.info.name, it.info.deviceCount) },
                    error = if (found.isEmpty()) {
                        "没有找到桌面端。请确认电脑上已启用 dsh-connect，且与手机在同一个 Wi-Fi。"
                    } else {
                        null
                    },
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    searching = false,
                    error = t.message ?: "搜索失败",
                )
            }
        }
    }

    /** The phone's own IPv4 address, used to derive the subnet to scan. */
    private fun localIpv4(): String? {
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }

    private fun deviceName(): String =
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim().ifBlank { "Android 设备" }

    /** Disconnect from the current desktop and forget its token. */
    fun unpair() {
        prefs.deviceToken = ""
        prefs.sessionCookie = ""
        _state.value = _state.value.copy(
            connected = false,
            sessions = emptyList(),
            activeSessionId = null,
            messages = emptyList(),
            info = "已断开连接",
        )
    }

    /**
     * Pair by pasting the URL `dsh web` printed.
     *
     * This is the direct path, kept for a desktop reached through a tunnel or
     * port forward where the plugin is not in play.
     */
    fun pair(rawInput: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(connecting = true, error = null, info = null)
            try {
                val (origin, token) = parsePairingInput(rawInput)
                prefs.serverUrl = origin
                // The direct path replaces any gateway token.
                prefs.deviceToken = ""
                val cookie = dsh.pair(origin, token)
                prefs.sessionCookie = cookie
                prefs.serverName = origin
                prefs.backend = Backend.REMOTE
                _state.value = _state.value.copy(
                    connecting = false,
                    connected = true,
                    backend = Backend.REMOTE,
                    viaGateway = false,
                    info = "已连接到 $origin",
                )
                refreshSessions()
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    connecting = false,
                    connected = false,
                    error = t.message ?: "配对失败",
                )
            }
        }
    }

    /** Accept `http://host:port/?token=x`, `host:port?token=x`, or a bare link. */
    private fun parsePairingInput(input: String): Pair<String, String> {
        val text = input.trim()
        if (text.isEmpty()) throw IllegalArgumentException("请输入服务器地址或配对链接")

        val tokenMatch = Regex("[?&]token=([^&\\s]+)").find(text)
        val token = tokenMatch?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            .orEmpty()

        var origin = if (tokenMatch != null) text.substring(0, tokenMatch.range.first) else text
        origin = origin.trim().trimEnd('/')
        if (origin.isEmpty()) throw IllegalArgumentException("无法从链接中解析出服务器地址")
        if (!origin.startsWith("http://") && !origin.startsWith("https://")) {
            origin = "http://$origin"
        }
        origin = origin.substringBefore("?").substringBefore("#").trimEnd('/')

        if (token.isBlank()) {
            throw IllegalArgumentException("缺少 token。请粘贴 `dsh web` 打印的完整链接。")
        }
        return origin to token
    }

    /**
     * Reconnect on launch using whatever credential is already stored.
     *
     * Silent by design: a user who paired once should just see their sessions.
     * A failure is reported once, without a modal, because the fix is a
     * deliberate trip to settings.
     */
    fun autoConnect() {
        if (prefs.backend != Backend.REMOTE) return
        val origin = prefs.serverUrl
        if (origin.isBlank()) return
        if (prefs.deviceToken.isBlank() && prefs.sessionCookie.isBlank()) return

        viewModelScope.launch {
            _state.value = _state.value.copy(connecting = true)
            val ok = dsh.verifySession(origin)
            _state.value = _state.value.copy(
                connecting = false,
                connected = ok,
                viaGateway = prefs.usesGateway,
                error = if (ok) null else "连不上上次的桌面端。请确认电脑开着，或重新配对。",
            )
            if (ok) refreshSessions()
        }
    }

    /** Remember a discovered address so the user only has to type the code. */
    fun setServerAddress(baseUrl: String) {
        _state.value = _state.value.copy(discovered = emptyList())
        prefs.serverUrl = baseUrl.trim().trimEnd('/')
    }

    /** Re-verify a stored credential without asking for the code again. */
    fun reconnect() {
        val origin = prefs.serverUrl
        if (origin.isBlank()) {
            _state.value = _state.value.copy(error = "尚未配置服务器地址")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(connecting = true, error = null)
            val ok = dsh.verifySession(origin)
            _state.value = _state.value.copy(
                connecting = false,
                connected = ok,
                viaGateway = prefs.usesGateway,
                error = if (ok) {
                    null
                } else if (prefs.usesGateway) {
                    "连接已失效。请在桌面端重新生成配对码，然后重新配对。"
                } else {
                    "会话已失效，请用 `dsh web` 的新链接重新配对。"
                },
            )
            if (ok) refreshSessions()
        }
    }

    // ---------------------------------------------------------------- sessions

    fun refreshSessions() {
        val origin = prefs.serverUrl
        if (origin.isBlank()) return
        viewModelScope.launch {
            try {
                val list = dsh.listSessions(origin)
                _state.value = _state.value.copy(
                    sessions = list.map {
                        SessionRow(it.id, it.title, it.updatedAt, it.cwd, it.running)
                    },
                    connected = true,
                    error = null,
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    error = t.message ?: "无法获取会话列表",
                    connected = false,
                )
            }
        }
    }

    fun openSession(sessionId: String) {
        followJob?.cancel()
        seenSeqs.clear()
        val row = _state.value.sessions.firstOrNull { it.id == sessionId }
        _state.value = _state.value.copy(
            activeSessionId = sessionId,
            activeTitle = row?.title ?: "会话",
            messages = emptyList(),
            liveDraft = null,
            running = row?.running == true,
            error = null,
            sessionCursor = 0L,
        )
        startFollowing(sessionId)
    }

    fun createSession() {
        val origin = prefs.serverUrl
        viewModelScope.launch {
            try {
                val id = dsh.createSession(origin)
                refreshSessions()
                openSession(id)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(error = t.message ?: "创建会话失败")
            }
        }
    }

    /**
     * Subscribe to the durable log plus live token deltas.
     *
     * `session/follow` yields three item kinds: `snapshot` (backlog), `event`
     * (incremental durable events) and `assistant-stream` (provisional chunks
     * that the next durable `assistant/message` supersedes).
     */
    private fun startFollowing(sessionId: String) {
        val origin = prefs.serverUrl
        val live = LiveAssistant()

        followJob = viewModelScope.launch {
            _state.value = _state.value.copy(connecting = true)
            // Turn tracking is kept across batches: a turn opened in one batch
            // and closed in a later one must still resolve correctly.
            var openTurn: Int? = null

            dsh.followSession(origin, sessionId)
                .catch { t ->
                    _state.value = _state.value.copy(
                        connecting = false,
                        error = t.message ?: "订阅会话失败",
                    )
                }
                .collect { item ->
                    // ---- durable events ----------------------------------
                    val events = SessionParser.eventsOf(item)
                    if (events.isNotEmpty()) {
                        val fresh = events.filter { e ->
                            val seq = e.optLong("seq", -1L)
                            seq < 0 || seenSeqs.add(seq)
                        }
                        if (fresh.isNotEmpty()) {
                            for (e in fresh) {
                                when (e.optString("type")) {
                                    "turn/start" ->
                                        openTurn = e.optJSONObject("data")?.optInt("turn", 0) ?: 0
                                    "turn/end" -> openTurn = null
                                }
                            }
                            val folded = SessionParser.fold(fresh, _state.value.messages)
                            // A durable assistant message supersedes the draft.
                            val superseded =
                                fresh.any { it.optString("type") == "assistant/message" }
                            if (superseded) live.clear()

                            _state.value = _state.value.copy(
                                messages = folded.messages,
                                liveDraft = if (superseded) null else _state.value.liveDraft,
                                running = openTurn != null,
                                connecting = false,
                                sessionCursor = folded.lastSeq,
                                activeTitle = folded.title ?: _state.value.activeTitle,
                            )
                        }
                    }

                    // ---- live token deltas -------------------------------
                    SessionParser.streamFrameOf(item)?.let { frame ->
                        if (live.apply(frame)) {
                            _state.value = _state.value.copy(
                                liveDraft = live.toMessage(),
                                running = true,
                            )
                        }
                    }
                }
        }
    }

    // -------------------------------------------------------------------- chat

    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        when (_state.value.backend) {
            Backend.REMOTE -> sendRemote(body)
            Backend.API -> sendApi(body)
        }
    }

    private fun sendRemote(text: String) {
        val origin = prefs.serverUrl
        val existing = _state.value.activeSessionId
        viewModelScope.launch {
            try {
                val sessionId: String = existing ?: dsh.createSession(origin).also { created ->
                    _state.value = _state.value.copy(activeSessionId = created)
                    startFollowing(created)
                }
                _state.value = _state.value.copy(
                    messages = _state.value.messages + Message(
                        id = "local-${System.currentTimeMillis()}",
                        role = Role.USER,
                        blocks = listOf(Block.Text(text)),
                        time = System.currentTimeMillis(),
                    ),
                    running = true,
                    error = null,
                )
                dsh.prompt(origin, sessionId, text)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    running = false,
                    error = t.message ?: "发送失败",
                )
            }
        }
    }

    private fun sendApi(text: String) {
        if (prefs.apiKey.isBlank()) {
            _state.value = _state.value.copy(error = "请先在设置中填写 API Key")
            return
        }
        viewModelScope.launch {
            val history = _state.value.messages.toMutableList()
            history += Message(
                id = "u-${System.currentTimeMillis()}",
                role = Role.USER,
                blocks = listOf(Block.Text(text)),
                time = System.currentTimeMillis(),
            )
            val assistantId = "a-${System.currentTimeMillis()}"
            history += Message(id = assistantId, role = Role.ASSISTANT, streaming = true)
            _state.value = _state.value.copy(messages = history, running = true, error = null)

            val payload = buildApiMessages(history.dropLast(1))
            api.streamCompletion(
                baseUrl = prefs.apiBaseUrl,
                apiKey = prefs.apiKey,
                model = prefs.apiModel,
                messages = payload,
            ).catch { t ->
                finishApi(assistantId, t.message ?: "请求失败")
            }.collect { ev ->
                when (ev) {
                    is StreamEvent.Delta -> appendAssistant(assistantId, ev.text, reasoning = false)
                    is StreamEvent.Reasoning -> appendAssistant(assistantId, ev.text, reasoning = true)
                    is StreamEvent.Done -> finishApi(assistantId, null)
                    is StreamEvent.Failed -> finishApi(assistantId, ev.message)
                }
            }
        }
    }

    private fun finishApi(assistantId: String, error: String?) {
        _state.value = _state.value.copy(
            running = false,
            error = error ?: _state.value.error,
            messages = _state.value.messages.map {
                if (it.id == assistantId) it.copy(streaming = false) else it
            },
        )
    }

    private fun buildApiMessages(history: List<Message>): List<ChatApi.Msg> {
        val out = mutableListOf<ChatApi.Msg>()
        prefs.apiSystemPrompt.takeIf { it.isNotBlank() }?.let { out += ChatApi.Msg("system", it) }
        for (m in history) {
            val text = m.plainText
            if (text.isBlank()) continue
            when (m.role) {
                Role.USER -> out += ChatApi.Msg("user", text)
                Role.ASSISTANT -> out += ChatApi.Msg("assistant", text)
                else -> Unit
            }
        }
        return out
    }

    private fun appendAssistant(id: String, text: String, reasoning: Boolean) {
        val messages = _state.value.messages.map { m ->
            if (m.id != id) return@map m
            val blocks = m.blocks.toMutableList()
            val last = blocks.lastOrNull()
            if (reasoning) {
                if (last is Block.Reasoning) {
                    blocks[blocks.lastIndex] = Block.Reasoning(last.text + text)
                } else {
                    blocks += Block.Reasoning(text)
                }
            } else {
                if (last is Block.Text) {
                    blocks[blocks.lastIndex] = Block.Text(last.text + text)
                } else {
                    blocks += Block.Text(text)
                }
            }
            m.copy(blocks = blocks, streaming = true)
        }
        _state.value = _state.value.copy(messages = messages)
    }

    fun stop() {
        when (_state.value.backend) {
            Backend.REMOTE -> {
                val origin = prefs.serverUrl
                val id = _state.value.activeSessionId
                if (id != null) {
                    viewModelScope.launch {
                        runCatching { dsh.cancel(origin, id) }
                            .onFailure { _state.value = _state.value.copy(error = it.message) }
                    }
                }
            }
            Backend.API -> api.cancel()
        }
        _state.value = _state.value.copy(running = false)
    }

    // ------------------------------------------------------------------ models

    fun loadModels() {
        viewModelScope.launch {
            val key = prefs.apiKey
            if (key.isBlank()) return@launch
            runCatching { api.listModels(prefs.apiBaseUrl, key) }
                .onSuccess { _state.value = _state.value.copy(models = it) }
        }
    }

    fun selectModel(model: String) {
        prefs.apiModel = model
        _state.value = _state.value.copy(selectedModel = model)
    }

    fun setServerUrl(url: String) {
        prefs.serverUrl = url.trim().trimEnd('/')
    }

    fun setApiConfig(baseUrl: String, key: String, model: String, systemPrompt: String) {
        prefs.apiBaseUrl = baseUrl
        prefs.apiKey = key
        prefs.apiModel = model
        prefs.apiSystemPrompt = systemPrompt
        _state.value = _state.value.copy(selectedModel = model)
    }

    fun renameActive(title: String) {
        val origin = prefs.serverUrl
        val id = _state.value.activeSessionId ?: return
        viewModelScope.launch {
            runCatching { dsh.renameSession(origin, id, title) }
                .onSuccess {
                    _state.value = _state.value.copy(activeTitle = title)
                    refreshSessions()
                }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null, info = null)
    }

    override fun onCleared() {
        followJob?.cancel()
        api.cancel()
        super.onCleared()
    }
}
