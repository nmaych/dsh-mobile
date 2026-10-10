package ai.deepseek.dshmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.deepseek.dshmobile.data.Backend
import ai.deepseek.dshmobile.data.Message
import ai.deepseek.dshmobile.data.QuestionAnswer
import ai.deepseek.dshmobile.ui.components.FilesDialog
import ai.deepseek.dshmobile.ui.components.ModelPickerDialog
import ai.deepseek.dshmobile.ui.components.QuestionDialog
import ai.deepseek.dshmobile.ui.components.TranscriptFilesDialog
import ai.deepseek.dshmobile.ui.components.WorkspaceDrawerRow
import ai.deepseek.dshmobile.ui.components.WorkspacePickerDialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AppShell(
    state: ChatUiState,
    serverUrl: String,
    apiBaseUrl: String,
    apiKey: String,
    apiSystemPrompt: String,
    updateManifestUrl: String,
    updateState: UpdateStateHolder,
    appVersion: String,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
    onBackendChange: (Backend) -> Unit,
    onPair: (String) -> Unit,
    onPairWithCode: (String, String) -> Unit,
    onDiscover: () -> Unit,
    onPickServer: (String) -> Unit,
    onPairLink: (String) -> Unit,
    onUnpair: () -> Unit,
    onReconnect: () -> Unit,
    onApiConfigChange: (String, String, String, String) -> Unit,
    onLoadModels: () -> Unit,
    onSelectModel: (String) -> Unit,
    onLoadRemoteModels: () -> Unit,
    onSelectRemoteModel: (String, String, String?) -> Unit,
    onRefreshWorkspaces: () -> Unit,
    onSelectWorkspace: (String) -> Unit,
    onAddWorkspace: (String) -> Unit,
    onUpdateManifestChange: (String) -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onAnswerQuestion: (List<QuestionAnswer>, Boolean) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenFilesChild: (String) -> Unit,
    onOpenWorkspaceFile: (String) -> Unit,
    onCloseWorkspaceFile: () -> Unit,
    onFilesUp: () -> Unit,
    onFilesReload: () -> Unit,
    onCloseFiles: () -> Unit,
    /**
     * Copy text to the clipboard, returning it to the caller to place.
     *
     * The rendering happens in the view model (a transcript is large, and
     * building it on the main thread would drop frames during the tap), so the
     * callback is how the finished string gets here to be handed to the
     * `ClipboardManager` — which needs a `Context` the view model does not have.
     */
    onCopyTranscript: (((String) -> Unit)) -> Unit,
    onCopyMessage: (Message, (String) -> Unit) -> Unit,
    onOpenTranscriptFiles: () -> Unit,
    onCloseTranscriptFiles: () -> Unit,
    onToggleGroup: (String) -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showWorkspacePicker by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // The agent is *blocked* on this question: its tool call is suspended inside
    // the host's waterfall, so the turn cannot advance until it is answered or the
    // host's wait expires. It is therefore composed above the settings early
    // return, because a question the user cannot see is a turn that cannot finish —
    // and a user who wandered into settings is exactly the user who would never
    // find out why the conversation stopped.
    state.pendingQuestion?.let { pending ->
        QuestionDialog(
            question = pending,
            onAnswer = { answers -> onAnswerQuestion(answers, false) },
            onDismiss = { onAnswerQuestion(emptyList(), true) },
        )
    }

    // The files this conversation's tool calls named. Composed beside the
    // question dialog and for the same reason: it is opened from the transcript's
    // app bar, so a result that arrives while the user is in settings must still
    // be shown rather than silently dropped.
    if (state.showTranscriptFiles) {
        TranscriptFilesDialog(
            files = state.transcriptFiles,
            onOpen = { path ->
                onCloseTranscriptFiles()
                onOpenWorkspaceFile(path)
            },
            onBrowse = {
                onCloseTranscriptFiles()
                onOpenFiles()
            },
            onDismiss = onCloseTranscriptFiles,
        )
    }

    // The file browser, composed above the settings early return for the same
    // reason as the question dialog: it is opened from the transcript's app bar,
    // but a user who wandered into settings while it was loading must still see
    // the result of what they asked for rather than a screen that quietly
    // dropped it.
    if (state.filesOpen) {
        FilesDialog(
            listing = state.files,
            path = state.filesPath,
            loading = state.filesLoading,
            openFile = state.openFile,
            openFilePath = state.openFilePath,
            error = state.filesError,
            canGoUp = state.filesPath.isNotBlank(),
            onOpenChild = onOpenFilesChild,
            onOpenFile = onOpenWorkspaceFile,
            onUp = onFilesUp,
            onCloseFile = onCloseWorkspaceFile,
            onReload = onFilesReload,
            onDismiss = onCloseFiles,
        )
    }

    if (showSettings) {
        SettingsScreen(
            state = state,
            serverUrl = serverUrl,
            apiBaseUrl = apiBaseUrl,
            apiKey = apiKey,
            apiSystemPrompt = apiSystemPrompt,
            updateManifestUrl = updateManifestUrl,
            updateState = updateState.value,
            appVersion = appVersion,
            onBack = { showSettings = false },
            onBackendChange = onBackendChange,
            onPair = onPair,
            onPairWithCode = onPairWithCode,
            onDiscover = onDiscover,
            onPickServer = onPickServer,
            onPairLink = onPairLink,
            onUnpair = onUnpair,
            onReconnect = onReconnect,
            onApiConfigChange = onApiConfigChange,
            onLoadModels = onLoadModels,
            onSelectModel = onSelectModel,
            onUpdateManifestChange = onUpdateManifestChange,
            onCheckUpdate = onCheckUpdate,
            onInstallUpdate = onInstallUpdate,
        )
        return
    }

    // Opening the picker is what fetches the catalog. Doing this in the click
    // handler rather than a `LaunchedEffect` keyed on "the list is empty" avoids
    // a retry loop: a failed fetch leaves the list empty, which would re-enter
    // the effect and fire again, forever.
    val openModelPicker = {
        showModelPicker = true
        if (state.remoteModels.isEmpty()) onLoadRemoteModels()
    }

    if (showModelPicker) {
        ModelPickerDialog(
            models = state.remoteModels,
            currentModel = state.activeModel,
            currentEffort = state.activeEffort,
            loading = state.loadingModels,
            error = state.error,
            onDismiss = { showModelPicker = false },
            onLoad = onLoadRemoteModels,
            onPick = { provider, model, effort ->
                showModelPicker = false
                onSelectRemoteModel(provider, model, effort)
            },
        )
    }

    if (showWorkspacePicker) {
        WorkspacePickerDialog(
            workspaces = state.workspaces,
            selectedId = state.selectedWorkspaceId,
            activeId = state.activeWorkspaceId,
            loading = state.loadingWorkspaces,
            onDismiss = { showWorkspacePicker = false },
            onReload = onRefreshWorkspaces,
            onPick = {
                onSelectWorkspace(it)
                showWorkspacePicker = false
            },
            onAdd = onAddWorkspace,
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionDrawer(
                state = state,
                onNewSession = {
                    onNewSession()
                    scope.launch { drawerState.close() }
                },
                onOpenSession = {
                    onOpenSession(it)
                    scope.launch { drawerState.close() }
                },
                onRefresh = onRefresh,
                onOpenSettings = {
                    showSettings = true
                    scope.launch { drawerState.close() }
                },
                onOpenWorkspacePicker = { showWorkspacePicker = true },
                onToggleGroup = onToggleGroup,
            )
        },
    ) {
        ChatScreen(
            state = state,
            onSend = onSend,
            onStop = onStop,
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onOpenSettings = { showSettings = true },
            onNewSession = onNewSession,
            onRefresh = onRefresh,
            onDismissError = onDismissError,
            onOpenModelPicker = openModelPicker,
            onOpenWorkspacePicker = { showWorkspacePicker = true },
            onOpenFiles = onOpenFiles,
            onCopyTranscript = onCopyTranscript,
            onCopyMessage = onCopyMessage,
            onOpenTranscriptFiles = onOpenTranscriptFiles,
        )
    }

    // Ask the drawer to refresh the first time it becomes usable.
    LaunchedEffect(state.backend, state.connected) {
        if (state.backend == Backend.REMOTE && state.connected) {
            onRefresh()
        }
    }
}

/** Tiny observable holder so the settings screen can read update progress. */
class UpdateStateHolder(initial: ai.deepseek.dshmobile.update.UpdateState) {
    var value by mutableStateOf(initial)
}

/**
 * The session drawer: a workspace tree with collapsible groups.
 *
 * ## Shape
 *
 * One header per workspace, with that workspace's conversations nested under it
 * and indented. The header is the whole point of the change — the user's own
 * project is now a place in the list rather than a small grey second line they
 * have to read on every row to find it.
 *
 * ## Collapse
 *
 * A group longer than [SessionTree.COLLAPSE_AFTER] shows only its newest few, with
 * a row that reveals the rest. Grouping alone would have made the drawer *taller*
 * (every workspace now costs a header) while leaving a forty-conversation project
 * as forty rows, so the two changes only work together.
 *
 * The expansion lives in the view model rather than in a `remember` here: this
 * composable is rebuilt on every state change — a token delta recomposes the whole
 * shell — and a local `remember` keyed to nothing would be lost the moment the
 * session list was replaced by a refresh, which is exactly when the user would
 * still be reading it.
 */
@Composable
private fun SessionDrawer(
    state: ChatUiState,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWorkspacePicker: () -> Unit,
    onToggleGroup: (String) -> Unit,
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "会话",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新", modifier = Modifier.width(20.dp))
                }
                IconButton(onClick = onNewSession) {
                    Icon(Icons.Default.Add, contentDescription = "新建会话")
                }
            }
            Text(
                when {
                    state.backend != Backend.REMOTE -> "当前为独立 API 模式"
                    !state.connected -> "未连接桌面端"
                    // Named by project as well as by count: with a tree, "how many
                    // projects" is the more useful summary of the list's shape.
                    else -> {
                        val groups = state.sessionGroups
                        val projects = groups.count { it.isWorkspace }
                        buildString {
                            append("已连接 · ${state.sessions.size} 个会话")
                            if (projects > 1) append(" · $projects 个工作区")
                        }
                    }
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The workspace is where the next new session lands, so it belongs next
        // to the "+" it affects rather than only inside settings.
        if (state.backend == Backend.REMOTE && state.connected) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            val selected = state.workspaces.firstOrNull { it.id == state.selectedWorkspaceId }
            WorkspaceDrawerRow(
                label = selected?.label ?: "默认工作区",
                path = selected?.path ?: "新建会话时由桌面端决定目录",
                onClick = onOpenWorkspacePicker,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

        if (state.sessions.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (state.backend == Backend.REMOTE) {
                        "暂无会话。\n在桌面端开始一个对话，或点右上角 + 新建。"
                    } else {
                        "独立 API 模式不使用桌面端会话。"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                val groups = state.sessionGroups
                for (group in groups) {
                    item(key = "h-${group.workspaceId}") {
                        WorkspaceGroupHeader(
                            group = group,
                            expanded = group.workspaceId in state.expandedGroups,
                            onClick = { onToggleGroup(group.workspaceId) },
                        )
                    }
                    // A collapsed group renders only its newest rows. `visible` is
                    // the group's own list, so the LazyColumn still keys every row
                    // by session id and scroll position survives a refresh.
                    val visible = if (group.workspaceId in state.expandedGroups || !group.collapsible) {
                        group.sessions
                    } else {
                        group.sessions.take(SessionTree.COLLAPSE_AFTER)
                    }
                    items(visible, key = { it.id }) { row ->
                        SessionRowItem(
                            row = row,
                            selected = row.id == state.activeSessionId,
                            onClick = { onOpenSession(row.id) },
                        )
                    }
                    if (group.collapsible && group.workspaceId !in state.expandedGroups) {
                        item(key = "m-${group.workspaceId}") {
                            ShowMoreRow(
                                count = group.collapsedCount,
                                onClick = { onToggleGroup(group.workspaceId) },
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onOpenSettings() }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.width(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(14.dp))
            Text("设置", fontSize = 13.sp)
        }
    }
}

/**
 * One workspace's header row.
 *
 * The chevron is drawn only when the group can actually be collapsed, so it never
 * promises a fold that would do nothing. The whole row is the target rather than
 * the chevron alone: the header is short and the chevron is a 14dp glyph, which is
 * well under a comfortable touch target.
 */
@Composable
private fun WorkspaceGroupHeader(
    group: SessionTree.Group,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = group.collapsible, onClick = onClick)
            .padding(start = 10.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            // A workspace the app knows about is a folder; the group that belongs
            // to no workspace is deliberately a different glyph, so "this is not
            // one of your projects" is visible without reading the label.
            if (group.isWorkspace) Icons.Default.FolderOpen else Icons.Default.FolderOff,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                group.label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (group.path.isNotBlank()) {
                Text(
                    group.path,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            "${group.sessions.size}",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (group.collapsible) {
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The row that reveals a collapsed group's remainder.
 *
 * It says how many are hidden rather than just "更多": with several long projects
 * open at once, the count is what tells the user which fold is worth opening.
 */
@Composable
private fun ShowMoreRow(count: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 30.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "还有 $count 个会话",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One conversation, indented under its workspace header.
 *
 * Indented by the header's icon width so the nesting is legible without a drawn
 * tree: the alignment alone carries the hierarchy, and a per-row connector would
 * cost width on a drawer that is already narrow.
 */
@Composable
private fun SessionRowItem(
    row: SessionRow,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = {
            Column {
                Text(
                    row.title,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(formatTime(row.updatedAt))
                        if (row.running) append("  ·  运行中")
                    },
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(
                // A running conversation is marked on the icon as well as in the
                // subtitle, because the subtitle is the first thing an ellipsis eats.
                if (row.running) Icons.Default.PlayCircleOutline
                else Icons.Default.ChatBubbleOutline,
                contentDescription = null,
                modifier = Modifier.width(18.dp),
                tint = if (row.running) MaterialTheme.colorScheme.primary
                else LocalContentColor.current,
            )
        },
        colors = NavigationDrawerItemDefaults.colors(
            unselectedContainerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier
            .padding(start = 18.dp, end = 8.dp)
            .padding(vertical = 1.dp),
    )
}

private fun formatTime(epochMillis: Long): String {
    if (epochMillis <= 0) return ""
    val now = System.currentTimeMillis()
    val diff = now - epochMillis
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000} 天前"
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(epochMillis))
    }
}
