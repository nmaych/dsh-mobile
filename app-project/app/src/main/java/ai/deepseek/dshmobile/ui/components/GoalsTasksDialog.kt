package ai.deepseek.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.deepseek.dshmobile.data.GoalState
import ai.deepseek.dshmobile.data.TodoItem

/**
 * The goal/todo sheet: what this conversation is trying to do, and which steps
 * it has promised along the way.
 *
 * ## Why a sheet and not chips on the strip
 *
 * The goal's objective is a paragraph and the todo list is up to a few dozen
 * rows; either would wreck a chip row that is already one line tall in
 * landscape. The strip already answers "what is this conversation running
 * with" (model, workspace, mode); this sheet answers "what is it *doing*" —
 * a different question with a different shape of answer.
 *
 * ## Why it renders the host's data verbatim
 *
 * Both values are projection reads: the `goal` projection's
 * `{goal, roundsStarted}` and the `todos` projection's whole-list snapshots.
 * Re-deriving them from the transcript (scanning `todo_write` tool calls, or
 * `source.kind: "goal"` injections) would be a second copy that drifts the
 * moment a turn resets the list or the goal is revised — the projection *is*
 * the fold of those events, so reading it is the honest path.
 *
 * An empty todo list is normal: the host clears the projection at every
 * `turn/start`, so between turns the list is empty by design and the sheet
 * says so rather than treating it as a failure.
 */
@Composable
fun GoalsTasksDialog(
    /** The session's current goal, or null when there is none. */
    goal: GoalState?,
    /** The session's todo list, or null when the host offers no todo tool. */
    todos: List<TodoItem>?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("目标与任务", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    "来自桌面端的实时投影",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                GoalSection(goal)
                if (todos != null) {
                    Spacer(Modifier.size(10.dp))
                    TodoSection(todos)
                }
                if (goal == null && todos == null) {
                    Text(
                        "这个会话当前没有目标或任务清单。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
        },
    )
}

@Composable
private fun GoalSection(goal: GoalState?) {
    if (goal == null) return
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                RoundedCornerShape(8.dp),
            )
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Flag,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                "当前目标",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                goal.phaseLabel,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(5.dp))
        Text(goal.objective, fontSize = 13.sp, lineHeight = 18.sp)
        if (goal.blockedReason != null) {
            Spacer(Modifier.size(5.dp))
            Text(
                goal.blockedReason,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (goal.maxRounds > 0) {
            Spacer(Modifier.size(5.dp))
            Text(
                "第 ${goal.roundsStarted}/${goal.maxRounds} 轮",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TodoSection(todos: List<TodoItem>) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "任务清单",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (todos.isEmpty()) {
            Spacer(Modifier.size(4.dp))
            Text(
                "本回合还没有任务清单。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        Spacer(Modifier.size(4.dp))
        for (todo in todos) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (todo.done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = when {
                        todo.done -> MaterialTheme.colorScheme.primary
                        todo.running -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    todo.content,
                    fontSize = 12.5.sp,
                    color = if (todo.done) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
