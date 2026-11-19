import { existsSync } from 'node:fs'
import { loadConfig } from './config.ts'

const config = loadConfig()
// Before anything reads the clock: times are hers ("4:30" means 4:30 in Bengaluru), whatever
// zone the host runs in.
process.env.TZ = config.timeZone

const { AgoraAgents } = await import('./agora.ts')
const { createApp } = await import('./app.ts')
const { Bridge } = await import('./bridge.ts')
const { runFollowUps } = await import('./followups.ts')
const { Hub } = await import('./hub.ts')
const { openAiChat } = await import('./llm.ts')
const { handleEvent } = await import('./replies.ts')
const { Sessions } = await import('./sessions.ts')
const { WhatsApp, connectWhatsApp } = await import('./whatsapp.ts')

if (!existsSync(config.seedPath)) {
  console.error(`No family at ${config.seedPath}. Copy seed/family.json there and fill in everyone's WhatsApp number, or set SEED_PATH.`)
  process.exit(1)
}
const hub = Hub.fromFiles(config.dbPath, config.seedPath)
const clock = () => new Date()
const whatsapp = config.whatsapp.enabled ? new WhatsApp(hub, clock) : null
const { app, attachBridge, announce } = createApp({
  config,
  hub,
  sessions: new Sessions(),
  bridge: new Bridge(),
  agents: new AgoraAgents(config),
  chat: openAiChat(config),
  whatsapp,
  clock,
})

const server = app.listen(config.port, () => {
  console.log(`Luna server on :${config.port}${config.publicUrl ? `, public at ${config.publicUrl}` : ' (no PUBLIC_URL: agents can\'t be started)'}`)
  const missing = [
    ['AGORA_APP_ID', config.agora.appId], ['AGORA_APP_CERTIFICATE', config.agora.appCertificate],
    ['AGORA_CUSTOMER_ID', config.agora.customerId], ['AGORA_CUSTOMER_SECRET', config.agora.customerSecret],
    ['LLM_API_KEY', config.llm.apiKey], ['MURF_API_KEY', config.murf.apiKey],
    ['APP_KEY', config.appKey], ['LLM_PROXY_SECRET', config.llmProxySecret],
  ].filter(([, value]) => !value).map(([name]) => name)
  if (missing.length) console.warn(`Not set: ${missing.join(', ')}`)
  if (whatsapp) console.log(`WhatsApp sign-in page: http://localhost:${config.port}/whatsapp/qr${config.appKey ? '?key=<APP_KEY>' : ''}`)
})
attachBridge(server)

if (whatsapp) {
  whatsapp.onEvent(event => handleEvent(event, { hub, whatsapp, announce, clock }))
  connectWhatsApp(whatsapp, hub, config.whatsapp.authDir).catch(e => console.warn(`WhatsApp: ${(e as Error).message}`))
}

setInterval(() => {
  runFollowUps(hub, whatsapp, clock()).then(did => {
    if (did.length) console.log(`[follow-ups] ${did.join('; ')}`)
  }).catch(e => console.warn('[follow-ups]', (e as Error).message))
}, 30_000)
