// Probe the model catalog and drive one real turn, mirroring the Android client.
// usage: node modeltest.mjs "http://127.0.0.1:19555/?token=..."
import http from 'node:http'
import crypto from 'node:crypto'

const pairingUrl = process.argv[2]
const u = new URL(pairingUrl)
const token = u.searchParams.get('token')
let COOKIE = ''

function handshake() {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: u.hostname, port: u.port, path: `/?token=${encodeURIComponent(token)}`, method: 'GET' },
      (res) => {
        const c = (res.headers['set-cookie'] || []).map((x) => x.split(';')[0]).join('; ')
        res.resume()
        if (!c) return reject(new Error('no cookie'))
        COOKIE = c
        resolve(c)
      },
    )
    req.on('error', reject)
    req.end()
  })
}

function rpc(namespace, method, args) {
  return new Promise((resolve, reject) => {
    const rpcId = crypto.randomUUID()
    const body = JSON.stringify({ type: 'client-request', rpcId, method: `${namespace}/${method}`, payload: { args } })
    const req = http.request(
      {
        host: u.hostname, port: u.port, path: `/api/${namespace}/${method}`, method: 'POST',
        headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body), cookie: COOKIE },
      },
      (res) => {
        let t = ''
        res.on('data', (c) => (t += c))
        res.on('end', () => {
          if (res.statusCode !== 200) return reject(new Error(`HTTP ${res.statusCode}: ${t.slice(0, 200)}`))
          const o = JSON.parse(t)
          if (o.result?.ok) return resolve(o.result.value)
          reject(new Error(`${o.result?.error?.code}: ${o.result?.error?.message}`))
        })
      },
    )
    req.on('error', reject)
    req.write(body)
    req.end()
  })
}

function follow(sessionId, onItem) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://${u.host}/api/remote.mux`, { headers: { cookie: COOKIE } })
    const streamId = crypto.randomUUID()
    let ok = false
    ws.onopen = () => ws.send(JSON.stringify({
      type: 'open', streamId, endpoint: 'session/follow',
      payload: { args: { request: { address: { kind: 'session', sessionId }, assistantStream: true, maxMessages: 50 } } },
    }))
    ws.onerror = () => { if (!ok) reject(new Error('ws error')) }
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data)
      if (m.streamId !== streamId) return
      if (m.type === 'item') { if (!ok) { ok = true; resolve({ ws, streamId }) } onItem(m.value) }
      else if (m.type === 'end') ws.close()
      else if (m.type === 'error') { if (!ok) reject(new Error(m.error.message)) }
    }
    setTimeout(() => { if (!ok) reject(new Error('timeout')) }, 30000)
  })
}

await handshake()
console.log('paired\n')

console.log('=== session/modelCatalog ===')
const cat = await rpc('session', 'modelCatalog', {})
console.log('default:', JSON.stringify(cat.default))
console.log('routableProviders:', JSON.stringify(cat.routableProviders))
for (const g of cat.groups || []) {
  console.log(`  group ${g.id} (${g.name}): ${(g.models || []).map((m) => m.id).join(', ')}`)
}
for (const f of cat.failures || []) console.log(`  FAILURE ${f.id}: ${f.message}`)

// Prefer the account-backed route, which uses the stored DeepSeek account token
// rather than an environment API key.
const groups = cat.groups || []
const group = groups.find((g) => g.id === 'deepseek-account') || groups[0]
if (!group) { console.log('\nno usable provider — cannot run a live turn'); process.exit(0) }
const model = (group.models || [])[0]
console.log(`\nselected: ${group.id} / ${model.id}`)

const created = await rpc('session', 'create', { request: {} })
const sessionId = created.sessionId
console.log('session:', sessionId)

await rpc('session', 'selectModel', {
  request: { sessionId, provider: group.id, model: model.id, ...(model.reasoning?.defaultEffort ? { reasoningEffort: model.reasoning.defaultEffort } : {}) },
})
console.log('model selected\n')

let text = ''
let durable = false
let liveFrames = 0
let endReason = null
let turnEnded = false

const stream = await follow(sessionId, (item) => {
  if (item.type === 'event') {
    const e = item.event
    if (e.type === 'assistant/message') {
      durable = true
      for (const b of e.data?.message?.content || []) if (b.type === 'text') text += b.text
    }
    if (e.type === 'turn/end') { turnEnded = true; endReason = e.data?.reason }
  } else if (item.type === 'assistant-stream') {
    liveFrames++
    const f = item.frame
    if (f?.type === 'chunk' && f.chunk?.type === 'text-delta') text += f.chunk.text
  }
})

await new Promise((r) => setTimeout(r, 1200))
await rpc('session', 'prompt', {
  request: {
    requestId: crypto.randomUUID(), sessionId, mode: 'queue',
    content: [{ type: 'text', text: 'Reply with exactly: PROTOCOL OK' }],
    clientTimeZone: 'Asia/Shanghai',
  },
})

const deadline = Date.now() + 120000
while (Date.now() < deadline && !turnEnded) await new Promise((r) => setTimeout(r, 400))
try { stream.ws.close() } catch {}

console.log('=== RESULT ===')
console.log('durable assistant/message:', durable)
console.log('live assistant-stream frames:', liveFrames)
console.log('turn/end reason:', JSON.stringify(endReason))
console.log('recovered text:', JSON.stringify(text.slice(0, 200)))
console.log(durable && text.includes('PROTOCOL OK') ? '\n*** HAPPY PATH VERIFIED ***' : '\n*** happy path NOT verified ***')
