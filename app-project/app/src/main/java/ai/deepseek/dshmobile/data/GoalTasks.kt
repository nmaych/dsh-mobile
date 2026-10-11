package ai.deepseek.dshmobile.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The session's current goal, as the host's `goal` projection reports it.
 *
 * The projection's view is `{goal, roundsStarted, createdAt, updatedAt}` or an
 * outright null — there is no "empty goal" object, so [currentOf] returns null
 * both for a missing key and for a cleared one and the caller treats both as
 * "no goal to show".
 */
data class GoalState(
    val id: String,
    val revision: Int,
    val objective: String,
    /** `active | paused | blocked | complete`. */
    val phase: String,
    /** Why the goal is blocked, when the host says so. */
    val blockedReason: String?,
    val roundsStarted: Int,
    val maxRounds: Int,
) {
    /** The phase as the short label the sheet shows. */
    val phaseLabel: String
        get() = when (phase) {
            "active" -> "进行中"
            "paused" -> "已暂停"
            "blocked" -> "受阻"
            "complete" -> "已完成"
            else -> phase
        }

    companion object {
        /**
         * Read one `goal` projection value, from either the one-shot
         * `session/projections` response (`values.goal`) or a live
         * `session/control` frame (`value`). Both carry the same shape.
         */
        fun currentOf(value: Any?): GoalState? {
            if (value == null || value == JSONObject.NULL) return null
            val o = value as? JSONObject ?: return null
            val goal = o.optJSONObject("goal") ?: return null
            val id = goal.optString("id")
            val objective = goal.optString("objective")
            if (id.isBlank() || objective.isBlank()) return null
            return GoalState(
                id = id,
                revision = goal.optInt("revision", 0),
                objective = objective,
                phase = goal.optString("phase").ifBlank { "active" },
                blockedReason = goal.optJSONObject("blockedReason")
                    ?.optString("message")?.takeIf { it.isNotBlank() },
                roundsStarted = o.optInt("roundsStarted", 0),
                maxRounds = goal.optInt("maxGoalRounds", 0),
            )
        }
    }
}

/** One todo row from the host's `todos` projection. */
data class TodoItem(
    val content: String,
    /** `pending | in_progress | completed`. */
    val status: String,
) {
    val done: Boolean get() = status == "completed"
    val running: Boolean get() = status == "in_progress"

    companion object {
        /**
         * Read one `todos` projection value: a JSON array of `{content, status}`
         * or null. Null is the host's "nothing written this turn", which clears
         * the list — it is a real state, not an absent key.
         */
        fun listOf(value: Any?): List<TodoItem>? {
            if (value == null || value == JSONObject.NULL) return null
            val array = value as? JSONArray ?: return null
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val content = o.optString("content")
                if (content.isBlank()) return@mapNotNull null
                TodoItem(content = content, status = o.optString("status").ifBlank { "pending" })
            }
        }
    }
}
