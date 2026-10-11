// Guards the release *artifact contract*: the path `release.yml` reads must be
// the path Gradle actually writes.
//
// This is the bug that broke every tagged release. `release.yml` hardcoded
// `app/build/outputs/apk/release/app-release.apk`, but Android Gradle Plugin only
// uses that name when a signing config is attached. With no signing config it
// writes **`app-release-unsigned.apk`** instead — and the only symptom was the
// workflow reporting `No release APK produced`, which named neither the file that
// was built nor the reason it was unsigned.
//
// Nothing in the toolchain catches this. The Kotlin compiler never sees either
// filename, YAML is not type-checked, and the local build *does* produce
// `app-release.apk` (because a developer has `keystore.properties`, which is
// gitignored) so the failure exists only on CI. That is the same shape as the
// `gradlew` executable bit that `repo-hygiene.test.mjs` guards: broken only where
// nobody develops.
//
// The check is deliberately a *static* one over the two files. The alternative —
// building the APK unsigned and asserting the filename — needs an Android SDK and
// a Gradle run, which would put this out of reach of the seconds-long logic job.
// The static rule pins the exact coupling that broke.
//
// usage: node test/release-artifact.test.mjs
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

const releaseYml = read('.github/workflows/release.yml')
const ciYml = read('.github/workflows/ci.yml')
const gradle = read('app-project/app/build.gradle.kts')
const buildCmd = read('build.cmd')
const releasing = read('docs/RELEASING.md')

// The one name AGP uses for a *signed* release APK. Kept as a literal so the
// assertion below is about this exact string, not about a pattern that could
// drift with it.
const SIGNED_APK = 'app-release.apk'
// The name AGP substitutes when no signing config is attached.
const UNSIGNED_APK = 'app-release-unsigned.apk'

// ==================== the workflow reads a path Gradle writes

console.log('=== the release workflow reads the artifact Gradle writes ===')

check('release.yml looks for the signed release APK', () => {
  // The positive half: the workflow must name the signed artifact, because that
  // is the only one it may publish. An unsigned APK cannot be installed over an
  // existing one at all (Android refuses a signature change), so publishing it
  // would be worse than failing.
  assert.match(
    releaseYml,
    /app-release\.apk/,
    'release.yml must reference app-release.apk',
  )
})

check('release.yml does not accept the unsigned artifact', () => {
  // The negative half, and the one that actually encodes the bug. If a future
  // edit "fixes" a missing-file failure by also accepting the unsigned name, the
  // release would silently ship an unsigned APK — which is a worse outcome than
  // the red build it was trying to clear. An unsigned APK cannot be installed
  // over an existing one at all, so it is not a fallback.
  const verifyStep = releaseYml.slice(releaseYml.indexOf('name: Verify APK'))
  const verifyBody = verifyStep.slice(0, verifyStep.indexOf('name: Generate update.json'))
  // The copy that produces the published asset must source the signed path, and
  // the unsigned name may only appear inside the diagnostic.
  assert.match(verifyBody, /cp "\$APK"/, 'the release asset must be copied from $APK')
  for (const line of verifyBody.split('\n')) {
    if (!line.includes(UNSIGNED_APK)) continue
    assert.match(
      line,
      /^\s*(#|if \[|echo|ls)/,
      `the unsigned name may only appear in a diagnostic, not in a copy: ${line.trim()}`,
    )
  }
})

check('the failure message names the unsigned file', () => {
  // The diagnostic is the point. The original message was "No release APK
  // produced", which sent the reader looking for a build failure that had not
  // happened. Naming the file that *was* produced turns a mystery into a
  // one-line diagnosis.
  const verifyStep = releaseYml.slice(releaseYml.indexOf('name: Verify APK'))
  assert.match(
    verifyStep.slice(0, 2000),
    /app-release-unsigned\.apk/,
    'a missing signed APK must be explained by pointing at the unsigned one',
  )
})

check('the Gradle script treats partial signing as a hard error', () => {
  // The root cause fix. Four signing inputs, and the legitimate states are all
  // four or none — a partial set is always a mistake, and the old conjunction
  // silently degraded it to "unsigned", which is what renamed the artifact.
  assert.match(
    gradle,
    /providedSigningInputs in 1 until signingInputs\.size/,
    'a partial signing configuration must be detected, not silently ignored',
  )
  assert.match(
    gradle,
    /\berror\(/,
    'it must fail the build rather than producing an unsigned APK',
  )
  // Every input must be named in the message, or the reader has to guess which
  // secret is missing — the thing the empty-string expansion hides.
  for (const key of [
    'DSH_KEYSTORE_FILE',
    'DSH_KEYSTORE_PASSWORD',
    'DSH_KEY_ALIAS',
    'DSH_KEY_PASSWORD',
  ]) {
    assert.match(gradle, new RegExp(key), `the signing inputs must include ${key}`)
  }
})

check('the workflow checks the three credential secrets before building', () => {
  // Checking them here rather than letting Gradle discover them means the error
  // is reported by the step that owns the secrets, with the secret's own name.
  const step = releaseYml.slice(releaseYml.indexOf('name: Check signing secrets'))
  assert.ok(step.length > 0, 'release.yml must have a signing-secrets check step')
  const body = step.slice(0, step.indexOf('- name: Build signed release'))
  for (const key of ['DSH_KEYSTORE_PASSWORD', 'DSH_KEY_ALIAS', 'DSH_KEY_PASSWORD']) {
    assert.match(body, new RegExp(key), `the pre-flight check must name ${key}`)
  }
  assert.match(body, /exit 1/, 'a missing secret must stop the run')
})

// ==================== the local build path agrees

console.log('\n=== the local build reports the artifact it produced ===')

check('build.cmd only advertises an APK that exists', () => {
  // build.cmd prints the release APK path on success. It guards each path with
  // `if exist`, which is why it correctly stayed silent during the unsigned
  // build instead of printing a path that was not there — the property that made
  // the CI failure so confusing by contrast.
  assert.match(
    buildCmd,
    /if exist "%PROJ%\\app\\build\\outputs\\apk\\release\\app-release\.apk"/,
    'build.cmd must guard the release path with an existence test',
  )
})

check('the unsigned name is documented as the thing to look for', () => {
  // The troubleshooting entry, so the next person to hit this reads the cause
  // rather than rediscovering it.
  assert.match(
    releasing,
    /app-release-unsigned\.apk/,
    'RELEASING.md must document the unsigned filename as the signature of a ' +
      'missing signing secret',
  )
})

// ==================== the workflow stays coherent

console.log('\n=== the release workflow is well formed ===')

check('both workflows resolve the wrapper from app-project', () => {
  // The wrapper lives in app-project/, not the repository root. A `./gradlew`
  // run from anywhere else fails with a bare "No such file or directory".
  for (const [name, yml] of [['release.yml', releaseYml], ['ci.yml', ciYml]]) {
    const calls = [...yml.matchAll(/\.\/gradlew/g)]
    assert.ok(calls.length > 0, `${name} must invoke ./gradlew`)
    assert.match(
      yml,
      /working-directory: app-project/,
      `${name} must declare working-directory: app-project for its gradlew call`,
    )
  }
})

check('release.yml verifies the signature before publishing', () => {
  // An unsigned or wrong-key APK is worse than no release: users install it and
  // then cannot update again, because Android refuses a signature change.
  const verifyAt = releaseYml.indexOf('name: Verify APK')
  const publishAt = releaseYml.indexOf('name: Publish release')
  assert.ok(verifyAt >= 0 && publishAt > verifyAt, 'verification must precede publishing')
  const verify = releaseYml.slice(verifyAt, publishAt)
  // The tool path is quoted, so the executable name is followed by a closing
  // quote before the subcommand — matching on `apksigner verify` would miss it.
  assert.match(verify, /apksigner"\s+verify/, 'the signature must be verified')
  assert.match(verify, /zipalign"\s+-c/, 'the APK must be checked for alignment')
  assert.match(verify, /versionCode/, 'the version code must be checked against the tag')
  assert.match(verify, /versionName/, 'the version name must be checked against the tag')
})

check('the signing check runs after the keystore is decoded', () => {
  // Order matters: `DSH_KEYSTORE_FILE` is exported by the decode step into
  // GITHUB_ENV, and the Gradle script reads it as one of the four inputs. Running
  // the pre-flight first would still work (it only checks the three secrets), but
  // a future edit that folded the file check into it would silently pass with no
  // keystore at all.
  const decodeAt = releaseYml.indexOf('name: Decode keystore')
  const checkAt = releaseYml.indexOf('name: Check signing secrets')
  const buildAt = releaseYml.indexOf('name: Build signed release')
  assert.ok(decodeAt >= 0 && checkAt > decodeAt, 'the secrets check must follow the decode')
  assert.ok(buildAt > checkAt, 'the build must follow the secrets check')
})

check('the secrets check is not skipped when a secret is empty', () => {
  // `set -euo pipefail` plus `${VAR:-}` is what makes an *unset* secret fail
  // rather than expand to a bare empty token that `[ -n ]` then misreads.
  const step = releaseYml.slice(releaseYml.indexOf('name: Check signing secrets'))
  const body = step.slice(0, step.indexOf('- name: Build signed release'))
  assert.match(body, /set -euo pipefail/, 'the step must fail on the first error')
  assert.match(body, /:-}/, 'unset variables must be expanded defensively')
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
