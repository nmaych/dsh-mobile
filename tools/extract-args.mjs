// Extract Remote method descriptors: namespace, method, and parameter wire names.
// The gateway rejects extra AND missing argument keys, so these must be exact.
import fs from 'node:fs'

const src = fs.readFileSync(process.argv[2], 'utf8')

const out = []
// Descriptor blocks start with an id, then service/namespace/method.
const idRe = /id:\s*'([^']+)',\s*service:\s*'([^']+)',\s*namespace:\s*'([^']+)',\s*method:\s*'([^']+)'/g

let m
while ((m = idRe.exec(src)) !== null) {
  const [full, id, service, namespace, method] = m
  // Scan forward to the parameters array that belongs to this descriptor.
  const rest = src.slice(m.index, m.index + 4000)
  const pIdx = rest.indexOf('parameters:')
  let wires = []
  if (pIdx >= 0) {
    // Match the bracket group after `parameters:`.
    const start = rest.indexOf('[', pIdx)
    let depth = 0
    let end = start
    for (let i = start; i < rest.length; i++) {
      if (rest[i] === '[') depth++
      else if (rest[i] === ']') { depth--; if (depth === 0) { end = i; break } }
    }
    const block = rest.slice(start, end + 1)
    wires = [...block.matchAll(/wire:\s*'([^']+)'/g)].map((x) => x[1])
  }
  const mode = /mode:\s*'stream'/.test(rest) ? 'stream' : 'unary'
  out.push({ id, service, namespace, method, mode, wires })
}

const seen = new Map()
for (const r of out) if (!seen.has(r.id)) seen.set(r.id, r)
const all = [...seen.values()].sort((a, b) =>
  (a.namespace + '/' + a.method).localeCompare(b.namespace + '/' + b.method))

for (const r of all) {
  console.log(
    `${r.mode === 'stream' ? '[stream]' : '        '} ${r.namespace}/${r.method}` +
    `\targs={${r.wires.map((w) => w + ':…').join(', ')}}`,
  )
}
console.log(`\ntotal: ${all.length}`)
fs.writeFileSync(process.argv[3], JSON.stringify(all, null, 2))
