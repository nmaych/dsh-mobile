package ai.deepseek.dshmobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The long-press copy sheet: the message's own text, selectable, plus a copy-all.
 *
 * ## Why a dialog rather than writing to the clipboard directly
 *
 * 1.1.8 and 1.1.9 both made the long press copy the **whole** message straight to
 * the clipboard, and the request that produced this file says that is not what was
 * wanted: 「复制内容，我要的是长按对话框选择内容复制」 — a long press should open a
 * dialog in which the content can be *selected*, and the selection copied.
 *
 * The two are genuinely different jobs, and the difference is not cosmetic:
 *
 *  - A whole-message copy is all-or-nothing. A reply that is a paragraph of
 *    explanation wrapped around one command line can only be copied entire, and the
 *    user then has to delete the prose by hand in whatever they pasted it into.
 *  - A long-press on *text* is the platform's own gesture for "select this word,
 *    then adjust the handles", and Android users reach for it expecting a selection
 *    UI. Sending the message to the clipboard instead means the gesture they used
 *    did something they did not ask for and cannot see.
 *
 * So the dialog gives both: the body is inside a [SelectionContainer], so a
 * long-press on a word inside it starts a real selection with handles, and the
 * confirm button copies everything at once for the case where that *is* what is
 * wanted. Neither path is hidden behind the other.
 *
 * ## Why the text is the plain bubble text, not the Markdown export
 *
 * The body shown is [text], which the caller has rendered with
 * `Transcript.messagePlainText` since 1.2.1 — the words visible on the bubble,
 * with no role heading, timestamp, quoted reasoning or fenced tool JSON. 1.2.0
 * fed this sheet the *export* format instead, and pasting it elsewhere produced
 * the 乱码 report: Markdown scaffolding the user never saw wrapped around what
 * they did. Monospace is still deliberate: the dialog exists to be *selected
 * from*, and a rendered view would hide exactly the characters being copied.
 */
@Composable
fun CopyMessageDialog(
    /** The message body to show and copy, already rendered as Markdown. */
    text: String,
    /** A short description of what is being copied, e.g. 「你的消息」. */
    subtitle: String,
    onCopyAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("复制内容", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    // Says *which* message, because the dialog covers the
                    // transcript and the user cannot see the bubble it came from.
                    "$subtitle · 长按正文可选中部分内容",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        },
        text = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // A scroll *outside* the selection container, so dragging the text
                // to select near an edge scrolls rather than fighting the selection.
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text.ifBlank { "（这条消息没有可复制的正文）" },
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCopyAll) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("复制全部", fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
        },
    )
}
