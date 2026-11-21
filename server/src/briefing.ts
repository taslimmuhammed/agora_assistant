import type { Config } from './config.ts'
import type { Hub, HubEvent } from './hub.ts'
import { ModelChoice, SlowModelError, isBusy } from './models.ts'

/**
 * What Luna says when she opens the app after being away: one short, warm message about what came
 * meanwhile ("Good morning, Lakshmi! Filu will bring your BP tablets this evening at 6, and she'll
 * call you tonight."), rather than each answer as it was written. Gemini writes it, saying times
 * from now, since "tomorrow" in an answer from last night means today; Murf speaks it, in the
 * voice her calls use. Without either, the answers are put together as they are, and the phone
 * speaks them with its own voice (Kokoro, else the phone's).
 */

export interface Update {
  text: string
  at: number
  /** When what it's about is due: a task's or a request's time. */
  due: number | null
}

/** [greet]: she's just opened the app; otherwise she's using it, and this is news as it comes. */
export interface BriefingInput { elder: string; now: Date; updates: Update[]; greet: boolean }
export type WriteBriefing = (input: BriefingInput) => Promise<string>
export type Speak = (text: string) => Promise<{ mime: string; data: string } | null>

/** [events] with the time each one's task is due, for saying "this evening" rather than "tomorrow". */
export function updatesFor(hub: Hub, events: HubEvent[]): Update[] {
  return events.map(e => {
    const item = (e.item ?? {}) as { task?: number; request?: number }
    const due = item.task ? hub.task(item.task)?.dueAt : null
    return { text: e.text, at: e.at, due: due ?? null }
  })
}

export function hello(now: Date): string {
  const hour = now.getHours()
  return hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening'
}

/** The answers as they are, after a greeting: when no model can write it. */
export function plainBriefing({ elder, now, updates, greet }: BriefingInput): string {
  const news = updates.map(u => u.text.trim()).map(t => (/[.!?]$/.test(t) ? t : `${t}.`)).join(' ')
  return greet ? `${hello(now)}, ${elder}! While you were away: ${news}` : news
}

const FIRST_MS = 8_000
const LAST_MS = 15_000

const when = (ms: number) =>
  new Date(ms).toLocaleString('en-IN', { weekday: 'long', day: 'numeric', month: 'long', hour: 'numeric', minute: '2-digit', hour12: true })

export function geminiBriefing(config: Config, models = new ModelChoice(config.llm.model, config.llm.fallbackModel)): WriteBriefing {
  return async ({ elder, now, updates, greet }) => {
    const prompt = [
      greet
        ? `You are Luna, the warm voice assistant of ${elder}, an older woman who lives on her own. She has just opened the app on her phone. Tell her what happened while she was away (below), in one to three short sentences. It is read aloud to her.`
        : `You are Luna, the warm voice assistant of ${elder}, an older woman who lives on her own. She is using the app on her phone, and news just came (below). Tell her, in one or two short sentences. It is read aloud to her.`,
      greet ? `- Begin with "${hello(now)}, ${elder}!"` : '- No greeting: start with the news ("Filu just answered: she\'ll call you tonight.").',
      '- Say it as a caring friend would, in simple spoken English: no list, no emoji, no markdown, no quotation marks.',
      `- It is now ${when(now.getTime())}. Each update was written when it came, so its "today" or "tomorrow" may be out of date: say when things happen from now ("this evening at 6", "tomorrow morning"), going by the times given.`,
      `- In an update, "my" and "me" are ${elder}'s own words from when she asked: "Filu said yes: bring my BP tablets" means Filu will bring ${elder}'s BP tablets. Tell her about her own things as "your", and keep who does what exactly as the update says.`,
      '- Keep names and words like Amma as they are. Add nothing that isn\'t in the updates.',
      `Updates, oldest first:\n${updates.map(u => `- came ${when(u.at)}: ${u.text}${u.due ? ` (it's for ${when(u.due)})` : ''}`).join('\n')}`,
    ].join('\n')
    const order = models.order()
    for (const [i, model] of order.entries()) {
      const last = i === order.length - 1
      try {
        const text = (await ask(model, prompt, last ? LAST_MS : FIRST_MS)).replace(/\s+/g, ' ').trim()
        if (text) return text
        throw new Error(`${model} wrote nothing`)
      } catch (e) {
        if (last || !isBusy(e)) throw e
        models.busy(model)
      }
    }
    throw new Error('No model to ask')
  }

  async function ask(model: string, prompt: string, ms: number): Promise<string> {
    let response: Response
    try {
      response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model.replace(/^models\//, '')}:generateContent`, {
        method: 'POST',
        headers: { 'x-goog-api-key': config.llm.apiKey, 'Content-Type': 'application/json' },
        body: JSON.stringify({ contents: [{ role: 'user', parts: [{ text: prompt }] }], generationConfig: { temperature: 0.4 } }),
        signal: AbortSignal.timeout(ms),
      })
    } catch (e) {
      throw (e as Error).name === 'TimeoutError' ? new SlowModelError(model, ms) : e
    }
    if (!response.ok) throw Object.assign(new Error(`${model} answered ${response.status}`), { status: response.status })
    const json = (await response.json()) as { candidates?: Array<{ content?: { parts?: Array<{ text?: string }> } }> }
    return json.candidates?.[0]?.content?.parts?.map(p => p.text ?? '').join('') ?? ''
  }
}

/** [text] in Murf Falcon's voice, the one her calls use, as an MP3; null when Murf can't. */
export function murfSpeech(config: Config): Speak {
  return async text => {
    if (!config.murf.apiKey) return null
    try {
      const response = await fetch('https://global.api.murf.ai/v1/speech/stream', {
        method: 'POST',
        headers: { 'api-key': config.murf.apiKey, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          text, voiceId: config.murf.voiceId, model: config.murf.model, locale: config.murf.locale,
          rate: config.murf.rate, format: 'MP3', sampleRate: 24000, channelType: 'MONO',
        }),
        signal: AbortSignal.timeout(12_000),
      })
      if (!response.ok) {
        console.warn(`[briefing] Murf answered ${response.status}: ${(await response.text()).slice(0, 200)}`)
        return null
      }
      return { mime: 'audio/mpeg', data: Buffer.from(await response.arrayBuffer()).toString('base64') }
    } catch (e) {
      console.warn(`[briefing] Murf failed: ${(e as Error).message}`)
      return null
    }
  }
}
