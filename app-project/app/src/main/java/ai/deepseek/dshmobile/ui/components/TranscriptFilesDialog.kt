package ai.deepseek.dshmobile.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
 * The files this conversation's tool calls referred to.
 *
 * ## Why this is separate from the workspace browser
 *
 * "查看文件" and "查看 AI 提供的文件" are different questions. The browser answers
 * *where is that file* — the user knows the project and wants to navigate. This
 * answers *what did it just make* — the user does not know the path and does not
 * want to learn it. Deriving the list from the transcript is what makes the second
 * question answerable at all: the path was already in the tool call, it just was
 * not actionable.
 *
 * ## Why newest first
 *
 * The reason to open this list is almost always the thing that was produced a
 * moment ago. Ordering by recency puts it at the top; ordering by path or by tool
 * name would bury it behind files from earlier in the session.
 */
@Composable
fun TranscriptFilesDialog(
    files: List<ToolFiles.Ref>,
    onOpen: (String) -> Unit,
    onBrowse: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("助手用到的文件", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    "这段对话里被读取或交付的文件，最新的在最上面。",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        },
        text = {
            if (files.isEmpty()) {
                Text(
                    "这段对话里还没有提到过文件。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    for (ref in files) {
                        FileRefRow(ref = ref, onClick = { onOpen(ref.path) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onBrowse) { Text("浏览工作区", fontSize = 13.sp) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
        },
    )
}

/**
 * One file the transcript named.
 *
 * The path is shown in full and monospaced rather than shortened to its tail. The
 * tail is what identifies a file in the transcript's one-line tool cards, but here
 * the path is the *only* content, and a user who is about to open a file wants to
 * see where it actually is — two files named `index.ts` are a real possibility and
 * an ambiguous list is worse than a long one.
 */
@Composable
private fun FileRefRow(ref: ToolFiles.Ref, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                ref.path,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (ref.description.isNotBlank()) {
                Text(
                    ref.description,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Default.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
