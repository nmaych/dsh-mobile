package ai.deepseek.dshmobile.data

import org.json.JSONArray
import org.json.JSONObject

/** Who produced a transcript entry. */
enum class Role { USER, ASSISTANT, SYSTEM, TOOL }

/** One rendered block inside a message. */
sealed interface Block {
    data class Text(val text: String) : Block
    data class Reasoning(val text: String) : Block
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
) {
    val plainText: String
        get() = blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
}

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
    )

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

    // ------------------------------------------------------------------- fold

    /**
     * Fold durable events into a transcript.
     *
     * @param previous messages already rendered, so folding is incremental.
     */
    fun fold(events: List<JSONObject>, previous: List<Message> = emptyList()): Folded {
        val out = previous.toMutableList()
        var title: String? = null
        var lastSeq = 0L
        var openTurn: Int? = null

        // callId -> (messageIndex, blockIndex), so a tool result can be folded
        // into the card its call created.
        val callIndex = HashMap<String, Pair<Int, Int>>()

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
                    val blocks = blocksOf(message.optJSONArray("content"))
                    if (blocks.isEmpty()) continue
                    val id = message.optString("id").ifBlank { "a-$seq" }
                    // Replace an earlier render of the same message id.
                    val existing = out.indexOfFirst { it.id == id }
                    val entry = Message(id = id, role = Role.ASSISTANT, blocks = blocks, time = time)
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
                        val last = out.lastOrNull()
                        if (last != null && last.role == Role.ASSISTANT) {
                            val blocks = last.blocks + Block.ToolCall(callId, name, args)
                            out[out.lastIndex] = last.copy(blocks = blocks)
                            indexCalls(out.lastIndex)
                        } else {
                            out += Message(
                                id = "tc-$seq",
                                role = Role.TOOL,
                                blocks = listOf(Block.ToolCall(callId, name, args)),
                                time = time,
                            )
                            indexCalls(out.lastIndex)
                        }
                    }
                }

                "tool/result" -> {
                    if (!isAppend(surfaceOp)) continue
                    val message = data.optJSONObject("message")
                    val callId = message?.optString("toolCallId")
                        ?: message?.optJSONObject("source")?.optString("callId")
                        ?: ""
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
                            id = message?.optString("id")?.ifBlank { null } ?: "tr-$seq",
                            role = Role.TOOL,
                            blocks = listOf(Block.ToolCall(callId, "tool", "", output, failed)),
                            time = time,
                        )
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

        return Folded(out, title, openTurn != null, lastSeq, openTurn)
    }

    /** Only `"append"` surface operations are conversation. */
    private fun isAppend(surfaceOp: Any?): Boolean = surfaceOp is String && surfaceOp == "append"

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
 */
class LiveAssistant {

    private var turn = -1
    private var step = -1
    private val blocks = sortedMapOf<Int, MutableBlock>()

    private class MutableBlock(
        var kind: String = "text",
        var text: String = "",
        var callId: String = "",
        var name: String = "",
        var args: String = "",
    )

    val isActive: Boolean get() = blocks.isNotEmpty()

    /** @return true when this frame changed the draft. */
    fun apply(frame: JSONObject): Boolean {
        when (frame.optString("type")) {
            "start" -> {
                turn = frame.optInt("turn", -1)
                step = frame.optInt("step", -1)
                blocks.clear()
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
        return Message(id = "live", role = Role.ASSISTANT, blocks = out, streaming = true)
    }
}
