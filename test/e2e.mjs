// End-to-end protocol test that mirrors exactly what the Android client does.
// usage: node e2e.mjs "http://127.0.0.1:19555/?token=..."
import http from 'node:http'
import crypto from 'node:crypto'

const pairingUrl = process.argv[2]
if (!pairingUrl) { console.error('usage: node e2e.mjs <pairing-url>'); process.exit(2) }

const u = new URL(pairingUrl)
const origin = `${u.protocol}//${u.host}`
const token = u.searchParams.get('token')

let COOKIE = ''
const log = (...a) => console.log(...a)

// ---------------------------------------------------------------- handshake

function handshake() {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: u.hostname, port: u.port, path: `/?token=${encodeURIComponent(token)}`, method: 'GET' },
      (res) => {
        const setCookie = res.headers['set-cookie'] || []
        res.resume()
        const cookie = setCookie.map((c) => c.split(';')[0]).join('; ')
        if (!cookie) return reject(new Error(`no Set-Cookie (status ${res.statusCode})`))
        COOKIE = cookie
        resolve(cookie)
      },
    )
    req.on('error', reject)
    req.end()
  })
}

// ---------------------------------------------------------------- unary RPC

function rpc(namespace, method, args) {
  return new Promise((resolve, reject) => {
    const rpcId = crypto.randomUUID()
    const body = JSON.stringify({
      type: 'client-request',
      rpcId,
      method: `${namespace}/${method}`,
      payload: { args },
    })
    const req = http.request(
      {
        host: u.hostname,
        port: u.port,
        path: `/api/${namespace}/${method}`,
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          'content-length': Buffer.byteLength(body),
          cookie: COOKIE,
        },
      },
      (res) => {
        let text = ''
        res.on('data', (c) => (text += c))
        res.on('end', () => {
          if (res.statusCode !== 200) {
            return reject(new Error(`HTTP ${res.statusCode}: ${text.slice(0, 300)}`))
          }
          let obj
          try { obj = JSON.parse(text) } catch { return reject(new Error('bad json: ' + text.slice(0, 200))) }
          if (obj.type !== 'server-response') return reject(new Error('bad envelope'))
          if (obj.rpcId !== rpcId) return reject(new Error(`rpcId mismatch`))
          const r = obj.result
          if (r?.ok === true) return resolve(r.value)
          reject(new Error(`remote error ${r?.error?.code}: ${r?.error?.message}`))
        })
      },
    )
    req.on('error', reject)
    req.write(body)
    req.end()
  })
}

// ---------------------------------------------------------------- streaming

function openStream(endpoint, args, onItem, opts = {}) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://${u.host}/api/remote.mux`, {
      headers: { cookie: COOKIE },
    })
    const streamId = crypto.randomUUID()
    let settled = false

    ws.onopen = () => {
      ws.send(JSON.stringify({ type: 'open', streamId, endpoint, payload: { args } }))
    }
    ws.onerror = (e) => { if (!settled) { settled = true; reject(new Error('ws error')) } }
    ws.onmessage = (ev) => {
      const msg = JSON.parse(ev.data)
      if (msg.streamId !== streamId) return
      if (msg.type === 'item') {
        if (!settled) { settled = true; resolve({ ws, streamId }) }
        onItem(msg.value)
        if (opts.stopAfter && opts.stopAfter()) {
          ws.send(JSON.stringify({ type: 'cancel', streamId }))
        }
      } else if (msg.type === 'end') {
        if (!settled) { settled = true; resolve({ ws, streamId }) }
        ws.close()
      } else if (msg.type === 'error') {
        if (!settled) { settled = true; reject(new Error(`stream error ${msg.error.code}: ${msg.error.message}`)) }
      }
    }
    setTimeout(() => { if (!settled) { settled = true; reject(new Error('timeout opening stream')) } }, 30000)
  })
}

// --------------------------------------------------------------------- main

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

log('=== 1. handshake ===')
try {
  const cookie = await handshake()
  check('token handshake sets a session cookie', true, cookie.split('=')[0] + '=…')
} catch (e) {
  check('token handshake sets a session cookie', false, e.message)
  process.exit(1)
}

log('\n=== 2. session/list (wire name `_request`) ===')
let sessions = []
try {
  const value = await rpc('session', 'list', { _request: {} })
  sessions = value.items || []
  check('session/list returns items', Array.isArray(sessions), `${sessions.length} sessions`)
  if (sessions[0]) {
    log('    sample:', JSON.stringify({
      sessionId: sessions[0].sessionId,
      updatedAt: sessions[0].updatedAt,
      running: sessions[0].running,
      blank: sessions[0].blank,
      cwd: sessions[0].cwd,
      title: sessions[0].projections?.values?.title,
    }))
    check('summary has no top-level `title` (title is projected)', !('title' in sessions[0]))
  }
} catch (e) {
  check('session/list returns items', false, e.message)
}

log('\n=== 3. session/create ===')
let sessionId
try {
  const value = await rpc('session', 'create', { request: {} })
  sessionId = value.sessionId
  check('session/create returns sessionId', typeof sessionId === 'string' && sessionId.length > 0, sessionId)
} catch (e) {
  check('session/create returns sessionId', false, e.message)
}

log('\n=== 4. session/follow stream ===')
if (sessionId) {
  const kinds = new Map()
  const eventTypes = new Set()
  try {
    await openStream(
      'session/follow',
      {
        request: {
          address: { kind: 'session', sessionId },
          assistantStream: true,
          maxMessages: 50,
          turnWindow: { minMessages: 50, minTurns: 2 },
        },
      },
      (item) => {
        kinds.set(item.type, (kinds.get(item.type) || 0) + 1)
        if (item.type === 'snapshot') {
          ;(item.records || []).forEach((r) => eventTypes.add(r.event?.type))
        } else if (item.type === 'event') {
          eventTypes.add(item.event?.type)
        }
      },
      { stopAfter: () => true },
    )
    check('session/follow opens and yields a snapshot', kinds.has('snapshot'),
      `frames: ${[...kinds].map(([k, v]) => k + '×' + v).join(', ')}`)
  } catch (e) {
    check('session/follow opens and yields a snapshot', false, e.message)
  }

  log('\n=== 5. session/prompt → live turn ===')
  const seen = { event: 0, assistantStream: 0, userMessage: false, turnStart: false, turnEnd: false, assistantMessage: false, text: '', types: [], endReason: null, streamFrames: [] }
  try {
    const stream = openStream(
      'session/follow',
      {
        request: {
          address: { kind: 'session', sessionId },
          assistantStream: true,
          maxMessages: 50,
        },
      },
      (item) => {
        if (item.type === 'event') {
          seen.event++
          const t = item.event?.type
          seen.types.push(t)
          if (t === 'user/message') seen.userMessage = true
          if (t === 'turn/start') seen.turnStart = true
          if (t === 'turn/end') {
            seen.turnEnd = true
            seen.endReason = item.event?.data?.reason
          }
          if (t === 'assistant/message') {
            seen.assistantMessage = true
            const blocks = item.event?.data?.message?.content || []
            for (const b of blocks) if (b.type === 'text') seen.text += b.text
          }
        } else if (item.type === 'assistant-stream') {
          seen.assistantStream++
          const f = item.frame
          seen.streamFrames.push(f?.type + (f?.chunk ? ':' + f.chunk.type : ''))
          if (f?.type === 'chunk' && f.chunk?.type === 'text-delta') seen.text += f.chunk.text
        }
      },
    )

    await new Promise((r) => setTimeout(r, 1500))
    await rpc('session', 'prompt', {
      request: {
        requestId: crypto.randomUUID(),
        sessionId,
        mode: 'queue',
        content: [{ type: 'text', text: 'Reply with exactly: PROTOCOL OK' }],
        clientTimeZone: 'Asia/Shanghai',
      },
    })

    const deadline = Date.now() + 90000
    while (Date.now() < deadline && !seen.turnEnd) await new Promise((r) => setTimeout(r, 500))
    const s = await stream
    try { s.ws.close() } catch {}

    check('session/prompt is accepted and the turn runs', seen.userMessage && seen.turnStart, JSON.stringify(seen))
    log('    event types:', JSON.stringify(seen.types))
    log('    stream frames:', JSON.stringify(seen.streamFrames))
    log('    turn/end reason:', JSON.stringify(seen.endReason))
    check('durable assistant/message arrives', seen.assistantMessage)
    check('turn/end closes the turn', seen.turnEnd)
    check('live assistant-stream frames arrive', seen.assistantStream > 0, `${seen.assistantStream} frames`)
    check('assistant text recovered', seen.text.includes('PROTOCOL OK'), JSON.stringify(seen.text.slice(0, 120)))
  } catch (e) {
    check('session/prompt is accepted and the turn runs', false, e.message)
  }

  log('\n=== 6. session/cancel ===')
  try {
    const v = await rpc('session', 'cancel', { request: { sessionId } })
    check('session/cancel returns accepted', v?.accepted === true, JSON.stringify(v))
  } catch (e) {
    // A finished turn has no live agent, which is the documented behaviour.
    check('session/cancel responds', /not-found|no live/i.test(e.message), e.message)
  }
}

log('\n=== SUMMARY ===')
const failed = results.filter((r) => !r.ok)
log(`${results.length - failed.length}/${results.length} passed`)
if (failed.length) {
  log('failures:')
  for (const f of failed) log(`  - ${f.name}: ${f.detail}`)
}
// Open WebSockets and pending timers would otherwise keep the loop alive.
process.exit(failed.length ? 1 : 0)
