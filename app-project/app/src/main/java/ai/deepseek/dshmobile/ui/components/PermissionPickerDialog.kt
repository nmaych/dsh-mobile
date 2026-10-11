package ai.deepseek.dshmobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.deepseek.dshmobile.data.PermissionPresets

/**
 * The permission-mode picker.
 *
 * ## Why the catalog comes from the host
 *
 * The two presets the Harness ships with (`workspace-write`,
 * `danger-full-access`) are not the whole set: a deployment configures its own
 * table, and `auto` exists only when an experimental reviewing answerer is
 * mounted. So the list is read from `permissionPresets/catalog` and this app never
 * hardcodes a name — a hardcoded pair would either miss a deployment's presets or
 * offer one that does not exist.
 *
 * ## Why the descriptions are shown
 *
 * The preset names are terse identifiers, and the difference between
 * `workspace-write` and `danger-full-access` is exactly the difference the user
 * needs to understand before choosing: one confines the assistant to the project
 * and asks before going wider, the other lets it do anything without asking. The
 * host supplies a description for each and it is rendered verbatim, because
 * paraphrasing a security-relevant sentence is how a user ends up granting more
 * than they meant to.
 *
 * ## Why there is no optimistic switch
 *
 * Selecting does not move the radio button. The authoritative value is the
 * session's `permissions` projection, which the host updates when the
 * `permission/preset` event is committed; showing the new choice immediately would
 * leave the dialog asserting a mode the session is not in whenever the switch was
 * refused. So the selection is sent and the dialog stays until the projection
 * agrees — which is why the whole row is disabled while a switch is in flight.
 */
@Composable
fun PermissionPickerDialog(
    presets: List<PermissionPresets.Preset>,
    current: String,
    loading: Boolean,
    switching: Boolean,
    onPick: (String) -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("权限模式", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    "决定助手能改什么、以及是否先问你。只对当前会话生效。",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    loading && presets.isEmpty() -> Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }

                    presets.isEmpty() -> Text(
                        // Not an error: a host without the permission-presets
                        // plugin simply has no modes to choose from, and saying
                        // so is more useful than a failure banner.
                        "桌面端没有提供权限模式，当前会话使用它的默认设置。",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> for (preset in presets) {
                        PresetRow(
                            preset = preset,
                            chosen = preset.value == current,
                            enabled = !switching,
                            onPick = { onPick(preset.value) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onReload) { Text("刷新", fontSize = 13.sp) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
        },
    )
}

/**
 * One selectable preset.
 *
 * `selectable` rather than `clickable`, so accessibility services are told this is
 * a choice with a checked state rather than a button that happens to change
 * something — the same reasoning as the question dialog's option rows.
 *
 * The unrestricted preset is tinted with the error colour. That is a deliberate
 * departure from a neutral list: it is the one option that removes the prompts
 * protecting the user's machine, and a list where every row looks the same is one
 * where that difference is invisible.
 */
@Composable
private fun PresetRow(
    preset: PermissionPresets.Preset,
    chosen: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
) {
    val unrestricted = preset.value.contains("danger", ignoreCase = true) ||
        preset.value.contains("full", ignoreCase = true)
    val accent = if (unrestricted) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary
    Surface(
        color = if (chosen) accent.copy(alpha = 0.10f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .selectable(selected = chosen, enabled = enabled, onClick = onPick),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = chosen, onClick = null, enabled = enabled)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = accent,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        preset.label,
                        fontSize = 12.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = if (unrestricted) accent
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (chosen) {
                        Spacer(Modifier.width(5.dp))
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "当前模式",
                            modifier = Modifier.size(13.dp),
                            tint = accent,
                        )
                    }
                }
                if (preset.description.isNotBlank()) {
                    Text(
                        preset.description,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
