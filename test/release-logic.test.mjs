// Tests for tools/release-metadata.mjs — the version arithmetic and changelog
// parsing that the release workflow depends on.
//
// These run offline, so a mistake here surfaces now rather than on a tagged
// build that publishes a broken update to every installed app.
//
// usage: node test/release-logic.test.mjs
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  buildManifest,
  changelogSection,
  versionCodeOf,
} from '../tools/release-metadata.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const changelogPath = path.join(here, '..', 'CHANGELOG.md')

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

// ------------------------------------------------------------ versionCode

console.log('=== versionCode derivation ===')

check('1.0.0 -> 10000', () => assert.equal(versionCodeOf('1.0.0'), 10000))
check('1.1.0 -> 10100', () => assert.equal(versionCodeOf('1.1.0'), 10100))
check('1.2.3 -> 10203', () => assert.equal(versionCodeOf('1.2.3'), 10203))
check('2.0.0 -> 20000', () => assert.equal(versionCodeOf('2.0.0'), 20000))
check('10.20.30 -> 102030', () => assert.equal(versionCodeOf('10.20.30'), 102030))

check('versionCode strictly increases across a release sequence', () => {
  const sequence = ['1.0.0', '1.0.1', '1.1.0', '1.2.3', '2.0.0', '2.1.0', '10.0.0']
  let previous = 0
  for (const version of sequence) {
    const code = versionCodeOf(version)
    assert.ok(code > previous, `${version} (${code}) must exceed ${previous}`)
    previous = code
  }
})

check('rejects a two-part version', () => assert.throws(() => versionCodeOf('1.1')))
check('rejects a leading v', () => assert.throws(() => versionCodeOf('v1.1.0')))
check('rejects four parts', () => assert.throws(() => versionCodeOf('1.1.0.0')))
check('rejects non-numeric', () => assert.throws(() => versionCodeOf('abc')))
check('rejects an empty string', () => assert.throws(() => versionCodeOf('')))

check('rejects minor >= 100 rather than silently colliding', () => {
  // 1.100.0 and 2.0.0 would both map to 20000 without this guard.
  assert.throws(() => versionCodeOf('1.100.0'))
  assert.throws(() => versionCodeOf('1.0.100'))
})

// -------------------------------------------------------------- changelog

console.log('\n=== changelog section extraction ===')
const markdown = fs.readFileSync(changelogPath, 'utf8')

check('finds the 1.1.0 section', () => {
  const notes = changelogSection(markdown, '1.1.0')
  assert.ok(notes.includes('扫码配对'), `expected 扫码配对 in notes, got: ${notes.slice(0, 120)}`)
})

check('a section does not bleed into the next version', () => {
  // 1.1.0 must not swallow 1.0.0's content.
  const notes = changelogSection(markdown, '1.1.0')
  assert.ok(
    !notes.includes('首个版本'),
    'the 1.1.0 section leaked content from 1.0.0',
  )
})

check('finds the 1.0.0 section', () => {
  const notes = changelogSection(markdown, '1.0.0')
  assert.ok(notes.includes('首个版本'), 'expected 首个版本 in the 1.0.0 section')
})

check('an unknown version yields an empty string', () => {
  assert.equal(changelogSection(markdown, '9.9.9'), '')
})

check('does not confuse 1.1.0 with 1.1.0-rc.1 style text', () => {
  const sample = '## [1.1.0]\n\nalpha\n\n## [1.1.0-rc.1]\n\nbeta\n'
  const notes = changelogSection(sample, '1.1.0')
  assert.ok(notes.includes('alpha'))
})

check('strips a trailing horizontal rule from the notes', () => {
  // CHANGELOG uses `---` between versions. That is document structure and
  // would look like noise in an update prompt.
  const sample = '## [1.2.0]\n\n### 新增\n\n- something\n\n---\n\n## [1.1.0]\n\nolder\n'
  const notes = changelogSection(sample, '1.2.0')
  assert.ok(!notes.includes('---'), `notes still contain a rule: ${JSON.stringify(notes)}`)
  assert.ok(notes.endsWith('- something'), `notes end unexpectedly: ${JSON.stringify(notes)}`)
})

check('real CHANGELOG notes carry no trailing rule', () => {
  const notes = changelogSection(markdown, '1.1.0')
  assert.ok(!/^-{3,}\s*$/m.test(notes), 'the 1.1.0 notes contain a horizontal rule')
  assert.ok(!notes.endsWith('---'), 'the 1.1.0 notes end with a horizontal rule')
})

check('every version heading in CHANGELOG.md is parseable', () => {
  const versionHeadings = markdown.match(/^## \[[^\]]+\]/gm) ?? []
  const parseable = markdown.match(/^## \[\d+\.\d+\.\d+\]/gm) ?? []
  assert.equal(
    versionHeadings.length,
    parseable.length,
    `found ${versionHeadings.length} headings but only ${parseable.length} match [x.y.z]: ` +
      versionHeadings.filter((h) => !/^## \[\d+\.\d+\.\d+\]/.test(h)).join(', '),
  )
  assert.ok(parseable.length > 0, 'CHANGELOG.md has no version sections')
})

// --------------------------------------------------------------- manifest

console.log('\n=== manifest assembly ===')

check('builds a complete update.json', () => {
  const manifest = buildManifest({
    version: '1.2.0',
    apkUrl: 'https://example.com/a.apk',
    sha256: 'ABCDEF',
    notes: 'hello',
  })
  assert.deepEqual(manifest, {
    versionCode: 10200,
    versionName: '1.2.0',
    apkUrl: 'https://example.com/a.apk',
    sha256: 'abcdef', // lowercased so a case mismatch cannot fail verification
    notes: 'hello',
    mandatory: false,
  })
})

check('manifest versionCode matches the version string', () => {
  const manifest = buildManifest({
    version: '1.1.0',
    apkUrl: 'x',
    sha256: 'y',
    notes: 'z',
  })
  assert.equal(manifest.versionCode, 10100)
  assert.equal(manifest.versionName, '1.1.0')
})

// ------------------------------------------------------------------ report

console.log()
const failed = results.filter((r) => !r.ok)
console.log(`${results.length - failed.length}/${results.length} passed`)
if (failed.length > 0) {
  console.log('failures:')
  for (const f of failed) console.log(`  - ${f.name}: ${f.detail}`)
}
process.exit(failed.length > 0 ? 1 : 0)
