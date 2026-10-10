// Guards the 1.1.8 changes. Two of them are corrections to features 1.1.7 shipped
// wrong, and two are new:
//
//   1. "复制" meant *long-press this message*, not "export the whole
//      conversation". 1.1.7 built the whole-transcript export and put it in the
//      app bar; the thing the user asked for was copying the bubble in front of
//      them.
//   2. "查看文件" meant *show me what the assistant used*, not "browse the
//      workspace". The browser answers "where is that file" and assumes the user
//      knows the path; the actual question was "what did it just make", asked
//      precisely because the path is unknown. The path was already in the tool
//      call and simply was not actionable.
//   3. Per-turn duration, which must only be shown when the log can *prove* one.
//   4. A drawer grouped by workspace, with long groups collapsed.
//
// The grouping and duration logic are pure functions, so they get differential
// tests against pinned inputs as well as static source assertions — an off-by-one
// in a collapse threshold or a rounding rule compiles perfectly and is invisible
// until a user notices the wrong number.
//
// usage: node test/regression-1.1.8.test.mjs
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
const sessionTree = src('ui/SessionTree.kt')
const messageBubble = src('ui/components/MessageBubble.kt')
const transcriptFilesDialog = src('ui/components/TranscriptFilesDialog.kt')
const sessionParser = src('data/SessionParser.kt')
const toolFiles = src('data/ToolFiles.kt')
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
  // `blank` is an offset into `rest`, so it has to be rebased onto the source.
  const end = blank < 0 ? source.length : decl.index + blank
  const text = source.slice(start, end)
  assert.ok(text.includes('='), `fun ${name} has no expression body`)
  return text
}

/** Drop `//` line comments so prose about a pattern cannot trip a check. */
function stripLineComments(text) {
  return text
    .split('\n')
    .map((line) => line.replace(/\/\/.*$/, ''))
    .join('\n')
}

// ==================== 1. copying is a long press on one message

console.log('=== copy is a long press on a single message ===')

check('a bubble copies on long press, not on a plain tap', () => {
  // A single tap has no meaning on a bubble here, and binding copy to it would
  // fire on every scroll that happens to end with a finger resting on one. The
  // long press is the platform idiom for "act on this item", and it is the
  // gesture that was asked for.
  assert.match(
    messageBubble,
    /combinedClickable\(/,
    'the bubble must use combinedClickable so it can distinguish press from tap',
  )
  const gesture = messageBubble.slice(messageBubble.indexOf('fun Modifier.longPressCopy'))
  assert.match(
    gesture.slice(0, 400),
    /onLongClick\s*=/,
    'the copy must be bound to onLongClick',
  )
  assert.doesNotMatch(
    stripLineComments(gesture.slice(0, 400)),
    /onClick\s*=\s*onCopy/,
    'a plain tap must not copy: scrolling that ends on a bubble would trigger it',
  )
})

check('the gesture is optional and left off system notices', () => {
  // `SystemBubble` renders things like "已停止生成". Offering "复制" for those
  // promises something the user did not ask for, so the handler is nullable and
  // the system branch does not receive one.
  assert.match(
    messageBubble,
    /onCopy:\s*\(\(\)\s*->\s*Unit\)\?\s*=\s*null/,
    'the copy handler must be nullable so it can be omitted',
  )
  const dispatch = functionBody(messageBubble, 'MessageBubble')
  const systemAt = dispatch.indexOf('Role.SYSTEM')
  assert.ok(systemAt >= 0, 'the dispatcher must route the system role')
  const systemLine = dispatch.slice(systemAt, dispatch.indexOf('\n', systemAt))
  assert.doesNotMatch(
    systemLine,
    /onCopy/,
    'system notices must not be copyable: there is nothing of the user\'s to copy',
  )
  // And the user/assistant branches must pass it through.
  for (const role of ['USER', 'ASSISTANT']) {
    const at = dispatch.indexOf(`Role.${role} ->`)
    assert.ok(at >= 0, `the dispatcher must route ${role}`)
    const line = dispatch.slice(at, dispatch.indexOf('\n', at))
    assert.match(line, /onCopy/, `${role} bubbles must be copyable`)
  }
})

check('the transcript wires the long press to the clipboard', () => {
  assert.match(
    chatScreen,
    /onCopyMessage/,
    'the transcript screen must accept a per-message copy action',
  )
  const list = chatScreen.slice(chatScreen.indexOf('items(rendered'))
  assert.match(
    list.slice(0, 400),
    /onCopy\s*=/,
    'each bubble must be given the copy handler',
  )
  assert.match(
    list.slice(0, 400),
    /clipboard\.setText/,
    'the handler must place the text on the real clipboard',
  )
})

check('one renderer is shared, so both copies agree', () => {
  // A second renderer for single messages would drift from the whole-transcript
  // one, and the drift is only visible when a user pastes the two side by side.
  const body = functionBody(viewModel, 'copyMessage')
  assert.match(
    body,
    /Transcript\.messageMarkdown\(/,
    'the single-message copy must reuse the transcript renderer',
  )
  const whole = functionBody(viewModel, 'copyTranscript')
  assert.match(
    whole,
    /Transcript\.toMarkdown\(/,
    'the whole-transcript copy must keep using the same renderer family',
  )
})

check('the whole-transcript copy is kept as well', () => {
  // They are different needs — "take this conversation with me" versus "copy the
  // thing I am reading" — so fixing one must not remove the other.
  assert.match(
    chatScreen,
    /onCopyTranscript/,
    'the app-bar whole-conversation copy must survive the long-press addition',
  )
})

// ============ 2. the files the assistant used, not a workspace browser

console.log('\n=== the assistant\'s files are directly reachable ===')

check('present contributes its files, with descriptions', () => {
  // `present` is the tool whose entire purpose is handing the user files, and its
  // arguments are `files: [{path, description}]`. Reading "the first string
  // argument" instead would pick up something that is not a file.
  const body = functionBody(toolFiles, 'of')
  assert.match(body, /"present"/, 'the present tool must be recognised')
  const arrayRefs = functionBody(toolFiles, 'arrayRefs')
  assert.match(arrayRefs, /"files"/, 'present\'s files live under the files key')
  assert.match(arrayRefs, /"path"/, 'each entry names a path')
  assert.match(
    arrayRefs,
    /"description"/,
    'the description must be carried through: it is the tool\'s own explanation',
  )
})

check('single-file tools are read per tool, not by scanning', () => {
  // A generic scan for the first string argument would offer to open a shell
  // command or a grep pattern. The same reasoning as ToolLabel, which already
  // picks its detail per tool.
  const body = functionBody(toolFiles, 'of')
  assert.match(body, /"read"/, 'read must be recognised')
  assert.match(body, /"write"/, 'write must be recognised')
  assert.match(body, /"edit"/, 'edit must be recognised')
  assert.match(
    body,
    /single\(args,\s*"file_path"\)/,
    'the file tools take their path from file_path',
  )
  assert.doesNotMatch(
    stripLineComments(body),
    /firstString|values\(\)\.first/,
    'the path must not be guessed by scanning for any string argument',
  )
})

check('the list is newest first and de-duplicated', () => {
  // The reason to open this list is almost always the file just produced, so
  // recency is the useful order. A file written and then edited must appear once.
  const body = functionBody(toolFiles, 'ofTranscript')
  assert.match(body, /LinkedHashMap/, 'de-duplication must preserve order')
  assert.match(
    body,
    /seen\.remove\(ref\.path\)[\s\S]{0,80}seen\[ref\.path\]\s*=/,
    're-inserting must move the key to the end so the newest mention wins',
  )
  assert.match(body, /asReversed\(\)/, 'the result must be reversed to newest-first')
})

check('the list is derived from the transcript, not snapshotted', () => {
  // The transcript grows one batch at a time. A stored copy would freeze at the
  // moment it was opened and leave the button's own visibility a turn behind —
  // the exact shape of bug this release fixes elsewhere.
  const stateBlock = viewModel.slice(0, viewModel.indexOf('class ChatViewModel'))
  assert.match(
    stateBlock,
    /val transcriptFiles:[\s\S]{0,120}get\(\)\s*=\s*ToolFiles\.ofTranscript\(messages\)/,
    'transcriptFiles must be a computed property over the live transcript',
  )
  assert.match(
    stateBlock,
    /val showTranscriptFiles: Boolean/,
    'visibility must be a separate flag rather than the list itself',
  )
})

check('opening it does not trip the workspace browser', () => {
  // `filesOpen` is defined as "the browser has a listing or an error", so writing
  // an error here would pop the *browser* open behind this dialog.
  const body = functionBody(viewModel, 'openTranscriptFiles')
  assert.doesNotMatch(
    stripLineComments(body),
    /filesError\s*=/,
    'the assistant-files action must not write the workspace browser\'s error field',
  )
  assert.match(body, /showTranscriptFiles\s*=\s*true/, 'it must open its own dialog')
})

check('the full path is shown, not a shortened tail', () => {
  // In a one-line tool card the tail is right, because the card shares a line. Here
  // the path is the only content, and two files named `index.ts` are a real
  // possibility — an ambiguous list is worse than a long one.
  assert.match(
    transcriptFilesDialog,
    /ref\.path/,
    'the row must render the path',
  )
  assert.match(
    transcriptFilesDialog,
    /FontFamily\.Monospace/,
    'the path must be monospaced so it reads as a path',
  )
  assert.doesNotMatch(
    stripLineComments(functionBody(transcriptFilesDialog, 'FileRefRow')),
    /takeLast|tailPath|shorten/,
    'the path must not be truncated to its tail here',
  )
})

check('opening one reads it through the existing read path', () => {
  // No second reader: the workspace-files read call already handles absolute and
  // relative paths and already enforces the workspace boundary.
  const shell = appShell.slice(appShell.indexOf('TranscriptFilesDialog('))
  assert.match(
    shell.slice(0, 500),
    /onOpenWorkspaceFile/,
    'opening a listed file must reuse the existing file reader',
  )
  assert.match(
    toolFiles,
    /not resolved here|resolution is the host|accepts both/i,
    'the file must record why paths are passed through unresolved',
  )
})

// ============================== 3. per-turn duration

console.log('\n=== the turn duration is only shown when provable ===')

check('a merged turn carries the span of its steps', () => {
  const body = functionBody(sessionParser, 'mergeSteps')
  assert.match(body, /earliest/, 'the earliest step timestamp must be tracked')
  assert.match(body, /latest/, 'the latest step timestamp must be tracked')
  assert.match(
    body,
    /latest\s*-\s*earliest/,
    'the duration is the span between the first and last step',
  )
  assert.match(
    body,
    /durationMs\s*=\s*duration/,
    'the computed span must reach the message',
  )
})

check('a step with no timestamp is skipped, not treated as the epoch', () => {
  // A zero `time` means "the log did not say". Counting it would report a duration
  // of roughly 56 years.
  const body = functionBody(sessionParser, 'mergeSteps')
  assert.match(
    body,
    /if\s*\(t\s*<=\s*0L\)\s*continue/,
    'timestampless steps must be skipped, or the span becomes ~56 years',
  )
  assert.match(
    body,
    /earliest\s*!=\s*Long\.MAX_VALUE/,
    'a group with no usable timestamp must not produce a span',
  )
})

check('a single-step turn reports no duration rather than zero', () => {
  // The log records when an event was *written*, not how long the model took. A
  // one-step turn has no span at all, so showing "用时 0 秒" would assert a
  // measurement that was never taken.
  const message = sessionParser.slice(sessionParser.indexOf('val durationMs'))
  assert.match(
    message.slice(0, 700),
    /val durationMs: Long = 0L/,
    'the default must be zero, meaning "no measurable span"',
  )
  assert.match(
    sessionParser,
    /only a merged turn has one|Only a merged turn/i,
    'the reason a lone message reports nothing must be recorded',
  )
})

check('the label suppresses sub-second and zero spans', () => {
  const body = functionBody(messageBubble, 'formatDuration')
  assert.match(
    body,
    /if\s*\(durationMs\s*<\s*1_000L\)\s*return null/,
    'under a second there is nothing provable to show',
  )
  assert.match(
    body,
    /Math\.round\(/,
    'seconds must be rounded, not truncated, so 59.6s does not read as 59',
  )
})

check('the duration is rendered on the answer', () => {
  const bubble = functionBody(messageBubble, 'AssistantBubble')
  assert.match(bubble, /formatDuration\(message\.durationMs\)/, 'the answer must show it')
  assert.match(
    bubble,
    /let\s*\{ label\s*->|let\s*\{/,
    'a null duration must render nothing at all, not an empty row',
  )
})

check('the duration formatting matches the pinned table', () => {
  // Differential test: a JS replica of `formatDuration` against pinned inputs.
  // Rounding and unit roll-over are exactly the kind of logic that compiles and is
  // silently wrong.
  const formatDuration = (durationMs) => {
    if (durationMs < 1000) return null
    const totalSeconds = Math.round(durationMs / 1000.0)
    if (totalSeconds < 60) return `用时 ${totalSeconds} 秒`
    const minutes = Math.floor(totalSeconds / 60)
    const seconds = totalSeconds % 60
    if (minutes < 60) {
      return seconds === 0 ? `用时 ${minutes} 分钟` : `用时 ${minutes} 分 ${seconds} 秒`
    }
    const hours = Math.floor(minutes / 60)
    const remMinutes = minutes % 60
    return remMinutes === 0 ? `用时 ${hours} 小时` : `用时 ${hours} 小时 ${remMinutes} 分`
  }
  const cases = [
    [0, null],
    [999, null],
    [1000, '用时 1 秒'],
    [12_400, '用时 12 秒'],
    [59_400, '用时 59 秒'],
    // 59.6s rounds up into the minute band rather than staying "59 秒", which is
    // what keeps two adjacent turns from looking a minute apart.
    [59_600, '用时 1 分钟'],
    [60_000, '用时 1 分钟'],
    [90_000, '用时 1 分 30 秒'],
    [3_599_000, '用时 59 分 59 秒'],
    [3_600_000, '用时 1 小时'],
    [5_400_000, '用时 1 小时 30 分'],
  ]
  for (const [input, expected] of cases) {
    assert.equal(
      formatDuration(input),
      expected,
      `${input}ms must format as ${JSON.stringify(expected)}`,
    )
  }
})

// ==================== 4. the drawer groups by workspace and collapses

console.log('\n=== the session drawer is a workspace tree ===')

check('long groups collapse, short ones do not', () => {
  assert.match(
    sessionTree,
    /const val COLLAPSE_AFTER = 4/,
    'the collapse threshold must be a named constant',
  )
  const group = sessionTree.slice(sessionTree.indexOf('data class Group'))
  assert.match(
    group.slice(0, 900),
    /\(sessions\.size - COLLAPSE_AFTER\)\.coerceAtLeast\(0\)/,
    'the hidden count must never go negative for a short group',
  )
  assert.match(
    group.slice(0, 900),
    /val collapsible: Boolean get\(\) = collapsedCount > 0/,
    'a group that fits must not be collapsible, or it offers a fold that does nothing',
  )
})

check('sessions are matched to workspaces by directory, not by id', () => {
  // `session/list` reports a `cwd` and not a workspace id; the two are the same
  // thing on the server, which resolves `cwd = workspace.path` itself.
  const body = functionBody(sessionTree, 'group')
  assert.match(
    body,
    /workspaces\.associateBy\s*\{\s*normalise\(it\.path\)\s*\}/,
    'the workspace index must be keyed by the normalised path',
  )
  assert.match(
    body,
    /byPath\[normalise\(it\)\]/,
    'the session must be looked up by its normalised cwd',
  )
  assert.doesNotMatch(
    stripLineComments(body),
    /workspaceId\s*==|it\.id\s*==\s*session/,
    'matching by workspace id cannot work: the session row does not carry one',
  )
})

check('paths are normalised before comparison', () => {
  // `E:\proj\` and `e:/proj` are the same directory. Without normalising, the two
  // sides simply fail to match and every session lands in the fallback group — a
  // bug that reads as "grouping is broken" rather than "a string differed".
  const body = functionBody(sessionTree, 'normalise')
  assert.match(body, /replace\('\\\\',\s*'\/'\)/, 'backslashes must be unified')
  assert.match(body, /trimEnd\('\/'\)/, 'a trailing separator must be dropped')
  assert.match(
    body,
    /lowercase\(\)/,
    'Windows paths must be compared case-insensitively, because that filesystem is',
  )
  const unix = functionLine(sessionTree, 'unixLike')
  assert.match(
    unix,
    /startsWith\("\/"\)/,
    'a Unix path must stay case-sensitive, so the folding is conditional',
  )
  // And the same comparison must be used for the fallback group's heading, or the
  // two halves of one decision disagree about what "the same path" means.
  assert.match(
    functionBody(sessionTree, 'unnamedLabel'),
    /distinctBy\s*\{\s*normalise\(it\)\s*\}/,
    'the shared-directory check must normalise too, or two spellings of one ' +
      'directory would be read as two directories and lose their heading',
  )
})

check('the workspace-less group sorts last', () => {
  const body = functionBody(sessionTree, 'group')
  assert.match(
    body,
    /compareBy<Group>\s*\{\s*!it\.isWorkspace\s*\}/,
    'the fallback group must sort after every real workspace',
  )
  assert.match(
    body,
    /thenByDescending\s*\{\s*it\.sessions\.firstOrNull\(\)\?\.updatedAt/,
    'groups must be ordered by their newest conversation, so the active project ' +
      'floats up regardless of when the workspace was created',
  )
})

check('a session outside the workspace list still gets a usable heading', () => {
  // A conversation in an unregistered directory names a directory, and that name is
  // what the user recognises the project by — far better than a generic label.
  const body = functionBody(sessionTree, 'unnamedLabel')
  assert.match(body, /singleOrNull\(\)/, 'a shared directory must be detected')
  assert.match(
    body,
    /substringAfterLast\('\/'\)/,
    'the heading must be the directory\'s own name',
  )
  assert.match(body, /未归类/, 'and there must be a fallback when they disagree')
})

check('the grouping matches the pinned tree', () => {
  // Differential test: a JS replica of `SessionTree.group` against pinned inputs.
  // Ordering and bucket assignment are exactly the kind of logic that compiles and
  // is silently wrong.
  const normalise = (p) => {
    const unified = p.trim().replace(/\\/g, '/').replace(/\/+$/, '')
    const unixLike = unified.startsWith('/') && !unified.startsWith('//')
    return unixLike ? unified : unified.toLowerCase()
  }
  const unnamedLabel = (rows) => {
    // Normalised, like the real one: two spellings of one directory are one
    // directory, and reading them as two would lose the group's heading.
    const seen = new Map()
    for (const r of rows) {
      const c = r.cwd
      if (!c || !c.trim()) continue
      if (!seen.has(normalise(c))) seen.set(normalise(c), c)
    }
    const paths = [...seen.values()]
    if (paths.length !== 1) return '未归类'
    const only = paths[0].replace(/\\/g, '/').replace(/\/+$/, '')
    const tail = only.slice(only.lastIndexOf('/') + 1)
    return tail.trim() ? tail : only
  }
  const group = (sessions, workspaces) => {
    const byPath = new Map(workspaces.map((w) => [normalise(w.path), w]))
    const buckets = new Map()
    const labels = new Map()
    for (const s of sessions) {
      const ws = s.cwd && s.cwd.trim() ? byPath.get(normalise(s.cwd)) : undefined
      const key = ws ? ws.id : ''
      if (!buckets.has(key)) buckets.set(key, [])
      buckets.get(key).push(s)
      if (ws && !labels.has(key)) {
        labels.set(key, { workspaceId: ws.id, label: ws.label, path: ws.path, isWorkspace: true })
      }
    }
    const groups = [...buckets.entries()].map(([key, rows]) => {
      const known = labels.get(key)
      return {
        workspaceId: key,
        label: known ? known.label : unnamedLabel(rows),
        path: known ? known.path : (rows[0]?.cwd ?? ''),
        isWorkspace: Boolean(known),
        sessionIds: rows.map((r) => r.id),
      }
    })
    return groups.sort((a, b) => {
      if (a.isWorkspace !== b.isWorkspace) return a.isWorkspace ? -1 : 1
      const at = a.sessionIds.length ? null : null
      void at
      const aTime = buckets.get(a.workspaceId)[0]?.updatedAt ?? 0
      const bTime = buckets.get(b.workspaceId)[0]?.updatedAt ?? 0
      return bTime - aTime
    })
  }

  const workspaces = [
    { id: 'w-app', label: 'App', path: 'E:\\proj\\app\\' },
    { id: 'w-lib', label: 'Lib', path: 'E:/proj/lib' },
  ]
  const sessions = [
    // Trailing separator and a case difference: must still match `w-app`.
    { id: 's1', cwd: 'e:/proj/app', updatedAt: 500 },
    { id: 's2', cwd: 'E:/proj/lib', updatedAt: 900 },
    { id: 's3', cwd: 'E:\\proj\\app', updatedAt: 700 },
    { id: 's4', cwd: 'E:/other/thing', updatedAt: 100 },
    { id: 's5', cwd: null, updatedAt: 50 },
  ]

  const groups = group(sessions, workspaces)
  assert.equal(groups.length, 3, 'two workspaces plus the fallback group')

  // Lib's newest (900) beats App's newest (700), so Lib leads.
  assert.deepEqual(
    groups.map((g) => g.workspaceId),
    ['w-lib', 'w-app', ''],
    'groups must be ordered by their newest conversation, with the fallback last',
  )
  assert.deepEqual(
    groups[0].sessionIds,
    ['s2'],
    'the Lib group holds only its own session',
  )
  // Both spellings of the App path must land in the same bucket, in input order.
  assert.deepEqual(
    groups[1].sessionIds,
    ['s1', 's3'],
    'both spellings of the same directory must join one group',
  )
  assert.deepEqual(
    groups[2].sessionIds,
    ['s4', 's5'],
    'unmatched and absent cwds share the fallback group',
  )

  // The fallback heading is the shared directory's own name when they agree.
  const agreed = group(
    [
      { id: 'a', cwd: 'E:/solo/alpha', updatedAt: 10 },
      { id: 'b', cwd: 'E:\\solo\\alpha', updatedAt: 20 },
    ],
    [],
  )
  assert.equal(agreed.length, 1, 'both sessions belong to one fallback group')
  assert.equal(agreed[0].isWorkspace, false, 'with no workspace behind it')
  assert.equal(agreed[0].label, 'alpha', 'the shared directory names the group')

  // And it falls back to a generic label when they disagree.
  const disagreed = group(
    [
      { id: 'a', cwd: 'E:/one', updatedAt: 10 },
      { id: 'b', cwd: 'E:/two', updatedAt: 20 },
    ],
    [],
  )
  assert.equal(disagreed[0].label, '未归类', 'disagreeing directories get the fallback label')
})

check('the expansion survives a refresh', () => {
  // The drawer is rebuilt on every state change — a token delta recomposes the whole
  // shell — and a refresh replaces the session list, which is exactly when the user
  // is still reading it. So the expansion lives in the view model.
  const stateBlock = viewModel.slice(0, viewModel.indexOf('class ChatViewModel'))
  assert.match(
    stateBlock,
    /val expandedGroups: Set<String>/,
    'the expansion must be part of the state',
  )
  const toggle = functionBody(viewModel, 'toggleGroup')
  assert.match(
    toggle,
    /if\s*\(workspaceId in current\)\s*current\s*-\s*workspaceId/,
    'toggling an open group must collapse it',
  )
  assert.match(
    toggle,
    /else current \+ workspaceId/,
    'toggling a closed group must expand it',
  )
})

check('the drawer renders headers, indentation and the reveal row', () => {
  const drawer = appShell.slice(appShell.indexOf('private fun SessionDrawer'))
  assert.match(drawer, /state\.sessionGroups/, 'the drawer must render the grouped tree')
  assert.match(
    drawer,
    /WorkspaceGroupHeader\(/,
    'each group needs its own header row',
  )
  assert.match(
    drawer,
    /SessionTree\.COLLAPSE_AFTER/,
    'a collapsed group must render only the first few rows',
  )
  assert.match(drawer, /ShowMoreRow\(/, 'the hidden remainder needs a reveal row')
  assert.match(
    drawer,
    /group\.collapsedCount/,
    'the reveal row must say how many are hidden',
  )
  assert.match(
    drawer,
    /start\s*=\s*18\.dp/,
    'session rows must be indented under their workspace header',
  )
  // The header must only be tappable when there is something to fold.
  const header = appShell.slice(appShell.indexOf('private fun WorkspaceGroupHeader'))
  assert.match(
    header.slice(0, 700),
    /clickable\(enabled = group\.collapsible/,
    'a header that cannot fold must not respond to a tap',
  )
})

check('the drawer is wired end to end', () => {
  assert.match(appShell, /onToggleGroup/, 'the shell must accept the toggle action')
  assert.match(
    mainActivity,
    /onToggleGroup\s*=\s*vm::toggleGroup/,
    'the activity must connect the toggle to the view model',
  )
  assert.match(
    mainActivity,
    /onCopyMessage\s*=\s*vm::copyMessage/,
    'the activity must connect the per-message copy',
  )
  assert.match(
    mainActivity,
    /onOpenTranscriptFiles\s*=\s*vm::openTranscriptFiles/,
    'the activity must connect the assistant-files action',
  )
})

check('the entry point is only offered when it has something to show', () => {
  // An action that leads to an empty list is worse than an absent one: it costs a
  // tap and teaches the user that the feature does not work.
  assert.match(
    chatScreen,
    /state\.transcriptFiles\.isNotEmpty\(\)[\s\S]{0,300}onOpenTranscriptFiles/,
    'the assistant-files button must be gated on the list being non-empty',
  )
})

// ================================= 5. the release is coherent

console.log('\n=== 1.1.8 is the released version ===')

check('the CHANGELOG has a 1.1.8 section naming all four changes', () => {
  const section = /^## \[1\.1\.8\][\s\S]*?(?=^## \[|\Z)/m.exec(changelog)
  assert.ok(section, 'CHANGELOG.md must have a "## [1.1.8]" section')
  assert.ok(
    section[0].length > 400,
    'the 1.1.8 section is too short to be the release notes users will see',
  )
  assert.match(section[0], /长按/, 'the notes must name the long-press copy fix')
  assert.match(
    section[0],
    /助手用到的文件|提供的文件/,
    'the notes must name the assistant-files fix',
  )
  assert.match(section[0], /用时/, 'the notes must name the duration feature')
  assert.match(section[0], /工作区/, 'the notes must name the workspace grouping')
  assert.match(section[0], /折叠/, 'the notes must name the collapsing')
})

check('the CHANGELOG link block defines 1.1.8', () => {
  assert.match(
    changelog,
    /^\[1\.1\.8\]:\s+https:\/\/github\.com\/nmaych\/dsh-mobile\/compare\/v1\.1\.7\.\.\.v1\.1\.8$/m,
    'the link reference for 1.1.8 must exist, or the heading renders as literal text',
  )
})

check('the Gradle default version matches the newest CHANGELOG entry', () => {
  // Asserted as an invariant rather than as the literal "1.1.8": the literal is
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
    /node test\/regression-1\.1\.8\.test\.mjs/,
    'ci.yml must run test/regression-1.1.8.test.mjs',
  )
})

check('the new sources are real files', () => {
  for (const rel of [
    'app-project/app/src/main/java/ai/deepseek/dshmobile/data/ToolFiles.kt',
    'app-project/app/src/main/java/ai/deepseek/dshmobile/ui/SessionTree.kt',
    'app-project/app/src/main/java/ai/deepseek/dshmobile/ui/components/TranscriptFilesDialog.kt',
  ]) {
    assert.ok(exists(rel), `${rel} is missing`)
  }
})

check('1.1.7\'s own suite still describes the code it guards', () => {
  // 1.1.8 changes what 1.1.7 shipped, so the older suite must have been updated
  // rather than left asserting a shape that no longer exists.
  const previous = read('test/regression-1.1.7.test.mjs')
  assert.match(
    previous,
    /copyMessage|onCopy/,
    'the 1.1.7 suite must account for the per-message copy that replaced its ' +
      'whole-transcript-only model',
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
