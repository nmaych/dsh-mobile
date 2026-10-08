// Guards the 1.1.4 changes, which are the kind the Kotlin compiler cannot see:
//
//   1. The black band above the app bar. The cause was a *colour value* in an XML
//      theme disagreeing with a Compose colour scheme that follows the system
//      light/dark setting. Nothing type-checks a hex string, so the previous code
//      compiled perfectly and still painted a near-black strip on a light phone.
//
//   2. The transcript refusing to scroll. The cause was a modifier's *behaviour*:
//      auto-scroll ran on every token delta and cancelled the drag in progress.
//      Again a clean compile, and the symptom only appears while a turn streams.
//
//   3. Turns rendering as a column of near-identical bubbles. The cause was a
//      missing fold over the `turn` field — the data was always there and simply
//      unused, so nothing failed; it just looked like the same answer repeating.
//
//   4. Tool cards headed by a tool's identifier (`pwsh`, `read`) rather than what
//      it did. A wording bug, invisible to the compiler.
//
//   5. "新建对话选择模型时提示连接超时". Three different failures — a stalled mux
//      heartbeat, a slow desktop, and an unreachable one — all arrived as a
//      timeout and all produced the *same* sentence, so two of the three sent the
//      user to fix a network that was not broken. The unary calls also had no read
//      timeout at all (`readTimeout(0)` is for the long-lived mux socket), so a
//      wedged desktop spun forever instead of reporting anything.
//
//   6. Scan-to-connect. The desktop already prints a pairing QR and the app
//      already follows `dshmobile://pair` links, but nothing in the app could read
//      the code off the screen in front of the user.
//
// The merge and label rules are reimplemented here so their *behaviour* is pinned,
// not just their presence. The event shapes are not invented: the `turn` field was
// confirmed present on 4126/4126 `assistant/message` and 5056/5056 `tool/call`
// events, and the tool names are the 5041 real `tool/call` values from 41 session
// logs.
//
// usage: node test/regression-1.1.4.test.mjs
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
const res = (rel) => read(`app-project/app/src/main/res/${rel}`)

const chatScreen = src('ui/ChatScreen.kt')
const mainActivity = src('MainActivity.kt')
const sessionParser = src('data/SessionParser.kt')
const toolLabel = src('data/ToolLabel.kt')
const messageBubble = src('ui/components/MessageBubble.kt')
const dshClient = src('data/DshClient.kt')
const transportErrors = src('net/TransportErrors.kt')
const viewModel = src('ui/ChatViewModel.kt')
const pickers = src('ui/components/Pickers.kt')
const settingsScreen = src('ui/SettingsScreen.kt')
const appShell = src('ui/AppShell.kt')
const qrScan = exists('app-project/app/src/main/java/ai/deepseek/dshmobile/ui/QrScan.kt')
  ? src('ui/QrScan.kt')
  : ''
const manifest = read('app-project/app/src/main/AndroidManifest.xml')
const appGradle = read('app-project/app/build.gradle.kts')
const themes = res('values/themes.xml')
const themesNight = exists('app-project/app/src/main/res/values-night/themes.xml')
  ? res('values-night/themes.xml')
  : ''

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

// ============================================ 1. the black band above the app bar

console.log('=== edge to edge and the system bar colours ===')

check('the app draws edge to edge', () => {
  assert.match(
    mainActivity,
    /enableEdgeToEdge\s*\(/,
    'MainActivity must call enableEdgeToEdge(), or the platform keeps painting the ' +
      'status bar strip from the theme and the app bar cannot fill it',
  )
  assert.match(
    mainActivity,
    /import\s+androidx\.activity\.enableEdgeToEdge/,
    'enableEdgeToEdge must be imported, not a locally defined helper',
  )
})

check('the system bars are transparent, not a hardcoded dark colour', () => {
  for (const [name, xml] of [['values', themes], ['values-night', themesNight]]) {
    assert.ok(xml, `${name}/themes.xml is missing`)
    const status = /android:statusBarColor">([^<]*)</.exec(xml)
    assert.ok(status, `${name}/themes.xml sets no statusBarColor`)
    assert.ok(
      !/#FF0B1220/i.test(status[1]),
      `${name}/themes.xml still paints the status bar with the hardcoded dark ` +
        '#FF0B1220 — that is the light-mode black band',
    )
    assert.match(
      status[1],
      /transparent/,
      `${name}/themes.xml statusBarColor should be transparent so the app bar fills it`,
    )
  }
})

check('the launch background follows the system dark-mode setting', () => {
  // The window background is what is on screen before the first Compose frame.
  // Hardcoding it dark flashes black on a light phone; hardcoding it light flashes
  // white on a dark one. It has to come in a night-qualified pair.
  assert.ok(
    themesNight,
    'values-night/themes.xml is missing, so the pre-Compose frame cannot follow ' +
      'the system dark-mode setting',
  )
  const light = /android:windowBackground">([^<]*)</.exec(themes)
  const dark = /android:windowBackground">([^<]*)</.exec(themesNight)
  assert.ok(light && dark, 'windowBackground must be set in both themes')
  assert.notEqual(
    light[1].toLowerCase(),
    dark[1].toLowerCase(),
    'the light and night window backgrounds are identical, so one of them is wrong',
  )
  assert.match(
    dark[1],
    /#FF0B1220/i,
    'the night window background should be the dark scheme background',
  )
})

check('the status bar icon appearance matches each background', () => {
  const light = /android:windowLightStatusBar"[^>]*>(\w+)</.exec(themes)
  const dark = /android:windowLightStatusBar"[^>]*>(\w+)</.exec(themesNight)
  assert.ok(light, 'values/themes.xml sets no windowLightStatusBar')
  assert.ok(dark, 'values-night/themes.xml sets no windowLightStatusBar')
  assert.equal(light[1], 'true', 'dark icons are needed over the light background')
  assert.equal(dark[1], 'false', 'light icons are needed over the dark background')
})

check('the input bar unions its insets instead of stacking them', () => {
  // `imePadding()` then `navigationBarsPadding()` adds the two, leaving a
  // navigation-bar-height gap between the input and an open keyboard.
  assert.match(
    chatScreen,
    /WindowInsets\.ime\.union\(WindowInsets\.navigationBars\)/,
    'the input bar must union the ime and navigation-bar insets',
  )
  assert.doesNotMatch(
    chatScreen,
    /\.imePadding\(\)/,
    'imePadding() stacks on top of the navigation-bar inset; union them instead',
  )
})

// ================================================ 2. the transcript would not scroll

console.log('\n=== auto-scroll yields to the user ===')

check('auto-scroll checks whether the list is pinned to the bottom', () => {
  assert.match(
    chatScreen,
    /pinnedToBottom/,
    'ChatScreen must track whether the newest content is in view',
  )
  assert.match(
    chatScreen,
    /last\.index\s*>=\s*info\.totalItemsCount\s*-\s*1/,
    'pinnedToBottom must test the last visible item against the item count',
  )
})

check('following is decided by the user, not by the streaming geometry', () => {
  // Gating auto-scroll on the geometry alone cannot work: streaming grows the last
  // item, so the content immediately extends past the viewport and the geometry
  // reports "not at the bottom" part-way through the turn. The follow flag must be
  // a separate piece of intent.
  assert.match(
    chatScreen,
    /var followNewest by remember \{ mutableStateOf\(true\) \}/,
    'the follow intent must be its own flag, separate from the geometry',
  )
  assert.match(
    chatScreen,
    /if \(!followNewest\) return@LaunchedEffect/,
    'the auto-scroll must gate on the follow intent',
  )
  // The gate must be the follow flag, not the geometry — that is the whole point.
  const effect = /LaunchedEffect\(rendered\.size[\s\S]*?\)\s*\{([\s\S]*?)\n    \}/.exec(chatScreen)
  assert.ok(effect, 'the auto-scroll LaunchedEffect was not found')
  assert.doesNotMatch(
    effect[1],
    /pinnedToBottom/,
    'auto-scroll must not gate on the live geometry, which streaming invalidates',
  )
})

check('a drag takes control immediately', () => {
  // This is the actual fix for "下滑时有概率无法下滑": the old code re-issued a
  // scroll animation on every token delta, and an in-flight animation cancels the
  // drag. Observing the drag start stops the fight the instant a finger lands.
  const startBranch = /is DragInteraction\.Start -> \{([\s\S]*?)\n                \}/.exec(chatScreen)
  assert.ok(startBranch, 'the drag-start branch was not found')
  assert.match(
    startBranch[1],
    /followNewest = false/,
    'a drag start must clear the follow flag at once',
  )
  assert.match(
    chatScreen,
    /listState\.interactionSource\.interactions\.collect/,
    'the drag must be observed through the list interaction source',
  )
})

check('following resumes only once the list settles at the bottom', () => {
  // A fling towards the bottom is still short of the end when the finger lifts, so
  // the position has to be read after the list stops moving.
  assert.match(
    chatScreen,
    /is DragInteraction\.Stop, is DragInteraction\.Cancel/,
    'the settle must be driven by the drag ending',
  )
  assert.match(
    chatScreen,
    /snapshotFlow \{ listState\.isScrollInProgress \}\.first \{ !it \}/,
    'the settle must wait for the fling to run out before deciding',
  )
  assert.match(
    chatScreen,
    /followNewest = pinnedToBottom/,
    'coming to rest at the bottom is what resumes following',
  )
})

check('our own scrolling can never switch following off', () => {
  // `isScrollInProgress` is also set by `animateScrollToItem`. A settle handler that
  // fired for every scroll would let auto-scroll disable itself: if the reply grew
  // while the animation ran, it would finish short of the new end, the settle would
  // read "not at the bottom", and following would stop until the user dragged.
  //
  // The structural test is *where* the settle is observed: it must sit inside the
  // drag-ended branch, not on the scroll state at large. Position is what
  // distinguishes the two, since the same expression appears either way.
  const dragEnd = chatScreen.indexOf('is DragInteraction.Stop, is DragInteraction.Cancel ->')
  const settle = chatScreen.indexOf('snapshotFlow { listState.isScrollInProgress }')
  assert.ok(dragEnd >= 0, 'the drag-ended branch was not found')
  assert.ok(settle >= 0, 'the settle observation was not found')
  assert.ok(
    settle > dragEnd,
    'the settle must be observed inside the drag-ended branch; observing it at the ' +
      'top level would let auto-scroll clear its own follow flag',
  )
  assert.equal(
    chatScreen.split('snapshotFlow { listState.isScrollInProgress }').length - 1,
    1,
    'there must be exactly one settle observation, and it must be the drag-gated one',
  )
  assert.doesNotMatch(
    chatScreen,
    /if \(scrolling\) followNewest = false/,
    'nothing but a drag may clear the follow flag',
  )
})

check('switching sessions resumes following', () => {
  assert.match(
    chatScreen,
    /LaunchedEffect\(state\.activeSessionId\) \{ followNewest = true \}/,
    'a new session must not inherit "scrolled up" from the previous one',
  )
})

check('the scroll targets the end of the last item, not its start', () => {
  // `scrollToItem(lastIndex)` aligns the item's TOP with the viewport top. With
  // turns merged, the last item can be far taller than the screen (the largest
  // turn had 830 steps), so aligning its top would scroll to the beginning of the
  // turn and push the newest text off the bottom.
  assert.match(
    chatScreen,
    /scrollOffset = PIN_TO_END_PX/,
    'the scroll must pass an offset so it lands at the end of the content',
  )
  const helper = /private suspend fun scrollToNewest[\s\S]*?\n\}/.exec(chatScreen)
  assert.ok(helper, 'scrollToNewest was not found')
  assert.match(
    helper[0],
    /animateScrollToItem\(index, scrollOffset = PIN_TO_END_PX\)/,
    'the animated scroll must also target the end',
  )
  assert.match(
    helper[0],
    /scrollToItem\(index, scrollOffset = PIN_TO_END_PX\)/,
    'the snapped scroll must also target the end',
  )
})

check('a growing message is snapped and only a new one animates', () => {
  // An animation is long enough to swallow a drag; running one per token delta is
  // what made the list feel stuck.
  assert.match(
    chatScreen,
    /animate\s*=\s*isNewMessage/,
    'only a newly arrived message should animate into view',
  )
})

check('the settle wait never blocks the gesture collector', () => {
  // If the wait ran inline, the collector would suspend, and a drag beginning during
  // the wait would sit in the flow's buffer — only to be seen after the settle had
  // already written `followNewest = pinnedToBottom`, re-enabling auto-scroll under a
  // finger that is actively dragging. So the wait must be launched separately and
  // cancelled by the next drag.
  const collector = /listState\.interactionSource\.interactions\.collect \{ interaction ->([\s\S]*?)\n        \}\n    \}/.exec(chatScreen)
  assert.ok(collector, 'the interaction collector was not found')
  const body = collector[1]
  assert.match(
    body,
    /settleJob = launch \{/,
    'the settle wait must be launched off the collector, not awaited inside it',
  )
  assert.match(
    body,
    /settleJob\?\.cancel\(\)/,
    'a new drag must cancel a pending settle, or the stale settle could re-enable following',
  )
  const startIdx = body.indexOf('is DragInteraction.Start ->')
  const stopIdx = body.indexOf('is DragInteraction.Stop')
  assert.ok(
    body.indexOf('settleJob?.cancel()', startIdx) < stopIdx,
    'the drag-start branch must cancel the pending settle',
  )
  // The wait must be *inside* the launched job. Checking the ordering rather than
  // indentation is what makes this robust: a regex for a leading-whitespace
  // `snapshotFlow` would also match the correctly nested line.
  const launchIdx = body.indexOf('settleJob = launch {')
  const waitIdx = body.indexOf('snapshotFlow {')
  assert.ok(
    waitIdx > launchIdx,
    'the settle wait must be the body of the launched job, not a bare statement in ' +
      'the collector',
  )
})

check('there is a way back to the newest message', () => {
  assert.match(chatScreen, /private fun JumpToLatest\(/, 'a jump-to-latest affordance is required once auto-scroll yields')
  assert.match(
    chatScreen,
    /if \(!pinnedToBottom && rendered\.isNotEmpty\(\)\)/,
    'the affordance should appear only when the user is scrolled up with content below',
  )
  // And it must use the end-aligned scroll, or it would land at the top of a long
  // merged turn instead of at its newest line.
  const jump = /onClick = \{\s*scope\.launch \{[\s\S]*?\}\s*\}/.exec(chatScreen)
  assert.ok(jump, 'the jump-to-latest click handler was not found')
  assert.match(
    jump[0],
    /scrollToNewest\(listState, rendered\.lastIndex, animate = true\)/,
    'jump-to-latest must share the end-aligned scroll helper',
  )
})

// ============================================================== 3. turn merging

console.log('\n=== one turn renders as one entry ===')

/**
 * Mirrors `SessionParser.mergeTurns` / `mergeSteps`.
 *
 * A message is `{ id, role, turn, blocks }` where a block is
 * `{ kind, text?, parts?, callId?, name?, input?, output?, failed? }`.
 */
function mergeTurns(messages) {
  if (messages.length < 2) return messages
  const out = []
  let i = 0
  while (i < messages.length) {
    const first = messages[i]
    const turn = first.turn ?? null
    // A group starts at an assistant settlement or a standalone tool card; both
    // are part of the turn they name.
    const startsTurn = first.role === 'ASSISTANT' || first.role === 'TOOL'
    if (turn === null || !startsTurn) {
      out.push(first)
      i++
      continue
    }
    let end = i + 1
    while (end < messages.length && (messages[end].turn ?? null) === turn) end++
    out.push(end - i === 1 ? first : mergeSteps(messages.slice(i, end)))
    i = end
  }
  return out
}

function mergeSteps(group) {
  let thinking = ''
  let parts = 0
  let thinkingAt = -1
  const blocks = []
  const seen = new Map()

  for (const message of group) {
    for (const block of message.blocks) {
      if (block.kind === 'reasoning') {
        if (thinkingAt < 0) {
          blocks.push({ kind: 'reasoning', text: '' })
          thinkingAt = blocks.length - 1
        }
        if (block.text && block.text.trim()) {
          if (thinking) thinking += '\n\n'
          thinking += block.text.trim()
        }
        parts += block.parts ?? 1
      } else if (block.kind === 'tool-call') {
        const at = block.callId ? seen.get(block.callId) : undefined
        if (at === undefined) {
          blocks.push(block)
          if (block.callId) seen.set(block.callId, blocks.length - 1)
        } else if (blocks[at].output == null && block.output != null) {
          blocks[at] = block
        }
      } else {
        blocks.push(block)
      }
    }
  }

  if (thinkingAt >= 0) blocks[thinkingAt] = { kind: 'reasoning', text: thinking, parts }

  return {
    ...group[0],
    role: 'ASSISTANT',
    blocks,
    streaming: group.some((m) => m.streaming),
  }
}

const step = (id, turn, blocks, extra = {}) => ({ id, role: 'ASSISTANT', turn, blocks, ...extra })
const reasoning = (text) => ({ kind: 'reasoning', text, parts: 1 })
const call = (callId, name, input = '{}', output = undefined) => ({
  kind: 'tool-call', callId, name, input, output,
})
const text = (t) => ({ kind: 'text', text: t })

check('the steps of one turn collapse into a single entry', () => {
  // The real shape: a turn is many steps, each settling its own assistant/message.
  const merged = mergeTurns([
    step('a-1', 1, [reasoning('first thought'), call('c1', 'read', '{"file_path":"a.kt"}')]),
    step('a-2', 1, [reasoning('second thought'), call('c2', 'pwsh', '{"command":"ls"}')]),
    step('a-3', 1, [reasoning('third thought'), text('done')]),
  ])
  assert.equal(merged.length, 1, 'one turn must render as one entry')
  assert.equal(merged[0].id, 'a-1', 'the merged entry keeps the first id, which the list keys on')
})

check('merged reasoning is one block that reports how many parts it has', () => {
  const merged = mergeTurns([
    step('a-1', 1, [reasoning('one')]),
    step('a-2', 1, [reasoning('two')]),
    step('a-3', 1, [reasoning('three')]),
  ])
  const thinking = merged[0].blocks.filter((b) => b.kind === 'reasoning')
  assert.equal(thinking.length, 1, 'the thinking of a turn is one section, not one per step')
  assert.equal(thinking[0].text, 'one\n\ntwo\n\nthree')
  assert.equal(thinking[0].parts, 3, 'the count is what tells the user it was merged')
})

check('tool calls keep their order and stay separate', () => {
  // Collapsing the steps would hide exactly the read/edit/run sequence that this
  // release exists to surface.
  const merged = mergeTurns([
    step('a-1', 1, [call('c1', 'read')]),
    step('a-2', 1, [call('c2', 'edit')]),
    step('a-3', 1, [call('c3', 'pwsh')]),
  ])
  assert.deepEqual(
    merged[0].blocks.map((b) => b.name),
    ['read', 'edit', 'pwsh'],
    'every step must survive, in the order it happened',
  )
})

check('a restated call does not become a second card', () => {
  const merged = mergeTurns([
    step('a-1', 1, [call('c1', 'read', '{"file_path":"a.kt"}')]),
    step('a-2', 1, [call('c1', 'read', '{"file_path":"a.kt"}')]),
  ])
  assert.equal(merged[0].blocks.length, 1, 'the same callId must not render twice')
})

check('a restated call never erases a result that already landed', () => {
  // A later step can repeat a call whose result arrived in between; keeping the
  // newer copy blindly would blank the output the user is looking at.
  const merged = mergeTurns([
    step('a-1', 1, [call('c1', 'read', '{}', 'file contents')]),
    step('a-2', 1, [call('c1', 'read', '{}')]),
  ])
  assert.equal(merged[0].blocks[0].output, 'file contents')
})

check('text blocks are not concatenated', () => {
  // Intermediate narration and the final answer are different things.
  const merged = mergeTurns([
    step('a-1', 1, [text('let me look')]),
    step('a-2', 1, [text('the answer is 42')]),
  ])
  assert.deepEqual(
    merged[0].blocks.map((b) => b.text),
    ['let me look', 'the answer is 42'],
    'narration and the answer must stay distinct paragraphs',
  )
})

check('a row without a turn ends the group', () => {
  // This is what keeps `llm/retry` and `approval/asked` visible: they carry no
  // turn, so merging across them is structurally impossible. 201 of 313 real turns
  // were correctly cut by an `approval/asked`.
  const merged = mergeTurns([
    step('a-1', 1, [reasoning('before')]),
    { id: 'appr-9', role: 'SYSTEM', turn: null, blocks: [{ kind: 'notice', text: '等待批准：pwsh' }] },
    step('a-2', 1, [reasoning('after')]),
  ])
  assert.equal(merged.length, 3, 'the notice must split the turn, not be swallowed')
  assert.equal(merged[1].role, 'SYSTEM')
  assert.equal(merged[0].blocks[0].parts, 1)
  assert.equal(merged[2].blocks[0].parts, 1)
})

check('different turns do not merge into each other', () => {
  const merged = mergeTurns([
    step('a-1', 1, [text('answer one')]),
    step('a-2', 2, [text('answer two')]),
  ])
  assert.equal(merged.length, 2, 'two turns are two responses')
})

check('an unlabelled turn is left alone rather than guessed at', () => {
  const merged = mergeTurns([
    step('a-1', null, [text('one')]),
    step('a-2', null, [text('two')]),
  ])
  assert.equal(merged.length, 2, 'without a turn there is no basis to merge')
})

check('a tool row with a turn joins that turn instead of splitting it', () => {
  // An orphan `tool/result` gets its own row; it is still part of its turn.
  const merged = mergeTurns([
    step('a-1', 1, [call('c1', 'read')]),
    { id: 'tr-2', role: 'TOOL', turn: 1, blocks: [call('c2', 'tool', '', 'orphan output')] },
    step('a-3', 1, [text('done')]),
  ])
  assert.equal(merged.length, 1, 'the orphan result belongs to the turn, not beside it')
  assert.deepEqual(
    merged[0].blocks.filter((b) => b.kind === 'tool-call').map((b) => b.callId),
    ['c1', 'c2'],
  )
  assert.equal(merged[0].blocks.at(-1).kind, 'text', 'the final answer stays last')
})

check('a merged group is drawn as an assistant turn', () => {
  const merged = mergeTurns([
    { id: 'tr-1', role: 'TOOL', turn: 1, blocks: [call('c1', 'read')] },
    step('a-2', 1, [text('done')]),
  ])
  assert.equal(merged.length, 1)
  assert.equal(merged[0].role, 'ASSISTANT', 'the first row must not decide how the turn is drawn')
})

check('the live draft joins the turn it belongs to', () => {
  // Without this a streaming reply renders as a second bubble beside the steps it
  // is still producing.
  const merged = mergeTurns([step('a-1', 1, [reasoning('thought'), call('c1', 'read')])])
  const draft = { id: 'live', role: 'ASSISTANT', turn: 1, streaming: true, blocks: [reasoning('still thinking'), text('partial')] }
  const last = merged[merged.length - 1]
  const joins = last.role === 'ASSISTANT' && last.turn != null && last.turn === draft.turn
  assert.ok(joins, 'the draft must be recognised as part of the last turn')
  const withDraft = [...merged.slice(0, -1), mergeSteps([last, draft])]
  assert.equal(withDraft.length, 1, 'the draft must not add a bubble')
  assert.equal(withDraft[0].streaming, true)
  assert.equal(withDraft[0].blocks.filter((b) => b.kind === 'reasoning')[0].parts, 2)
})

check('the Kotlin merge is actually wired into the transcript', () => {
  assert.match(
    sessionParser,
    /fun mergeTurns\(messages: List<Message>\)/,
    'SessionParser.mergeTurns is missing',
  )
  assert.match(
    chatScreen,
    /SessionParser\.mergeTurns\(state\.messages\)/,
    'ChatScreen must merge the durable transcript',
  )
  assert.match(
    chatScreen,
    /SessionParser\.withDraft\(merged, state\.liveDraft\)/,
    'the live draft must be joined through withDraft, not appended',
  )
})

check('the durable merge is remembered apart from the draft', () => {
  // Re-merging the whole transcript per token delta rebuilds every finished turn's
  // reasoning text tens of times a second.
  assert.match(
    chatScreen,
    /remember\(state\.messages\)\s*\{\s*SessionParser\.mergeTurns/,
    'mergeTurns must be remembered against state.messages alone',
  )
})

check('the parser records the turn on the events that carry it', () => {
  assert.match(
    sessionParser,
    /data\.optInt\("turn", -1\)\.takeIf \{ it >= 0 \}/,
    'assistant/message must record its turn',
  )
  const turnWrites = sessionParser.match(/optInt\("turn", -1\)/g) ?? []
  assert.ok(
    turnWrites.length >= 3,
    'the turn must be recorded on assistant messages and on both standalone ' +
      `tool rows; found ${turnWrites.length} reads`,
  )
})

// ============================================================ 4. tool call labels

console.log('\n=== tool calls are described, not just named ===')

/**
 * Mirrors `ToolLabel`.
 *
 * The real tool names and their argument keys are taken from the 5041 `tool/call`
 * events in 41 session logs, so the mapping is checked against what actually
 * appears rather than what the vocabulary might contain.
 */
const ACTIONS = {
  read: '读取', read_image: '查看图片', write: '写入', edit: '编辑',
  pwsh: '运行命令', pash: '运行命令',
  grep: '搜索内容', glob: '查找文件', web_fetch: '抓取网页', web_search: '网络搜索',
}

function tailPath(p, segments = 2) {
  if (!p) return ''
  const parts = p.replace(/\\/g, '/').replace(/\/+$/, '').split('/').filter(Boolean)
  if (!parts.length) return p
  return parts.slice(-segments).join('/')
}

function shorten(s, max) {
  const flat = (s ?? '').replace(/\s+/g, ' ').trim()
  return flat.length <= max ? flat : `${flat.slice(0, max - 1).trimEnd()}…`
}

function target(name, args) {
  const a = args ?? {}
  switch (name) {
    case 'read': case 'read_image': case 'write': case 'edit':
      return tailPath(a.file_path)
    case 'pwsh': case 'pash':
      return shorten(a.command, 90)
    case 'grep': {
      const pattern = a.pattern ?? ''
      const where = a.include ?? a.path ?? ''
      if (!pattern) return ''
      return where ? `${shorten(pattern, 48)} · ${tailPath(where, 1)}` : shorten(pattern, 60)
    }
    case 'glob': return shorten(a.pattern, 60)
    case 'web_fetch': return shorten(a.url, 80)
    case 'web_search': return shorten((a.queries ?? []).join(' / '), 80)
    default: return ''
  }
}

const describe = (name, args) => {
  const detail = target(name, args)
  const action = ACTIONS[name] ?? name
  return detail ? `${action} ${detail}` : action
}

check('a shell call shows the command, not the tool name', () => {
  // The real 1.1.4 case. Printing `pwsh` named the mechanism and hid the act.
  const line = describe('pwsh', {
    command: 'Get-ChildItem -Force E:\\deepseekworkspace | Select-Object Mode,Length,Name',
    description: 'List workspace root',
  })
  assert.match(line, /^运行命令 /)
  assert.match(line, /Get-ChildItem/, 'the command itself is the point')
  assert.doesNotMatch(line, /pwsh/, 'the identifier is not the description')
})

check('a file call shows the file, trimmed to its tail', () => {
  const line = describe('read', { file_path: 'E:\\deepseekworkspace\\dsh-android\\app-project\\app\\src\\main\\java\\ai\\deepseek\\dshmobile\\ui\\ChatScreen.kt', limit: 200 })
  assert.match(line, /^读取 /)
  assert.match(line, /ui\/ChatScreen\.kt$/, 'the tail identifies the file; the prefix is noise')
  assert.doesNotMatch(line, /E:\\/, 'the absolute prefix must not be printed')
})

check('the label is the useful argument for the tool, not the first string', () => {
  // A generic "first string argument" scan would print the `description` for a
  // shell call and hide the command.
  const shell = describe('pwsh', { command: 'ls', description: 'List the files here' })
  assert.match(shell, /运行命令 ls$/)
  assert.doesNotMatch(shell, /List the files here/)
})

check('every real tool name has a Chinese action or falls back to itself', () => {
  // The 26 names observed across 41 logs. Kotlin groups several names onto one
  // arm (`"pwsh", "pash", "shell" -> "运行命令"`), so the arms are parsed rather
  // than matched one by one — a per-name regex would miss every grouped case.
  const observed = [
    'pwsh', 'read', 'edit', 'write', 'grep', 'read_image', 'web_fetch', 'job_output',
    'todo_write', 'list_agents', 'job_kill', 'web_search', 'glob', 'present',
    'load_workspace_dependencies', 'ask_user_question', 'send_message', 'job_list',
    'create_goal', 'skill', 'subagent', 'get_goal', 'update_goal', 'pash',
    'subagent_fork', 'interrupt_agent',
  ]
  const actionBody = /fun action\(name: String\): String = when \(name\) \{([\s\S]*?)\n    \}/.exec(toolLabel)
  assert.ok(actionBody, 'ToolLabel.action was not found')
  const covered = new Set()
  for (const arm of actionBody[1].split('\n')) {
    const arrow = arm.indexOf('->')
    if (arrow < 0) continue
    for (const m of arm.slice(0, arrow).matchAll(/"([a-z_]+)"/g)) covered.add(m[1])
  }
  const missing = observed.filter((n) => !covered.has(n))
  assert.deepEqual(missing, [], `ToolLabel.action has no case for: ${missing.join(', ')}`)
})

check('an unknown tool degrades to its own name rather than to nothing', () => {
  // `else -> name.ifBlank { "工具" }` is what stops a new plugin's tool from
  // rendering as an empty header.
  assert.match(toolLabel, /else -> name\.ifBlank \{ "工具" \}/)
  assert.match(
    messageBubble,
    /ToolLabel\.describe\(block\.name, block\.input\)\.ifBlank \{ block\.name \}/,
    'the bubble must fall back to the raw name',
  )
})

check('a nested arguments object is unwrapped', () => {
  // 10 real `web_fetch` calls nest their arguments one level deeper.
  assert.match(
    toolLabel,
    /optJSONObject\("arguments"\)/,
    'the nested {"arguments": {...}, "name": ...} form must be unwrapped',
  )
})

check('the tool card reports whether the step is still running', () => {
  assert.match(
    messageBubble,
    /val running = block\.output == null && !block\.failed/,
    'a step with no result yet must be distinguishable from a finished one',
  )
  assert.match(
    messageBubble,
    /running -> Icons\.Default\.Autorenew/,
    'a running step needs its own icon',
  )
})

check('a long argument is truncated rather than allowed to overflow', () => {
  assert.match(toolLabel, /private fun shorten\(text: String, max: Int\)/, 'shorten() is required')
  assert.match(messageBubble, /maxLines = 2/, 'the description must be bounded in height')
})

// ================================================= 5. the false "连接超时" report

console.log('\n=== a timeout says which timeout it was ===')

/**
 * Mirrors `TransportErrors.message` for the timeout family.
 *
 * The point of the check is not the exact prose — it is that the three failures
 * below stop sharing one sentence. They are constructed from the *real* messages
 * the stack produces:
 *
 *   - `sent ping but didn't receive pong within 20000ms (after 0 successful
 *     ping/pongs)` — OkHttp's `RealWebSocket.writePingFrame`
 *   - `connect timed out` — `Socket.connect(endpoint, timeout)` via `IoBridge`
 *   - `timeout` / `Read timed out` — okio's `AsyncTimeout`, and the platform's
 *     `SocketTimeoutException` on a read
 */
function timeoutWording(exception) {
  const chain = [exception]
  const hasPong = chain.some((e) => /pong/.test(e.message ?? ''))
  const hasConnect = chain.some(
    (e) => /connect/i.test(e.message ?? '') && !/pong/.test(e.message ?? ''),
  )
  if (hasPong) return '心跳超时'
  if (hasConnect) return '连接超时'
  return '服务端超时'
}

check('a stalled mux heartbeat is not reported as an unreachable desktop', () => {
  // This is the reported bug. The heartbeat message mentions neither `connect`
  // nor a read, so it was indistinguishable from a dead host and produced
  // "连接…超时。请确认电脑上的 dsh-mobile-connect 仍在运行…" — advice to go check a
  // desktop the phone was, at that moment, still connected to.
  const heartbeat = new Error(
    "sent ping but didn't receive pong within 20000ms (after 0 successful ping/pongs)",
  )
  assert.equal(timeoutWording(heartbeat), '心跳超时')
})

check('a slow desktop is not reported as a broken network', () => {
  // `session/modelCatalog` asks every provider for its models, so it is the one
  // unary call that legitimately takes tens of seconds. The socket connected —
  // the address, the Wi-Fi and the plugin are all proven good — and telling the
  // user otherwise sends them to fix something that is not broken.
  assert.equal(timeoutWording(new Error('timeout')), '服务端超时')
  assert.equal(timeoutWording(new Error('Read timed out')), '服务端超时')
})

check('only a real connect timeout blames the network', () => {
  // `IoBridge.connect` is the sole producer of this wording, and it is the only
  // case where the desktop was never reached.
  assert.equal(timeoutWording(new Error('connect timed out')), '连接超时')
})

check('the three timeout kinds produce three different sentences', () => {
  // The regression is a *collapse*, so the check has to be about distinctness.
  // Asserting each sentence individually would still pass if two of them were
  // later rewritten to the same text.
  const kinds = [
    new Error("sent ping but didn't receive pong within 20000ms"),
    new Error('connect timed out'),
    new Error('Read timed out'),
  ].map(timeoutWording)
  assert.equal(
    new Set(kinds).size,
    3,
    `the three timeout causes must stay distinguishable, got: ${kinds.join(', ')}`,
  )
})

check('the Kotlin mapper really branches on the ping wording', () => {
  const body = functionBody(transportErrors, 'message')
  assert.match(body, /isHeartbeatStall\(/, 'message() must test for a heartbeat stall')
  assert.match(body, /isConnectTimeout\(/, 'message() must test for a connect timeout')
  const stall = functionBody(transportErrors, 'isHeartbeatStall')
  assert.match(stall, /pong/, 'the heartbeat test must key on the pong wording')
})

check('the read timeout is not misclassified as a connect timeout', () => {
  // `isConnectTimeout` keys on the word `connect`, and the *heartbeat* message is
  // also a `SocketTimeoutException` that must not match it. Ordering is what keeps
  // them apart, so both the ordering and the wording test are pinned.
  const heartbeat = functionBody(transportErrors, 'isHeartbeatStall')
  const connect = functionBody(transportErrors, 'isConnectTimeout')
  assert.match(connect, /connect/i, 'isConnectTimeout must key on the connect wording')
  assert.doesNotMatch(
    connect,
    /pong/,
    'isConnectTimeout must not claim the heartbeat message; that is a different failure',
  )
  assert.ok(heartbeat.length > 0 && connect.length > 0)
})

// ============================================ 6. the unary calls are bounded

console.log('\n=== a unary call cannot wait forever ===')

check('the unary client has a read timeout and the stream client does not', () => {
  // `readTimeout(0)` is *infinite*, which is right for the mux socket and wrong
  // for everything else. Before this, the only timeout the RPC path could ever
  // report was a connect timeout — which is why the one failure it could name was
  // the one that had not happened.
  assert.match(
    dshClient,
    /readTimeout\(0,\s*TimeUnit\.MILLISECONDS\)/,
    'the mux client must keep an infinite read timeout; streams are long-lived',
  )
  assert.match(
    dshClient,
    /rpcHttp[\s\S]{0,400}?readTimeout\(/,
    'there must be a second client whose read timeout is bounded',
  )
  assert.match(
    dshClient,
    /http\.newBuilder\(\)/,
    'the bounded client must be derived from the first so the pool and cookie jar are shared',
  )
})

check('the unary RPC uses the bounded client, not the infinite one', () => {
  // The whole fix is worthless if `rpc()` still calls the stream client.
  const body = functionBody(dshClient, 'executeUnary')
  assert.match(
    body,
    /rpcHttp\.newCall\(/,
    'the unary path must send through the bounded client',
  )
  assert.doesNotMatch(
    body,
    /[^c]http\.newCall\(/,
    'the unary path must not send through the infinite-read client',
  )
})

check('a connect timeout is retried, and nothing else is', () => {
  // Only a connect timeout is safe to repeat: the request never reached the
  // desktop, so `session/prompt` cannot run twice. This mirrors OkHttp's own
  // `isRecoverable` (`SocketTimeoutException && !requestSendStarted`).
  const body = functionBody(dshClient, 'executeUnary')
  assert.match(
    body,
    /TransportErrors\.isConnectTimeout\(/,
    'the retry must be gated on the shared connect-timeout predicate',
  )
  assert.match(
    body,
    /UNARY_CONNECT_RETRIES/,
    'the retry count must be a named bound, not an inline literal',
  )
  // The retry budget is what stops a genuinely dead desktop from being hammered.
  const retries = /UNARY_CONNECT_RETRIES\s*=\s*(\d+)/.exec(dshClient)
  assert.ok(retries, 'UNARY_CONNECT_RETRIES must be defined')
  assert.ok(
    Number(retries[1]) <= 2,
    `a connect retry budget of ${retries[1]} keeps the user waiting for an ` +
      'unreachable desktop; the failure it rides out resolves in under a second',
  )
})

check('the retry decision is not re-derived in two places', () => {
  // The app may only repeat a request it has *proven* never reached the desktop.
  // Two independent copies of that judgement is how a retry eventually
  // double-applies a prompt, so the predicate is public and shared.
  assert.match(
    transportErrors,
    /fun isConnectTimeout\(t: Throwable\)/,
    'TransportErrors must expose the connect-timeout predicate',
  )
})

check('the model switch does not report a cancellation as a failure', () => {
  // `catch (t: Throwable)` swallowed the CancellationException raised when the
  // view model is cleared and turned it into a banner the user never caused. Its
  // sibling jobs already rethrow.
  const body = functionBody(viewModel, 'selectRemoteModel')
  assert.match(
    body,
    /catch \(e: kotlinx\.coroutines\.CancellationException\)/,
    'selectRemoteModel must rethrow cancellation rather than report it',
  )
})

check('the model picker shows the failure instead of blaming the account', () => {
  // A failed catalog load left the list empty, and the empty state asserted
  // "请确认桌面端已登录账号或配置了 API Key" — sending the user to fix an account
  // that was fine while the real reason sat in a banner behind the dialog.
  const body = functionBody(pickers, 'ModelPickerDialog')
  assert.match(
    body,
    /error\s*\?\:\s*"没有可用的模型/,
    'the empty state must prefer the real error over the account advice',
  )
  assert.match(appShell, /error = state\.error/, 'AppShell must pass the error in')
})

// ============================================== 7. scan-to-connect wiring

console.log('\n=== the pairing QR can be scanned in the app ===')

check('a scanner exists and uses the bundled capture activity', () => {
  assert.ok(qrScan, 'ui/QrScan.kt is missing')
  assert.match(qrScan, /ScanContract\(\)/, 'the scanner must use the library contract')
  assert.match(
    qrScan,
    /rememberLauncherForActivityResult/,
    'the scanner must launch through the Activity result API',
  )
  assert.match(qrScan, /ScanOptions\.QR_CODE/, 'only QR codes should be decoded')
})

check('the scanner does not double-request the camera permission', () => {
  // `CaptureManager` requests CAMERA itself. Asking here as well would prompt
  // twice, and a denial message shown here would be about a permission the
  // capture screen was about to request anyway.
  assert.doesNotMatch(
    qrScan,
    /RequestPermission/,
    'the capture activity owns the camera permission; do not request it here',
  )
})

check('the camera permission is declared but not required', () => {
  assert.match(
    manifest,
    /<uses-permission android:name="android\.permission\.CAMERA" \/>/,
    'the manifest must declare CAMERA',
  )
  // `required="true"` would have the store filter the app off every device
  // without a camera — which must still be able to pair by typing the code.
  const feature = /<uses-feature android:name="android\.hardware\.camera"[^>]*>/.exec(manifest)
  assert.ok(feature, 'the camera feature must be declared')
  assert.match(
    feature[0],
    /android:required="false"/,
    'a camera must not be required: pairing by hand has to keep working',
  )
})

check('the scanner dependency is declared', () => {
  assert.match(
    appGradle,
    /com\.journeyapps:zxing-android-embedded:/,
    'the QR scanner dependency must be declared',
  )
})

check('a scanned link reaches the same code path a deep link does', () => {
  // Scanning must not be a second, parallel implementation of pairing. Both
  // routes have to converge on `pairFromLink`, or they drift — one gets the
  // fixes the other misses.
  assert.match(
    settingsScreen,
    /rememberQrScanner \{ onPairLink\(it\) \}/,
    'the scan result must be handed to onPairLink',
  )
  assert.match(
    mainActivity,
    /onPairLink = \{ pendingPairLink = it \}/,
    'a scanned link must be routed through the same pendingPairLink slot as a deep link',
  )
  assert.match(
    mainActivity,
    /vm\.pairFromLink\(link\)/,
    'the shared slot must still be consumed by pairFromLink',
  )
  assert.match(appShell, /onPairLink = onPairLink/, 'AppShell must forward the callback')
  assert.match(
    settingsScreen,
    /onPairLink: \(String\) -> Unit/,
    'SettingsScreen must accept the scanned link',
  )
})

check('the scan button is offered before the manual paths', () => {
  // Ordering is the point of the feature: scanning needs nothing typed, so it is
  // the one that should be found first.
  //
  // The manual anchor is the code *field*'s placeholder, not the word "配对码":
  // that word also appears in the introductory sentence above the button, so
  // searching for it would compare the button against prose.
  const scan = settingsScreen.indexOf('扫描二维码连接')
  const discover = settingsScreen.indexOf('搜索电脑')
  const manual = settingsScreen.indexOf('桌面端显示的 6 位数字')
  assert.ok(scan >= 0, 'the scan button was not found')
  assert.ok(discover >= 0, 'the LAN search button was not found')
  assert.ok(manual >= 0, 'the manual code field was not found')
  assert.ok(discover > scan, 'the scan button must come before the LAN search')
  assert.ok(manual > scan, 'the scan button must come before the manual code field')
})

check('a failed scan is reported where the user is standing', () => {
  // `pairFromLink` reports a bad QR by setting `state.error`, but Settings is a
  // different screen from the one that renders it, so the user was dropped back
  // on the pairing card with no explanation — which reads as a dead button.
  assert.match(
    settingsScreen,
    /state\.error\?\.let/,
    'the pairing card must render the current error',
  )
})

check('the scanner is registered outside the backend branches', () => {
  // `rememberLauncherForActivityResult` registers with the Activity result
  // registry; registering it inside a conditional composable makes the
  // registration come and go with the branch.
  const hoist = settingsScreen.indexOf('val scanQr = rememberQrScanner')
  const remote = settingsScreen.indexOf('if (state.backend == Backend.REMOTE)')
  assert.ok(hoist >= 0, 'the scanner launcher was not found')
  assert.ok(
    hoist < remote,
    'the scanner must be hoisted above the backend branches, not created inside one',
  )
})

check('a cancelled scan stays silent', () => {
  // Backing out of the capture screen returns null contents. Treating that as a
  // failure would flash an error every time the user changes their mind.
  assert.match(
    qrScan,
    /result\.contents\?\.trim\(\)\?\.takeIf \{ it\.isNotEmpty\(\) \}\?\.let\(onResult\)/,
    'a null or blank scan result must not be reported as a failure',
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
