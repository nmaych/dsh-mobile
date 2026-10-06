// Resumable downloader using Node's TLS stack.
// usage: node download.mjs <url> <destPath>
import https from 'node:https'
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'

const [url, dest] = process.argv.slice(2)
if (!url || !dest) { console.error('usage: node download.mjs <url> <dest>'); process.exit(2) }

fs.mkdirSync(path.dirname(dest), { recursive: true })

const MAX_REDIRECTS = 12

function request(u, headers = {}, redirects = 0) {
  return new Promise((resolve, reject) => {
    if (redirects > MAX_REDIRECTS) return reject(new Error('too many redirects'))
    const mod = u.startsWith('https') ? https : http
    const req = mod.request(u, { method: 'GET', headers, timeout: 60000 }, (res) => {
      if ([301, 302, 303, 307, 308].includes(res.statusCode) && res.headers.location) {
        res.resume()
        const next = new URL(res.headers.location, u).toString()
        return resolve(request(next, headers, redirects + 1))
      }
      resolve(res)
    })
    req.on('timeout', () => req.destroy(new Error('timeout')))
    req.on('error', reject)
    req.end()
  })
}

function fmt(n) {
  if (n > 1024 ** 3) return (n / 1024 ** 3).toFixed(2) + ' GB'
  if (n > 1024 ** 2) return (n / 1024 ** 2).toFixed(1) + ' MB'
  if (n > 1024) return (n / 1024).toFixed(0) + ' KB'
  return n + ' B'
}

const existing = fs.existsSync(dest) ? fs.statSync(dest).size : 0
const headers = existing > 0 ? { Range: `bytes=${existing}-` } : {}

let res = await request(url, headers)

// If the server ignored our Range, restart from scratch.
if (existing > 0 && res.statusCode === 200) {
  res.resume()
  fs.unlinkSync(dest)
  res = await request(url, {})
  console.log(`[restart] ${path.basename(dest)}`)
}

if (res.statusCode !== 200 && res.statusCode !== 206) {
  console.error(`HTTP ${res.statusCode} for ${url}`)
  process.exit(1)
}

const total = res.headers['content-length']
  ? Number(res.headers['content-length']) + (res.statusCode === 206 ? existing : 0)
  : 0

const out = fs.createWriteStream(dest, { flags: res.statusCode === 206 ? 'a' : 'w' })
let got = res.statusCode === 206 ? existing : 0
let lastLog = 0
const t0 = Date.now()

res.on('data', (c) => {
  got += c.length
  const now = Date.now()
  if (now - lastLog > 2000) {
    lastLog = now
    const pct = total ? ((got / total) * 100).toFixed(1) + '%' : '?'
    const mbps = (got / 1024 / 1024) / ((now - t0) / 1000)
    process.stdout.write(`\r${path.basename(dest)}: ${fmt(got)}/${total ? fmt(total) : '?'} (${pct}) ${mbps.toFixed(1)} MB/s   `)
  }
})

res.pipe(out)
out.on('finish', () => {
  process.stdout.write(`\r${path.basename(dest)}: ${fmt(got)} DONE (${((Date.now() - t0) / 1000).toFixed(0)}s)                    \n`)
  process.exit(0)
})
out.on('error', (e) => { console.error('write error', e); process.exit(1) })
res.on('error', (e) => { console.error('read error', e); process.exit(1) })
