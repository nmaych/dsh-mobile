package ai.deepseek.dshmobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.deepseek.dshmobile.data.ToolFiles

/**
 * The files the assistant delivered in this conversation.
 *
 * ## Why this exists next to the inline chips
 *
 * A chip on a tool card answers "what did *this step* touch", which is the right
 * answer while reading a step. It is the wrong answer to "what did it hand me?"
 * over a long conversation: the files are scattered across dozens of cards, the
 * newest is at the bottom, and finding it means scrolling back through the work.
 * This is the one place that answers the second question, so it is a separate
 * surface rather than a second copy of the first.
 *
 * ## Why it is derived rather than stored
 *
 * The list is computed from the transcript on every read (see
 * `ChatUiState.deliverables`). A snapshot taken when the sheet opened would freeze
 * the moment a turn produced another file — and a turn producing files is exactly
 * when the user has this open. Deriving it also means there is no second state to
 * go stale, no fetch to fail, and no loading state to draw.
 *
 * ## What counts as delivered
 *
 * Only `present` (the deliverable tool) and `write` (a file the assistant created
 * or overwrote). `read` and `edit` are excluded on purpose: reading a file is the
 * opposite of delivering it, and an edit is work *on* the project rather than
 * something handed over. Without that filter a turn that read twenty files to
 * answer one question would list twenty "deliverables" and bury the one artifact
 * that mattered. See [ToolFiles.delivered].
 */
@Composable
fun DeliverablesDialog(
    files: List<ToolFiles.Ref>,
    onOpen: (String) -> Unit,
    onBrowse: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("交付文件", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    "这次对话里助手生成并交给你的文件，最新的在最上面。",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (files.isEmpty()) {
                    Text(
                        // Says what the list is *for* rather than just that it is
                        // empty, because "no deliverables yet" is also the state
                        // before the first file exists, and a bare "空" reads as a
                        // broken list.
                        "这次对话还没有交付文件。\n当助手用 present 交付文件、或写入新文件时，它们会出现在这里。",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    for (ref in files) {
                        DeliverableRow(ref = ref, onOpen = onOpen)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onBrowse) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text("浏览工作区", fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
        },
    )
}

/**
 * One delivered file.
 *
 * The **full path** is shown here, which is the opposite choice from the inline
 * chip. That is deliberate and the two are not inconsistent: a chip sits in a card
 * that already names the tool and its target, is one line wide, and is not the only
 * way to reach the file — so it shows the tail. This row *is* the only content on
 * its line and its whole job is to identify one file among many, so two files named
 * `index.ts` have to be told apart. The description the assistant attached to the
 * hand-over is shown beneath it, because that is the assistant telling the user what
 * the file is for.
 */
@Composable
private fun DeliverableRow(ref: ToolFiles.Ref, onOpen: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable { onOpen(ref.path) },
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    ref.path,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ref.description.isNotBlank()) {
                    Text(
                        ref.description,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(
                "打开",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
