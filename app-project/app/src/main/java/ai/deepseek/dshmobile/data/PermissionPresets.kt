package ai.deepseek.dshmobile.data

import org.json.JSONObject

/**
 * The deployment's permission presets, and which one a session is using.
 *
 * ## What a preset is
 *
 * A preset bundles the two policy knobs the Harness enforces — the **sandbox
 * mode** (how much of the filesystem a command may touch) and the **approval
 * policy** (whether gated operations ask a human) — under one name. The shipped
 * table has `workspace-write` (write inside the workspace, ask before going
 * wider) and `danger-full-access` (full access, never ask); a deployment may
 * configure others, and `auto` is reserved for an experimental reviewing
 * answerer. The catalog is the deployment's own table, so this app never
 * hardcodes the two it ships with.
 *
 * ## Why the phone cares
 *
 * The mode is the difference between "the assistant can do the thing" and "the
 * assistant stops and waits for a human". Before this, that switch existed only
 * on the desktop, so a phone user watching a turn stall had no way to widen it —
 * the transcript said the operation was denied and the only remedy was a walk to
 * the computer. It is per-session state, so it is shown next to the model and
 * workspace chips rather than buried in settings.
 *
 * ## Two sources, deliberately
 *
 * The **catalog** is deployment-wide and read once, from
 * `permissionPresets/catalog`. The **current value** is per-session and rides the
 * `permissions` projection, so it is read from `session/projections` and kept
 * current by the same `session/control` stream that feeds the token chip. They
 * are separate calls because they have different lifetimes: a catalog that is
 * re-fetched per session would be wasted work, and a selection cached across
 * sessions would be a lie.
 */
object PermissionPresets {

    /** One selectable preset. */
    data class Preset(
        val value: String,
        val name: String,
        val description: String,
    ) {
        /** What to put on the chip and in the list. */
        val label: String get() = name.ifBlank { value }
    }

    /**
     * The deployment's preset table.
     *
     * @param options every preset that can be selected.
     * @param defaultPreset the preset a session starts in, per the deployment.
     */
    data class Catalog(
        val options: List<Preset>,
        val defaultPreset: String,
    ) {
        val isEmpty: Boolean get() = options.isEmpty()
    }

    /**
     * Read the `permissionPresets/catalog` result.
     *
     * `defaultOptions` is deliberately ignored: it describes the presets a *new*
     * session may be created with, which is a settings-screen concern this app
     * does not have. The selectable set is `options`, and `defaultPreset` is what
     * the chip shows before a session reports its own value.
     *
     * An option with no `value` is dropped rather than listed: `value` is the
     * identity that gets sent back, and a row that cannot be selected is a
     * promise the UI cannot keep.
     */
    fun catalogOf(value: JSONObject): Catalog {
        val options = value.optJSONArray("options")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("value").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Preset(
                    value = id,
                    name = o.optString("name"),
                    description = o.optString("description"),
                )
            }
        }.orEmpty()
        return Catalog(
            options = options,
            defaultPreset = value.optString("defaultPreset"),
        )
    }

    /**
     * The active preset out of a `permissions` projection **value**.
     *
     * The projection's view is `{currentValue}`. An empty or absent value means
     * the projection is not being reported — the host has no permission-presets
     * plugin mounted, or has cleared it — which is not the same as a session in
     * some unnamed mode. The chip hides in that case rather than claiming a mode.
     */
    fun presetOf(value: JSONObject?): String? =
        value?.optString("currentValue")?.takeIf { it.isNotBlank() }

    /**
     * The active preset out of a whole `session/projections` response.
     *
     * A thin wrapper over [presetOf] so the one-shot read and the live
     * `session/control` frame cannot disagree about where the value lives: both
     * end in the same reader, and only the path to it differs.
     */
    fun currentOf(projections: JSONObject?): String? {
        val values = projections?.optJSONObject("values") ?: return null
        return presetOf(values.optJSONObject("permissions"))
    }

    /**
     * The command line that switches a session's preset.
     *
     * The switch is not an RPC of its own. `permissionPresets` exposes only a
     * *read* (`catalog`); the write goes through the plugin's own `/permission`
     * slash command, which is the same path the desktop's picker uses. Sending
     * anything else — a guessed `permissionPresets/select`, say — would fail
     * against a descriptor that does not exist.
     *
     * The preset is passed through unquoted, because that is what the desktop
     * does and the command parses its argument by whitespace.
     */
    fun switchLine(preset: String): String = "/permission $preset"
}
