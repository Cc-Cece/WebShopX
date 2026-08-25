import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
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

const startedAt = new Date().toISOString()
const writeJson = (file, value) => fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, 'utf8')
const offlineUuid = (username) => {
  const bytes = createHash('md5').update(`OfflinePlayer:${username}`, 'utf8').digest()
  bytes[6] = (bytes[6] & 0x0f) | 0x30
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = bytes.toString('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

if (options['mcc-executable']) runMcc()
else runProtocolClient()

function runProtocolClient () {
  let loggedIn = false
  let requestedDisconnect = false
  let finished = false
  let identity = null
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
    identity = { username: options.username, uuid: client.uuid, version: options.version }
    writeJson(readyFile, { status: 'joined', ...identity, joinedAt: new Date().toISOString() })
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
}

function runMcc () {
  for (const required of ['mcc-sha256', 'client-log', 'client-error-log']) {
    if (!options[required]) throw new Error(`Missing --${required} for MCC verification`)
  }
  const executable = path.resolve(options['mcc-executable'])
  const clientLog = path.resolve(options['client-log'])
  const clientErrorLog = path.resolve(options['client-error-log'])
  const actualHash = createHash('sha256').update(fs.readFileSync(executable)).digest('hex')
  if (actualHash !== options['mcc-sha256']) throw new Error('MCC executable hash changed after verification')

  const workingDirectory = path.join(path.dirname(evidenceFile), `${path.basename(evidenceFile, '.json')}-mcc`)
  fs.mkdirSync(workingDirectory, { recursive: true })
  const config = path.join(workingDirectory, 'probe.ini')
  fs.writeFileSync(config, [
    '[Main.General]',
    `Account = { Login = "${options.username}", Password = "-" }`,
    `Server = { Host = "${options.host}", Port = ${Number.parseInt(options.port, 10)} }`,
    '',
    '[Main.Advanced]',
    'EnableSentry = false',
    'Language = "en_us"',
    'LoadMccTranslation = false',
    `MinecraftVersion = "${options.version}"`,
    'ExitOnFailure = true',
    'SessionCache = "none"',
    'ProfileKeyCache = "none"',
    'ShowGithubStarReminder = false',
    'TerrainAndMovements = false',
    'InventoryHandling = false',
    'EntityHandling = false',
    ''
  ].join('\n'), 'ascii')

  let stdout = ''
  let stderr = ''
  let loggedIn = false
  let requestedDisconnect = false
  let finished = false
  const identity = { username: options.username, uuid: offlineUuid(options.username), version: options.version }
  const child = spawn(executable, [config, 'BasicIO-NoColor'], {
    cwd: workingDirectory,
    stdio: ['pipe', 'pipe', 'pipe'],
    windowsHide: true
  })
  const append = (current, chunk) => (current + chunk.toString('utf8')).slice(-2_000_000)
  child.stdout.on('data', (chunk) => {
    stdout = append(stdout, chunk)
    if (!loggedIn && stdout.includes('[MCC] Server was successfully joined.')) {
      loggedIn = true
      writeJson(readyFile, { status: 'joined', ...identity, joinedAt: new Date().toISOString() })
    }
  })
  child.stderr.on('data', (chunk) => { stderr = append(stderr, chunk) })

  const finish = (status, detail = {}) => {
    if (finished) return
    finished = true
    clearTimeout(timeout)
    clearInterval(disconnectPoll)
    fs.mkdirSync(path.dirname(clientLog), { recursive: true })
    fs.writeFileSync(clientLog, stdout, 'utf8')
    fs.writeFileSync(clientErrorLog, stderr, 'utf8')
    writeJson(evidenceFile, {
      status,
      startedAt,
      finishedAt: new Date().toISOString(),
      ...identity,
      client: 'Minecraft Console Client 20260704-478',
      clientSha256: actualHash,
      ...detail
    })
    process.exitCode = status === 'passed' ? 0 : 1
  }
  const timeout = setTimeout(() => {
    child.kill()
    finish('failed', { reason: loggedIn ? 'disconnect_timeout' : 'login_timeout' })
  }, Number.parseInt(options['timeout-ms'] ?? '60000', 10))
  const disconnectPoll = setInterval(() => {
    if (loggedIn && fs.existsSync(disconnectFile)) {
      requestedDisconnect = true
      clearInterval(disconnectPoll)
      setTimeout(() => {
        if (!child.killed && child.exitCode === null) child.stdin.write('/quit\n')
      }, 1500)
    }
  }, 100)
  child.on('error', (error) => finish('failed', { reason: 'client_error', detail: error.message }))
  child.on('close', (code) => {
    const escapedVersion = options.version.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const protocolMatch = stdout.match(
      new RegExp(`Using Minecraft version ${escapedVersion} \\(protocol v(\\d+)\\)`))
    const outputValid = protocolMatch !== null && stdout.includes('[MCC] Server was successfully joined.')
    const passed = code === 0 && loggedIn && requestedDisconnect && outputValid
    finish(passed ? 'passed' : 'failed', {
      reason: passed ? 'requested_disconnect' : 'unexpected_disconnect',
      protocol: protocolMatch === null ? null : Number.parseInt(protocolMatch[1], 10),
      exitCode: code
    })
  })
}
