// Guards the 1.1.7 fixes and features. Every one of them compiled cleanly while
// broken, which is the only reason this file exists:
//
//   1. `ask_user_question` still could not be answered. 1.1.6 built the whole
//      `$events` waterfall channel correctly and then opened it from only two of
//      the four paths that establish a connection — and it never re-opened it
//      once the stream ended, so a phone that slept could not answer a question
//      again until the app was restarted.
//   2. "Check for updates" then "download" did nothing. Four independent causes
//      met in one button: no state emitted until the first response *byte*, an
//      exception mid-download killing the collector that updates the screen, two
//      taps writing the same partial file, and an already-present APK trusted
//      without re-hashing.
//   3. The app reported "无法连接" over a working connection. A failed list call
//      was treated as proof the desktop was gone, `connectionState` could not
//      answer "is it up right now", and a failure that happened while the socket
//      stayed open had no event to retract it.
//   4. "复制对话" — the transcript as Markdown, with fences that survive their own
//      content.
//   5. "查看文件" — the `workspaceFiles` namespace, whose first argument is a
//      session *lookup* and whose third is required even though every field in it
//      is optional.
//
// usage: node test/regression-1.1.7.test.mjs
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

const dshClient = src('data/DshClient.kt')
const viewModel = src('ui/ChatViewModel.kt')
const updateManager = src('update/UpdateManager.kt')
const mainActivity = src('MainActivity.kt')
const chatScreen = src('ui/ChatScreen.kt')
const appShell = src('ui/AppShell.kt')
const transcript = src('data/Transcript.kt')
const workspaceFile = src('data/WorkspaceFile.kt')
const filesDialog = src('ui/components/FilesDialog.kt')
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

/** The balanced `{...}` group starting at the first `{` at or after `from`. */
function braceBlock(source, from) {
  const start = source.indexOf('{', from)
  assert.ok(start > 0, `no block found after offset ${from}`)
  let depth = 0
  for (let i = start; i < source.length; i++) {
    if (source[i] === '{') depth++
    else if (source[i] === '}') {
      depth--
      if (depth === 0) return source.slice(start + 1, i)
    }
  }
  throw new Error('unbalanced braces')
}

// ============== 1. the question stream is opened everywhere and stays open

console.log('=== the ask_user_question channel is always open ===')

check('every path that establishes a connection subscribes to $events', () => {
  // This is the actual 1.1.7 bug: `startEvents()` was called from `autoConnect`
  // and `reconnect` only. `pairWithCode` (scan the QR / type the code) and
  // `pair` (paste the `dsh web` link) — the two paths a *new* install uses —
  // never called it, so the very first run after pairing could not answer a
  // question at all. Pairing itself succeeded and every other call worked, which
  // is why it read as "the feature is missing" rather than "this is broken".
  //
  // `setBackend` is the fifth: launching in API mode makes `autoConnect` return
  // before subscribing, so switching back to remote mode has to subscribe too.
  const paths = ['pairWithCode', 'pair', 'autoConnect', 'reconnect', 'setBackend']
  for (const name of paths) {
    const body = functionBody(viewModel, name)
    assert.match(
      body,
      /startEvents\(\)/,
      `${name} establishes a connection, so it must subscribe to the forwarded ` +
        'event stream — otherwise a question asked in that session is unanswerable',
    )
  }
})

check('the client id is cleared on every re-arm, not just the first', () => {
  // A `clientId` identifies one stream generation and dies with it. Clearing it
  // only *before* the loop covers the first attempt: on every re-arm the id of
  // the stream that just ended stayed in the field, and the host re-delivers a
  // still-pending question, so that question *was* on screen and answerable.
  // The gateway finds no delivery for a dead `(clientId, eventId)` pair and
  // reports success, so the answer would be accepted and silently dropped —
  // the question simply reappearing as though the tap had not registered.
  const body = functionBody(viewModel, 'startEvents')
  const loopAt = body.indexOf('while (isActive)')
  assert.ok(loopAt >= 0, 'the subscription must loop')
  const loop = body.slice(loopAt)
  assert.match(
    loop,
    /eventsClientId\s*=\s*""/,
    'the identity must be cleared inside the loop: re-arming starts a new stream ' +
      'generation, and the previous generation\'s id is not valid for it',
  )
})

check('the stream is re-armed after it ends, not opened once', () => {
  // `forwardedEvents` retries a dead socket internally but gives up after
  // `MAX_STREAM_RETRIES`, and it also returns *normally* when the host ends the
  // stream. Either way the collector returned, `.catch { }` swallowed the reason,
  // and the job finished — after which nothing ever subscribed again. The ability
  // to answer a question was therefore tied to how long the process had been up.
  const body = functionBody(viewModel, 'startEvents')
  assert.match(
    body,
    /while\s*\(\s*isActive\s*\)/,
    'the subscription must loop, or a single stream ending disables answering ' +
      'for the rest of the process lifetime',
  )
  assert.match(
    body,
    /delay\(\s*EVENTS_RETRY_DELAY_MS\s*\)/,
    'the loop must pause between attempts: `stream` returns immediately when the ' +
      'socket cannot be opened at all, so re-subscribing without a delay is a hot loop',
  )
  // Re-delivery is safe only because a duplicate is ignored by eventId.
  assert.match(
    body,
    /queue\.any\s*\{\s*it\.eventId\s*==\s*question\.eventId\s*\}/,
    'a re-opened stream re-delivers pending waterfalls, so duplicates must still ' +
      'be deduplicated by eventId',
  )
})

check('the answer quotes the client id of the stream that is live now', () => {
  // The id is re-issued on every re-open and dies with its stream, so it must be
  // replaced rather than kept. A stale id identifies nothing and the gateway
  // finds no delivery for it.
  const body = functionBody(viewModel, 'startEvents')
  assert.match(
    body,
    /eventsClientId\s*=\s*""/,
    'opening a stream must clear the previous identity, so an answer sent in the ' +
      'gap fails rather than quoting a dead id',
  )
  assert.match(body, /"ready"/, 'the new identity arrives in the ready frame')
  assert.match(
    functionBody(viewModel, 'answerQuestion'),
    /val clientId = eventsClientId/,
    'the answer must read the id captured from the stream',
  )
})

check('the stream cannot be opened without an origin', () => {
  // `stream` on a blank origin would build a URL from nothing and fail in a way
  // that looks like a network problem.
  const body = functionBody(viewModel, 'startEvents')
  assert.match(
    body,
    /if\s*\(origin\.isBlank\(\)\)\s*return/,
    'subscribing without a configured server must be a no-op',
  )
})

// ======================= 2. the update can actually be downloaded

console.log('\n=== the download reports every outcome ===')

check('progress is emitted before the request is sent', () => {
  // `client.newCall(...).execute()` returns only once the *response headers*
  // arrive, and `apkUrl` redirects to a second host — a second or more on a
  // phone. Until then nothing was emitted, so the button stayed in its "download"
  // appearance and the tap looked ignored. That is half of "点了没反应".
  //
  // Comments are stripped first: this class's own KDoc names `client.newCall`,
  // and a check that counted that mention would pass on a file that never made
  // the call at all.
  const body = stripLineComments(functionBody(updateManager, 'download'))
  const firstEmit = body.indexOf('emit(')
  const request = body.indexOf('client.newCall(')
  assert.ok(firstEmit >= 0, 'download must emit at least one state')
  assert.ok(request >= 0, 'download must issue a request')
  assert.ok(
    firstEmit < request,
    'the first state must be emitted *before* the request goes out, or the tap ' +
      'has no visible effect until the first byte arrives',
  )
})

check('a failure mid-download is reported instead of thrown', () => {
  // The collector is a plain `collect { }` in the UI. An exception escaping the
  // flow kills the coroutine that updates the screen, so nothing is emitted, the
  // previous state stays up, and a *failed* download is indistinguishable from a
  // tap that did nothing. A 12 MB fetch over Wi-Fi drops often enough that this
  // was guaranteed to happen.
  const body = functionBody(updateManager, 'download')
  assert.match(
    body,
    /catch\s*\(t:\s*Throwable\)/,
    'the download must catch its own failures and turn them into a state',
  )
  assert.match(
    body,
    /UpdateState\.Failed/,
    'the caught failure must be reported as Failed so the button becomes usable',
  )
  // Cancellation is the caller leaving, not a failed download.
  assert.match(
    body,
    /CancellationException/,
    'cancellation must be re-thrown rather than reported as a download failure',
  )
})

check('two taps cannot write the same partial file at once', () => {
  // Two concurrent downloads each open an output stream on the same `.part`
  // file: the bytes interleave, the result hashes to neither release, and the
  // only symptom is a SHA-256 mismatch that blames the server.
  const body = functionBody(updateManager, 'download')
  assert.match(
    body,
    /compareAndSet|synchronized|Mutex/,
    'a second concurrent download must be refused, not interleaved',
  )
  assert.match(
    updateManager,
    /AtomicBoolean/,
    'the guard must be atomic: a plain flag read-then-written from two coroutines ' +
      'does not exclude anything',
  )
})

check('a reused APK is re-hashed, not merely present', () => {
  // Existence proves only that something is at that path: a half-written file
  // from a killed process, a file the user replaced, or an older release sharing
  // the version name all pass an `isFile && length > 0` test and then fail at the
  // installer with an error naming none of them.
  assert.match(
    updateManager,
    /suspend fun verify\(/,
    'the manager must be able to verify an on-disk APK against the manifest hash',
  )
  const verify = functionBody(updateManager, 'verify')
  assert.match(
    verify,
    /sha256Of\(/,
    'verification must recompute the hash rather than trust the file',
  )
  assert.match(
    verify,
    /equals\(info\.sha256,\s*ignoreCase\s*=\s*true\)/,
    'the recomputed hash must be compared against the manifest, case-insensitively',
  )
  // And the install handler must actually use it before handing the file over.
  const handler = mainActivity.slice(mainActivity.indexOf('onInstallUpdate'))
  assert.match(
    handler,
    /updater\.verify\(/,
    'the install handler must verify a reused file before passing it to the installer',
  )
})

check('a file that fails verification is discarded', () => {
  // Leaving it would make every retry take the same dead branch and fail the same
  // way, which reads as a permanently broken update.
  const handler = mainActivity.slice(mainActivity.indexOf('onInstallUpdate'))
  assert.match(
    handler,
    /existing\?\.delete\(\)/,
    'a stale file must be removed so the next attempt downloads a fresh one',
  )
})

// ===================== 3. a working connection is not reported as broken

console.log('\n=== a working connection is never called broken ===')

check('a failed list call does not clear the connected flag', () => {
  // `refreshSessions` runs on every drawer open and after every reconnect, and
  // one attempt can lose a race with the radio waking up — a connect timeout that
  // is over a second later. Clearing `connected` there greyed out the model and
  // workspace chips and made the app bar read "未连接" while the transcript was
  // streaming normally underneath. That is "显示无法连接但实际上连接正常".
  const body = functionBody(viewModel, 'refreshSessions')
  const catchAt = body.indexOf('catch (t: Throwable)')
  assert.ok(catchAt >= 0, 'refreshSessions must handle a failed list call')
  const handler = body.slice(catchAt)
  assert.doesNotMatch(
    handler,
    /connected\s*=\s*false/,
    'a failed list request is not evidence the desktop is unreachable: the mux ' +
      'socket is the only authority, so `connected` must be left alone here',
  )
  assert.match(
    handler,
    /errorIsTransport\s*=/,
    'the failure must still be flagged so the connection state can retract it',
  )
})

check('connectionState can be read, not only observed', () => {
  // A replaying `SharedFlow` pushes only *changes*, so a consumer that raised a
  // banner while the socket was already open waited for an emission that would
  // never come. That is the mechanism by which the app kept asserting a
  // disconnection over a live connection.
  assert.match(
    dshClient,
    /MutableStateFlow\(false\)/,
    'connectionState must be backed by a StateFlow so it has a current value',
  )
  assert.match(
    dshClient,
    /val connectionState:\s*StateFlow<Boolean>/,
    'the exposed type must be a StateFlow',
  )
  assert.doesNotMatch(
    dshClient,
    /MutableSharedFlow<Boolean>/,
    'a SharedFlow cannot answer "is it up right now" and must not come back',
  )
})

check('the current socket state is applied in both directions', () => {
  // 1.1.6 only reacted to an "up" edge. That leaves a banner raised while the
  // socket was already open with no event to retract it, and leaves the UI marked
  // "已连接" after the socket actually dropped.
  const init = viewModel.slice(viewModel.indexOf('dsh.connectionState.collect'))
  const body = init.slice(0, init.indexOf('\n    }\n'))
  assert.match(
    body,
    /connected\s*=\s*true/,
    'a live socket must restore `connected`, or a transient failure leaves the UI ' +
      'permanently marked as disconnected',
  )
  assert.match(
    body,
    /connected\s*=\s*false/,
    'a dropped socket must also be reflected, or the UI stays "已连接" while nothing works',
  )
})

check('a failure on a live socket is not shown as a connection error', () => {
  // The collector can only retract on a *change*. A single request can fail while
  // the socket stays open — a connect timeout that lost the race, a stream that
  // exhausted its retries while another stream kept the mux alive — and with no
  // state change there is no event to react to, so the banner asserted "无法连接"
  // over a socket that was open at that moment.
  assert.match(
    viewModel,
    /private fun transportAwareError\(/,
    'the failure text must be filtered through the live socket state',
  )
  const body = functionBody(viewModel, 'transportAwareError')
  assert.match(
    body,
    /connectionState\.value/,
    'the check must read the socket state at the moment of the failure',
  )
  assert.match(
    body,
    /isTransportError\(/,
    'only transport failures may be suppressed; a server-side error must survive',
  )
  // Written as `return if (...) null else message`, so the check is that a live
  // socket yields no text rather than that the literal `return null` appears.
  assert.match(
    body,
    /connectionState\.value\)\s*null\s*else\s*message/,
    'a transport failure on a live socket must produce no banner at all',
  )
})

check('the other refresh paths use the same guard', () => {
  // `refreshSessions` is not special: the workspace list and the model catalog
  // fail in exactly the same way, and the catalog is the *slowest* unary call the
  // app makes, so it is the most likely of all of them to time out on a desktop
  // that is merely busy. A fix applied to one of three identical paths is the
  // shape of bug this whole file exists to catch.
  for (const [name, label] of [
    ['refreshWorkspaces', '工作区列表'],
    ['loadRemoteModels', '模型列表'],
  ]) {
    const body = functionBody(viewModel, name)
    assert.match(
      body,
      /transportAwareError\(/,
      `${name} must route its failure through the live-socket guard too`,
    )
    assert.match(
      body,
      /errorIsTransport\s*=/,
      `${name} must flag the failure so the connection state can retract it`,
    )
  }
})

// ============================== 4. copying the conversation

console.log('\n=== the transcript can be copied ===')

check('the transcript is exported as Markdown with role headings', () => {
  // The copy is read by someone who has never seen this app. Without a heading
  // per message the user's question and the assistant's answer become the same
  // voice, which is what makes a pasted transcript unreadable.
  assert.match(
    transcript,
    /fun toMarkdown\(/,
    'the transcript must have a Markdown export',
  )
  const body = functionBody(transcript, 'toMarkdown')
  assert.match(body, /"# "/, 'the document must open with a title heading')
  assert.match(
    transcript,
    /"## "/,
    'each message must be introduced by its own heading',
  )
  assert.match(
    transcript,
    /Role\.USER -> "你"/,
    'the user and the assistant must be labelled distinctly',
  )
})

check('reasoning and tool calls survive the copy, fenced', () => {
  // They are usually the reason for copying at all ("why did it do that?"), and
  // fencing keeps a `#` inside a shell command from turning into a heading.
  const body = functionBody(transcript, 'blockMarkdown')
  assert.match(body, /is Block\.Reasoning/, 'reasoning must be exported')
  assert.match(body, /is Block\.ToolCall/, 'tool calls must be exported')
  assert.match(body, /fence\(/, 'arbitrary tool text must be fenced')
})

check('the fence outgrows backticks inside the content', () => {
  // Tool output regularly contains ``` (a pasted file, a nested Markdown
  // document). A fixed three-backtick fence closes early there and dumps the rest
  // of the output into the surrounding prose. Growing the fence is CommonMark's
  // own rule.
  const body = functionBody(transcript, 'fence')
  assert.match(
    body,
    /maxOf\(\s*3\s*,/,
    'the fence must be at least three backticks and grow with the content',
  )
  assert.match(
    body,
    /longest\s*\+\s*1/,
    'the fence must be strictly longer than the longest run inside the text',
  )
})

check('a message with nothing to render is skipped, not left as a bare heading', () => {
  const body = functionBody(transcript, 'toMarkdown')
  assert.match(
    body,
    /if\s*\(body\.isBlank\(\)\)\s*continue/,
    'an empty message must contribute nothing: a transcript of bare headings reads ' +
      'as though the assistant said nothing',
  )
})

check('the copy action is reachable and renders off the main thread', () => {
  assert.match(
    chatScreen,
    /onCopyTranscript/,
    'the transcript screen must offer the copy action',
  )
  assert.match(
    chatScreen,
    /LocalClipboardManager/,
    'the text must be placed on the real clipboard',
  )
  const body = functionBody(viewModel, 'copyTranscript')
  assert.match(
    body,
    /withContext\(Dispatchers\.Default\)/,
    'a long transcript must be rendered off the main thread, or the tap drops frames',
  )
})

// ============================== 5. viewing workspace files

console.log('\n=== workspace files can be browsed ===')

check('the scope argument is the session id, not the workspace id', () => {
  // `workspaceFiles` resolves its first argument as a *session lookup* and uses
  // that session's `cwd` as the root. A workspace id resolves to nothing and the
  // gateway answers with a lookup failure — it does not fall back to the sandbox
  // root. The two ids look interchangeable, so this is written down.
  const list = functionBody(dshClient, 'listWorkspaceFiles')
  assert.match(
    list,
    /put\("workspaceFileScopeId",\s*sessionId\)/,
    'the scope wire name is workspaceFileScopeId and its value is the session id',
  )
  assert.match(
    list,
    /workspaceFiles",\s*"list"/,
    'the listing is the workspaceFiles/list endpoint',
  )
  const view = functionBody(viewModel, 'openFiles')
  assert.match(
    view,
    /activeSessionId/,
    'the browser must take its scope from the active session',
  )
})

check('read always sends range, even when it carries nothing', () => {
  // Every *field* of `range` is optional, but the parameter itself is not: the
  // descriptor declares it as a required `json` argument and the gateway's
  // `assertExactArguments` refuses an args object with a missing key. Omitting it
  // because "it has no required fields" is exactly the mistake that ships as a
  // feature that silently does nothing.
  const read = functionBody(dshClient, 'readWorkspaceFile')
  assert.match(
    read,
    /put\("range",\s*range\)/,
    'the range parameter must always be present on the wire',
  )
  assert.match(
    read,
    /put\("offset",\s*offset\)/,
    'the offset is what makes a page meaningful, so it is always set',
  )
  assert.match(
    read,
    /workspaceFiles",\s*"read"/,
    'the read is the workspaceFiles/read endpoint',
  )
})

check('the listing sorts directories first', () => {
  // The server returns its backend's stable order, which interleaves files and
  // directories. Finding a subdirectory in a large listing then means reading the
  // whole list.
  assert.match(
    workspaceFile,
    /compareByDescending<WorkspaceFileEntry>\s*\{\s*it\.isDirectory\s*\}/,
    'directories must sort before files',
  )
})

check('a truncated listing says so', () => {
  // A partial listing that looks complete is how a user concludes a file does not
  // exist.
  assert.match(
    workspaceFile,
    /val truncated/,
    'the wire flag must be carried into the model',
  )
  assert.match(
    filesDialog,
    /listing\.truncated/,
    'the UI must state that the listing is partial',
  )
})

check('the file browser is reachable and rooted at a session', () => {
  assert.match(appShell, /FilesDialog\(/, 'the shell must host the file browser')
  assert.match(
    chatScreen,
    /onOpenFiles/,
    'the transcript screen must offer the file browser',
  )
  // With no session there is no workspace root to browse, so the entry point is
  // gated on one rather than opening onto a guessed directory.
  assert.match(
    chatScreen,
    /state\.activeSessionId != null[\s\S]{0,200}onOpenFiles/,
    'the browser is rooted at a session, so it must only be offered with one',
  )
  assert.match(
    viewModel,
    /files = null/,
    'switching sessions must close the browser: its root belongs to the old one',
  )
})

check('a directory entry is opened by name, joined onto the current path', () => {
  // The wire strips each child's absolute path — `workspaceFiles/list` returns
  // names and metadata only — so the full path has to be rebuilt client-side.
  const body = functionBody(workspaceFile, 'childOf')
  assert.match(body, /directory/, 'the join must take the current directory')
  assert.match(body, /name/, 'and the child name')
  assert.match(
    mainActivity,
    /WorkspaceFiles\.childOf\(vm\.state\.value\.filesPath,\s*name\)/,
    'the dialog names an entry by name, so the caller must join it onto the path',
  )
})

check('going up from the root is refused rather than guessed', () => {
  // The workspace root is the empty string; there is nothing above it to list.
  const body = functionBody(workspaceFile, 'parentOf')
  assert.match(
    body,
    /if\s*\(trimmed\.isEmpty\(\)\)\s*return null/,
    'the root must have no parent, or "up" would request an arbitrary directory',
  )
  assert.match(
    appShell,
    /canGoUp = state\.filesPath\.isNotBlank\(\)/,
    'the up action must be hidden at the root rather than failing when tapped',
  )
})

check('file text is shown verbatim rather than rendered as Markdown', () => {
  // The user asked to see the file, so the source is the honest answer; a
  // rendered view would hide exactly the syntax they opened it to check.
  assert.match(
    filesDialog,
    /page\.text/,
    'the viewer must show the file text',
  )
  assert.doesNotMatch(
    functionBody(filesDialog, 'FileViewer'),
    /Markdown\(/,
    'the file viewer must not render Markdown: the source is what was asked for',
  )
})

// ================================= 6. the release is coherent

console.log('\n=== 1.1.7 is the released version ===')

check('the CHANGELOG has a 1.1.7 section with real notes', () => {
  const section = /^## \[1\.1\.7\][\s\S]*?(?=^## \[|\Z)/m.exec(changelog)
  assert.ok(section, 'CHANGELOG.md must have a "## [1.1.7]" section')
  assert.ok(
    section[0].length > 400,
    'the 1.1.7 section is too short to be the release notes users will see',
  )
  // Each of the three reported bugs must be named, since that is what the
  // affected user searched for.
  assert.match(
    section[0],
    /提问|回答/,
    'the notes must name the unanswerable-question fix',
  )
  assert.match(
    section[0],
    /下载/,
    'the notes must name the download fix',
  )
  assert.match(
    section[0],
    /无法连接/,
    'the notes must name the spurious disconnected report',
  )
  // And both new features.
  assert.match(section[0], /复制对话/, 'the notes must name the copy feature')
  assert.match(section[0], /查看文件|文件/, 'the notes must name the file browser')
})

check('the CHANGELOG link block defines 1.1.7', () => {
  assert.match(
    changelog,
    /^\[1\.1\.7\]:\s+https:\/\/github\.com\/nmaych\/dsh-mobile\/compare\/v1\.1\.6\.\.\.v1\.1\.7$/m,
    'the link reference for 1.1.7 must exist, or the heading renders as literal text',
  )
})

check('the Gradle default version matches the newest CHANGELOG entry', () => {
  // Asserted as an invariant rather than as the literal "1.1.7": the literal is
  // supposed to move every release, and pinning it is what made the 1.1.6 suite
  // fail for a reason unrelated to 1.1.6.
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
    /node test\/regression-1\.1\.7\.test\.mjs/,
    'ci.yml must run test/regression-1.1.7.test.mjs',
  )
})

check('the protocol notes record the new wire facts', () => {
  // `workspaceFiles` is the first namespace this app uses whose first argument is
  // a *lookup* rather than a value, and whose third argument is required while
  // every field inside it is optional. Both are counter-intuitive enough that a
  // reader would "simplify" them back into a broken call.
  const protocol = read('docs/PROTOCOL.md')
  assert.match(
    protocol,
    /workspaceFiles/,
    'docs/PROTOCOL.md must document the workspace-files namespace',
  )
  assert.match(
    protocol,
    /workspaceFileScopeId/,
    'docs/PROTOCOL.md must record the lookup argument and its session-id meaning',
  )
  assert.match(
    protocol,
    /range/,
    'docs/PROTOCOL.md must record that read always sends range',
  )
})

check('the new sources are real files', () => {
  for (const rel of [
    'app-project/app/src/main/java/ai/deepseek/dshmobile/data/Transcript.kt',
    'app-project/app/src/main/java/ai/deepseek/dshmobile/data/WorkspaceFile.kt',
    'app-project/app/src/main/java/ai/deepseek/dshmobile/ui/components/FilesDialog.kt',
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
