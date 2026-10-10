package ai.deepseek.dshmobile.data

import org.json.JSONArray
import org.json.JSONObject

/** Who produced a transcript entry. */
enum class Role { USER, ASSISTANT, SYSTEM, TOOL }

/** One rendered block inside a message. */
sealed interface Block {
    data class Text(val text: String) : Block

    /**
     * A stretch of the model's reasoning.
     *
     * [parts] is how many separate reasoning blocks were folded into this one by
     * [SessionParser.mergeTurns]; it is 1 for a block straight out of the log. The
     * count is carried so the UI can say a thinking section was merged rather than
     * pretending the model thought once.
     */
    data class Reasoning(val text: String, val parts: Int = 1) : Block

    data class ToolCall(
        val callId: String,
        val name: String,
        val input: String,
        val output: String? = null,
        val failed: Boolean = false,
    ) : Block
    data class Notice(val text: String, val isError: Boolean) : Block
}

/** One entry in the chat transcript. */
data class Message(
    val id: String,
    val role: Role,
    val blocks: List<Block> = emptyList(),
    val streaming: Boolean = false,
    val time: Long = 0L,
    /**
     * The turn this entry belongs to, when the log says so.
     *
     * A single turn is many steps: one `assistant/message` per step, each with its
     * own reasoning and tool call. The turn number is what lets the transcript
     * group those steps back into the one response the user asked for — see
     * [SessionParser.mergeTurns]. Null for anything the server does not tag with a
     * turn, which is every user and system row.
     */
    val turn: Int? = null,
    /**
     * How long this entry took, when that is knowable.
     *
     * Only a merged turn has one: it is the span from the first to the last step
     * settlement of that turn, which is the one duration the log can actually
     * prove. A single-step message reports 0 rather than "0 seconds" — the log
     * records when an event was *written*, not how long the model took to produce
     * it, so a one-step turn's own timestamp says nothing about its duration and
     * inventing a number would be worse than showing none.
     *
     * A turn whose steps share a single timestamp is also 0, for the same reason.
     */
    val durationMs: Long = 0L,
) {
    val plainText: String
        get() = blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
}

/**
 * Provider-reported token accounting for one attempt or a whole session.
 *
 * The Harness reports `inputTokens` as the **uncached** prompt side; cache reads
 * and writes are counted separately so a cached prompt does not look free. The
 * projection surface names the same field `uncachedInputTokens`, which is why
 * [fromProjection] and [fromUsage] read different keys for the same number.
 */
data class TokenUsage(
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val cacheReadTokens: Long = 0L,
    val cacheWriteTokens: Long = 0L,
) {
    /** Every billed bucket, which is what "total tokens" means to a user. */
    val total: Long get() = inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens

    val isEmpty: Boolean get() = total == 0L

    operator fun plus(other: TokenUsage): TokenUsage = TokenUsage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        cacheReadTokens = cacheReadTokens + other.cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens + other.cacheWriteTokens,
    )

    companion object {
        /**
         * Read the live `usage` stream chunk / `assistant/message.data.usage`
         * shape: `{inputTokens, outputTokens, cacheReadTokens?, cacheWriteTokens?}`.
         */
        fun fromUsage(o: JSONObject?): TokenUsage? {
            if (o == null) return null
            if (!o.has("inputTokens") && !o.has("outputTokens")) return null
            return TokenUsage(
                inputTokens = o.optLong("inputTokens", 0L),
                outputTokens = o.optLong("outputTokens", 0L),
                cacheReadTokens = o.optLong("cacheReadTokens", 0L),
                cacheWriteTokens = o.optLong("cacheWriteTokens", 0L),
            )
        }

        /**
         * Read the `tokenUsage` session projection, which is the session's
         * running total rather than one attempt.
         */
        fun fromProjection(o: JSONObject?): TokenUsage? {
            if (o == null) return null
            return TokenUsage(
                inputTokens = o.optLong("uncachedInputTokens", 0L),
                outputTokens = o.optLong("outputTokens", 0L),
                cacheReadTokens = o.optLong("cacheReadTokens", 0L),
                cacheWriteTokens = o.optLong("cacheWriteTokens", 0L),
            )
        }
    }
}

/**
 * `callId -> (messageIndex, blockIndex)` for one session's transcript.
 *
 * Shared across `fold` batches: a tool result lands in a later batch than the
 * call that created its card.
 */
typealias CallIndex = MutableMap<String, Pair<Int, Int>>

/**
 * Parser for the Harness session event log.
 *
 * The Harness writes a rich, append-only event log; a client renders a subset of
 * it. Two rules drive everything here:
 *
 *  - **Only `surfaceOp == "append"` events are conversation.** The five
 *    surface-eligible types (`system/message`, `developer/message`,
 *    `user/message`, `assistant/message`, `tool/result`) may also carry
 *    `{"op":"replace",…}`, which rewrites model-visible history only. A landed
 *    replacement must never erase text the user already saw.
 *
 *  - **Unknown types are ignored, not fatal.** The vocabulary is
 *    merge-extensible across plugins, so anything unrecognised is skipped.
 */
object SessionParser {

    data class Folded(
        val messages: List<Message>,
        val title: String?,
        val running: Boolean,
        val lastSeq: Long,
        /** Turn number of the currently open turn, or null. */
        val openTurn: Int?,
        /** Provider usage reported by the assistant settlements in this batch. */
        val usage: TokenUsage?,
        /** `provider/model` of the newest assistant settlement, when present. */
        val model: String?,
    )

    /** Where a `tool/result` can find the card its `tool/call` created. */
    // (The `CallIndex` alias is declared at file scope: Kotlin does not allow a
    // typealias nested inside an object.)

    // ------------------------------------------------------------ item frames

    /** Durable events carried by a `snapshot` or `event` item. */
    fun eventsOf(item: JSONObject): List<JSONObject> = when (item.optString("type")) {
        "snapshot" -> {
            val records = item.optJSONArray("records") ?: JSONArray()
            (0 until records.length()).mapNotNull { i ->
                records.optJSONObject(i)?.optJSONObject("event")
            }
        }
        "event" -> listOfNotNull(item.optJSONObject("event"))
        else -> emptyList()
    }

    fun headerOf(item: JSONObject): JSONObject? =
        if (item.optString("type") == "snapshot") item.optJSONObject("header") else null

    /** The live token-delta frame, when the item is an `assistant-stream`. */
    fun streamFrameOf(item: JSONObject): JSONObject? =
        if (item.optString("type") == "assistant-stream") item.optJSONObject("frame") else null

    fun cursorOf(item: JSONObject): Long? =
        if (item.optString("type") == "snapshot") item.optLong("cursor", -1L) else null

    /**
     * `provider/model` from a `session/list` row's `modelSelection` projection.
     *
     * `next` is the pending choice and `lastUsed` is what the previous turn
     * actually ran with; `next` wins when both are present.
     */
    fun modelSelectionOf(projections: JSONObject?): String? {
        val selection = projections?.optJSONObject("values")?.optJSONObject("modelSelection")
            ?: return null
        val chosen = selection.optJSONObject("next") ?: selection.optJSONObject("lastUsed")
            ?: return null
        val provider = chosen.optString("provider")
        val model = chosen.optString("model")
        if (provider.isBlank() || model.isBlank()) return null
        return "$provider/$model"
    }

    // ------------------------------------------------------------------- fold

    /**
     * Fold durable events into a transcript.
     *
     * @param previous messages already rendered, so folding is incremental.
     * @param callIndex carries `callId -> (messageIndex, blockIndex)` **across
     *   batches**. It must be the same map for the whole session: a live turn
     *   commits `assistant/message` and its `tool/result` in different batches,
     *   and a per-call index would fail to find the card the call created and
     *   render the result as a second, duplicate card.
     */
    fun fold(
        events: List<JSONObject>,
        previous: List<Message> = emptyList(),
        callIndex: CallIndex = mutableMapOf(),
    ): Folded {
        val out = previous.toMutableList()
        var title: String? = null
        var lastSeq = 0L
        var openTurn: Int? = null
        var usage: TokenUsage? = null
        var model: String? = null

        fun indexCalls(messageIndex: Int) {
            val msg = out.getOrNull(messageIndex) ?: return
            msg.blocks.forEachIndexed { blockIndex, block ->
                if (block is Block.ToolCall && block.callId.isNotBlank()) {
                    callIndex[block.callId] = messageIndex to blockIndex
                }
            }
        }

        fun replaceBlock(messageIndex: Int, blockIndex: Int, block: Block) {
            val msg = out.getOrNull(messageIndex) ?: return
            if (blockIndex !in msg.blocks.indices) return
            val blocks = msg.blocks.toMutableList()
            blocks[blockIndex] = block
            out[messageIndex] = msg.copy(blocks = blocks)
        }

        // Index everything already rendered first: this batch may carry the
        // result of a call committed in an earlier one.
        for (index in out.indices) indexCalls(index)

        for (event in events) {
            val type = event.optString("type")
            val seq = event.optLong("seq", 0L)
            if (seq > lastSeq) lastSeq = seq
            val time = event.optLong("time", 0L)
            val data = event.opt("data") as? JSONObject ?: JSONObject()
            val surfaceOp = event.opt("surfaceOp")

            when (type) {
                // ---------------------------------------------------- turn flow
                "turn/start" -> openTurn = data.optInt("turn", 0)

                "turn/end" -> {
                    openTurn = null
                    val reason = data.optJSONObject("reason")
                    when (reason?.optString("kind")) {
                        "error" -> {
                            val err = reason.optJSONObject("error")
                            out += Message(
                                id = "err-$seq",
                                role = Role.SYSTEM,
                                blocks = listOf(
                                    Block.Notice(
                                        err?.optString("message") ?: "本轮出错",
                                        isError = true,
                                    )
                                ),
                                time = time,
                            )
                        }
                        "max-tokens" -> out += Message(
                            id = "mt-$seq",
                            role = Role.SYSTEM,
                            blocks = listOf(Block.Notice("已达到最大输出长度", isError = false)),
                            time = time,
                        )
                        "aborted" -> out += Message(
                            id = "ab-$seq",
                            role = Role.SYSTEM,
                            blocks = listOf(Block.Notice("已停止生成", isError = false)),
                            time = time,
                        )
                        else -> Unit
                    }
                }

                "session/title" -> title = data.optString("title").takeIf { it.isNotBlank() }

                // ------------------------------------------------- user message
                "user/message" -> {
                    if (!isAppend(surfaceOp)) continue
                    val text = textOf(data.optJSONArray("content"))
                    if (text.isNotBlank()) {
                        out += Message(
                            id = data.optString("id").ifBlank { "u-$seq" },
                            role = Role.USER,
                            blocks = listOf(Block.Text(text)),
                            time = time,
                        )
                    }
                }

                // -------------------------------------------- assistant message
                "assistant/message" -> {
                    if (!isAppend(surfaceOp)) continue
                    val message = data.optJSONObject("message") ?: continue
                    TokenUsage.fromUsage(data.optJSONObject("usage"))?.let { usage = it }
                    modelLabelOf(message)?.let { model = it }
                    val blocks = blocksOf(message.optJSONArray("content"))
                    if (blocks.isEmpty()) continue
                    val id = message.optString("id").ifBlank { "a-$seq" }
                    // The turn is what lets one response be reassembled from the
                    // many steps that produced it; every real settlement carries
                    // it (4126/4126 in the sampled logs).
                    val turn = data.optInt("turn", -1).takeIf { it >= 0 }
                    // Replace an earlier render of the same message id.
                    val existing = out.indexOfFirst { it.id == id }
                    val entry = Message(
                        id = id,
                        role = Role.ASSISTANT,
                        blocks = blocks,
                        time = time,
                        turn = turn,
                    )
                    if (existing >= 0) out[existing] = entry else out += entry
                    indexCalls(if (existing >= 0) existing else out.lastIndex)
                }

                // --------------------------------------------------- tool calls
                "tool/call" -> {
                    // Log-only. Rendered inside the owning assistant message when
                    // it carries a matching tool-call block; otherwise shown as
                    // its own card so nothing is lost.
                    val callId = data.optString("callId")
                    if (callId.isNotBlank() && !callIndex.containsKey(callId)) {
                        val name = data.optString("name").ifBlank { "tool" }
                        val args = data.optString("arguments")
                        val turn = data.optInt("turn", -1).takeIf { it >= 0 }
                        val last = out.lastOrNull()
                        // Attaching to the previous assistant entry is what keeps
                        // the call with the step that made it. The turn must agree:
                        // hanging a new turn's call on the previous turn's message
                        // would move it into a group it does not belong to.
                        if (last != null && last.role == Role.ASSISTANT &&
                            (turn == null || last.turn == turn)
                        ) {
                            val blocks = last.blocks + Block.ToolCall(callId, name, args)
                            out[out.lastIndex] = last.copy(blocks = blocks)
                            indexCalls(out.lastIndex)
                        } else {
                            out += Message(
                                id = "tc-$seq",
                                role = Role.TOOL,
                                blocks = listOf(Block.ToolCall(callId, name, args)),
                                time = time,
                                // Carried so the card joins the turn it belongs to
                                // instead of splitting that turn in two.
                                turn = turn,
                            )
                            indexCalls(out.lastIndex)
                        }
                    }
                }

                "tool/result" -> {
                    if (!isAppend(surfaceOp)) continue
                    val message = data.optJSONObject("message")
                    // `toolCallId` is the canonical field; older logs only carry
                    // `source.callId`.
                    val callId = message?.optString("toolCallId")?.takeIf { it.isNotBlank() }
                        ?: message?.optJSONObject("source")?.optString("callId").orEmpty()
                    val output = textOf(message?.optJSONArray("content"))
                    val failed = message?.optBoolean("isError", false) == true || data.has("error")

                    val target = callIndex[callId]
                    if (target != null) {
                        val (mi, bi) = target
                        val existing = out.getOrNull(mi)?.blocks?.getOrNull(bi) as? Block.ToolCall
                        if (existing != null) {
                            replaceBlock(mi, bi, existing.copy(output = output, failed = failed))
                        }
                    } else if (output.isNotBlank()) {
                        out += Message(
                            id = message?.optString("id")?.takeIf { it.isNotBlank() } ?: "tr-$seq",
                            role = Role.TOOL,
                            blocks = listOf(Block.ToolCall(callId, "tool", "", output, failed)),
                            time = time,
                            turn = data.optInt("turn", -1).takeIf { it >= 0 },
                        )
                        indexCalls(out.lastIndex)
                    }
                }

                // ------------------------------------------------- misc notices
                "llm/retry" -> {
                    val failure = data.optJSONObject("failure")
                    val msg = failure?.optString("message") ?: "正在重试"
                    out += Message(
                        id = "retry-$seq",
                        role = Role.SYSTEM,
                        blocks = listOf(Block.Notice("重试中：$msg", isError = false)),
                        time = time,
                    )
                }

                "approval/asked" -> {
                    val toolName = data.optString("toolName").ifBlank { "操作" }
                    out += Message(
                        id = "appr-$seq",
                        role = Role.SYSTEM,
                        blocks = listOf(
                            Block.Notice("等待批准：$toolName（请在桌面端确认）", isError = false)
                        ),
                        time = time,
                    )
                }

                // `assistant/attempt` is a failed/cancelled attempt with no
                // message; it must not render as a reply. Everything else in the
                // plugin-extensible vocabulary is skipped too.
                else -> Unit
            }
        }

        return Folded(out, title, openTurn != null, lastSeq, openTurn, usage, model)
    }

    /** Only `"append"` surface operations are conversation. */
    private fun isAppend(surfaceOp: Any?): Boolean = surfaceOp is String && surfaceOp == "append"

    // ------------------------------------------------------------ turn merging

    /**
     * Fold one turn's many steps into one assistant entry.
     *
     * A turn is one answer, but the log writes one `assistant/message` per *step*,
     * and a step is one model call. A turn that reads three files and runs a
     * command is therefore five or six settlements — measured over 35 real sessions
     * the average is 29 steps per turn and the largest single turn had 830. Rendered
     * literally that is a column of near-identical bubbles, each with its own
     * "思考过程" box, which is what the 1.1.4 report described: the same answer
     * apparently repeating itself.
     *
     * Two things are merged and one is deliberately not:
     *
     *  - **Reasoning becomes one block**, carrying how many parts it folded. The
     *    user asked to see the thinking of a turn as one thing; splitting it per
     *    step also hid it, since every part was collapsed by default.
     *  - **Tool calls stay in order and stay separate.** They are the steps, and
     *    collapsing them would hide exactly the "读取 / 编辑 / 运行命令" sequence
     *    this release is meant to surface. A call re-stated by a later step keeps
     *    the copy that has a result, so a tool never renders twice.
     *  - **Text blocks are NOT concatenated.** Intermediate narration ("let me look
     *    at X") and the final answer are different things; gluing them into one
     *    paragraph would bury the answer inside the play-by-play.
     *
     * Only messages that share a **non-null turn** merge, and a row without one
     * ends the group. That is what keeps a notice between two steps (`llm/retry`,
     * `approval/asked`, `turn/end`) visible instead of silently swallowed: those
     * carry no turn, so they are a boundary by construction rather than by a rule
     * someone has to remember to apply. A turn the server never labelled is left
     * alone rather than guessed at.
     *
     * A `TOOL` row *is* part of its turn — it is how a `tool/result` whose call was
     * never seen in an assistant message still gets a card — so it joins the group
     * and contributes its tool block instead of splitting the turn in two.
     *
     * The result is display-only: [fold]'s own list stays as the log wrote it,
     * because `callIndex` addresses blocks by their position in it.
     */
    fun mergeTurns(messages: List<Message>): List<Message> {
        if (messages.size < 2) return messages
        val out = mutableListOf<Message>()
        var i = 0
        while (i < messages.size) {
            val first = messages[i]
            val turn = first.turn
            // A group starts at either an assistant settlement or a standalone tool
            // card, because both are part of the turn they name. Starting only at an
            // assistant row would leave an orphan `tool/result` that happens to
            // precede its turn's first settlement stranded outside the group.
            val startsTurn = first.role == Role.ASSISTANT || first.role == Role.TOOL
            if (turn == null || !startsTurn) {
                out += first
                i++
                continue
            }
            var end = i + 1
            while (end < messages.size && messages[end].turn == turn) end++
            out += if (end - i == 1) first else mergeSteps(messages.subList(i, end))
            i = end
        }
        return out
    }

    /** The single entry that replaces one turn's [group] of step settlements. */
    private fun mergeSteps(group: List<Message>): Message {
        val thinking = StringBuilder()
        var parts = 0
        // Where the merged reasoning block goes: the position of the turn's first
        // one, so the entry still reads "thought, then acted".
        var thinkingAt = -1
        val blocks = mutableListOf<Block>()
        // `callId -> index in blocks`, so a call restated by a later step updates
        // its existing card instead of adding a second one.
        val seen = mutableMapOf<String, Int>()

        for (message in group) {
            for (block in message.blocks) {
                when (block) {
                    is Block.Reasoning -> {
                        if (thinkingAt < 0) {
                            blocks += Block.Reasoning("")
                            thinkingAt = blocks.lastIndex
                        }
                        if (block.text.isNotBlank()) {
                            if (thinking.isNotEmpty()) thinking.append("\n\n")
                            thinking.append(block.text.trim())
                        }
                        // `parts` is carried rather than recounted, so merging an
                        // already-merged group stays correct.
                        parts += block.parts
                    }

                    is Block.ToolCall -> {
                        val at = if (block.callId.isBlank()) null else seen[block.callId]
                        if (at == null) {
                            blocks += block
                            if (block.callId.isNotBlank()) seen[block.callId] = blocks.lastIndex
                        } else {
                            // Keep whichever copy knows the outcome; a later step
                            // that merely repeats the call must not erase a result
                            // that has already landed.
                            val previous = blocks[at] as Block.ToolCall
                            if (previous.output == null && block.output != null) blocks[at] = block
                        }
                    }

                    else -> blocks += block
                }
            }
        }

        if (thinkingAt >= 0) blocks[thinkingAt] = Block.Reasoning(thinking.toString(), parts)

        // The turn's span: the earliest and latest step timestamps it has. This is
        // the only duration the log can prove — it records when each event was
        // *written*, so the span across a turn's steps is real elapsed time, while
        // a single-step turn has no span at all and is reported as 0 (see
        // [Message.durationMs]).
        //
        // A zero timestamp means "the log did not say", so those steps are skipped
        // rather than treated as the epoch: including one would report a duration
        // of ~56 years.
        var earliest = Long.MAX_VALUE
        var latest = 0L
        for (message in group) {
            val t = message.time
            if (t <= 0L) continue
            if (t < earliest) earliest = t
            if (t > latest) latest = t
        }
        val duration = if (latest > earliest && earliest != Long.MAX_VALUE) latest - earliest else 0L

        // The merged entry keeps the first settlement's id, because that is the id
        // the transcript has already rendered and the list keys on. `Role` becomes
        // ASSISTANT: the group is one assistant turn, and a lone `TOOL` row folded
        // in must not decide how the whole turn is drawn.
        return group.first().copy(
            role = Role.ASSISTANT,
            blocks = blocks,
            streaming = group.any { it.streaming },
            durationMs = duration,
        )
    }

    /**
     * Attach the in-flight streaming draft to an already-merged transcript.
     *
     * Split out from [mergeTurns] because the two run at completely different
     * rates. The durable transcript changes once per event batch; the draft changes
     * on every token delta, tens of times a second. Re-merging the whole transcript
     * for each delta would rebuild every finished turn's reasoning text over and
     * over — on a long session that is megabytes of string churn per second — so
     * [mergeTurns] is remembered against the durable list alone and only the turn
     * the draft belongs to is rebuilt here.
     *
     * A draft whose turn matches the last entry's is merged into it, which is what
     * stops a live reply from appearing as a second bubble beside the steps of the
     * turn it is still producing.
     */
    fun withDraft(merged: List<Message>, draft: Message?): List<Message> {
        if (draft == null) return merged
        val last = merged.lastOrNull() ?: return merged + draft
        // A `TOOL` row counts too: an orphan result carries its turn, and the turn
        // it belongs to may not have settled its next step yet. `mergeSteps`
        // normalises the result to an assistant entry, so the turn is still drawn
        // as a response rather than as a tool card with a reply bolted on.
        val partOfTurn = last.role == Role.ASSISTANT || last.role == Role.TOOL
        val joins = partOfTurn && last.turn != null && last.turn == draft.turn
        if (!joins) return merged + draft
        return merged.dropLast(1) + mergeSteps(listOf(last, draft))
    }

    /** `provider/model` from an assistant message's `source`, for the model chip. */
    private fun modelLabelOf(message: JSONObject): String? {
        val source = message.optJSONObject("source") ?: return null
        val provider = source.optString("provider")
        val model = source.optString("model")
        if (provider.isBlank() || model.isBlank()) return null
        return "$provider/$model"
    }

    /**
     * The text of a `user/message` event, used to reconcile a local echo.
     *
     * A `user/message` carries its content at `data.content` rather than under
     * `data.message`, unlike every other message-bearing event.
     */
    fun userTextOf(event: JSONObject): String? {
        if (event.optString("type") != "user/message") return null
        val data = event.optJSONObject("data") ?: return null
        return textOf(data.optJSONArray("content"))
    }

    // ---------------------------------------------------------------- blocks

    /** Convert a `content` array into renderable blocks. */
    fun blocksOf(content: JSONArray?): List<Block> {
        if (content == null) return emptyList()
        val out = mutableListOf<Block>()
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            when (b.optString("type")) {
                "text" -> b.optString("text").takeIf { it.isNotEmpty() }
                    ?.let { out += Block.Text(it) }
                "reasoning" -> b.optString("text").takeIf { it.isNotEmpty() }
                    ?.let { out += Block.Reasoning(it) }
                "tool-call" -> out += Block.ToolCall(
                    callId = b.optString("id"),
                    name = b.optString("name").ifBlank { "tool" },
                    input = b.optString("arguments"),
                )
                "image" -> {
                    val name = b.optJSONObject("attachment")?.optString("name").orEmpty()
                    out += Block.Text(if (name.isNotBlank()) "[图片：$name]" else "[图片]")
                }
                "file" -> {
                    val name = b.optJSONObject("attachment")?.optString("name").orEmpty()
                    out += Block.Text(if (name.isNotBlank()) "[文件：$name]" else "[文件]")
                }
                else -> Unit
            }
        }
        return out
    }

    private fun textOf(content: JSONArray?): String {
        if (content == null) return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            when (b.optString("type")) {
                "text" -> sb.append(b.optString("text"))
                "image" -> sb.append("[图片]")
                "file" -> sb.append("[文件]")
                else -> Unit
            }
        }
        return sb.toString().trim()
    }
}

/**
 * Accumulates `assistant-stream` chunk frames into one provisional message.
 *
 * The Harness sends `start` / `chunk` / `end` frames on the same mux socket as
 * the durable log. Chunks are indexed, so blocks are built by index:
 * `text-delta` and `reasoning-delta` append, `block-end` replaces the whole
 * block, and `tool-call-delta` appends to a JSON argument string.
 *
 * Frames carry an `attemptId`; a frame belonging to a newer attempt resets the
 * accumulator, because a retry re-streams the same indices and would otherwise
 * append the second attempt's text to the first's.
 */
class LiveAssistant {

    private var turn = -1
    private var step = -1
    private var attemptId = ""
    private val blocks = sortedMapOf<Int, MutableBlock>()
    private var usage: TokenUsage? = null

    private class MutableBlock(
        var kind: String = "text",
        var text: String = "",
        var callId: String = "",
        var name: String = "",
        var args: String = "",
    )

    val isActive: Boolean get() = blocks.isNotEmpty()

    /** Usage reported by the newest `usage` chunk of the live attempt. */
    val lastUsage: TokenUsage? get() = usage

    /** @return true when this frame changed the draft. */
    fun apply(frame: JSONObject): Boolean {
        // A frame from a different attempt (a retry) starts a clean slate.
        val frameAttempt = frame.optString("attemptId")
        if (frameAttempt.isNotBlank() && frameAttempt != attemptId) {
            attemptId = frameAttempt
            blocks.clear()
            usage = null
        }

        when (frame.optString("type")) {
            "start" -> {
                turn = frame.optInt("turn", -1)
                step = frame.optInt("step", -1)
                blocks.clear()
                // A new attempt starts from zero. Without this, a retry that has
                // not yet reported its own `usage` would keep displaying the
                // previous attempt's figure on top of the running total.
                usage = null
                return false
            }
            "chunk" -> {
                val index = frame.optInt("index", 0)
                val chunk = frame.optJSONObject("chunk") ?: return false
                val b = blocks.getOrPut(index) { MutableBlock() }
                when (chunk.optString("type")) {
                    "block-start" -> b.kind = chunk.optString("blockType").ifBlank { "text" }
                    "text-delta" -> {
                        b.kind = "text"
                        b.text += chunk.optString("text")
                    }
                    "reasoning-delta" -> {
                        b.kind = "reasoning"
                        b.text += chunk.optString("text")
                    }
                    "tool-call-delta" -> {
                        b.kind = "tool-call"
                        chunk.optString("id").takeIf { it.isNotBlank() }?.let { b.callId = it }
                        chunk.optString("name").takeIf { it.isNotBlank() }?.let { b.name = it }
                        b.args += chunk.optString("argumentsDelta")
                    }
                    "block-end" -> {
                        val block = chunk.optJSONObject("block") ?: return true
                        b.kind = block.optString("type").ifBlank { b.kind }
                        b.text = block.optString("text").ifBlank { b.text }
                    }
                    "usage" -> {
                        // Provider accounting for the live attempt. It is not
                        // renderable content, but it is the earliest signal the
                        // UI can show, so it is kept and surfaced.
                        TokenUsage.fromUsage(chunk.optJSONObject("usage"))?.let { usage = it }
                        return true
                    }
                    "finish" -> return true
                    else -> return false
                }
                return true
            }
            "end" -> {
                // The durable assistant/message supersedes this draft.
                blocks.clear()
                return true
            }
        }
        return false
    }

    /** True when the draft belongs to the given durable turn/step. */
    fun matches(turnNo: Int, stepNo: Int): Boolean = turn == turnNo && step == stepNo

    fun clear() {
        blocks.clear()
        turn = -1
        step = -1
        attemptId = ""
        usage = null
    }

    /** Build the provisional message, or null when there is nothing to show. */
    fun toMessage(): Message? {
        if (blocks.isEmpty()) return null
        val out = mutableListOf<Block>()
        for ((_, b) in blocks) {
            when (b.kind) {
                "reasoning" -> if (b.text.isNotEmpty()) out += Block.Reasoning(b.text)
                "tool-call" -> out += Block.ToolCall(
                    callId = b.callId,
                    name = b.name.ifBlank { "tool" },
                    input = b.args,
                )
                else -> if (b.text.isNotEmpty()) out += Block.Text(b.text)
            }
        }
        if (out.isEmpty()) return null
        return Message(
            id = "live",
            role = Role.ASSISTANT,
            blocks = out,
            streaming = true,
            // The live draft carries its turn too, so it merges with the steps of
            // the same turn that have already settled. Without it the draft would
            // render as a second bubble beside the turn it belongs to.
            turn = turn.takeIf { it >= 0 },
        )
    }
}
