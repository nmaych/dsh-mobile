package ai.deepseek.dshmobile.data

import org.json.JSONObject

/**
 * One entry in a workspace directory listing.
 *
 * The wire sends `{name, type, size?}` and nothing else: `workspaceFiles/list`
 * deliberately strips the resolved absolute path of every child, so the client
 * cannot learn where a file lives on the desktop's disk by listing a directory.
 * The name is therefore joined onto the directory the caller already knows,
 * rather than the server handing back a path to display.
 */
data class WorkspaceFileEntry(
    val name: String,
    /** `file`, `directory` or `other`. */
    val type: String,
    val size: Long?,
) {
    val isDirectory: Boolean get() = type == "directory"
    val isFile: Boolean get() = type == "file"
}

/** One page of a directory's children, as `workspaceFiles/list` returns it. */
data class WorkspaceListing(
    /** The listed directory, relative to the workspace root; empty means the root. */
    val path: String,
    val entries: List<WorkspaceFileEntry>,
    /** True when the server capped the listing, so the UI can say it is partial. */
    val truncated: Boolean,
) {
    /**
     * Directories first, then names — the order a file browser is read in.
     *
     * The server returns its backend's own order, which is stable but not
     * *useful*: it interleaves files and directories, so finding a subdirectory
     * in a large listing means reading the whole list. Sorting here rather than
     * server-side keeps the wire contract untouched.
     */
    val sorted: List<WorkspaceFileEntry>
        get() = entries.sortedWith(
            compareByDescending<WorkspaceFileEntry> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
}

/**
 * One page of a text file, as `workspaceFiles/read` returns it.
 *
 * `offset` is 1-based and `eof` says whether this page reaches the last line, so
 * the UI can offer "load more" without guessing from the line count: a page that
 * happens to end exactly on the file's last line and one that was cut short by
 * the line cap are otherwise indistinguishable.
 */
data class WorkspaceFilePage(
    val absolutePath: String,
    val text: String,
    val offset: Int,
    val lines: Int,
    val eof: Boolean,
    val bytes: Long?,
)

/**
 * Reading a workspace file, over the `workspaceFiles` namespace.
 *
 * The first argument of every method in that namespace is a **lookup** named
 * `workspaceFileScopeId`, not an ordinary value: the host resolves it to a live
 * session and takes that session's `cwd` as the workspace root. It is therefore
 * the *session id*, and passing a workspace id here silently resolves nothing —
 * the gateway answers with a lookup failure rather than listing the wrong place,
 * which is the failure mode worth knowing about.
 *
 * `read`'s third argument is not optional on the wire even though every field
 * inside it is: the descriptor declares it as a required `json` parameter, and
 * the gateway refuses an args object with a missing key. So `range` is always
 * sent, possibly empty.
 */
object WorkspaceFiles {

    /** Parse a `workspaceFiles/list` value. */
    fun listingOf(value: JSONObject): WorkspaceListing {
        val raw = value.optJSONArray("entries")
        val entries = buildList {
            if (raw != null) {
                for (i in 0 until raw.length()) {
                    val o = raw.optJSONObject(i) ?: continue
                    val name = o.optString("name").takeIf { it.isNotBlank() } ?: continue
                    add(
                        WorkspaceFileEntry(
                            name = name,
                            type = o.optString("type").ifBlank { "other" },
                            // Absent for a directory, and a directory's "size" is
                            // meaningless anyway, so it stays null rather than 0.
                            size = if (o.has("size") && !o.isNull("size")) o.optLong("size") else null,
                        ),
                    )
                }
            }
        }
        return WorkspaceListing(
            path = value.optString("path"),
            entries = entries,
            truncated = value.optBoolean("truncated", false),
        )
    }

    /** Parse a `workspaceFiles/read` value. */
    fun pageOf(value: JSONObject): WorkspaceFilePage = WorkspaceFilePage(
        absolutePath = value.optString("absolutePath"),
        text = value.optString("text"),
        offset = value.optInt("offset", 1),
        lines = value.optInt("lines", 0),
        eof = value.optBoolean("eof", true),
        bytes = if (value.has("bytes") && !value.isNull("bytes")) value.optLong("bytes") else null,
    )

    /**
     * The parent of a workspace-relative directory, or null at the root.
     *
     * Kept here rather than in the UI because the root is the empty string and
     * the two obvious spellings of "go up from `src`" — `""` and `"."` — differ
     * on the wire: `""` is what `list` wants for the workspace root, while `"."`
     * resolves to the same place only by accident of the filesystem backend.
     */
    fun parentOf(path: String): String? {
        val trimmed = path.trim().replace('\\', '/').trim('/')
        if (trimmed.isEmpty()) return null
        val cut = trimmed.substringBeforeLast('/', "")
        return cut
    }

    /** Join a directory and a child name into a workspace-relative path. */
    fun childOf(directory: String, name: String): String {
        val base = directory.trim().replace('\\', '/').trim('/')
        return if (base.isEmpty()) name else "$base/$name"
    }
}
