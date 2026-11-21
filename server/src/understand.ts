import type { Config } from './config.ts'
import { ModelChoice, SlowModelError, isBusy } from './models.ts'
import type { Understand } from './whatsapp.ts'

/**
 * Gemini reads a family member's WhatsApp reply to Luna, whatever it is: a sentence in English,
 * Hindi or Hinglish, a voice note, a photo, a sticker, a reaction. It says which of the message's
 * choices the reply makes, if any, and what it says for her, in plain English, to be spoken in
 * her call.
 *
 * Gemini's own API rather than the OpenAI-compatible one the calls use: it takes WhatsApp's voice
 * notes (Ogg Opus) and stickers (WebP) as they are. The configured model gets [FIRST_MS]; when
 * it's busy or slow the fallback (models.ts) reads it.
 */

const FIRST_MS = 10_000
const LAST_MS = 25_000

export function geminiUnderstand(config: Config, models = new ModelChoice(config.llm.model, config.llm.fallbackModel)): Understand {
  return async ({ reply, luna, options, from, elder }) => {
    const media = reply.media
    const said = media
      ? `the attached ${media.kind}${reply.text ? `, with the caption """${reply.text}"""` : ''}`
      : reply.reaction
        ? `the reaction ${reply.reaction} on that message`
        : `this text: """${reply.text ?? ''}"""`
    const prompt = [
      `You read one WhatsApp reply for Luna, a voice assistant who looks after ${elder}, an older woman. ${from.name} (${from.relation}) is replying${luna ? ` to this message Luna sent on ${elder}'s behalf:\n"""\n${luna}\n"""` : ' to Luna.'}`,
      options.length
        ? `The message asks for one of these choices:\n${options.map((o, i) => `${i + 1}. ${o.text}`).join('\n')}`
        : 'Nothing is waiting for a choice.',
      `The reply is ${said}. It may be in English, Hindi, Hinglish or another language, or only emoji.`,
      'Answer with JSON:',
      `- choice: the number of the choice the reply clearly makes ("sure", "haan main le jaunga", "can't today", 👍, ❤️ for yes), or 0 if it makes none or you can't tell.`,
      `- message: what ${from.name} says for ${elder}, in short plain English, as ${from.name}'s own words ("I'll pick you up at 4."), to be read aloud to her: no emoji, and names and words like Amma, Ma or Papa kept as they are. ` +
        `For a photo, say briefly what it shows, as ${from.name} would ("Here's the medicine I bought: Amlodipine 5 mg."). ` +
        `Leave it empty when the reply only makes the choice and adds nothing ("ok", "1", "haan", a reaction, a thumbs-up sticker).`,
    ].join('\n\n')
    const parts: Array<Record<string, unknown>> = [{ text: prompt }]
    if (media) parts.push({ inline_data: { mime_type: media.mimeType.split(';')[0].trim(), data: media.data.toString('base64') } })

    const order = models.order()
    for (const [i, model] of order.entries()) {
      const last = i === order.length - 1
      try {
        return read(await ask(model, parts, last ? LAST_MS : FIRST_MS), options.length)
      } catch (e) {
        if (last || !isBusy(e)) throw e
        models.busy(model)
        // A moment before the next: a 503 is often a spike that passes.
        await new Promise(r => setTimeout(r, 600))
      }
    }
    throw new Error('No model to ask')
  }

  async function ask(model: string, parts: Array<Record<string, unknown>>, ms: number): Promise<string> {
    let response: Response
    try {
      response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model.replace(/^models\//, '')}:generateContent`, {
        method: 'POST',
        headers: { 'x-goog-api-key': config.llm.apiKey, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          contents: [{ role: 'user', parts }],
          generationConfig: {
            temperature: 0,
            responseMimeType: 'application/json',
            responseSchema: { type: 'OBJECT', properties: { choice: { type: 'INTEGER' }, message: { type: 'STRING' } }, required: ['choice', 'message'] },
          },
        }),
        signal: AbortSignal.timeout(ms),
      })
    } catch (e) {
      throw (e as Error).name === 'TimeoutError' ? new SlowModelError(model, ms) : e
    }
    if (!response.ok) throw Object.assign(new Error(`${model} answered ${response.status}: ${(await response.text()).slice(0, 300)}`), { status: response.status })
    const json = (await response.json()) as { candidates?: Array<{ content?: { parts?: Array<{ text?: string }> } }> }
    return json.candidates?.[0]?.content?.parts?.map(p => p.text ?? '').join('') ?? ''
  }
}

/** The model's JSON, checked: a choice that isn't one of the options is none. */
function read(text: string, count: number) {
  const answer = JSON.parse(text) as { choice?: number; message?: string }
  const choice = Number(answer.choice)
  return {
    choice: Number.isInteger(choice) && choice >= 1 && choice <= count ? choice : null,
    message: String(answer.message ?? '').trim(),
  }
}
