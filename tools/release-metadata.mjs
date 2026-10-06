#!/usr/bin/env node
/**
 * Derive release metadata from a version string and the CHANGELOG.
 *
 * This exists so the version arithmetic and the changelog parsing live in ONE
 * place. `.github/workflows/release.yml` calls this script rather than
 * reimplementing the rules in shell, which means:
 *
 *   - the logic is testable offline (see test/release-logic.test.mjs)
 *   - the workflow cannot silently drift from what the tests cover
 *
 * Getting this wrong is not cosmetic. The APK's versionCode, the APK filename
 * and update.json's versionCode must agree, or the in-app updater either never
 * offers a new build or offers the same one forever.
 *
 * Usage:
 *   node tools/release-metadata.mjs version <x.y.z>
 *       -> prints the integer versionCode
 *
 *   node tools/release-metadata.mjs notes <x.y.z> [changelogPath]
 *       -> prints the release notes (the CHANGELOG section for that version),
 *          or nothing when there is no section
 *
 *   node tools/release-metadata.mjs manifest <x.y.z> <apkUrl> <sha256> [changelogPath]
 *       -> prints the complete update.json
 *
 * Exit codes: 0 ok, 1 bad version format, 2 usage error.
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const VERSION_PATTERN = /^(\d+)\.(\d+)\.(\d+)$/

/**
 * Turn a semantic version into an Android versionCode.
 *
 * Scheme: major * 10000 + minor * 100 + patch, so 1.2.3 -> 10203.
 *
 * Chosen because it is readable, and because it is strictly increasing for any
 * normal release sequence. It does mean minor and patch must stay under 100 —
 * see `assertRange` below, which fails loudly rather than silently colliding.
 *
 * @param {string} version - `x.y.z`.
 * @returns {number} the versionCode.
 */
export function versionCodeOf(version) {
  const match = VERSION_PATTERN.exec(version)
  if (match === null) {
    throw new Error(`version "${version}" is not MAJOR.MINOR.PATCH`)
  }
  const [, major, minor, patch] = match.map(Number)
  if (minor > 99 || patch > 99) {
    // Without this, 1.100.0 and 2.0.0 would both be 20000.
    throw new Error(
      `version "${version}" has minor or patch above 99, which would collide in the versionCode scheme`,
    )
  }
  if (major > 2000) {
    throw new Error(`version "${version}" major is implausibly large`)
  }
  return major * 10000 + minor * 100 + patch
}

/**
 * Extract one version's section from a Keep-a-Changelog file.
 *
 * Reads from a `## [x.y.z]` heading up to the next `## [` heading, so a section
 * can never bleed into its neighbour.
 *
 * @param {string} markdown - the CHANGELOG contents.
 * @param {string} version - the version to find.
 * @returns {string} the trimmed section body, or '' when absent.
 */
export function changelogSection(markdown, version) {
  const lines = markdown.split(/\r?\n/)
  const start = lines.findIndex((line) =>
    new RegExp(`^##\\s+\\[${version.replace(/\./g, '\\.')}\\]`).test(line),
  )
  if (start === -1) return ''

  const body = []
  for (let i = start + 1; i < lines.length; i += 1) {
    if (/^##\s+\[/.test(lines[i])) break
    body.push(lines[i])
  }

  // Drop leading/trailing blank lines and collapse runs of blanks, so the
  // result reads cleanly in the update dialog.
  let notes = body
    .join('\n')
    .replace(/^\s*\n/, '')
    .replace(/\n\s*$/, '')
    .replace(/\n{3,}/g, '\n\n')

  // Drop a trailing horizontal rule. The changelog uses `---` between
  // versions; that is document structure, not something to show a user in an
  // update prompt.
  notes = notes.replace(/\n*-{3,}\s*$/, '').replace(/\n\s*$/, '')

  return notes
}

/** Build the complete update.json object. */
export function buildManifest({ version, apkUrl, sha256, notes }) {
  return {
    versionCode: versionCodeOf(version),
    versionName: version,
    apkUrl,
    sha256: String(sha256).toLowerCase(),
    notes,
    mandatory: false,
  }
}

// --------------------------------------------------------------------- CLI

const isMain = process.argv[1] !== undefined &&
  path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))

if (isMain) {
  const [command, ...rest] = process.argv.slice(2)
  const defaultChangelog = path.join(
    path.dirname(fileURLToPath(import.meta.url)),
    '..',
    'CHANGELOG.md',
  )

  try {
    switch (command) {
      case 'version': {
        const [version] = rest
        if (version === undefined) throw new Error('usage: release-metadata.mjs version <x.y.z>')
        process.stdout.write(`${versionCodeOf(version)}\n`)
        break
      }
      case 'notes': {
        const [version, changelogPath = defaultChangelog] = rest
        if (version === undefined) throw new Error('usage: release-metadata.mjs notes <x.y.z> [path]')
        const markdown = fs.readFileSync(changelogPath, 'utf8')
        const notes = changelogSection(markdown, version)
        // An empty result is not an error: the workflow falls back to a link.
        if (notes !== '') process.stdout.write(`${notes}\n`)
        break
      }
      case 'manifest': {
        const [version, apkUrl, sha256, changelogPath = defaultChangelog] = rest
        if (!version || !apkUrl || !sha256) {
          throw new Error('usage: release-metadata.mjs manifest <x.y.z> <apkUrl> <sha256> [path]')
        }
        const markdown = fs.readFileSync(changelogPath, 'utf8')
        const notes = changelogSection(markdown, version)
        const manifest = buildManifest({
          version,
          apkUrl,
          sha256,
          notes: notes === '' ? `Release notes: ${apkUrl}` : notes,
        })
        process.stdout.write(`${JSON.stringify(manifest, null, 2)}\n`)
        break
      }
      default:
        process.stderr.write(
          'usage: release-metadata.mjs <version|notes|manifest> ...\n' +
            'See the header of this file for details.\n',
        )
        process.exit(2)
    }
  } catch (error) {
    process.stderr.write(`${error.message}\n`)
    process.exit(1)
  }
}
