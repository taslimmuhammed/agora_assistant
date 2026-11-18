import { createHash } from 'node:crypto'

/** Everything the server reads from the environment; see .env.example for what each one is. */
export interface Config {
  port: number
  /** The https address Agora and Telegram reach this server at, without a trailing slash. */
  publicUrl: string
  appKey: string
  llmProxySecret: string
  timeZone: string
  agora: {
    appId: string
    appCertificate: string
    customerId: string
    customerSecret: string
    /** The join request's whole asr block. */
    asr: Record<string, unknown>
  }
  llm: { baseUrl: string; apiKey: string; model: string; reasoningEffort: string }
  murf: { apiKey: string; voiceId: string; locale: string; model: string; rate: number; baseUrl: string }
  telegram: { botToken: string; webhookSecret: string }
  dbPath: string
  seedPath: string
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const read = (name: string, fallback = '') => (env[name] ?? '').trim() || fallback
  const botToken = read('TELEGRAM_BOT_TOKEN')
  const asrJson = read('AGORA_ASR_JSON')
  return {
    port: Number(read('PORT', '8080')),
    publicUrl: read('PUBLIC_URL').replace(/\/+$/, ''),
    appKey: read('APP_KEY'),
    llmProxySecret: read('LLM_PROXY_SECRET'),
    timeZone: read('TIME_ZONE', 'Asia/Kolkata'),
    agora: {
      appId: read('AGORA_APP_ID'),
      appCertificate: read('AGORA_APP_CERTIFICATE'),
      customerId: read('AGORA_CUSTOMER_ID'),
      customerSecret: read('AGORA_CUSTOMER_SECRET'),
      asr: asrJson
        ? JSON.parse(asrJson)
        : { vendor: read('AGORA_ASR_VENDOR', 'ares'), language: read('AGORA_ASR_LANGUAGE', 'en-IN') },
    },
    llm: {
      baseUrl: read('LLM_BASE_URL', 'https://generativelanguage.googleapis.com/v1beta/openai/'),
      apiKey: read('LLM_API_KEY'),
      model: read('LLM_MODEL', 'gemini-2.5-flash'),
      reasoningEffort: read('LLM_REASONING_EFFORT'),
    },
    murf: {
      apiKey: read('MURF_API_KEY'),
      voiceId: read('MURF_VOICE', 'Anusha'),
      locale: read('MURF_LOCALE', 'en-IN'),
      model: read('MURF_MODEL', 'FALCON'),
      rate: Number(read('MURF_RATE', '0')),
      baseUrl: read('MURF_BASE_URL'),
    },
    telegram: {
      botToken,
      // Telegram repeats it on every webhook call; derived from the token so there's one less
      // thing to set. Only letters and digits are allowed, which hex is.
      webhookSecret: botToken ? createHash('sha256').update(botToken).digest('hex').slice(0, 32) : '',
    },
    dbPath: read('DB_PATH', 'data/hub.db'),
    seedPath: read('SEED_PATH', 'seed/family.json'),
  }
}
