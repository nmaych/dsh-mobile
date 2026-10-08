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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.deepseek.dshmobile.data.Backend
import ai.deepseek.dshmobile.ui.components.ModelPickerDialog
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
) {
    var showSettings by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showWorkspacePicker by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

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

@Composable
private fun SessionDrawer(
    state: ChatUiState,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWorkspacePicker: () -> Unit,
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
                    else -> "已连接 · ${state.sessions.size} 个会话"
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
                items(state.sessions, key = { it.id }) { row ->
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
                                        row.cwd?.let { append("  ·  ") ; append(shortPath(it)) }
                                    },
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        selected = row.id == state.activeSessionId,
                        onClick = { onOpenSession(row.id) },
                        icon = {
                            Icon(
                                Icons.Default.ChatBubbleOutline,
                                contentDescription = null,
                                modifier = Modifier.width(18.dp),
                            )
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            unselectedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                    )
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

private fun shortPath(path: String): String {
    val parts = path.replace('\\', '/').trimEnd('/').split('/').filter { it.isNotBlank() }
    return parts.takeLast(2).joinToString("/")
}
