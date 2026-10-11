package ai.deepseek.dshmobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.deepseek.dshmobile.data.Block
import ai.deepseek.dshmobile.data.Message
import ai.deepseek.dshmobile.data.Role
import ai.deepseek.dshmobile.data.ToolFiles
import ai.deepseek.dshmobile.data.ToolLabel

@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
    /**
     * Long-press handler, or null to disable it.
     *
     * Nullable rather than a no-op default so the gesture can be left off where it
     * has nothing to copy: a `SystemBubble` carries a notice like "已停止生成", and
     * offering "复制" for it would promise something the user did not ask for.
     */
    onCopy: (() -> Unit)? = null,
    /**
     * Opens one file a tool call named, or null to render the chip as plain text.
     *
     * Nullable for the same reason [onCopy] is: API mode has no workspace to read
     * from, so a chip there would be a button that cannot work.
     */
    onOpenFile: ((String) -> Unit)? = null,
) {
    when (message.role) {
        Role.USER -> UserBubble(message, modifier, onCopy)
        Role.ASSISTANT -> AssistantBubble(message, modifier, onCopy, onOpenFile)
        Role.CONTEXT -> ContextBubble(message, modifier, onCopy)
        Role.SYSTEM, Role.TOOL -> SystemBubble(message, modifier)
    }
}

/**
 * The long-press gesture that copies one message.
 *
 * `combinedClickable` rather than `clickable`: a plain tap on a bubble has no
 * meaning here, and binding copy to it would fire on every scroll that ends with a
 * finger down. A long press is the platform's own idiom for "act on this item",
 * and it is what the user asked for.
 *
 * `onClick` is left as an empty lambda rather than omitted because
 * `combinedClickable` requires one; a long press still reports haptic feedback, so
 * the gesture is discoverable without a visual affordance on every bubble.
 *
 * `combinedClickable` is still marked experimental in this Compose version, so the
 * opt-in is declared here rather than at every call site.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.longPressCopy(enabled: Boolean, onCopy: (() -> Unit)?): Modifier =
    if (!enabled || onCopy == null) this
    else this.combinedClickable(onClick = {}, onLongClick = onCopy)

@Composable
private fun UserBubble(message: Message, modifier: Modifier, onCopy: (() -> Unit)?) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                .background(MaterialTheme.colorScheme.primary)
                .longPressCopy(enabled = true, onCopy = onCopy)
                .padding(horizontal = 13.dp, vertical = 9.dp)
        ) {
            Column {
                for (block in message.blocks) {
                    when (block) {
                        is Block.Text -> Text(
                            block.text,
                            color = Color.White,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        )
                        is Block.Reasoning -> Unit
                        is Block.ToolCall -> Unit
                        is Block.Notice -> Text(
                            block.text,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(
    message: Message,
    modifier: Modifier,
    onCopy: (() -> Unit)?,
    onOpenFile: ((String) -> Unit)?,
) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "DS",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(9.dp))
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .longPressCopy(enabled = true, onCopy = onCopy)
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            for (block in message.blocks) {
                when (block) {
                    is Block.Text -> Markdown(block.text)
                    is Block.Reasoning -> ReasoningBlock(block)
                    is Block.ToolCall -> ToolCallBlock(block, onOpenFile)
                    is Block.Notice -> Text(
                        block.text,
                        color = if (block.isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }
            if (message.streaming) {
                Spacer(Modifier.height(6.dp))
                TypingDots()
            }
            // The turn's duration, when the log can prove one. Placed after the
            // content rather than in the header so it reads as a footnote to the
            // answer instead of competing with it.
            formatDuration(message.durationMs)?.let { label ->
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        label,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * A duration as a short human-readable label, or null when there is nothing to say.
 *
 * Null for zero and negative: a single-step turn has no measurable span (see
 * [Message.durationMs]), and rendering "用时 0 秒" would assert a measurement that
 * was never taken. Under a second the label is suppressed for the same reason —
 * the log's timestamps are whole milliseconds of *write* time, so a sub-second span
 * is indistinguishable from two events written in the same tick.
 *
 * Seconds are rounded rather than truncated so a 59.6 s turn does not read as
 * "59 秒" and then appear to be a minute off from the one beside it.
 */
internal fun formatDuration(durationMs: Long): String? {
    if (durationMs < 1_000L) return null
    val totalSeconds = Math.round(durationMs / 1000.0)
    if (totalSeconds < 60L) return "用时 ${totalSeconds} 秒"
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    if (minutes < 60L) {
        return if (seconds == 0L) "用时 $minutes 分钟" else "用时 $minutes 分 $seconds 秒"
    }
    val hours = minutes / 60
    val remMinutes = minutes % 60
    return if (remMinutes == 0L) "用时 $hours 小时" else "用时 $hours 小时 $remMinutes 分"
}

@Composable
private fun SystemBubble(message: Message, modifier: Modifier) {
    for (block in message.blocks) {
        val isError = (block as? Block.Notice)?.isError == true
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isError) Icons.Default.ErrorOutline else Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(7.dp))
            val text = when (block) {
                is Block.Notice -> block.text
                is Block.Text -> block.text
                is Block.ToolCall -> block.name
                is Block.Reasoning -> block.text
            }
            Text(
                text,
                fontSize = 12.sp,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Harness-injected context: the runtime-context snapshot, the skill catalog.
 *
 * ## Why this is not a user bubble
 *
 * These arrive as `user/message` events — that is the role the model reads them in
 * — so before the parser distinguished them they rendered exactly like something
 * the user had typed: same blue, same right alignment, same size. The reported
 * anomaly was precisely that: a prompt followed by two more blue bubbles the user
 * never wrote, one of them a wall of sandbox and approval boilerplate.
 *
 * ## Why it is still shown
 *
 * Hiding it would make the transcript disagree with the log, and the material is
 * genuinely part of what the model was told — a user debugging "why did it think
 * it could not write there?" needs to see the policy it was given. So it is shown
 * but demoted: collapsed by default, full width, neutral chrome, and labelled so
 * it cannot be mistaken for speech. The collapsed row is the whole fix — the
 * content is one tap away and no longer impersonates the user.
 *
 * The gesture is still offered, because copying the policy the model saw is a
 * legitimate thing to want.
 */
@Composable
private fun ContextBubble(message: Message, modifier: Modifier, onCopy: (() -> Unit)?) {
    var expanded by remember { mutableStateOf(false) }
    val text = message.blocks.filterIsInstance<Block.Text>().joinToString("\n\n") { it.text }
    if (text.isBlank()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .longPressCopy(enabled = true, onCopy = onCopy)
            .clickable { expanded = !expanded }
            .padding(horizontal = 9.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                contextLabel(text),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                text,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

/**
 * A short name for one injected block, taken from the block itself.
 *
 * The source kind would be the precise label, but it is not carried into the
 * transcript — and a generic "上下文" would be wrong for the one case a user is
 * most likely to meet. The two producers this app actually sees both announce
 * themselves in their first line, so the label is read from there and falls back
 * to a plain name when it does not match.
 *
 * Recognising by *content* is deliberate here rather than by source: the content
 * is what the user is looking at, so the label cannot disagree with the body.
 */
internal fun contextLabel(text: String): String {
    val head = text.trimStart().take(200)
    return when {
        head.startsWith("<system-reminder>") && head.contains("available_skills") ->
            "技能列表"
        head.startsWith("<system-reminder>") -> "系统提示"
        head.startsWith("Current runtime context") ||
            head.startsWith("Current DSH file policy") -> "运行环境"
        else -> "上下文"
    }
}

/**
 * Collapsible "thinking" section.
 *
 * [Block.Reasoning.parts] counts how many separate reasoning blocks were folded
 * into this one by the turn merge. Saying so matters: a turn that thought twenty
 * times used to render as twenty collapsed boxes, and one box with no indication
 * of its size reads as a single short thought. The count is the honest label, and
 * it is what makes the merged form easier to trust than the split one.
 */
@Composable
private fun ReasoningBlock(block: Block.Reasoning) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 9.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Psychology,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                if (block.parts > 1) "思考过程 · ${block.parts} 段" else "思考过程",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                block.text,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

/**
 * One step of a turn: what the assistant did, in words, and the files it touched.
 *
 * The header used to be the tool's identifier — `pwsh`, `read`, `edit` — in
 * monospace. That named the mechanism and hid the act: a user could see that
 * something happened but not that a command ran or which file was read. The
 * header is now the verb and its target ("运行命令 Get-ChildItem …", "读取
 * ui/ChatScreen.kt"), with the identifier kept as a small trailing tag so the
 * detail is still there for anyone who wants it.
 *
 * A step that has not reported back yet is marked as running. That distinction
 * was previously invisible, and it is the one thing a user watching a long turn
 * actually wants to know.
 *
 * ## Why the files are chips on the card
 *
 * 1.1.7 answered "what did it just make?" with a separate dialog behind an app-bar
 * button, and that was one step too many: the path is already *in* the call being
 * rendered, so a list that had to be opened elsewhere was a second copy of a fact
 * the card was already showing. Worse, it was a snapshot of the whole
 * conversation — the file the user wanted was at the top of a list that also held
 * everything from an hour ago. Now the file is a chip on the very step that named
 * it, in the place the user is already reading, and one tap opens it. The chips
 * are derived from the call's own arguments by [ToolFiles], which is the same
 * per-tool reader the dialog used, so `present`'s file list and `read`'s
 * `file_path` are still understood by name rather than guessed at.
 */
@Composable
private fun ToolCallBlock(block: Block.ToolCall, onOpenFile: ((String) -> Unit)?) {
    var expanded by remember { mutableStateOf(false) }
    val running = block.output == null && !block.failed
    val accent = when {
        block.failed -> MaterialTheme.colorScheme.error
        running -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    // Read from the call's own arguments, so a tool this build does not know
    // simply yields nothing rather than offering to open a shell command.
    val files = remember(block.name, block.input) { ToolFiles.of(block.name, block.input) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 9.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when {
                    block.failed -> Icons.Default.ErrorOutline
                    running -> Icons.Default.Autorenew
                    else -> Icons.Default.Check
                },
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = accent,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                // Falls back to the raw name when the arguments cannot be read, so
                // an unknown tool still says *something* rather than "工具".
                ToolLabel.describe(block.name, block.input).ifBlank { block.name },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The chips sit outside the collapse: they are the answer to "what did it
        // just make", and hiding the thing the card exists to surface behind a
        // second tap would put the friction straight back.
        if (files.isNotEmpty()) {
            Spacer(Modifier.height(5.dp))
            for (ref in files) {
                FileChip(ref = ref, onOpen = onOpenFile)
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(top = 5.dp)) {
                Text(
                    block.name,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (block.input.isNotBlank() && block.input != "{}") {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        block.input.take(1200),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                block.output?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        it.take(4000),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (block.failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * One file a tool call named, as a tappable chip.
 *
 * The chip shows the file's tail rather than its full path — see
 * [ToolFiles.chipName] for why that is the opposite choice from the dialog it
 * replaces — and carries the full path in its content description, so a screen
 * reader and a long-press both still reach the unambiguous form.
 *
 * When [onOpen] is null (API mode, where there is no workspace to read from) the
 * chip still renders, because the *fact* that a file was involved is worth
 * knowing, but it is not clickable: a button that cannot work is worse than a
 * label that does not pretend.
 */
@Composable
private fun FileChip(ref: ToolFiles.Ref, onOpen: ((String) -> Unit)?) {
    val name = ToolFiles.chipName(ref.path)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .then(
                if (onOpen == null) Modifier
                // `clickable` on the chip, not on the card: the card's own tap
                // toggles the detail, so without stopping here a chip tap would do
                // both.
                else Modifier.clickable { onOpen(ref.path) }
            )
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = ref.path,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            name,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onOpen != null) {
            Text(
                "打开",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun TypingDots() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            Box(
                Modifier
                    .padding(end = 3.dp)
                    .size(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f + i * 0.2f)
                    )
            )
        }
    }
}
