import { timingSafeEqual } from 'node:crypto'
import type { Request, Response } from 'express'
import OpenAI from 'openai'
import type { Bridge } from './bridge.ts'
import type { Config } from './config.ts'
import type { Hub } from './hub.ts'
import { ModelChoice, SlowModelError, isBusy } from './models.ts'
import { buildSystemPrompt } from './prompt.ts'
import type { Session, Sessions, ToolDeclaration } from './sessions.ts'
import type { WhatsApp } from './whatsapp.ts'
import { SERVER_TOOLS, isServerTool, runServerTool } from './tools.ts'

/**
 * Agora's custom LLM: POST /v1/chat/completions?session=…, OpenAI's format, streamed as SSE.
 *
 * Each turn: the system message is rebuilt (prompt.ts), the server's tools and the phone's are
 * offered together, and the model (Gemini Flash through Google's OpenAI-compatible endpoint) is
 * streamed. Its words go straight back to Agora, which speaks them with Murf; its tool calls are
 * run here or on the phone, and the model is asked again with the results, up to MAX_ROUNDS.
 */

const MAX_ROUNDS = 4

/** Said before a tool round the model gave no words for, so the call isn't silent meanwhile. */
const FILLERS = ['Okay. ', 'Sure, one moment. ', 'Alright. ']

/** One streamed chunk, as much of OpenAI's ChatCompletionChunk as is read here. */
export interface ChunkLike {
  choices?: Array<{ delta?: { content?: string | null; tool_calls?: ToolCallDelta[] } }>
}

export interface ToolCallDelta {
  index?: number
  id?: string
  type?: string
  function?: { name?: string; arguments?: string }
  [extra: string]: unknown
}

/** Streams a completion for [params]; a fake in tests. */
export type ChatFn = (params: Record<string, unknown>, signal: AbortSignal) => Promise<AsyncIterable<ChunkLike>>

/** How long the configured model has to start answering before the fallback is asked. */
const FIRST_WORDS_MS = 8_000

export function openAiChat(config: Config, models = new ModelChoice(config.llm.model, config.llm.fallbackModel), firstWordsMs = FIRST_WORDS_MS): ChatFn {
  // No retries of its own: a call can't wait through them. A busy model hands over instead.
  const client = new OpenAI({ apiKey: config.llm.apiKey || 'missing', baseURL: config.llm.baseUrl, maxRetries: 0 })
  return async (params, signal) => {
    const order = params.model === models.primary ? models.order() : [String(params.model)]
    for (const [i, model] of order.entries()) {
      const last = i === order.length - 1
      try {
        return await streamStarted(client, { ...params, model }, signal, last ? 30_000 : firstWordsMs)
      } catch (e) {
        if (signal.aborted || last || !isBusy(e)) throw e
        models.busy(model)
      }
    }
    throw new Error('No model to ask')
  }
}

/** The stream, once its first chunk is in: a model that hangs without a word fails after [ms]. */
async function streamStarted(client: OpenAI, params: Record<string, unknown>, signal: AbortSignal, ms: number): Promise<AsyncIterable<ChunkLike>> {
  const controller = new AbortController()
  const stop = () => controller.abort()
  signal.addEventListener('abort', stop, { once: true })
  let slow = false
  const timer = setTimeout(() => {
    slow = true
    controller.abort()
  }, ms)
  try {
    const stream = (await client.chat.completions.create(
      { ...params, stream: true } as unknown as OpenAI.ChatCompletionCreateParamsStreaming,
      { signal: controller.signal },
    )) as AsyncIterable<ChunkLike>
    const chunks = stream[Symbol.asyncIterator]()
    const first = await chunks.next()
    clearTimeout(timer)
    return (async function* () {
      try {
        if (first.done) return
        yield first.value
        for (let next = await chunks.next(); !next.done; next = await chunks.next()) yield next.value
      } finally {
        signal.removeEventListener('abort', stop)
      }
    })()
  } catch (e) {
    clearTimeout(timer)
    signal.removeEventListener('abort', stop)
    throw slow && !signal.aborted ? new SlowModelError(String(params.model), ms) : e
  }
}

export interface LlmDeps {
  config: Config
  hub: Hub
  sessions: Sessions
  bridge: Bridge
  whatsapp: WhatsApp | null
  chat: ChatFn
  clock: () => Date
}

interface Message {
  role: 'system' | 'user' | 'assistant' | 'tool'
  /** Parts only for a chat message with a photo (chat.ts). */
  content: string | null | Array<Record<string, unknown>>
  tool_calls?: PendingCall[]
  tool_call_id?: string
}

export function chatCompletions(deps: LlmDeps) {
  return async (req: Request, res: Response) => {
    if (deps.config.llmProxySecret && !sameSecret(req.headers.authorization ?? '', `Bearer ${deps.config.llmProxySecret}`)) {
      res.status(401).json({ error: { message: 'Wrong or missing bearer' } })
      return
    }
    const body = (req.body ?? {}) as { messages?: Array<Record<string, unknown>>; stream?: boolean }
    if (body.stream === false) {
      res.status(400).json({ error: { message: 'Only streaming is supported' } })
      return
    }
    const session = deps.sessions.get(String(req.query.session ?? ''))
    if (!session) {
      res.status(404).json({ error: { message: 'No such call' } })
      return
    }

    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' })
    const controller = new AbortController()
    // Agora drops the request when she talks over the reply: stop the model with it.
    res.on('close', () => { if (!res.writableEnded) controller.abort() })
    const out = new SseWriter(res, deps.config.llm.model)
    await runTurn(deps, session, body.messages ?? [], out, controller.signal)
    out.finish()
  }
}

/** One turn of the call: streams the reply into [out], running tools in between. */
export async function runTurn(deps: LlmDeps, session: Session, incoming: Array<Record<string, unknown>>, out: { text(t: string): void }, signal: AbortSignal) {
  const started = Date.now()
  const lastUser = [...incoming].reverse().find(m => m.role === 'user')
  const userText = contentText(lastUser?.content)
  const turnId = lastUser?.turn_id === undefined ? undefined : String(lastUser.turn_id)
  const messages: Message[] = [
    { role: 'system', content: buildSystemPrompt(deps.hub, session, deps.clock()) },
    ...incoming
      .filter(m => m.role === 'user' || m.role === 'assistant')
      .map(m => ({ role: m.role as 'user' | 'assistant', content: m.role === 'user' && hasImage(m.content) ? (m.content as Array<Record<string, unknown>>) : contentText(m.content) }))
      .filter(m => m.content),
  ]
  // A typed chat shows every word: no "Okay." before a tool round, as a call says to fill the quiet.
  const fills = session.context.purpose !== 'chat'
  const deviceTools = (session.context.device_tools ?? []).filter(t => t?.name && !isServerTool(t.name))
  const deviceNames = new Set(deviceTools.map(t => t.name))
  const tools = [...SERVER_TOOLS, ...deviceTools].map(asFunction)

  let spoke = false
  let firstWordMs: number | null = null
  const ran: string[] = []
  try {
    for (let round = 0; round <= MAX_ROUNDS; round++) {
      const params: Record<string, unknown> = {
        model: deps.config.llm.model,
        messages,
        tools,
        // The last round must answer in words, whatever the model would still like to call.
        tool_choice: round === MAX_ROUNDS ? 'none' : 'auto',
      }
      if (deps.config.llm.reasoningEffort) params.reasoning_effort = deps.config.llm.reasoningEffort
      const stream = await deps.chat(params, signal)
      const calls = new ToolCallAccumulator()
      let text = ''
      for await (const chunk of stream) {
        const delta = chunk.choices?.[0]?.delta
        if (delta?.content) {
          text += delta.content
          out.text(delta.content)
          spoke = true
          firstWordMs ??= Date.now() - started
        }
        if (delta?.tool_calls?.length) calls.add(delta.tool_calls)
      }
      const pending = calls.list()
      if (!pending.length) break
      if (!spoke && fills) {
        out.text(FILLERS[session.fillers++ % FILLERS.length])
        spoke = true
        firstWordMs ??= Date.now() - started
      }
      messages.push({ role: 'assistant', content: text || null, tool_calls: pending })
      for (const call of pending) {
        const args = parseArgs(call.function.arguments)
        const result = await runTool(deps, session, call.function.name, args, deviceNames, turnId, userText)
        ran.push(call.function.name)
        messages.push({ role: 'tool', tool_call_id: call.id, content: result })
      }
    }
  } catch (e) {
    if (signal.aborted) return
    console.warn(`[llm] turn failed in ${session.id}:`, (e as Error).message)
    out.text(spoke ? ' Sorry, something went wrong there.' : "Sorry, I couldn't do that just now. Could you say it again?")
  } finally {
    console.log(`[turn] ${session.id} first words ${firstWordMs ?? '-'} ms, total ${Date.now() - started} ms, tools: ${ran.join(', ') || 'none'}${signal.aborted ? ' (interrupted)' : ''}`)
  }
}

async function runTool(deps: LlmDeps, session: Session, name: string, args: Record<string, unknown>, deviceNames: Set<string>, turnId: string | undefined, userText: string): Promise<string> {
  if (isServerTool(name)) {
    const outcome = await runServerTool(name, args, { hub: deps.hub, whatsapp: deps.whatsapp, session, now: deps.clock() })
    if (outcome.action) session.actions.push(outcome.action)
    if (outcome.card) deps.bridge.push(session.id, { type: 'action', tool: name, ok: outcome.result.ok !== false, ...outcome.card })
    return JSON.stringify(outcome.result)
  }
  if (deviceNames.has(name)) {
    const { result, chip } = await deps.bridge.callDevice(session.id, { name, args, turnId, userText })
    session.actions.push(`${name}: ${chip?.title ? `${chip.title}${chip.detail ? ` (${chip.detail})` : ''}` : shorten(result, 160)}`)
    return result
  }
  return JSON.stringify({ ok: false, error: `There is no tool called ${name}.` })
}

/** A tool call as it is built from streamed pieces, and sent back with its result. */
export interface PendingCall {
  id: string
  type: 'function'
  function: { name: string; arguments: string }
  [extra: string]: unknown
}

/**
 * Puts tool calls back together from streamed deltas. OpenAI sends each call's pieces under one
 * index; Gemini's compatible endpoint sends each call whole, sometimes under the same index or
 * none, and with ids that may be missing. Fields other than id/type/function — Gemini's thought
 * signature in `extra_content` — are kept, since the next request must send them back.
 */
export class ToolCallAccumulator {
  private readonly calls: PendingCall[] = []
  private readonly byIndex = new Map<number, PendingCall>()

  add(deltas: ToolCallDelta[]) {
    for (const delta of deltas) {
      let call: PendingCall | undefined
      if (typeof delta.index === 'number') {
        call = this.byIndex.get(delta.index)
        if (call && delta.id && call.id && delta.id !== call.id) call = undefined
        if (call && delta.function?.name && isWholeJson(call.function.arguments)) call = undefined
      } else if (delta.id) {
        call = this.calls.find(c => c.id === delta.id)
      } else if (!delta.function?.name) {
        call = this.calls.at(-1)
      }
      if (!call) {
        call = { id: '', type: 'function', function: { name: '', arguments: '' } }
        this.calls.push(call)
        if (typeof delta.index === 'number') this.byIndex.set(delta.index, call)
      }
      if (delta.id && !call.id) call.id = delta.id
      if (delta.function?.name && !call.function.name) call.function.name = delta.function.name
      if (delta.function?.arguments) call.function.arguments += delta.function.arguments
      for (const [key, value] of Object.entries(delta)) {
        if (!['index', 'id', 'type', 'function'].includes(key) && value !== undefined) call[key] = value
      }
    }
  }

  list(): PendingCall[] {
    return this.calls
      .filter(c => c.function.name)
      .map((c, i) => ({ ...c, id: c.id || `call_${i}_${Date.now().toString(36)}`, function: { ...c.function, arguments: c.function.arguments || '{}' } }))
  }
}

/** Writes OpenAI chat.completion.chunk events, then [DONE]. */
class SseWriter {
  private readonly id = `chatcmpl-${Date.now().toString(36)}`
  private readonly created = Math.floor(Date.now() / 1000)
  private started = false
  private readonly res: Response
  private readonly model: string

  constructor(res: Response, model: string) {
    this.res = res
    this.model = model
  }

  text(content: string) {
    if (this.res.writableEnded) return
    this.write({ ...(this.started ? {} : { role: 'assistant' }), content })
    this.started = true
  }

  finish() {
    if (this.res.writableEnded) return
    this.write({}, 'stop')
    this.res.write('data: [DONE]\n\n')
    this.res.end()
  }

  private write(delta: Record<string, unknown>, finish: string | null = null) {
    this.res.write(`data: ${JSON.stringify({ id: this.id, object: 'chat.completion.chunk', created: this.created, model: this.model, choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`)
  }
}

function asFunction(tool: ToolDeclaration) {
  return { type: 'function', function: { name: tool.name, description: tool.description, parameters: tool.parameters } }
}

function contentText(content: unknown): string {
  if (typeof content === 'string') return content
  if (Array.isArray(content)) return content.map(p => (typeof p === 'string' ? p : (p as { text?: string })?.text ?? '')).join(' ').trim()
  return ''
}

/** OpenAI-style content parts with a picture in them. */
function hasImage(content: unknown): boolean {
  return Array.isArray(content) && content.some(p => (p as { type?: string })?.type === 'image_url')
}

function parseArgs(json: string): Record<string, unknown> {
  try {
    const parsed = JSON.parse(json || '{}')
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}

function isWholeJson(text: string): boolean {
  if (!text) return false
  try {
    JSON.parse(text)
    return true
  } catch {
    return false
  }
}

function shorten(text: string, max: number): string {
  return text.length > max ? `${text.slice(0, max)}…` : text
}

function sameSecret(given: string, expected: string): boolean {
  const a = Buffer.from(given)
  const b = Buffer.from(expected)
  return a.length === b.length && timingSafeEqual(a, b)
}

export { sameSecret }
