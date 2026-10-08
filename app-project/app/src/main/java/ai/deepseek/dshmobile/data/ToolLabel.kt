package ai.deepseek.dshmobile.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns a raw tool invocation into the one line a user can read.
 *
 * The transcript used to print the tool's *name* — `pwsh`, `read`, `edit` — in
 * monospace beside a collapsed JSON blob. That is the tool's identifier, not what
 * it did: a user watching a turn could see that something happened but not that a
 * file was read, which file, or that a command ran. Every name below is a real one
 * taken from 5 041 `tool/call` events across 41 session logs, so the mapping is
 * grounded in what actually appears rather than in what the vocabulary might
 * contain.
 *
 * The target is chosen per tool rather than by scanning for "the first string
 * argument", because the most useful detail differs: a path for a file tool, the
 * command for a shell tool, the query for a search. A generic scan would print
 * `{"description": "List files"}` for a shell call and hide the command.
 */
object ToolLabel {

    /** The verb for a tool, in the app's language. */
    fun action(name: String): String = when (name) {
        "read" -> "读取"
        "read_image" -> "查看图片"
        "write" -> "写入"
        "edit" -> "编辑"
        "pwsh", "pash", "shell", "bash", "cmd" -> "运行命令"
        "grep" -> "搜索内容"
        "glob" -> "查找文件"
        "web_fetch" -> "抓取网页"
        "web_search" -> "网络搜索"
        "todo_write" -> "更新任务"
        "job_output" -> "读取后台输出"
        "job_list" -> "列出后台任务"
        "job_kill" -> "停止后台任务"
        "subagent", "subagent_fork" -> "派发子代理"
        "send_message" -> "发送给子代理"
        "list_agents" -> "列出子代理"
        "interrupt_agent" -> "停止子代理"
        "present" -> "交付文件"
        "skill" -> "加载技能"
        "ask_user_question" -> "询问用户"
        "create_goal" -> "创建目标"
        "get_goal" -> "读取目标"
        "update_goal" -> "更新目标"
        "load_workspace_dependencies" -> "读取运行环境"
        else -> name.ifBlank { "工具" }
    }

    /**
     * The detail worth showing next to [action].
     *
     * Never throws and never returns raw JSON: an argument object this build does
     * not understand degrades to an empty target, which renders as the bare verb.
     */
    fun target(name: String, arguments: String): String {
        val args = parse(arguments)
        return when (name) {
            "read", "read_image", "write", "edit" ->
                tailPath(string(args, "file_path"))

            "pwsh", "pash", "shell", "bash", "cmd" ->
                shorten(string(args, "command", "cmd"), 90)

            "grep" -> {
                val pattern = string(args, "pattern")
                val where = string(args, "include", "path")
                when {
                    pattern.isBlank() -> ""
                    where.isBlank() -> shorten(pattern, 60)
                    else -> "${shorten(pattern, 48)} · ${tailPath(where, 1)}"
                }
            }

            "glob" -> shorten(string(args, "pattern"), 60)

            "web_fetch" -> shorten(string(args, "url"), 80)

            "web_search" -> shorten(joinStrings(args, "queries"), 80)

            "skill" -> string(args, "name")

            "present" -> shorten(joinObjects(args, "files", "path"), 80)

            "ask_user_question" -> shorten(firstQuestion(args), 70)

            "job_output", "job_kill" -> string(args, "job_id")

            "job_list", "list_agents", "get_goal", "load_workspace_dependencies" -> ""

            "send_message", "interrupt_agent" -> string(args, "agent_id")

            "subagent", "subagent_fork" -> shorten(string(args, "description"), 60)

            "todo_write" -> countLabel(args.optJSONArray("todos"), "项任务")

            "create_goal" -> shorten(string(args, "objective"), 70)

            "update_goal" -> string(args, "action")

            else -> ""
        }
    }

    /**
     * `读取 dsh-android/app-project/.../SessionParser.kt` — the whole step.
     *
     * The target is appended only when there is one, so a tool with nothing to say
     * still renders as a verb rather than a verb plus a dangling separator.
     */
    fun describe(name: String, arguments: String): String {
        val detail = target(name, arguments)
        return if (detail.isBlank()) action(name) else "${action(name)} $detail"
    }

    // --------------------------------------------------------------- argument IO

    /**
     * The call's argument object, or an empty one.
     *
     * Two shapes occur in real logs. Usually the arguments are the object itself;
     * occasionally a model nests them one level deeper, producing
     * `{"arguments": {"url": "…"}, "name": "web_fetch"}`. Both are accepted,
     * because the nested form otherwise renders as a tool with no target at all.
     */
    private fun parse(arguments: String): JSONObject {
        val root = runCatching { JSONObject(arguments) }.getOrNull() ?: return JSONObject()
        val nested = root.optJSONObject("arguments")
        if (nested != null && root.length() <= 2 && (root.has("name") || root.length() == 1)) {
            return nested
        }
        return root
    }

    /** The first non-blank string among [keys]. */
    private fun string(o: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = o.optString(key)
            if (value.isNotBlank()) return value
        }
        return ""
    }

    /** Join a string array argument, e.g. the `queries` of a web search. */
    private fun joinStrings(o: JSONObject, key: String): String {
        val array = o.optJSONArray(key) ?: return ""
        val parts = (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
        return parts.joinToString(" / ")
    }

    /** Join one field of an array of objects, e.g. the `path` of each presented file. */
    private fun joinObjects(o: JSONObject, key: String, field: String): String {
        val array = o.optJSONArray(key) ?: return ""
        val parts = (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.optString(field)?.takeIf { it.isNotBlank() }?.let { tailPath(it) }
        }
        return parts.joinToString("、")
    }

    /** The first question of an `ask_user_question` call. */
    private fun firstQuestion(o: JSONObject): String {
        val array = o.optJSONArray("questions") ?: return ""
        val first = array.optJSONObject(0) ?: return ""
        return first.optString("header").takeIf { it.isNotBlank() }
            ?: first.optString("question")
    }

    /** `3 项任务` for a todo write, which has no single target. */
    private fun countLabel(array: JSONArray?, suffix: String): String {
        if (array == null) return ""
        val done = (0 until array.length()).count { array.optJSONObject(it)?.optString("status") == "completed" }
        return "${array.length()} $suffix（已完成 $done）"
    }

    // ----------------------------------------------------------------- formatting

    /**
     * The last [segments] of a path, so `E:\a\b\c\File.kt` reads as `c/File.kt`.
     *
     * The leading directories are the same for every file in a project and are the
     * part that makes a line too long to read; the tail is what identifies it.
     */
    private fun tailPath(path: String, segments: Int = 2): String {
        if (path.isBlank()) return ""
        val parts = path.replace('\\', '/').trimEnd('/').split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return path
        return parts.takeLast(segments).joinToString("/")
    }

    /** Collapse whitespace to one line and cut it to [max], with an ellipsis. */
    private fun shorten(text: String, max: Int): String {
        val flat = text.replace(WHITESPACE, " ").trim()
        if (flat.length <= max) return flat
        return flat.take(max - 1).trimEnd() + "…"
    }

    private val WHITESPACE = Regex("\\s+")
}
