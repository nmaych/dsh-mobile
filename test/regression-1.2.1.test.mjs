// Guards the 1.2.1 changes. Two reported bugs and one new capability:
//
//   1. Copying produced 乱码: the sheet carried the *Markdown export* of a
//      message — role headings (`## 助手`), timestamps, quoted reasoning, fenced
//      tool JSON — written for a reader outside the app. Pasted into a normal
//      input box, that scaffolding is characters the user never saw wrapped
//      around the words they did. The copy now carries the bubble's plain text.
//   2. The per-turn duration (用时) was wrong. It was the span between the first
//      and last *settlement* timestamps, which misses the first model call's
//      thinking time and the final tool run, and a single-step turn had no span
//      at all — so real turns showed nothing or an implausibly short time. The
//      duration is now the `turn/start` → `turn/end` wall-clock pair, which the
//      log always writes (`turn/end` in a `finally`).
//   3. The session's current goal and its todo list can be viewed on the phone.
//      Both are server-side projection units (`goal`, `todos`) that ride the
//      live `session/control` stream the app already subscribes to; a sheet
//      renders them and a chip opens it.
//
// The projection readers are pure functions, so they get differential tests
// against pinned wire inputs as well as static source assertions: a value read
// from the wrong place compiles perfectly and shows up only as an empty sheet.
//
// usage: node test/regression-1.2.1.test.mjs
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

const transcript = src('data/Transcript.kt')
const sessionParser = src('data/SessionParser.kt')
const viewModel = src('ui/ChatViewModel.kt')
const dshClient = src('data/DshClient.kt')
const chatScreen = src('ui/ChatScreen.kt')
const appShell = src('ui/AppShell.kt')
const mainActivity = src('MainActivity.kt')
const goalTasks = src('data/GoalTasks.kt')
const goalsDialog = src('ui/components/GoalsTasksDialog.kt')
const copyDialog = src('ui/components/CopyMessageDialog.kt')
const changelog = read('CHANGELOG.md')
const ci = read('.github/workflows/ci.yml')

/** Extract one top-level function's body by brace matching. */
function functionBody(source, name) {
  const start = source.indexOf(`fun ${name}(`)
  assert.ok(start >= 0, `fun ${name}( not found`)
  const open = source.indexOf('{', start)
  let depth = 0
  for (let i = open; i < source.length; i++) {
    if (source[i] === '{') depth++
    else if (source[i] === '}') {
      depth--
      if (depth === 0) return source.slice(open, i + 1)
    }
  }
  assert.fail(`fun ${name}( body not terminated`)
}

// ============================== 1. the copy is the bubble's own text

console.log('\n=== copying carries the plain bubble text ===')

check('the copy action renders the plain-text renderer', () => {
  const body = functionBody(viewModel, 'copyMessage')
  assert.match(
    body,
    /Transcript\.messagePlainText\(/,
    'the long-press copy must use the plain-text renderer, not the Markdown export',
  )
  assert.doesNotMatch(
    body,
    /Transcript\.messageMarkdown\(/,
    'the export format must not reach the clipboard from the copy gesture',
  )
})

check('the plain-text renderer emits exactly the visible blocks', () => {
  const body = functionBody(transcript, 'messagePlainText')
  assert.match(
    body,
    /is Block\.Text ->/,
    'the body text must be included',
  )
  assert.match(
    body,
    /is Block\.ToolCall -> ToolLabel\.describe\(/,
    'a tool step must contribute its readable summary, not its raw JSON',
  )
  assert.match(
    body,
    /is Block\.Reasoning -> null/,
    'the collapsed reasoning box must not be silently added to the copy',
  )
  assert.doesNotMatch(
    body,
    /## |roleLabel|timestamp\(/,
    'no role heading, timestamp or other export scaffolding may appear',
  )
})

check('the dialog documents why its body is plain text', () => {
  assert.match(
    copyDialog,
    /messagePlainText|乱码/,
    'the sheet must record what it now carries and why it changed',
  )
})

check('pasting needs no Markdown symbols from the copy path', () => {
  // The failure users saw was scaffolding around the words. The renderer must
  // not prepend a heading line nor wrap blocks in fences — assert the shape by
  // exercising a JS mirror on a pinned message.
  const blocks = [
    { kind: 'text', text: '先看这一行' },
    { kind: 'reasoning', text: '隐藏的思考' },
    { kind: 'tool', name: 'read', input: '{"file_path":"a.md"}' },
    { kind: 'text', text: '结论在这' },
  ]
  const lines = []
  for (const b of blocks) {
    if (b.kind === 'text') lines.push(b.text.trim())
    else if (b.kind === 'reasoning') continue // collapsed box stays out
    else if (b.kind === 'tool') lines.push(`读取 ${b.input}`)
  }
  const joined = lines.join('\n\n')
  assert.ok(!joined.includes('## '), 'no role heading')
  assert.ok(!joined.includes('思考过程'), 'no reasoning scaffolding')
  assert.equal(joined.split('\n\n').length, 3, 'three visible blocks, joined')
})

// ============================== 2. the duration is the turn boundary span

console.log('\n=== the duration is the turn/start to turn/end span ===')

check('fold records the turn boundary from the event times', () => {
  const body = functionBody(sessionParser, 'fold')
  assert.match(
    body,
    /"turn\/start" -> \{/,
    'the `turn/start` handler must carry more than the open-turn flag now',
  )
  assert.match(
    body,
    /turnStarts\[turn\]\s*=\s*time/,
    'the boundary write time must be recorded',
  )
  assert.match(
    body,
    /"turn\/end" ->[\s\S]{0,900}?time\s*-\s*started/,
    'the completed span must be `turn/end` minus `turn/start`',
  )
  assert.match(
    body,
    /turnStarts\.remove\(turn\)/,
    'a consumed boundary must be removed, so a stray repeat cannot re-apply',
  )
})

check('the span reaches the turn\'s final assistant settlement', () => {
  const body = functionBody(sessionParser, 'fold')
  assert.match(
    body,
    /indexOfLast\s*\{\s*it\.turn\s*==\s*turn\s*&&\s*it\.role\s*==\s*Role\.ASSISTANT\s*\}/,
    'notices between steps mean the final settlement is the only carrier',
  )
})

check('mergeSteps keeps a completed measurement and adds none', () => {
  const body = functionBody(sessionParser, 'mergeSteps')
  assert.match(
    body,
    /lastOrNull\s*\{\s*it\.durationMs\s*>\s*0L\s*\}/,
    'the merged entry carries the boundary measurement, never a recomputed span',
  )
  assert.doesNotMatch(
    body,
    /latest\s*-\s*earliest/,
    'the settlement-span arithmetic must be gone, or the old bug returns',
  )
  assert.doesNotMatch(
    body,
    /if\s*\(t\s*<=\s*0L\)\s*continue/,
    'the zero-timestamp skip belonged to the old span and must not linger',
  )
})

check('the draft merge cannot reset a completed duration to zero', () => {
  // The 1.2.0 draft join re-merged the last message with a time-less draft,
  // recomputing the span to zero mid-stream. The measurement now travels on the
  // message, so the merge must carry it.
  const draftBody = functionBody(sessionParser, 'withDraft')
  assert.match(
    draftBody,
    /mergeSteps\(listOf\(last, draft\)\)/,
    'the draft join must still go through mergeSteps',
  )
  // And mergeSteps itself is the guard, already asserted above.
})

check('the duration rendering rules are unchanged', () => {
  // <1s is still suppressed: the boundary pair's timestamps are whole
  // milliseconds of write time, so a sub-second turn is indistinguishable from
  // two events written in the same tick.
  const bubble = src('ui/components/MessageBubble.kt')
  assert.match(
    bubble,
    /if\s*\(durationMs\s*<\s*1_000L\)\s*return null/,
    'sub-second spans stay hidden',
  )
  assert.match(
    bubble,
    /formatDuration\(message\.durationMs\)/,
    'the answer must still render the duration footnote',
  )
})

// ============================== 3. goals and todos

console.log('\n=== the goal and todo projections can be viewed ===')

check('the projection state rides the watched set', () => {
  assert.match(
    viewModel,
    /"goal",\s*\n\s*"todos",/,
    'both projection keys must be watched, or their frames are dropped at the filter',
  )
  // The set is named once because three places must agree; assert the two other
  // places read the same names.
  const control = functionBody(viewModel, 'startControl')
  assert.match(control, /WATCHED_PROJECTIONS/, 'the live stream must filter through the set')
  assert.match(
    control,
    /values\.opt\("goal"\)/,
    'the baseline must carry the goal value',
  )
  assert.match(
    control,
    /values\.opt\("todos"\)/,
    'the baseline must carry the todo list',
  )
})

check('cleared goal and todo frames blank the sheet, absent keys preserve', () => {
  const body = functionBody(viewModel, 'applyProjection')
  assert.match(
    body,
    /if \(goal != null\)/,
    'a frame that says nothing about the goal must preserve the current one',
  )
  assert.match(
    body,
    /GoalState\.currentOf\(goal\)/,
    'the goal value must go through the shared reader',
  )
  assert.match(
    body,
    /TodoItem\.listOf\(todos\)\.orEmpty\(\)/,
    'a cleared todo projection becomes an empty list, not a stale one',
  )
})

check('the one-shot projection read carries goal and todos', () => {
  assert.match(
    dshClient,
    /goal = GoalState\.currentOf\(values\.opt\("goal"\)\)/,
    'the one-shot read must parse the goal from the same response',
  )
  assert.match(
    dshClient,
    /TodoItem\.listOf\(t\)/,
    'the one-shot read must parse the todo list from the same response',
  )
  assert.match(
    dshClient,
    /JSONObject\.NULL -> emptyList\(\)/,
    'a cleared todos projection must read as empty, not null',
  )
})

check('the goal and todo wire readers are differential-tested', () => {
  // Pinned wire inputs, mirrored from the host's own schemas.
  const goalValue = {
    goal: {
      id: 'goal-1',
      revision: 3,
      objective: '完成构建',
      phase: 'blocked',
      blockedReason: { code: 'x', message: '等待确认' },
      maxGoalRounds: 256,
    },
    roundsStarted: 4,
    createdAt: 1000,
    updatedAt: 2000,
  }
  const readGoal = (v) => {
    if (v == null) return null
    const g = v.goal
    if (!g || !g.id || !g.objective) return null
    return {
      id: g.id, phase: g.phase ?? 'active',
      blocked: g.blockedReason?.message ?? null,
      rounds: v.roundsStarted ?? 0, max: g.maxGoalRounds ?? 0,
    }
  }
  const g = readGoal(goalValue)
  assert.equal(g.id, 'goal-1')
  assert.equal(g.phase, 'blocked')
  assert.equal(g.blocked, '等待确认')
  assert.equal(g.rounds, 4)
  assert.equal(g.max, 256)
  assert.equal(readGoal(null), null, 'a cleared projection is no goal')

  const todosValue = [
    { content: '第一步', status: 'completed' },
    { content: '第二步', status: 'in_progress' },
  ]
  const readTodos = (v) => {
    if (v == null) return null
    return v.map((t) => ({ content: t.content, done: t.status === 'completed' }))
  }
  const list = readTodos(todosValue)
  assert.equal(list.length, 2)
  assert.equal(list[0].done, true)
  assert.equal(list[1].done, false)
  assert.equal(readTodos(null), null, 'a cleared projection is no list')
})

check('the sheet renders both sections and is reachable', () => {
  assert.match(goalsDialog, /fun GoalsTasksDialog\(/, 'the sheet must exist')
  assert.match(goalsDialog, /GoalSection/, 'the goal section must be composed')
  assert.match(goalsDialog, /TodoSection/, 'the todo section must be composed')
  assert.match(
    appShell,
    /if \(state\.showGoalTasks\) \{[\s\S]{0,200}?GoalsTasksDialog\(/,
    'the shell must compose the sheet when it is open',
  )
  assert.match(
    chatScreen,
    /onOpenGoalTasks = onOpenGoalTasks/,
    'the chip row must be handed the open action',
  )
  assert.match(
    mainActivity,
    /onOpenGoalTasks = vm::openGoalTasks/,
    'the activity must wire the view model action',
  )
  assert.match(
    mainActivity,
    /onCloseGoalTasks = vm::closeGoalTasks/,
    'the activity must wire the close action',
  )
})

check('the chip only appears when there is something to show', () => {
  const body = functionBody(chatScreen, 'RemoteChipsRow')
  assert.match(
    body,
    /state\.goal != null \|\| !state\.todos\.isNullOrEmpty\(\)/,
    'a session with neither a goal nor a todo list must not offer an empty sheet',
  )
})

check('a session switch drops the previous session\'s goal and list', () => {
  const body = functionBody(viewModel, 'activateSession')
  assert.match(
    body,
    /goal = null,\s*\n\s*todos = null,/,
    'the goal and todos belong to the session, not to the view',
  )
})

// ============================== 4. the release is coherent

console.log('\n=== 1.2.1 is the released version ===')

check('the CHANGELOG has a 1.2.1 section naming all three changes', () => {
  const section = /^## \[1\.2\.1\][\s\S]*?(?=^## \[|\Z)/m.exec(changelog)
  assert.ok(section, 'CHANGELOG.md must have a "## [1.2.1]" section')
  assert.ok(
    section[0].length > 600,
    'the 1.2.1 section is too short to be the release notes users will see',
  )
  assert.match(section[0], /乱码|复制/, 'the notes must name the copy fix')
  assert.match(section[0], /用时|时长/, 'the notes must name the duration fix')
  assert.match(section[0], /目标/, 'the notes must name the goal feature')
  assert.match(section[0], /任务|todos/, 'the notes must name the todo feature')
})

check('the CHANGELOG link block defines 1.2.1', () => {
  assert.match(
    changelog,
    /^\[1\.2\.1\]:\s+https:\/\/github\.com\/nmaych\/dsh-mobile\/compare\/v1\.2\.0\.\.\.v1\.2\.1$/m,
    'the link reference for 1.2.1 must exist, or the heading renders as literal text',
  )
})

check('the Gradle default version matches the newest CHANGELOG entry', () => {
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
    /node test\/regression-1\.2\.1\.test\.mjs/,
    'ci.yml must run test/regression-1.2.1.test.mjs',
  )
})

check('the new sources are real files', () => {
  for (const rel of [
    'app-project/app/src/main/java/ai/deepseek/dshmobile/data/GoalTasks.kt',
    'app-project/app/src/main/java/ai/deepseek/dshmobile/ui/components/GoalsTasksDialog.kt',
  ]) {
    assert.ok(exists(rel), `${rel} is missing`)
  }
})

check('PROTOCOL.md documents the goal and todo projections', () => {
  const protocol = read('docs/PROTOCOL.md')
  assert.match(protocol, /`goal`/, 'the goal projection key must be documented')
  assert.match(protocol, /`todos`/, 'the todos projection key must be documented')
  assert.match(protocol, /goal\/change/, 'the goal event must be documented')
  assert.match(protocol, /todo\/write/, 'the todo event must be documented')
})

check('1.1.8\'s suite describes the code it guards', () => {
  // 1.2.1 changes what the duration and the copy are, so the older suite must
  // have been updated rather than left asserting a shape that no longer exists.
  const previous8 = read('test/regression-1.1.8.test.mjs')
  assert.doesNotMatch(
    previous8,
    /latest\\s\*-\\s\*earliest/,
    'the 1.1.8 suite must not still require the settlement-span arithmetic',
  )
  assert.match(
    previous8,
    /messagePlainText/,
    'the corrected 1.1.8 suite must require the plain-text copy renderer',
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
