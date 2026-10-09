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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.deepseek.dshmobile.data.QuestionAnswer
import ai.deepseek.dshmobile.data.UserQuestion

/**
 * The dialog that answers a pending `ask_user_question`.
 *
 * ## Why a dialog, and why blocking
 *
 * The host's tool call is *suspended* inside the answer waterfall: the turn
 * cannot advance until this is answered or the host's wait expires. So this is
 * not a notification to be dismissed and revisited — it is the one thing the
 * conversation is waiting on. A modal is the honest shape for that, and it is
 * what the desktop does too.
 *
 * ## One batch, submitted once
 *
 * The host validates the whole batch: the reply must name every question of the
 * call exactly once, and a batch that does not is rejected outright. So the
 * questions are answered together — there is no "next question" round trip and
 * no partial submit. The answers are collected here and handed back in one list,
 * in the order the questions were asked.
 *
 * ## Free text
 *
 * An option may be *typed* as well as picked: a question with no options is
 * free-text only, and a question with options still accepts a custom answer
 * alongside them. `custom` is only sent when non-blank, because the wire schema
 * treats its presence as significant.
 */
@Composable
fun QuestionDialog(
    question: UserQuestion,
    onAnswer: (List<QuestionAnswer>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Per-question drafts, keyed by question id so they survive recomposition.
    val selected = remember(question.eventId) {
        mutableStateMapOf<String, Set<String>>()
    }
    val custom = remember(question.eventId) {
        mutableStateMapOf<String, String>()
    }

    // A question is answerable when an option is picked or text was typed. The
    // host rejects a batch whose question has neither, so the submit button is
    // gated on this rather than sending a doomed request.
    val answerable = question.questions.all { item ->
        !selected[item.id].isNullOrEmpty() || !custom[item.id].isNullOrBlank()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("需要你的回答", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (question.questions.size > 1) {
                        "共 ${question.questions.size} 个问题，全部回答后一起提交。"
                    } else {
                        "回答后助手会继续工作。"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        },
        text = {
            // Bounded and scrollable: a batch can carry several questions with
            // long option lists, and an unbounded dialog would push the submit
            // button off the screen — the button is the point of the card.
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                for ((index, item) in question.questions.withIndex()) {
                    if (index > 0) {
                        Spacer(Modifier.height(14.dp))
                    }
                    if (item.header.isNotBlank()) {
                        Text(
                            item.header,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                    Text(
                        item.question,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (item.detail.isNotBlank()) {
                        Text(
                            item.detail,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }

                    if (item.options.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        for (option in item.options) {
                            val chosen = selected[item.id]?.contains(option.label) == true
                            OptionRow(
                                label = option.label,
                                description = option.description,
                                chosen = chosen,
                                multiSelect = item.multiSelect,
                                onToggle = {
                                    val current = selected[item.id].orEmpty()
                                    val next = if (item.multiSelect) {
                                        if (chosen) current - option.label else current + option.label
                                    } else {
                                        // Single-select replaces rather than
                                        // toggles: tapping the chosen option
                                        // again must not leave the question
                                        // unanswered, which is what a toggle
                                        // would do.
                                        setOf(option.label)
                                    }
                                    selected[item.id] = next
                                },
                            )
                        }
                    }

                    // The free-text field is offered for every question, with or
                    // without options: the host accepts a custom answer beside a
                    // selection, and a question with no options needs it.
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = custom[item.id].orEmpty(),
                        onValueChange = { custom[item.id] = it },
                        placeholder = {
                            Text(
                                if (item.options.isEmpty()) "输入你的答案" else "或输入你自己的答案",
                                fontSize = 12.sp,
                            )
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = item.options.isEmpty(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAnswer(
                        question.questions.map { item ->
                            val text = custom[item.id].orEmpty().trim()
                            val picked = selected[item.id].orEmpty().toList()
                            QuestionAnswer(
                                id = item.id,
                                // A custom answer supersedes a single selection,
                                // mirroring the desktop: typing an answer means
                                // the options were not what was wanted. A
                                // multi-select keeps its picks, because several
                                // of them plus a note is a meaningful answer.
                                selected = if (text.isNotEmpty() && !item.multiSelect) {
                                    emptyList()
                                } else {
                                    picked
                                },
                                custom = text.takeIf { it.isNotEmpty() },
                            )
                        },
                    )
                },
                enabled = answerable,
            ) {
                Text("提交", fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("稍后", fontSize = 13.sp) }
        },
    )
}

/**
 * One selectable option.
 *
 * Drawn as a radio button or a checkbox depending on whether the question allows
 * more than one answer, because the control is what tells the user which of the
 * two rules applies — the label alone cannot.
 */
@Composable
private fun OptionRow(
    label: String,
    description: String,
    chosen: Boolean,
    multiSelect: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        color = if (chosen) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            // `selectable` rather than `clickable`: it carries the checked state
            // to accessibility services, so the option reads as a choice instead
            // of a button that happens to change something.
            .selectable(selected = chosen, onClick = onToggle),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (multiSelect) {
                Checkbox(checked = chosen, onCheckedChange = { onToggle() })
            } else {
                RadioButton(selected = chosen, onClick = { onToggle() })
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 12.5.sp, lineHeight = 17.sp)
                if (description.isNotBlank()) {
                    Text(
                        description,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
