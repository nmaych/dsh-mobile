package ai.deepseek.dshmobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import ai.deepseek.dshmobile.DshApp
import ai.deepseek.dshmobile.data.AgentPresets
import ai.deepseek.dshmobile.data.ApprovalRequest
import ai.deepseek.dshmobile.data.Backend
import ai.deepseek.dshmobile.data.Block
import ai.deepseek.dshmobile.data.CallIndex
import ai.deepseek.dshmobile.data.DshClient
import ai.deepseek.dshmobile.data.DshClient.SessionAddress
import ai.deepseek.dshmobile.data.GoalState
import ai.deepseek.dshmobile.data.LiveAssistant
import ai.deepseek.dshmobile.data.Message
import ai.deepseek.dshmobile.data.PermissionPresets
import ai.deepseek.dshmobile.data.QuestionAnswer
import ai.deepseek.dshmobile.data.Role
import ai.deepseek.dshmobile.data.SessionParser
import ai.deepseek.dshmobile.data.TodoItem
import ai.deepseek.dshmobile.data.TokenUsage
import ai.deepseek.dshmobile.data.ToolFiles
import ai.deepseek.dshmobile.data.Transcript
import ai.deepseek.dshmobile.data.UserQuestion
import ai.deepseek.dshmobile.data.WorkspaceFilePage
import ai.deepseek.dshmobile.data.WorkspaceFiles
import ai.deepseek.dshmobile.data.WorkspaceListing
import ai.deepseek.dshmobile.net.ChatApi
import ai.deepseek.dshmobile.net.GatewayClient
import ai.deepseek.dshmobile.net.StreamEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    /**
     * The `approval/request` waterfalls the agent is waiting on, oldest first.
     *
     * Non-empty means a turn is *blocked* on a permission decision: the tool call
     * is suspended inside the host's approval waterfall. This is the same shape as
     * [pendingQuestions] and for the same reason — the host delivers each waterfall
     * once per stream, so a request dropped here could never be answered and would
     * sit blocked until its wait expired. A list rather than a slot because a
     * second request can arrive while the first is on screen.
     */
    val pendingApprovals: List<ApprovalRequest> = emptyList(),
    // ---- permission presets ------------------------------------------------
    /**
     * The deployment's permission presets, from `permissionPresets/catalog`.
     *
     * Empty until loaded, and stays empty on a host without the plugin — which is
     * what hides the chip rather than offering a switch that cannot work.
     */
    val permissionPresets: List<PermissionPresets.Preset> = emptyList(),
    /** The preset the active session is in, or blank when the host does not say. */
    val activePermissionPreset: String = "",
    /** True while the preset catalog is being fetched. */
    val loadingPermissions: Boolean = false,
    /** True while a preset switch is in flight. */
    val switchingPermission: Boolean = false,
    /** True while the permission picker is on screen. */
    val showPermissionPicker: Boolean = false,
    /** True while a manual refresh is in flight, so the button can show it. */
    val refreshing: Boolean = false,
    // ---- agent presets (模式) ----------------------------------------------
    /**
     * The deployment's Agent presets, from `agentPresets/list`.
     *
     * Empty until loaded, and stays empty on a host without the preset registry —
     * which is what hides the mode chip rather than offering a switch that cannot
     * work. Distinct from [permissionPresets]: that one is policy, this one is
     * which tools and working style the agent gets.
     */
    val agentPresets: List<AgentPresets.Preset> = emptyList(),
    /** The preset the active session runs, or blank when the host does not say. */
    val activeAgentPreset: String = "",
    /** The mode a *new* session will be created in. */
    val selectedAgentPreset: String = "",
    /** True while the preset roster is being fetched. */
    val loadingAgentPresets: Boolean = false,
    /** True while a preset switch is in flight. */
    val switchingAgentPreset: Boolean = false,
    /** True while the preset picker is on screen. */
    val showAgentPresetPicker: Boolean = false,
    // ---- goal & todos -------------------------------------------------------
    /**
     * The active session's current goal, from the host's `goal` projection.
     *
     * Null when the session has none — which is most sessions — and the entry
     * chip hides on null rather than offering a sheet with nothing in it.
     */
    val goal: GoalState? = null,
    /**
     * The active session's todo list, from the host's `todos` projection.
     *
     * The host clears it at every `turn/start`, so an empty list is normal
     * between turns; the sheet renders it as "本回合没有任务清单" rather than
     * treating it as an error.
     */
    val todos: List<TodoItem>? = null,
    /** True while the goal/todo sheet is on screen. */
    val showGoalTasks: Boolean = false,
    // ---- workspace files ---------------------------------------------------
    /**
     * The directory listing currently on screen, or null when the browser is shut.
     *
     * Non-null is what makes the file browser visible, so opening and closing it
     * is a state change rather than a separate flag that could disagree with the
     * contents.
     */
    val files: WorkspaceListing? = null,
    /** The workspace-relative directory [files] describes; empty is the root. */
    val filesPath: String = "",
    /** True while a listing or a file read is in flight. */
    val filesLoading: Boolean = false,
    /** The file opened from the browser, with its page of text. */
    val openFile: WorkspaceFilePage? = null,
    /** The path of [openFile], as the user sees it. */
    val openFilePath: String = "",
    /** Why the browser is showing an error instead of entries. */
    val filesError: String? = null,
    // ---- delivered files ---------------------------------------------------
    /**
     * True while the "delivered files" sheet is on screen.
     *
     * A flag rather than the list itself, because the list is *derived* from the
     * transcript — see [deliverables]. Storing it would be a second copy that can
     * disagree with the messages it was read from, and the disagreement would show
     * up as a sheet that stops updating while the turn that produces the file is
     * still running.
     */
    val showDeliverables: Boolean = false,
    /**
     * The workspace groups currently expanded in the drawer.
     *
     * Held here rather than inside the drawer composable because the drawer is
     * rebuilt on every `state` change — a token delta recomposes the whole shell —
     * and a `remember` inside it would still be keyed to the wrong scope once the
     * session list is replaced. Keeping it in the state makes an expansion survive
     * a refresh, which is the whole point of expanding it.
     */
    val expandedGroups: Set<String> = emptySet(),
) {
    /** The question the UI is currently asking, if any. */
    val pendingQuestion: UserQuestion? get() = pendingQuestions.firstOrNull()

    /** The approval the UI is currently asking about, if any. */
    val pendingApproval: ApprovalRequest? get() = pendingApprovals.firstOrNull()

    /**
     * The active session's preset, as a row the UI can name.
     *
     * Falls back to a row built from the raw value so a preset the catalog does not
     * list — a `custom` derived state, or a table that changed under us — still
     * reads as *something* rather than as a blank chip. That is the honest
     * fallback: the session really is in that mode, and hiding it would make the
     * chip claim the default.
     */
    val activePermission: PermissionPresets.Preset?
        get() {
            val value = activePermissionPreset
            if (value.isBlank()) return null
            return permissionPresets.firstOrNull { it.value == value }
                ?: PermissionPresets.Preset(value, value, "")
        }

    /** True when the host offers a preset switch at all. */
    val permissionSwitchable: Boolean
        get() = permissionPresets.isNotEmpty() && activePermissionPreset.isNotBlank()

    /**
     * The active session's mode, as a row the UI can name.
     *
     * Falls back to a row built from the raw id so a preset the roster does not
     * list — a declaration that changed under us, or a host that reports a mode
     * without listing it — still reads as *something* rather than as a blank chip.
     * The shipped four resolve through [AgentPresets.builtInName], so a session in
     * `standard` shows 「标准模式」 even before the roster has loaded.
     */
    val activeAgentPresetRow: AgentPresets.Preset?
        get() {
            val id = activeAgentPreset
            if (id.isBlank()) return null
            return agentPresets.firstOrNull { it.id == id }
                ?: AgentPresets.Preset(
                    id = id,
                    name = AgentPresets.builtInName(id).orEmpty(),
                    description = AgentPresets.builtInDescription(id).orEmpty(),
                )
        }

    /** The mode a new session will start in, as a row the UI can name. */
    val selectedAgentPresetRow: AgentPresets.Preset?
        get() {
            val id = selectedAgentPreset
            if (id.isBlank()) return null
            return agentPresets.firstOrNull { it.id == id }
                ?: AgentPresets.Preset(
                    id = id,
                    name = AgentPresets.builtInName(id).orEmpty(),
                    description = AgentPresets.builtInDescription(id).orEmpty(),
                )
        }

    /**
     * True when the mode chip should be offered.
     *
     * The roster has to be non-empty — a host without the registry has no modes to
     * offer, and a chip that cannot change anything is a control that lies. Unlike
     * the permission chip this does *not* also require a value on the active
     * session: the mode is chosen when a session is **created**, so it has to be
     * selectable *before* one exists, which is exactly when
     * [activeAgentPreset] is still blank.
     */
    val agentPresetSwitchable: Boolean
        get() = agentPresets.isNotEmpty()

    /**
     * True when the active session's mode can still be changed.
     *
     * The host refuses a switch once the first turn has started
     * (`agent-preset/locked`) because the preset decides the tool schemas and
     * prompt sections the model has already been shown. The picker says so up
     * front rather than letting the user tap and be refused: a session that has
     * produced any transcript row has started, and `running` covers the window
     * between the first token and the first durable event.
     */
    val agentPresetLocked: Boolean
        get() = activeSessionId != null && (running || messages.isNotEmpty())

    /** What the usage chip renders: settled totals plus the in-flight attempt. */
    val displayUsage: TokenUsage get() = usage + liveUsage

    /**
     * The drawer's session list, grouped by workspace and collapsed when long.
     *
     * Derived rather than stored: it is a pure function of `sessions` and
     * `workspaces`, both of which the view model already owns, so storing it too
     * would be a second copy that can disagree with them.
     */
    val sessionGroups: List<SessionTree.Group>
        get() = SessionTree.group(sessions, workspaces)

    /**
     * True when the file dialog should be on screen.
     *
     * `openFile` counts on its own, and that is the fix for the inline file chips.
     * The dialog used to be reachable only from the workspace browser, so it was
     * always opened *after* a listing existed and "the browser has content" was a
     * sufficient test. A chip on a tool card opens a file directly, with no
     * listing behind it — under the old definition the dialog simply never
     * appeared, and the tap looked dead.
     */
    val filesOpen: Boolean get() = files != null || filesError != null || openFile != null

    /**
     * The files the assistant delivered, newest first.
     *
     * Derived from the transcript rather than stored, and that is the point: the
     * transcript is *live*. A snapshot taken when the sheet was opened would stop
     * updating the moment a turn produced another file — and the moment a turn is
     * producing files is exactly when the user is watching.
     *
     * ## Why it is a `lazy` and not a plain `get()`
     *
     * The app bar reads it on **every recomposition**, and a token delta recomposes
     * the whole shell — many times a second on a streaming turn. A plain getter
     * would walk every message and every block of a long conversation that often,
     * on the main thread, to answer one `isNotEmpty()`.
     *
     * A `lazy` is correct here rather than a cache that can go stale: `messages` is
     * a `val`, and every update goes through `copy()`, which produces a **new
     * instance** with its own uninitialised delegate. So the value can never
     * outlive the messages it was computed from — which is exactly the property a
     * stored field in the view model would not have.
     */
    val deliverables: List<ToolFiles.Ref> by lazy { ToolFiles.delivered(messages) }
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

    /**
     * `turn -> the `turn/start` event's write time, for the active session.
     *
     * Kept on the view model like [callIndex]: a `turn/end` lands in a later batch
     * than its `turn/start` whenever the socket re-arms between them, and a
     * per-batch map would leave that turn without a measurable duration — the
     * pre-1.2.1 bug of turns quietly showing none.
     */
    private val turnStarts: MutableMap<Int, Long> = mutableMapOf()

    init {
        _state.value = _state.value.copy(
            backend = prefs.backend,
            selectedModel = prefs.apiModel,
            viaGateway = prefs.usesGateway,
            selectedWorkspaceId = prefs.workspaceId,
            activeEffort = prefs.reasoningEffort,
            // Seeded from the stored preference so a create that races the roster
            // fetch still names the mode the user last chose. The roster, when it
            // arrives, validates it and falls back to the host's own default if the
            // deployment no longer declares it.
            selectedAgentPreset = prefs.agentPreset,
        )
        // Retract a transport error the moment the mux comes back, and never let a
        // stale "disconnected" outlive a working connection.
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
                // Two directions, and the second one is what 1.1.6 still got wrong.
                //
                // The socket is the only authority on reachability, so its state is
                // applied whether it went up *or* down. 1.1.6 ignored the "down"
                // edge and only *reacted* to an "up" edge — so a banner raised
                // while the socket was already open waited for an emission that
                // could never come, and the app went on claiming it was
                // disconnected over a connection that was demonstrably working.
                // A `StateFlow` (rather than the replaying `SharedFlow` it was)
                // means the current value can simply be read, so a collector that
                // starts late still learns the truth instead of waiting for a
                // change that has already happened.
                val current = _state.value
                if (connected) {
                    // An open mux socket is itself proof the desktop is reachable
                    // and the stored credential still authenticates — the
                    // handshake required the cookie — so it also clears a
                    // `connected = false` left behind by a unary call that failed
                    // during the blip. Without this the chips stayed greyed out and
                    // the app bar read "未连接" while the transcript streamed
                    // normally underneath.
                    if (current.errorIsTransport || !current.connected) {
                        _state.value = current.copy(
                            error = if (current.errorIsTransport) null else current.error,
                            errorIsTransport = false,
                            connected = true,
                        )
                    }
                } else if (current.connected && current.errorIsTransport) {
                    // The socket dropped *and* the only thing on screen is a
                    // transport complaint about it. Marking the UI disconnected is
                    // honest here; a server-side error is left alone, because the
                    // drop says nothing about it.
                    _state.value = current.copy(connected = false)
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

    /**
     * The banner text for a failure, or null when the socket contradicts it.
     *
     * The connection-state collector retracts a transport error on the *next*
     * socket event, which is enough while the socket is flapping. It cannot help
     * when the socket never went down at all: a single request can fail on its own
     * — a connect timeout that lost a race with the radio waking, a stream that
     * exhausted its retries while another stream kept the shared mux alive — and
     * with no state *change* there is no event to react to. The banner would then
     * sit there asserting "无法连接" over a socket that is open at that very
     * moment, which is the reported symptom.
     *
     * So the current value is consulted at the moment of the failure, not only
     * when it changes. A live mux socket proves the desktop is reachable *and*
     * that the stored credential still authenticates, so it outranks a failed
     * request: the request is dropped silently rather than misreported. The
     * original failure is still logged by `DshClient`.
     *
     * A server-side failure is never suppressed this way — an open socket says
     * nothing about a rejected prompt, and hiding it would lose a real error.
     */
    private fun transportAwareError(message: String, t: Throwable): String? {
        if (!isTransportError(t)) return message
        return if (dsh.connectionState.value) null else message
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
            // The mode roster is deployment-wide and read once, so it is fetched
            // alongside the other two. Without it the mode chip stays hidden and a
            // user who switched to the API backend and back would lose the ability
            // to pick a mode until the app restarted.
            loadAgentPresets()
            // Switching *into* remote mode is a fifth way to end up connected, and
            // it needs the same subscription as the other four. Launching the app
            // in API mode means `autoConnect` returned before subscribing, so
            // without this a user who pairs, tries the API backend, and switches
            // back would have a working conversation that could never answer a
            // question. `startEvents` is idempotent — it returns while a job is
            // active — so calling it here is safe when one is already running.
            startEvents()
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
                loadAgentPresets()
                // Subscribe to the host's forwarded events. Without this the
                // pairing *succeeds* and every other call works, but
                // `ask_user_question` has no channel at all: the question is
                // delivered to whichever `$events` stream exists, and on this path
                // none did. A freshly paired install could therefore never answer a
                // question until the app was restarted, which is exactly the
                // "无法回答提问" report — the feature looked absent rather than
                // broken, because the transcript showed the tool call and nothing
                // was listening.
                startEvents()
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
        turnStarts.clear()
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
            // Same for an approval: its `eventId` names a waterfall on the old
            // session, and answering it from here would answer the wrong turn.
            pendingApprovals = emptyList(),
            // The preset is per-session state, so the old session's value must not
            // outlive it — the chip would name a mode the new session is not in.
            activePermissionPreset = "",
            showPermissionPicker = false,
            // And neither must the mode, for the same reason. The remembered
            // next-session choice is a preference and stays.
            activeAgentPreset = "",
            showAgentPresetPicker = false,
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
                // Same reason as the gateway path above: the direct `dsh web`
                // pairing is a supported way in, and it must not be the one that
                // cannot answer questions.
                startEvents()
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
                loadAgentPresets()
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
                loadAgentPresets()
                startEvents()
                if (reopen != null) activateSession(reopen, reopenTitle.ifBlank { "会话" }, false)
            }
        }
    }

    // ---------------------------------------------------------------- sessions

    /**
     * Reload the session list, and re-sync the conversation the user is reading.
     *
     * ## Why the button looked dead
     *
     * The refresh button called exactly this method, and this method only ever
     * replaced `sessions` — the *drawer's* list. A user who tapped it while
     * looking at a transcript saw the same transcript, the same title and the same
     * chips, because nothing they could see had been re-read. The one thing it did
     * change was the drawer, which is closed while the transcript is on screen.
     * There was no spinner, no toast and no timestamp either, so even the case it
     * did handle was indistinguishable from a dead button.
     *
     * Three things fix that, and all three are needed:
     *
     *  1. **The open session is re-synced too.** `session/follow` already replays
     *     its backlog, so the cheapest honest re-sync is to re-arm that
     *     subscription — the transcript then catches up on anything the phone
     *     missed, which is the actual reason to press refresh on a phone whose
     *     socket has been asleep. See [restartFollowing].
     *  2. **`refreshing` is set**, so the app bar can show that something happened.
     *  3. **An `info` line reports the outcome**, including the count, so a
     *     successful refresh is visibly a refresh rather than a no-op.
     *
     * `refreshSessions` is still called by the automatic paths (drawer open,
     * reconnect, after a send), and those must not flash a spinner or a toast at
     * the user. So the *feedback* is owned by [manualRefresh], which is what the
     * button is wired to, and this method stays the quiet primitive.
     */
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
                applySessionList(dsh.listSessions(origin), requestedFor)
            } catch (t: Throwable) {
                // A failed *list* call is not evidence that the desktop is gone,
                // and it must not be reported as though it were.
                //
                // This is the "显示无法连接但实际上连接正常" report. `refreshSessions`
                // runs on every drawer open and after every reconnect, and one
                // attempt can lose a race with the radio waking up — a connect
                // timeout that is over a second later. Clearing `connected` here
                // then greyed out the model and workspace chips and made the app
                // bar read "未连接" over a connection that was working: the mux
                // socket was open, the transcript was streaming, and only this one
                // request had failed.
                //
                // The socket is the authority on reachability, so `connected` is
                // left exactly as it was and the banner is flagged as transport —
                // which lets the connection-state collector retract it the moment
                // there is a live socket to prove it wrong. A server-side refusal
                // (a rejected credential) is not flagged, so it stays until the
                // user acts on it.
                //
                // `transportAwareError` covers the case the collector cannot: an
                // open socket means this failure was this one request's, so there
                // is nothing worth showing at all.
                val transport = isTransportError(t)
                _state.value = _state.value.copy(
                    error = transportAwareError(t.message ?: "无法获取会话列表", t),
                    errorIsTransport = transport,
                )
            }
        }
    }

    /**
     * Fold a freshly read session list into the state.
     *
     * Shared by the quiet path and the manual refresh so the two cannot disagree
     * about what a list means — the same reason `refreshUsage` and the control
     * stream share their projection reader.
     *
     * @param requestedFor the session that was active when the read *started*.
     *   Only a response still describing that session may stamp the model and
     *   workspace chips: a slow reply that lands after the user switched would
     *   otherwise write the old session's facts onto the new one's chips. The list
     *   itself is applied either way, because it is not specific to any session.
     */
    private fun applySessionList(list: List<DshClient.SessionInfo>, requestedFor: String?) {
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
    }

    /**
     * The app-bar refresh: reload the list, re-sync the transcript, and say so.
     *
     * Kept separate from [refreshSessions] because the automatic callers must stay
     * silent. This is the one the button is wired to.
     *
     * The re-sync is a re-arm of the follow subscription rather than a second
     * `session/follow` stream: opening a competing stream would leave two
     * collectors folding the same events into the same transcript, and the one
     * that lost the race would re-add everything the other had already applied.
     * `restartFollowing` cancels the existing job first, so exactly one is ever
     * live.
     */
    fun manualRefresh() {
        val origin = prefs.serverUrl
        if (origin.isBlank()) {
            _state.value = _state.value.copy(error = "尚未配置桌面端地址，无法刷新。")
            return
        }
        if (_state.value.refreshing) return
        val requestedFor = _state.value.activeSessionId
        viewModelScope.launch {
            _state.value = _state.value.copy(refreshing = true, error = null, info = null)
            // Re-arm the transcript's subscription first: this is the half the
            // button was missing, and it is the half a user actually watches.
            restartFollowing()
            try {
                val list = dsh.listSessions(origin)
                applySessionList(list, requestedFor)
                _state.value = _state.value.copy(
                    refreshing = false,
                    info = "已刷新 · ${list.size} 个会话",
                )
                // The chips and the roster are the other two things a refresh should
                // bring up to date; both are deployment-wide and cheap, and neither
                // reports an error of its own (they set their own loading flags).
                refreshWorkspaces()
                loadAgentPresets()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                val transport = isTransportError(t)
                _state.value = _state.value.copy(
                    refreshing = false,
                    // Reported even when a live socket would normally suppress it:
                    // this failure is the *direct answer* to a button the user just
                    // pressed, and silence there is exactly the "按钮没反应"
                    // complaint. The wording says the refresh failed rather than
                    // claiming the desktop is unreachable.
                    error = "刷新失败：${t.message ?: "无法获取会话列表"}",
                    errorIsTransport = transport,
                )
            }
        }
    }

    /**
     * Re-arm the active session's follow subscription, keeping the transcript.
     *
     * The job is cancelled and a fresh one started against the same address. The
     * transcript, `seenSeqs` and `callIndex` are deliberately **not** cleared: the
     * new subscription replays the backlog, and the dedupe set is what makes that
     * replay a no-op for events already folded. Clearing them would rebuild the
     * transcript from scratch — a visible flicker, and a loss of the optimistic
     * echoes that have not been committed yet.
     *
     * A no-op without an active session, which is why it is safe to call
     * unconditionally from a refresh.
     */
    private fun restartFollowing() {
        val sessionId = _state.value.activeSessionId ?: return
        val address = _state.value.activeAddress ?: return
        followJob?.cancel()
        followJob = null
        startFollowing(sessionId, address)
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
        turnStarts.clear()
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
            // And neither must an approval, for the same reason.
            pendingApprovals = emptyList(),
            // The preset belongs to the session, so the chip goes blank until the
            // new session's own projection reports one. Carrying the old value
            // over would name a mode the new conversation is not in.
            activePermissionPreset = "",
            showPermissionPicker = false,
            // Same reasoning as the permission preset: the mode belongs to the
            // session. The *selected* (next-session) mode deliberately survives —
            // it is a user preference, not session state.
            activeAgentPreset = "",
            showAgentPresetPicker = false,
            // The goal and the todo list belong to the session too: they describe
            // what the previous conversation was doing, and showing them under a
            // different title would misattribute work.
            goal = null,
            todos = null,
            showGoalTasks = false,
            // The file browser is rooted at the *previous* session's workspace, so
            // its listing describes a directory the new session may not even be
            // able to see. Closing it is the only honest option: keeping it would
            // offer "go up" and file reads that resolve against the wrong root.
            files = null,
            filesPath = "",
            filesLoading = false,
            openFile = null,
            openFilePath = "",
            filesError = null,
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
            // The mode has to be named at create time: a live session's preset is
            // locked once its first turn starts, so a create without one takes
            // whatever the deployment's default happens to be — a choice made by
            // nobody. Blank is left blank rather than defaulted here, so a host
            // without the registry is not sent a preset it does not know.
            agentPreset = _state.value.selectedAgentPreset.takeIf { it.isNotBlank() },
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
            // and closed in a later one must still resolve correctly. It is also
            // kept across *re-arms* below, for the same reason — a re-subscription
            // replays the backlog, and a turn that opened before the re-arm must
            // still be recognised as open.
            var openTurn: Int? = null
            // Tracks the turn boundary so the usage projection is re-read exactly
            // once, when a turn settles — not on a timer.
            var wasRunning = false

            // Re-armed, not subscribed once.
            //
            // This is the "桌面端在跑、手机端卡住" report, and it is the same defect
            // 1.1.6 fixed for the question stream — `startEvents` carries the long
            // version of this reasoning. `DshClient.stream` retries a dead *socket*
            // internally, but it also completes **normally** when the host ends the
            // stream: the mux delivers an `end` frame, the inbox closes without an
            // error, the collector returns, and the job finishes. After that
            // nothing ever subscribed again, so the transcript simply stopped —
            // permanently. The desktop went on streaming because it holds its own
            // subscription; only this phone went deaf, which is exactly why the
            // symptom looked like the phone hanging rather than the desktop
            // stopping.
            //
            // The same happens when `stream` exhausts its retry budget
            // (`MAX_STREAM_RETRIES`) while the phone sleeps or changes network: the
            // failure is reported once and then never retried.
            //
            // Re-arming is safe by construction: `session/follow` replays its
            // backlog as a fresh `snapshot`, and `seenSeqs` dedupes it, so a
            // re-subscription can only *add* what was missed. `live` is kept across
            // re-arms so a draft that was mid-flight is not thrown away and rebuilt.
            while (isActive) {
                // Set when the failure is one a retry cannot cure, so the loop
                // stops instead of hammering the host forever.
                var permanent = false
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
                        val transport = isTransportError(t)
                        // A server-side refusal is not self-healing: the session is
                        // gone, or the credential is rejected. Re-arming would ask
                        // the same question every 700ms for the life of the process
                        // and re-raise the same banner each time, so the loop exits
                        // and the user is left with the error and the refresh button.
                        // Only a transport failure is worth retrying.
                        permanent = !transport
                        _state.value = _state.value.copy(
                            connecting = false,
                            historyLoaded = true,
                            error = t.message ?: "订阅会话失败",
                            errorIsTransport = transport,
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
                            // Turn tracking scans **every** event, not only the fresh
                            // ones. A re-arm below replays the whole backlog, and
                            // those events are already in `seenSeqs` — so tracking
                            // them only when fresh would leave `openTurn` null after
                            // a re-subscription that landed mid-turn, and a turn
                            // whose `turn/end` had already been replayed would look
                            // like it was still running. Reading the boundary from
                            // the replay is what makes the re-arm transparent.
                            for (e in events) {
                                when (e.optString("type")) {
                                    "turn/start" ->
                                        openTurn = e.optJSONObject("data")?.optInt("turn", 0) ?: 0
                                    "turn/end" -> openTurn = null
                                }
                            }
                            val fresh = events.filter { e ->
                                val seq = e.optLong("seq", -1L)
                                seq < 0 || seenSeqs.add(seq)
                            }
                            if (fresh.isNotEmpty()) {
                                val (durable, echoes) = splitEchoes(_state.value.messages)
                                val folded = SessionParser.fold(fresh, durable, callIndex, turnStarts)
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
                // The collector returned: the stream ended, with or without an
                // error. Pause before re-arming — a host that ends the stream
                // immediately (an idle session, a closed endpoint) would otherwise
                // be re-subscribed in a hot loop, spinning a core and hammering the
                // gateway with handshakes.
                //
                // The delay is what keeps a normal `end` from becoming a busy loop,
                // and it is short enough that a stream the host closes between turns
                // is re-opened before the user notices: a re-arm that lands mid-turn
                // replays the backlog and picks up the deltas from there.
                if (permanent) {
                    // Nothing a retry can fix. The banner stays, and the app-bar
                    // refresh or reopening the session is the way out — which is
                    // what the user would do anyway after reading the error.
                    return@launch
                }
                delay(FOLLOW_RETRY_DELAY_MS)
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
                            for (key in WATCHED_PROJECTIONS) {
                                projectionSeqs[key] = asOf
                            }
                            applyProjection(
                                values.optJSONObject("tokenUsage"),
                                values.optJSONObject("contextPressure"),
                                values.optJSONObject("permissions"),
                                // A bare string, not an object: the projection's own
                                // view is the id. Read through `opt` so a JSON null
                                // (the host clearing it) is distinguishable from an
                                // absent key.
                                values.opt("agentPreset"),
                                values.opt("goal"),
                                values.opt("todos"),
                            )
                        }

                        "projection" -> {
                            if (frame.optString("sessionId") != sessionId) return@collect
                            val key = frame.optString("key")
                            if (key !in WATCHED_PROJECTIONS) return@collect
                            val seq = frame.optLong("seq", 0L)
                            // Strictly newer only: an equal seq is the same write
                            // arriving twice, and applying it again is harmless
                            // but pointless.
                            if (seq <= (projectionSeqs[key] ?: Long.MIN_VALUE)) return@collect
                            projectionSeqs[key] = seq
                            // `opt`, not `optJSONObject`: `agentPreset`'s value is a
                            // bare string, and coercing it to an object here is
                            // exactly how the chip would silently never update.
                            val value = if (frame.isNull("value")) null else frame.opt("value")
                            when (key) {
                                "tokenUsage" -> applyProjection(value as? JSONObject, null, null, null)
                                "contextPressure" -> applyProjection(null, value as? JSONObject, null, null)
                                // Passed as `JSONObject.NULL` rather than Kotlin null
                                // so a *cleared* projection blanks the chip. `null`
                                // is reserved for "this frame said nothing about the
                                // mode", which must leave the value alone.
                                "agentPreset" -> applyProjection(null, null, null, value ?: JSONObject.NULL)
                                // The goal and todo frames are passed straight through:
                                // a JSON null means "cleared" (goal finished, turn
                                // reset the list), an absent key never gets here.
                                "goal" -> applyProjection(null, null, null, null, value ?: JSONObject.NULL, null)
                                "todos" -> applyProjection(null, null, null, null, null, value ?: JSONObject.NULL)
                                else -> applyProjection(null, null, value as? JSONObject, null)
                            }
                        }
                    }
                }
        }
    }

    /**
     * Fold one or more projection values into the chip's state.
     *
     * A null argument means "this frame did not carry that key", not "the value
     * is empty" — the caller passes null for whichever projection the frame is
     * not about, and the existing value is preserved.
     *
     * A *present but null* `tokenUsage` is a real thing (the host can clear a
     * projection), and it maps to a zeroed total rather than being ignored.
     */
    private fun applyProjection(
        tokenUsage: JSONObject?,
        contextPressure: JSONObject?,
        permissions: JSONObject?,
        agentPreset: Any? = null,
        goal: Any? = null,
        todos: Any? = null,
    ) {
        val current = _state.value
        var next = current
        if (tokenUsage != null) {
            next = next.copy(usage = DshClient.UsageSnapshot.usageOf(tokenUsage), usageKnown = true)
        }
        if (contextPressure != null) {
            val (tokens, window) = DshClient.UsageSnapshot.pressureOf(contextPressure)
            next = next.copy(contextTokens = tokens, contextWindow = window)
        }
        if (permissions != null) {
            // The projection's view is `{currentValue}`. A present-but-null value
            // is a cleared projection, which is a host that no longer reports the
            // mode — so the chip hides rather than keeping a stale name.
            next = next.copy(
                activePermissionPreset = PermissionPresets.presetOf(permissions).orEmpty(),
            )
        }
        // `null` means the frame said nothing about the mode, which preserves the
        // current value. A *cleared* projection arrives as `JSONObject.NULL` — which
        // `opt` returns for a JSON null and never for an absent key — so it reaches
        // `presetOf` as a non-null, unrecognised value and resolves to a blank id.
        // That is why the caller passes the value straight through instead of
        // pre-collapsing nulls.
        if (agentPreset != null) {
            next = next.copy(activeAgentPreset = AgentPresets.presetOf(agentPreset).orEmpty())
        }
        // Same contract as the mode for `goal` and `todos`: Kotlin null preserves,
        // `JSONObject.NULL` clears, a real value replaces. `todos` also collapses a
        // cleared projection to an empty list rather than null, so the sheet can
        // tell "this turn has no list" from "the host does not offer the tool".
        if (goal != null) {
            next = next.copy(goal = GoalState.currentOf(goal))
        }
        if (todos != null) {
            next = next.copy(todos = TodoItem.listOf(todos).orEmpty())
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
                projectionSeqs["agentPreset"] = snapshot.asOfSeq
                projectionSeqs["goal"] = snapshot.asOfSeq
                projectionSeqs["todos"] = snapshot.asOfSeq
                _state.value = _state.value.copy(
                    usage = snapshot.total,
                    contextTokens = snapshot.contextTokens,
                    contextWindow = snapshot.contextWindow,
                    usageKnown = true,
                    // Only overwritten when the host reported one: a null here
                    // means the projection is absent, and clearing the chip on
                    // that basis would hide a preset that is still in effect.
                    activePermissionPreset = snapshot.permissionPreset
                        ?: _state.value.activePermissionPreset,
                    // Same rule, and it matters more here: a session whose mode the
                    // host does not report must not have the *previous* session's
                    // mode left on the chip.
                    activeAgentPreset = snapshot.agentPreset
                        ?: _state.value.activeAgentPreset,
                    // The goal section is authoritative when the response carries
                    // the key at all; an absent key (host without the projection)
                    // keeps whatever the live stream already delivered. `todos`
                    // follows the same rule.
                    goal = snapshot.goal ?: _state.value.goal,
                    todos = snapshot.todos ?: _state.value.todos,
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
        if (origin.isBlank()) return
        if (eventsJob?.isActive == true) return
        // A re-opened stream is issued a fresh identity, and until its `ready`
        // frame lands there is nothing valid to answer with. Clearing it makes an
        // answer attempted in that window fail rather than quote a dead id.
        eventsClientId = ""
        eventsJob = viewModelScope.launch {
            // Re-opened, not opened once.
            //
            // `forwardedEvents` already retries a dead *socket* internally, but it
            // gives up after its own budget (`MAX_STREAM_RETRIES`), and it also
            // completes normally when the host ends the stream. Either way the
            // collector returns, `.catch { }` swallows the reason, and the job
            // finishes — after which nothing ever subscribed again. The question
            // stream was then dead for the rest of the process's life, so a turn
            // that asked something could never be answered and the only cure was
            // restarting the app. A phone sleeps, changes network and loses Wi-Fi
            // constantly, so this was reachable on any long-lived session.
            //
            // Re-arming is safe: a re-delivered waterfall is deduplicated by
            // `eventId` below, and the host back-fills still-pending questions to
            // whichever stream is open, so a question asked during the gap is not
            // lost.
            while (isActive) {
                // Cleared *inside* the loop, not only before it.
                //
                // A `clientId` identifies one stream generation and dies with it.
                // Clearing before the loop covers the first attempt only: on every
                // re-arm the id from the stream that just ended stayed in the
                // field, so a question still on screen — the host re-delivers it,
                // so it *is* still on screen — could be answered with a dead id.
                // The gateway finds no delivery for that pair and answers success,
                // so the user's answer would be accepted and then silently
                // dropped, with the question reappearing as though they had never
                // tapped. Re-arming therefore has to start from no identity at all.
                eventsClientId = ""
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
                                // Two waterfalls share this stream: the agent's
                                // questions and the host's permission requests.
                                // They are told apart by the `event` name, which is
                                // the same field the desktop's listeners key on.
                                // A frame that is neither is ignored rather than
                                // guessed at — the vocabulary is plugin-extensible.
                                when (frame.optString("event")) {
                                    UserQuestion.EVENT_NAME -> {
                                        val question = UserQuestion.fromFrame(frame, eventsClientId)
                                            ?: return@collect
                                        // Only the active session's question is shown. A
                                        // question for another session is the host's to hold;
                                        // it will still be pending when the user switches,
                                        // and the host re-delivers pending waterfalls to a
                                        // newly opened stream.
                                        if (question.agentId != _state.value.activeSessionId) {
                                            return@collect
                                        }
                                        // Queue rather than replace. The host delivers each
                                        // waterfall once per stream, so a question dropped
                                        // here could never be answered — it would sit blocked
                                        // until its wait expired. A duplicate is ignored so a
                                        // re-delivered frame does not ask the same thing twice.
                                        val queue = _state.value.pendingQuestions
                                        if (queue.any { it.eventId == question.eventId }) {
                                            return@collect
                                        }
                                        _state.value = _state.value.copy(
                                            pendingQuestions = queue + question,
                                        )
                                    }

                                    ApprovalRequest.EVENT_NAME -> {
                                        val approval = ApprovalRequest.fromFrame(frame, eventsClientId)
                                            ?: return@collect
                                        // Same session filter and same reason as a
                                        // question: the prompt is about *this*
                                        // conversation's turn, and a request for
                                        // another session is re-delivered when the
                                        // user switches to it.
                                        if (approval.agentId != _state.value.activeSessionId) {
                                            return@collect
                                        }
                                        val queue = _state.value.pendingApprovals
                                        if (queue.any { it.eventId == approval.eventId }) {
                                            return@collect
                                        }
                                        _state.value = _state.value.copy(
                                            pendingApprovals = queue + approval,
                                        )
                                    }
                                }
                            }

                            "cancel" -> {
                                // The host settles a request elsewhere — the
                                // desktop answered first, or the wait expired — and
                                // broadcasts a `cancel` for it. Both queues are
                                // filtered because the two kinds share this frame
                                // shape and the id spaces are separate; a cancel
                                // that matches nothing is simply ignored.
                                val eventId = frame.optString("eventId")
                                val questions = _state.value.pendingQuestions
                                val approvals = _state.value.pendingApprovals
                                if (questions.none { it.eventId == eventId } &&
                                    approvals.none { it.eventId == eventId }
                                ) {
                                    return@collect
                                }
                                _state.value = _state.value.copy(
                                    pendingQuestions = questions.filterNot { it.eventId == eventId },
                                    pendingApprovals = approvals.filterNot { it.eventId == eventId },
                                )
                            }
                        }
                    }
                // The stream ended. Pause before re-arming, and do not depend on
                // the failure to be slow: `stream` gives up immediately when the
                // socket cannot be opened at all (a dead desktop, a rejected
                // credential), and re-subscribing in a tight loop would then spin
                // a core and hammer the gateway with handshakes. The delay also
                // stops a genuinely closed endpoint from turning into a hot loop,
                // while still reconnecting within a second of a transient drop.
                delay(EVENTS_RETRY_DELAY_MS)
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
     * Answer the pending approval request.
     *
     * The outcome is a **bare string** on the wire, not an object: the host's
     * listener returns `'allowed-once' | 'rejected'` directly, and the gateway
     * forwards whatever lossless JSON value the listener produced. That is the one
     * place this differs from answering a question, and it is why
     * [DshClient.answerForwardedEvent] takes `Any?` rather than a `JSONObject`.
     *
     * The prompt is hidden immediately and put back only if the reply never
     * reached the host — the same optimistic-then-restore shape as a question. A
     * round trip is long enough that leaving the card up makes the tap look
     * ignored, and restoring it on failure is what keeps a lost reply from
     * silently becoming a rejection: the host fails closed, so an answer that
     * vanished would look to the user like the assistant simply refused to act.
     *
     * @param dismiss true to hide the prompt without answering. The host's
     *   waterfall is deliberately left running, so the desktop's own answerer can
     *   still decide; declining here is not a rejection, and sending one would be
     *   the phone overruling a decision it was never asked to make.
     */
    fun answerApproval(decision: ApprovalRequest.Decision?, dismiss: Boolean = false) {
        val approval = _state.value.pendingApproval ?: return
        _state.value = _state.value.copy(
            pendingApprovals = _state.value.pendingApprovals.filterNot {
                it.eventId == approval.eventId
            },
        )
        if (dismiss || decision == null) return

        val origin = prefs.serverUrl
        val clientId = eventsClientId
        viewModelScope.launch {
            try {
                dsh.answerForwardedEvent(origin, clientId, approval.eventId, decision.wire)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Same reasoning as a question: the host is still waiting, so the
                // request goes back at the front and the failure is reported.
                val queue = _state.value.pendingApprovals
                if (queue.none { it.eventId == approval.eventId }) {
                    _state.value = _state.value.copy(
                        pendingApprovals = listOf(approval) + queue,
                        error = "授权结果没有送达桌面端，请重试。",
                    )
                }
            }
        }
    }

    // ------------------------------------------------------- permission presets

    /**
     * Load the deployment's permission presets, once.
     *
     * The catalog is deployment-wide, so it is fetched the first time it is needed
     * and reused for every session afterwards. A failure is reported through the
     * usual transport-aware path rather than as a hard error: the chip simply
     * stays hidden, which is honest — the app cannot say what modes exist.
     */
    fun loadPermissionPresets() {
        loadPermissionPresets(force = false)
    }

    /**
     * Load the permission catalog.
     *
     * @param force re-read even when a table is already in hand. The dialog's own
     *   refresh uses this: the whole point of asking again is that the first
     *   answer was empty or wrong, and a cache-hit early return would make the
     *   button do nothing at all.
     */
    private fun loadPermissionPresets(force: Boolean) {
        val origin = prefs.serverUrl
        if (origin.isBlank()) return
        if (_state.value.loadingPermissions) return
        if (!force && _state.value.permissionPresets.isNotEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingPermissions = true)
            val result = try {
                Result.success(dsh.permissionCatalog(origin))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Result.failure(t)
            }
            result
                .onSuccess { catalog ->
                    _state.value = _state.value.copy(
                        loadingPermissions = false,
                        permissionPresets = catalog.options,
                    )
                }
                .onFailure {
                    // A host without the plugin answers with an error; that is a
                    // capability that is absent, not a broken connection, so it is
                    // not worth a banner. The chip's own visibility is the report.
                    _state.value = _state.value.copy(loadingPermissions = false)
                }
        }
    }

    /**
     * Switch the active session's permission preset.
     *
     * The switch is a `/permission <preset>` command rather than an RPC, because
     * that is the only write the package offers — see
     * [DshClient.selectPermissionPreset]. The new value is *not* written to the
     * state optimistically: the authoritative value is the `permissions`
     * projection, and the plugin commits a `permission/preset` event that the
     * `session/control` stream folds into it. Setting it locally first would make
     * the chip disagree with the session whenever the command was refused.
     */
    fun selectPermissionPreset(preset: String) {
        val origin = prefs.serverUrl
        val sessionId = _state.value.activeSessionId ?: return
        if (_state.value.switchingPermission) return
        viewModelScope.launch {
            _state.value = _state.value.copy(switchingPermission = true, error = null)
            try {
                val text = dsh.selectPermissionPreset(origin, sessionId, preset)
                _state.value = _state.value.copy(
                    switchingPermission = false,
                    showPermissionPicker = false,
                    info = text ?: "已切换权限模式：$preset",
                )
                // The command's own result is not the projection: re-read it so the
                // chip reflects what the session actually committed.
                refreshUsage(sessionId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    switchingPermission = false,
                    error = t.message ?: "切换权限模式失败",
                )
            }
        }
    }

    /** Open the permission picker, fetching the catalog if it is not loaded yet. */
    fun openPermissionPicker() {
        _state.value = _state.value.copy(showPermissionPicker = true)
        loadPermissionPresets()
    }

    /**
     * Re-read the catalog for the picker's own refresh button.
     *
     * Forces the read rather than reusing the cache, for the reason above: the
     * only thing a user does with this button is retry a load that gave them
     * nothing.
     */
    fun reloadPermissionPresets() {
        loadPermissionPresets(force = true)
    }

    /** Close the permission picker. */
    fun closePermissionPicker() {
        _state.value = _state.value.copy(showPermissionPicker = false)
    }

    // ---------------------------------------------------------- agent presets

    /**
     * Load the deployment's Agent presets, once.
     *
     * The roster is deployment-wide, so it is fetched the first time it is needed
     * and reused for every session afterwards. A failure is reported through the
     * usual transport-aware path rather than as a hard error: the chip simply
     * stays hidden, which is honest — the app cannot say what modes exist.
     */
    fun loadAgentPresets() {
        loadAgentPresets(force = false)
    }

    /**
     * Load the Agent preset roster.
     *
     * @param force re-read even when a roster is already in hand. The picker's own
     *   refresh uses this: the whole point of asking again is that the first answer
     *   was empty or wrong, and a cache-hit early return would make the button do
     *   nothing at all.
     */
    private fun loadAgentPresets(force: Boolean) {
        val origin = prefs.serverUrl
        if (origin.isBlank()) return
        if (_state.value.loadingAgentPresets) return
        if (!force && _state.value.agentPresets.isNotEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingAgentPresets = true)
            val result = try {
                Result.success(dsh.agentPresetRoster(origin))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Result.failure(t)
            }
            result
                .onSuccess { roster ->
                    // The remembered choice must still exist, or every new session
                    // would ask the server for a preset it has never heard of.
                    val remembered = prefs.agentPreset
                    val valid = roster.presets.any { it.id == remembered }
                    _state.value = _state.value.copy(
                        loadingAgentPresets = false,
                        agentPresets = roster.presets,
                        // Resolution order: the user's remembered choice, then the
                        // host's own default, then the shipped fallback. Never
                        // blank once a roster exists, because a create with no
                        // preset would silently take whatever the deployment's
                        // default happens to be — a choice made by nobody.
                        selectedAgentPreset = when {
                            valid -> remembered
                            roster.defaultId.isNotBlank() -> roster.defaultId
                            else -> roster.presets.firstOrNull()?.id.orEmpty()
                        },
                    )
                }
                .onFailure {
                    // A host without the registry answers with an error; that is a
                    // capability that is absent, not a broken connection, so it is
                    // not worth a banner. The chip's own visibility is the report.
                    _state.value = _state.value.copy(loadingAgentPresets = false)
                }
        }
    }

    /**
     * Switch the active session's mode.
     *
     * The switch is refused by the host once the session has started
     * (`agent-preset/locked`), so it is also refused here up front — the user gets
     * a sentence telling them to start a new conversation instead of a round trip
     * that ends in the server's English-only refusal.
     *
     * The value is *not* written to the state optimistically. The authoritative
     * value is the `agentPreset` projection, which the host commits with an
     * `agent-preset/selected` event; setting it locally first would make the chip
     * disagree with the session whenever the switch was refused. What *is*
     * remembered is [Prefs.agentPreset], which only affects the *next* session.
     */
    fun selectAgentPreset(preset: String) {
        val origin = prefs.serverUrl
        val sessionId = _state.value.activeSessionId
        if (_state.value.switchingAgentPreset) return

        // No session yet: this is the "choose the mode for the conversation I am
        // about to start" case, which is the common one. Nothing to switch on the
        // server — the choice rides `session/create`.
        if (sessionId == null) {
            prefs.agentPreset = preset
            _state.value = _state.value.copy(
                selectedAgentPreset = preset,
                showAgentPresetPicker = false,
                info = "新模式将在下一个会话中生效：${agentPresetLabel(preset)}",
            )
            return
        }

        if (_state.value.agentPresetLocked) {
            // Also remembered, so "new session" after this carries the intent over
            // rather than making the user pick again.
            prefs.agentPreset = preset
            _state.value = _state.value.copy(
                selectedAgentPreset = preset,
                showAgentPresetPicker = false,
                error = "这个会话已经开始了，模式只能在对话开始前选择。" +
                    "已记住该模式，新建会话时会用它。",
            )
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(switchingAgentPreset = true, error = null)
            try {
                val committed = dsh.selectAgentPreset(origin, sessionId, preset)
                prefs.agentPreset = committed
                _state.value = _state.value.copy(
                    switchingAgentPreset = false,
                    showAgentPresetPicker = false,
                    selectedAgentPreset = committed,
                    info = "已切换模式：${agentPresetLabel(committed)}",
                )
                // The command's own result is not the projection: re-read it so the
                // chip reflects what the session actually committed.
                refreshUsage(sessionId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    switchingAgentPreset = false,
                    error = t.message ?: "切换模式失败",
                )
            }
        }
    }

    /** The roster's label for an id, falling back to the shipped copy then the id. */
    private fun agentPresetLabel(id: String): String =
        _state.value.agentPresets.firstOrNull { it.id == id }?.label
            ?: AgentPresets.builtInName(id)
            ?: id

    /** Open the mode picker, fetching the roster if it is not loaded yet. */
    fun openAgentPresetPicker() {
        _state.value = _state.value.copy(showAgentPresetPicker = true)
        loadAgentPresets()
    }

    /**
     * Re-read the roster for the picker's own refresh button.
     *
     * Forces the read rather than reusing the cache, for the same reason as the
     * permission picker: the only thing a user does with this button is retry a
     * load that gave them nothing.
     */
    fun reloadAgentPresets() {
        loadAgentPresets(force = true)
    }

    /** Close the mode picker. */
    fun closeAgentPresetPicker() {
        _state.value = _state.value.copy(showAgentPresetPicker = false)
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
                // Context and notices are not part of the API-mode conversation:
                // they are this app's rendering of the *remote* host's injected
                // material, and re-sending them as turns would put words in the
                // user's mouth on a completely different backend.
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
                    // The catalog is the slowest unary call the app makes, so a
                    // timeout here is the *most* likely of all of them to be the
                    // desktop being busy rather than unreachable — which is
                    // exactly the report this flag exists to avoid.
                    _state.value = _state.value.copy(
                        loadingModels = false,
                        error = transportAwareError(it.message ?: "无法获取模型列表", it),
                        errorIsTransport = isTransportError(it),
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
                    // Same reasoning as `refreshSessions`: this runs on every
                    // reconnect and on opening the workspace picker, so one lost
                    // race with the radio waking up must not be reported as a
                    // broken connection. A live socket suppresses it entirely.
                    _state.value = _state.value.copy(
                        loadingWorkspaces = false,
                        error = transportAwareError(it.message ?: "无法获取工作区列表", it),
                        errorIsTransport = isTransportError(it),
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

    // ---------------------------------------------------------- workspace files

    /**
     * Open the file browser at the active session's workspace root.
     *
     * The scope is the **session** id, not the workspace id: `workspaceFiles`
     * resolves its first argument as a session lookup and takes that session's
     * `cwd` as the root. A workspace id names something else entirely and the
     * gateway answers with a lookup failure rather than listing the wrong place.
     *
     * A session is required, so this is a no-op without one — there is no
     * workspace to browse until a conversation exists to define it, and guessing
     * the sandbox root would show files the session cannot actually reach.
     */
    fun openFiles(path: String = "") {
        val sessionId = _state.value.activeSessionId ?: run {
            _state.value = _state.value.copy(
                files = WorkspaceListing(path, emptyList(), false),
                filesPath = path,
                openFile = null,
                openFilePath = "",
                filesError = "请先打开或新建一个会话，文件浏览以该会话的工作区为根目录。",
            )
            return
        }
        val origin = prefs.serverUrl
        if (origin.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                filesLoading = true,
                filesError = null,
                openFile = null,
                openFilePath = "",
            )
            try {
                val listing = dsh.listWorkspaceFiles(origin, sessionId, path)
                _state.value = _state.value.copy(
                    files = listing,
                    filesPath = path,
                    filesLoading = false,
                    filesError = null,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    filesLoading = false,
                    // Keep whatever listing is already on screen: a failed
                    // descent should not also throw away the directory the user
                    // was reading, which would leave them with nothing to go back
                    // to.
                    filesError = t.message ?: "无法读取该目录",
                )
            }
        }
    }

    /** Open a subdirectory from the listing currently on screen. */
    fun openFilesChild(name: String) {
        openFiles(WorkspaceFiles.childOf(_state.value.filesPath, name))
    }

    /** Go up one level, or do nothing at the workspace root. */
    fun openFilesParent() {
        val parent = WorkspaceFiles.parentOf(_state.value.filesPath) ?: return
        openFiles(parent)
    }

    /** Read one file from the workspace into the viewer. */
    fun openWorkspaceFile(path: String) {
        val sessionId = _state.value.activeSessionId ?: return
        val origin = prefs.serverUrl
        if (origin.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(filesLoading = true, filesError = null)
            try {
                val page = dsh.readWorkspaceFile(origin, sessionId, path)
                _state.value = _state.value.copy(
                    openFile = page,
                    openFilePath = path,
                    filesLoading = false,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    filesLoading = false,
                    filesError = t.message ?: "无法读取该文件",
                )
            }
        }
    }

    /** Close the opened file and return to the listing. */
    fun closeWorkspaceFile() {
        _state.value = _state.value.copy(openFile = null, openFilePath = "")
    }

    /** Close the whole file browser. */
    fun closeFiles() {
        _state.value = _state.value.copy(
            files = null,
            filesPath = "",
            filesLoading = false,
            openFile = null,
            openFilePath = "",
            filesError = null,
        )
    }

    // --------------------------------------------------------- delivered files

    /**
     * Show the files the assistant delivered.
     *
     * The list is derived from the transcript, so this only has to open the sheet —
     * there is nothing to fetch and nothing to fail. Opening a *file* from it goes
     * through the ordinary [openWorkspaceFile], which is the same read the inline
     * chips use, so a file reached from here and one reached from its tool card
     * cannot render differently.
     */
    fun openDeliverables() {
        _state.value = _state.value.copy(showDeliverables = true, filesError = null)
    }

    /** Close the delivered-files sheet. */
    fun closeDeliverables() {
        _state.value = _state.value.copy(showDeliverables = false)
    }

    /** Open the goal/todo sheet. */
    fun openGoalTasks() {
        _state.value = _state.value.copy(showGoalTasks = true)
    }

    /** Close the goal/todo sheet. */
    fun closeGoalTasks() {
        _state.value = _state.value.copy(showGoalTasks = false)
    }

    // ------------------------------------------------------------------- copy

    /**
     * Copy one message, for the long-press action on a bubble.
     *
     * 1.2.1 copies the **plain text of the bubble** — the words the user can see
     * — rather than the Markdown export. The export format is written for a
     * reader outside the app (role headings, timestamps, quoted reasoning, fenced
     * tool JSON), and pasting all of that into a normal input box is what the
     * 乱码 report describes: characters that were never on screen wrapped around
     * the words that were.
     *
     * [Transcript.messageMarkdown] stays as the documented export format and the
     * whole-conversation renderer contract; the copy sheet's selectable body and
     * 「复制全部」 both carry the plain text now, because "copy what I am reading"
     * and "export this conversation" are different needs and only the first has
     * a gesture.
     */
    fun copyMessage(message: Message, onReady: (String) -> Unit) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.Default) {
                Transcript.messagePlainText(message).trim()
            }
            if (text.isNotBlank()) onReady(text)
        }
    }

    /**
     * Expand or collapse one workspace group in the drawer.
     *
     * The key is the group's workspace id, with the empty string naming the
     * fallback group — the same key [SessionTree.group] produces, so the two cannot
     * disagree about which group is open.
     */
    fun toggleGroup(workspaceId: String) {
        val current = _state.value.expandedGroups
        _state.value = _state.value.copy(
            expandedGroups = if (workspaceId in current) current - workspaceId
            else current + workspaceId,
        )
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
         * The projections the chips read.
         *
         * Named once because three places must agree about them: the baseline
         * seeding, the live frame filter, and the one-shot read's watermark. A key
         * missing from any one of those is a chip that silently stops updating.
         */
        val WATCHED_PROJECTIONS = setOf(
            "tokenUsage",
            "contextPressure",
            "permissions",
            // The mode. Watched for the same reason as the permission preset: it is
            // per-session state that the host commits with its own event, so the
            // chip has to follow the projection rather than the tap.
            "agentPreset",
            // 1.2.1: the session's current goal and its todo list. Both are
            // projection units driven by durable events (`goal/change`, the goal
            // injection, and `todo/write`), so the live control stream carries
            // their changes — watching them here is what keeps the sheet current
            // while a turn works, without a poll.
            "goal",
            "todos",
        )

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

        /**
         * How long to wait before re-subscribing to the forwarded-event stream.
         *
         * Long enough that a permanently closed endpoint cannot become a hot loop
         * (the collector returns immediately when the socket will not open), short
         * enough that a transient drop does not leave `ask_user_question`
         * unanswerable for a noticeable time. The host back-fills pending
         * waterfalls to a stream that opens late, so a question asked during the
         * pause still arrives.
         */
        const val EVENTS_RETRY_DELAY_MS = 1_000L

        /**
         * How long to wait before re-subscribing to the active session's log.
         *
         * The same trade-off as [EVENTS_RETRY_DELAY_MS], and it exists for the
         * same reason: `session/follow` completes *normally* when the host ends the
         * stream, so without a pause a host that closes an idle session's stream
         * immediately would be re-subscribed in a hot loop. Long enough not to
         * spin, short enough that a conversation the desktop is actively running is
         * never stale for a noticeable time.
         */
        const val FOLLOW_RETRY_DELAY_MS = 700L
    }
}
