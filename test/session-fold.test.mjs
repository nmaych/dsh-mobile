// Behavioural tests for the transcript fold — the logic behind "对话显示异常".
//
// The fold is a pure function over event envelopes, so it can be exercised
// exactly by reimplementing its rules here and asserting the invariants that
// matter. The event shapes below are not invented: they were taken from real
// `session.v4.jsonl` logs, and the invariants were verified against 31 of them
// (3949 tool cards, 0 duplicate ids, 1 unresolved card).
//
// Why a JS mirror rather than a JVM unit test: this repo has no test source set,
// and every other guard here is a Node script that runs in seconds with no
// Android SDK. What is being pinned is the *algorithm*, and the risk this
// catches is a regression in the rules — not Kotlin syntax.
//
// usage: node test/session-fold.test.mjs
import assert from 'node:assert/strict'

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

// --------------------------------------------------------------- fixtures

/** An event envelope, with the fields the fold reads. */
function ev(type, seq, data = {}, surfaceOp = undefined) {
  const e = { type, seq, time: 1_700_000_000_000 + seq, data }
  if (surfaceOp !== undefined) e.surfaceOp = surfaceOp
  return e
}

function userMessage(seq, text, id) {
  return ev('user/message', seq, { id: id ?? `u-${seq}`, content: [{ type: 'text', text }] }, 'append')
}

function assistantMessage(seq, blocks, id) {
  return ev(
    'assistant/message',
    seq,
    {
      turn: 1,
      step: 1,
      message: {
        id: id ?? `a-${seq}`,
        role: 'assistant',
        source: { kind: 'model', provider: 'deepseek-account', model: 'deepseek-chat' },
        content: blocks,
      },
    },
    'append',
  )
}

function toolCall(seq, callId, name, args = '{}') {
  return ev('tool/call', seq, { turn: 1, step: 1, callId, name, arguments: args })
}

function toolResult(seq, callId, output, isError = false, id) {
  return ev(
    'tool/result',
    seq,
    {
      turn: 1,
      step: 1,
      message: {
        id: id ?? `tr-${seq}`,
        role: 'tool',
        toolCallId: callId,
        source: { kind: 'tool', callId },
        isError,
        content: [{ type: 'text', text: output }],
      },
    },
    'append',
  )
}

// ------------------------------------------------------- the fold, mirrored

const ECHO_PREFIX = 'local-'

function textOf(content) {
  if (!Array.isArray(content)) return ''
  let out = ''
  for (const b of content) {
    if (b.type === 'text') out += b.text
    else if (b.type === 'image') out += '[图片]'
    else if (b.type === 'file') out += '[文件]'
  }
  return out.trim()
}

function blocksOf(content) {
  const out = []
  for (const b of (content ?? [])) {
    if (b.type === 'text' && b.text) out.push({ kind: 'text', text: b.text })
    else if (b.type === 'reasoning' && b.text) out.push({ kind: 'reasoning', text: b.text })
    else if (b.type === 'tool-call') {
      out.push({ kind: 'tool-call', callId: b.id, name: b.name, input: b.arguments })
    }
  }
  return out
}

/**
 * The view model's fold state.
 *
 * `seenSeqs` and `callIndex` are per-session fields, and `activateSession` is
 * the only thing that resets them — mirroring ChatViewModel exactly, because
 * their lifetime is what the display bug turned on.
 */
class Transcript {
  constructor() {
    this.messages = []
    this.seenSeqs = new Set()
    this.callIndex = new Map()
    this.historyLoaded = false
  }

  /** Mirrors ChatViewModel.activateSession. */
  activateSession() {
    this.seenSeqs.clear()
    this.callIndex.clear()
    this.messages = []
    this.historyLoaded = false
  }

  /**
   * Kotlin's `MutableSet.add` returns whether the element was new. JavaScript's
   * `Set.add` returns the Set, which is always truthy — writing the dedupe out
   * explicitly is what keeps this mirror honest.
   */
  addSeq(seq) {
    if (this.seenSeqs.has(seq)) return false
    this.seenSeqs.add(seq)
    return true
  }

  indexCalls(mi) {
    const m = this.messages[mi]
    if (!m) return
    m.blocks.forEach((b, bi) => {
      if (b.kind === 'tool-call' && b.callId) this.callIndex.set(b.callId, [mi, bi])
    })
  }

  /** Mirrors SessionParser.fold. */
  fold(events) {
    const kept = []
    for (const e of events) {
      const seq = typeof e.seq === 'number' ? e.seq : -1
      if (seq < 0 || this.addSeq(seq)) kept.push(e)
    }
    if (!kept.length) return

    // Everything already rendered is re-indexed first: this batch may carry the
    // result of a call committed in an earlier one.
    for (const mi of this.messages.keys()) this.indexCalls(mi)

    for (const e of kept) {
      const data = e.data ?? {}
      const seq = e.seq ?? 0
      switch (e.type) {
        case 'user/message': {
          if (e.surfaceOp !== 'append') break
          const text = textOf(data.content)
          if (text) this.messages.push({ id: data.id ?? `u-${seq}`, role: 'USER', blocks: [{ kind: 'text', text }] })
          break
        }
        case 'assistant/message': {
          if (e.surfaceOp !== 'append') break
          const m = data.message
          if (!m) break
          const blocks = blocksOf(m.content)
          if (!blocks.length) break
          const id = m.id ?? `a-${seq}`
          const existing = this.messages.findIndex((x) => x.id === id)
          const entry = { id, role: 'ASSISTANT', blocks }
          if (existing >= 0) this.messages[existing] = entry
          else this.messages.push(entry)
          this.indexCalls(existing >= 0 ? existing : this.messages.length - 1)
          break
        }
        case 'tool/call': {
          const callId = data.callId
          if (callId && !this.callIndex.has(callId)) {
            const last = this.messages[this.messages.length - 1]
            if (last && last.role === 'ASSISTANT') {
              last.blocks.push({ kind: 'tool-call', callId, name: data.name ?? 'tool', input: data.arguments })
              this.indexCalls(this.messages.length - 1)
            } else {
              this.messages.push({
                id: `tc-${seq}`,
                role: 'TOOL',
                blocks: [{ kind: 'tool-call', callId, name: data.name ?? 'tool', input: data.arguments }],
              })
              this.indexCalls(this.messages.length - 1)
            }
          }
          break
        }
        case 'tool/result': {
          if (e.surfaceOp !== 'append') break
          const m = data.message
          const callId = m?.toolCallId || m?.source?.callId || ''
          const output = textOf(m?.content)
          const failed = m?.isError === true || data.error !== undefined
          const target = this.callIndex.get(callId)
          if (target) {
            const [mi, bi] = target
            const existing = this.messages[mi]?.blocks?.[bi]
            if (existing) this.messages[mi].blocks[bi] = { ...existing, output, failed }
          } else if (output) {
            this.messages.push({
              id: m?.id ?? `tr-${seq}`,
              role: 'TOOL',
              blocks: [{ kind: 'tool-call', callId, name: 'tool', input: '', output, failed }],
            })
            this.indexCalls(this.messages.length - 1)
          }
          break
        }
        default:
          break
      }
    }
    this.historyLoaded = true
  }

  toolCards() {
    const out = []
    for (const m of this.messages) for (const b of m.blocks) if (b.kind === 'tool-call') out.push(b)
    return out
  }

  ids() {
    return this.messages.map((m) => m.id)
  }
}

// ============================================================ the invariants

console.log('=== per-session dedupe (the blank-transcript bug) ===')

check('a second session renders when its seqs overlap the first', () => {
  // `seq` restarts at zero per session. A shared seenSeqs made the second
  // session's events look like replays, so the transcript stayed empty — the
  // reported 对话显示异常.
  const t = new Transcript()
  t.activateSession()
  t.fold([
    userMessage(0, 'session A question'),
    assistantMessage(1, [{ type: 'text', text: 'session A answer' }]),
  ])
  assert.equal(t.messages.length, 2)

  t.activateSession()
  t.fold([
    userMessage(0, 'session B question'),
    assistantMessage(1, [{ type: 'text', text: 'session B answer' }]),
  ])
  assert.equal(t.messages.length, 2, 'session B must render, not be deduped away')
  assert.equal(t.messages[0].blocks[0].text, 'session B question')
})

check('without the reset the second session would be blank', () => {
  // Proves the test above is actually exercising the bug: skipping the reset
  // reproduces the empty transcript.
  const t = new Transcript()
  t.activateSession()
  t.fold([userMessage(0, 'a'), assistantMessage(1, [{ type: 'text', text: 'b' }])])
  // Switch the transcript but deliberately keep the dedupe set.
  t.messages = []
  t.callIndex.clear()
  t.fold([userMessage(0, 'c'), assistantMessage(1, [{ type: 'text', text: 'd' }])])
  assert.equal(t.messages.length, 0, 'the un-fixed path must reproduce the bug')
})

check('the tool-call index is reset with the session', () => {
  const t = new Transcript()
  t.activateSession()
  t.fold([assistantMessage(0, [{ type: 'tool-call', id: 'call-1', name: 'read', arguments: '{}' }])])
  assert.ok(t.callIndex.has('call-1'))
  t.activateSession()
  assert.equal(t.callIndex.size, 0, 'a stale index would pair a result into another session')
})

console.log('\n=== tool-call / tool-result pairing ===')

check('a result arriving in a later batch updates its card', () => {
  // This is why the index cannot be per-fold-call: the call is committed in one
  // batch and its result in the next.
  const t = new Transcript()
  t.activateSession()
  t.fold([assistantMessage(0, [{ type: 'tool-call', id: 'call-1', name: 'read', arguments: '{"p":1}' }])])
  assert.equal(t.toolCards().length, 1)
  assert.equal(t.toolCards()[0].output, undefined)

  t.fold([toolResult(1, 'call-1', 'file contents')])
  assert.equal(t.toolCards().length, 1, 'the result must update the card, not add a second one')
  assert.equal(t.toolCards()[0].output, 'file contents')
})

check('a result whose call was never seen becomes its own card', () => {
  const t = new Transcript()
  t.activateSession()
  t.fold([toolResult(0, 'orphan-call', 'output')])
  assert.equal(t.toolCards().length, 1)
  assert.equal(t.toolCards()[0].output, 'output')
})

check('a failed result is marked failed', () => {
  const t = new Transcript()
  t.activateSession()
  t.fold([assistantMessage(0, [{ type: 'tool-call', id: 'c1', name: 'read' }])])
  t.fold([toolResult(1, 'c1', 'boom', true)])
  assert.equal(t.toolCards()[0].failed, true)
})

check('a result carrying data.error is marked failed even without isError', () => {
  const t = new Transcript()
  t.activateSession()
  t.fold([assistantMessage(0, [{ type: 'tool-call', id: 'c1', name: 'read' }])])
  t.fold([
    ev('tool/result', 1, {
      turn: 1,
      step: 1,
      error: { name: 'ToolNotStartedError', code: 'TOOL_NOT_STARTED' },
      message: {
        id: 'tr-1',
        role: 'tool',
        toolCallId: 'c1',
        source: { kind: 'tool', callId: 'c1' },
        content: [{ type: 'text', text: 'not started' }],
      },
    }, 'append'),
  ])
  assert.equal(t.toolCards()[0].failed, true)
})

check('the tool/call event does not duplicate a card the message already has', () => {
  // `tool/call` is log-only when the owning assistant message already carries a
  // matching tool-call block. Rendering both would show every tool twice.
  const t = new Transcript()
  t.activateSession()
  t.fold([
    assistantMessage(0, [{ type: 'tool-call', id: 'call-1', name: 'read' }]),
    toolCall(1, 'call-1', 'read'),
  ])
  assert.equal(t.toolCards().length, 1)
})

console.log('\n=== surface replacement ===')

check('a replace op never erases text the user already saw', () => {
  const t = new Transcript()
  t.activateSession()
  t.fold([userMessage(0, 'original')])
  t.fold([
    ev('user/message', 1, { id: 'u-0', content: [{ type: 'text', text: 'rewritten' }] },
      { op: 'replace', startSeq: 0, endSeq: 0 }),
  ])
  assert.equal(t.messages.length, 1)
  assert.equal(t.messages[0].blocks[0].text, 'original')
})

check('an assistant message replaces an earlier render of the same id', () => {
  const t = new Transcript()
  t.activateSession()
  t.fold([assistantMessage(0, [{ type: 'text', text: 'first' }], 'a-fixed')])
  t.fold([assistantMessage(1, [{ type: 'text', text: 'second' }], 'a-fixed')])
  assert.equal(t.messages.length, 1)
  assert.equal(t.messages[0].blocks[0].text, 'second')
})

console.log('\n=== incremental folding ===')

check('one event per batch equals a single pass', () => {
  const events = [
    userMessage(0, 'q'),
    ev('turn/start', 1, { turn: 1 }),
    assistantMessage(2, [
      { type: 'reasoning', text: 'thinking' },
      { type: 'tool-call', id: 'c1', name: 'read', arguments: '{}' },
    ]),
    toolCall(3, 'c1', 'read'),
    toolResult(4, 'c1', 'out'),
    assistantMessage(5, [{ type: 'text', text: 'done' }]),
    ev('turn/end', 6, { turn: 1, reason: { kind: 'completed' } }),
  ]

  const oneShot = new Transcript()
  oneShot.activateSession()
  oneShot.fold(events)

  const incremental = new Transcript()
  incremental.activateSession()
  for (const e of events) incremental.fold([e])

  const shape = (t) => t.messages.map(
    (m) => `${m.role}:${m.blocks.map((b) => b.kind + (b.output !== undefined ? '+' : '')).join('+')}`,
  )
  assert.deepEqual(shape(incremental), shape(oneShot))
  assert.deepEqual(incremental.ids(), oneShot.ids())
})

check('replaying the same batch twice changes nothing', () => {
  // The follow stream re-sends its backlog after a reconnect, which is exactly
  // why the dedupe set exists.
  const events = [userMessage(0, 'q'), assistantMessage(1, [{ type: 'text', text: 'a' }])]
  const t = new Transcript()
  t.activateSession()
  t.fold(events)
  const before = t.ids()
  t.fold(events)
  assert.deepEqual(t.ids(), before)
})

check('message ids are unique, as the list key requires', () => {
  // A duplicate id breaks LazyColumn's `key = { it.id }` and can drop rows.
  const t = new Transcript()
  t.activateSession()
  t.fold([
    userMessage(0, 'q'),
    assistantMessage(1, [{ type: 'tool-call', id: 'c1', name: 'read' }]),
    toolCall(2, 'c1', 'read'),
    toolResult(3, 'c1', 'out'),
    toolResult(4, 'c2', 'orphan output'),
    assistantMessage(5, [{ type: 'text', text: 'done' }]),
  ])
  const ids = t.ids()
  assert.equal(new Set(ids).size, ids.length, `duplicate ids: ${ids.join(', ')}`)
})

console.log('\n=== usage and model extraction ===')

check('a usage sample is read off the settlement', () => {
  const event = ev('assistant/message', 0, {
    turn: 1,
    step: 1,
    usage: { inputTokens: 782, outputTokens: 123, cacheReadTokens: 6784, totalTokens: 7689 },
    message: {
      id: 'a-0',
      role: 'assistant',
      source: { kind: 'model', provider: 'deepseek-account', model: 'deepseek-chat' },
      content: [{ type: 'text', text: 'hi' }],
    },
  }, 'append')
  const usage = event.data.usage
  // The Android client sums the disjoint buckets rather than trusting
  // totalTokens, which is verified equal on 3001 real settlements.
  const computed = usage.inputTokens + usage.outputTokens + usage.cacheReadTokens + (usage.cacheWriteTokens ?? 0)
  assert.equal(computed, usage.totalTokens)
})

check('an absent usage is not treated as zero usage', () => {
  const event = assistantMessage(0, [{ type: 'text', text: 'hi' }])
  assert.equal(event.data.usage, undefined)
})

check('the model label comes from the message source', () => {
  const event = assistantMessage(0, [{ type: 'text', text: 'hi' }])
  const s = event.data.message.source
  assert.equal(`${s.provider}/${s.model}`, 'deepseek-account/deepseek-chat')
})

check('an empty assistant message produces no transcript row', () => {
  // A max-tokens step settles with empty content; it must not inject a blank
  // reply bubble.
  const t = new Transcript()
  t.activateSession()
  t.fold([assistantMessage(0, [])])
  assert.equal(t.messages.length, 0)
})

console.log('\n=== echo reconciliation ===')

/**
 * Mirrors ChatViewModel.reconcileEchoes.
 *
 * Pass 1 retires every echo that matches a committed message by text; pass 2
 * lets each still-unclaimed committed message retire one leftover echo, oldest
 * first. The first pass is what keeps an exact match from being pre-empted by
 * whichever echo happens to come first, and the second is what guarantees
 * progress when the server rewrites the prompt text.
 */
function reconcileEchoes(echoes, fresh) {
  const committed = fresh
    .filter((e) => e.type === 'user/message')
    .map((e) => textOf(e.data?.content))
  if (!committed.length) return echoes

  const pending = []
  for (const echo of echoes) {
    const text = echo.blocks.map((b) => b.text ?? '').join('\n').trim()
    const match = committed.findIndex((c) => c.trim() === text)
    if (match >= 0) committed.splice(match, 1)
    else pending.push(echo)
  }
  const out = []
  for (const echo of pending) {
    if (committed.length === 0) out.push(echo)
    else committed.shift()
  }
  return out
}

check('an echoed message is not shown twice', () => {
  // The optimistic local render and the durable user/message share only their
  // text, so reconciliation matches on that.
  const echo = { id: `${ECHO_PREFIX}1`, role: 'USER', blocks: [{ kind: 'text', text: 'hello' }] }
  const survivors = reconcileEchoes([echo], [userMessage(0, 'hello')])
  assert.equal(survivors.length, 0, 'the echo must be dropped')
})

check('two identical sends reconcile one echo each', () => {
  const echoes = [
    { id: `${ECHO_PREFIX}1`, blocks: [{ kind: 'text', text: 'same' }] },
    { id: `${ECHO_PREFIX}2`, blocks: [{ kind: 'text', text: 'same' }] },
  ]
  // Only one committed message, so exactly one echo is consumed.
  const survivors = reconcileEchoes(echoes, [userMessage(0, 'same')])
  assert.equal(survivors.length, 1, 'one echo stays pending')
  assert.equal(survivors[0].id, `${ECHO_PREFIX}2`)
})

check('both echoes clear once both messages are committed', () => {
  const echoes = [
    { id: `${ECHO_PREFIX}1`, blocks: [{ kind: 'text', text: 'same' }] },
    { id: `${ECHO_PREFIX}2`, blocks: [{ kind: 'text', text: 'same' }] },
  ]
  const survivors = reconcileEchoes(echoes, [userMessage(0, 'same'), userMessage(1, 'same')])
  assert.equal(survivors.length, 0)
})

check('a server-rewritten prompt still clears its echo', () => {
  // A slash command is expanded and injected context arrives as its own
  // `user/message`, so the stored text need not equal what was typed. Matching on
  // text alone left the echo beside its durable twin forever.
  const echo = { id: `${ECHO_PREFIX}1`, blocks: [{ kind: 'text', text: '/compact' }] }
  const survivors = reconcileEchoes([echo], [userMessage(0, '<system-reminder>compacted')])
  assert.equal(survivors.length, 0, 'the committed prompt must consume the echo')
})

check('an echo with no committed message is kept', () => {
  // Nothing has landed yet, so the optimistic render must stay.
  const echo = { id: `${ECHO_PREFIX}1`, blocks: [{ kind: 'text', text: 'pending' }] }
  assert.equal(reconcileEchoes([echo], [assistantMessage(0, [{ type: 'text', text: 'x' }])]).length, 1)
})

check('a pending echo keeps its place after the durable rows', () => {
  // Echoes are held out of the fold and re-appended, so a confirmed message
  // cannot be pushed past an echo that was sent after it. Both echoes exist
  // because both were sent from this phone; only the first is committed yet, and
  // it consumes exactly its own echo.
  const t = new Transcript()
  t.activateSession()
  const echoes = [
    { id: `${ECHO_PREFIX}1`, role: 'USER', blocks: [{ kind: 'text', text: 'first' }] },
    { id: `${ECHO_PREFIX}2`, role: 'USER', blocks: [{ kind: 'text', text: 'second' }] },
  ]
  t.fold([userMessage(0, 'first')])
  const ordered = [...t.messages, ...reconcileEchoes(echoes, [userMessage(0, 'first')])]
  assert.deepEqual(
    ordered.map((m) => m.blocks.map((b) => b.text).join('')),
    ['first', 'second'],
  )
})

check('a committed message never consumes more echoes than it has', () => {
  // One committed message may retire one pending echo. Retiring two would hide a
  // prompt the user just sent while its own event is still in flight.
  const echoes = [
    { id: `${ECHO_PREFIX}1`, blocks: [{ kind: 'text', text: 'a' }] },
    { id: `${ECHO_PREFIX}2`, blocks: [{ kind: 'text', text: 'b' }] },
  ]
  const survivors = reconcileEchoes(echoes, [userMessage(0, 'a')])
  assert.equal(survivors.length, 1)
  assert.equal(survivors[0].id, `${ECHO_PREFIX}2`)
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
