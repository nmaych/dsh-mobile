// Guards the 1.1.9 changes. Two corrections to what 1.1.8 shipped, and two new
// capabilities:
//
//   1. The copy and file buttons are gone from the app bar. Copy is the long
//      press and nothing else — 1.1.8 kept a whole-conversation export beside it,
//      which was the same mistake in a different size — and the files a tool call
//      named are now chips *on the step that named them* rather than a separate
//      list behind a button.
//   2. The transcript anomaly in the report: the Harness's own injected material
//      (the runtime-context snapshot, the skill catalog) is written as
//      `user/message` events, so it rendered as the user's own words. The source
//      kind is what tells them apart.
//   3. A permission-mode switch, so the sandbox and approval policy can be
//      changed from the phone instead of only on the desktop.
//   4. Approvals can be answered from the phone. `approval/request` is a
//      waterfall on the same forwarded-event stream as `ask_user_question`, but
//      its answer is a bare wire string rather than an object, and the seam fails
//      closed — so a request the user never sees is an operation that does not
//      happen.
//
// The source-kind classification and the preset parsing are pure functions, so
// they get differential tests against pinned inputs as well as static source
// assertions: a kind missing from the table, or a projection read from the wrong
// place, compiles perfectly and is invisible until a user's transcript shows
// boilerplate in their own voice.
//
// usage: node test/regression-1.1.9.test.mjs
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
const messageBubble = src('ui/components/MessageBubble.kt')
const approvalDialog = src('ui/components/ApprovalDialog.kt')
const permissionDialog = src('ui/components/PermissionPickerDialog.kt')
const sessionParser = src('data/SessionParser.kt')
const dshClient = src('data/DshClient.kt')
const approval = src('data/Approval.kt')
const presets = src('data/PermissionPresets.kt')
const toolFiles = src('data/ToolFiles.kt')
const transcript = src('data/Transcript.kt')
const mainActivity = src('MainActivity.kt')
const changelog = read('CHANGELOG.md')
const ci = read('.github/workflows/ci.yml')

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
 * A whole Kotlin `fun <name>(...)` declaration that has an *expression* body.
 *
 * Needed for `fun f(): Boolean =\n    a && b`, which has no `{...}` of its own:
 * [functionBody] would search forward and find the first brace of whatever
 * declaration follows, silently testing the wrong function. The declaration runs
 * from its own line to the next blank line, which covers the wrapped body.
 */
function functionLine(source, name) {
  const decl = new RegExp(`fun\\s+${name}\\s*\\(`).exec(source)
  assert.ok(decl, `no declaration found for fun ${name}`)
  const start = source.lastIndexOf('\n', decl.index) + 1
  const rest = source.slice(decl.index)
  const blank = rest.search(/\n[ \t]*\n/)
  const end = blank < 0 ? source.length : decl.index + blank
  const text = source.slice(start, end)
  assert.ok(text.includes('='), `fun ${name} has no expression body`)
  return text
}

/** The `setOf(...)`/`mapOf(...)` body of a Kotlin property initialiser. */
function propertyBody(source, name) {
  const decl = new RegExp(`(?:val|var)\\s+${name}\\b[^=]*=`).exec(source)
  assert.ok(decl, `no declaration found for ${name}`)
  let depth = 0
  let started = false
  for (let i = decl.index; i < source.length; i++) {
    const c = source[i]
    if (c === '(' || c === '{') {
      depth++
      started = true
    } else if (c === ')' || c === '}') {
      depth--
      if (started && depth === 0) return source.slice(decl.index, i + 1)
    }
  }
  throw new Error(`unbalanced delimiters in ${name}`)
}

// ==================== 1. the app bar carries no copy or file button

console.log('=== the app bar is down to what it needs ===')

check('the app bar no longer offers copy or the file browser', () => {
  // Both were 1.1.7's answer to "copy" and "view files", and 1.1.8 corrected
  // what they should *mean* without removing either button. They are now
  // redundant: copying is the long press, and a file is a chip on the step that
  // named it. Two ways to do one thing is one way too many, and the app bar is
  // the scarcest space on the screen.
  const actions = chatScreen.slice(chatScreen.indexOf('actions = {'))
  const block = actions.slice(0, actions.indexOf('colors = TopAppBarDefaults'))
  assert.doesNotMatch(
    stripLineComments(block),
    /onCopyTranscript/,
    'the whole-conversation copy button must be gone from the app bar',
  )
  assert.doesNotMatch(
    stripLineComments(block),
    /onOpenFiles|onOpenTranscriptFiles/,
    'the file buttons must be gone from the app bar: files are chips in the transcript',
  )
  // The actions that remain are the ones that act on the conversation itself.
  for (const action of ['onNewSession', 'onRefresh', 'onOpenSettings']) {
    assert.match(block, new RegExp(action), `${action} must remain in the app bar`)
  }
})

check('the whole-transcript copy is deleted, not merely unwired', () => {
  // Leaving `copyTranscript` in the view model would leave `Transcript.toMarkdown`
  // reachable from nowhere and `copying` in the state with no writer. The renderer
  // stays (the tests and the documentation of the format use it), the feature goes.
  assert.doesNotMatch(
    stripLineComments(viewModel),
    /fun copyTranscript/,
    'the whole-transcript copy must be removed, not just unbuttoned',
  )
  assert.doesNotMatch(
    stripLineComments(viewModel),
    /val copying:/,
    'the copying flag belonged to that button and must go with it',
  )
  assert.match(
    transcript,
    /fun toMarkdown\(/,
    'the shared Markdown renderer stays: it documents the one format a copy is written in',
  )
})

check('the transcript-files dialog is gone', () => {
  assert.ok(
    !exists('app-project/app/src/main/java/ai/deepseek/dshmobile/ui/components/TranscriptFilesDialog.kt'),
    'the separate file list must be deleted now that files render inline',
  )
  assert.doesNotMatch(
    stripLineComments(viewModel),
    /showTranscriptFiles|openTranscriptFiles|closeTranscriptFiles/,
    'its state and actions must go with it',
  )
  assert.doesNotMatch(
    stripLineComments(appShell),
    /TranscriptFilesDialog/,
    'the shell must not still compose it',
  )
})

check('a file named by a tool call renders as a chip on that step', () => {
  const block = functionBody(messageBubble, 'ToolCallBlock')
  assert.match(
    block,
    /ToolFiles\.of\(block\.name,\s*block\.input\)/,
    'the chips must come from the call\'s own arguments, read per tool',
  )
  assert.match(block, /FileChip\(/, 'each file must render as a chip')
  // The chips must be outside the collapse. They are the answer to "what did it
  // just make", and hiding them behind a second tap puts the friction back that
  // moving them out of the dialog removed.
  const chipAt = block.indexOf('FileChip(')
  const collapseAt = block.indexOf('AnimatedVisibility')
  assert.ok(
    chipAt >= 0 && collapseAt >= 0 && chipAt < collapseAt,
    'the file chips must render before the collapsed detail, not inside it',
  )
})

check('a chip opens the file through the existing read path', () => {
  // No second reader: `workspaceFiles/read` already handles absolute and relative
  // paths and already enforces the workspace boundary.
  assert.match(chatScreen, /onOpenFile/, 'the transcript must accept a file opener')
  assert.match(
    appShell,
    /onOpenFile = onOpenWorkspaceFile/,
    'the chip must open through the same call the browser uses',
  )
  assert.match(
    mainActivity,
    /onOpenWorkspaceFile = \{ name ->/,
    'the activity must keep the path-joining wrapper for the browser rows',
  )
})

check('opening a file from a chip shows the dialog with no listing behind it', () => {
  // `filesOpen` used to be "the browser has a listing or an error", which was
  // sufficient while the only way in was the browser. A chip opens a file
  // directly, so under that definition the dialog never appeared and the tap
  // looked dead.
  const stateBlock = viewModel.slice(0, viewModel.indexOf('class ChatViewModel'))
  assert.match(
    stateBlock,
    /val filesOpen: Boolean get\(\) = [^\n]*openFile != null/,
    'filesOpen must count an open file on its own, or a chip tap does nothing',
  )
})

check('the chip shortens the path and the full path stays reachable', () => {
  // The chip is one line inside a card that already names the tool and its
  // target, and it is not the only way to reach the file — so the tail is right
  // here, and the opposite choice from the full-path list it replaces.
  const body = functionLine(toolFiles, 'chipName')
  assert.match(body, /substringAfterLast\('\/'\)/, 'the chip must show the file name')
  assert.match(
    body,
    /replace\('\\\\',\s*'\/'\)/,
    'both separators must be handled: the desktop writes backslashes',
  )
  assert.match(
    messageBubble,
    /contentDescription = ref\.path/,
    'the full path must still be exposed, so the chip is not ambiguous to a screen reader',
  )
})

// ============ 2. injected context is not the user's own words

console.log('\n=== injected context stops impersonating the user ===')

check('the transcript has a role that is not a speaker', () => {
  assert.match(
    sessionParser,
    /enum class Role \{[^}]*CONTEXT[^}]*\}/,
    'the parser must distinguish injected context from a real user message',
  )
  // Every `when (role)` over the enum must handle it, or the build fails — this
  // pins the two that are easy to miss because they are not exhaustive switches.
  assert.match(
    transcript,
    /Role\.CONTEXT ->/,
    'the Markdown export must not attribute injected context to the user',
  )
  assert.match(
    messageBubble,
    /Role\.CONTEXT -> ContextBubble/,
    'the bubble dispatcher must route it to its own chrome',
  )
})

check('the source kind, not the role, decides', () => {
  // A `user/message` is exactly how the Harness delivers injected material — that
  // is the role the model reads it in — so the role alone cannot tell it from a
  // real prompt. The `source.kind` can, and it is the field the desktop's own
  // renderer uses for the same decision.
  const body = functionBody(sessionParser, 'isInjectedContext')
  assert.match(body, /optString\("kind"\)/, 'the decision must be read from source.kind')
  const kinds = propertyBody(sessionParser, 'CONTEXT_SOURCE_KINDS')
  for (const kind of ['runtime-context', 'skill-catalog', 'skill-invocation']) {
    assert.match(kinds, new RegExp(`"${kind}"`), `"${kind}" must be classified as injected`)
  }
})

check('an unknown source kind is treated as a real user message', () => {
  // The safe direction. Showing a user's own prompt under the context chrome
  // would hide what they said, which is worse than the reverse — and it is what
  // keeps a future plugin's material from silently swallowing a prompt.
  const body = stripLineComments(functionBody(sessionParser, 'isInjectedContext'))
  assert.match(
    body,
    /kind in CONTEXT_SOURCE_KINDS/,
    'the classification must be an allowlist of known-injected kinds',
  )
  assert.match(
    body,
    /return false/,
    'anything unrecognised must fall through to "a real user message"',
  )
})

check('the fold routes injected context by that decision', () => {
  const body = functionBody(sessionParser, 'fold')
  const arm = body.slice(body.indexOf('"user/message" ->'))
  // The arm runs to its own closing brace; scanning a generous window past its
  // opening is enough and does not depend on comment length.
  const window = arm.slice(0, 2000)
  assert.match(
    window,
    /isInjectedContext\(data\.optJSONObject\("source"\)\)/,
    'the user/message arm must consult the source',
  )
  assert.match(
    window,
    /if \(injected\) Role\.CONTEXT else Role\.USER/,
    'the role must follow the decision, or the fix does not reach the screen',
  )
})

check('injected context cannot retire a pending local echo', () => {
  // `reconcileEchoes` retires one echo per committed `user/message`. An injected
  // snapshot counted as a committed prompt would swallow the optimistic render of
  // something the user actually sent, and the message would vanish until its own
  // event landed.
  const body = functionBody(sessionParser, 'userTextOf')
  assert.match(
    body,
    /isInjectedContext\(data\.optJSONObject\("source"\)\)[\s\S]{0,60}return null/,
    'userTextOf must answer null for injected context, so it consumes no echo',
  )
})

check('the classification matches the pinned table', () => {
  // Differential test: a JS replica of `isInjectedContext` against pinned inputs.
  // A kind missing from the allowlist compiles perfectly and is invisible until a
  // user's own transcript shows boilerplate in their voice.
  const KNOWN = new Set([
    'runtime-context',
    'skill-catalog',
    'skill-invocation',
    'agent-instructions',
    'session-reference',
    'goal',
    'plan-mode',
    'time-context',
    'tmux-context',
    'repeat-tool-reminder',
    'model-selection',
    'coordinator',
    'team-message',
    'agent-message',
    'subagent-report',
    'subagent-settled',
    'webhook',
    'compact-checkpoint',
    'compact-basic',
    'ptc-mode',
    'tool-registry',
    'tool-cordis',
    'tool-goal',
    'tool-jobs',
    'hooks-codex',
    'hooks-claude-code',
    'schedule',
  ])
  const isInjected = (source) => {
    const kind = source?.kind
    if (typeof kind !== 'string' || !kind.length) return false
    if (KNOWN.has(kind)) return true
    return kind.startsWith('plugin:')
  }

  const cases = [
    // The two in the report.
    [{ kind: 'runtime-context' }, true],
    [{ kind: 'skill-catalog' }, true],
    // A real prompt: `source.kind` is `user` for anything a human typed.
    [{ kind: 'user' }, false],
    // The `user-rpc` source keeps the `user` kind, so a message sent from a
    // client is still the user's.
    [{ kind: 'user', rpcId: 'r1' }, false],
    // An unrecognised plugin's material is injected by construction.
    [{ kind: 'plugin:acme' }, true],
    // Anything else unrecognised is treated as a real message.
    [{ kind: 'something-new' }, false],
    // No source at all, or a non-string kind: never hide a prompt on the strength
    // of a field that was not there.
    [undefined, false],
    [{}, false],
    [{ kind: '' }, false],
    [{ kind: 42 }, false],
  ]
  for (const [input, expected] of cases) {
    assert.equal(
      isInjected(input),
      expected,
      `${JSON.stringify(input)} must classify as injected=${expected}`,
    )
  }

  // And the Kotlin table must be exactly this set. A kind present in one and not
  // the other is a classification that differs between the app and this test —
  // which is the whole failure mode the differential test exists to catch, and it
  // would otherwise be invisible because both would still "pass" separately.
  const kotlinKinds = new Set(
    [...propertyBody(sessionParser, 'CONTEXT_SOURCE_KINDS').matchAll(/"([a-z0-9-]+)"/g)]
      .map((m) => m[1]),
  )
  assert.deepEqual(
    [...kotlinKinds].sort(),
    [...KNOWN].sort(),
    'the Kotlin allowlist and the pinned table must list the same kinds',
  )
})

check('injected context is collapsed and labelled, not hidden', () => {
  // Hiding it would make the transcript disagree with the log, and the material
  // is genuinely part of what the model was told — a user asking "why did it
  // think it could not write there?" needs to see the policy it was given.
  const body = functionBody(messageBubble, 'ContextBubble')
  assert.match(
    body,
    /var expanded by remember \{ mutableStateOf\(false\) \}/,
    'it must start collapsed: the point is that it stops dominating the screen',
  )
  assert.match(body, /contextLabel\(text\)/, 'it must be labelled for what it is')
  assert.match(
    body,
    /AnimatedVisibility\(visible = expanded\)/,
    'the body must be behind the expand, not removed',
  )
  assert.match(
    stripLineComments(body),
    /longPressCopy\(/,
    'copying the policy the model saw is a legitimate thing to want',
  )
})

check('the context label is read from the block, and has a fallback', () => {
  // Recognising by content rather than by source kind is deliberate: the content
  // is what the user is looking at, so the label cannot disagree with the body.
  const body = functionBody(messageBubble, 'contextLabel')
  assert.match(body, /available_skills/, 'the skill catalog must be named')
  assert.match(
    body,
    /Current runtime context/,
    'the runtime-context snapshot must be named',
  )
  assert.match(body, /else -> "上下文"/, 'an unrecognised block still needs a label')
})

// ============ 3. the permission mode can be changed from the phone

console.log('\n=== the permission mode is switchable ===')

check('the catalog is read from permissionPresets/catalog with no arguments', () => {
  // The descriptor declares zero parameters and the gateway rejects any extra
  // key, so passing one fails every time.
  const body = functionBody(dshClient, 'permissionCatalog')
  assert.match(body, /"permissionPresets", "catalog"/, 'the catalog call must exist')
  assert.match(
    body,
    /rpc\(origin, "permissionPresets", "catalog"\)/,
    'permissionPresets/catalog declares no parameters; it must be called with none',
  )
})

check('the switch is the plugin\'s own command, not an invented RPC', () => {
  // `permissionPresets` exposes only a read. The write is the plugin's
  // `/permission <preset>` slash command — the same path the desktop's picker
  // takes — so a guessed `permissionPresets/select` would fail against a
  // descriptor that does not exist.
  const body = functionBody(dshClient, 'selectPermissionPreset')
  assert.match(body, /"commands", "execute"/, 'the switch must go through commands/execute')
  assert.doesNotMatch(
    stripLineComments(body),
    /"permissionPresets",\s*"select"/,
    'there is no permissionPresets/select method to call',
  )
  assert.match(
    functionLine(presets, 'switchLine'),
    /"\/permission \$preset"/,
    'the command line must be /permission <preset>',
  )
})

check('commands/execute is addressed and its required arguments are all sent', () => {
  // `commands/execute` is agent-scoped: the session id is the *lookup* wire field
  // `agentId`, not a value in a request object. `submittedAttachments` is not
  // marked omissible, so an absent key is a gateway/arguments-invalid; it is sent
  // as an empty array, which is what the desktop sends for a plain invocation.
  const body = functionBody(dshClient, 'selectPermissionPreset')
  assert.match(body, /put\("agentId", sessionId\)/, 'the session must go in agentId')
  assert.match(body, /put\("line",/, 'the command line is the `line` argument')
  assert.match(
    body,
    /put\("submittedAttachments", JSONArray\(\)\)/,
    'submittedAttachments is required by the descriptor and must be sent empty',
  )
})

check('a refused switch is reported rather than swallowed', () => {
  // A switch that silently does nothing is indistinguishable from a broken
  // button, and the failure modes are real: the host may not offer the command,
  // and the plugin may refuse the preset.
  const body = functionBody(dshClient, 'selectPermissionPreset')
  assert.match(
    body,
    /commandId/,
    'a result with no commandId means the line resolved to no command at all',
  )
  assert.match(body, /"error"/, 'a command that settled as an error must throw')
  assert.match(body, /DshException/, 'both failures must be reported as errors')
})

check('the current value is read from the permissions projection', () => {
  // The catalog is deployment-wide; the current value is per-session and rides
  // the `permissions` projection. Reading the wrong one is a silent blank chip.
  const body = functionLine(presets, 'presetOf')
  assert.match(body, /"currentValue"/, 'the projection view names the value currentValue')
  const current = functionBody(presets, 'currentOf')
  assert.match(current, /presetOf\(/, 'the two paths must share one reader')
  assert.match(
    current,
    /"permissions"/,
    'the projection is keyed `permissions` under values',
  )
})

check('the one-shot read and the live stream both carry the preset', () => {
  // Two sources again: the opening `session/projections` read and the live
  // `session/control` frames. A key missing from either is a chip that goes stale.
  assert.match(
    functionBody(dshClient, 'sessionUsage'),
    /permissionPreset = PermissionPresets\.currentOf\(value\)/,
    'the one-shot read must carry the preset through the shared reader',
  )
  const baseline = viewModel.slice(viewModel.indexOf('"baseline" ->'))
  assert.match(
    baseline.slice(0, 1600),
    /values\.optJSONObject\("permissions"\)/,
    'the baseline must fold the permissions projection too',
  )
  assert.match(
    viewModel,
    // 1.2.0 added `agentPreset` to the same set, for the same reason the other
    // three are there: it is per-session state the host commits with its own event,
    // so the chip has to follow the projection rather than the tap. The property
    // this check guards — that the keys are named *once* rather than repeated at
    // each call site — is unchanged, so it is asserted on the set as it now stands:
    // every key is inside the one `setOf(...)` and none is spelled out elsewhere.
    /val WATCHED_PROJECTIONS = setOf\([\s\S]*?\)/,
    'the watched keys must be named once, or the three call sites drift',
  )
  const watched = /val WATCHED_PROJECTIONS = setOf\(([\s\S]*?)\)/.exec(viewModel)[1]
  for (const key of ['tokenUsage', 'contextPressure', 'permissions']) {
    assert.match(
      watched,
      new RegExp(`"${key}"`),
      `${key} must still be a watched projection`,
    )
  }
})

check('the chip is offered only when it can change something', () => {
  // A control that cannot act is one that lies. It needs both a mode reported and
  // alternatives to choose from — a host without the plugin reports neither.
  const stateBlock = viewModel.slice(0, viewModel.indexOf('class ChatViewModel'))
  assert.match(
    stateBlock,
    /val permissionSwitchable: Boolean[\s\S]{0,120}permissionPresets\.isNotEmpty\(\)/,
    'the chip must require a non-empty catalog',
  )
  assert.match(
    chatScreen,
    /if \(state\.permissionSwitchable\)/,
    'the strip must gate the chip on it',
  )
})

check('selecting does not write the new value optimistically', () => {
  // The authoritative value is the projection. Writing it locally first would
  // leave the chip asserting a mode the session is not in whenever the switch was
  // refused — and the whole point of the switch is that it is about safety.
  const body = functionBody(viewModel, 'selectPermissionPreset')
  assert.doesNotMatch(
    stripLineComments(body),
    /activePermissionPreset = preset/,
    'the selection must come back from the projection, not from the tap',
  )
  assert.match(
    body,
    /refreshUsage\(sessionId\)/,
    'the projection must be re-read after the command settles',
  )
})

check('the picker lists what the host offers, not a hardcoded pair', () => {
  // A deployment configures its own table and `auto` exists only when the
  // reviewing answerer is mounted, so a hardcoded list would either miss presets
  // or offer one that does not exist.
  const body = functionBody(presets, 'catalogOf')
  assert.match(body, /"options"/, 'the selectable set is `options`')
  assert.doesNotMatch(
    stripLineComments(body),
    /"workspace-write"|"danger-full-access"/,
    'the shipped preset names must not be hardcoded as the whole set',
  )
  // The descriptions are security-relevant and are rendered verbatim.
  assert.match(
    permissionDialog,
    /preset\.description/,
    'each preset must show the host\'s own description',
  )
})

// ============ 4. approvals can be answered from the phone

console.log('\n=== approvals are answerable from the phone ===')

check('the approval waterfall is recognised and parsed', () => {
  const body = functionBody(approval, 'fromFrame')
  assert.match(body, /EVENT_NAME/, 'the event name must be matched, not guessed')
  assert.match(
    propertyBody(approval, 'EVENT_NAME'),
    /"approval\/request"/,
    'the forwarded event is `approval/request`',
  )
  assert.match(body, /"waterfall"/, 'it arrives as a waterfall frame')
  for (const key of ['eventId', 'agentId', 'toolName', 'callId', 'reason', 'displayReason']) {
    assert.match(body, new RegExp(key), `the parser must read ${key}`)
  }
})

check('the outcome is a bare wire string, not an object', () => {
  // This is the one place an approval differs from a question: the host's
  // listener returns `'allowed-once' | 'rejected'` directly, and the gateway
  // forwards whatever lossless JSON value the listener produced.
  const enumAt = approval.indexOf('enum class Decision')
  assert.ok(enumAt >= 0, 'the decision vocabulary must be an enum')
  const decisions = approval.slice(enumAt, approval.indexOf('\n    }', enumAt))
  assert.match(decisions, /ALLOW_ONCE\("allowed-once"\)/, 'allow-once is the allow outcome')
  assert.match(decisions, /REJECT\("rejected"\)/, 'reject is the refuse outcome')
  assert.doesNotMatch(
    decisions,
    /cancelled|unavailable/,
    'only real decisions belong on a button: cancelled and unavailable are not choices',
  )
  // The transport must accept it, which means it cannot be typed to JSONObject.
  // The parameter sits in the signature, above the body [functionBody] returns,
  // so the declaration is scanned from its own line.
  assert.match(
    functionLine(dshClient, 'answerForwardedEvent'),
    /value: Any\?/,
    'the answer parameter must accept a bare JSON scalar, not only an object',
  )
})

check('both waterfalls share the one forwarded-event subscription', () => {
  // One `$events` stream carries every forwarded event; opening a second would
  // split the pending waterfalls between two streams and the host back-fills to
  // only one of them.
  assert.match(
    viewModel,
    /UserQuestion\.EVENT_NAME ->/,
    'the question arm must still exist',
  )
  assert.match(
    viewModel,
    /ApprovalRequest\.EVENT_NAME ->/,
    'the approval arm must be added to the same collector',
  )
  // The re-arming loop is what makes the stream survive a phone that sleeps,
  // changes network, and loses Wi-Fi. `while (isActive)` wraps the subscribe, so
  // it sits *before* the call rather than after it.
  const events = functionBody(viewModel, 'startEvents')
  assert.match(
    events,
    /while \(isActive\)/,
    'the re-arming loop must stay: a dropped stream is re-opened, not abandoned',
  )
  assert.match(
    events,
    /eventsClientId = ""[\s\S]{0,200}dsh\.forwardedEvents\(origin\)/,
    'a re-opened stream must start from no identity, or an answer quotes a dead id',
  )
})

check('a cancel clears whichever kind it names', () => {
  // The two kinds share the cancel frame shape and have separate id spaces, so
  // both queues must be filtered — a cancel for an approval that only searched
  // the question queue would leave the prompt on screen after the host settled it.
  const cancel = viewModel.slice(viewModel.indexOf('"cancel" ->'))
  const block = cancel.slice(0, 1600)
  assert.match(block, /pendingQuestions = questions\.filterNot/, 'questions must be filtered')
  assert.match(block, /pendingApprovals = approvals\.filterNot/, 'approvals must be filtered')
})

check('a prompt that could not be delivered goes back on screen', () => {
  // The seam fails closed, so an answer that vanished would look to the user like
  // the assistant simply refused to act.
  const body = functionBody(viewModel, 'answerApproval')
  assert.match(
    body,
    /listOf\(approval\) \+ queue/,
    'a failed delivery must restore the prompt at the front',
  )
  assert.match(
    body,
    /decision\.wire/,
    'the decision must be sent as its wire string',
  )
})

check('declining is not rejecting', () => {
  // The host's waterfall is deliberately left running when the user dismisses, so
  // the desktop's own answerer can still decide. Sending `rejected` instead would
  // be the phone overruling a decision it was never asked to make.
  const body = stripLineComments(functionBody(viewModel, 'answerApproval'))
  const dismissAt = body.indexOf('if (dismiss || decision == null) return')
  assert.ok(dismissAt >= 0, 'dismiss must return before anything is sent')
  const sendAt = body.indexOf('answerForwardedEvent')
  assert.ok(
    dismissAt < sendAt,
    'the dismiss return must precede the send, or dismissing would send an outcome',
  )
  assert.match(
    approvalDialog,
    /onDismiss/,
    'the dialog must offer a dismiss that is separate from the two decisions',
  )
  assert.match(
    approvalDialog,
    /稍后/,
    'the dismiss must be labelled as "later", not as a refusal',
  )
})

check('the approval dialog is composed above the settings early return', () => {
  // A request the user cannot see is an operation that does not happen, and a
  // user who wandered into settings is exactly the user who would never find out
  // why the conversation stopped.
  const dialogAt = appShell.indexOf('ApprovalDialog(')
  const settingsAt = appShell.indexOf('if (showSettings)')
  assert.ok(dialogAt >= 0, 'the shell must compose the approval dialog')
  assert.ok(
    dialogAt < settingsAt,
    'it must be composed before the settings early return, like the question dialog',
  )
})

check('an approval does not survive a session switch', () => {
  // Its `eventId` names a waterfall on the session being left; answering it from
  // here would answer the wrong turn.
  const activate = functionBody(viewModel, 'activateSession')
  assert.match(activate, /pendingApprovals = emptyList\(\)/, 'a switch must clear the queue')
  const detach = functionBody(viewModel, 'detachSession')
  assert.match(detach, /pendingApprovals = emptyList\(\)/, 'so must a detach')
})

// ================================= 5. the release is coherent

console.log('\n=== 1.1.9 is the released version ===')

check('the CHANGELOG has a 1.1.9 section naming all four changes', () => {
  const section = /^## \[1\.1\.9\][\s\S]*?(?=^## \[|\Z)/m.exec(changelog)
  assert.ok(section, 'CHANGELOG.md must have a "## [1.1.9]" section')
  assert.ok(
    section[0].length > 400,
    'the 1.1.9 section is too short to be the release notes users will see',
  )
  assert.match(section[0], /长按/, 'the notes must say copy is the long press')
  assert.match(section[0], /文件/, 'the notes must name the inline file chips')
  assert.match(
    section[0],
    /上下文|user\/message|注入/,
    'the notes must name the transcript anomaly and its cause',
  )
  assert.match(section[0], /权限/, 'the notes must name the permission mode')
  assert.match(section[0], /授权/, 'the notes must name the approval capability')
})

check('the CHANGELOG link block defines 1.1.9', () => {
  assert.match(
    changelog,
    /^\[1\.1\.9\]:\s+https:\/\/github\.com\/nmaych\/dsh-mobile\/compare\/v1\.1\.8\.\.\.v1\.1\.9$/m,
    'the link reference for 1.1.9 must exist, or the heading renders as literal text',
  )
})

check('the Gradle default version matches the newest CHANGELOG entry', () => {
  // Asserted as an invariant rather than as the literal "1.1.9": the literal is
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
    /node test\/regression-1\.1\.9\.test\.mjs/,
    'ci.yml must run test/regression-1.1.9.test.mjs',
  )
})

check('the protocol notes record the new wire facts', () => {
  // The three facts that are invisible to the compiler and fail only on a user's
  // phone: the approval outcome is a bare string rather than an object, the
  // permission switch is a command rather than an RPC, and injected context is
  // told apart by the source kind rather than by the role.
  const protocol = read('docs/PROTOCOL.md')
  assert.match(protocol, /approval\/request/, 'the approval waterfall must be documented')
  assert.match(
    protocol,
    /裸字符串/,
    'the notes must record that an approval answers with a bare wire string',
  )
  assert.match(
    protocol,
    /permissionPresets/,
    'the permission catalog call must be documented',
  )
  assert.match(
    protocol,
    /submittedAttachments/,
    'the notes must record that commands/execute requires submittedAttachments',
  )
  assert.match(
    protocol,
    /runtime-context/,
    'the notes must record which source kinds are injected',
  )
})

check('the new sources are real files', () => {
  for (const rel of [
    'app-project/app/src/main/java/ai/deepseek/dshmobile/data/Approval.kt',
    'app-project/app/src/main/java/ai/deepseek/dshmobile/data/PermissionPresets.kt',
    'app-project/app/src/main/java/ai/deepseek/dshmobile/ui/components/ApprovalDialog.kt',
    'app-project/app/src/main/java/ai/deepseek/dshmobile/ui/components/PermissionPickerDialog.kt',
  ]) {
    assert.ok(exists(rel), `${rel} is missing`)
  }
})

check('1.1.8\'s own suite still describes the code it guards', () => {
  // 1.1.9 changes what 1.1.8 shipped — the app-bar copy and the separate file
  // list are both gone — so the older suite must have been updated rather than
  // left asserting a shape that no longer exists.
  //
  // The check is on the *positive* assertion, not on the name: 1.1.8's suite now
  // names both features in order to assert their absence, and a check that
  // flagged any mention would fail on the corrected suite.
  const previous = read('test/regression-1.1.8.test.mjs')
  assert.doesNotMatch(
    previous,
    /assert\.match\(\s*chatScreen,\s*\/onCopyTranscript\//,
    'the 1.1.8 suite must not still require the app-bar copy button it lost',
  )
  assert.doesNotMatch(
    previous,
    /src\('ui\/components\/TranscriptFilesDialog\.kt'\)/,
    'the 1.1.8 suite must not still read the dialog that was deleted — that ' +
      'throws at load time, taking every check in the file with it',
  )
  assert.match(
    previous,
    /later removed by 1\.1\.9|removed in 1\.1\.9/,
    'the corrected suite must record that the removal was deliberate',
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
