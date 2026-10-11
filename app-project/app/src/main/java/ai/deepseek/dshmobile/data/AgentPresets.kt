package ai.deepseek.dshmobile.data

import org.json.JSONObject

/**
 * The deployment's Agent presets — the "模式" a conversation runs in — and which
 * one a session is using.
 *
 * ## What a mode is, and how it differs from a permission preset
 *
 * A **permission preset** ([PermissionPresets]) is a policy: how much of the
 * filesystem a command may touch and whether gated operations ask a human. An
 * **Agent preset** is a composition: which tools the agent is handed and how it is
 * told to work. The desktop's own help text draws the same line — "模式是一份
 * Agent 预设，用来选择任务可用的工具，并约定 Agent 的工作方式… 审查要求用于指导
 * Agent，实际可执行的操作仍由权限设置决定".
 *
 * The four the Harness ships with are `standard` (标准模式), `ptc` (PTC 模式),
 * `minimal` (极简模式) and `cordis` (创造模式). A deployment may declare more.
 *
 * ## Why this app carries copy for the shipped four
 *
 * `agentPresets/list` publishes `name`/`description` **only for presets a
 * declaration names itself**. A shipped preset publishes neither — the desktop
 * resolves them through its own locale dictionaries (`BUILT_IN_PRESET_KEYS` in
 * `@deepseek-ai/dsh-agent-preset-registry/display`, keyed by the same four ids).
 * A phone that showed the raw id would render "standard" where the desktop says
 * 「标准模式」, so the shipped four are named here, and anything the host *does*
 * name is rendered verbatim in preference.
 *
 * ## Two sources, deliberately
 *
 * The **roster** is deployment-wide and read from `agentPresets/list`. The
 * **current value** is per-session and rides the `agentPreset` projection. Same
 * split, and the same reason, as [PermissionPresets]: a roster re-fetched per
 * session is wasted work, and a selection cached across sessions is a lie.
 *
 * ## The projection's view is a bare string
 *
 * Worth stating because it is the one place this differs from every other
 * projection the app reads. `agentPresetProjectionDefinition` declares
 * `wire: { viewSchema: z.union([z.string(), z.null()]), view: (state) => state }`,
 * so `values.agentPreset` **is** the id — not an object with a field in it, the
 * way `permissions` wraps its value in `{currentValue}`. Reading it with
 * `optJSONObject` would return null for every session and the chip would never
 * appear.
 */
object AgentPresets {

    /** One selectable mode. */
    data class Preset(
        val id: String,
        val name: String,
        val description: String,
        /** True for the preset the host marks as the default for new sessions. */
        val isDefault: Boolean = false,
        /** The host's diagnostic when this declaration failed to activate. */
        val broken: String? = null,
    ) {
        /** What to put on the chip and in the list. */
        val label: String get() = name.ifBlank { id }

        /** False when the host could not activate it, so it cannot be chosen. */
        val usable: Boolean get() = broken == null
    }

    /**
     * The deployment's preset roster.
     *
     * @param presets every preset the host knows about, in the host's order.
     * @param defaultId the one the host marks `isDefault`, or blank.
     */
    data class Roster(
        val presets: List<Preset>,
        val defaultId: String,
    ) {
        val isEmpty: Boolean get() = presets.isEmpty()
    }

    /** The id of the mode a session starts in when the host does not say. */
    const val FALLBACK_ID = "standard"

    /**
     * Display copy for the shipped presets, keyed by the ids the registry uses.
     *
     * Transcribed from the desktop's Simplified Chinese dictionary
     * (`presetStandardName` … `presetCordisDescription`). Kept as the *fallback*
     * rather than the source: a preset the host names wins, so a deployment that
     * renames or adds one is never contradicted.
     */
    private val BUILT_IN = mapOf(
        "standard" to (
            "标准模式" to
                "处理代码、文件和资料，适合大多数任务。Agent 会按需使用检索、编辑和终端等工具。"
            ),
        "ptc" to (
            "PTC 模式" to
                "包含标准模式的所有能力，更适合批量调用工具，并对结果进行筛选、整理、去重、统计或汇总的任务。"
            ),
        "minimal" to (
            "极简模式" to
                "Agent 仅使用终端工具完成任务，适合测试和对比其基础表现。"
            ),
        "cordis" to (
            "创造模式" to
                "用对话定制 DSH：让 Agent 编写插件，添加新功能或界面；也能组合工具和提示词，创建自己的模式。"
            ),
    )

    /** True for an id the desktop's own dictionary carries copy for. */
    fun isBuiltIn(id: String): Boolean = BUILT_IN.containsKey(id)

    /** The shipped name for [id], or null when it is not one of the four. */
    fun builtInName(id: String): String? = BUILT_IN[id]?.first

    /** The shipped description for [id], or null when it is not one of the four. */
    fun builtInDescription(id: String): String? = BUILT_IN[id]?.second

    /**
     * Resolve a roster row's display copy.
     *
     * A row that publishes its own `name` owns its copy and is rendered verbatim.
     * A row that publishes none but whose id the desktop's dictionary carries is a
     * shipped preset, and this app supplies the same Chinese the desktop shows.
     * Anything else falls back to its id, which is honest — a deployment's preset
     * with no copy has no better name than the one it declared.
     */
    fun displayName(id: String, published: String): String =
        published.ifBlank { builtInName(id) ?: id }

    /** [displayName]'s other half; blank when nothing is known about the preset. */
    fun displayDescription(id: String, published: String): String =
        published.ifBlank { builtInDescription(id).orEmpty() }

    /**
     * Read the `agentPresets/list` result.
     *
     * A row with no `id` is dropped rather than listed: the id is the identity that
     * gets sent back, and a row that cannot be selected is a promise the UI cannot
     * keep. A row the host reports as `broken` is *kept* — hiding it would make the
     * list disagree with the desktop, and the diagnostic is exactly what tells a
     * user why a mode they declared is missing.
     */
    fun rosterOf(value: JSONObject): Roster {
        val raw = value.optJSONArray("presets")
        val presets = buildList {
            if (raw != null) {
                for (i in 0 until raw.length()) {
                    val o = raw.optJSONObject(i) ?: continue
                    val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val publishedName = o.optString("name")
                    val publishedDescription = o.optString("description")
                    add(
                        Preset(
                            id = id,
                            name = displayName(id, publishedName),
                            description = displayDescription(id, publishedDescription),
                            isDefault = o.optBoolean("isDefault", false),
                            broken = o.optString("broken").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }
        return Roster(
            presets = presets,
            defaultId = presets.firstOrNull { it.isDefault }?.id.orEmpty(),
        )
    }

    /**
     * The active preset id out of an `agentPreset` projection **value**.
     *
     * The view is the id itself (see the object's KDoc), so this accepts a bare
     * string. A null or blank value means the host is not reporting the
     * projection — a composition without the preset registry mounted — which is
     * not the same as a session in some unnamed mode. The chip hides in that case
     * rather than claiming a mode.
     */
    fun presetOf(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.isNotBlank() }
        // Tolerated because a deployment *could* wrap it, and refusing to read a
        // shape the host might legitimately send would hide the chip for a reason
        // no user could act on.
        is JSONObject -> value.optString("currentValue").takeIf { it.isNotBlank() }
        else -> null
    }

    /**
     * The active preset out of a whole `session/projections` response.
     *
     * A thin wrapper over [presetOf] so the one-shot read and the live
     * `session/control` frame cannot disagree about where the value lives.
     */
    fun currentOf(projections: JSONObject?): String? {
        val values = projections?.optJSONObject("values") ?: return null
        return presetOf(values.opt("agentPreset"))
    }
}
