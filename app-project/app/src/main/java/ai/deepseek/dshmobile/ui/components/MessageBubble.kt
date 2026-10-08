package ai.deepseek.dshmobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
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
import ai.deepseek.dshmobile.data.ToolLabel

@Composable
fun MessageBubble(message: Message, modifier: Modifier = Modifier) {
    when (message.role) {
        Role.USER -> UserBubble(message, modifier)
        Role.ASSISTANT -> AssistantBubble(message, modifier)
        Role.SYSTEM, Role.TOOL -> SystemBubble(message, modifier)
    }
}

@Composable
private fun UserBubble(message: Message, modifier: Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                .background(MaterialTheme.colorScheme.primary)
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
private fun AssistantBubble(message: Message, modifier: Modifier) {
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
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            for (block in message.blocks) {
                when (block) {
                    is Block.Text -> Markdown(block.text)
                    is Block.Reasoning -> ReasoningBlock(block)
                    is Block.ToolCall -> ToolCallBlock(block)
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
        }
    }
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
 * One step of a turn: what the assistant did, in words.
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
 */
@Composable
private fun ToolCallBlock(block: Block.ToolCall) {
    var expanded by remember { mutableStateOf(false) }
    val running = block.output == null && !block.failed
    val accent = when {
        block.failed -> MaterialTheme.colorScheme.error
        running -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
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
