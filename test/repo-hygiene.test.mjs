// Guards repository invariants that the build and CI depend on, but that no
// compiler and no Kotlin test can see.
//
// Why this exists: `ci.yml` runs `./gradlew assembleDebug`. The Gradle wrapper
// was committed with mode `100644` (not executable) since the repository's very
// first commit, so on Linux — where CI runs — that line died with:
//
//     ./gradlew: Permission denied
//     Error: Process completed with exit code 126
//
// Nothing local catches it: `core.filemode=false` on Windows means the mode is
// invisible in a working tree, and `build.cmd` never touches `./gradlew` at all
// (it prefers the bundled Gradle and otherwise calls `gradlew.bat`). So the
// breakage existed only on the one platform nobody develops on.
//
// The executable bit lives in the *git index*, which is why this test reads git
// rather than the filesystem. It also checks the two other ways the same line
// can break on Linux: a CRLF or missing shebang ("bad interpreter"), and a
// workflow invoking the wrapper from the wrong directory.
//
// usage: node test/repo-hygiene.test.mjs
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
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

/**
 * Run git and return its stdout, or null when git is unavailable.
 *
 * Output is redirected to a *file* rather than captured through a pipe. Piped
 * stdio is refused under the DSH file sandbox (`EPERM` on the shell it spawns),
 * and a temp file keeps this test runnable in the same environment as the rest
 * of the suite.
 */
function git(args) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-git-'))
  const out = path.join(dir, 'out.txt')
  let result
  let fd
  try {
    fd = fs.openSync(out, 'w')
    result = spawnSync('git', args, {
      cwd: repo,
      stdio: ['ignore', fd, 'ignore'],
    })
    // Close our handle *before* reading, so the bytes the child wrote are
    // definitely flushed to the file rather than possibly still buffered.
    fs.closeSync(fd)
    fd = undefined
    if (result.error || result.status !== 0) return null
    return fs.readFileSync(out, 'utf8')
  } finally {
    // Always, including on the `git is missing` path: a test that litters the
    // temp directory on every run is its own small bug.
    if (fd !== undefined) fs.closeSync(fd)
    fs.rmSync(dir, { recursive: true, force: true })
  }
}

const GRADLEW = 'app-project/gradlew'
const gradlewPath = path.join(repo, GRADLEW)

// ------------------------------------------------------------- the exec bit

console.log('=== the Gradle wrapper is runnable on Linux ===')

check('gradlew carries the executable bit in the git index', () => {
  const out = git(['ls-files', '-s', GRADLEW])
  if (out === null) {
    console.log('      (skipped: git is unavailable here; CI always has it)')
    return
  }
  const entry = out.trim()
  assert.ok(entry !== '', `${GRADLEW} is not tracked by git`)
  const mode = entry.split(/\s+/)[0]
  assert.equal(
    mode,
    '100755',
    `${GRADLEW} is committed as mode ${mode}, so \`./gradlew\` fails on Linux ` +
      'with "Permission denied" (exit 126). Fix it with:\n' +
      `        git update-index --chmod=+x ${GRADLEW}\n` +
      '      On Windows the bit is invisible (core.filemode=false), which is how ' +
      'it stayed broken; the mode lives in the index, not the working tree.',
  )
})

check('gradlew is executable in a non-Windows working tree', () => {
  // The end-to-end form of the check above: on Linux/macOS this is the actual
  // permission CI will see after checkout.
  if (process.platform === 'win32') {
    console.log('      (skipped: Windows has no executable bit on disk)')
    return
  }
  const mode = fs.statSync(gradlewPath).mode
  assert.ok(
    (mode & 0o111) !== 0,
    `${GRADLEW} is not executable on disk (mode ${(mode & 0o777).toString(8)}); ` +
      'a checkout must set the bit from the git index',
  )
})

// ------------------------------------------------------- it must be a script

console.log('\n=== the wrapper is a valid shell script ===')

check('gradlew starts with a shebang and uses LF endings', () => {
  // A CRLF line ending turns the shebang into `#!/usr/bin/env sh\r`, which Linux
  // reports as "bad interpreter: No such file or directory" — the other way this
  // same command fails on CI.
  const bytes = fs.readFileSync(gradlewPath)
  const firstLine = bytes.subarray(0, bytes.indexOf(0x0a)).toString('utf8')
  assert.ok(
    firstLine.startsWith('#!'),
    `the first line must be a shebang, got: ${JSON.stringify(firstLine)}`,
  )
  assert.ok(
    !firstLine.endsWith('\r'),
    'the shebang line ends with CR: the file has CRLF endings, which breaks ' +
      '`./gradlew` on Linux even when the executable bit is set',
  )
  assert.ok(
    bytes.indexOf(0x0d) === -1,
    'gradlew contains CR bytes; .gitattributes pins it to LF (eol=lf) and a ' +
      'CRLF checkout makes the interpreter path unresolvable',
  )
})

// ---------------------------------------------------- invoked from the right place

console.log('\n=== workflows invoke the wrapper correctly ===')

/**
 * Strip whole-line YAML/shell comments.
 *
 * Without this, prose *about* `./gradlew` (including this repository's own
 * explanatory comments, which quote the failing command) is mistaken for an
 * invocation of it.
 */
function stripComments(text) {
  return text
    .split('\n')
    .filter((line) => !/^\s*#/.test(line))
    .join('\n')
}

check('every ./gradlew invocation runs from app-project', () => {
  // The wrapper lives in app-project/, not the repository root, so a step that
  // calls `./gradlew` from the root fails with "No such file or directory".
  const dir = path.join(repo, '.github', 'workflows')
  const offenders = []
  for (const name of fs.readdirSync(dir)) {
    if (!name.endsWith('.yml') && !name.endsWith('.yaml')) continue
    const text = stripComments(fs.readFileSync(path.join(dir, name), 'utf8'))
    if (!/\.\/gradlew\b/.test(text)) continue
    // Every step that runs it must declare the working directory. Steps are
    // separated by a `- name:` line; scan the block each invocation sits in.
    const steps = text.split(/\n\s*-\s+name:/)
    for (const step of steps) {
      if (!/\.\/gradlew\b/.test(step)) continue
      if (!/working-directory:\s*app-project\b/.test(step)) {
        offenders.push(`${name}: ${step.split('\n')[0].trim().slice(0, 60)}`)
      }
    }
  }
  assert.deepEqual(
    offenders,
    [],
    `these workflow steps call ./gradlew without working-directory: app-project — ` +
      `the wrapper is not at the repository root: ${offenders.join('; ')}`,
  )
})

check('the wrapper is guarded against losing its executable bit again', () => {
  // Belt and braces. The index mode above is the real fix and the thing that
  // regresses; this asserts CI does not depend on it alone, because the bit is
  // easy to drop (a Windows contributor re-adding the file, a squash that loses
  // modes, an upload through the web UI).
  const dir = path.join(repo, '.github', 'workflows')
  for (const name of fs.readdirSync(dir)) {
    if (!name.endsWith('.yml') && !name.endsWith('.yaml')) continue
    const text = fs.readFileSync(path.join(dir, name), 'utf8')
    if (!/\.\/gradlew\b/.test(text)) continue
    assert.ok(
      /chmod\s+\+x\s+gradlew/.test(text),
      `${name} calls ./gradlew but never restores the executable bit; add ` +
        '`chmod +x gradlew` so a lost mode cannot break the build again',
    )
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
