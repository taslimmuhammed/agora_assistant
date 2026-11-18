import agoraToken from 'agora-token'
import type { Config } from './config.ts'

// CommonJS: its exports aren't visible to ESM by name.
const { RtcRole, RtcTokenBuilder } = agoraToken
import type { Session } from './sessions.ts'

/** A day: Agora's limit for a token, and longer than any call. */
const TOKEN_SECONDS = 24 * 60 * 60

/**
 * A token for [account] in [channel] that covers both RTC (audio) and RTM (the agent's
 * transcripts and state come over RTM). The agent's RTM login uses its join token too, so it
 * gets the same kind. Numeric uids are passed as their digits, which AccessToken2 treats the same.
 */
export function rtcRtmToken(config: Config, channel: string, account: string | number): string {
  return RtcTokenBuilder.buildTokenWithRtm(
    config.agora.appId,
    config.agora.appCertificate,
    channel,
    String(account),
    RtcRole.PUBLISHER,
    TOKEN_SECONDS,
    TOKEN_SECONDS,
  )
}

/** The Conversational AI Engine's REST calls the server makes; a fake in tests. */
export interface AgentApi {
  join(body: unknown): Promise<{ agent_id: string; status: string }>
  leave(agentId: string): Promise<void>
  /** Says [text] in the call (at most 512 bytes), after whatever the agent is saying. */
  speak(agentId: string, text: string): Promise<void>
}

export class AgoraAgents implements AgentApi {
  private readonly base: string
  private readonly auth: string

  constructor(config: Config) {
    this.base = `https://api.agora.io/api/conversational-ai-agent/v2/projects/${config.agora.appId}`
    this.auth = 'Basic ' + Buffer.from(`${config.agora.customerId}:${config.agora.customerSecret}`).toString('base64')
  }

  async join(body: unknown) {
    return (await this.post('/join', body)) as { agent_id: string; status: string }
  }

  async leave(agentId: string) {
    await this.post(`/agents/${agentId}/leave`, {})
  }

  async speak(agentId: string, text: string) {
    await this.post(`/agents/${agentId}/speak`, { text: fitBytes(text, 512), priority: 'APPEND', interruptable: true })
  }

  private async post(path: string, body: unknown): Promise<unknown> {
    const response = await fetch(this.base + path, {
      method: 'POST',
      headers: { Authorization: this.auth, 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(15_000),
    })
    const text = await response.text()
    if (!response.ok) throw new Error(`Agora ${path} answered ${response.status}: ${text}`)
    return text ? JSON.parse(text) : {}
  }
}

/**
 * The join request for [session]: Agora listens on the channel (ASR), sends each turn to this
 * server's /v1/chat/completions as the custom LLM, and speaks the reply with Murf Falcon.
 * Transcripts and the agent's state go to the phone over RTM, which the client toolkit reads.
 */
export function joinBody(config: Config, session: Session, prompt: string, greeting: string) {
  const murf: Record<string, unknown> = {
    api_key: config.murf.apiKey,
    voiceId: config.murf.voiceId,
    locale: config.murf.locale,
    model: config.murf.model,
    style: 'Conversational',
    rate: config.murf.rate,
    sample_rate: 24000,
  }
  if (config.murf.baseUrl) murf.base_url = config.murf.baseUrl
  return {
    name: `luna-${session.id}`,
    properties: {
      channel: session.channel,
      token: rtcRtmToken(config, session.channel, session.agentUid),
      agent_rtc_uid: String(session.agentUid),
      remote_rtc_uids: [String(session.uid)],
      // A quiet minute ends the call: the free minutes go to rehearsals, not empty rooms.
      idle_timeout: 60,
      geofence: { area: 'INDIA' },
      advanced_features: { enable_rtm: true },
      parameters: { data_channel: 'rtm', enable_metrics: true, enable_error_message: true },
      asr: config.agora.asr,
      llm: {
        vendor: 'custom',
        url: `${config.publicUrl}/v1/chat/completions?session=${session.id}`,
        api_key: config.llmProxySecret,
        // Each turn's system message is rebuilt here anyway (llm.ts); this one is what Agora
        // keeps if it ever calls without it.
        system_messages: [{ role: 'system', content: prompt }],
        max_history: 20,
        greeting_message: greeting,
        failure_message: "Sorry, I lost my train of thought. Could you say that again?",
        params: { model: config.llm.model },
      },
      tts: { vendor: 'murf', params: murf },
      turn_detection: { config: { end_of_speech: { mode: 'semantic' } } },
      interruption: { enable: true, mode: 'start_of_speech' },
    },
  }
}

/** [text] cut at a word to at most [limit] bytes of UTF-8. */
export function fitBytes(text: string, limit: number): string {
  if (Buffer.byteLength(text) <= limit) return text
  let cut = text
  while (Buffer.byteLength(cut + '…') > limit) cut = cut.slice(0, cut.lastIndexOf(' ') > 0 ? cut.lastIndexOf(' ') : cut.length - 1)
  return cut + '…'
}
