package ai.deepseek.dshmobile.data

import org.json.JSONObject

/**
 * A pending approval request, forwarded from the desktop.
 *
 * ## Why this is the same channel as `ask_user_question`
 *
 * When the agent wants to do something the session's approval policy gates — run a
 * command outside the workspace, escalate the sandbox — the Harness asks its
 * answerers over a Cordis **waterfall** (`approval/request`). The desktop UI is one
 * such answerer; this app is another. Both waterfalls are forwarded over the same
 * `$events` stream, in the same frame shape, and answered through the same
 * `$events/result` call, so the plumbing is shared with [UserQuestion] and only
 * the payload and the outcome vocabulary differ.
 *
 * Before this existed the phone could *see* the request arrive as an
 * `approval/asked` event in the transcript — which the parser already rendered as
 * "等待批准：…（请在桌面端确认）" — but had no way to answer it, so the turn sat
 * blocked until someone walked to the desktop. That is the same dead end
 * `ask_user_question` was in before 1.1.6, and it is why the note said "请在桌面端确认".
 *
 * ## Fail-closed, and why that shapes the UI
 *
 * The host's policy is `ask` by default and the whole seam **fails closed**: with
 * no answerer, or an answerer that errors, the request resolves `unavailable` and
 * the operation is *refused*. So the phone declining to answer is not neutral — it
 * is a rejection. The UI must therefore present this as a decision to be made, not
 * as a notification that can be dismissed, and it must never claim to have
 * "skipped" one. `dismiss` in the view model only hides the prompt locally, which
 * leaves the desktop's own answerer to decide.
 *
 * ## Correlation
 *
 * Identified by the pair `(clientId, eventId)`, exactly as a question is: `clientId`
 * names one `$events` stream and dies with it, `eventId` names the request within
 * it, and both must be echoed back verbatim. They are carried here rather than
 * looked up later for that reason.
 *
 * @param clientId the `$events` stream identity, from its `ready` frame.
 * @param eventId this request's identity within that stream.
 * @param agentId the session the request belongs to, as the host scoped it.
 * @param toolName the tool that wants to act, e.g. `pwsh`.
 * @param callId the tool call it belongs to, when the host named one. Present for
 *   a tool-driven request; the desktop renders correlated detail from it.
 * @param reason the host's audit reason — an untranslated sentence about *what*
 *   is being asked for.
 * @param displayReason the requester's own localized presentation copy, when it
 *   supplied one. Preferred over [reason] for display, exactly as the desktop
 *   does: [reason] is the audit record and is not written for a phone screen.
 */
data class ApprovalRequest(
    val clientId: String,
    val eventId: String,
    val agentId: String,
    val toolName: String,
    val callId: String?,
    val reason: String,
    val displayReason: String,
) {
    /**
     * What to put in front of the user.
     *
     * The requester's own copy wins because it is the only one written to be read:
     * `reason` is an audit string, and the desktop resolves `displayReason` through
     * its locale table for the same reason. Falls back to [reason], and then to a
     * sentence built from the tool name, so the prompt is never blank — a dialog
     * with no text is one the user cannot decide on.
     */
    val headline: String
        get() = displayReason.ifBlank { reason }.ifBlank { "助手想要使用「$toolName」，需要你确认。" }

    /**
     * The decision the user can make.
     *
     * The host's vocabulary is `allowed-once | rejected | cancelled |
     * unavailable`. Only the first two are *decisions*: `cancelled` means the
     * request's own signal aborted and `unavailable` means no answerer claimed
     * it, and neither is something a button should send. So this enum is
     * deliberately narrower than the wire union.
     *
     * Declared beside [ApprovalRequest] rather than inside its companion: a
     * nested type of a companion is spelled `ApprovalRequest.Companion.Decision`
     * from Kotlin, which is not what any call site wants to write.
     */
    enum class Decision(val wire: String) {
        /** Claim the request: the operation proceeds, this once. */
        ALLOW_ONCE("allowed-once"),

        /** Refuse it. The host treats this as the operation failing. */
        REJECT("rejected"),
    }

    companion object {
        /** The forwarded event name this app answers. */
        const val EVENT_NAME = "approval/request"

        /**
         * Parse one forwarded waterfall frame into an approval request.
         *
         * Returns null for anything that is not an `approval/request`. The frame is
         * untrusted input from the host, so every field is read defensively. Unlike
         * a question there is no required sub-structure — a request with no
         * `toolName` is still answerable, because the whole payload is advisory and
         * the decision does not depend on it — so the bar for "usable" is just a
         * resolvable `eventId`.
         *
         * `displayReason` is an object keyed by locale (`{en, zh, …}`) with `en`
         * required; `zh` is preferred here and `en` is the fallback, which is what
         * the desktop's own `resolveText` does with a Chinese locale.
         */
        fun fromFrame(frame: JSONObject, clientId: String): ApprovalRequest? {
            if (frame.optString("type") != "waterfall") return null
            if (frame.optString("event") != EVENT_NAME) return null
            val eventId = frame.optString("eventId").takeIf { it.isNotBlank() } ?: return null
            val request = frame.optJSONObject("request") ?: return null
            return ApprovalRequest(
                clientId = clientId,
                eventId = eventId,
                agentId = frame.optString("agentId"),
                toolName = request.optString("toolName").ifBlank { "工具" },
                callId = request.optString("callId").takeIf { it.isNotBlank() },
                reason = request.optString("reason"),
                displayReason = displayReasonOf(request.optJSONObject("displayReason")),
            )
        }

        /**
         * One localized sentence out of the `displayReason` map.
         *
         * A bare string is also accepted: the field is documented as a locale map,
         * but a requester that supplied a plain string is not worth refusing a
         * prompt over, and the alternative is a dialog with no text.
         */
        private fun displayReasonOf(o: JSONObject?): String {
            if (o == null) return ""
            for (key in listOf("zh", "en")) {
                val text = o.optString(key)
                if (text.isNotBlank()) return text
            }
            // Any other locale is better than nothing.
            val keys = o.keys()
            while (keys.hasNext()) {
                val text = o.optString(keys.next())
                if (text.isNotBlank()) return text
            }
            return ""
        }
    }
}
