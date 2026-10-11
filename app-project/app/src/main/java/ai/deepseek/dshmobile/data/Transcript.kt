package ai.deepseek.dshmobile.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One transcript, rendered as Markdown for the clipboard.
 *
 * "复制对话" is a request to get a conversation *out* of the app — into a note, a
 * bug report, an email. The export is therefore written for a reader who has
 * never seen this app rather than for a re-import:
 *
 *  - Each entry starts with a heading naming who spoke, so the shape survives
 *    being pasted into anything that renders Markdown at all. A flat run of
 *    prose with no role markers is the one thing that makes a pasted transcript
 *    unreadable, because the user's question and the assistant's answer become
 *    the same voice.
 *  - Reasoning and tool calls are kept, because they are usually the point of
 *    copying at all ("why did it do that?"). They are fenced so they cannot be
 *    mistaken for the answer, and so a `#` inside a shell command cannot turn
 *    into a heading.
 *  - A timestamp is emitted only when the log carries one. Stamping "now" onto
 *    an old message would date the copy instead of the conversation.
 *
 * A message with no renderable block contributes nothing rather than an empty
 * heading: a transcript of bare `## 助手` lines reads as though the assistant
 * said nothing at all, which is worse than omitting the row.
 */
object Transcript {

    /** The whole conversation as one Markdown document. */
    fun toMarkdown(messages: List<Message>, title: String = ""): String {
        val heading = title.trim().ifBlank { "对话记录" }
        val out = StringBuilder()
        out.append("# ").append(heading).append('\n').append('\n')
        if (messages.isEmpty()) {
            out.append("_（暂无内容）_\n")
            return out.toString()
        }
        for (message in messages) {
            val body = messageMarkdown(message)
            if (body.isBlank()) continue
            out.append(body).append('\n').append('\n')
        }
        return out.toString().trimEnd() + "\n"
    }

    /**
     * One message as plain text — exactly the words the user sees, nothing else.
     *
     * 1.2.0 copied the *Markdown export* of a message, which is written for a
     * reader outside the app: role headings (`## 助手`), timestamps, quoted
     * reasoning and fenced tool payloads. Pasted into a normal chat box that
     * scaffolding is what the report called 乱码 — characters the user never saw
     * on the bubble, wrapped around the words they did. The plain text is the
     * body blocks only, in display order, separated the way the bubble renders
     * them; a tool call contributes its readable summary line rather than its
     * raw JSON, and a reasoning block is skipped because its collapsed box is
     * not part of the reply being read.
     */
    fun messagePlainText(message: Message): String {
        val out = StringBuilder()
        for (block in message.blocks) {
            val line = when (block) {
                is Block.Text -> block.text.trim().takeIf { it.isNotEmpty() }
                is Block.ToolCall -> ToolLabel.describe(block.name, block.input).takeIf { it.isNotEmpty() }
                // The reasoning box is collapsed by default, so its words are not
                // what the user is looking at; copying them would be another
                // surprise, which is the bug being fixed here.
                is Block.Reasoning -> null
                is Block.Notice -> block.text.trim().takeIf { it.isNotEmpty() }
            } ?: continue
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(line)
        }
        return out.toString()
    }

    /** One message as a Markdown fragment, without a trailing blank line. */
    fun messageMarkdown(message: Message): String {
        val blocks = message.blocks.mapNotNull { blockMarkdown(it) }
        if (blocks.isEmpty()) return ""
        val out = StringBuilder()
        out.append("## ").append(roleLabel(message.role)).append('\n')
        timestamp(message.time)?.let { out.append('\n').append('_').append(it).append('_').append('\n') }
        for (block in blocks) {
            out.append('\n').append(block)
        }
        return out.toString()
    }

    private fun roleLabel(role: Role): String = when (role) {
        Role.USER -> "你"
        Role.ASSISTANT -> "助手"
        // Injected context is not a speaker. Labelling it "系统" keeps it out of
        // the user's voice, which is the whole point: the export used to attribute
        // the runtime-context snapshot to "你" and so recorded the user as having
        // written boilerplate they never saw.
        Role.CONTEXT -> "上下文"
        Role.SYSTEM, Role.TOOL -> "系统"
    }

    private fun blockMarkdown(block: Block): String? = when (block) {
        is Block.Text -> block.text.trim().takeIf { it.isNotEmpty() }

        // Reasoning is quoted rather than fenced: it is prose, and a quote
        // survives being read in a client that does not highlight code.
        is Block.Reasoning -> block.text.trim()
            .takeIf { it.isNotEmpty() }
            ?.lineSequence()
            ?.joinToString("\n") { "> $it" }
            ?.let { if (block.parts > 1) "> _（思考过程 · ${block.parts} 段）_\n>\n$it" else it }

        is Block.ToolCall -> {
            val out = StringBuilder()
            out.append("**工具 · ").append(block.name).append("**")
            if (block.failed) out.append("（失败）")
            out.append('\n')
            val input = block.input.trim()
            if (input.isNotEmpty() && input != "{}") {
                out.append('\n').append(fence(input)).append('\n')
            }
            block.output?.trim()?.takeIf { it.isNotEmpty() }?.let {
                out.append('\n').append(fence(it)).append('\n')
            }
            out.toString().trimEnd()
        }

        is Block.Notice -> block.text.trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Wrap arbitrary text in a code fence that its own content cannot close.
     *
     * A fixed ``` fence is not enough: tool output regularly *contains* ``` (a
     * pasted file, a nested Markdown document), and the first such line would end
     * the fence early and dump the rest of the output into the surrounding prose.
     * The fence is therefore grown until it is longer than the longest run of
     * backticks inside the text, which is the rule CommonMark itself uses.
     */
    private fun fence(text: String): String {
        var longest = 0
        var run = 0
        for (ch in text) {
            if (ch == '`') {
                run++
                if (run > longest) longest = run
            } else {
                run = 0
            }
        }
        val marker = "`".repeat(maxOf(3, longest + 1))
        return "$marker\n$text\n$marker"
    }

    private fun timestamp(epochMillis: Long): String? {
        if (epochMillis <= 0L) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))
        }.getOrNull()
    }
}
