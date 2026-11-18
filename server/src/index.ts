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
const { Sessions } = await import('./sessions.ts')
const { TelegramBot, handleUpdate } = await import('./telegram.ts')

const hub = Hub.fromFiles(config.dbPath, config.seedPath)
const bot = config.telegram.botToken ? new TelegramBot(config.telegram.botToken) : null
const clock = () => new Date()
const { app, attachBridge, announce } = createApp({
  config,
  hub,
  sessions: new Sessions(),
  bridge: new Bridge(),
  agents: new AgoraAgents(config),
  chat: openAiChat(config),
  messenger: bot,
  clock,
})

const server = app.listen(config.port, () => {
  console.log(`Luna server on :${config.port}${config.publicUrl ? `, public at ${config.publicUrl}` : ' (no PUBLIC_URL: agents can\'t be started)'}`)
  const missing = [
    ['AGORA_APP_ID', config.agora.appId], ['AGORA_APP_CERTIFICATE', config.agora.appCertificate],
    ['AGORA_CUSTOMER_ID', config.agora.customerId], ['AGORA_CUSTOMER_SECRET', config.agora.customerSecret],
    ['LLM_API_KEY', config.llm.apiKey], ['MURF_API_KEY', config.murf.apiKey], ['TELEGRAM_BOT_TOKEN', config.telegram.botToken],
    ['APP_KEY', config.appKey], ['LLM_PROXY_SECRET', config.llmProxySecret],
  ].filter(([, value]) => !value).map(([name]) => name)
  if (missing.length) console.warn(`Not set: ${missing.join(', ')}`)
})
attachBridge(server)

if (bot) {
  if (config.publicUrl) {
    bot.setWebhook(`${config.publicUrl}/telegram/webhook`, config.telegram.webhookSecret)
      .then(() => console.log('Telegram webhook set'))
      .catch(e => console.warn(`Telegram webhook: ${(e as Error).message}`))
  } else {
    void bot.startPolling(update => handleUpdate(update, { hub, messenger: bot, announce, clock }))
    console.log('Telegram: polling for updates')
  }
}

setInterval(() => {
  runFollowUps(hub, bot, clock()).then(did => {
    if (did.length) console.log(`[follow-ups] ${did.join('; ')}`)
  }).catch(e => console.warn('[follow-ups]', (e as Error).message))
}, 30_000)
