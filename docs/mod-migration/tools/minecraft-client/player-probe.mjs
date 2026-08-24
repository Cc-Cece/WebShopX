import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import minecraftProtocol from 'minecraft-protocol'

const options = Object.fromEntries(process.argv.slice(2).map((value) => {
  const separator = value.indexOf('=')
  if (!value.startsWith('--') || separator < 3) throw new Error(`Invalid argument: ${value}`)
  return [value.slice(2, separator), value.slice(separator + 1)]
}))

for (const required of ['host', 'port', 'version', 'username', 'ready-file', 'disconnect-file', 'evidence-file']) {
  if (!options[required]) throw new Error(`Missing --${required}`)
}

const evidenceFile = path.resolve(options['evidence-file'])
const readyFile = path.resolve(options['ready-file'])
const disconnectFile = path.resolve(options['disconnect-file'])
for (const file of [evidenceFile, readyFile, disconnectFile]) fs.mkdirSync(path.dirname(file), { recursive: true })

let loggedIn = false
let requestedDisconnect = false
let finished = false
let identity = null
const startedAt = new Date().toISOString()
const writeJson = (file, value) => fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, 'utf8')
const finish = (status, detail = {}) => {
  if (finished) return
  finished = true
  writeJson(evidenceFile, { status, startedAt, finishedAt: new Date().toISOString(), ...identity, ...detail })
  process.exitCode = status === 'passed' ? 0 : 1
}

const client = minecraftProtocol.createClient({
  host: options.host,
  port: Number.parseInt(options.port, 10),
  username: options.username,
  version: options.version,
  auth: 'offline',
  hideErrors: true
})

const timeout = setTimeout(() => {
  finish('failed', { reason: loggedIn ? 'disconnect_timeout' : 'login_timeout' })
  client.end('probe timeout')
}, Number.parseInt(options['timeout-ms'] ?? '60000', 10))

client.once('login', () => {
  loggedIn = true
  identity = {
    username: options.username,
    uuid: client.uuid,
    version: options.version
  }
  writeJson(readyFile, {
    status: 'joined',
    ...identity,
    joinedAt: new Date().toISOString()
  })
})

const disconnectPoll = setInterval(() => {
  if (loggedIn && fs.existsSync(disconnectFile)) {
    requestedDisconnect = true
    clearInterval(disconnectPoll)
    client.end('WebShopX verification complete')
  }
}, 100)

client.on('error', (error) => finish('failed', { reason: 'client_error', detail: error.message }))
client.on('end', (reason) => {
  clearTimeout(timeout)
  clearInterval(disconnectPoll)
  finish(loggedIn && requestedDisconnect ? 'passed' : 'failed', {
    reason: loggedIn && requestedDisconnect ? 'requested_disconnect' : 'unexpected_disconnect',
    protocolReason: String(reason ?? '')
  })
})
