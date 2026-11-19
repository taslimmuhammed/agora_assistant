import { existsSync } from 'node:fs'

/** Everything the server reads from the environment; see .env.example for what each one is. */
export interface Config {
  port: number
  /** The https address Agora reaches this server at, without a trailing slash. */
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
  /** WhatsApp through Baileys: off with WHATSAPP=off; the signed-in session is kept in authDir. */
  whatsapp: { enabled: boolean; authDir: string }
  dbPath: string
  seedPath: string
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const read = (name: string, fallback = '') => (env[name] ?? '').trim() || fallback
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
      // GEMINI_* as well: the names Google's own examples use.
      apiKey: read('LLM_API_KEY', read('GEMINI_API_KEY')),
      model: read('LLM_MODEL', read('GEMINI_MODEL_ID', 'gemini-2.5-flash')),
      reasoningEffort: read('LLM_REASONING_EFFORT'),
    },
    murf: {
      apiKey: read('MURF_API_KEY'),
      voiceId: read('MURF_VOICE', read('MURF_VOICE_ID', 'Anusha')),
      locale: read('MURF_LOCALE', 'en-IN'),
      model: read('MURF_MODEL', 'FALCON'),
      rate: Number(read('MURF_RATE', '0')),
      baseUrl: read('MURF_BASE_URL'),
    },
    whatsapp: {
      enabled: !/^(off|false|0|no)$/i.test(read('WHATSAPP', 'on')),
      authDir: read('WHATSAPP_AUTH_DIR', 'data/whatsapp-auth'),
    },
    dbPath: read('DB_PATH', 'data/hub.db'),
    // The local copy, with real numbers, when there is one.
    seedPath: read('SEED_PATH', existsSync('seed/family.local.json') ? 'seed/family.local.json' : 'seed/family.json'),
  }
}
