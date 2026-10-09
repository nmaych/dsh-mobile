package ai.deepseek.dshmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.deepseek.dshmobile.data.Backend
import ai.deepseek.dshmobile.data.SessionParser
import ai.deepseek.dshmobile.ui.components.MessageBubble
import ai.deepseek.dshmobile.ui.components.ModelChip
import ai.deepseek.dshmobile.ui.components.UsageChip
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * How close to the end still counts as "at the bottom".
 *
 * A list scrolled to the end rarely reports an exact match — the last item's
 * bottom sits within a pixel or two of the viewport edge — so an exact test would
 * treat a pinned list as unpinned and stop following the stream.
 */
private val BOTTOM_SLACK = 24.dp

/**
 * The offset passed to the scroll-to-item calls so they land on the **end** of the
 * content rather than the start of the last item.
 *
 * This matters because of the turn merge. `scrollToItem(lastIndex)` puts the last
 * item's *top* at the top of the viewport, which is only the same thing as "the
 * newest text is visible" while the last item is shorter than the screen. A merged
 * turn is one item that can be far taller than the screen — the largest turn in the
 * sampled logs had 830 steps — so aligning its top would scroll to the *beginning*
 * of the turn and push the text that just streamed in off the bottom of the screen,
 * which is the opposite of following the stream.
 *
 * Passing a large offset instead scrolls past the item's start, and the lazy list
 * clamps to the end of its content, so the newest line always ends up in view. The
 * value is bounded rather than `Int.MAX_VALUE` because the offset is added to a
 * pixel position internally, and an offset that large risks overflowing that
 * arithmetic into a negative position. A million pixels is ~3000dp, comfortably
 * taller than any transcript entry, and overflowing it would only mean landing a
 * little above the bottom rather than at it.
 */
private const val PIN_TO_END_PX = 1_000_000

/**
 * Bring the newest content into view, without fighting the user.
 *
 * `animateScrollToItem` is only used when a whole new message arrives, because a
 * scroll animation takes long enough to be cancelled mid-drag; a growing message is
 * snapped instead. Any failure is swallowed: the list can be momentarily
 * inconsistent while it is being remeasured, and a failed scroll is not worth taking
 * the screen down for.
 */
private suspend fun scrollToNewest(state: LazyListState, index: Int, animate: Boolean) {
    runCatching {
        if (animate) {
            state.animateScrollToItem(index, scrollOffset = PIN_TO_END_PX)
        } else {
            state.scrollToItem(index, scrollOffset = PIN_TO_END_PX)
        }
    }
}

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
    val scope = rememberCoroutineScope()
    val bottomSlackPx = with(LocalDensity.current) { BOTTOM_SLACK.roundToPx() }

    // The transcript is the durable log with each turn's steps reassembled into the
    // one response it was, plus the in-flight draft joined onto its own turn.
    //
    // The durable merge is remembered against `state.messages` alone, so a token
    // delta only rebuilds the turn being streamed — see `SessionParser.withDraft`.
    val merged = remember(state.messages) { SessionParser.mergeTurns(state.messages) }
    val rendered = remember(merged, state.liveDraft) {
        SessionParser.withDraft(merged, state.liveDraft)
    }

    // Geometry: is the bottom of the transcript on screen *right now*? This drives
    // the "最新" button, which is a statement about the current view.
    val pinnedToBottom by remember(bottomSlackPx) {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.totalItemsCount == 0) return@derivedStateOf true
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset + bottomSlackPx
        }
    }

    // Intent: should the view chase the newest content?
    //
    // This is deliberately a separate flag from `pinnedToBottom`, and the
    // distinction is the fix. Gating auto-scroll on the geometry alone cannot work,
    // because streaming *grows* the last item: the moment a block is appended the
    // content extends past the viewport, the geometry reports "not at the bottom",
    // and auto-follow switches itself off part-way through the turn — the exact
    // opposite of following it. The old code had the same blind spot from the other
    // side: it never checked at all, and re-scrolled on every delta.
    //
    // So the flag is written **only** by the user's own gestures, never by geometry
    // and never by our own scrolling:
    //
    //  - A drag start takes control immediately. That is what ends the fight the
    //    instant a finger lands, and it is the actual fix for "下滑时有概率无法
    //    下滑": the old code re-issued a scroll animation on every token delta, and
    //    an in-flight animation cancels the drag underneath it.
    //  - When the drag ends, following resumes only if the list *settles* at the
    //    bottom. Waiting for the settle rather than reading at drag-end is what makes
    //    a fling towards the bottom work: at the moment the finger lifts the list is
    //    still short of the end.
    //
    // Nothing here reacts to a programmatic scroll, which matters more than it
    // looks. `isScrollInProgress` is also set by `animateScrollToItem`, so a settle
    // handler that fired for every scroll would let auto-scroll switch *itself* off:
    // if the reply grew while the animation ran, the animation would finish short of
    // the new end, the settle would read "not at the bottom", and following would
    // stop until the user dragged again. Reacting only to drag interactions makes
    // that impossible — once following is on, it stays on until a finger says
    // otherwise.
    var followNewest by remember { mutableStateOf(true) }
    LaunchedEffect(listState, bottomSlackPx) {
        // The pending "wait for the fling to finish" job. It is a plain local, not
        // Compose state: it never needs to trigger recomposition, only to be
        // cancelled when a new drag supersedes it.
        //
        // Cancelling matters. The wait must not run *inside* the collector: that
        // would suspend the loop, so a drag beginning during the wait would sit in
        // the flow's buffer and only be seen once the settle had already written
        // `followNewest = pinnedToBottom` — re-enabling auto-scroll underneath a
        // finger that is actively dragging. Launching it separately keeps the
        // collector free to react to the next gesture immediately.
        var settleJob: Job? = null
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    settleJob?.cancel()
                    followNewest = false
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    settleJob?.cancel()
                    settleJob = launch {
                        // Let any fling run out before deciding where the user landed.
                        snapshotFlow { listState.isScrollInProgress }.first { !it }
                        followNewest = pinnedToBottom
                    }
                }
                else -> Unit
            }
        }
    }
    // A different session starts pinned to its own newest message, rather than
    // inheriting "scrolled up" from the conversation the user just left.
    LaunchedEffect(state.activeSessionId) { followNewest = true }

    // Keep the newest message in view as tokens stream in.
    //
    // A new message gets a short animation; a growing one is snapped, because
    // animating every delta is both wasteful and long enough to swallow a drag.
    var lastCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(rendered.size, rendered.lastOrNull()?.blocks?.size) {
        if (rendered.isEmpty()) return@LaunchedEffect
        val isNewMessage = rendered.size != lastCount
        lastCount = rendered.size
        if (!followNewest) return@LaunchedEffect
        scrollToNewest(listState, rendered.lastIndex, animate = isNewMessage)
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

                // Once the user scrolls up, following the stream is suspended — so
                // there has to be a way back that is not "scroll all the way down
                // by hand". It appears only when it is needed.
                if (!pinnedToBottom && rendered.isNotEmpty()) {
                    JumpToLatest(
                        onClick = {
                            scope.launch {
                                scrollToNewest(listState, rendered.lastIndex, animate = true)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = 12.dp),
                    )
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
 *
 * Every chip is also centred on the strip's cross axis, and that is a separate
 * fix for the landscape misalignment. `FlowRow` aligns its children to the
 * *top* of the line by default (its `CROSS_AXIS_ALIGNMENT_TOP`), which is only
 * harmless while every chip measures the same height. They do not: the model and
 * workspace chips are clickable `Surface`s, and Material3 enforces a 48dp
 * minimum interactive size on those, centring their content inside it, while the
 * usage chip is a plain informational `Surface` and stays about one text line
 * tall. With top alignment the usage readout therefore sat at the top of the
 * line while its neighbours' labels sat in the middle of theirs. In portrait the
 * chips wrap onto separate lines and the difference is invisible; in landscape
 * they share one line, which is why the misalignment only showed up there.
 * Centring each chip makes the two boxes agree about where the baseline is, so
 * the strip reads as one row of chips in either orientation.
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
            //
            // `align` is what keeps it level with the usage chip in landscape;
            // see the strip's KDoc for why top alignment could not.
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .widthIn(max = 200.dp),
        )
        Surface(
            onClick = onOpenWorkspacePicker,
            enabled = state.connected,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            shape = RoundedCornerShape(6.dp),
            // One text line tall, and level with the usage chip: see the strip's
            // KDoc. Without this the constraints stretch a chip to the row height.
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .wrapContentHeight(),
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
                // The usage chip is the only non-clickable chip, so it is the one
                // Material3 does *not* pad out to 48dp. It has to be centred
                // explicitly or it sits above its neighbours in landscape.
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}

/**
 * The "back to the newest message" affordance.
 *
 * It exists because auto-scroll now yields to the user: once the stream stops
 * following, the only way back would otherwise be to drag through however many
 * screens of history have accumulated. It is a filled surface rather than a bare
 * icon so it stays visible over the transcript's varied backgrounds.
 */
@Composable
private fun JumpToLatest(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(3.dp))
            Text(
                "最新",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onPrimary,
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
                // The keyboard and the navigation bar overlap, so their insets are
                // unioned rather than added. Applying `imePadding()` and
                // `navigationBarsPadding()` in sequence stacks them, leaving a
                // bar-height gap between the input and an open keyboard.
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
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
