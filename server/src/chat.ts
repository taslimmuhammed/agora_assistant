import type { Express, Request, RequestHandler, Response } from 'express'
import { Bridge, type BridgeSocket } from './bridge.ts'
import { type LlmDeps, runTurn } from './llm.ts'
import type { LiveContext } from './sessions.ts'

/**
 * Luna's typed chat, outside a call: the same brain the Agora calls use (runTurn, with the server's
 * family tools and the phone's own), written instead of spoken.
 *
 * POST /chat/turn {user_id, context, messages, image?} answers as a stream of server-sent events,
 * each `data: {json}`:
 *  - `{type: 'session', session_id}` first, for the tool results to name;
 *  - `{type: 'text', text}` as the model writes;
 *  - `{type: 'tool_call', call_id, name, args, …}` when it calls one of the phone's tools: the phone
 *    runs it and answers with POST /chat/tool_result {session_id, call_id, result, chip};
 *  - `{type: 'action', …}` for what the server did itself ("asked Rahul"), as a call's cards;
 *  - `{type: 'done'}` last.
 *
 * POST /chat/describe {image} gives a photo kept from the Images tab its title and details.
 *
 * The phone's tools go over this one stream rather than the call's WebSocket, so a chat message
 * costs a single request plus one small POST per tool.
 */

/** A chat's phone tool may wait on the user: a permission dialog, a contact picked. */
const PHONE_TOOL_MS = 45_000

/** How much of the chat's history a turn carries. */
const MAX_MESSAGES = 24

export interface ChatTurnBody {
  user_id?: string
  context?: LiveContext
  messages?: Array<{ role?: string; content?: unknown }>
  /** A photo sent with the last message, base64. */
  image?: { mime?: string; data?: string }
}

export function chatRoutes(app: Express, deps: LlmDeps, requireKey: RequestHandler) {
  // Its own bridge: a chat's phone tools may take longer than a call's 8 s.
  const bridge = new Bridge(PHONE_TOOL_MS)
  const streams = new Map<string, StreamSocket>()

  app.post('/chat/turn', requireKey, async (req: Request, res: Response) => {
    const body = (req.body ?? {}) as ChatTurnBody
    const messages = chatMessages(body)
    if (!messages.length || messages.at(-1)?.role !== 'user') {
      res.status(400).json({ error: 'Send the chat, ending with what the user said.' })
      return
    }
    const userId = String(body.user_id || deps.hub.elderId)
    const now = deps.clock()
    // Not the user's own id: a call in progress stays the one family news is pushed to.
    const session = deps.sessions.create(`chat:${userId}`, Number(deps.hub.meta(`last_call_end:${userId}`) ?? 0), now.getTime())
    session.context = { ...(body.context ?? {}), purpose: 'chat' }

    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive', 'X-Accel-Buffering': 'no' })
    const socket = new StreamSocket(res)
    streams.set(session.id, socket)
    bridge.attach(session.id, socket)
    socket.event({ type: 'session', session_id: session.id })

    const controller = new AbortController()
    // The phone stopped the reply, or lost the connection: stop the model with it.
    res.on('close', () => {
      if (!res.writableEnded) controller.abort()
      socket.closed()
    })
    const started = Date.now()
    try {
      await runTurn({ ...deps, bridge }, session, messages, { text: t => socket.event({ type: 'text', text: t }) }, controller.signal)
    } finally {
      socket.event({ type: 'done' })
      res.end()
      streams.delete(session.id)
      deps.sessions.drop(session.id)
      console.log(`[chat] ${session.id} answered in ${Date.now() - started} ms`)
    }
  })

  // A photo kept from the app's Images tab: a title and what's written on it, for memory search.
  app.post('/chat/describe', requireKey, async (req: Request, res: Response) => {
    const image = (req.body?.image ?? {}) as ChatTurnBody['image']
    if (!image?.data || !/^image\/(jpeg|png|webp)$/.test(image.mime ?? '')) {
      res.status(400).json({ error: 'Send the photo as {image: {mime, data}}.' })
      return
    }
    try {
      res.json(await describe(deps, image.mime!, image.data))
    } catch (e) {
      console.warn('[chat] describing a photo failed:', (e as Error).message)
      res.status(502).json({ error: "Luna couldn't look at the photo just now." })
    }
  })

  app.post('/chat/tool_result', requireKey, (req: Request, res: Response) => {
    const socket = streams.get(String(req.body?.session_id ?? ''))
    if (!socket || !req.body?.call_id) {
      res.status(404).json({ error: 'That reply is over.' })
      return
    }
    socket.received(JSON.stringify({ ...req.body, type: 'tool_result' }))
    res.json({ ok: true })
  })
}

const DESCRIBE = `This photo is being kept in the user's memory on their phone, to be found and read again later. Answer with JSON only, no markdown: {"title": "...", "details": "..."}.
- title: two to five words saying what it is ("Dr. Rao's prescription", "Wi-Fi password card").
- details: everything written on it worth keeping, copied exactly (names, numbers, dates, amounts, addresses), then one short line on what it shows. Plain sentences, no lists.`

/** A photo's title and details, written by the model the calls use. */
export async function describe(deps: Pick<LlmDeps, 'chat' | 'config'>, mime: string, data: string): Promise<{ title: string; details: string }> {
  const stream = await deps.chat({
    model: deps.config.llm.model,
    messages: [{ role: 'user', content: [{ type: 'text', text: DESCRIBE }, { type: 'image_url', image_url: { url: `data:${mime};base64,${data}` } }] }],
  }, AbortSignal.timeout(40_000))
  let text = ''
  for await (const chunk of stream) text += chunk.choices?.[0]?.delta?.content ?? ''
  const json = text.match(/\{[\s\S]*\}/)?.[0]
  const parsed = json ? (JSON.parse(json) as { title?: unknown; details?: unknown }) : {}
  const title = typeof parsed.title === 'string' ? parsed.title.trim() : ''
  const details = typeof parsed.details === 'string' ? parsed.details.trim() : text.trim()
  if (!title && !details) throw new Error('the model wrote nothing')
  return { title: title || 'Saved photo', details }
}

/** The chat as the model reads it: the last [MAX_MESSAGES] words, with the photo on the last one. */
export function chatMessages(body: ChatTurnBody): Array<Record<string, unknown>> {
  const said = (body.messages ?? [])
    .filter(m => (m.role === 'user' || m.role === 'assistant') && typeof m.content === 'string' && m.content.trim())
    .slice(-MAX_MESSAGES)
    .map(m => ({ role: m.role as string, content: (m.content as string).trim() }))
  // A reply that came first (a welcome-back message) has nothing to answer: the model wants a user first.
  while (said.length && said[0].role !== 'user') said.shift()
  const last = said.at(-1)
  const image = body.image
  if (last?.role === 'user' && image?.data && /^image\/(jpeg|png|webp)$/.test(image.mime ?? '')) {
    return [
      ...said.slice(0, -1),
      { role: 'user', content: [{ type: 'text', text: last.content }, { type: 'image_url', image_url: { url: `data:${image.mime};base64,${image.data}` } }] },
    ]
  }
  return said
}

/** The bridge's socket for one chat reply: what it sends goes down the event stream. */
class StreamSocket implements BridgeSocket {
  private readonly res: Response
  private readonly messageListeners: Array<(data: unknown) => void> = []
  private readonly closeListeners: Array<() => void> = []
  private isClosed = false

  constructor(res: Response) {
    this.res = res
  }

  /** One event on the stream. */
  event(message: Record<string, unknown>) {
    if (!this.res.writableEnded) this.res.write(`data: ${JSON.stringify(message)}\n\n`)
  }

  send(data: string) {
    if (!this.res.writableEnded) this.res.write(`data: ${data}\n\n`)
  }

  close() {
    this.closed()
  }

  /** The phone's answer to a tool call, as the WebSocket would have brought it. */
  received(data: string) {
    for (const listener of this.messageListeners) listener(data)
  }

  closed() {
    if (this.isClosed) return
    this.isClosed = true
    for (const listener of this.closeListeners) listener()
  }

  on(event: 'message', listener: (data: unknown) => void): unknown
  on(event: 'close', listener: () => void): unknown
  on(event: 'message' | 'close', listener: ((data: unknown) => void) | (() => void)) {
    if (event === 'message') this.messageListeners.push(listener as (data: unknown) => void)
    else this.closeListeners.push(listener as () => void)
    return this
  }
}
