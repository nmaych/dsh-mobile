package ai.deepseek.dshmobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.deepseek.dshmobile.DshApp
import ai.deepseek.dshmobile.data.Backend
import ai.deepseek.dshmobile.data.Block
import ai.deepseek.dshmobile.data.CallIndex
import ai.deepseek.dshmobile.data.DshClient
import ai.deepseek.dshmobile.data.DshClient.SessionAddress
import ai.deepseek.dshmobile.data.LiveAssistant
import ai.deepseek.dshmobile.data.Message
import ai.deepseek.dshmobile.data.QuestionAnswer
import ai.deepseek.dshmobile.data.Role
import ai.deepseek.dshmobile.data.SessionParser
import ai.deepseek.dshmobile.data.TokenUsage
import ai.deepseek.dshmobile.data.UserQuestion
import ai.deepseek.dshmobile.net.ChatApi
import ai.deepseek.dshmobile.net.GatewayClient
import ai.deepseek.dshmobile.net.StreamEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** A session row in the drawer. */
data class SessionRow(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val cwd: String?,
    val running: Boolean,
    /** `provider/model` the session last used, when the server reports it. */
    val model: String? = null,
    /**
     * Non-null when this row is a subagent child session.
     *
     * A child cannot be read with a plain `session` address — the server answers
     * "subagent Sessions require their durable parent address" — so the address
     * travels with the row from the moment the list is read.
     */
    val subagent: SessionAddress.Subagent? = null,
) {
    /** The address every call against this session must use. */
    val address: SessionAddress get() = subagent ?: SessionAddress.Plain(id)
}

/** A desktop found by scanning the local network. */
data class DiscoveredServer(
    val baseUrl: String,
    val name: String,
    val deviceCount: Int,
)

/** One selectable model in the picker. */
data class ModelRow(
    val provider: String,
    val providerName: String,
    val id: String,
    val name: String,
    val description: String,
    val efforts: List<String>,
    val defaultEffort: String?,
) {
    val key: String get() = "$provider/$id"
}

/** One selectable workspace in the picker. */
data class WorkspaceRow(
    val id: String,
    val path: String,
    val title: String,
) {
    val label: String get() = title.ifBlank { path.replace('\\', '/').trimEnd('/').substringAfterLast('/') }
}

data class ChatUiState(
    val connecting: Boolean = false,
    val messages: List<Message> = emptyList(),
    /** Provisional assistant draft fed by `assistant-stream` frames. */
    val liveDraft: Message? = null,
    val sessions: List<SessionRow> = emptyList(),
    val activeSessionId: String? = null,
    /**
     * How to address the active session on the server.
     *
     * Null only before any session is open. A subagent child must be addressed
     * through its parent, so this cannot be reconstructed from
     * [activeSessionId] alone.
     */
    val activeAddress: SessionAddress? = null,
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
    /** True when the current connection goes through the dsh-mobile-connect gateway. */
    val viaGateway: Boolean = false,
    /** True once the first snapshot of the active session has been folded. */
    val historyLoaded: Boolean = false,
    // ---- remote model selection -------------------------------------------
    val remoteModels: List<ModelRow> = emptyList(),
    val loadingModels: Boolean = false,
    /** `provider/model` currently in effect for the active session. */
    val activeModel: String = "",
    /** Reasoning effort chosen for [activeModel], when the model supports it. */
    val activeEffort: String = "",
    /** True when a model change is in flight. */
    val switchingModel: Boolean = false,
    // ---- workspaces --------------------------------------------------------
    val workspaces: List<WorkspaceRow> = emptyList(),
    val loadingWorkspaces: Boolean = false,
    /** Workspace the next new session will be created in. */
    val selectedWorkspaceId: String = "",
    /** Workspace the active session belongs to, when known. */
    val activeWorkspaceId: String = "",
    // ---- token usage -------------------------------------------------------
    /**
     * The session's cumulative provider totals, as the server reports them.
     *
     * Only ever written from the `tokenUsage` projection — never from a single
     * settlement, whose `usage` covers one attempt. Mixing the two made the
     * counter jump *down* mid-turn and back up when the turn settled.
     */
    val usage: TokenUsage = TokenUsage(),
    /**
     * Usage reported so far by the in-flight attempt.
     *
     * The projection cannot include an attempt that has not settled, so this is
     * shown on top of [usage] while a turn runs and cleared when it settles.
     */
    val liveUsage: TokenUsage = TokenUsage(),
    /** Tokens the next request is expected to occupy, and the model's window. */
    val contextTokens: Long? = null,
    val contextWindow: Long? = null,
    /** True when the server reported any usage, so the chip can hide otherwise. */
    val usageKnown: Boolean = false,
    /**
     * True when [error] came from the transport rather than from the desktop.
     *
     * A mux socket failure is *self-healing*: `DshClient.stream` re-opens the
     * stream on a fresh socket with backoff, and `session/follow` replays its
     * backlog. So a transport error is a statement about a connection that may
     * already have been replaced by the time the user reads it, and it must be
     * retracted when the socket comes back. A server-side error is not
     * self-healing and must stay until the user dismisses it.
     */
    val errorIsTransport: Boolean = false,
    /**
     * The `ask_user_question` requests the agent is waiting on, oldest first.
     *
     * Non-empty means a turn is *blocked* on a human answer: the tool call is
     * suspended inside the host's waterfall, so the transcript cannot progress
     * until these are answered or the host's waits expire. The UI must therefore
     * present them as a prompt rather than as another transcript row.
     *
     * A list rather than a single slot because the host delivers each waterfall
     * exactly once per stream: a question that arrived while another was on
     * screen would otherwise have to be dropped, and dropping it means it can
     * never be answered — the host will not re-send it, so the tool call would sit
     * blocked until its wait expired. Only the first is shown; the rest follow as
     * they are answered.
     */
    val pendingQuestions: List<UserQuestion> = emptyList(),
) {
    /** The question the UI is currently asking, if any. */
    val pendingQuestion: UserQuestion? get() = pendingQuestions.firstOrNull()

    /** What the usage chip renders: settled totals plus the in-flight attempt. */
    val displayUsage: TokenUsage get() = usage + liveUsage
}

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
    private var usageJob: Job? = null
    /**
     * The live `session/control` subscription.
     *
     * One stream for the whole host, kept open while a session is active, so the
     * usage chip tracks the desktop *during* a turn instead of only at its end.
     */
    private var controlJob: Job? = null
    /**
     * The `$events` subscription that carries `ask_user_question` requests.
     *
     * Opened once for the whole host, not per session: the stream carries
     * forwarded events for every agent, each frame naming its own `agentId`, and
     * re-opening it per session would drop the pending questions that the
     * gateway back-fills to a newly opened stream.
     */
    private var eventsJob: Job? = null
    /**
     * The `clientId` of the live `$events` stream.
     *
     * Assigned by the host in the stream's opening `ready` frame and required on
     * every answer, so it is captured here the moment it arrives. It is cleared
     * whenever the stream is (re)opened, because a `clientId` from a dead stream
     * identifies nothing — the gateway looks the pair up in a registry keyed by
     * the live stream.
     */
    @Volatile private var eventsClientId: String = ""
    /** The in-flight workspace list load, superseded by a newer request. */
    private var workspaceRequest: Job? = null

    /**
     * Highest `seq` seen per projection key, for the active session.
     *
     * `session/control` is a live push, so frames can arrive out of order
     * relative to a one-shot `session/projections` read taken at the same time.
     * The host's own client resolves that by `seq` — higher wins — and this
     * mirrors it. Cleared with the session, because `seq` is per-session.
     */
    private val projectionSeqs = mutableMapOf<String, Long>()

    /**
     * Serializes session creation.
     *
     * `send`, `createSession` and `selectRemoteModel` all create a session when
     * none is active, and each does so after a network round trip. Two quick taps
     * on Send would otherwise both observe "no active session" and create two —
     * leaving one as an orphan on the server and discarding the other's
     * transcript when the second `activateSession` reset the view.
     */
    private val sessionCreation = Mutex()
    private val seenSeqs = mutableSetOf<Long>()

    /**
     * `callId -> (messageIndex, blockIndex)` for the whole active session.
     *
     * Kept on the view model, not per `fold` call: a tool result lands in a later
     * batch than the call that created its card, and a per-batch index would not
     * find it — which renders the same tool twice.
     */
    private val callIndex: CallIndex = mutableMapOf()

    init {
        _state.value = _state.value.copy(
            backend = prefs.backend,
            selectedModel = prefs.apiModel,
            viaGateway = prefs.usesGateway,
            selectedWorkspaceId = prefs.workspaceId,
            activeEffort = prefs.reasoningEffort,
        )
        // Retract a transport error the moment the mux comes back.
        //
        // This is the other half of the spurious "连接超时" report, and the half
        // that 1.1.4 missed. A mux failure is not terminal: `DshClient.stream`
        // re-opens the stream on a fresh socket, and the reconnect usually
        // succeeds within a second. But the banner raised by the *failed* attempt
        // stayed on screen afterwards — so the app went on asserting a timeout
        // long after it had reconnected, and the user was left reading a warning
        // about a connection that was, at that moment, demonstrably fine.
        //
        // Only a transport error is cleared this way. A server-side error (a bad
        // model, a rejected prompt) is not self-healing, so reconnecting says
        // nothing about it and it must survive until the user dismisses it.
        viewModelScope.launch {
            dsh.connectionState.collect { connected ->
                if (!connected) return@collect
                val current = _state.value
                // An open mux socket is itself proof the desktop is reachable and
                // the stored credential still authenticates — the handshake
                // required the cookie — so it also clears a `connected = false`
                // left behind by a unary call that failed during the blip.
                // Without this the chips stayed greyed out and the app bar read
                // "未连接" while the transcript streamed normally underneath.
                if (current.errorIsTransport || !current.connected) {
                    _state.value = current.copy(
                        error = if (current.errorIsTransport) null else current.error,
                        errorIsTransport = false,
                        connected = true,
                    )
                }
            }
        }
    }

    /**
     * True when a failure is a transport problem that a reconnect can cure.
     *
     * Keyed on the code [DshClient] assigns, not on the message: the message is
     * user-facing Chinese by then, and matching prose would break the moment the
     * wording changed. Only the transport codes are listed — a server-side
     * failure such as `unauthorized` or `http-500` is not self-healing, so
     * reconnecting must not silently retract it.
     */
    private fun isTransportError(t: Throwable): Boolean {
        val code = (t as? DshClient.DshException)?.code ?: return false
        return code in TRANSPORT_ERROR_CODES
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
        if (backend == Backend.REMOTE) {
            refreshSessions()
            refreshWorkspaces()
        } else {
            loadModels()
        }
    }

    // ----------------------------------------------------------------- pairing

    /**
     * Pair with the `dsh-mobile-connect` gateway using the 6-digit code the desktop
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

                // `installId` identifies this installation, so re-pairing
                // replaces this phone's row on the desktop instead of leaving a
                // dead duplicate behind: the token below overwrites the stored
                // one, and the old record would otherwise never be usable again.
                val token = gateway.pair(origin, code, deviceName(), prefs.installId)
                // The old session belongs to the old desktop: stop following it
                // before the transport is replaced, so its stream's teardown does
                // not surface as an error over the new connection.
                detachSession()
                // Store the new authority *before* resetting the transport, so the
                // cookie jar re-seeds from the new pairing rather than the old one.
                prefs.serverUrl = origin
                prefs.deviceToken = token
                prefs.sessionCookie = ""
                prefs.backend = Backend.REMOTE
                prefs.serverName = origin
                // Drop the previous authority's socket and cookie.
                dsh.reset()

                _state.value = _state.value.copy(
                    connecting = false,
                    connected = true,
                    backend = Backend.REMOTE,
                    // Mark the transport, not just the fact of being connected.
                    // The settings screen branches on `viaGateway` to decide
                    // between "already paired" and "show the pairing form", so
                    // leaving it false here made a *successful* pairing still
                    // render the code/address form — as if it had not worked.
                    viaGateway = true,
                    info = "已连接到 $origin",
                    error = null,
                )
                refreshSessions()
                refreshWorkspaces()
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    connecting = false,
                    connected = false,
                    error = t.message ?: "配对失败",
                )
            }
        }
    }

    /**
     * Pair using the link carried by the desktop's QR code.
     *
     * Format: `dshmobile://pair?host=…&port=…&code=…`
     *
     * This is the whole point of the QR: scanning it should connect, rather than
     * making the user read an address and a six-digit code off a terminal and
     * type both. A malformed link is reported rather than ignored, because a
     * silent no-op after scanning looks like the app is broken.
     */
    fun pairFromLink(link: String) {
        val uri = runCatching { java.net.URI(link) }.getOrNull()
        if (uri == null || !uri.scheme.equals("dshmobile", true) || !uri.host.equals("pair", true)) {
            _state.value = _state.value.copy(error = "这个二维码不是配对码，无法识别。")
            return
        }

        val params = uri.rawQuery.orEmpty()
            .split('&')
            .mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
            }
            .toMap()

        val host = params["host"]?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val port = params["port"]
        val code = params["code"]?.trim()

        if (host.isNullOrBlank() || code.isNullOrBlank()) {
            _state.value = _state.value.copy(
                error = "二维码里缺少连接地址或配对码，请让电脑重新生成一个。",
            )
            return
        }

        val address = if (port.isNullOrBlank()) host else "$host:$port"
        pairWithCode(address, code)
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
                        "没有找到桌面端。请确认电脑上已启用 dsh-mobile-connect，且与手机在同一个 Wi-Fi。"
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
        detachSession()
        // The stream identity belongs to the old authority; `reset()` fails the
        // stream that carried it, so the id must not be reused.
        eventsJob?.cancel()
        eventsJob = null
        eventsClientId = ""
        // Clear the credentials first: `reset()` re-seeds the cookie jar from
        // prefs, so doing it the other way round would leave the old cookie live.
        prefs.deviceToken = ""
        prefs.sessionCookie = ""
        dsh.reset()
        _state.value = _state.value.copy(
            connected = false,
            sessions = emptyList(),
            workspaces = emptyList(),
            remoteModels = emptyList(),
            pendingQuestions = emptyList(),
            info = "已断开连接",
        )
    }

    /**
     * Stop following the active session and forget everything about it.
     *
     * Called before a re-pair. The old follow stream is riding a socket that is
     * about to be dropped, and its failure would otherwise land as an error
     * banner on top of a connection that just succeeded.
     */
    private fun detachSession() {
        followJob?.cancel()
        usageJob?.cancel()
        controlJob?.cancel()
        followJob = null
        usageJob = null
        controlJob = null
        seenSeqs.clear()
        callIndex.clear()
        projectionSeqs.clear()
        _state.value = _state.value.copy(
            activeSessionId = null,
            activeTitle = "",
            messages = emptyList(),
            liveDraft = null,
            running = false,
            sessionCursor = 0L,
            historyLoaded = false,
            activeModel = "",
            activeWorkspaceId = "",
            usage = TokenUsage(),
            liveUsage = TokenUsage(),
            usageKnown = false,
            contextTokens = null,
            contextWindow = null,
            // The question belonged to the session being left. It is still
            // pending on the host, which re-delivers it when the user returns.
            pendingQuestions = emptyList(),
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
                detachSession()
                // The direct path replaces any gateway token, and the cookie must
                // belong to the new authority. Prefs are written first so the
                // cookie jar re-seeds against the new origin.
                prefs.serverUrl = origin
                prefs.deviceToken = ""
                prefs.sessionCookie = ""
                dsh.reset()
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
                    error = null,
                )
                refreshSessions()
                refreshWorkspaces()
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
        if (prefs.backend != Backend.REMOTE) {
            loadModels()
            return
        }
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
                // Do not clobber a more specific error raised by a failed
                // session/workspace load that raced this probe.
                error = if (ok) _state.value.error
                else "连不上上次的桌面端。请确认电脑开着，或重新配对。",
            )
            if (ok) {
                refreshSessions()
                refreshWorkspaces()
                // Only worth subscribing once the credential is known good; the
                // stream is admitted by the same fence as every other `/api` call.
                startEvents()
            }
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
        // The conversation the user was reading is restored after the transport
        // is rebuilt, so a reconnect is not also a navigation away from it.
        val reopen = _state.value.activeSessionId
        val reopenTitle = _state.value.activeTitle
        viewModelScope.launch {
            _state.value = _state.value.copy(connecting = true, error = null)
            // A stale mux socket from a previous desktop would answer nothing;
            // start from a clean transport on every explicit reconnect. Detaching
            // first stops the old follow stream's teardown from reporting a
            // failure over the connection that is about to succeed.
            detachSession()
            dsh.reset()
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
            if (ok) {
                refreshSessions()
                refreshWorkspaces()
                startEvents()
                if (reopen != null) activateSession(reopen, reopenTitle.ifBlank { "会话" }, false)
            }
        }
    }

    // ---------------------------------------------------------------- sessions

    fun refreshSessions() {
        val origin = prefs.serverUrl
        if (origin.isBlank()) return
        // Captured so a slow response that lands after the user has switched
        // sessions cannot stamp the *new* session's chips with the old one's
        // model or workspace. The session list itself is always applied: it is
        // not specific to whichever session was open when the call started.
        val requestedFor = _state.value.activeSessionId
        viewModelScope.launch {
            try {
                val list = dsh.listSessions(origin)
                val stillOnRequested = _state.value.activeSessionId == requestedFor
                val active = list.firstOrNull { it.id == requestedFor }
                _state.value = _state.value.copy(
                    sessions = list.map {
                        SessionRow(
                            it.id, it.title, it.updatedAt, it.cwd, it.running, it.model,
                            subagent = it.subagent,
                        )
                    },
                    connected = true,
                    activeModel = if (stillOnRequested) {
                        active?.model?.takeIf { it.isNotBlank() } ?: _state.value.activeModel
                    } else {
                        _state.value.activeModel
                    },
                    activeWorkspaceId = if (stillOnRequested) {
                        active?.cwd
                            ?.let { cwd -> _state.value.workspaces.firstOrNull { it.path == cwd }?.id }
                            .orEmpty()
                    } else {
                        _state.value.activeWorkspaceId
                    },
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
        val row = _state.value.sessions.firstOrNull { it.id == sessionId }
        activateSession(
            sessionId,
            row?.title ?: "会话",
            row?.running == true,
            row?.address ?: SessionAddress.Plain(sessionId),
        )
    }

    /**
     * Switch the view model onto one session, from a clean slate.
     *
     * Every path that changes the active session must come through here.
     * `seenSeqs` and `callIndex` are per-session: event `seq` numbers restart at
     * zero for each session, so carrying them across a switch made the new
     * session's first events look like replays and get filtered out — the
     * transcript simply stayed empty, which is exactly what "对话显示异常"
     * looked like. The implicit create inside `sendRemote` used to skip this
     * reset entirely.
     *
     * @param address how the server wants this session addressed. A subagent
     *   child needs its parent's id, so a plain `sessionId` address would be
     *   rejected outright.
     */
    private fun activateSession(
        sessionId: String,
        title: String,
        running: Boolean,
        address: SessionAddress = SessionAddress.Plain(sessionId),
    ) {
        followJob?.cancel()
        usageJob?.cancel()
        controlJob?.cancel()
        seenSeqs.clear()
        callIndex.clear()
        projectionSeqs.clear()
        _state.value = _state.value.copy(
            activeSessionId = sessionId,
            activeAddress = address,
            activeTitle = title,
            messages = emptyList(),
            liveDraft = null,
            running = running,
            error = null,
            sessionCursor = 0L,
            historyLoaded = false,
            usage = TokenUsage(),
            liveUsage = TokenUsage(),
            usageKnown = false,
            contextTokens = null,
            contextWindow = null,
            // A question from the previous session must not survive the switch:
            // its `clientId`/`eventId` pair belongs to that session's waterfall.
            pendingQuestions = emptyList(),
        )
        startFollowing(sessionId, address)
        startControl(sessionId)
        refreshUsage(sessionId)
        refreshActiveWorkspace(sessionId)
    }

    /**
     * Create a session, in the selected workspace when one is chosen.
     *
     * `workspaceId` is what actually puts the session in a workspace: passing a
     * `cwd` alone creates a session with that directory but does not file it
     * under the workspace, so the desktop sidebar would not show it there.
     *
     * The two are **mutually exclusive** on the wire — the server answers
     * `session.create accepts workspaceId or cwd, not both` — so exactly one is
     * sent. See [DshClient.createSession].
     */
    fun createSession() {
        val origin = prefs.serverUrl
        viewModelScope.launch {
            try {
                // Serialized against the implicit create in `send`, so a tap on
                // "+" and a send cannot both create a session and then fight over
                // which one the view is showing.
                val id = sessionCreation.withLock { createSessionLocked(origin) }
                refreshSessions()
                activateSession(id, "新会话", false)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(error = t.message ?: "创建会话失败")
            }
        }
    }

    /**
     * Create a session on the server. The caller must hold [sessionCreation].
     *
     * A selected workspace is named by `workspaceId` **instead of** its path, not
     * in addition to it: the server refuses a request carrying both, and a
     * workspace already implies its directory (`cwd = workspace.path`), so the
     * path would be redundant even if it were accepted. Falling back to `cwd`
     * only when nothing is selected is what keeps "new session in the chosen
     * project" and "new session in a bare directory" the same single call.
     */
    private suspend fun createSessionLocked(origin: String): String {
        val workspaceId = _state.value.selectedWorkspaceId
        return dsh.createSession(
            origin,
            cwd = if (workspaceId.isBlank()) selectedWorkspacePath() else null,
            workspaceId = workspaceId,
        )
    }

    /** The directory of the selected workspace, when one is selected. */
    private fun selectedWorkspacePath(): String? =
        _state.value.workspaces.firstOrNull { it.id == _state.value.selectedWorkspaceId }?.path

    /**
     * Subscribe to the durable log plus live token deltas.
     *
     * `session/follow` yields three item kinds: `snapshot` (backlog), `event`
     * (incremental durable events) and `assistant-stream` (provisional chunks
     * that the next durable `assistant/message` supersedes).
     *
     * @param address the durable address of the session. Passing a bare
     *   `sessionId` for a subagent child is what produced
     *   "subagent Sessions require their durable parent address".
     */
    private fun startFollowing(sessionId: String, address: SessionAddress) {
        val origin = prefs.serverUrl
        val live = LiveAssistant()

        followJob = viewModelScope.launch {
            _state.value = _state.value.copy(connecting = true)
            // Turn tracking is kept across batches: a turn opened in one batch
            // and closed in a later one must still resolve correctly.
            var openTurn: Int? = null
            // Tracks the turn boundary so the usage projection is re-read exactly
            // once, when a turn settles — not on a timer.
            var wasRunning = false

            dsh.followSession(origin, address)
                .catch { t ->
                    // `historyLoaded` is set even on failure: leaving it false
                    // would keep the loading spinner up behind the error banner,
                    // as though the transcript were still coming.
                    //
                    // A socket failure is flagged as a transport error so the
                    // reconnect can retract it. `DshClient.stream` has already
                    // given up by the time this runs (it exhausted its own
                    // retries), so the banner is honest *now* — but the next
                    // successful socket makes it stale, and only the flag lets
                    // the view model know it is allowed to clear it.
                    _state.value = _state.value.copy(
                        connecting = false,
                        historyLoaded = true,
                        error = t.message ?: "订阅会话失败",
                        errorIsTransport = isTransportError(t),
                    )
                }
                .collect { item ->
                    // A `snapshot` is the backlog, and it arrives even for a
                    // brand-new session with no records. Marking the history
                    // loaded here — rather than only when events are folded —
                    // is what stops a fresh session from showing a spinner
                    // forever: there is nothing to fold, but the backlog *is*
                    // complete.
                    if (item.optString("type") == "snapshot") {
                        _state.value = _state.value.copy(
                            historyLoaded = true,
                            connecting = false,
                        )
                    }

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
                            val (durable, echoes) = splitEchoes(_state.value.messages)
                            val folded = SessionParser.fold(fresh, durable, callIndex)
                            // A durable assistant message supersedes the draft.
                            val superseded =
                                fresh.any { it.optString("type") == "assistant/message" }
                            if (superseded) live.clear()

                            // Drop the echo of any user message the server has now
                            // committed, then put the survivors back on the end so
                            // they stay after the durable rows.
                            val reconciled = folded.messages + reconcileEchoes(echoes, fresh)

                            // The durable settlement's own `usage` is *not* added
                            // to the total here: the projection that `refreshUsage`
                            // reads already accounts for it, and adding both would
                            // double-count. It is only used to know that a figure
                            // exists, so the chip stops showing a bare zero.
                            val settled = folded.usage != null
                            // `assistant/attempt` is a settlement too — it carries
                            // the usage of a failed or cancelled attempt, and the
                            // server's `tokenUsage` fold counts it. Clearing only
                            // on `assistant/message` left an abandoned attempt's
                            // tokens in `liveUsage`, where `refreshUsage` then
                            // added the same attempt again through the total.
                            val settledAttempt = fresh.any {
                                val type = it.optString("type")
                                type == "assistant/message" || type == "assistant/attempt"
                            }
                            val running = openTurn != null
                            _state.value = _state.value.copy(
                                messages = reconciled,
                                liveDraft = if (superseded) null else _state.value.liveDraft,
                                running = running,
                                connecting = false,
                                sessionCursor = folded.lastSeq,
                                activeTitle = folded.title ?: _state.value.activeTitle,
                                historyLoaded = true,
                                activeModel = folded.model ?: _state.value.activeModel,
                                // The attempt is over, so its live figure must not
                                // linger on top of the refreshed total.
                                liveUsage = if (settledAttempt) TokenUsage() else _state.value.liveUsage,
                                usageKnown = settled || _state.value.usageKnown,
                            )
                            // A settled turn is when the server commits its usage,
                            // so this is the one moment worth re-reading the
                            // projection — it is the only authoritative total.
                            if (wasRunning && !running) refreshUsage(sessionId)
                            wasRunning = running
                        }
                    }

                    // ---- live token deltas -------------------------------
                    SessionParser.streamFrameOf(item)?.let { frame ->
                        // `apply` returns false for a `start` frame (it opens an
                        // attempt rather than changing the draft), so the changed
                        // check and the attempt-start check are kept separate.
                        val changed = live.apply(frame)
                        val isAttemptStart = frame.optString("type") == "start"
                        if (changed) {
                            _state.value = _state.value.copy(
                                liveDraft = live.toMessage(),
                                running = true,
                                historyLoaded = true,
                                // Shown *in addition to* the settled total, and
                                // dropped as soon as the settlement supersedes it.
                                liveUsage = live.lastUsage ?: _state.value.liveUsage,
                                usageKnown = live.lastUsage != null || _state.value.usageKnown,
                            )
                            wasRunning = true
                        } else if (isAttemptStart) {
                            // A new attempt starts from zero, and `LiveAssistant`
                            // has just cleared its own figure. Keeping the view's
                            // copy would show the previous attempt's tokens on top
                            // of the total until the retry reported its own.
                            _state.value = _state.value.copy(liveUsage = TokenUsage())
                        }
                    }
                }
        }
    }

    /**
     * Subscribe to the host's live projection state.
     *
     * This is what makes the token counter update *in real time*, the way the
     * desktop's does. The one-shot [refreshUsage] read only ever ran at a turn
     * boundary, so on a long turn the chip sat on a stale number and then jumped
     * — while the desktop, subscribed to this same stream, counted up as the
     * tokens were billed.
     *
     * The stream is host-wide, so it is opened once and the frames are filtered
     * to the active session. Two frame shapes matter here:
     *
     *  - `baseline` carries `value.projections`, keyed by session id, each with
     *    `asOfSeq` and `values`. It is the opening state and arrives even for a
     *    session that has never run a turn.
     *  - `projection` carries one `{sessionId, key, value, seq}` replacement.
     *
     * `seq` is compared per key and only a strictly newer frame is applied. A
     * frame that is older than what the one-shot read already applied would
     * otherwise walk the counter backwards, which is exactly the "counter jumps
     * down mid-turn" bug that `usage` and `liveUsage` are kept separate to avoid.
     */
    private fun startControl(sessionId: String) {
        val origin = prefs.serverUrl
        controlJob?.cancel()
        projectionSeqs.clear()
        controlJob = viewModelScope.launch {
            dsh.sessionControl(origin)
                // A dropped control stream is not worth an error banner: it is a
                // convenience feed, and `refreshUsage` still runs at every turn
                // boundary, so the chip stays correct without it. The stream
                // itself already retries the socket underneath.
                .catch { }
                .collect { frame ->
                    if (_state.value.activeSessionId != sessionId) return@collect
                    when (frame.optString("type")) {
                        "baseline" -> {
                            val blocks = frame.optJSONObject("value")
                                ?.optJSONObject("projections")
                                ?: return@collect
                            val block = blocks.optJSONObject(sessionId) ?: return@collect
                            val values = block.optJSONObject("values") ?: return@collect
                            val asOf = block.optLong("asOfSeq", 0L)
                            // The baseline is authoritative for every key it
                            // carries, so it seeds the watermarks directly.
                            for (key in listOf("tokenUsage", "contextPressure")) {
                                projectionSeqs[key] = asOf
                            }
                            applyProjection(values.optJSONObject("tokenUsage"), values.optJSONObject("contextPressure"))
                        }

                        "projection" -> {
                            if (frame.optString("sessionId") != sessionId) return@collect
                            val key = frame.optString("key")
                            if (key != "tokenUsage" && key != "contextPressure") return@collect
                            val seq = frame.optLong("seq", 0L)
                            // Strictly newer only: an equal seq is the same write
                            // arriving twice, and applying it again is harmless
                            // but pointless.
                            if (seq <= (projectionSeqs[key] ?: Long.MIN_VALUE)) return@collect
                            projectionSeqs[key] = seq
                            val value = if (frame.isNull("value")) null else frame.optJSONObject("value")
                            when (key) {
                                "tokenUsage" -> applyProjection(value, null)
                                else -> applyProjection(null, value)
                            }
                        }
                    }
                }
        }
    }

    /**
     * Fold one or both projection values into the chip's state.
     *
     * A null argument means "this frame did not carry that key", not "the value
     * is empty" — the caller passes null for whichever projection the frame is
     * not about, and the existing value is preserved.
     *
     * A *present but null* `tokenUsage` is a real thing (the host can clear a
     * projection), and it maps to a zeroed total rather than being ignored.
     */
    private fun applyProjection(tokenUsage: JSONObject?, contextPressure: JSONObject?) {
        val current = _state.value
        var next = current
        if (tokenUsage != null) {
            next = next.copy(usage = DshClient.UsageSnapshot.usageOf(tokenUsage), usageKnown = true)
        }
        if (contextPressure != null) {
            val (tokens, window) = DshClient.UsageSnapshot.pressureOf(contextPressure)
            next = next.copy(contextTokens = tokens, contextWindow = window)
        }
        if (next !== current) _state.value = next
    }

    /**
     * Re-read the session's usage projection.
     *
     * The projection is authoritative and cheap, but it is a separate call from
     * the event stream, so it is refreshed on open and after each turn instead of
     * being derived locally — a locally summed total drifts as soon as a retry or
     * a compaction replaces an attempt.
     *
     * The live [startControl] stream keeps the chip current *during* a turn; this
     * remains the authority at the boundaries, and the only source when the host
     * offers no control stream.
     */
    private fun refreshUsage(sessionId: String) {
        val origin = prefs.serverUrl
        usageJob?.cancel()
        usageJob = viewModelScope.launch {
            // `runCatching` would also swallow the CancellationException raised
            // when this job is replaced, so cancellation is re-thrown explicitly.
            val result = try {
                Result.success(dsh.sessionUsage(origin, sessionId))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Result.failure(t)
            }
            result.onSuccess { snapshot ->
                if (snapshot == null) return@onSuccess
                if (_state.value.activeSessionId != sessionId) return@onSuccess
                // Seed the live stream's watermark from this read, so a control
                // frame already in flight that predates it cannot pull the
                // counter back to older values.
                projectionSeqs["tokenUsage"] = snapshot.asOfSeq
                projectionSeqs["contextPressure"] = snapshot.asOfSeq
                _state.value = _state.value.copy(
                    usage = snapshot.total,
                    contextTokens = snapshot.contextTokens,
                    contextWindow = snapshot.contextWindow,
                    usageKnown = true,
                )
            }
        }
    }

    /**
     * Subscribe to the host's forwarded events, so the agent's questions can be
     * answered from the phone.
     *
     * This is the whole of fix 4. `ask_user_question` blocks the turn inside the
     * host's `user-questions/request` waterfall, and a client only gets to answer
     * by claiming that waterfall. The unary `userQuestions/answer` RPC cannot do
     * it: it requires the question to be in its `continued` state, which happens
     * only *after* the tool call has returned — by which point the model has
     * moved on and the answer is a reply rather than a decision.
     *
     * One subscription for the host, because the stream is host-wide and a
     * question can be asked for a session the user is not looking at. The frame
     * is filtered to the active session only when *displaying* it; a question for
     * another session is still tracked by the host and will be re-delivered.
     *
     * A dropped stream is not reported as an error: the gateway retries the mux
     * underneath, and a pending waterfall is back-filled to the new stream, so
     * the question reappears on its own. Reporting it would put a banner over a
     * transcript that is still working.
     */
    private fun startEvents() {
        val origin = prefs.serverUrl
        if (eventsJob?.isActive == true) return
        // A re-opened stream is issued a fresh identity, and until its `ready`
        // frame lands there is nothing valid to answer with. Clearing it makes an
        // answer attempted in that window fail rather than quote a dead id.
        eventsClientId = ""
        eventsJob = viewModelScope.launch {
            dsh.forwardedEvents(origin)
                .catch { }
                .collect { frame ->
                    when (frame.optString("type")) {
                        "ready" -> {
                            // Every answer must quote this, and it changes when
                            // the stream is re-opened, so it is replaced rather
                            // than kept.
                            eventsClientId = frame.optString("clientId")
                        }

                        "waterfall" -> {
                            val question = UserQuestion.fromFrame(frame, eventsClientId)
                                ?: return@collect
                            // Only the active session's question is shown. A
                            // question for another session is the host's to hold;
                            // it will still be pending when the user switches,
                            // and the host re-delivers pending waterfalls to a
                            // newly opened stream.
                            if (question.agentId != _state.value.activeSessionId) return@collect
                            // Queue rather than replace. The host delivers each
                            // waterfall once per stream, so a question dropped
                            // here could never be answered — it would sit blocked
                            // until its wait expired. A duplicate is ignored so a
                            // re-delivered frame does not ask the same thing twice.
                            val queue = _state.value.pendingQuestions
                            if (queue.any { it.eventId == question.eventId }) return@collect
                            _state.value = _state.value.copy(
                                pendingQuestions = queue + question,
                            )
                        }

                        "cancel" -> {
                            val eventId = frame.optString("eventId")
                            val queue = _state.value.pendingQuestions
                            if (queue.none { it.eventId == eventId }) return@collect
                            _state.value = _state.value.copy(
                                pendingQuestions = queue.filterNot { it.eventId == eventId },
                            )
                        }
                    }
                }
        }
    }

    /**
     * Answer the pending question, or dismiss it.
     *
     * @param answers one answer per question of the batch, in order. The host
     *   validates that the batch names every question exactly once and rejects
     *   the whole batch otherwise, so a partial list is never submitted.
     * @param dismiss true to decline the question locally without answering it.
     *   The host's wait is deliberately left running: declining is not the same
     *   as cancelling the agent's question, and a `next` reply would step aside
     *   for another answerer — which is exactly what the desktop already is. So
     *   dismissing only hides the prompt here, and the turn stays blocked on the
     *   desktop until it answers or the wait expires.
     */
    fun answerQuestion(answers: List<QuestionAnswer>, dismiss: Boolean = false) {
        val question = _state.value.pendingQuestion ?: return
        // Hide immediately. The answer is a round trip, and leaving the dialog up
        // while it flies makes a tap look ignored; it is put back if the reply
        // never reached the host.
        _state.value = _state.value.copy(
            pendingQuestions = _state.value.pendingQuestions.filterNot {
                it.eventId == question.eventId
            },
        )
        if (dismiss) return

        val origin = prefs.serverUrl
        val clientId = eventsClientId
        viewModelScope.launch {
            val payload = JSONObject().put(
                "answers",
                org.json.JSONArray().apply {
                    for (answer in answers) put(answer.toJson())
                },
            )
            try {
                dsh.answerForwardedEvent(origin, clientId, question.eventId, payload)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                // The reply never reached the host, so the question is still open
                // and the user's answer is still worth sending. Put it back at the
                // front — ahead of any question that arrived while this one was on
                // screen, since the host is still waiting on this one.
                val queue = _state.value.pendingQuestions
                if (queue.none { it.eventId == question.eventId }) {
                    _state.value = _state.value.copy(
                        pendingQuestions = listOf(question) + queue,
                        error = "回答没有送达桌面端，请重试。",
                    )
                }
            }
        }
    }

    /**
     * Split a transcript into durable rows and still-unconfirmed local echoes.
     *
     * Echoes are held back from the fold and re-appended afterwards. That keeps
     * the transcript in order: a durable `user/message` is appended by the fold,
     * so leaving the echo in `previous` would push the confirmed message *past*
     * any echo sent after it.
     */
    private fun splitEchoes(messages: List<Message>): Pair<List<Message>, List<Message>> {
        val durable = mutableListOf<Message>()
        val echoes = mutableListOf<Message>()
        for (message in messages) {
            if (message.id.startsWith(ECHO_PREFIX)) echoes += message else durable += message
        }
        return durable to echoes
    }

    /**
     * Drop the echoes whose durable `user/message` event has just arrived.
     *
     * An echo is matched to a committed message by text, which is all they share:
     * the durable event carries the server's own message id.
     *
     * Text matching alone is not enough. The server does not always store the
     * prompt verbatim — a slash command is expanded, and injected context arrives
     * as its own `user/message` — and an echo that never matched would sit beside
     * its durable twin forever.
     *
     * So it runs in two passes. First every echo that matches a committed
     * message by text is retired, which is the exact case and must not be
     * pre-empted. Then any committed message still unclaimed retires one
     * remaining echo, oldest first: the server has clearly committed *a* prompt,
     * and a prompt produces exactly one committed message, so this cannot retire
     * an echo whose own event is still in flight.
     */
    private fun reconcileEchoes(
        echoes: List<Message>,
        fresh: List<org.json.JSONObject>,
    ): List<Message> {
        val committed = fresh
            .filter { it.optString("type") == "user/message" }
            .mapNotNull { SessionParser.userTextOf(it) }
            .toMutableList()
        if (committed.isEmpty()) return echoes

        val pending = mutableListOf<Message>()
        // Pass 1: exact text matches, so a committed message retires its own echo
        // rather than whichever echo happens to come first.
        for (echo in echoes) {
            val text = echo.plainText.trim()
            val match = committed.indexOfFirst { it.trim() == text }
            if (match >= 0) committed.removeAt(match) else pending += echo
        }
        // Pass 2: one unclaimed committed message retires one leftover echo.
        val out = mutableListOf<Message>()
        for (echo in pending) {
            if (committed.isEmpty()) out += echo else committed.removeAt(0)
        }
        return out
    }

    private fun refreshActiveWorkspace(sessionId: String) {
        val row = _state.value.sessions.firstOrNull { it.id == sessionId } ?: return
        val cwd = row.cwd ?: return
        val workspace = _state.value.workspaces.firstOrNull { it.path == cwd }
        _state.value = _state.value.copy(activeWorkspaceId = workspace?.id.orEmpty())
    }

    /**
     * The active session's address, creating a session if there is none.
     *
     * Both the check and the create happen inside [sessionCreation], so two
     * concurrent callers cannot each create a session: the loser re-reads the
     * session the winner just activated and reuses it. The re-read is what makes
     * the second of two rapid sends join the first conversation instead of
     * starting a second one and discarding the first transcript.
     *
     * The *address* is returned rather than the bare id, because a subagent child
     * cannot be driven by id: the parent owns the delegation, so a write has to
     * name both ends. Resolving it here means every caller gets a value the
     * server accepts.
     */
    private suspend fun resolveSession(origin: String): SessionAddress =
        sessionCreation.withLock {
            _state.value.activeAddress
                ?: SessionAddress.Plain(
                    createSessionLocked(origin).also { created ->
                        // Route through activateSession so the new session starts
                        // from a clean dedupe/index state. Creating the session
                        // and following it without resetting `seenSeqs` made the
                        // first events look like replays and the transcript
                        // stayed empty.
                        activateSession(created, "新会话", false)
                    }
                )
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
        // `running` is set only once the session exists, so the send button stays
        // live across the create round trip; the mutex in `resolveSession` is what
        // keeps two taps from becoming two sessions.
        viewModelScope.launch {
            try {
                val address = resolveSession(origin)
                _state.value = _state.value.copy(
                    messages = _state.value.messages + Message(
                        id = "$ECHO_PREFIX${System.currentTimeMillis()}",
                        role = Role.USER,
                        blocks = listOf(Block.Text(text)),
                        time = System.currentTimeMillis(),
                    ),
                    running = true,
                    error = null,
                )
                // Addressed, not a bare id: a subagent child is rejected by
                // `session/prompt` and has to go through `subagents/prompt`.
                dsh.prompt(origin, address, text)
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
                // The address, not the id: a subagent child's turn is interrupted
                // through its parent, and `session/cancel` rejects the bare id.
                val address = _state.value.activeAddress
                if (id != null && address != null) {
                    viewModelScope.launch {
                        runCatching { dsh.cancel(origin, address) }
                            .onFailure { _state.value = _state.value.copy(error = it.message) }
                        refreshUsage(id)
                    }
                }
            }
            Backend.API -> api.cancel()
        }
        _state.value = _state.value.copy(running = false)
    }

    // ------------------------------------------------------------------ models

    /** API-mode model list, pulled from the endpoint's `/v1/models`. */
    fun loadModels() {
        viewModelScope.launch {
            val key = prefs.apiKey
            if (key.isBlank()) return@launch
            runCatching { api.listModels(prefs.apiBaseUrl, key) }
                .onSuccess { _state.value = _state.value.copy(models = it) }
        }
    }

    /**
     * Load the remote model catalog.
     *
     * The catalog is server-wide, not per session, so it is fetched once and
     * reused for every session the user opens.
     */
    fun loadRemoteModels() {
        val origin = prefs.serverUrl
        if (origin.isBlank()) return
        if (_state.value.loadingModels) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingModels = true)
            runCatching { dsh.modelCatalog(origin) }
                .onSuccess { catalog ->
                    val rows = catalog.options.map {
                        ModelRow(
                            provider = it.provider,
                            providerName = it.providerName,
                            id = it.id,
                            name = it.name,
                            description = it.description,
                            efforts = it.efforts,
                            defaultEffort = it.defaultEffort,
                        )
                    }
                    _state.value = _state.value.copy(
                        loadingModels = false,
                        remoteModels = rows,
                        // Only a real failure is worth surfacing; a catalog with
                        // no failures is the normal case.
                        error = if (rows.isEmpty() && catalog.failures.isNotEmpty()) {
                            catalog.failures.first()
                        } else {
                            _state.value.error
                        },
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        loadingModels = false,
                        error = it.message ?: "无法获取模型列表",
                    )
                }
        }
    }

    /**
     * Switch the active session's model.
     *
     * `session/selectModel` needs a session to act on, so a session is created
     * first when the user picks a model before sending anything. That matches
     * what the desktop does: the model chip belongs to a conversation.
     *
     * A subagent child is refused up front rather than sent and rejected: the
     * server resolves that id through subagent routing and answers
     * `session/agent-busy`, because the child runs under the parent's
     * composition and has no model selection of its own.
     */
    fun selectRemoteModel(provider: String, model: String, reasoningEffort: String? = null) {
        val origin = prefs.serverUrl
        viewModelScope.launch {
            _state.value = _state.value.copy(switchingModel = true, error = null)
            try {
                when (val address = resolveSession(origin)) {
                    // A child runs under the parent's composition and has no model
                    // selection of its own, so this is refused up front rather than
                    // sent and rejected with `session/agent-busy`.
                    is SessionAddress.Subagent -> {
                        _state.value = _state.value.copy(
                            switchingModel = false,
                            error = "子代理会话使用父会话的模型，无法单独切换。",
                        )
                        return@launch
                    }

                    is SessionAddress.Plain -> {
                        dsh.selectModel(origin, address.sessionId, provider, model, reasoningEffort)
                        prefs.reasoningEffort = reasoningEffort.orEmpty()
                        _state.value = _state.value.copy(
                            switchingModel = false,
                            activeModel = "$provider/$model",
                            activeEffort = reasoningEffort.orEmpty(),
                            info = "已切换模型：$model",
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Rethrown, not reported. `catch (t: Throwable)` would swallow the
                // cancellation raised when the view model is cleared and turn it
                // into a banner — a "failure" the user never caused and cannot fix.
                // The sibling jobs (`refreshUsage`, `refreshWorkspaces`) already
                // rethrow for exactly this reason.
                throw e
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    switchingModel = false,
                    error = t.message ?: "切换模型失败",
                )
            }
        }
    }

    fun selectModel(model: String) {
        prefs.apiModel = model
        _state.value = _state.value.copy(selectedModel = model)
    }

    // -------------------------------------------------------------- workspaces

    /**
     * Load the workspace list from `workspace/follow`'s baseline frame.
     *
     * A refresh asked for while one is in flight **supersedes** it rather than
     * being dropped. That matters after a re-pair: the pre-pair load is still
     * running when the post-pair one is requested, and dropping the latter would
     * leave the old desktop's workspaces on screen with no way to correct them.
     * The superseded load is cancelled, and a response is discarded outright when
     * the authority changed under it.
     */
    fun refreshWorkspaces() {
        val origin = prefs.serverUrl
        if (origin.isBlank()) return
        workspaceRequest?.cancel()
        workspaceRequest = viewModelScope.launch {
            _state.value = _state.value.copy(loadingWorkspaces = true)
            // `runCatching` would also swallow the CancellationException raised by
            // a superseding refresh, so cancellation is re-thrown explicitly.
            val result = try {
                Result.success(dsh.listWorkspaces(origin))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Result.failure(t)
            }
            // A response that arrives after the pairing changed describes a
            // different desktop; writing it would show workspaces the user cannot
            // use and would clear a perfectly valid selection.
            if (prefs.serverUrl != origin) return@launch
            result
                .onSuccess { list ->
                    val rows = list.map { WorkspaceRow(it.id, it.path, it.title) }
                    val selected = _state.value.selectedWorkspaceId
                    // The active session's workspace is resolved here as well as
                    // in refreshSessions, because either load can finish first.
                    val activeCwd = _state.value.sessions
                        .firstOrNull { it.id == _state.value.activeSessionId }
                        ?.cwd
                    _state.value = _state.value.copy(
                        loadingWorkspaces = false,
                        workspaces = rows,
                        // A remembered workspace that no longer exists must not
                        // stay selected, or every new session would target a
                        // workspace the server rejects.
                        selectedWorkspaceId = if (rows.any { it.id == selected }) selected else "",
                        activeWorkspaceId = rows.firstOrNull { it.path == activeCwd }?.id.orEmpty(),
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        loadingWorkspaces = false,
                        error = it.message ?: "无法获取工作区列表",
                    )
                }
        }
    }

    /**
     * Choose the workspace new sessions are created in.
     *
     * Selecting a workspace is a client-side preference; it takes effect on the
     * next `session/create`. An open session is not moved, because a session's
     * workspace is fixed once it exists.
     */
    fun selectWorkspace(workspaceId: String) {
        prefs.workspaceId = workspaceId
        _state.value = _state.value.copy(selectedWorkspaceId = workspaceId, error = null)
    }

    /**
     * Register a directory path as a workspace and select it.
     *
     * This is how a phone user reaches a project that the desktop has never
     * opened: typing the path is the only affordance available without a native
     * directory picker on the host.
     */
    fun addWorkspace(path: String) {
        val origin = prefs.serverUrl
        val trimmed = path.trim()
        if (trimmed.isEmpty()) {
            _state.value = _state.value.copy(error = "请填写工作区目录的绝对路径")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingWorkspaces = true, error = null)
            try {
                val created = dsh.createWorkspace(origin, trimmed)
                // Select first, then refresh: `refreshWorkspaces` owns the
                // `loadingWorkspaces` flag and would otherwise find it already
                // set and return without ever listing the new workspace.
                if (created.id.isNotBlank()) selectWorkspace(created.id)
                _state.value = _state.value.copy(info = "已添加工作区：${created.label}")
                refreshWorkspaces()
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    loadingWorkspaces = false,
                    error = t.message ?: "添加工作区失败",
                )
            }
        }
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
        _state.value = _state.value.copy(error = null, info = null, errorIsTransport = false)
    }

    override fun onCleared() {
        followJob?.cancel()
        usageJob?.cancel()
        controlJob?.cancel()
        eventsJob?.cancel()
        api.cancel()
        super.onCleared()
    }

    private companion object {
        /**
         * Id prefix for a user message rendered before its durable event lands.
         *
         * The prefix is load-bearing: [reconcileEchoes] finds these rows by it.
         */
        const val ECHO_PREFIX = "local-"

        /**
         * [DshClient.DshException] codes that a reconnect can cure.
         *
         * These are exactly the codes [DshClient.stream] itself treats as
         * retryable (`isTransportFailure`), plus the unary transport code. A mux
         * socket that died is replaced by the stream retry; nothing else in the
         * list recovers on its own.
         */
        val TRANSPORT_ERROR_CODES = setOf(
            "transport",
            "ws-failure",
            "ws-closed",
        )
    }
}
