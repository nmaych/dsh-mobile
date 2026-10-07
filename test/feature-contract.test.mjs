// Guards the wire contract for the features added in 1.1.2: workspace
// selection, remote model switching, and token-usage display.
//
// Why this exists: the Harness gateway rejects a request whose `args` keys do
// not *exactly* match the descriptor's wire names — an extra key, a missing key,
// or a renamed one is a hard `gateway/arguments-invalid`. That is invisible to
// the Kotlin compiler, and it fails only on a user's phone, as a feature that
// silently does nothing. The wire names are not uniform (`session/list` takes
// `_request`, everything else takes `request`), so they are easy to get wrong.
//
// These are static checks over the sources, so they run in seconds with no
// Android SDK.
//
// usage: node test/feature-contract.test.mjs
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

const dshClient = read(
  'app-project/app/src/main/java/ai/deepseek/dshmobile/data/DshClient.kt',
)
const parser = read(
  'app-project/app/src/main/java/ai/deepseek/dshmobile/data/SessionParser.kt',
)
const viewModel = read(
  'app-project/app/src/main/java/ai/deepseek/dshmobile/ui/ChatViewModel.kt',
)

/**
 * Extract the balanced `(...)` group starting at the first `(` at or after
 * `from`.
 *
 * A naive non-greedy `\)` is not enough: these call sites nest `JSONObject()`
 * and `put(...)`, so the first `)` belongs to an inner call and the match would
 * stop before the argument that matters.
 *
 * @returns the text inside the parentheses, or null when unbalanced.
 */
function balancedParens(source, from) {
  const start = source.indexOf('(', from)
  if (start < 0) return null
  let depth = 0
  for (let i = start; i < source.length; i++) {
    const c = source[i]
    if (c === '(') depth++
    else if (c === ')') {
      depth--
      if (depth === 0) return source.slice(start + 1, i)
    }
  }
  return null
}

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
 * The argument text of the `rpc(...)` call that names this endpoint, split at
 * the method string.
 *
 * @returns `{ args, tail }` where `args` is everything inside the parentheses and
 *   `tail` is what follows the method string — empty when the call passes no
 *   arguments of its own.
 */
function rpcCallFor(source, namespace, method) {
  const needle = `"${namespace}", "${method}"`
  const at = source.indexOf(needle)
  assert.ok(at >= 0, `no rpc() call found for ${namespace}/${method}`)
  // Walk back to the `rpc(` that owns this endpoint string.
  const rpcAt = source.lastIndexOf('rpc(', at)
  assert.ok(rpcAt >= 0, `no rpc( before ${namespace}/${method}`)
  const args = balancedParens(source, rpcAt)
  assert.ok(args !== null, `unbalanced rpc() for ${namespace}/${method}`)
  const offset = at - rpcAt - 1
  return { args, tail: args.slice(offset + needle.length).trim() }
}

// ---------------------------------------------------------------- workspaces

console.log('=== workspace selection ===')

check('workspace list is read from the follow baseline', () => {
  // There is no unary workspace list method: the baseline frame of
  // `workspace/follow` is the only complete snapshot.
  assert.ok(
    dshClient.includes('"workspace/follow"'),
    'DshClient must read workspaces from workspace/follow',
  )
  assert.ok(
    dshClient.includes('"baseline"'),
    'the workspace snapshot arrives as a frame with type "baseline"',
  )
})

check('workspace/create passes `request` (not `_request`)', () => {
  const { args } = rpcCallFor(dshClient, 'workspace', 'create')
  assert.ok(
    args.includes('"request"'),
    `workspace/create must pass its argument as "request"; got: ${args.replace(/\s+/g, ' ')}`,
  )
  assert.ok(
    !args.includes('"_request"'),
    'only session/list uses the `_request` wire name',
  )
})

check('workspace/create sends the path under `path`', () => {
  const { args } = rpcCallFor(dshClient, 'workspace', 'create')
  assert.ok(
    args.includes('"path"'),
    'workspace/create takes its directory as `path`',
  )
})

check('session/create passes workspaceId so the session is filed in it', () => {
  // The fields are assembled into `req` just above the call.
  const body = functionBody(dshClient, 'createSession')
  const { args } = rpcCallFor(body, 'session', 'create')
  assert.ok(args.includes('"request"'), 'session/create must pass "request"')
  assert.ok(
    body.includes('put("workspaceId"'),
    'session/create must pass `workspaceId`; a `cwd` alone does not file the ' +
      'session under the workspace, so the desktop sidebar would not show it',
  )
  assert.ok(body.includes('put("cwd"'), 'session/create should also pass the cwd')
})

check('session/create guards each optional key before putting it', () => {
  // Every key on that descriptor is optional, but a key present with an empty
  // string is a *value*, and the strict codec rejects it.
  const body = functionBody(dshClient, 'createSession')
  assert.ok(
    /if\s*\(!workspaceId\.isNullOrBlank\(\)\)\s*req\.put\("workspaceId"/.test(body),
    'workspaceId must be guarded by a non-blank check',
  )
  assert.ok(
    /if\s*\(!cwd\.isNullOrBlank\(\)\)\s*req\.put\("cwd"/.test(body),
    'cwd must be guarded by a non-blank check',
  )
})

// -------------------------------------------------------------------- models

console.log('\n=== remote model switching ===')

check('the model catalog comes from session/modelCatalog', () => {
  assert.ok(
    dshClient.includes('"session", "modelCatalog"'),
    'DshClient must call session/modelCatalog',
  )
})

check('session/modelCatalog is called with no arguments', () => {
  // Its descriptor declares zero parameters, and the gateway rejects any extra
  // key, so passing one would fail every time.
  const { tail } = rpcCallFor(dshClient, 'session', 'modelCatalog')
  assert.equal(
    tail,
    '',
    `session/modelCatalog declares no parameters; it must be called with none, got: ${tail}`,
  )
})

check('session/selectModel passes `request` with sessionId, provider and model', () => {
  const body = functionBody(dshClient, 'selectModel')
  const { args } = rpcCallFor(body, 'session', 'selectModel')
  assert.ok(args.includes('"request"'), 'session/selectModel must pass "request"')
  // The fields are assembled into `req` just above the call.
  for (const key of ['sessionId', 'provider', 'model']) {
    assert.ok(
      body.includes(`put("${key}"`),
      `session/selectModel must put "${key}" into its request`,
    )
  }
})

check('reasoningEffort is only sent when it has a value', () => {
  // The descriptor marks it optional, but an empty string is a *present* key
  // with an invalid value, which is rejected rather than ignored.
  const body = functionBody(dshClient, 'selectModel')
  assert.ok(
    /if\s*\(!reasoningEffort\.isNullOrBlank\(\)\)\s*req\.put\("reasoningEffort"/.test(body),
    'reasoningEffort must be guarded by a non-blank check before being put',
  )
})

check('the catalog groups are read as provider -> models', () => {
  const body = functionBody(dshClient, 'modelCatalog')
  for (const key of ['groups', 'default', 'failures']) {
    assert.ok(body.includes(`"${key}"`), `the model catalog reader must handle "${key}"`)
  }
  // Models live under each group, and the provider id is the group id.
  assert.ok(body.includes('"models"'), 'each group carries its `models` array')
  assert.ok(body.includes('"id"'), 'the provider id is the group `id`')
})

check('reasoning efforts are read per model', () => {
  const body = functionBody(dshClient, 'modelCatalog')
  assert.ok(body.includes('"reasoning"'), 'a model may declare `reasoning`')
  assert.ok(body.includes('"efforts"'), 'reasoning carries an `efforts` list')
  assert.ok(
    body.includes('"defaultEffort"'),
    'reasoning carries a `defaultEffort`',
  )
})

// --------------------------------------------------------------- token usage

console.log('\n=== token usage ===')

check('usage is read from session/projections, not guessed locally', () => {
  assert.ok(
    dshClient.includes('"session", "projections"'),
    'DshClient must read usage from session/projections',
  )
})

check('session/projections passes `request` with sessionId', () => {
  const { args } = rpcCallFor(dshClient, 'session', 'projections')
  assert.ok(args.includes('"request"'), 'session/projections must pass "request"')
  assert.ok(args.includes('"sessionId"'), 'session/projections must pass "sessionId"')
})
check('the projection is read by its registered key `tokenUsage`', () => {
  // The key is the projection's `key`, registered by the token-meter plugin.
  assert.ok(
    dshClient.includes('"tokenUsage"'),
    'the usage projection is keyed "tokenUsage"',
  )
})

check('the projection field is `uncachedInputTokens`, not `inputTokens`', () => {
  // The projection names the prompt side `uncachedInputTokens`; the raw usage
  // record on an event names the same number `inputTokens`. Reading the wrong
  // one yields a silent zero.
  const fromProjection = functionBody(parser, 'fromProjection')
  assert.ok(
    fromProjection.includes('"uncachedInputTokens"'),
    'TokenUsage.fromProjection must read `uncachedInputTokens`',
  )
  const fromUsage = functionBody(parser, 'fromUsage')
  assert.ok(
    fromUsage.includes('"inputTokens"'),
    'TokenUsage.fromUsage must read `inputTokens` off the raw usage record',
  )
  assert.ok(
    !fromProjection.includes('"inputTokens"'),
    'fromProjection must not read `inputTokens`; that key is not on the projection',
  )
})

check('context occupancy is read from contextPressure', () => {
  const body = functionBody(dshClient, 'sessionUsage')
  assert.ok(
    body.includes('"contextPressure"'),
    'the context window comes from the contextPressure projection',
  )
  for (const key of ['contextWindow', 'projectedTokens', 'pressureTokens']) {
    assert.ok(body.includes(`"${key}"`), `the context pressure reader must handle "${key}"`)
  }
})

check('a usage sample is taken from the live stream too', () => {
  // The durable total only moves at settlement; the live `usage` chunk is the
  // earliest figure the UI can show during a turn.
  const body = functionBody(parser, 'apply')
  assert.ok(
    body.includes('"usage"'),
    'LiveAssistant must handle the `usage` stream chunk',
  )
  assert.ok(
    body.includes('fromUsage'),
    'the live usage chunk must be parsed through TokenUsage.fromUsage',
  )
})

check('a new attempt clears the previous attempt\'s live usage', () => {
  // A retry re-streams; keeping the earlier attempt's figure would show it on top
  // of the running total until the retry reported its own sample.
  const applyBody = functionBody(parser, 'apply')
  const startAt = applyBody.indexOf('"start" ->')
  assert.ok(startAt >= 0, 'the `start` frame arm must exist in LiveAssistant.apply')
  // The arm runs to its own closing brace; scanning a generous window past the
  // arm's opening is enough and does not depend on comment length.
  const window = applyBody.slice(startAt, startAt + 900)
  assert.ok(
    /usage = null/.test(window),
    'the `start` arm must clear the accumulated usage',
  )
})

check('live usage is cleared on any settlement, not only a message', () => {
  // The server's `tokenUsage` fold counts `assistant/attempt` too (see
  // dsh-token-meter usage-projection), so an abandoned attempt's tokens are in
  // the refreshed total. Leaving them in liveUsage as well double-counted them.
  assert.ok(
    /assistant\/attempt/.test(viewModel),
    'the settlement check must include assistant/attempt',
  )
  assert.ok(
    /if \(settledAttempt\) TokenUsage\(\) else _state\.value\.liveUsage/.test(viewModel),
    'liveUsage must be cleared when any settlement lands',
  )
})

check('a per-attempt sample never overwrites the cumulative total', () => {
  // A settlement's `usage` covers one attempt; the projection is the session
  // total. Assigning the former to the latter made the counter jump *down*
  // mid-turn and back up at settlement. They are separate fields, and the chip
  // renders their sum.
  assert.ok(
    /val usage: TokenUsage = TokenUsage\(\)/.test(viewModel),
    'the state must keep a settled `usage` field',
  )
  assert.ok(
    /val liveUsage: TokenUsage = TokenUsage\(\)/.test(viewModel),
    'the state must keep a separate `liveUsage` field for the in-flight attempt',
  )
  assert.ok(
    /val displayUsage: TokenUsage get\(\) = usage \+ liveUsage/.test(viewModel),
    'the chip must render usage + liveUsage',
  )
  // The projection is the only writer of the settled total.
  const refresh = functionBody(viewModel, 'refreshUsage')
  assert.ok(
    /usage = snapshot\.total/.test(refresh),
    'only the projection may write the settled total',
  )
  // And nothing else may assign to `usage =` from a settlement.
  const assignsToUsage = viewModel.match(/^\s*usage = (?!TokenUsage|snapshot)/gm) ?? []
  assert.equal(
    assignsToUsage.length,
    0,
    `only refreshUsage may assign the settled total; found ${assignsToUsage.length} other writes`,
  )
})

// ------------------------------------------------------- transcript integrity

console.log('\n=== transcript integrity (the display fixes) ===')

check('the per-session dedupe set is cleared when the session changes', () => {
  // `seq` restarts at zero for each session, so a shared `seenSeqs` made a newly
  // opened session's events look like replays and the transcript stayed empty.
  const body = functionBody(viewModel, 'activateSession')
  assert.ok(
    /seenSeqs\.clear\(\)/.test(body),
    'activateSession must clear seenSeqs',
  )
})

check('the tool-call index is cleared with the session and shared across batches', () => {
  const activate = functionBody(viewModel, 'activateSession')
  assert.ok(/callIndex\.clear\(\)/.test(activate), 'activateSession must clear callIndex')
  // The index must outlive a single fold() call: a tool/result arrives in a
  // later batch than the tool/call that created its card.
  assert.ok(
    /SessionParser\.fold\(fresh,\s*durable,\s*callIndex\)/.test(viewModel) ||
      /SessionParser\.fold\(fresh,\s*_state\.value\.messages,\s*callIndex\)/.test(viewModel),
    'fold() must be given the long-lived callIndex, not a fresh map',
  )
  assert.ok(
    /private val callIndex: CallIndex/.test(viewModel),
    'callIndex must be a view-model field so it survives across batches',
  )
})

check('a single activateSession path owns every session switch', () => {
  // The implicit create inside send() used to start following without resetting
  // the dedupe state, so a brand-new session showed no reply.
  //
  // The call now carries the address as well as the id: a subagent child cannot
  // be followed by id, so the address travels with the switch.
  const calls = viewModel.match(/startFollowing\(sessionId,\s*address\)/g) ?? []
  assert.equal(
    calls.length,
    1,
    `startFollowing must be invoked from exactly one place (activateSession); ` +
      `found ${calls.length} call sites`,
  )
  const activate = functionBody(viewModel, 'activateSession')
  assert.ok(
    activate.includes('startFollowing(sessionId, address)'),
    'the one startFollowing call must live inside activateSession',
  )
  // And every session switch must go through it.
  const activations = viewModel.match(/activateSession\(/g) ?? []
  assert.ok(
    activations.length >= 3,
    `openSession, createSession and sendRemote must all route through ` +
      `activateSession; found only ${activations.length} references`,
  )
})

check('local echoes are reconciled against durable user/message events', () => {
  assert.ok(
    viewModel.includes('reconcileEchoes'),
    'ChatViewModel must reconcile locally echoed user messages',
  )
  // Echoes are held out of the fold and re-appended, so a durable message
  // cannot be ordered before an echo that was sent after it.
  assert.ok(
    /splitEchoes/.test(viewModel),
    'echoes must be split out before the fold and re-appended after it',
  )
  const foldCall = /SessionParser\.fold\(fresh,\s*durable,\s*callIndex\)/.test(viewModel)
  assert.ok(
    foldCall,
    'fold() must receive the durable rows only, not the pending echoes',
  )
  assert.ok(
    /folded\.messages \+ reconcileEchoes\(echoes, fresh\)/.test(viewModel),
    'the surviving echoes must be appended after the folded messages',
  )
  assert.ok(
    parser.includes('userTextOf'),
    'the parser must expose the text of a user/message event for reconciliation',
  )
  // A `user/message` carries its content at data.content, unlike every other
  // message-bearing event, which nests it under data.message.
  const body = functionBody(parser, 'userTextOf')
  assert.ok(
    body.includes('data.optJSONArray("content")'),
    'userTextOf must read data.content, not data.message.content',
  )
})

check('the local echo id prefix is what reconciliation matches on', () => {
  assert.ok(
    /ECHO_PREFIX\s*=\s*"local-"/.test(viewModel),
    'the echo prefix constant must stay "local-"',
  )
  assert.ok(
    /message\.id\.startsWith\(ECHO_PREFIX\)/.test(viewModel),
    'reconcileEchoes must find echoes by the prefix',
  )
  assert.ok(
    /id = "\$ECHO_PREFIX\$?\{?/.test(viewModel),
    'the echoed message must be created with the ECHO_PREFIX id',
  )
})

check('a broken mux stream is retried, but only for transport failures', () => {
  assert.ok(
    /isTransportFailure/.test(dshClient),
    'DshClient must distinguish a transport failure from a logical stream error',
  )
  assert.ok(
    /MAX_STREAM_RETRIES/.test(dshClient),
    'the reconnect loop must be bounded',
  )
  const body = functionBody(dshClient, 'stream')
  assert.ok(
    body.includes('ensureActive'),
    'a cancelled collector must not be retried',
  )
})

check('a closed socket is dropped so it cannot be reused', () => {
  // A normal close still leaves the socket unusable; keeping it cached made every
  // later stream hang on a dead connection.
  const body = functionBody(dshClient, 'onClosed')
  assert.ok(body.includes('failAll('), 'onClosed must invalidate the cached socket')
})

check('a failed send does not leave a stream waiting forever', () => {
  // `send` returns false rather than throwing when the socket is already closed,
  // and the socket's teardown already ran before this stream existed.
  const body = functionBody(dshClient, 'stream')
  assert.ok(
    /val opened = ws\.send\(/.test(body),
    'stream must check whether the open frame was actually sent',
  )
  assert.ok(
    /if \(!opened\)/.test(body),
    'a refused send must fail the stream instead of waiting',
  )
  assert.ok(
    /failAll\(DshException\("连接不可用", "ws-closed"\), ws\)/.test(body),
    'a refused send must also drop the socket, or the retry reuses it',
  )
})

check('session creation is serialized behind a mutex', () => {
  // Two quick taps on Send both observed "no active session" and each created
  // one; the second activateSession then discarded the first transcript.
  assert.ok(
    /private val sessionCreation = Mutex\(\)/.test(viewModel),
    'the view model must hold a session-creation mutex',
  )
  assert.ok(
    /sessionCreation\.withLock/.test(viewModel),
    'session creation must happen inside the lock',
  )
  // The check and the create must both be inside the lock, or the race remains.
  // `resolveSession` is a single expression, so its body is scanned directly from
  // the `withLock {` that follows the signature rather than via functionBody
  // (which would stop at that brace).
  //
  // It re-reads the *address* now, not just the id: a subagent child cannot be
  // written to by id, so the re-read has to yield something the server accepts.
  const resolveAt = viewModel.indexOf('fun resolveSession(')
  assert.ok(resolveAt >= 0, 'resolveSession must exist')
  const lockAt = viewModel.indexOf('withLock', resolveAt)
  assert.ok(lockAt >= 0, 'resolveSession must use withLock')
  const body = viewModel.slice(lockAt, lockAt + 500)
  assert.ok(
    /_state\.value\.activeAddress\s*\?:/.test(body),
    'resolveSession must re-read activeAddress inside the lock',
  )
  assert.ok(
    /createSessionLocked/.test(body),
    'resolveSession must create through the shared locked helper',
  )
})

check('the workspace refresh supersedes rather than drops', () => {
  // `addWorkspace` sets loadingWorkspaces and then refreshes; a drop-on-busy
  // guard made that refresh a guaranteed no-op, so the new workspace never
  // appeared and the selection pointed at a row the UI did not have.
  const body = functionBody(viewModel, 'refreshWorkspaces')
  assert.ok(
    !/if \(_state\.value\.loadingWorkspaces\) return/.test(body),
    'refreshWorkspaces must not silently drop a request made while one is in flight',
  )
  assert.ok(
    /workspaceRequest\?\.cancel\(\)/.test(body),
    'a newer refresh must cancel the superseded one',
  )
  // A response from a previous desktop must not be written at all.
  assert.ok(
    /if \(prefs\.serverUrl != origin\) return@launch/.test(body),
    'a response whose authority changed must be discarded',
  )
})

check('addWorkspace selects before refreshing', () => {
  // Selecting first means the refresh finds the new id in the list; refreshing
  // first left selectedWorkspaceId pointing at a workspace that was not there.
  const body = functionBody(viewModel, 'addWorkspace')
  const selectAt = body.indexOf('selectWorkspace(')
  const refreshAt = body.indexOf('refreshWorkspaces()')
  assert.ok(selectAt >= 0 && refreshAt >= 0, 'addWorkspace must do both')
  assert.ok(
    selectAt < refreshAt,
    'addWorkspace must select the new workspace before refreshing the list',
  )
})

check('a stale socket callback cannot tear down its replacement', () => {
  // Without the identity guard, a late failure from a socket that was already
  // replaced closes the streams now running on its successor.
  const body = functionBody(dshClient, 'failAll')
  assert.ok(
    /socket\s*!==\s*from/.test(body),
    'failAll must ignore callbacks from a socket that is no longer current',
  )
})

check('the socket is keyed to its URL so a re-pair cannot reuse it', () => {
  assert.ok(
    /socketUrl/.test(dshClient),
    'DshClient must remember which URL the live socket was opened for',
  )
  const body = functionBody(dshClient, 'ensureSocket')
  assert.ok(
    /url\s*==\s*socketUrl/.test(body),
    'ensureSocket must reuse the socket only when the URL still matches',
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
