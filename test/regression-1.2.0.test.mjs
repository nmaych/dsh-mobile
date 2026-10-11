// Guards the 1.2.0 changes. Four fixes and two new capabilities:
//
//   1. The long press opens a dialog in which content can be *selected*, rather
//      than writing the whole message to the clipboard. 1.1.8 and 1.1.9 both made
//      it a whole-message copy, which is what the report says is wrong.
//   2. The app-bar refresh actually refreshes. It used to reload only the drawer's
//      session list — off-screen while the transcript is — so nothing visible
//      changed and there was no feedback at all.
//   3. `session/follow` is re-armed instead of being subscribed once. The collector
//      returned when the host ended the stream and nothing ever re-subscribed, so
//      the phone went permanently deaf while the desktop kept running.
//   4. Agent presets (模式): standard / ptc / minimal / cordis, chosen at
//      `session/create` and switched only while the session is still blank.
//   5. The files the assistant *delivered* are listed in one place.
//   6. `.md` files render as Markdown, with the source one tap away.
//
// The pure parsers get differential tests against pinned wire inputs as well as
// static source assertions: a projection read from the wrong place, or a mode id
// resolved from the wrong key, compiles perfectly and is invisible until a user
// sees a blank chip or a mode that is not the one they chose.
//
// usage: node test/regression-1.2.0.test.mjs
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const repo = path.join(here, '..')

const results = []
function check(name, fn) {
  try {
    fn()
    results.push({ name, ok: true })
    console.log(`PASS  ${name}`)
  } catch (error) {
    results.push({ name, ok: false, detail: error.message })
    console.log(`FAIL  ${name}\n      ${error.message}`)
  }
}

const read = (rel) => fs.readFileSync(path.join(repo, rel), 'utf8')
const exists = (rel) => fs.existsSync(path.join(repo, rel))
const src = (rel) =>
  read(`app-project/app/src/main/java/ai/deepseek/dshmobile/${rel}`)

const viewModel = src('ui/ChatViewModel.kt')
const appShell = src('ui/AppShell.kt')
const chatScreen = src('ui/ChatScreen.kt')
const mainActivity = src('MainActivity.kt')
const dshClient = src('data/DshClient.kt')
const agentPresets = src('data/AgentPresets.kt')
const toolFiles = src('data/ToolFiles.kt')
const workspaceFile = src('data/WorkspaceFile.kt')
const prefs = src('data/Prefs.kt')
const filesDialog = src('ui/components/FilesDialog.kt')
const copyDialog = src('ui/components/CopyMessageDialog.kt')
const agentPicker = src('ui/components/AgentPresetPickerDialog.kt')
const deliverables = src('ui/components/DeliverablesDialog.kt')
const changelog = read('CHANGELOG.md')
const ci = read('.github/workflows/ci.yml')
const protocol = read('docs/PROTOCOL.md')

/** The balanced `{...}` body of a Kotlin `fun <name>(...)` declaration. */
function functionBody(source, name) {
  const decl = new RegExp(`fun\\s+${name}\\s*\\(`).exec(source)
  assert.ok(decl, `no declaration found for fun ${name}`)
  const braceStart = source.indexOf('{', decl.index)
  assert.ok(braceStart > 0, `fun ${name} has no body`)
  let depth = 0
  for (let i = braceStart; i < source.length; i++) {
    if (source[i] === '{') depth++
    else if (source[i] === '}') {
      depth--
      if (depth === 0) return source.slice(braceStart + 1, i)
    }
  }
  throw new Error(`unbalanced braces in fun ${name}`)
}

/** Drop `//` line comments so prose about a pattern cannot trip a check. */
function stripLineComments(text) {
  return text
    .split('\n')
    .map((line) => line.replace(/\/\/.*$/, ''))
    .join('\n')
}

/**
 * Extract the string literals of a Kotlin `val X = mapOf(...)` / `setOf(...)`.
 *
 * Used to test the tables themselves rather than the code around them: the mode
 * copy and the Markdown extension list are pure data, and a wrong key or a typo in
 * an extension is exactly the kind of thing that compiles.
 */
function literalKeys(source, name) {
  const decl = new RegExp(`(?:val|var)\\s+${name}\\b[^=]*=`).exec(source)
  assert.ok(decl, `no declaration found for ${name}`)
  const rest = source.slice(decl.index)
  const end = rest.search(/\n\s*\n/)
  const body = end < 0 ? rest : rest.slice(0, end)
  return [...body.matchAll(/"([^"]*)"/g)].map((m) => m[1])
}

/**
 * The `actions = { … }` block of the chat screen's app bar.
 *
 * Read to the end of the block rather than to a fixed character count: the block
 * carries long explanatory comments (which is deliberate), so any fixed window
 * silently stops covering the later buttons as the prose grows.
 */
function appBarActions() {
  const at = chatScreen.indexOf('actions = {')
  assert.ok(at >= 0, 'the chat screen must have an app-bar actions block')
  const end = chatScreen.indexOf('colors = TopAppBarDefaults', at)
  assert.ok(end > at, 'the actions block must end before the bar colours')
  return chatScreen.slice(at, end)
}

// ============================ 1. the copy dialog

console.log('=== the long press opens a selectable dialog ===')

check('the copy dialog exists and makes its body selectable', () => {
  assert.ok(
    exists('app-project/app/src/main/java/ai/deepseek/dshmobile/ui/components/CopyMessageDialog.kt'),
    'CopyMessageDialog.kt must exist: the report asked for a dialog, not a clipboard write',
  )
  const body = functionBody(copyDialog, 'CopyMessageDialog')
  assert.match(
    body,
    /SelectionContainer\s*\{/,
    'the body must be inside a SelectionContainer, or nothing can be selected in it',
  )
  assert.match(
    body,
    /copyTarget|onCopyAll/,
    'the dialog must offer a copy-all action as well as selection',
  )
})

check('the long press opens the dialog instead of writing the clipboard', () => {
  // The requirement the 1.1.8 test guarded — "the press must reach the real
  // clipboard" — still holds, but it now happens one step later, from the sheet.
  // What must NOT hold any more is writing it directly in the press handler: that
  // is the behaviour the report calls wrong.
  const list = chatScreen.slice(chatScreen.indexOf('items(rendered'))
  const bubble = list.slice(0, 1200)
  assert.match(
    bubble,
    /onCopyMessage\(msg\)\s*\{/,
    'the bubble must still be given a copy action',
  )
  assert.match(
    bubble,
    /copyTarget\s*=/,
    'and it must open the copy sheet',
  )
  assert.doesNotMatch(
    stripLineComments(bubble),
    /clipboard\.setText/,
    'the long press must not write the clipboard directly: it must open the dialog',
  )
  // The write moved to the sheet's own confirm button.
  const sheet = chatScreen.slice(chatScreen.indexOf('CopyMessageDialog('))
  assert.match(
    sheet.slice(0, 700),
    /clipboard\.setText\(AnnotatedString\(target\.text\)\)/,
    'the copy-all button must place the text on the real clipboard',
  )
})

check('the sheet cannot show one message\'s body with another\'s label', () => {
  // Two independent `mutableStateOf` slots would allow a second long press landing
  // between them to compose a body from one message and a subtitle from another.
  // One holder makes that unrepresentable.
  assert.match(
    chatScreen,
    /private data class CopyTarget\(/,
    'the pending copy must be a single holder, not two independent slots',
  )
  const holder = chatScreen.slice(chatScreen.indexOf('private data class CopyTarget('))
  assert.match(
    holder.slice(0, 200),
    /val text: String[\s\S]{0,60}?val subtitle: String/,
    'the holder must carry both the body and its label together',
  )
})

check('every copyable role gets a label the sheet can show', () => {
  // The sheet covers the transcript, so the user cannot see which bubble they
  // pressed. An unlabelled sheet makes "did I copy mine or its answer?"
  // unanswerable, and a missing branch would be a `when` that does not compile —
  // so this is really guarding that the mapping stays exhaustive and distinct.
  const labels = functionBody(chatScreen, 'copySubtitle')
  for (const role of ['USER', 'ASSISTANT', 'CONTEXT', 'SYSTEM']) {
    assert.match(labels, new RegExp(`Role\\.${role}`), `${role} must have a label`)
  }
})

// ============================ 2. the refresh button

console.log('\n=== the refresh button actually refreshes ===')

check('the app bar is wired to the manual refresh, not the quiet one', () => {
  // The bug: the button called the same action the automatic paths call, and that
  // action only replaced the drawer's list. The two jobs are now separate names,
  // which is what makes "the button must re-sync the transcript" assertable.
  const call = appShell.slice(appShell.indexOf('ChatScreen('))
  const args = call.slice(0, call.indexOf('\n        )'))
  assert.match(
    args,
    /onRefresh\s*=\s*onManualRefresh/,
    'the transcript screen must be given the manual refresh',
  )
  assert.match(
    mainActivity,
    /onManualRefresh\s*=\s*vm::manualRefresh/,
    'and the activity must connect it to the view model',
  )
})

check('the manual refresh re-arms the follow subscription', () => {
  // This is the half the button was missing, and the half the user watches: the
  // list it used to reload is inside a closed drawer.
  const body = functionBody(viewModel, 'manualRefresh')
  assert.match(
    body,
    /restartFollowing\(\)/,
    'a manual refresh must re-sync the open conversation, not just the list',
  )
})

check('re-arming cancels first, so exactly one collector folds a session', () => {
  // Two live `session/follow` collectors would each fold the same events into the
  // same transcript, and whichever lost the race would re-add everything the other
  // had already applied — a visibly duplicated transcript.
  const body = functionBody(viewModel, 'restartFollowing')
  assert.match(body, /followJob\?\.cancel\(\)/, 'the old subscription must be cancelled')
  assert.match(body, /followJob = null/, 'and cleared, or `isActive` would still be true')
  assert.match(
    body,
    /startFollowing\(sessionId,\s*address\)/,
    'and a fresh one started for the same address',
  )
})

check('a re-arm keeps the transcript and the dedupe state', () => {
  // Clearing these would rebuild the transcript from scratch — a flicker, and the
  // loss of the uncommitted local echoes. The replayed backlog is deduped by
  // `seenSeqs`, so keeping it is also what makes the re-arm a no-op for events
  // already folded.
  const body = stripLineComments(functionBody(viewModel, 'restartFollowing'))
  assert.doesNotMatch(
    body,
    /seenSeqs\.clear\(\)|callIndex\.clear\(\)|messages = emptyList\(\)/,
    'a re-arm must not reset per-session state: that is a session *switch*',
  )
})

check('the refresh reports itself, because a silent refresh looks dead', () => {
  assert.match(
    viewModel,
    /val refreshing: Boolean = false/,
    'the state must carry a refreshing flag so the button can show progress',
  )
  const body = functionBody(viewModel, 'manualRefresh')
  assert.match(body, /refreshing = true/, 'the flag must be raised')
  assert.match(body, /refreshing = false/, 'and lowered on both outcomes')
  assert.match(
    body,
    /info = "已刷新/,
    'a successful refresh must say so, or it is indistinguishable from a no-op',
  )
  // The failure branch must speak even though a live socket normally suppresses a
  // transport complaint: this error is the direct answer to a button press.
  assert.match(
    body,
    /error = "刷新失败/,
    'a failed refresh must be reported rather than swallowed by the socket check',
  )
  const bar = appBarActions()
  assert.match(
    bar,
    /state\.refreshing/,
    'the app bar must actually render the flag',
  )
  assert.match(
    bar,
    /CircularProgressIndicator/,
    'and show progress while it runs',
  )
})

check('the automatic paths stay quiet', () => {
  // A spinner and a result line would be noise on a drawer open, a reconnect or a
  // send. Only the button gets feedback, which is why the two are separate.
  const body = functionBody(viewModel, 'refreshSessions')
  assert.doesNotMatch(
    body,
    /refreshing = true|info = "已刷新/,
    'the automatic refresh must not flash a spinner or a toast',
  )
})

// ============================ 3. the follow stream re-arms

console.log('\n=== the session stream re-arms instead of going deaf ===')

check('the follow collector loops instead of subscribing once', () => {
  // The bug: `session/follow` completes *normally* when the host ends the stream,
  // so the collector returned, the job finished, and nothing ever subscribed
  // again. The desktop kept streaming; only the phone went deaf.
  const body = functionBody(viewModel, 'startFollowing')
  assert.match(
    body,
    /while\s*\(isActive\)/,
    'the subscription must be re-armed in a loop, or one `end` frame silences the phone forever',
  )
  assert.match(
    body,
    /delay\(FOLLOW_RETRY_DELAY_MS\)/,
    'and pause between attempts, or a host that ends immediately becomes a hot loop',
  )
  // The pause must be a real constant, not an inline literal, so the reasoning
  // lives somewhere it can be read.
  assert.match(
    viewModel,
    /const val FOLLOW_RETRY_DELAY_MS = \d+L/,
    'the re-arm delay must be a named constant',
  )
})

check('turn boundaries are read from the replay, not only from fresh events', () => {
  // A re-arm replays the whole backlog, and those events are already in `seenSeqs`.
  // Tracking `turn/start`/`turn/end` only when *fresh* would leave `openTurn` at a
  // stale value after a re-subscription that landed mid-turn, so a finished turn
  // would look like it was still running.
  const body = functionBody(viewModel, 'startFollowing')
  const eventsAt = body.indexOf('val events = SessionParser.eventsOf(item)')
  assert.ok(eventsAt >= 0, 'the durable-event branch must exist')
  const branch = body.slice(eventsAt)
  const freshAt = branch.indexOf('val fresh = events.filter')
  assert.ok(freshAt > 0, 'the fresh filter must exist')
  const tracking = branch.slice(0, freshAt)
  assert.match(
    tracking,
    /"turn\/start"/,
    'the turn boundary must be tracked before the fresh filter, i.e. over every event',
  )
  assert.match(
    tracking,
    /"turn\/end"/,
    'both ends of the boundary must be tracked over every event',
  )
})

check('a failure a retry cannot cure stops the loop', () => {
  // Re-arming must not become "retry forever". A server-side refusal — the session
  // is gone, the credential was rejected — would otherwise be re-asked every
  // `FOLLOW_RETRY_DELAY_MS` for the life of the process, re-raising the same banner
  // each time. Only a transport failure is self-healing.
  const body = functionBody(viewModel, 'startFollowing')
  assert.match(
    body,
    /permanent = !transport/,
    'a non-transport failure must be marked permanent',
  )
  assert.match(
    body,
    /if \(permanent\)[\s\S]{0,300}?return@launch/,
    'and the loop must exit on it rather than retrying',
  )
  assert.match(
    body,
    /val transport = isTransportError\(t\)/,
    'the classification must reuse the existing transport test',
  )
})

check('the re-arm reasoning records why a normal end is also a reason to retry', () => {
  // The trap this guards: "retry on failure" reads as sufficient, and the failure
  // path *was* already handled. What was missing is that a clean end is equally
  // terminal, and a future reader who trims the loop would reintroduce the bug.
  const body = functionBody(viewModel, 'startFollowing')
  assert.match(
    body,
    /正常|normally|clean/,
    'the comment must say that a *normal* end is also why the loop exists',
  )
  assert.match(
    body,
    /桌面端|desktop/,
    'and record the symptom: the desktop kept running while the phone went deaf',
  )
})

// ============================ 4. agent presets (模式)

console.log('\n=== the mode selector ===')

check('the four shipped modes are named, and only as a fallback', () => {
  // The host publishes no `name` for a shipped preset — the desktop resolves them
  // through its own locale dictionary — so a phone that showed the raw id would
  // render "standard" where the desktop says 标准模式. The copy is a *fallback*
  // though: a preset the host names must win, or a deployment that renames one is
  // contradicted.
  for (const id of ['standard', 'ptc', 'minimal', 'cordis']) {
    assert.match(
      agentPresets,
      new RegExp(`"${id}" to \\(`),
      `${id} must carry local display copy`,
    )
  }
  for (const name of ['标准模式', 'PTC 模式', '极简模式', '创造模式']) {
    assert.match(agentPresets, new RegExp(name), `the copy must include ${name}`)
  }
  // And the published name wins. `displayName` has an *expression* body, so the
  // whole declaration is read rather than `functionBody`, which would return only
  // the contents of the `ifBlank { … }` lambda and never see the condition.
  const displayDecl = agentPresets.slice(agentPresets.indexOf('fun displayName('))
  assert.match(
    displayDecl.slice(0, 160),
    /published\.ifBlank\s*\{/,
    'a name the host publishes must take precedence over the local fallback',
  )
})

check('the roster parses the host\'s rows, keeping broken ones', () => {
  const body = functionBody(agentPresets, 'rosterOf')
  assert.match(body, /optJSONArray\("presets"\)/, 'the rows live under `presets`')
  assert.match(body, /optString\("id"\)/, 'the id is the identity that gets sent back')
  assert.match(body, /isDefault/, 'the host\'s default must be read')
  assert.match(
    body,
    /broken/,
    'a broken declaration must be kept so the list agrees with the desktop',
  )
  // A row with no id is a row that cannot be selected.
  assert.match(
    body,
    /takeIf \{ it\.isNotBlank\(\) \} \?: continue/,
    'a row with no id must be dropped rather than listed',
  )
})

check('the projection is read as a bare string, not as an object', () => {
  // This is the one projection whose view is the value itself:
  // `wire: { viewSchema: z.union([z.string(), z.null()]), view: (state) => state }`.
  // Reading it with `optJSONObject` returns null for every session, so the chip
  // would never appear — a bug that compiles.
  const body = functionBody(agentPresets, 'presetOf')
  assert.match(body, /is String ->/, 'a bare string must be accepted')
  assert.match(
    body,
    /is JSONObject ->/,
    'a wrapped shape is tolerated as well, for a deployment that sends one',
  )
  const current = functionBody(agentPresets, 'currentOf')
  assert.match(
    current,
    /values\.opt\("agentPreset"\)/,
    'the value must be read with `opt`, which preserves its JSON type',
  )
  assert.doesNotMatch(
    stripLineComments(current),
    /optJSONObject\("agentPreset"\)/,
    'and never with `optJSONObject`, which would drop a string value',
  )
})

check('the select call puts the session in agentId, not in agentPreset', () => {
  // The descriptor declares `source: 'lookup', lookup: 'agent', wire: 'agentId'`
  // for the first parameter and the preset as the second. Swapping them is
  // `gateway/arguments-invalid`, not a wrong-but-working selection.
  const body = functionBody(dshClient, 'selectAgentPreset')
  assert.match(body, /\.put\("agentId", sessionId\)/, 'the session goes in `agentId`')
  assert.match(body, /\.put\("agentPreset", preset\)/, 'the preset goes in `agentPreset`')
  // The result is a bare string, so it must not be unwrapped as an object.
  assert.match(
    body,
    /rpcValue\(/,
    'the call must use `rpcValue`: `rpc` drops a scalar result silently',
  )
  assert.match(
    body,
    /as\? String/,
    'and the committed id must be read as a string',
  )
})

check('a locked session is explained rather than silently refused', () => {
  // The host refuses a preset change once the first turn has started, because the
  // preset decides the tool schemas and prompt sections the model has already been
  // shown. Its own message is English-only and names nothing actionable.
  assert.match(
    dshClient,
    /agent-preset\/locked/,
    'the refusal code must be recognised',
  )
  assert.match(
    dshClient,
    /已经开始了[\s\S]{0,80}?新建一个会话/,
    'and translated into something the user can act on',
  )
  // The picker says so up front rather than letting the user tap and be refused.
  assert.match(
    viewModel,
    /val agentPresetLocked: Boolean[\s\S]{0,200}?running \|\| messages\.isNotEmpty\(\)/,
    'the UI must know a session has started, so it can warn before the tap',
  )
  assert.match(
    agentPicker,
    /locked/,
    'and the picker must render that state',
  )
})

check('the mode is named at create time, which is the only way to choose it', () => {
  // A live session's preset is locked once its first turn starts, so a create
  // without one takes whatever the deployment's default happens to be — a choice
  // made by nobody.
  const create = functionBody(dshClient, 'createSession')
  assert.match(
    create,
    /req\.put\("agentPreset", agentPreset\)/,
    'session/create must carry the preset',
  )
  const locked = functionBody(viewModel, 'createSessionLocked')
  assert.match(
    locked,
    /agentPreset = _state\.value\.selectedAgentPreset\.takeIf \{ it\.isNotBlank\(\) \}/,
    'and the chosen mode must be passed through, blank left blank',
  )
  // Blank rather than defaulted here, so a host without the registry is not sent a
  // preset it has never heard of.
  assert.doesNotMatch(
    stripLineComments(locked),
    /agentPreset = AgentPresets\.FALLBACK_ID/,
    'the app must not invent a preset id for a host that lists none',
  )
})

check('the mode is remembered for the next session, not written optimistically', () => {
  // The authoritative value is the `agentPreset` projection. Writing it locally
  // first would leave the chip asserting a mode the session is not in whenever the
  // switch was refused.
  const body = functionBody(viewModel, 'selectAgentPreset')
  assert.doesNotMatch(
    stripLineComments(body),
    /activeAgentPreset = preset/,
    'the active value must come back from the projection, not from the tap',
  )
  assert.match(
    body,
    /refreshUsage\(sessionId\)/,
    'the projection must be re-read after the switch settles',
  )
  assert.match(
    body,
    /prefs\.agentPreset = /,
    'the choice must be remembered for the next session',
  )
  assert.match(
    prefs,
    /var agentPreset: String/,
    'the preference must exist',
  )
})

check('the chip is offered only when the host lists modes', () => {
  // A chip that cannot change anything is a control that lies. Unlike the
  // permission chip it must NOT also require a value on the active session: the
  // mode is chosen when a session is *created*, so it has to be usable before one
  // exists — which is exactly when the projection is still blank.
  assert.match(
    viewModel,
    /val agentPresetSwitchable: Boolean[\s\S]{0,120}?agentPresets\.isNotEmpty\(\)/,
    'the chip must require a non-empty roster',
  )
  assert.doesNotMatch(
    stripLineComments(functionBody(viewModel, 'openAgentPresetPicker')),
    /activeSessionId \?: return/,
    'opening the picker must work with no session open',
  )
  assert.match(
    chatScreen,
    /if \(state\.agentPresetSwitchable\)/,
    'the strip must gate the chip on it',
  )
})

check('the picker lists what the host offers, not a hardcoded four', () => {
  const body = functionBody(agentPicker, 'AgentPresetPickerDialog')
  assert.match(body, /for \(preset in presets\)/, 'the rows must come from the roster')
  assert.doesNotMatch(
    stripLineComments(body),
    /"standard"|"ptc"|"minimal"|"cordis"/,
    'the picker must not hardcode the shipped ids',
  )
  // The locked state changes what "selected" means, and conflating the two is the
  // one way this dialog can lie.
  assert.match(
    body,
    /val chosen = if \(current\.isNotBlank\(\)\) current else pending/,
    'with a session open the session\'s mode is selected; with none, the pending choice',
  )
})

check('the roster is loaded on every connect path', () => {
  // The mode chip's visibility is the roster's visibility, so a path that
  // connects without loading it leaves the user unable to pick a mode until the
  // app restarts — the same defect 1.1.6 fixed for the question stream.
  const calls = viewModel.match(/loadAgentPresets\(\)/g) ?? []
  assert.ok(
    calls.length >= 4,
    `every connect path must load the roster; found ${calls.length} calls`,
  )
  assert.match(
    mainActivity,
    /onOpenAgentPresetPicker\s*=\s*vm::openAgentPresetPicker/,
    'the activity must connect the picker',
  )
})

check('the mode is a watched projection, named once', () => {
  // The same rule the other three projections follow: the key is spelled once so
  // the baseline seeding, the live-frame filter and the one-shot watermark cannot
  // drift apart. A key missing from any one of those is a chip that stops updating.
  assert.match(
    viewModel,
    /val WATCHED_PROJECTIONS = setOf\([\s\S]*?"agentPreset"[\s\S]*?\)/,
    'agentPreset must be a watched projection',
  )
  assert.match(
    viewModel,
    /"agentPreset" -> applyProjection\(null, null, null, value \?: JSONObject\.NULL\)/,
    'the live frame branch must route it, and must pass a cleared value through',
  )
  assert.match(
    functionBody(dshClient, 'sessionUsage'),
    /agentPreset = AgentPresets\.currentOf\(value\)/,
    'the one-shot read must carry it too, or the chip goes stale after a reload',
  )
  // And the `values.opt(...)` read in the baseline must not coerce it to an object.
  const baseline = viewModel.slice(viewModel.indexOf('"baseline" ->'))
  assert.match(
    baseline.slice(0, 1800),
    /values\.opt\("agentPreset"\)/,
    'the baseline must read it with `opt`, preserving the string',
  )
})

// ============================ 5. delivered files

console.log('\n=== the files the assistant delivered ===')

check('only `present` and `write` count as delivered', () => {
  // A turn reads twenty files to answer one question. Calling all twenty
  // "delivered" would bury the one artifact the user asked for under the
  // assistant's own working notes.
  const body = functionBody(toolFiles, 'delivered')
  assert.match(body, /"present" ->/, 'the deliverable tool must count')
  assert.match(body, /"write" ->/, 'and a file the assistant created must count')
  assert.doesNotMatch(
    stripLineComments(body),
    /"read"|"edit"|"read_image"/,
    'reading and editing must not count: reading is the opposite of delivering, ' +
      'and an edit is work on the project rather than something handed over',
  )
})

check('a `present` outranks a `write` of the same path', () => {
  // A file that was written and then handed over is a deliverable, and it keeps
  // the description the hand-over carried. Without the precedence the winner would
  // be whichever happened to come last.
  const body = functionBody(toolFiles, 'delivered')
  assert.match(
    body,
    /val keepNew = presented \|\| !previousWasPresented/,
    'a present must always win, and otherwise the newer mention must',
  )
})

check('the list is derived from the transcript, not stored', () => {
  // The transcript is live. A snapshot taken when the sheet opened would freeze
  // the moment a turn produced another file — which is exactly when the user has
  // it open.
  assert.match(
    viewModel,
    /val deliverables: List<ToolFiles\.Ref> by lazy \{ ToolFiles\.delivered\(messages\) \}/,
    'the list must be a pure function of the transcript, not a stored copy',
  )
  // Derived *per state instance*, so it cannot outlive the messages it came from:
  // `messages` is a `val` and every update goes through `copy()`, which builds a
  // new instance with a fresh delegate. A field on the view model would not have
  // that property.
  assert.doesNotMatch(
    viewModel,
    /private var deliverables|val deliverables: List<ToolFiles\.Ref> = emptyList\(\)/,
    'the list must not be a stored field that can go stale',
  )
  assert.match(
    viewModel,
    /val showDeliverables: Boolean = false/,
    'only the visibility is state, not the list',
  )
  assert.match(
    appShell,
    /files = state\.deliverables/,
    'the shell must read the derived list',
  )
})

check('the deliverable rows show the full path, unlike the chips', () => {
  // The chip sits in a card that already names the tool and its target, is one
  // line wide, and is not the only way to reach the file — so it shows the tail.
  // This row *is* the only content on its line and exists to identify one file
  // among many, so two files named `index.ts` have to be told apart.
  assert.match(
    deliverables,
    /ref\.path/,
    'the row must show the full path',
  )
  assert.doesNotMatch(
    stripLineComments(deliverables),
    /ToolFiles\.chipName/,
    'and must not shorten it the way a chip does',
  )
  assert.match(
    deliverables,
    /ref\.description/,
    'the description the assistant attached to the hand-over must be shown',
  )
})

check('the deliverables entry is reachable and the sheet is composed', () => {
  const bar = appBarActions()
  assert.match(
    bar,
    /onOpenDeliverables/,
    'the app bar must offer the delivered files',
  )
  // Gated on a non-empty list, so the button never leads to an empty sheet.
  assert.match(
    bar,
    /if \(state\.deliverables\.isNotEmpty\(\)\)/,
    'the button must be gated on there being something to show',
  )
  assert.match(appShell, /DeliverablesDialog\(/, 'the shell must compose the sheet')
  assert.match(
    mainActivity,
    /onOpenDeliverables\s*=\s*vm::openDeliverables/,
    'and the activity must connect it',
  )
  // Opening a file from it must reuse the same viewer as a chip, or the two
  // render the same file differently.
  const sheet = appShell.slice(appShell.indexOf('DeliverablesDialog('))
  assert.match(
    sheet.slice(0, 400),
    /onOpen = onOpenWorkspaceFile/,
    'a file opened from the sheet must go through the same read as a chip',
  )
})

// ============================ 6. Markdown rendering

console.log('\n=== .md files render as Markdown ===')

check('the extension reader handles dotfiles and dotted directories', () => {
  // Read as data rather than executed, so the table is checked where it lives.
  const extensions = literalKeys(workspaceFile, 'MARKDOWN_EXTENSIONS')
  for (const ext of ['md', 'markdown']) {
    assert.ok(extensions.includes(ext), `${ext} must be a Markdown extension`)
  }
  const body = functionBody(workspaceFile, 'extensionOf')
  assert.match(
    body,
    /substringAfterLast\('\/'\)/,
    'only the final path segment may contribute an extension, or a directory ' +
      'named "notes.v2" would lend its own suffix',
  )
  assert.match(
    body,
    /if \(dot <= 0/,
    'a leading dot is a dotfile, not an extension: `.gitignore` has none',
  )
})

check('the viewer renders Markdown only for Markdown files', () => {
  const body = functionBody(filesDialog, 'FileViewer')
  assert.match(
    body,
    /WorkspaceFiles\.isMarkdown\(/,
    'the rendered view must be gated on the file actually being Markdown',
  )
  assert.match(
    body,
    /isMarkdown && !showSource/,
    'and the source view must win when the user asks for it',
  )
})

check('the source view is kept, so the old guarantee still holds', () => {
  // 1.1.7's rule — "the user asked to see the file, so the source is the honest
  // answer" — is not repealed; it is joined by a preview. The syntax stays one tap
  // away rather than becoming unreachable.
  const body = functionBody(filesDialog, 'FileViewer')
  assert.match(body, /showSource/, 'a source toggle must exist')
  assert.match(
    body,
    /page\.text/,
    'the raw text must still be rendered',
  )
  // The toggle is drawn only when there is a choice to make.
  assert.match(
    body,
    /if \(isMarkdown\)\s*\{/,
    'a switch with one position must not be drawn for a non-Markdown file',
  )
})

check('the preview uses the same renderer as the transcript', () => {
  // Two renderers would drift, and the drift is only visible when a user compares
  // a message against the file it quotes.
  const body = functionBody(filesDialog, 'FileViewer')
  assert.match(
    body,
    /Markdown\(page\.text/,
    'the preview must go through the shared Markdown composable',
  )
})

check('the source toggle does not carry over between files', () => {
  // Opening a second file after choosing "查看源码" on the first must not open in
  // source mode: the choice is about one file, not a global preference.
  assert.match(
    filesDialog,
    /var showSource by remember\(path\)/,
    'the toggle must be keyed to the file path',
  )
})

// ============================ 7. the release is coherent

console.log('\n=== 1.2.0 is the released version ===')

check('the CHANGELOG has a 1.2.0 section naming all six changes', () => {
  const section = /^## \[1\.2\.0\][\s\S]*?(?=^## \[|\Z)/m.exec(changelog)
  assert.ok(section, 'CHANGELOG.md must have a "## [1.2.0]" section')
  assert.ok(
    section[0].length > 800,
    'the 1.2.0 section is too short to be the release notes users will see',
  )
  // Each reported bug must be named, since that is what the affected user searched
  // for.
  assert.match(section[0], /长按/, 'the notes must name the long-press copy fix')
  assert.match(section[0], /刷新/, 'the notes must name the refresh fix')
  assert.match(
    section[0],
    /卡住/,
    'the notes must name the stuck-mobile fix',
  )
  assert.match(section[0], /模式/, 'the notes must name the mode selector')
  // And both new features.
  assert.match(section[0], /交付/, 'the notes must name the delivered-files feature')
  assert.match(section[0], /Markdown/, 'the notes must name the Markdown rendering')
})

check('the CHANGELOG link block defines 1.2.0', () => {
  assert.match(
    changelog,
    /^\[1\.2\.0\]:\s+https:\/\/github\.com\/nmaych\/dsh-mobile\/compare\/v1\.1\.9\.\.\.v1\.2\.0$/m,
    'the link reference for 1.2.0 must exist, or the heading renders as literal text',
  )
})

check('the Gradle default version matches the newest CHANGELOG entry', () => {
  // Asserted as an invariant rather than as the literal "1.2.0": the literal is
  // supposed to move every release.
  const gradle = read('app-project/app/build.gradle.kts')
  const name = /System\.getenv\("DSH_VERSION_NAME"\)\s*\?:\s*"([^"]+)"/.exec(gradle)
  const code = /System\.getenv\("DSH_VERSION_CODE"\)\s*\?:\s*"(\d+)"/.exec(gradle)
  assert.ok(name, 'the default version name must be a literal in build.gradle.kts')
  assert.ok(code, 'the default version code must be a literal in build.gradle.kts')
  const newest = /^## \[(\d+\.\d+\.\d+)\]/m.exec(changelog)
  assert.ok(newest, 'the CHANGELOG must open with a released version heading')
  assert.equal(
    name[1],
    newest[1],
    `build.gradle.kts defaults to ${name[1]} but the newest CHANGELOG entry is ${newest[1]}`,
  )
  const [major, minor, patch] = newest[1].split('.').map(Number)
  assert.equal(
    Number(code[1]),
    major * 10000 + minor * 100 + patch,
    `the default version code ${code[1]} must match ${newest[1]}`,
  )
})

check('the new suite runs in CI', () => {
  assert.match(
    ci,
    /node test\/regression-1\.2\.0\.test\.mjs/,
    'ci.yml must run test/regression-1.2.0.test.mjs',
  )
})

check('the new sources are real files', () => {
  for (const rel of [
    'app-project/app/src/main/java/ai/deepseek/dshmobile/data/AgentPresets.kt',
    'app-project/app/src/main/java/ai/deepseek/dshmobile/ui/components/AgentPresetPickerDialog.kt',
    'app-project/app/src/main/java/ai/deepseek/dshmobile/ui/components/CopyMessageDialog.kt',
    'app-project/app/src/main/java/ai/deepseek/dshmobile/ui/components/DeliverablesDialog.kt',
  ]) {
    assert.ok(exists(rel), `${rel} is missing`)
  }
})

check('PROTOCOL.md documents the agent-preset surface', () => {
  // The three shape traps are the ones a reader would otherwise get wrong, and
  // each of them fails as "nothing happened" rather than as an error.
  assert.match(protocol, /agentPresets\/list/, 'the roster method must be documented')
  assert.match(protocol, /agentPresets\/select/, 'the select method must be documented')
  assert.match(
    protocol,
    /agentId[\s\S]{0,200}?查找字段/,
    'the lookup-vs-value trap on `agentId` must be recorded',
  )
  assert.match(
    protocol,
    /裸字符串/,
    'the bare-string result and projection view must be recorded',
  )
  assert.match(
    protocol,
    /agent-preset\/locked/,
    'the locked rule must be recorded',
  )
})

check('1.1.7\'s and 1.1.8\'s suites describe the code they guard', () => {
  // 1.2.0 changes what those two shipped, so the older suites must have been
  // updated rather than left asserting a shape that no longer exists. The check is
  // on the *corrected* assertion, not on the name.
  const previous7 = read('test/regression-1.1.7.test.mjs')
  assert.doesNotMatch(
    previous7,
    /the file viewer must not render Markdown/,
    'the 1.1.7 suite must not still forbid the Markdown preview it gained',
  )
  assert.match(
    previous7,
    /showSource/,
    'the corrected 1.1.7 suite must require the source view it kept',
  )
  const previous8 = read('test/regression-1.1.8.test.mjs')
  assert.doesNotMatch(
    previous8,
    /'the handler must place the text on the real clipboard'/,
    'the 1.1.8 suite must not still require the clipboard write inside the press',
  )
  assert.match(
    previous8,
    /CopyMessageDialog/,
    'the corrected 1.1.8 suite must account for the dialog that replaced it',
  )
})

// ------------------------------------------------------------------- report

console.log()
const failed = results.filter((r) => !r.ok)
console.log(`${results.length - failed.length}/${results.length} passed`)
if (failed.length > 0) {
  console.log('failures:')
  for (const f of failed) console.log(`  - ${f.name}: ${f.detail}`)
}
process.exit(failed.length > 0 ? 1 : 0)
