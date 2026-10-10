package ai.deepseek.dshmobile.data

import org.json.JSONObject

/**
 * The files one tool call refers to, in the order it named them.
 *
 * "查看 AI 提供的文件" is a request to open what the assistant *produced or
 * pointed at* without hunting for it in the workspace tree. The path is already
 * sitting in the call's arguments — the transcript just never made it actionable.
 *
 * Which argument holds the path is per-tool, exactly as in [ToolLabel]: a single
 * `file_path` for the file tools, and an array of `{path, description}` for
 * `present`, which is the tool whose entire purpose is to hand the user files.
 * Reading "the first string argument" instead would pick up a shell command or a
 * search pattern and offer to open something that is not a file at all.
 *
 * Paths come back **as the model wrote them** — usually absolute on the desktop,
 * sometimes relative to the session's working directory. They are not resolved
 * here: resolution is the host's job, and `workspaceFiles/read` accepts both
 * forms. A relative path therefore works without this app having to guess the
 * session's `cwd`.
 */
object ToolFiles {

    /** One file a tool call named, with the description when the tool carried one. */
    data class Ref(val path: String, val description: String)

    /**
     * The files [name] refers to, or an empty list.
     *
     * Never throws and never returns a non-path: arguments this build does not
     * understand degrade to an empty list, so the caller simply offers nothing
     * rather than offering to open `{"description": "…"}`.
     */
    fun of(name: String, arguments: String): List<Ref> {
        val args = argumentsOf(arguments)
        return when (name) {
            // The deliverable tool. Its whole contract is a list of files the user
            // is meant to open, so it is the one call where the list is the point.
            "present" -> arrayRefs(args)

            // Single-file tools. `read_image` is included: an image is not text,
            // so the text viewer will refuse it — but the refusal comes from the
            // server as a readable message, which is better than silently hiding
            // the fact that a file was involved.
            "read", "read_image", "write", "edit" -> single(args, "file_path")

            else -> emptyList()
        }
    }

    /**
     * Every file referenced by the whole transcript, newest first.
     *
     * Newest first because the reason to open this list is almost always "the
     * thing it just made". De-duplicated by path, keeping the newest mention's
     * description, so a file written and then edited appears once.
     */
    fun ofTranscript(messages: List<Message>): List<Ref> {
        val seen = LinkedHashMap<String, Ref>()
        for (message in messages) {
            for (block in message.blocks) {
                if (block !is Block.ToolCall) continue
                for (ref in of(block.name, block.input)) {
                    // Re-inserting moves the key to the end, which after the
                    // reversal below makes it the newest mention.
                    seen.remove(ref.path)
                    seen[ref.path] = ref
                }
            }
        }
        return seen.values.toList().asReversed()
    }

    /** The argument object, tolerating the nested shape [ToolLabel] documents. */
    private fun argumentsOf(arguments: String): JSONObject {
        val root = runCatching { JSONObject(arguments) }.getOrNull() ?: return JSONObject()
        val nested = root.optJSONObject("arguments")
        if (nested != null && root.length() <= 2 && (root.has("name") || root.length() == 1)) {
            return nested
        }
        return root
    }

    private fun single(args: JSONObject, key: String): List<Ref> {
        val path = args.optString(key).trim()
        return if (path.isEmpty()) emptyList() else listOf(Ref(path, ""))
    }

    private fun arrayRefs(args: JSONObject): List<Ref> {
        val array = args.optJSONArray("files") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val path = o.optString("path").trim()
            if (path.isEmpty()) null else Ref(path, o.optString("description").trim())
        }
    }
}
