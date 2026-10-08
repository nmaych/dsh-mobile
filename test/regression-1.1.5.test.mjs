// Guards the 1.1.5 fixes, which are the same kind the Kotlin compiler cannot see:
//
//   1. "新建对话时提示 session.create accepts workspaceId or cwd, not both". A
//      *field* bug, not a network or server one. Both keys are valid wire names on
//      that descriptor, so every type check passes; the server refuses the
//      *combination* at runtime. `createSessionLocked` passed the selected
//      workspace's id AND its path, so "new conversation" failed for every user who
//      had ever picked a workspace.
//
//   2. The scan-to-connect screen coming up in landscape. The capture Activity is
//      the scanner library's, and its `sensorLandscape` lives in the library's own
//      manifest — an XML attribute no compiler reads. The fix is an app-declared
//      portrait subclass, so the thing to pin is that the app declares it, points
//      `ScanOptions` at it, and leaves orientation locking off so the manifest stays
//      the single source of truth.
//
//   3. Release notes rendered as one flat paragraph. `notes` is Markdown (the
//      release workflow fills it from the CHANGELOG section), and the settings
//      screen passed it to a plain `Text`. A rendering bug: the string was correct,
//      the widget was not.
//
//   4. No way to download the update at all. The only install button lived in the
//      `ReadyToInstall` branch, which is reached *after* a download — so
//      `UpdateManager.download` was unreachable from the UI and "check for updates"
//      could report a new version and then do nothing. A missing call site, which
//      is exactly what no compiler complains about.
//
// usage: node test/regression-1.1.5.test.mjs
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
const settingsScreen = src('ui/SettingsScreen.kt')
const updateManager = src('update/UpdateManager.kt')
const mainActivity = src('MainActivity.kt')
const qrScan = src('ui/QrScan.kt')
const manifest = read('app-project/app/src/main/AndroidManifest.xml')
const changelog = read('CHANGELOG.md')
const updateDoc = read('docs/UPDATE.md')

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
 * A whole `fun <name>(...)` declaration, body included.
 *
 * Handles both Kotlin forms. A statement body is the signature plus its balanced
 * `{...}`. An *expression* body (`fun f(): Boolean = runCatching { ... }.isSuccess`)
 * puts its first `{` inside the expression, so the slice has to run to the end of
 * that expression — stopping at the closing brace would drop the trailing
 * `.isSuccess` and report the function as missing the very thing being checked.
 */
function functionText(source, name) {
  const decl = new RegExp(`fun\\s+${name}\\s*\\(`).exec(source)
  assert.ok(decl, `no declaration found for fun ${name}`)
  const braceStart = source.indexOf('{', decl.index)
  assert.ok(braceStart > 0, `fun ${name} has no body`)
  let depth = 0
  for (let i = braceStart; i < source.length; i++) {
    if (source[i] === '{') depth++
    else if (source[i] === '}') {
      depth--
      if (depth === 0) {
        const newline = source.indexOf('\n', i)
        return source.slice(decl.index, newline < 0 ? source.length : newline)
      }
    }
  }
  throw new Error(`unbalanced braces in fun ${name}`)
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

// ============================ 1. workspaceId and cwd are mutually exclusive

console.log('=== session/create sends one of workspaceId or cwd, never both ===')

check('the server rejects both keys, so the client must never send both', () => {
  // This is the contract the fix exists for. Quoted from the Harness'
  // `SessionCommandController.create`, which throws `gateway/bad-request`:
  //
  //     session.create accepts workspaceId or cwd, not both
  //
  // A `cwd` is not an alternative that happens to conflict — a workspace *is* a
  // directory, and the server resolves `cwd = workspace.path` itself.
  const body = functionBody(dshClient, 'createSession')
  const workspacePut = body.indexOf('req.put("workspaceId"')
  const cwdPut = body.indexOf('req.put("cwd"')
  assert.ok(workspacePut >= 0, 'createSession must be able to name a workspaceId')
  assert.ok(cwdPut >= 0, 'createSession must be able to name a cwd')
  assert.ok(
    /if\s*\(workspaceId\.isNullOrBlank\(\)\s*&&\s*!cwd\.isNullOrBlank\(\)\)/.test(body),
    'the cwd must be written only when no workspaceId is set; otherwise the ' +
      'request carries both keys and the server answers "session.create accepts ' +
      'workspaceId or cwd, not both"',
  )
})

check('the workspace wins over its own path, and the path is not also sent', () => {
  // The 1.1.4 regression verbatim: `cwd = selectedWorkspacePath()` *and*
  // `workspaceId = selectedWorkspaceId`. Both were correct values; sending both
  // was the bug.
  const locked = functionBody(viewModel, 'createSessionLocked')
  assert.ok(
    /if\s*\(workspaceId\.isBlank\(\)\)\s*selectedWorkspacePath\(\)\s*else\s*null/.test(locked),
    'createSessionLocked must send the workspace path only when no workspace is ' +
      'selected, or a user who picked a workspace cannot create a session at all',
  )
})

check('the mutual exclusion is documented where the next reader will look', () => {
  // The rule is a wire contract, so it belongs next to the wire call as well as in
  // the protocol doc — the failure is invisible locally, and a future reader who
  // only sees "both fields are optional" would put them back together.
  assert.match(
    dshClient,
    /mutually exclusive/i,
    'DshClient.createSession must say the two fields are mutually exclusive',
  )
  assert.match(
    viewModel,
    /mutually exclusive/i,
    'the view model call site must say the two fields are mutually exclusive',
  )
  assert.match(
    read('docs/PROTOCOL.md'),
    /not both|互斥/,
    'docs/PROTOCOL.md must record the constraint on session/create',
  )
})

// ================================= 2. the capture screen is portrait

console.log('\n=== the QR capture screen is portrait ===')

check('the app declares its own portrait capture activity', () => {
  // The library's `CaptureActivity` is declared `sensorLandscape` in the library's
  // own manifest. That entry belongs to a dependency, so it cannot be edited from
  // here — the direction has to come from an activity this app declares.
  const activity = /<activity[^>]*PortraitCaptureActivity[^>]*>/.exec(manifest)
  assert.ok(activity, 'the manifest must declare PortraitCaptureActivity')
  assert.match(
    activity[0],
    /android:screenOrientation="portrait"/,
    'the app-declared capture activity must be portrait',
  )
  assert.match(
    activity[0],
    /android:exported="false"/,
    'the capture screen is started only by this app through the Activity result ' +
      'API, so it must not be exported',
  )
})

check('the portrait activity is a real subclass of the library activity', () => {
  const rel = 'app-project/app/src/main/java/ai/deepseek/dshmobile/ui/PortraitCaptureActivity.kt'
  assert.ok(exists(rel), 'ui/PortraitCaptureActivity.kt is missing')
  const body = read(rel)
  assert.match(
    body,
    /class PortraitCaptureActivity\s*:\s*CaptureActivity\(\)/,
    'it must extend the library CaptureActivity, or the camera preview and result ' +
      'handling would have to be reimplemented',
  )
  assert.match(
    body,
    /import\s+com\.journeyapps\.barcodescanner\.CaptureActivity/,
    'the library CaptureActivity must be imported, not shadowed locally',
  )
})

check('ScanOptions points at the portrait activity', () => {
  // Declaring the activity is not enough: the scanner must be launched into it.
  assert.match(
    qrScan,
    /setCaptureActivity\(PortraitCaptureActivity::class\.java\)/,
    'the scanner must launch PortraitCaptureActivity, or it keeps using the ' +
      'library landscape one and the declaration does nothing',
  )
})

check('orientation locking is off so the manifest is the only source of truth', () => {
  // `setOrientationLocked` defaults to true, which makes `CaptureManager` also
  // call `setRequestedOrientation` from whatever it reads at creation. Two
  // sources for one answer can only agree or surprise, so it is disabled and the
  // manifest decides.
  assert.match(
    qrScan,
    /setOrientationLocked\(false\)/,
    'the library orientation lock must be disabled; the manifest declares the ' +
      'direction, and a second source of truth can only disagree',
  )
  assert.doesNotMatch(
    qrScan,
    /setOrientationLocked\(true\)/,
    'the library orientation lock must not be re-enabled',
  )
})

// ============================== 3. release notes are Markdown

console.log('\n=== the update notes are rendered as Markdown ===')

check('the notes are handed to the Markdown renderer, not a plain Text', () => {
  // `notes` is Markdown by construction: the release workflow fills it from the
  // CHANGELOG section, which carries `###` headings, `-` bullets and `**bold**`.
  //
  // Asserted at the *call site*, inside the Available branch. Merely finding
  // `UpdateNotes` somewhere in the file is not enough — the definition alone
  // satisfies that, so a branch that fell back to `Text(u.info.notes)` would slip
  // through and ship the exact bug this guards.
  const at = settingsScreen.indexOf('is UpdateState.Available ->')
  assert.ok(at >= 0, 'the Available branch must exist')
  const branch = braceBlock(settingsScreen, at)
  assert.match(
    branch,
    /UpdateNotes\(\s*u\.info\.notes/,
    'the Available branch must render the notes through the Markdown-aware ' +
      'component, passing u.info.notes to it',
  )
  assert.doesNotMatch(
    branch,
    /Text\(\s*u\.info\.notes/,
    'the notes must not be passed straight to a plain Text: that flattens the ' +
      'headings and bullets the CHANGELOG exists to carry',
  )

  const notes = functionBody(settingsScreen, 'UpdateNotes')
  assert.match(
    notes,
    /Markdown\(/,
    'UpdateNotes must use the transcript Markdown renderer, so one renderer is ' +
      'maintained rather than two that drift',
  )
  assert.match(
    notes,
    /verticalScroll/,
    'the notes block must scroll: a real entry runs to thousands of characters',
  )
  assert.match(
    notes,
    /heightIn\(max/,
    'the notes block must be height-capped, or it pushes the download button off ' +
      'the screen — and that button is the point of the card',
  )
})

check('the renderer is imported rather than reimplemented', () => {
  assert.match(
    settingsScreen,
    /import\s+ai\.deepseek\.dshmobile\.ui\.components\.Markdown/,
    'SettingsScreen must import the shared Markdown composable',
  )
})

check('the manifest documents notes as Markdown', () => {
  assert.match(
    updateManager,
    /notes`? is \*\*Markdown\*\*/,
    'UpdateManager must record that `notes` is Markdown, so the next reader does ' +
      'not "fix" it back into a plain Text',
  )
  assert.match(
    updateDoc,
    /Markdown/,
    'docs/UPDATE.md must tell a self-hoster that notes are rendered as Markdown',
  )
})

// ============================== 4. the update can be downloaded in-app

console.log('\n=== the update can be downloaded from inside the app ===')

check('the Available state offers a download button', () => {
  // The 1.1.4 regression: `Available` rendered the version and the notes and
  // stopped. The only install button was in `ReadyToInstall`, which is reached
  // *after* a download, so `UpdateManager.download` had no caller in the UI.
  const at = settingsScreen.indexOf('is UpdateState.Available ->')
  assert.ok(at >= 0, 'the Available branch must exist')
  const branch = braceBlock(settingsScreen, at)
  assert.match(
    branch,
    /Button\(onClick = onInstallUpdate\)/,
    'the Available branch must offer the download, or the user is told a new ' +
      'version exists with no way to get it',
  )
})

check('checking for updates leads to downloading, not a dead end', () => {
  assert.match(
    mainActivity,
    /updater\.download\(current\.info\)/,
    'the UI must actually call UpdateManager.download',
  )
  assert.match(
    mainActivity,
    /is UpdateState\.Available ->/,
    'the download must be reachable from the Available state',
  )
})

check('an unknown content length does not render as a stuck 0%', () => {
  // Some servers send no Content-Length. A determinate bar at 0% says "stuck",
  // which is the one thing that is not true.
  const at = settingsScreen.indexOf('is UpdateState.Downloading ->')
  assert.ok(at >= 0, 'the Downloading branch must exist')
  const branch = braceBlock(settingsScreen, at)
  assert.match(
    branch,
    /if \(u\.total > 0\)[\s\S]*?progress = \{ u\.percent \/ 100f \}/,
    'a known length must drive a determinate bar',
  )
  assert.match(
    branch,
    /else \{[\s\S]*?LinearProgressIndicator\(modifier = Modifier\.fillMaxWidth\(\)\)/,
    'an unknown length must use the indeterminate bar',
  )
})

check('missing install permission is a step, not a failure', () => {
  // Reporting it as `Failed` threw away a downloaded, hash-verified 12 MB APK and
  // made the user fetch it again to retry a system toggle.
  assert.match(
    updateManager,
    /data class InstallPermissionRequired\(val file: File, val info: UpdateInfo\)/,
    'the permission must be its own state, carrying the verified file',
  )
  assert.match(
    settingsScreen,
    /is UpdateState\.InstallPermissionRequired ->/,
    'the settings screen must render that state',
  )
  // And it must be reachable in the `when` in MainActivity, or the state would be
  // rendered but never left.
  assert.match(
    mainActivity,
    /is UpdateState\.InstallPermissionRequired -> installVerified\(current\.file, current\.info\)/,
    'retrying from the permission state must install the file already on disk',
  )
})

check('the permission is checked when installing, not when downloading', () => {
  // Checking it before the download would ask for a permission whose need the
  // user cannot judge yet, and would strand a perfectly good APK behind a prompt.
  // The download path must therefore not consult it at all; the install path must.
  const download = functionBody(updateManager, 'download')
  assert.doesNotMatch(
    download,
    /canInstallPackages|requestInstallPermission/,
    'the download must not gate on the install permission; that check belongs at ' +
      'the moment the installer is launched',
  )
  const installAt = mainActivity.indexOf('fun installVerified(')
  assert.ok(installAt >= 0, 'the install path must be a named function')
  const install = braceBlock(mainActivity, installAt)
  assert.match(
    install,
    /updater\.canInstallPackages\(\)/,
    'the install path must check the permission before handing the file over',
  )
  assert.match(
    install,
    /UpdateState\.InstallPermissionRequired/,
    'a refusal must move to the permission state rather than an error, so the ' +
      'verified file survives',
  )
})

check('an already-downloaded APK is reused instead of re-fetched', () => {
  assert.match(
    updateManager,
    /fun downloadedFile\(/,
    'UpdateManager must expose the already-downloaded file for a given release',
  )
  assert.match(
    mainActivity,
    /updater\.downloadedFile\(current\.info\)/,
    'the install handler must reuse a verified file rather than download again',
  )
})

check('a refused install is reported rather than swallowed', () => {
  // `startActivity` throws on a device with no package installer, so a silent
  // `install()` left a button that looked dead.
  const body = functionText(updateManager, 'install')
  assert.match(
    body,
    /runCatching/,
    'install must catch the failure rather than letting it crash the UI',
  )
  assert.match(
    body,
    /\.isSuccess/,
    'install must return the outcome so the caller can say what happened',
  )
  assert.match(
    mainActivity,
    /if \(!updater\.install\(file\)\)/,
    'the caller must branch on a refused install and surface it',
  )
})

// =============================================== 5. the version is in step

console.log('\n=== 1.1.5 is the released version ===')

check('the CHANGELOG has a 1.1.5 section with real notes', () => {
  const section = /^## \[1\.1\.5\][\s\S]*?(?=^## \[|\Z)/m.exec(changelog)
  assert.ok(section, 'CHANGELOG.md must have a "## [1.1.5]" section')
  // The notes are what users read in the update prompt, so an empty section
  // would ship a prompt with nothing in it.
  assert.ok(
    section[0].length > 400,
    'the 1.1.5 section is too short to be the release notes users will see',
  )
  assert.match(
    section[0],
    /accepts workspaceId or cwd, not both/,
    'the notes must name the error the release fixes, since that is what the ' +
      'affected users searched for',
  )
})

check('the CHANGELOG link block defines 1.1.5', () => {
  assert.match(
    changelog,
    /^\[1\.1\.5\]:\s+https:\/\/github\.com\/nmaych\/dsh-mobile\/compare\/v1\.1\.4\.\.\.v1\.1\.5$/m,
    'the link reference for 1.1.5 must exist, or the heading renders as literal text',
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
