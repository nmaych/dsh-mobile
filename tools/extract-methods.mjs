// Extract every Remote method descriptor from the DSH api-remotes client bundle.
// Each descriptor is an object literal with id/service/namespace/method/mode.
import fs from 'node:fs'

const src = fs.readFileSync(process.argv[2], 'utf8')

// Descriptors look like: { id: "...", service: "...", namespace: "...", method: "...", [mode: "stream",] invocation: {...} }
const re = /\{\s*id:\s*"([^"]+)",\s*service:\s*"([^"]+)",\s*namespace:\s*"([^"]+)",\s*method:\s*"([^"]+)"(?:,\s*mode:\s*"([^"]+)")?/g

const rows = []
let m
while ((m = re.exec(src)) !== null) {
  rows.push({ id: m[1], service: m[2], namespace: m[3], method: m[4], mode: m[5] || 'unary' })
}

// de-dup by id
const seen = new Map()
for (const r of rows) if (!seen.has(r.id)) seen.set(r.id, r)
const all = [...seen.values()].sort((a, b) => (a.namespace + '/' + a.method).localeCompare(b.namespace + '/' + b.method))

const byNs = {}
for (const r of all) (byNs[r.namespace] ??= []).push(r)

console.log(`total descriptors: ${all.length}`)
console.log(`namespaces (${Object.keys(byNs).length}): ${Object.keys(byNs).sort().join(', ')}\n`)
for (const ns of Object.keys(byNs).sort()) {
  console.log(`## ${ns}`)
  for (const r of byNs[ns]) console.log(`   ${r.mode === 'stream' ? '[stream] ' : '         '}${r.namespace}/${r.method}`)
}

fs.writeFileSync(process.argv[3], JSON.stringify(all, null, 2))
console.log(`\nwritten: ${process.argv[3]}`)
