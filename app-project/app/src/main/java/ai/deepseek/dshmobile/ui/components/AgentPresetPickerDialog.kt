package ai.deepseek.dshmobile.ui.components

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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.deepseek.dshmobile.data.AgentPresets

/**
 * The Agent-preset (模式) picker.
 *
 * ## Why the roster comes from the host
 *
 * The four the Harness ships with (`standard`, `ptc`, `minimal`, `cordis`) are not
 * the whole set: a deployment declares its own presets, and a user can create one
 * from the desktop. So the list is read from `agentPresets/list` and this app never
 * hardcodes the four — a hardcoded list would miss a deployment's presets or offer
 * one that no longer exists. The *copy* for the shipped four is supplied locally
 * (see [AgentPresets]) because the host publishes none for them, which is a naming
 * problem rather than a roster problem.
 *
 * ## Why the descriptions are shown
 *
 * The names are terse ("PTC 模式") and the difference between the modes is exactly
 * what the user needs before choosing: one hands the agent every tool directly, one
 * makes it drive them from a program, one gives it a terminal and nothing else. The
 * host supplies a description for each and it is rendered verbatim, because
 * paraphrasing "what this agent is allowed to do" is how a user ends up with a mode
 * they did not intend.
 *
 * ## Why the locked state is shown rather than hidden
 *
 * The host refuses a preset change once a session has started its first turn
 * (`agent-preset/locked`), because the preset decides the tool schemas and prompt
 * sections the model has already been shown — changing it mid-conversation would
 * make the log dishonest. A picker that simply did nothing when tapped would be
 * read as a bug, so the rule is stated up front and choosing still *remembers* the
 * mode for the next conversation.
 */
@Composable
fun AgentPresetPickerDialog(
    presets: List<AgentPresets.Preset>,
    /** The mode the active session runs, or blank when none is open. */
    current: String,
    /** The mode a new session will use. */
    pending: String,
    /** True when the active session has started, so a switch will be refused. */
    locked: Boolean,
    loading: Boolean,
    switching: Boolean,
    onPick: (String) -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Which row reads as "selected" is a different question in the two states, and
    // conflating them is the one way this dialog can lie. With a session open it is
    // the session's own mode; with none it is the pending choice for the next one.
    val chosen = if (current.isNotBlank()) current else pending
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("模式", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (current.isNotBlank()) {
                        if (locked) {
                            "本任务的模式在任务开始时确定，已经无法更改。选择一个模式会记住它，新建会话时使用。"
                        } else {
                            "决定助手能用哪些工具、以及它的工作方式。"
                        }
                    } else {
                        "选择下一个会话使用的模式。"
                    },
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
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
                        // Not an error: a host without the preset registry simply
                        // has no modes to choose from, and saying so is more useful
                        // than a failure banner.
                        "桌面端没有提供可选的模式，当前使用它的默认设置。",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> for (preset in presets) {
                        PresetRow(
                            preset = preset,
                            chosen = preset.id == chosen,
                            enabled = !switching,
                            locked = locked,
                            onPick = { onPick(preset.id) },
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
 * One selectable mode.
 *
 * `selectable` rather than `clickable`, so accessibility services are told this is
 * a choice with a checked state rather than a button that happens to change
 * something — the same reasoning as the permission picker's rows.
 *
 * A preset the host reports as `broken` is listed but disabled, with the host's own
 * diagnostic underneath. Hiding it would make the list disagree with the desktop,
 * and the diagnostic is the only thing that tells a user why a mode they declared
 * is missing.
 */
@Composable
private fun PresetRow(
    preset: AgentPresets.Preset,
    chosen: Boolean,
    enabled: Boolean,
    locked: Boolean,
    onPick: () -> Unit,
) {
    val usable = preset.usable
    val accent = if (!usable) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary
    Surface(
        color = if (chosen && usable) accent.copy(alpha = 0.10f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .selectable(
                selected = chosen,
                enabled = enabled && usable,
                onClick = onPick,
            ),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = chosen, onClick = null, enabled = enabled && usable)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when {
                            !usable -> Icons.Default.WarningAmber
                            locked -> Icons.Default.Lock
                            else -> Icons.Default.Tune
                        },
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = accent,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        preset.label,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (!usable) accent else MaterialTheme.colorScheme.onSurface,
                    )
                    if (preset.isDefault) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            // Names the host's own notion of "the mode a new task
                            // starts in", which is not the same as the one currently
                            // chosen — a user who changed it needs to see both.
                            "新任务默认",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (chosen && usable) {
                        Spacer(Modifier.width(5.dp))
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "当前模式",
                            modifier = Modifier.size(13.dp),
                            tint = accent,
                        )
                    }
                }
                val description = preset.broken ?: preset.description
                if (description.isNotBlank()) {
                    Text(
                        description,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        color = if (!usable) accent
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
