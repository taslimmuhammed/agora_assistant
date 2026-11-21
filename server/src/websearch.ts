import type { Config } from './config.ts'
import { ModelChoice, SlowModelError, isBusy } from './models.ts'
import type { ToolDeclaration } from './sessions.ts'

/**
 * Looking things up on the web for her calls and chats: Gemini answers with Google Search
 * (grounding), so news, weather and prices need no key of the phone's. Offered only when the
 * phone has no web_lookup of its own (a Tavily key in its settings), whose name it shares.
 */

/** What a look-up found: a short answer, and the sites it came from. */
export interface WebAnswer {
  answer: string
  sources: Array<{ title: string; url: string }>
}

export interface WebLookup {
  /** Whether to offer it now: not for a while after Google refused every model a search. */
  ready(): boolean
  find(query: string, signal?: AbortSignal): Promise<WebAnswer>
}

export const WEB_LOOKUP = 'web_lookup'

export const WEB_LOOKUP_TOOL: ToolDeclaration = {
  name: WEB_LOOKUP,
  description: 'Looks something up on the web with Google Search: news, weather, prices, scores, opening hours, and anything else that changes or that she asks you to look up. Returns a short answer and the sites it came from: tell her the answer simply and name the site. General knowledge, how-to, advice and recipes: answer yourself. Never put her health readings or private details in the query.',
  parameters: {
    type: 'object',
    properties: {
      query: { type: 'string', description: 'What to look up, as a search, with the place and day when they matter ("weather in Kochi today").' },
    },
    required: ['query'],
  },
}

/** A call can't wait long: the configured model gets this long before a quicker one is asked. */
const FIRST_MS = 7_000
const LAST_MS = 12_000

/** How long web_lookup is off once every model answered 429: a key without search (no billing) or out of it. */
const OFF_MS = 15 * 60_000

/**
 * [config]'s Gemini, with Google Search. Its own choice of model: a search refused (429) mustn't
 * send the calls themselves to the fallbacks.
 */
export function geminiWebLookup(config: Config, clock: () => Date = () => new Date()): WebLookup {
  const models = new ModelChoice(config.llm.model, config.llm.fallbackModel)
  let offUntil = 0
  return {
    ready: () => clock().getTime() >= offUntil,
    find: async (query, signal) => {
      try {
        return await lookUp(config, models, clock(), query, signal)
      } catch (e) {
        if ((e as { status?: unknown }).status === 429) {
          offUntil = clock().getTime() + OFF_MS
          console.warn(`[web] Google Search refused every model (429): web_lookup is off for ${OFF_MS / 60_000} minutes. Searching needs billing on the Gemini key, or a Tavily key in Luna's settings on the phone.`)
        }
        throw e
      }
    },
  }
}

async function lookUp(config: Config, models: ModelChoice, now: Date, query: string, signal?: AbortSignal): Promise<WebAnswer> {
  const today = now.toLocaleString('en-IN', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', hour: 'numeric', minute: '2-digit', hour12: true })
  const prompt = [
    `Look this up and answer in two or three plain sentences, with the facts that matter: numbers, dates, names, places.`,
    `It is now ${today} (${config.timeZone}). Say if what you found is old or you're unsure.`,
    `Question: ${query}`,
  ].join('\n')
  const order = models.order()
  for (const [i, model] of order.entries()) {
    const last = i === order.length - 1
    try {
      const found = await ask(config, model, prompt, last ? LAST_MS : FIRST_MS, signal)
      if (found.answer) return found
      throw new Error(`${model} found nothing`)
    } catch (e) {
      if (signal?.aborted || last || !isBusy(e)) throw e
      models.busy(model)
    }
  }
  throw new Error('No model to ask')
}

interface Grounded {
  candidates?: Array<{
    content?: { parts?: Array<{ text?: string }> }
    groundingMetadata?: { groundingChunks?: Array<{ web?: { uri?: string; title?: string } }> }
  }>
}

async function ask(config: Config, model: string, prompt: string, ms: number, signal?: AbortSignal): Promise<WebAnswer> {
  let response: Response
  try {
    response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model.replace(/^models\//, '')}:generateContent`, {
      method: 'POST',
      headers: { 'x-goog-api-key': config.llm.apiKey, 'Content-Type': 'application/json' },
      body: JSON.stringify({ contents: [{ role: 'user', parts: [{ text: prompt }] }], tools: [{ google_search: {} }], generationConfig: { temperature: 0.2 } }),
      signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(ms)]) : AbortSignal.timeout(ms),
    })
  } catch (e) {
    throw (e as Error).name === 'TimeoutError' ? new SlowModelError(model, ms) : e
  }
  if (!response.ok) throw Object.assign(new Error(`${model} answered ${response.status}`), { status: response.status })
  const candidate = ((await response.json()) as Grounded).candidates?.[0]
  const answer = (candidate?.content?.parts?.map(p => p.text ?? '').join('') ?? '').replace(/\s+/g, ' ').trim()
  const seen = new Set<string>()
  const sources = (candidate?.groundingMetadata?.groundingChunks ?? [])
    .map(c => ({ title: c.web?.title?.trim() ?? '', url: c.web?.uri ?? '' }))
    .filter(s => s.title && !seen.has(s.title) && seen.add(s.title))
    .slice(0, 3)
  return { answer, sources }
}
