package ai.deepseek.dshmobile.ui

/**
 * The session list, grouped by workspace and collapsed when it grows.
 *
 * ## Why a tree rather than a flat list
 *
 * The drawer used to be one flat column sorted by recency, with the workspace
 * shown as a small second line. That works while there are five conversations and
 * stops working at fifty: the user's own project is scattered through the list,
 * interleaved with every other project, and the one thing they know about the
 * conversation they want is *which project it was in*. Grouping by workspace puts
 * that back as the primary axis.
 *
 * ## Why collapse
 *
 * Grouping alone makes the drawer taller, not shorter: every workspace now costs a
 * header, and a workspace with forty conversations still renders forty rows. So a
 * group that is long enough to be a wall is collapsed to its newest few, and the
 * rest are one tap away. The threshold is on the *group*, not on the whole list,
 * because that is the unit the user scans.
 *
 * ## The "no workspace" group is real
 *
 * A session created with a bare `cwd`, or one whose `cwd` the server did not
 * report, belongs to no workspace. It gets its own group rather than being
 * dropped: a conversation missing from the drawer is indistinguishable from one
 * that was deleted.
 */
object SessionTree {

    /**
     * How many rows a group shows before it collapses.
     *
     * Four is enough that the common case — a project with a handful of recent
     * conversations — never collapses at all, and small enough that the expanded
     * drawer stays scannable when several projects are long.
     */
    const val COLLAPSE_AFTER = 4

    /** One workspace's conversations, newest first. */
    data class Group(
        /** The workspace id, or empty for the group that belongs to no workspace. */
        val workspaceId: String,
        /** The workspace's display name, or a stand-in for the unnamed group. */
        val label: String,
        /** The workspace's directory, for the second line; may be blank. */
        val path: String,
        /** Every conversation in this group, newest first. */
        val sessions: List<SessionRow>,
        /** True when this group has a workspace behind it. */
        val isWorkspace: Boolean,
    ) {
        val collapsedCount: Int
            get() = (sessions.size - COLLAPSE_AFTER).coerceAtLeast(0)

        /** True when this group is long enough that showing all of it would be a wall. */
        val collapsible: Boolean get() = collapsedCount > 0
    }

    /**
     * Group [sessions] by workspace, newest group first.
     *
     * Ordering rules, each chosen for what the user is looking for:
     *
     *  - Groups are ordered by their **newest** conversation, so the project being
     *    worked on right now is at the top regardless of when its workspace was
     *    created.
     *  - Within a group, conversations stay newest first — the same order the flat
     *    list had, so nothing about finding a recent one changes.
     *  - The workspace-less group sorts last. It is the fallback, and putting it
     *    first would push every real project below a group the user does not think
     *    of as a place.
     *
     * The session's workspace is matched by `cwd` against the workspace list rather
     * than by id, because `session/list` reports a directory and not a workspace
     * id — the two are the same thing on the server, which resolves
     * `cwd = workspace.path` itself.
     */
    fun group(sessions: List<SessionRow>, workspaces: List<WorkspaceRow>): List<Group> {
        // `path -> workspace`, normalised so a trailing separator or a backslash
        // does not silently fail to match. The desktop is the one reporting both
        // sides, but the two can still disagree about separators.
        val byPath = workspaces.associateBy { normalise(it.path) }

        val buckets = LinkedHashMap<String, MutableList<SessionRow>>()
        val labels = mutableMapOf<String, Group>()

        for (session in sessions) {
            val cwd = session.cwd
            val workspace = cwd?.takeIf { it.isNotBlank() }?.let { byPath[normalise(it)] }
            val key = workspace?.id ?: ""
            buckets.getOrPut(key) { mutableListOf() } += session
            if (workspace != null && key !in labels) {
                labels[key] = Group(
                    workspaceId = workspace.id,
                    label = workspace.label,
                    path = workspace.path,
                    sessions = emptyList(),
                    isWorkspace = true,
                )
            }
        }

        val groups = buckets.map { (key, rows) ->
            val known = labels[key]
            Group(
                workspaceId = key,
                // A session whose `cwd` is not in the workspace list still names a
                // directory, and that directory is far more useful as a heading
                // than a generic label — it is what the user recognises the project
                // by.
                label = known?.label ?: unnamedLabel(rows),
                path = known?.path ?: rows.firstOrNull()?.cwd.orEmpty(),
                sessions = rows,
                // Marked as a real workspace only when one is behind it, so the UI
                // does not offer workspace-only actions for the fallback group.
                isWorkspace = known != null,
            )
        }

        // Newest group first, with the workspace-less group always last. Comparing
        // on the newest conversation's timestamp is what makes "the project I am
        // working in" float to the top.
        return groups.sortedWith(
            compareBy<Group> { !it.isWorkspace }
                .thenByDescending { it.sessions.firstOrNull()?.updatedAt ?: 0L },
        )
    }

    /**
     * A heading for a group with no workspace behind it.
     *
     * Uses the shared directory when every session in the group agrees on one, so
     * a group of conversations from an unregistered directory still reads as that
     * directory. Only when they disagree — or none reported a `cwd` — does it fall
     * back to a generic label.
     *
     * The comparison goes through [normalise], like the workspace lookup does.
     * Comparing the raw strings was a real bug: `E:/solo/alpha` and
     * `E:\solo\alpha` are the same directory, so a group of conversations from one
     * project would be titled "未归类" purely because two events spelled the
     * separator differently. The two sides of the *same* decision have to agree
     * about what "the same path" means.
     */
    private fun unnamedLabel(rows: List<SessionRow>): String {
        val paths = rows.mapNotNull { it.cwd?.takeIf { p -> p.isNotBlank() } }
            .distinctBy { normalise(it) }
        val only = paths.singleOrNull() ?: return "未归类"
        return only.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifBlank { only }
    }

    /**
     * A directory path in one canonical spelling.
     *
     * `E:\proj\` and `e:/proj` name the same directory, and the desktop and the
     * workspace list can each produce either form. Without this the two sides
     * simply fail to match and every session lands in the fallback group — a bug
     * that looks like "grouping is broken" rather than "a string differed".
     * Windows paths are compared case-insensitively because that filesystem is.
     */
    private fun normalise(path: String): String {
        val unified = path.trim().replace('\\', '/').trimEnd('/')
        return if (unixLike(unified)) unified else unified.lowercase()
    }

    /** True when the path is not a Windows drive or UNC path, so case matters. */
    private fun unixLike(path: String): Boolean =
        path.startsWith("/") && !path.startsWith("//")
}
