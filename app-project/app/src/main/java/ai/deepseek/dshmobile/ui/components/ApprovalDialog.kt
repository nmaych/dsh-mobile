package ai.deepseek.dshmobile.ui.components

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
import androidx.compose.material.icons.filled.Build
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.deepseek.dshmobile.data.ApprovalRequest

/**
 * The dialog that answers a pending `approval/request`.
 *
 * ## Why a dialog, and why it blocks
 *
 * The host's tool call is *suspended* inside the approval waterfall: the turn
 * cannot advance until this is answered or the host's wait expires. So it is not a
 * notification to be dismissed and revisited — it is the one thing the
 * conversation is waiting on, and a modal is the honest shape for that.
 *
 * ## Why "稍后" is not "拒绝"
 *
 * The seam **fails closed**: with no answerer, a request resolves `unavailable`
 * and the operation is refused. That makes it tempting to treat the dismiss
 * button as a rejection, and it would be wrong. Dismissing here only hides the
 * prompt on *this* phone; the host's waterfall is deliberately left running, so
 * the desktop's own answerer can still decide. Sending `rejected` instead would
 * be the phone overruling a decision it was never asked to make — a user who
 * stepped away from their desk would come back to a refused operation they never
 * refused. The two buttons are therefore labelled for what they do.
 *
 * ## Why the reason is shown verbatim
 *
 * The headline is the requester's own presentation copy, falling back to the audit
 * reason. Both are untrusted host input and neither is translated here: a
 * permission prompt the user cannot read is one they cannot answer, and inventing
 * a paraphrase of "what is being asked for" would be worse than quoting it.
 */
@Composable
fun ApprovalDialog(
    request: ApprovalRequest,
    onDecide: (ApprovalRequest.Decision) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("需要授权", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    "助手要执行一个受保护的操作，需要你确认。",
                    fontSize = 11.sp,
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
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Build,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            request.toolName,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    request.headline,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
                if (request.callId != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        // Said plainly rather than hidden: the id is what makes
                        // this request traceable to the step in the transcript
                        // that is waiting on it, which is how a user checks what
                        // they are approving.
                        "对应工具调用：${request.callId}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDecide(ApprovalRequest.Decision.ALLOW_ONCE) }) {
                Text("允许一次", fontSize = 13.sp)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onDecide(ApprovalRequest.Decision.REJECT) }) {
                    Text(
                        "拒绝",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = onDismiss) { Text("稍后", fontSize = 13.sp) }
            }
        },
    )
}
