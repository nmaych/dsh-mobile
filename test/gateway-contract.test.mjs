// Guards the contract between the app and the `dsh-mobile-connect` desktop
// plugin: the gateway endpoints, the service identity it accepts, and the
// pairing deep link the QR code carries.
//
// Why this exists: the plugin was renamed from `dsh-connect` to
// `dsh-mobile-connect`, and the app was updated in source — but a *released*
// APK still shipped the old `/.dsh-connect/pair` path. The plugin answers only
// the new path, so that build could never pair: `GET /.dsh-connect/info` fell
// through to the gateway's device-token gate and came back 401, which reads to
// a user as "the pairing code is wrong" rather than "this build is out of
// date". Nothing in the build caught it, because a string rename is invisible
// to the compiler.
//
// These are static checks over the sources, so they run in seconds with no
// Android SDK and fail at PR time instead of on a user's phone.
//
// usage: node test/gateway-contract.test.mjs
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

/** Every Kotlin source, so a rename cannot hide in a file this test forgot. */
function kotlinSources() {
  const root = path.join(repo, 'app-project', 'app', 'src', 'main', 'java')
  const out = []
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) walk(full)
      else if (entry.name.endsWith('.kt')) out.push(full)
    }
  }
  walk(root)
  return out
}

const gatewayClient = read(
  'app-project/app/src/main/java/ai/deepseek/dshmobile/net/GatewayClient.kt',
)
const manifest = read('app-project/app/src/main/AndroidManifest.xml')
const mainActivity = read(
  'app-project/app/src/main/java/ai/deepseek/dshmobile/MainActivity.kt',
)

// ------------------------------------------------------------- the endpoints

console.log('=== gateway endpoints ===')

check('the app probes the current info endpoint', () => {
  assert.ok(
    gatewayClient.includes('/.dsh-mobile-connect/info'),
    'GatewayClient.kt must GET /.dsh-mobile-connect/info; the plugin answers nothing else',
  )
})

check('the app pairs against the current pair endpoint', () => {
  assert.ok(
    gatewayClient.includes('/.dsh-mobile-connect/pair'),
    'GatewayClient.kt must POST /.dsh-mobile-connect/pair',
  )
})

check('the app accepts the current service identity', () => {
  assert.ok(
    gatewayClient.includes('"dsh-mobile-connect"'),
    'GatewayClient.kt must match service == "dsh-mobile-connect", or probe() will ' +
      'treat every real gateway as "not a gateway"',
  )
})

check('no source still refers to the pre-rename plugin path', () => {
  // `/.dsh-connect/` is the old name. Matching it as a *path* keeps the
  // hyphenated words `dsh-connect` in prose from tripping the check while still
  // catching a live endpoint.
  const offenders = []
  for (const file of kotlinSources()) {
    const text = fs.readFileSync(file, 'utf8')
    if (text.includes('/.dsh-connect/')) {
      offenders.push(path.relative(repo, file))
    }
  }
  assert.deepEqual(
    offenders,
    [],
    `these files still call the pre-rename gateway path /.dsh-connect/: ${offenders.join(', ')}`,
  )
})

// ------------------------------------------------------------ the deep link

console.log('\n=== pairing deep link ===')

check('the manifest registers the dshmobile://pair deep link', () => {
  assert.ok(
    /<data\s+android:scheme="dshmobile"\s+android:host="pair"\s*\/>/.test(manifest),
    'AndroidManifest.xml must declare <data android:scheme="dshmobile" android:host="pair"/> ' +
      'inside a VIEW/BROWSABLE intent-filter, or the QR code opens nothing',
  )
})

check('the deep link is browsable and viewable', () => {
  assert.ok(
    manifest.includes('android.intent.action.VIEW'),
    'the deep-link filter needs the VIEW action',
  )
  assert.ok(
    manifest.includes('android.intent.category.BROWSABLE'),
    'the deep-link filter needs the BROWSABLE category, or a camera/scanner cannot open it',
  )
})

check('the activity reads the same scheme and host it registers', () => {
  assert.ok(
    mainActivity.includes('"dshmobile"'),
    'MainActivity.kt must look for the dshmobile scheme',
  )
  assert.ok(
    mainActivity.includes('"pair"'),
    'MainActivity.kt must look for the pair host',
  )
})

check('the pairing link parser accepts the plugin\'s query shape', () => {
  // The plugin builds `dshmobile://pair?host=…&port=…&code=…` (see its
  // lib/pair-url.js). The parser must pull those three keys out.
  const viewModel = read(
    'app-project/app/src/main/java/ai/deepseek/dshmobile/ui/ChatViewModel.kt',
  )
  for (const key of ['host', 'port', 'code']) {
    assert.ok(
      viewModel.includes(`"${key}"`),
      `ChatViewModel.pairFromLink must read the "${key}" query parameter`,
    )
  }
})

check('the app sends the device token as the gateway expects it', () => {
  // The gateway reads `?t=` (see its lib/gateway.js #authenticate). A header
  // would not work for the WebSocket handshake, which is why it is a query
  // parameter on both transports.
  const dshClient = read(
    'app-project/app/src/main/java/ai/deepseek/dshmobile/data/DshClient.kt',
  )
  assert.ok(
    dshClient.includes('t=$token') || dshClient.includes('&t='),
    'DshClient must append the device token as the `t` query parameter',
  )
})

// --------------------------------------------------------- re-pairing a phone

console.log('\n=== re-pairing the same phone ===')

check('the app identifies itself when pairing', () => {
  // The plugin keys its device list by this identity, which is what makes a
  // second pairing replace this phone's row instead of adding a duplicate.
  // Omitting it silently restores the append-only behaviour that caused
  // "connecting twice shows two identical paired devices".
  const gatewayClient = read(
    'app-project/app/src/main/java/ai/deepseek/dshmobile/net/GatewayClient.kt',
  )
  assert.ok(
    gatewayClient.includes('"deviceId"'),
    'GatewayClient.pair must send the `deviceId` field; without it the desktop ' +
      'cannot tell a re-pair from a second phone and appends a duplicate row',
  )
})

check('the pairing identity is stable across launches', () => {
  // A freshly generated identity on every pairing would defeat the point: the
  // desktop would see a new device each time, which is the bug being fixed.
  const prefs = read('app-project/app/src/main/java/ai/deepseek/dshmobile/data/Prefs.kt')
  assert.ok(
    prefs.includes('KEY_INSTALL_ID'),
    'Prefs must persist the install identity under its own key',
  )
  assert.ok(
    /sp\.edit\s*\{\s*putString\(KEY_INSTALL_ID/.test(prefs),
    'the install identity must be written back to SharedPreferences, not ' +
      'regenerated per call',
  )
})

check('the app passes its install identity into the pairing call', () => {
  const viewModel = read(
    'app-project/app/src/main/java/ai/deepseek/dshmobile/ui/ChatViewModel.kt',
  )
  // Match the call's argument list, allowing the nested `deviceName()` call the
  // real code has — a flat `[^)]*` would stop at that inner paren.
  assert.ok(
    /gateway\.pair\((?:[^()]|\([^()]*\))*installId/.test(viewModel),
    'ChatViewModel.pairWithCode must pass prefs.installId to gateway.pair',
  )
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
