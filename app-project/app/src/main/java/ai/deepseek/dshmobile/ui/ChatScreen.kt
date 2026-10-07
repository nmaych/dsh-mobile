package ai.deepseek.dshmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.deepseek.dshmobile.data.Backend
import ai.deepseek.dshmobile.ui.components.MessageBubble
import ai.deepseek.dshmobile.ui.components.ModelChip
import ai.deepseek.dshmobile.ui.components.UsageChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewSession: () -> Unit,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onOpenWorkspacePicker: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // The transcript is the durable log plus the provisional streaming draft.
    val rendered = remember(state.messages, state.liveDraft) {
        if (state.liveDraft != null) state.messages + state.liveDraft else state.messages
    }

    // Keep the newest message in view as tokens stream in.
    LaunchedEffect(rendered.size, rendered.lastOrNull()?.blocks?.size) {
        if (rendered.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(rendered.lastIndex) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.activeTitle.ifBlank {
                                if (state.backend == Backend.REMOTE) "DSH Mobile" else "API 对话"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 16.sp,
                        )
                        Text(
                            when {
                                state.connecting -> "连接中…"
                                state.backend == Backend.REMOTE && state.connected -> "已连接桌面端"
                                state.backend == Backend.REMOTE -> "未连接"
                                else -> state.selectedModel.ifBlank { "未选择模型" }
                            },
                            fontSize = 11.sp,
                            color = if (state.backend == Backend.REMOTE && state.connected) {
                                Color(0xFF4ADE80)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "会话列表")
                    }
                },
                actions = {
                    if (state.backend == Backend.REMOTE) {
                        IconButton(onClick = onNewSession) {
                            Icon(Icons.Default.Add, contentDescription = "新建会话")
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            InputBar(
                value = input,
                onValueChange = { input = it },
                running = state.running,
                onSend = {
                    val text = input
                    input = ""
                    onSend(text)
                },
                onStop = onStop,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Model and workspace are per-conversation facts, so they live in a
            // strip above the transcript rather than buried in settings.
            if (state.backend == Backend.REMOTE) {
                RemoteChipsRow(
                    state = state,
                    onOpenModelPicker = onOpenModelPicker,
                    onOpenWorkspacePicker = onOpenWorkspacePicker,
                )
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    !state.historyLoaded && state.activeSessionId != null -> LoadingState()
                    rendered.isEmpty() -> EmptyState(state)
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(rendered, key = { it.id }) { msg ->
                            MessageBubble(msg)
                        }
                    }
                }

                state.error?.let { error ->
                    ErrorBanner(
                        text = error,
                        onDismiss = onDismissError,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
    }
}

/**
 * The model / workspace / usage strip.
 *
 * Usage is shown for the active session only: before a session is open there is
 * nothing to account for, and a zeroed chip would read as "you have used
 * nothing" rather than "nothing is selected yet".
 *
 * Laid out with [FlowRow] rather than a `Row` with a weighted spacer. A weighted
 * spacer takes the *remainder* of the line for itself and leaves whatever is
 * left to its siblings, so on a narrow screen the usage chip was handed a few
 * pixels of width: its monospace readout wrapped one token per line and the chip
 * grew to ~900px tall, overflowing the app bar and the transcript. FlowRow
 * instead measures every chip at its natural width and moves a chip that does
 * not fit onto the next line, so nothing is starved and nothing is dropped.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoteChipsRow(
    state: ChatUiState,
    onOpenModelPicker: () -> Unit,
    onOpenWorkspacePicker: () -> Unit,
) {
    val workspace = state.workspaces.firstOrNull { it.id == state.selectedWorkspaceId }
    FlowRow(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ModelChip(
            // Never stand in a catalog entry for the active model: before a turn
            // reports one, or before the user picks one, no model is in effect
            // and saying otherwise is a lie the user cannot act on.
            label = state.activeModel.ifBlank { "选择模型" },
            loading = state.loadingModels || state.switchingModel,
            enabled = state.connected && !state.switchingModel,
            onClick = onOpenModelPicker,
            // Bound the chip so one long `provider/model` cannot swallow the
            // whole line and leave the other chips to wrap for no reason.
            modifier = Modifier.widthIn(max = 200.dp),
        )
        Surface(
            onClick = onOpenWorkspacePicker,
            enabled = state.connected,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            shape = RoundedCornerShape(6.dp),
            // Every chip in this strip is one text line tall, whatever the row's
            // height ends up being. Without it the incoming constraints let a chip
            // stretch vertically and the strip grows to fill the screen.
            modifier = Modifier.wrapContentHeight(),
        ) {
            Row(
                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    workspace?.label ?: "默认工作区",
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 110.dp),
                )
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.usageKnown) {
            UsageChip(
                usage = state.displayUsage,
                contextTokens = state.contextTokens,
                contextWindow = state.contextWindow,
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(10.dp))
            Text(
                "正在载入会话…",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(state: ChatUiState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                "DSH Mobile",
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    state.backend == Backend.REMOTE && !state.connected ->
                        "尚未连接桌面端。\n在桌面运行 `dsh web`，然后到设置里粘贴它打印的链接完成配对。"
                    state.backend == Backend.REMOTE ->
                        "已连接。发送第一条消息即可开始，\n或在左上角选择一个已有会话。"
                    else ->
                        "在设置中填写 API Key 后即可对话。"
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun ErrorBanner(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(10.dp),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            Text(
                "关闭",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clickableNoRipple(onDismiss),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    running: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text("发送消息…", fontSize = 14.sp)
                },
                maxLines = 6,
                shape = RoundedCornerShape(20.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(44.dp)
                    .background(
                        if (running) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(22.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (running) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = "停止", tint = Color.White)
                    }
                } else {
                    IconButton(
                        onClick = { if (value.isNotBlank()) onSend() },
                        enabled = value.isNotBlank(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = if (value.isNotBlank()) Color.White
                            else Color.White.copy(alpha = 0.45f),
                        )
                    }
                }
            }
        }
    }
}

/** Small helper so banners are tappable without the ripple indirection. */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
