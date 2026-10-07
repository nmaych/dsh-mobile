// Guards the three 1.1.3 fixes against the mistakes that are invisible to the
// Kotlin compiler:
//
//   1. A transport failure reaching the UI as raw English. This is a *wording*
//      bug, so nothing type-checks it — the previous code compiled perfectly and
//      still printed "failed to connect to /192.168.3.103 (port 19387) from
//      /192.168.3.104 (port 39694) after 20000ms" on screen.
//
//   2. A subagent child being addressed as a plain top-level session. The server
//      rejects that with "subagent Sessions require their durable parent address",
//      and the shapes are easy to get wrong because the two sibling methods in
//      `subagents` do NOT share a wire shape.
//
//   3. The model/workspace/usage strip collapsing into a vertical column. The
//      trigger was a layout modifier, and no compiler notices a `weight(1f)` that
//      starves its siblings.
//
// All checks are static over the sources, so they run in seconds with no Android
// SDK. The expected wire shapes and error codes are taken from the DSH Host's own
// generated descriptors, not from guesswork:
//
//   - `session/list` result:  `items[].origin`, `items[].parentSessionId`,
//     `items[].projections.values.subagent.{mode,label,seq}`
//   - `session/page|follow` param: `address` is a union of
//     `{kind:"session",sessionId}` and
//     `{kind:"subagent",parentSessionId,childSessionId,mode}` where `mode` is
//     `one-shot | continuable | unknown`
//   - `subagents/prompt`: one `request` parameter
//   - `subagents/interruptByParent`: three TOP-LEVEL parameters
//     (`childSessionId`, `parentSessionId`, `mode`) — no `request` wrapper
//
// usage: node test/regression-1.1.3.test.mjs
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

const src = (rel) =>
  read(`app-project/app/src/main/java/ai/deepseek/dshmobile/${rel}`)

const dshClient = src('data/DshClient.kt')
const viewModel = src('ui/ChatViewModel.kt')
const chatScreen = src('ui/ChatScreen.kt')
const pickers = src('ui/components/Pickers.kt')
const chatApi = src('net/ChatApi.kt')
const updateManager = src('update/UpdateManager.kt')
const transportErrors = src('net/TransportErrors.kt')
const settingsScreen = src('ui/SettingsScreen.kt')

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
 * The body of the `fun <name>` overload whose parameter list contains
 * [paramNeedle].
 *
 * Kotlin overloading makes a bare name ambiguous, and the overloads here are
 * exactly the pair that matters: `prompt(origin, sessionId, …)` is the raw form
 * and `prompt(origin, address, …)` is the routing one. Picking the first match
 * would silently assert against the wrong function.
 */
function overloadBody(source, name, paramNeedle) {
  const re = new RegExp(`fun\\s+${name}\\s*\\(`, 'g')
  let match
  while ((match = re.exec(source)) !== null) {
    // The parameter list runs to the matching `)`; read it without assuming the
    // signature is on one line.
    const open = source.indexOf('(', match.index)
    let depth = 0
    let close = -1
    for (let i = open; i < source.length; i++) {
      if (source[i] === '(') depth++
      else if (source[i] === ')') {
        depth--
        if (depth === 0) {
          close = i
          break
        }
      }
    }
    if (close < 0) continue
    const params = source.slice(open + 1, close)
    if (params.includes(paramNeedle)) {
      return functionBody(source.slice(match.index), name)
    }
  }
  throw new Error(`no overload of fun ${name} takes a parameter matching ${paramNeedle}`)
}

/** Drop `//` line comments so prose about a pattern cannot trip a check. */
function stripLineComments(text) {
  return text
    .split('\n')
    .map((line) => line.replace(/\/\/.*$/, ''))
    .join('\n')
}

// ------------------------------------------------------- 1. transport wording

console.log('=== transport failures are Chinese and actionable ===')

check('the transport mapper exists and is shared, not duplicated per caller', () => {
  // The wording lives in one place because three back ends hit the same failure
  // and each needs different advice. A per-caller copy is how the API path ends
  // up telling the user to enable a LAN plugin that is not involved.
  assert.ok(
    /object TransportErrors/.test(transportErrors),
    'net/TransportErrors.kt must own the wording',
  )
  assert.ok(
    /fun message\(/.test(transportErrors),
    'TransportErrors must expose a message() mapper',
  )
  for (const [label, text] of [
    ['DshClient', dshClient],
    ['ChatApi', chatApi],
    ['UpdateManager', updateManager],
  ]) {
    assert.ok(
      text.includes('TransportErrors'),
      `${label} must route transport failures through TransportErrors`,
    )
  }
})

check('the raw OkHttp text is never echoed back to the user', () => {
  // This is the whole bug: OkHttp's message names the remote IP, the *local*
  // ephemeral port and the millisecond budget, none of which the user can act on.
  //
  // The fallback arm is the dangerous one — it is what catches every exception
  // type not enumerated — so it must not interpolate the throwable's message.
  // Comments are stripped first: the doc comment above `message()` *quotes* the
  // offending OkHttp text to explain the bug, and that prose must not count.
  const body = stripLineComments(functionBody(transportErrors, 'message'))
  assert.ok(
    !/t\.message/.test(body),
    'TransportErrors.message must not echo t.message; the wording is a long ' +
      'tail of English ("Software caused connection abort", "unexpected end of ' +
      'stream") and every one of them is a regression',
  )
  assert.ok(
    /else\s*->\s*\n?\s*"无法连接/.test(body),
    'the fallback arm must be a fixed Chinese sentence',
  )
  // And the original text must still reach logcat, so attach it as the cause.
  assert.ok(
    /initCause/.test(dshClient),
    'the original IOException must be attached as the cause so it still reaches logcat',
  )
})

check('the mapper inspects the whole cause chain, not just the outermost type', () => {
  // OkHttp wraps a connect failure in its own RouteException, which is a
  // RuntimeException rather than an IOException, and a WebSocket listener is
  // handed exactly that. Matching only the outermost type missed the commonest
  // case — which is why the screenshot showed the raw text from the WS path.
  const body = functionBody(transportErrors, 'message')
  assert.ok(
    /causeChain\(/.test(body),
    'message() must walk the cause chain',
  )
  const chain = functionBody(transportErrors, 'causeChain')
  assert.ok(
    /\.cause/.test(chain),
    'causeChain must follow Throwable.cause',
  )
  for (const type of [
    'UnknownHostException',
    'SocketTimeoutException',
    'ConnectException',
    'SocketException',
  ]) {
    assert.ok(
      body.includes(type),
      `message() must distinguish ${type}, because each has a different fix`,
    )
  }
})

check('the WebSocket failure path is mapped too, for any Throwable', () => {
  // The screenshot's exact text came from here. OkHttp hands the listener a
  // Throwable, so a `t is IOException` guard let the non-IOException route
  // through un-mapped.
  const body = functionBody(dshClient, 'onFailure')
  assert.ok(
    /TransportErrors\.message\(/.test(body),
    'onFailure must map the failure through TransportErrors',
  )
  assert.ok(
    !/t is java\.io\.IOException/.test(body),
    'onFailure must not gate the mapping on an IOException type test; the ' +
      'RouteException it actually receives is a RuntimeException',
  )
  assert.ok(
    /ws-failure/.test(body),
    'onFailure must keep the ws-failure code so the mux retry still recognises it',
  )
})

check('the API and updater paths get internet advice, not LAN advice', () => {
  // Telling an API-mode user to enable dsh-mobile-connect is worse than saying
  // nothing: there is no LAN and no plugin on that path.
  assert.ok(
    /INTERNET_HINT/.test(transportErrors),
    'TransportErrors must expose a non-LAN hint',
  )
  for (const [label, text] of [
    ['ChatApi', chatApi],
    ['UpdateManager', updateManager],
  ]) {
    assert.ok(
      text.includes('TransportErrors.INTERNET_HINT'),
      `${label} must use the internet hint`,
    )
  }
  assert.ok(
    /LAN_HINT/.test(dshClient),
    'DshClient must use the LAN hint, where the plugin really is involved',
  )
})

check('a failed model list is mapped, not just the streaming call', () => {
  // listModels threw straight out of OkHttp, so a dead API host surfaced the
  // same raw English in the model picker.
  const body = functionBody(chatApi, 'listModels')
  assert.ok(
    /TransportErrors\.message\(/.test(body),
    'ChatApi.listModels must map its transport failure',
  )
})

// --------------------------------------------- 2. durable subagent addressing

console.log('\n=== subagent sessions use their durable parent address ===')

check('the address union matches the server descriptor exactly', () => {
  // From the Host's generated schema: the union is
  // `{kind:"session",sessionId}` | `{kind:"subagent",parentSessionId,
  //  childSessionId,mode}`, and `mode` is one-shot | continuable | unknown.
  for (const key of ['kind', 'parentSessionId', 'childSessionId', 'mode']) {
    assert.ok(
      dshClient.includes(`"${key}"`),
      `SessionAddress must emit the "${key}" wire name`,
    )
  }
  assert.ok(
    /"kind",\s*"subagent"/.test(dshClient),
    'the child address must declare kind "subagent"',
  )
  assert.ok(
    /"kind",\s*"session"/.test(dshClient),
    'the top-level address must declare kind "session"',
  )
  // `unknown` is the value the server accepts when the projection is unread:
  // validateAddress only compares modes when `address.mode !== "unknown"`.
  assert.ok(
    dshClient.includes('"unknown"'),
    'mode must fall back to "unknown", which the server accepts for reads',
  )
})

check('the parent id is read from the session list, not guessed', () => {
  // `origin == "subagent"` marks a child and `parentSessionId` carries its
  // durable parent. Reading both from the list row is what makes every later
  // call addressable.
  assert.ok(
    dshClient.includes('"origin"') && dshClient.includes('"subagent"'),
    'listSessions must detect origin == "subagent"',
  )
  const body = functionBody(dshClient, 'subagentAddressOf')
  assert.ok(
    /"parentSessionId"/.test(body),
    'the parent must come from the summary\'s parentSessionId',
  )
  assert.ok(
    /return null/.test(body),
    'a top-level session must yield no subagent address',
  )
})

check('follow and page are given the address, never a bare id', () => {
  for (const fn of ['followSession', 'pageSession']) {
    const body = functionBody(dshClient, fn)
    assert.ok(
      /address\.toJson\(\)/.test(body),
      `${fn} must send address.toJson()`,
    )
    assert.ok(
      !/put\("sessionId",\s*sessionId\)/.test(body),
      `${fn} must not send a bare sessionId: the server answers a child with ` +
        '"subagent Sessions require their durable parent address"',
    )
  }
})

check('a child prompt goes through subagents/prompt', () => {
  // `session/prompt` refuses a child ("…is owned by subagent routing"), so the
  // write has to name both ends of the delegation.
  const body = functionBody(dshClient, 'promptSubagent')
  assert.ok(
    /"subagents",\s*"prompt"/.test(dshClient),
    'a child turn must be queued through subagents/prompt',
  )
  for (const key of ['parentSessionId', 'childSessionId', 'delivery', 'content']) {
    assert.ok(
      body.includes(`"${key}"`),
      `subagents/prompt must send "${key}"`,
    )
  }
  assert.ok(
    /"mode",\s*"continuable"/.test(body),
    'subagents/prompt only accepts a continuable address',
  )
})

check('the address-aware prompt routes by kind', () => {
  // The call site must not have to know which endpoint applies. The overload is
  // selected by its `address` parameter — `prompt(origin, sessionId, …)` is the
  // raw form and must not be mistaken for it.
  const body = overloadBody(dshClient, 'prompt', 'address: SessionAddress')
  assert.ok(
    /is SessionAddress\.Plain/.test(body) && /is SessionAddress\.Subagent/.test(body),
    'prompt() must branch on the address kind',
  )
  assert.ok(
    /promptSubagent\(/.test(body),
    'the Subagent branch must call promptSubagent()',
  )
})

check('a one-shot child is refused before the server has to say so', () => {
  // A one-shot delegation has no inbox left to accept a turn. Saying so locally
  // is better than a round trip that returns a code the user cannot read.
  const body = overloadBody(dshClient, 'prompt', 'address: SessionAddress')
  assert.ok(
    /continuable/.test(body),
    'prompt() must check the child is continuable',
  )
  assert.ok(
    /subagent\/not-continuable/.test(body),
    'a one-shot child must produce a distinguishable error code',
  )
})

check('interruptByParent uses three top-level parameters, not a request wrapper', () => {
  // This is the shape trap: `subagents/prompt` takes ONE `request` parameter,
  // while its sibling `subagents/interruptByParent` takes three top-level ones.
  // Wrapping these in `request` is a strict-codec rejection.
  const body = functionBody(dshClient, 'interruptSubagent')
  assert.ok(
    /"subagents",\s*"interruptByParent"/.test(dshClient),
    'a child turn must be interrupted through subagents/interruptByParent',
  )
  assert.ok(
    !/"request"/.test(body),
    'interruptByParent takes no `request` wrapper; its three parameters are top-level',
  )
  for (const key of ['childSessionId', 'parentSessionId', 'mode']) {
    assert.ok(
      body.includes(`"${key}"`),
      `interruptByParent must send "${key}" at the top level`,
    )
  }
})

check('stop routes a child through its parent, not session/cancel', () => {
  const body = overloadBody(dshClient, 'cancel', 'address: SessionAddress')
  assert.ok(
    /is SessionAddress\.Subagent/.test(body),
    'cancel() must special-case a child address',
  )
  assert.ok(
    /interruptSubagent\(/.test(body),
    'a child must be cancelled through interruptSubagent()',
  )
  // And the view model must pass the address, not the bare id.
  const stop = functionBody(viewModel, 'stop')
  assert.ok(
    /activeAddress/.test(stop),
    'stop() must use activeAddress',
  )
  assert.ok(
    /dsh\.cancel\(origin,\s*address\)/.test(stop),
    'stop() must pass the address to cancel()',
  )
})

check('the view model follows and prompts by address', () => {
  assert.ok(
    /val activeAddress: SessionAddress\?/.test(viewModel),
    'the UI state must carry the active address',
  )
  assert.ok(
    /dsh\.followSession\(origin,\s*address\)/.test(viewModel),
    'startFollowing must follow the address',
  )
  assert.ok(
    /dsh\.prompt\(origin,\s*address,\s*text\)/.test(viewModel),
    'sendRemote must prompt by address',
  )
  // `resolveSession` must return the address, or a child loses its parent on the
  // very first implicit create.
  const resolve = viewModel.slice(
    viewModel.indexOf('fun resolveSession('),
    viewModel.indexOf('fun resolveSession(') + 700,
  )
  assert.ok(
    /activeAddress/.test(resolve),
    'resolveSession must return the active address',
  )
})

check('the server\'s subagent error codes are translated, not passed through', () => {
  // "subagent Sessions require their durable parent address" is accurate and
  // tells a phone user nothing. The codes below are the ones the Host raises for
  // this family of failures.
  for (const code of [
    'session/agent-busy',
    'subagent/unauthorized',
    'subagent/catalog-diagnostic',
    'subagent/not-found',
  ]) {
    assert.ok(
      dshClient.includes(`"${code}"`),
      `DshClient must translate the "${code}" code`,
    )
  }
  const body = functionBody(dshClient, 'serverMessage')
  assert.ok(
    !/durable parent address/.test(body),
    'the internal addressing wording must not reach the user',
  )
  assert.ok(
    /else -> raw/.test(body),
    'an unrecognised code must keep the server\'s own wording rather than a ' +
      'generic apology',
  )
})

// ------------------------------------------------------ 3. the chip strip layout

console.log('\n=== the model/workspace/usage strip stays a strip ===')

check('the strip is a FlowRow, not a Row with a weighted spacer', () => {
  // A `weight(1f)` spacer takes the whole remainder of the line for itself, so
  // the usage chip was handed a few pixels: its monospace readout broke after
  // every token and the chip grew to hundreds of pixels tall.
  const body = functionBody(chatScreen, 'RemoteChipsRow')
  assert.ok(
    /FlowRow\(/.test(body),
    'RemoteChipsRow must use FlowRow so each chip is measured at its natural width',
  )
  assert.ok(
    !/Spacer\(Modifier\.weight\(/.test(body),
    'a weighted spacer in this strip starves the chips after it',
  )
  assert.ok(
    /Arrangement\.spacedBy/.test(body),
    'FlowRow needs its own spacing; a weight-based layout cannot provide it',
  )
})

check('every chip is pinned to one line tall', () => {
  // The vertical stretch and the per-token wrapping are the same missing
  // constraint, so both halves are asserted: `wrapContentHeight` stops the chip
  // being stretched to the row's height, and the single-line text stops the
  // label stacking one token per line.
  const usage = functionBody(pickers, 'UsageChip')
  assert.ok(
    /wrapContentHeight\(\)/.test(usage),
    'UsageChip must not stretch to the row height',
  )
  assert.ok(
    /maxLines = 1/.test(usage),
    'UsageChip text must be single-line',
  )
  assert.ok(
    /softWrap = false/.test(usage),
    'UsageChip text must not soft-wrap; the readout is many short tokens and ' +
      'wraps after every one of them when given a narrow width',
  )

  const model = functionBody(pickers, 'ModelChip')
  assert.ok(
    /wrapContentHeight\(\)/.test(model),
    'ModelChip must not stretch to the row height',
  )
  assert.ok(
    /softWrap = false/.test(model),
    'ModelChip label must not soft-wrap',
  )
})

check('the workspace chip carries the same one-line constraints', () => {
  // It was the one chip left without them, so it could still stretch and wrap.
  const row = functionBody(chatScreen, 'RemoteChipsRow')
  const workspaceAt = row.indexOf('onOpenWorkspacePicker')
  assert.ok(workspaceAt >= 0, 'the workspace chip must be in the strip')
  const chip = row.slice(workspaceAt, workspaceAt + 1200)
  assert.ok(
    /wrapContentHeight\(\)/.test(chip),
    'the workspace chip must not stretch to the row height',
  )
  assert.ok(
    /softWrap = false/.test(chip),
    'the workspace label must not soft-wrap',
  )
  assert.ok(
    /maxLines = 1/.test(chip),
    'the workspace label must be single-line',
  )
})

check('long chip labels are ellipsised rather than allowed to take the line', () => {
  const row = functionBody(chatScreen, 'RemoteChipsRow')
  assert.ok(
    /widthIn\(max =/.test(row),
    'a long provider/model or workspace path must be bounded so it cannot ' +
      'swallow the line and force the other chips to wrap',
  )
  assert.ok(
    /overflow = TextOverflow\.Ellipsis/.test(row),
    'a bounded label must ellipsise',
  )
})

// ------------------------------------------------------------- the project link

console.log('\n=== the project URL is shown and reachable from settings ===')

check('the settings screen shows the project repository URL', () => {
  assert.ok(
    /github\.com\/nmaych\/dsh-mobile/.test(settingsScreen),
    'SettingsScreen must show the project URL',
  )
  // It must be the same repository the updater reads from, or a user who
  // follows the link to report a bug lands somewhere that is not this app.
  assert.ok(
    updateManager.includes('github.com/nmaych/dsh-mobile') ||
      read('app-project/app/src/main/java/ai/deepseek/dshmobile/data/Prefs.kt')
        .includes('github.com/nmaych/dsh-mobile'),
    'the shown URL must match the repository the updater uses',
  )
})

check('the link is tappable, not just printed', () => {
  // A URL the user has to retype by hand does not answer "where did this come
  // from" — which is the whole point of showing it.
  assert.ok(
    /ACTION_VIEW/.test(settingsScreen),
    'the URL must open through an ACTION_VIEW intent',
  )
  assert.ok(
    /Uri\.parse\(/.test(settingsScreen),
    'the intent must carry the URL as data',
  )
  assert.ok(
    /FLAG_ACTIVITY_NEW_TASK/.test(settingsScreen),
    'the intent needs FLAG_ACTIVITY_NEW_TASK, since the caller may not be an Activity',
  )
  assert.ok(
    /runCatching/.test(settingsScreen),
    'opening the link must be guarded: a device with no browser would otherwise ' +
      'crash on a settings tap',
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
