/**
 * Write dist/update.json for the local build drop.
 *
 * `docs/RELEASING.md` shows a PowerShell one-liner for this, but piping the
 * script's stdout through PowerShell splits it into an array of lines and
 * rejoins them with spaces, so the "pretty" JSON comes out as one long line.
 * Doing the write in Node keeps the formatting, avoids a BOM, and uses the
 * exact same `buildManifest` the release workflow calls.
 *
 * usage: node tools/write-dist-manifest.mjs <version> <apkPath> <apkUrl>
 */
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { buildManifest, changelogSection } from './release-metadata.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const repo = path.join(here, '..')

const [version, apkPath, apkUrl] = process.argv.slice(2)
if (!version || !apkPath || !apkUrl) {
  process.stderr.write(
    'usage: node tools/write-dist-manifest.mjs <version> <apkPath> <apkUrl>\n',
  )
  process.exit(2)
}

const apk = path.resolve(apkPath)
const sha256 = crypto.createHash('sha256').update(fs.readFileSync(apk)).digest('hex')

const changelog = fs.readFileSync(path.join(repo, 'CHANGELOG.md'), 'utf8')
const notes = changelogSection(changelog, version)
if (notes === '') {
  process.stderr.write(
    `warning: CHANGELOG.md has no "## [${version}]" section; ` +
      'the update prompt will show a bare link instead of release notes\n',
  )
}

const manifest = buildManifest({
  version,
  apkUrl,
  sha256,
  notes: notes === '' ? `Release notes: ${apkUrl}` : notes,
})

const out = path.join(repo, 'dist', 'update.json')
fs.writeFileSync(out, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')

console.log(`wrote ${path.relative(repo, out)}`)
console.log(`  versionCode ${manifest.versionCode}  versionName ${manifest.versionName}`)
console.log(`  sha256      ${manifest.sha256}`)
console.log(`  apk         ${path.basename(apk)} (${fs.statSync(apk).size} bytes)`)
