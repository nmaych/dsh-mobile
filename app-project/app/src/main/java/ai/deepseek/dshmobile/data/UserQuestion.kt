package ai.deepseek.dshmobile.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A pending `ask_user_question` request, forwarded from the desktop.
 *
 * When the agent calls `ask_user_question`, the Harness asks its answerers over
 * a Cordis **waterfall** (`user-questions/request`). The desktop UI is one such
 * answerer; this app is another. Before 1.1.6 the phone could *see* the question
 * — it arrives as a `tool/call` in the transcript — but had no way to answer it,
 * so a turn that asked a question simply stalled until the desktop answered it
 * or the wait expired. The tool call was a dead end on mobile.
 *
 * ## Correlation
 *
 * A request is identified by the pair `(clientId, eventId)`, **not** by which
 * socket delivered it. `clientId` names one `$events` stream and dies with it;
 * `eventId` names the request within that stream. Both must be echoed back
 * verbatim, which is why they are carried here rather than looked up later.
 *
 * ## First answer wins
 *
 * The waterfall settles on the first answerer that returns a value, and the
 * gateway then sends `cancel` to the others. So this app must not "decline"
 * speculatively: replying `next` would step aside, which is correct, but staying
 * silent leaves the question open for the user, which is the point. An answer
 * that loses the race is simply dropped by the host, and the resulting `cancel`
 * frame is what tells the UI to close.
 *
 * @param clientId the `$events` stream identity, from its `ready` frame.
 * @param eventId this request's identity within that stream.
 * @param agentId the session the question belongs to, as the host scoped it.
 * @param callId the `ask_user_question` tool call, when the host named one.
 *   Present for a timed question; a `null` here means the call is not keyed to a
 *   tool call and can only be answered through the waterfall.
 * @param questions the questions to put to the user, in the order to ask them.
 */
data class UserQuestion(
    val clientId: String,
    val eventId: String,
    val agentId: String,
    val callId: String?,
    val questions: List<Question>,
) {
    /**
     * One question of the batch.
     *
     * A batch is answered **atomically**: the host validates that the reply names
     * every question of the call exactly once, and rejects the whole batch
     * otherwise. So a partial answer is not a thing, and the UI must collect all
     * of them before it can submit.
     *
     * @param id the identifier the answer must quote back.
     * @param question the question text.
     * @param detail optional supporting prose.
     * @param header optional short label for the question.
     * @param options the offered choices; empty means free text only.
     * @param multiSelect true when more than one option may be chosen.
     */
    data class Question(
        val id: String,
        val question: String,
        val detail: String,
        val header: String,
        val options: List<Option>,
        val multiSelect: Boolean,
    ) {
        /** One selectable choice. */
        data class Option(val label: String, val description: String)
    }

    companion object {
        /**
         * Parse one forwarded waterfall frame into a question batch.
         *
         * Returns null for anything that is not a `user-questions/request` with
         * a usable question list. The frame is untrusted input from the host, so
         * every field is read defensively: a missing `questions` array is not a
         * question, and inventing an empty one would render an answerable dialog
         * with nothing in it.
         *
         * The `clientId` is not in the frame — the gateway's `waterfall` frame
         * carries only `event`, `eventId`, `agentId` and `request`, and the
         * client identity comes from the stream's opening `ready` frame. The
         * caller supplies it.
         */
        fun fromFrame(frame: JSONObject, clientId: String): UserQuestion? {
            if (frame.optString("type") != "waterfall") return null
            if (frame.optString("event") != EVENT_NAME) return null
            val eventId = frame.optString("eventId").takeIf { it.isNotBlank() } ?: return null
            val request = frame.optJSONObject("request") ?: return null
            val raw = request.optJSONArray("questions") ?: return null
            val questions = (0 until raw.length()).mapNotNull { i ->
                questionOf(raw.optJSONObject(i))
            }
            if (questions.isEmpty()) return null
            return UserQuestion(
                clientId = clientId,
                eventId = eventId,
                agentId = frame.optString("agentId"),
                callId = request.optJSONObject("wait")
                    ?.optString("callId")
                    ?.takeIf { it.isNotBlank() },
                questions = questions,
            )
        }

        private fun questionOf(o: JSONObject?): Question? {
            if (o == null) return null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val text = o.optString("question").takeIf { it.isNotBlank() } ?: return null
            val options = o.optJSONArray("options")?.let { array ->
                (0 until array.length()).mapNotNull { i ->
                    val opt = array.optJSONObject(i) ?: return@mapNotNull null
                    val label = opt.optString("label").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    Question.Option(label, opt.optString("description"))
                }
            } ?: emptyList()
            return Question(
                id = id,
                question = text,
                detail = o.optString("detail"),
                header = o.optString("header"),
                options = options,
                multiSelect = o.optBoolean("multiSelect", false),
            )
        }

        /** The forwarded event name this app answers. */
        const val EVENT_NAME = "user-questions/request"
    }
}

/**
 * One question's answer, as the wire expects it.
 *
 * `selected` holds **option labels**, not indices: the schema quotes the label
 * back, and the host matches on it. `custom` is the free-text answer, omitted
 * entirely when blank so the host's exact-keys validation is not tripped by an
 * empty string it did not ask for.
 */
data class QuestionAnswer(
    val id: String,
    val selected: List<String>,
    val custom: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("selected", JSONArray(selected))
        .apply { if (!custom.isNullOrBlank()) put("custom", custom) }
}
