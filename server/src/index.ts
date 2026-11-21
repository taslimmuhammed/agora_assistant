import { existsSync } from 'node:fs'
import { loadConfig } from './config.ts'

const config = loadConfig()
// Baileys' Signal library logs whole sessions, keys and all, on each re-key: not for a terminal.
for (const level of ['log', 'info', 'warn'] as const) {
  const original = console[level].bind(console)
  console[level] = (...args: unknown[]) => {
    if (typeof args[0] === 'string' && /^(Closing (open )?session|Removing old closed session|Opening session|Migrating session|Session already)/.test(args[0])) return
    original(...args)
  }
}
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
const { geminiUnderstand } = await import('./understand.ts')
const { ModelChoice } = await import('./models.ts')
const { geminiWebLookup } = await import('./websearch.ts')

if (!existsSync(config.seedPath)) {
  console.error(`No family at ${config.seedPath}. Copy seed/family.json there and fill in everyone's WhatsApp number, or set SEED_PATH.`)
  process.exit(1)
}
const hub = Hub.fromFiles(config.dbPath, config.seedPath)
const clock = () => new Date()
// One choice of model for the calls and for reading replies: when the big one is busy, both move.
const models = new ModelChoice(config.llm.model, config.llm.fallbackModel)
// Gemini reads the family's replies (sentences, voice notes, emoji) when there's a key for it.
const whatsapp = config.whatsapp.enabled ? new WhatsApp(hub, clock, config.llm.apiKey ? geminiUnderstand(config, models) : null) : null
const { geminiBriefing, murfSpeech } = await import('./briefing.ts')
const { app, attachBridge, announce } = createApp({
  config,
  hub,
  // A few plain sentences, wanted the moment she opens the app: a quick Flash-Lite first.
  briefing: config.llm.apiKey ? geminiBriefing(config, models.fallbacks.length ? new ModelChoice(models.fallbacks[0], [...models.fallbacks.slice(1), models.primary].join(',')) : models) : undefined,
  speak: murfSpeech(config),
  sessions: new Sessions(),
  bridge: new Bridge(),
  agents: new AgoraAgents(config),
  chat: openAiChat(config, models),
  // Google Search through Gemini, when the calls' model is Gemini.
  webLookup: config.llm.apiKey && /generativelanguage\.googleapis\.com/.test(config.llm.baseUrl) ? geminiWebLookup(config, clock) : undefined,
  whatsapp,
  clock,
})

const server = app.listen(config.port, () => {
  console.log(`Luna server on :${config.port}${config.publicUrl ? `, public at ${config.publicUrl}` : ' (no PUBLIC_URL: agents can\'t be started)'}`)
  const missing = [
    ['AGORA_APP_ID', config.agora.appId], ['AGORA_APP_CERTIFICATE', config.agora.appCertificate],
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
