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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.deepseek.dshmobile.data.TokenUsage
import ai.deepseek.dshmobile.ui.ModelRow
import ai.deepseek.dshmobile.ui.WorkspaceRow

/** Compact "1.2k" style token count, so a chip stays one line wide. */
fun formatTokens(value: Long): String = when {
    value < 1_000 -> value.toString()
    value < 1_000_000 -> {
        val thousands = value / 1000.0
        if (thousands < 10) String.format(java.util.Locale.US, "%.1fk", thousands)
        else "${(value / 1000)}k"
    }
    else -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0)
}

/**
 * The token-usage chip.
 *
 * Shows the session's running total, and — when the server reports a context
 * window — how full that window is, because the total alone does not tell a user
 * whether they are about to be compacted. Both figures come from the server's
 * own projections rather than being summed locally.
 *
 * Every text here is pinned to a single line. The readout is monospace and made
 * of many short tokens (`↑209k`, `(10.3M`, `缓存)`), so when it was handed a
 * narrow width it broke after *every* token and the chip grew to hundreds of
 * pixels tall — tall enough to push the app bar off screen. A one-line clip
 * degrades gracefully instead: the chip stays chip-sized and long values are
 * ellipsised rather than stacked.
 */
@Composable
fun UsageChip(
    usage: TokenUsage,
    contextTokens: Long?,
    contextWindow: Long?,
    modifier: Modifier = Modifier,
) {
    if (usage.isEmpty && contextTokens == null) return
    val window = contextWindow
    val contextText = if (contextTokens != null && window != null && window > 0) {
        "${formatTokens(contextTokens)} / ${formatTokens(window)}"
    } else {
        null
    }
    // Near the window's edge the next request is likely to be compacted, which
    // is the one thing a bare total cannot convey.
    val nearLimit = contextTokens != null && window != null &&
        window > 0 && contextTokens.toDouble() > window.toDouble() * 0.8
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(6.dp),
        // The chip is one text line tall whatever its width: without this the
        // incoming constraints let it stretch to whatever the row's height
        // happens to be.
        modifier = modifier.wrapContentHeight(),
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Memory,
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                buildString {
                    append("↑").append(formatTokens(usage.inputTokens))
                    if (usage.cacheReadTokens > 0) append(" (").append(formatTokens(usage.cacheReadTokens)).append(" 缓存)")
                    append(" ↓").append(formatTokens(usage.outputTokens))
                },
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            if (contextText != null) {
                Text(
                    "  ·  上下文 $contextText",
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (nearLimit) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A one-line "current model" button that opens [ModelPickerDialog]. */
@Composable
fun ModelChip(
    label: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(6.dp),
        // Same one-line guarantee as [UsageChip]: a chip in the strip must never
        // be stretched to the row's height, and its label must never wrap. Both
        // symptoms came from the same missing constraint.
        modifier = modifier.wrapContentHeight(),
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(9.dp), strokeWidth = 1.5.dp)
            } else {
                Icon(
                    Icons.Default.Memory,
                    contentDescription = null,
                    modifier = Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                fontSize = 9.5.sp,
                maxLines = 1,
                // `softWrap = false` keeps the measured width equal to the whole
                // label's width instead of the first wrapped word's, so a long
                // `provider/model` is ellipsised rather than collapsed.
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Pick a remote model.
 *
 * Models are grouped by provider, because the same model id can be offered by
 * more than one provider and the pair — not the id — is what the server routes
 * on. A model that declares reasoning efforts gets a second step rather than a
 * submenu, so the choice stays one dialog deep.
 */
@Composable
fun ModelPickerDialog(
    models: List<ModelRow>,
    currentModel: String,
    currentEffort: String,
    loading: Boolean,
    onDismiss: () -> Unit,
    onLoad: () -> Unit,
    onPick: (provider: String, model: String, effort: String?) -> Unit,
    /** Why the last catalog load failed, shown instead of the empty-state advice. */
    error: String? = null,
) {
    var pendingEffortFor by remember { mutableStateOf<ModelRow?>(null) }

    val effortTarget = pendingEffortFor
    if (effortTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingEffortFor = null },
            title = { Text("推理强度", fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        effortTarget.name,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    for (effort in effortTarget.efforts) {
                        PickerRow(
                            label = effortLabel(effort),
                            selected = effort == effortTarget.defaultEffort,
                            onClick = {
                                pendingEffortFor = null
                                onPick(effortTarget.provider, effortTarget.id, effort)
                            },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    PickerRow(
                        label = "使用默认",
                        selected = false,
                        onClick = {
                            pendingEffortFor = null
                            onPick(effortTarget.provider, effortTarget.id, null)
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingEffortFor = null }) { Text("取消", fontSize = 13.sp) }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择模型", fontSize = 16.sp) },
        text = {
            Box(Modifier.heightIn(max = 420.dp)) {
                when {
                    loading && models.isEmpty() -> Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在获取模型列表…", fontSize = 12.sp)
                    }

                    models.isEmpty() -> Column(Modifier.padding(8.dp)) {
                        // A failed load and an empty account are different
                        // problems with different fixes, and the old copy asserted
                        // the second one unconditionally. When the catalog request
                        // timed out, the dialog told the user to go log in on the
                        // desktop — sending them to fix an account that was fine,
                        // while the real reason sat in the error banner *behind*
                        // this dialog. Reporting the failure here is what makes
                        // "选择模型时提示连接超时" actionable.
                        Text(
                            error ?: "没有可用的模型。请确认桌面端已登录账号或配置了 API Key。",
                            fontSize = 12.sp,
                            color = if (error != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            lineHeight = 17.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = onLoad) { Text("重新获取", fontSize = 13.sp) }
                    }

                    else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        val grouped = models.groupBy { it.provider }
                        for ((provider, group) in grouped) {
                            item(key = "header-$provider") {
                                Text(
                                    group.first().providerName,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 3.dp),
                                )
                            }
                            items(group, key = { it.key }) { row ->
                                val selected = row.key == currentModel
                                PickerRow(
                                    label = row.name,
                                    detail = row.description.ifBlank { row.id },
                                    selected = selected,
                                    trailing = if (selected && currentEffort.isNotBlank()) {
                                        currentEffort
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        if (row.efforts.isNotEmpty()) {
                                            pendingEffortFor = row
                                        } else {
                                            onPick(row.provider, row.id, null)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
        },
    )
}

/**
 * Pick the workspace new sessions are created in.
 *
 * Selecting is a preference, not a move: an existing session's workspace is
 * fixed, so the dialog says so rather than implying the open conversation will
 * relocate.
 */
@Composable
fun WorkspacePickerDialog(
    workspaces: List<WorkspaceRow>,
    selectedId: String,
    activeId: String,
    loading: Boolean,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onPick: (String) -> Unit,
    onAdd: (String) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    var newPath by remember { mutableStateOf("") }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("添加工作区", fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        "填写电脑上的目录绝对路径，例如 D:\\projects\\my-app 或 /home/me/app。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newPath,
                        onValueChange = { newPath = it },
                        label = { Text("目录路径", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val path = newPath
                        showAdd = false
                        newPath = ""
                        onAdd(path)
                    },
                    enabled = newPath.isNotBlank(),
                ) { Text("添加", fontSize = 13.sp) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("取消", fontSize = 13.sp) }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择工作区", fontSize = 16.sp) },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                Text(
                    "新建的会话会创建在选中的工作区里。已经打开的会话不会移动。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    loading && workspaces.isEmpty() -> Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在获取工作区…", fontSize = 12.sp)
                    }

                    workspaces.isEmpty() -> Column(Modifier.padding(vertical = 8.dp)) {
                        Text(
                            "桌面端还没有工作区。可以直接添加一个目录。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> Column(
                        Modifier
                            .heightIn(max = 340.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        PickerRow(
                            label = "跟随桌面端默认",
                            detail = "不指定工作区",
                            selected = selectedId.isBlank(),
                            onClick = { onPick("") },
                        )
                        for (row in workspaces) {
                            PickerRow(
                                label = row.label,
                                detail = row.path,
                                selected = row.id == selectedId,
                                trailing = if (row.id == activeId) "当前会话" else null,
                                onClick = { onPick(row.id) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onReload) { Text("刷新", fontSize = 12.sp) }
                    TextButton(onClick = { showAdd = true }) { Text("添加目录", fontSize = 12.sp) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
        },
    )
}

/** One selectable row inside a picker dialog. */
@Composable
private fun PickerRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    detail: String? = null,
    trailing: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Text(
                trailing,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** A drawer row that opens the workspace picker. */
@Composable
fun WorkspaceDrawerRow(
    label: String,
    path: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (path.isNotBlank()) {
                Text(
                    path,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.Default.ExpandMore,
            contentDescription = "切换工作区",
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun effortLabel(effort: String): String = when (effort.lowercase()) {
    "low" -> "低（low）"
    "medium" -> "中（medium）"
    "high" -> "高（high）"
    "max", "xhigh" -> "最高（$effort）"
    "minimal" -> "最少（minimal）"
    else -> effort
}
