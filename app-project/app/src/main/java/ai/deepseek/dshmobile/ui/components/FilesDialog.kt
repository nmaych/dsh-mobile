package ai.deepseek.dshmobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import ai.deepseek.dshmobile.data.WorkspaceFileEntry
import ai.deepseek.dshmobile.data.WorkspaceFilePage
import ai.deepseek.dshmobile.data.WorkspaceListing

/**
 * Browse the active session's workspace, and read a file from it.
 *
 * ## Why the session defines the root
 *
 * There is no "list the desktop's files" call, and that is a deliberate boundary
 * rather than a missing feature: `workspaceFiles` resolves a **session** and uses
 * that session's `cwd` as the root, so what can be browsed is exactly what the
 * agent working in that conversation can reach. A global file browser would have
 * to name a root the phone has no business choosing, and directories are refused
 * outside the workspace anyway (`workspace-file/outside-workspace`).
 *
 * ## Why a dialog rather than a screen
 *
 * The browser is a look-up aid — "what did it just edit?" — not a place the user
 * lives. Keeping it over the transcript means closing it returns to the
 * conversation at the same scroll position, which a navigation push would not.
 */
@Composable
fun FilesDialog(
    listing: WorkspaceListing?,
    path: String,
    loading: Boolean,
    openFile: WorkspaceFilePage?,
    openFilePath: String,
    error: String?,
    canGoUp: Boolean,
    onOpenChild: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onUp: () -> Unit,
    onCloseFile: () -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    if (openFile != null) "查看文件" else "工作区文件",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    // The path is what tells the user *where* they are, and the
                    // empty string is the root rather than "nothing selected", so
                    // it is spelled out instead of rendered blank.
                    if (openFile != null) openFilePath
                    else if (path.isBlank()) "工作区根目录" else path,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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
                when {
                    openFile != null -> FileViewer(openFile)

                    loading && listing == null -> Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }

                    error != null -> Text(
                        error,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        lineHeight = 17.sp,
                    )

                    listing == null || listing.entries.isEmpty() -> Text(
                        "这个目录是空的。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> {
                        for (entry in listing.sorted) {
                            FileRow(entry = entry, onClick = {
                                if (entry.isDirectory) onOpenChild(entry.name)
                                else onOpenFile(entry.name)
                            })
                        }
                        if (listing.truncated) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                // Said plainly rather than silently: a partial
                                // listing that looks complete is how a user
                                // concludes a file does not exist.
                                "目录内容过多，只显示了前一部分。",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (openFile != null) {
                TextButton(onClick = onCloseFile) { Text("返回目录", fontSize = 13.sp) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (canGoUp) {
                        TextButton(onClick = onUp) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(3.dp))
                            Text("上级", fontSize = 13.sp)
                        }
                    }
                    TextButton(onClick = onReload) { Text("刷新", fontSize = 13.sp) }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
        },
    )
}

/** One directory entry: an icon, a name, and a size for files. */
@Composable
private fun FileRow(entry: WorkspaceFileEntry, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                entry.isDirectory -> Icons.Default.Folder
                entry.isFile -> Icons.AutoMirrored.Filled.InsertDriveFile
                // Neither a file nor a directory: a socket, a device node. It is
                // listed rather than hidden, because silently omitting an entry
                // makes the listing disagree with the directory it describes.
                else -> Icons.AutoMirrored.Filled.Article
            },
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            entry.name,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        entry.size?.let {
            Text(
                formatBytes(it),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A read-only view of one text file.
 *
 * Monospace and not re-wrapped: this is a file, and honouring its own line breaks
 * is the point. Markdown is deliberately *not* rendered here even though the
 * transcript renders it — the user asked to see the file, so showing the source
 * is the honest answer, and a rendered view would hide exactly the syntax they
 * opened it to check.
 */
@Composable
private fun FileViewer(page: WorkspaceFilePage) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (page.eof) {
                    "共 ${page.offset + page.lines - 1} 行"
                } else {
                    "第 ${page.offset} 行起，已显示 ${page.lines} 行（未完）"
                },
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            page.bytes?.let {
                Text(
                    formatBytes(it),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                page.text.ifEmpty { "（空文件）" },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp,
                modifier = Modifier.padding(10.dp),
            )
        }
        if (!page.eof) {
            Spacer(Modifier.height(6.dp))
            Text(
                "文件较长，这里只显示开头部分。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Bytes as a short human-readable size.
 *
 * Duplicated from the settings screen rather than shared: the two are a private
 * formatting detail of two unrelated screens, and a common helper would have to
 * live somewhere neither owns.
 */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (value >= 100) {
        "${Math.round(value)} ${units[unit]}"
    } else {
        "${Math.round(value * 10) / 10.0} ${units[unit]}"
    }
}
