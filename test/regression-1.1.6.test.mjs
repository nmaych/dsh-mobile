// Guards the 1.1.6 fixes, which are the same kind the Kotlin compiler cannot see:
//
//   1. The token chip sitting out of line with its neighbours in landscape. The
//      cause is a *layout default*: `FlowRow` aligns children to the top of the
//      line, and Material3 pads a clickable `Surface` out to a 48dp minimum
//      interactive size (centring its content) while a plain one stays one text
//      line tall. Every chip was therefore correct on its own and the row was
//      wrong. In portrait they wrap onto separate lines and it is invisible,
//      which is why it survived until someone looked at the phone sideways.
//
//   2. A "连接超时" reported while the connection was demonstrably healthy. Two
//      independent causes, and 1.1.4 fixed neither: the connect retry paused
//      400ms — less than the radio wake-up its own comment described, so the
//      retry re-sent into the same doze and failed identically; and a mux
//      failure's banner was never retracted, so the app kept asserting a timeout
//      after it had already reconnected.
//
//   3. A token readout that did not match the desktop and did not move during a
//      turn. The phone rendered its own invention (`↑209k (10.3M 缓存) ↓309k`,
//      lowercase k) while the desktop renders `{total} tok · 缓存命中 {p}%`, and
//      it was refreshed only at a turn boundary because the app polled a one-shot
//      RPC instead of subscribing to the projection stream the desktop uses.
//
//   4. `ask_user_question` being unanswerable. The tool call was visible in the
//      transcript but there was no way to reply, so a turn that asked a question
//      stalled until the desktop answered it. Answering requires claiming the
//      host's forwarded waterfall over `$events`, which is a different transport
//      from every other call in the app.
//
// The wire shapes here are not invented. They are read from the Host's own
// generated descriptors and from the desktop's reference client:
//
//   - `session/control` is a stream with **no parameters** and yields one
//     `{type:"baseline",value:{projections:{<id>:{asOfSeq,values}}}}` followed by
//     `{type:"projection",sessionId,key,value,seq}`.
//   - the forwarded-event stream is the gateway-internal endpoint `$events`, and
//     its payload must be *exactly* `{"args":{}}` — the gateway throws
//     `gateway/arguments-invalid` for any other shape.
//   - its frames are `ready`/`emit`/`waterfall`/`cancel`, and an answer is a
//     unary `$events/result` call carrying `{clientId,eventId,outcome}` where
//     `outcome` is `{kind:"result",value}` or `{kind:"next"}`.
//   - the desktop's `formatTokens` is `517 / 12.2K / 1.2M` with an **uppercase**
//     unit, one decimal only below 100, and `999999 -> 1000K`.
//
// usage: node test/regression-1.1.6.test.mjs
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

const chatScreen = src('ui/ChatScreen.kt')
const pickers = src('ui/components/Pickers.kt')
const dshClient = src('data/DshClient.kt')
const viewModel = src('ui/ChatViewModel.kt')
const appShell = src('ui/AppShell.kt')
const mainActivity = src('MainActivity.kt')
const userQuestion = src('data/UserQuestion.kt')
const questionDialog = src('ui/components/QuestionDialog.kt')
const transportErrors = src('net/TransportErrors.kt')
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

// =========================== 1. the chip strip lines up in landscape

console.log('=== the token chip lines up with its neighbours ===')

check('every chip in the strip is centred on the row cross axis', () => {
  // `FlowRow`'s default cross-axis alignment is TOP (verified against
  // foundation-layout's `CROSS_AXIS_ALIGNMENT_TOP`). That is only harmless while
  // every child measures the same height, and these do not: the model and
  // workspace chips are clickable `Surface`s, which Material3 pads out to a 48dp
  // minimum interactive size and centres the content inside, while the usage chip
  // is not clickable and stays one text line tall. Top-aligning two boxes of
  // different height puts their text at different y positions — the landscape
  // misalignment. So each chip must opt into centring.
  const row = functionBody(chatScreen, 'RemoteChipsRow')
  const aligns = row.match(/\.align\(\s*Alignment\.CenterVertically\s*\)/g) ?? []
  assert.ok(
    aligns.length >= 3,
    `all three chips (model, workspace, usage) must be centred on the cross axis, ` +
      `found ${aligns.length} align(CenterVertically) calls`,
  )
  // And the chip that needs it most is the one Material3 does *not* pad, so its
  // alignment is the one that was actually missing.
  const usageAt = row.indexOf('UsageChip(')
  assert.ok(usageAt >= 0, 'the usage chip must be in the strip')
  const usage = row.slice(usageAt)
  assert.match(
    usage,
    /Modifier\.align\(\s*Alignment\.CenterVertically\s*\)/,
    'the usage chip must be centred explicitly: it is the only non-clickable ' +
      'chip, so it is the only one Material3 does not pad to 48dp and centre for us',
  )
})

check('the strip explains why top alignment was wrong', () => {
  // The fix is a one-line modifier, and a future reader who sees "every chip
  // already has wrapContentHeight" would reasonably delete it. The reason has to
  // be written down next to it.
  const kdoc = chatScreen.slice(0, chatScreen.indexOf('fun RemoteChipsRow'))
  assert.match(
    kdoc,
    /minimumInteractiveComponentSize|48dp/,
    'the strip must record that Material3 enforces a 48dp minimum interactive ' +
      'size on the clickable chips, which is what made their heights differ',
  )
  assert.match(
    kdoc,
    /landscape|横屏/i,
    'the strip must record that the misalignment only shows in landscape, where ' +
      'the chips share one line instead of wrapping',
  )
})

check('the one-line guarantees the strip already had are intact', () => {
  // 1.1.3's fix and this one are both about this row; the new alignment must not
  // have replaced the height/width constraints.
  const row = functionBody(chatScreen, 'RemoteChipsRow')
  assert.match(row, /FlowRow\(/, 'the strip must still be a FlowRow')
  assert.doesNotMatch(
    row,
    /Spacer\(Modifier\.weight\(/,
    'a weighted spacer still starves the chips after it',
  )
  for (const [label, text] of [
    ['model chip', functionBody(pickers, 'ModelChip')],
    ['usage chip', functionBody(pickers, 'UsageChip')],
  ]) {
    assert.match(text, /wrapContentHeight\(\)/, `${label} must stay one line tall`)
  }
})

// ================== 2. a timeout is only reported when it really happened

console.log('\n=== a healthy connection does not report a timeout ===')

check('the connect retry actually spans the wake-up it exists for', () => {
  // This is the core of the spurious timeout, and 1.1.4 got the arithmetic
  // wrong. Its own comment said the failure it rides out — a dozing Wi-Fi radio —
  // "resolves in well under a second", and then it waited 400ms, which is *less*
  // than that. The retry therefore re-sent into the tail of the same doze and
  // failed identically, and the user was told the desktop was unreachable while
  // the mux socket proved it was not.
  const delay = /UNARY_RETRY_DELAY_MS\s*=\s*([\d_]+)L/.exec(dshClient)
  assert.ok(delay, 'UNARY_RETRY_DELAY_MS must be defined')
  const ms = Number(delay[1].replace(/_/g, ''))
  assert.ok(
    ms >= 1000,
    `the first retry pause is ${ms}ms, which is shorter than the radio wake-up ` +
      'the retry exists to ride out, so the retry cannot help and the user sees a ' +
      'timeout for a connection that was about to work',
  )
  const retries = /UNARY_CONNECT_RETRIES\s*=\s*(\d+)/.exec(dshClient)
  assert.ok(retries, 'UNARY_CONNECT_RETRIES must be defined')
  assert.ok(
    Number(retries[1]) >= 2,
    `a single retry (found ${retries[1]}) cannot cover a wake-up that is longer ` +
      'than the first pause; the second attempt is the one that succeeds',
  )
  // The pause must grow, or both attempts cluster at the start of the window.
  const body = functionBody(dshClient, 'executeUnary')
  assert.match(
    body,
    /UNARY_RETRY_DELAY_MS\s*\*\s*attempt/,
    'the pause must grow with the attempt number, so the attempts are spread ' +
      'across the wake-up window instead of clustering at its start',
  )
})

check('only a proven connect timeout is retried', () => {
  // A retry is only safe when the request provably never reached the desktop.
  // A looser test would eventually re-send `session/prompt` and run a turn twice.
  const body = functionBody(transportErrors, 'isConnectTimeout')
  assert.match(body, /SocketTimeoutException/, 'the predicate must test the type')
  assert.match(
    body,
    /CONNECT_TIMEOUT_WORDINGS/,
    'the predicate must match against the enumerated real wordings, not a bare ' +
      'substring: a substring also matches any future message that merely mentions ' +
      'connecting, and this predicate gates a retry',
  )
  assert.doesNotMatch(
    body,
    /contains\(\s*"connect"\s*,\s*ignoreCase/,
    'a bare "connect" substring is what made the predicate loose',
  )
  // The two real producers, and they disagree by platform.
  assert.match(
    transportErrors,
    /failed to connect to/,
    'the Android wording must be matched: IoBridge hardcodes ' +
      '"failed to connect to /ip (port p) …" and never says "connect timed out"',
  )
  assert.match(
    transportErrors,
    /connect timed out/,
    'the OpenJDK wording must be matched too, since the JVM says "Connect timed out"',
  )
  // And the heartbeat message must not be claimable.
  assert.doesNotMatch(
    functionBody(transportErrors, 'isConnectTimeout'),
    /pong/,
    'the ping/pong failure is a different timeout and must not be retried',
  )
})

check('a transport error is retracted once the socket is back', () => {
  // The second half of the spurious report, and the half 1.1.4 missed entirely.
  // A mux failure is self-healing: the stream re-opens on a fresh socket within a
  // second. But the banner raised by the failed attempt stayed up, so the app
  // went on asserting a timeout long after it had reconnected.
  assert.match(
    viewModel,
    /dsh\.connectionState\.collect/,
    'the view model must observe the connection state; DshClient already exposed ' +
      'it and nothing consumed it, which is why the banner could outlive the outage',
  )
  assert.match(
    viewModel,
    /errorIsTransport/,
    'a transport error must be distinguishable from a server-side one, or ' +
      'reconnecting would also retract an error the user still needs to see',
  )
  // Only self-healing codes may be retracted. A rejected prompt is not cured by
  // a reconnect, and silently clearing it would hide a real failure.
  const codes = /TRANSPORT_ERROR_CODES\s*=\s*setOf\(([\s\S]*?)\)/.exec(viewModel)
  assert.ok(codes, 'the retractable codes must be an explicit set')
  for (const code of ['transport', 'ws-failure', 'ws-closed']) {
    assert.ok(
      codes[1].includes(`"${code}"`),
      `"${code}" is a self-healing transport failure and must be retractable`,
    )
  }
  for (const code of ['unauthorized', 'http-500', 'forbidden']) {
    assert.ok(
      !codes[1].includes(`"${code}"`),
      `"${code}" is not cured by reconnecting, so a reconnect must not retract it`,
    )
  }
  // The decision must be keyed on the code, not on the rendered Chinese text:
  // matching prose breaks the moment the wording changes.
  const fn = functionBody(viewModel, 'isTransportError')
  assert.match(fn, /DshException/, 'the predicate must read the exception code')
  assert.doesNotMatch(
    fn,
    /message/,
    'the predicate must not match on the user-facing message, which is Chinese ' +
      'prose by then and would silently stop matching if reworded',
  )
})

check('a recovered socket also clears a stale disconnected flag', () => {
  // A unary call that failed during the blip set `connected = false`, and nothing
  // ever set it back: the chips stayed greyed out and the app bar read "未连接"
  // while the transcript streamed normally underneath. An open mux socket is
  // itself proof the desktop is reachable.
  const init = viewModel.slice(viewModel.indexOf('dsh.connectionState.collect'))
  const body = init.slice(0, init.indexOf('\n    }\n'))
  assert.match(
    body,
    /connected\s*=\s*true/,
    'a live socket must restore `connected`, or a transient failure leaves the ' +
      'whole UI permanently marked as disconnected',
  )
})

// ==================== 3. the token readout matches the desktop, live

console.log('\n=== the token readout matches the desktop ===')

/**
 * The desktop's `formatTokens`, transcribed from
 * `dsh-client-ui-chat/lib/client.js` (and the locale templates
 * `"number.thousand": "{value}K"` / `"number.million": "{value}M"`).
 *
 * Transcribed rather than described so the *values* are pinned, not just the
 * presence of a function: the phone's previous version was a plausible-looking
 * formatter that disagreed with the desktop on case and on rounding.
 */
function desktopFormatTokens(value) {
  const scaled = (candidate) =>
    candidate >= 100
      ? String(Math.round(candidate))
      : String(Math.round(candidate * 10) / 10)
  if (value < 1e3) return String(value)
  if (value < 1e6) return `${scaled(value / 1e3)}K`
  return `${scaled(value / 1e6)}M`
}

check('the desktop format is the one this app implements', () => {
  // The values below are the desktop's real output, including the boundary that
  // a tidier rule would get wrong: 999999 renders as 1000K, not 1M, because the
  // desktop rounds the *scaled* value and 999.999 thousandths rounds to 1000.
  const expected = {
    0: '0',
    517: '517',
    999: '999',
    1000: '1K',
    1234: '1.2K',
    12345: '12.3K',
    99999: '100K',
    209000: '209K',
    517000: '517K',
    999499: '999K',
    999500: '1000K',
    999999: '1000K',
    1000000: '1M',
    1234567: '1.2M',
    10300000: '10.3M',
  }
  for (const [input, want] of Object.entries(expected)) {
    assert.equal(
      desktopFormatTokens(Number(input)),
      want,
      `the reference formatter disagrees with the documented desktop value for ${input}`,
    )
  }
})

check('the Kotlin formatter implements the desktop rules, not its own', () => {
  // The specific regressions: lowercase `k`, and `%.1f` always (which produced
  // `517.0k` where the desktop says `517K`). Both made the same session read
  // differently on the two clients.
  assert.doesNotMatch(
    pickers,
    /"%.1fk"|"%.1fM"|%\.1fk/,
    'the old formatter lowercased the unit and always printed a decimal; the ' +
      'desktop uses an uppercase K/M and drops the decimal at 100 and above',
  )
  assert.doesNotMatch(
    functionBody(pickers, 'formatTokens'),
    /"[kKmM]"\s*\)/,
    'the unit must be chosen explicitly, not from a lowercase format string',
  )
  assert.match(
    functionBody(pickers, 'formatTokens'),
    /"K"/,
    'the thousands unit must be an uppercase K, as the desktop renders it',
  )
  assert.match(
    functionBody(pickers, 'formatTokens'),
    /"M"/,
    'the millions unit must be M',
  )
  // `Math.round` specifically: JavaScript breaks ties upward and Kotlin's
  // `kotlin.math.round` breaks them to even, so the port must not use the latter.
  assert.match(
    functionBody(pickers, 'formatTokens'),
    /Math\.round/,
    'the port must use Math.round to match JavaScript tie-breaking; ' +
      'kotlin.math.round rounds half to even and would disagree',
  )
})

check('the chip renders the desktop label, not a mobile invention', () => {
  const usage = functionBody(pickers, 'UsageChip')
  // The desktop's exact composition: `{total} tok` then `· 缓存命中 {p}%`.
  assert.match(usage, /" tok"/, 'the total must be suffixed " tok", as the desktop does')
  assert.match(
    usage,
    /缓存命中/,
    'the cache-hit share must be labelled "缓存命中", the desktop wording',
  )
  assert.doesNotMatch(
    usage,
    /↑|↓/,
    'the arrow form was a mobile-only invention with no desktop counterpart, so ' +
      'the two clients could not be compared; it must be gone',
  )
  // The desktop's total is all four billed buckets including output.
  assert.match(
    usage,
    /usage\.total/,
    'the total must be the four-bucket sum the desktop shows',
  )
  assert.match(
    usage,
    /formatCacheHitPercent\(/,
    'the cache share must come from the shared formatter',
  )
})

check('a partial cache hit is never shown as 100%', () => {
  // The one thing the cache-hit figure exists to report. `formatCacheHitPercent`
  // widens its precision rather than rounding a real miss up to a full hit.
  const body = functionBody(pickers, 'formatCacheHitPercent')
  assert.match(
    body,
    /missed\s*==\s*0L[\s\S]{0,40}return "100"/,
    'a full hit must return exactly 100',
  )
  assert.match(
    body,
    /rounded\s*<\s*100L/,
    'a rounded value below 100 is returned as-is',
  )
  assert.match(
    body,
    /99\./,
    'a partial hit that rounds to 100 must fall back to the 99.9… form instead of ' +
      'claiming the whole prompt was cached',
  )
  // The boundary arithmetic must stay integral: forming a float here is how a
  // large token count lands on the wrong side of a percent boundary.
  assert.match(
    body,
    /roundedPercentUnits\(/,
    'the ordinary percent must come from the integer helper',
  )
  assert.match(
    functionBody(pickers, 'roundedPercentUnits'),
    /while\s*\(lower\s*<\s*upper\)/,
    'the integer helper must be the binary search the desktop uses, not a float ratio',
  )
})

check('usage is pushed live instead of only polled at turn boundaries', () => {
  // The "update in real time" half. The app polled a one-shot RPC, so on a long
  // turn the chip sat on a stale figure and then jumped — while the desktop,
  // subscribed to this same stream, counted up as tokens were billed.
  assert.match(
    dshClient,
    /fun sessionControl\(/,
    'DshClient must expose the session/control stream',
  )
  assert.match(
    dshClient,
    /"session\/control"/,
    'the live feed is session/control',
  )
  assert.match(
    viewModel,
    /dsh\.sessionControl\(/,
    'the view model must subscribe to it, not only call refreshUsage',
  )
  const control = dshClient.slice(dshClient.indexOf('fun sessionControl('))
  assert.match(
    control.slice(0, 300),
    /JSONObject\(\)/,
    'session/control takes no arguments: its descriptor declares an empty ' +
      'parameter list, and the gateway rejects a non-empty args object',
  )
})

check('the live stream cannot walk the counter backwards', () => {
  // A push frame can arrive out of order relative to the one-shot read taken at
  // the same moment. The host resolves that by `seq`, higher wins; the app has to
  // do the same or the total visibly jumps down mid-turn.
  assert.match(
    viewModel,
    /projectionSeqs/,
    'the view model must track the highest seq per projection key',
  )
  const apply = viewModel.slice(viewModel.indexOf('"projection" ->'))
  assert.match(
    apply.slice(0, 700),
    /seq\s*<=\s*\(?\s*projectionSeqs\[/,
    'a frame must be applied only when its seq is strictly newer than the ' +
      'watermark, or a stale frame overwrites newer values',
  )
  // The baseline seeds the watermark, and the one-shot read does too, so neither
  // can be superseded by something older.
  assert.match(
    viewModel,
    /asOfSeq/,
    'the one-shot read must seed the watermark from asOfSeq',
  )
  assert.match(
    functionBody(dshClient, 'sessionUsage'),
    /asOfSeq/,
    'sessionUsage must return the asOfSeq it read at, so the caller can seed from it',
  )
})

check('the two clients parse a projection value the same way', () => {
  // The one-shot read and the live stream both decode `tokenUsage` and
  // `contextPressure`. A second copy of that decoding is how the two drift, and
  // the drift would be invisible — the chip would just be subtly wrong on one path.
  assert.match(
    dshClient,
    /fun usageOf\(/,
    'the projection value reader must be shared',
  )
  assert.match(
    dshClient,
    /fun pressureOf\(/,
    'the context-pressure reader must be shared',
  )
  const usage = functionBody(dshClient, 'sessionUsage')
  assert.match(usage, /usageOf\(/, 'sessionUsage must read through the shared reader')
  assert.match(usage, /pressureOf\(/, 'sessionUsage must read through the shared reader')
  // The projection's own wire names differ from the settlement's: the prompt side
  // is `uncachedInputTokens` there and `inputTokens` here. That mapping lives in
  // `TokenUsage.fromProjection`, which the shared reader delegates to, so the
  // check follows it there.
  const parser = src('data/SessionParser.kt')
  assert.match(
    functionBody(parser, 'fromProjection'),
    /"uncachedInputTokens"/,
    'the projection names the prompt side uncachedInputTokens, not inputTokens; ' +
      'reading the settlement key here silently yields zero',
  )
})

// ================================ 4. the agent's questions can be answered

console.log('\n=== ask_user_question can be answered from the phone ===')

check('the app subscribes to the forwarded-event stream', () => {
  // This is the only path that can answer a question while it is being asked.
  assert.match(
    dshClient,
    /fun forwardedEvents\(/,
    'DshClient must open the forwarded-event stream',
  )
  assert.match(
    dshClient,
    /"\\\$events"/,
    'the endpoint is the gateway-internal `$events`',
  )
})

check('the forwarded-event payload is exactly an empty args object', () => {
  // The gateway validates this literally: it requires `payload` to have exactly
  // one key `args`, and that args be a plain object with zero own keys. Anything
  // else is refused with `gateway/arguments-invalid`, so a well-meaning extra
  // field makes the whole feature silently dead.
  const fn = dshClient.slice(dshClient.indexOf('fun forwardedEvents('))
  const body = fn.slice(0, fn.indexOf('\n\n'))
  assert.match(
    body,
    /stream\(origin,\s*"\\\$events",\s*JSONObject\(\)\)/,
    'forwardedEvents must pass an empty args object and nothing else',
  )
})

check('the answer carries the correlation pair the host requires', () => {
  const body = functionBody(dshClient, 'answerForwardedEvent')
  // Correlation is by (clientId, eventId), not by socket identity — which is
  // what makes this workable from OkHttp, where the unary result call and the
  // mux socket are different connections.
  for (const key of ['clientId', 'eventId', 'outcome']) {
    assert.ok(body.includes(`"${key}"`), `the answer must carry "${key}"`)
  }
  assert.match(
    body,
    /"kind",\s*"result"/,
    'a real answer is the `result` outcome, which claims the waterfall',
  )
  assert.match(
    body,
    /"kind",\s*"next"/,
    'declining must be an explicit `next` outcome, not a missing field',
  )
  assert.match(
    body,
    /"\\\$events",\s*"result"/,
    'the reply endpoint is $events/result',
  )
})

check('the client id comes from the stream, not from guesswork', () => {
  // The host issues it in the opening `ready` frame and it dies with the stream,
  // so it has to be captured and replaced rather than assumed.
  assert.match(
    viewModel,
    /eventsClientId/,
    'the view model must hold the stream client id',
  )
  const events = viewModel.slice(viewModel.indexOf('private fun startEvents('))
  assert.match(
    events,
    /"ready"/,
    'the ready frame is where the client id comes from',
  )
  // Anchored to the `ready` branch rather than a fixed character window: the
  // body legitimately grows as comments are added, and a window that silently
  // stops covering the branch turns this into a test of the comment length.
  const readyAt = events.indexOf('"ready"')
  const readyBranch = events.slice(readyAt, events.indexOf('"waterfall"', readyAt))
  assert.match(
    readyBranch,
    /eventsClientId\s*=/,
    'the ready frame must store the client id',
  )
})

check('the question is parsed from the real frame shape', () => {
  // The gateway's waterfall frame is exactly
  // {type,event,eventId,agentId,request} — the client id is NOT in it, which is
  // why the parser takes it as a parameter.
  const body = functionBody(userQuestion, 'fromFrame')
  assert.match(body, /"waterfall"/, 'only a waterfall frame is answerable')
  assert.match(body, /"eventId"/, 'the request identity comes from eventId')
  assert.match(body, /"request"/, 'the questions ride on the request object')
  assert.match(
    body,
    /"questions"/,
    'the question list is request.questions',
  )
  assert.match(
    userQuestion,
    /user-questions\/request/,
    'the forwarded event name is user-questions/request',
  )
  // A frame naming no questions must not become an empty, unanswerable dialog.
  assert.match(
    body,
    /if\s*\(questions\.isEmpty\(\)\)\s*return null/,
    'a frame with no usable questions must be ignored, not rendered blank',
  )
})

check('the call id is read from wait.callId', () => {
  // The `wait` object is how the host ties a question to its tool call, and it is
  // absent for a question that is not keyed to one.
  const body = functionBody(userQuestion, 'fromFrame')
  assert.match(
    body,
    /"wait"[\s\S]{0,80}"callId"/,
    'the call id lives at request.wait.callId',
  )
})

check('an answer batch names every question exactly once', () => {
  // The host rejects a batch that does not, so the UI must submit all of them
  // together rather than one at a time.
  const body = functionBody(questionDialog, 'QuestionDialog')
  assert.match(
    body,
    /question\.questions\.map/,
    'the batch must be built by mapping over every question, so none is omitted',
  )
  assert.match(
    body,
    /QuestionAnswer\(/,
    'each question must produce one answer item',
  )
  // The custom field is optional on the wire, and its *presence* is meaningful.
  assert.match(
    userQuestion,
    /isNullOrBlank\(\)/,
    'a blank custom answer must be omitted rather than sent as an empty string',
  )
  assert.match(
    userQuestion,
    /put\("selected"/,
    'the selected labels must be sent',
  )
})

check('the dialog is reachable and is shown above everything else', () => {
  // A question the user cannot see is a turn that can never finish.
  assert.match(
    appShell,
    /state\.pendingQuestion\?\.let/,
    'AppShell must render the pending question',
  )
  assert.match(appShell, /QuestionDialog\(/, 'the dialog must be the question UI')
  // Specifically *above* the settings early return: `AppShell` composes the
  // settings screen and then `return`s, so a dialog placed after that point would
  // never be drawn while the user is in settings — and a user sitting in settings
  // is exactly the one who would never learn why the turn stopped.
  const dialogAt = appShell.indexOf('state.pendingQuestion?.let')
  const settingsReturn = /if\s*\(showSettings\)\s*\{[\s\S]*?\n\s*return\b/.exec(appShell)
  assert.ok(settingsReturn, 'AppShell must still early-return into the settings screen')
  assert.ok(
    dialogAt < settingsReturn.index,
    'the question dialog must be composed before the settings early return, or a ' +
      'question arriving while the user is in settings is never shown',
  )
  assert.match(
    mainActivity,
    /onAnswerQuestion\s*=\s*vm::answerQuestion/,
    'the answer callback must be wired to the view model',
  )
  assert.match(
    viewModel,
    /fun answerQuestion\(/,
    'the view model must expose the answer action',
  )
})

check('a question for another session is not shown as this one\'s', () => {
  // The stream is host-wide: it carries every agent's forwarded events. Showing
  // another session's question would attach it to the wrong transcript.
  const events = viewModel.slice(viewModel.indexOf('private fun startEvents('))
  assert.match(
    events,
    /question\.agentId\s*!=\s*_state\.value\.activeSessionId/,
    'the frame must be filtered to the active session before it is displayed',
  )
})

check('a cancelled question closes the prompt', () => {
  // The host settles a waterfall with the first answerer and sends `cancel` to
  // the others, so this is how a question answered on the desktop disappears here.
  const events = viewModel.slice(viewModel.indexOf('private fun startEvents('))
  assert.match(events, /"cancel"/, 'the cancel frame must be handled')
  assert.match(
    events,
    /filterNot\s*\{\s*it\.eventId\s*==\s*eventId\s*\}/,
    'a cancel must remove exactly that question from the queue',
  )
})

check('concurrent questions are queued, never dropped', () => {
  // The host delivers each waterfall **once per stream**. A question that arrived
  // while another was on screen would therefore be unanswerable forever if it were
  // dropped: the host does not re-send it, so its tool call would sit blocked
  // until the wait expired. So the state holds a queue and only the first is shown.
  assert.match(
    viewModel,
    /val pendingQuestions:\s*List<UserQuestion>/,
    'the pending questions must be a list, or a second question has nowhere to go',
  )
  assert.match(
    viewModel,
    /pendingQuestions\.firstOrNull\(\)/,
    'the UI asks one question at a time: the head of the queue',
  )
  const events = viewModel.slice(viewModel.indexOf('private fun startEvents('))
  assert.match(
    events,
    /pendingQuestions\s*=\s*queue\s*\+\s*question/,
    'an arriving question must be appended to the queue, not replace its predecessor',
  )
  // A re-delivered frame must not ask the same question twice.
  assert.match(
    events,
    /queue\.any\s*\{\s*it\.eventId\s*==\s*question\.eventId\s*\}/,
    'a duplicate delivery must be ignored by eventId',
  )
})

check('a reply that never arrived keeps the question answerable', () => {
  // "The host already settled this" and "we never reached the host" are different
  // outcomes with different consequences, and collapsing them loses the user's
  // answer: the first means the prompt is done with, the second means the host is
  // still waiting. The transport failure must therefore be thrown, not folded into
  // a falsy return value.
  const send = functionBody(dshClient, 'answerForwardedEvent')
  assert.doesNotMatch(
    send,
    /runCatching/,
    'the reply must not swallow a transport failure into a falsy result: the ' +
      'caller cannot then tell "already settled" from "never delivered"',
  )
  assert.match(
    send,
    /rpc\(origin,\s*"\\\$events",\s*"result"/,
    'the reply must be the $events/result call',
  )
  // And the caller must put it back at the front.
  const answer = functionBody(viewModel, 'answerQuestion')
  assert.match(
    answer,
    /catch\s*\(t:\s*Throwable\)/,
    'answerQuestion must handle a failed delivery',
  )
  assert.match(
    answer,
    /listOf\(question\)\s*\+\s*queue/,
    'a failed delivery must restore the question at the front, ahead of any ' +
      'question that arrived while it was on screen',
  )
})

check('a stale question is dropped when the session changes', () => {
  // Its (clientId, eventId) pair belongs to the session being left.
  const activate = functionBody(viewModel, 'activateSession')
  assert.match(
    activate,
    /pendingQuestions\s*=\s*emptyList\(\)/,
    'switching sessions must clear the questions belonging to the previous one',
  )
})

check('the question dialog cannot be submitted empty', () => {
  // The host rejects a question answered with neither a selection nor text, so
  // the button must be gated rather than sending a doomed request.
  assert.match(
    questionDialog,
    /val answerable/,
    'the dialog must compute whether the batch can be submitted',
  )
  assert.match(
    questionDialog,
    /enabled\s*=\s*answerable/,
    'the submit button must be gated on it',
  )
})

// ================================================= 5. the release is coherent

console.log('\n=== 1.1.6 is the released version ===')

check('the CHANGELOG has a 1.1.6 section with real notes', () => {
  const section = /^## \[1\.1\.6\][\s\S]*?(?=^## \[|\Z)/m.exec(changelog)
  assert.ok(section, 'CHANGELOG.md must have a "## [1.1.6]" section')
  // The notes are what users read in the update prompt.
  assert.ok(
    section[0].length > 400,
    'the 1.1.6 section is too short to be the release notes users will see',
  )
  // Each of the four fixes must be named, since that is what the affected user
  // searched for.
  assert.match(section[0], /横屏/, 'the notes must name the landscape chip fix')
  assert.match(
    section[0],
    /连接超时/,
    'the notes must name the spurious timeout, which is what users reported',
  )
  assert.match(section[0], /缓存命中/, 'the notes must name the token-readout change')
  assert.match(
    section[0],
    /ask_user_question|提问|回答/,
    'the notes must name the new ability to answer questions',
  )
})

check('the CHANGELOG link block defines 1.1.6', () => {
  assert.match(
    changelog,
    /^\[1\.1\.6\]:\s+https:\/\/github\.com\/nmaych\/dsh-mobile\/compare\/v1\.1\.5\.\.\.v1\.1\.6$/m,
    'the link reference for 1.1.6 must exist, or the heading renders as literal text',
  )
})

check('the Gradle defaults match the newest CHANGELOG entry', () => {
  // This used to name the literal `1.1.6` / `10106`, which made the suite fail
  // on every later release for a reason that had nothing to do with 1.1.6 —
  // the values are *supposed* to move. What 1.1.6 actually depends on is the
  // invariant, which `release-logic.test.mjs` already enforces in full; the
  // check is repeated here only so a mistake is reported by both suites.
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
  // A test that only runs on the machine of whoever wrote it is not a regression
  // guard. `logic` runs each file by name, so a missing line means this suite
  // never runs.
  assert.match(
    ci,
    /node test\/regression-1\.1\.6\.test\.mjs/,
    'ci.yml must run test/regression-1.1.6.test.mjs',
  )
})

check('the protocol notes record the new wire facts', () => {
  // The forwarded-event stream is the first thing in this app that is not a
  // registered service method, and its args rule is counter-intuitive enough that
  // a reader would "fix" it back into a normal call.
  const protocol = read('docs/PROTOCOL.md')
  assert.match(
    protocol,
    /\$events/,
    'docs/PROTOCOL.md must document the forwarded-event stream',
  )
  assert.match(
    protocol,
    /session\/control/,
    'docs/PROTOCOL.md must document the live projection stream',
  )
  assert.match(
    protocol,
    /缓存命中/,
    'docs/PROTOCOL.md must record the desktop token wording the chip now matches',
  )
})

check('the new sources are real files', () => {
  for (const rel of [
    'app-project/app/src/main/java/ai/deepseek/dshmobile/data/UserQuestion.kt',
    'app-project/app/src/main/java/ai/deepseek/dshmobile/ui/components/QuestionDialog.kt',
  ]) {
    assert.ok(exists(rel), `${rel} is missing`)
  }
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
