import { readFileSync } from 'node:fs'
import type { WAMessage } from 'baileys'
import type { AgentApi } from '../src/agora.ts'
import { announcer } from '../src/announce.ts'
import { Bridge, type BridgeSocket } from '../src/bridge.ts'
import { type Config, loadConfig } from '../src/config.ts'
import { Hub, type Seed } from '../src/hub.ts'
import type { ChatFn, ChunkLike } from '../src/llm.ts'
import { handleEvent } from '../src/replies.ts'
import { Sessions } from '../src/sessions.ts'
import { runServerTool } from '../src/tools.ts'
import { type Understand, WhatsApp, type WhatsAppSocket, chatOf } from '../src/whatsapp.ts'

process.env.TZ = 'Asia/Kolkata'

/** The seed's family, with made-up numbers: the committed seed has none (real ones go in a local seed). */
const NUMBERS: Record<string, string> = {
  lakshmi: '+91 90000 00001', rahul: '+91 90000 00002', priya: '+91 90000 00003', 'ramesh-stores': '+91 90000 00004', 'dr-rao': '+91 90000 00005',
}
const seed = JSON.parse(readFileSync(new URL('../seed/family.json', import.meta.url), 'utf8')) as Seed
export const SEED: Seed = { ...seed, members: seed.members.map(m => ({ ...m, phone: NUMBERS[m.id] ?? m.phone })) }

/** Saturday 21 November 2026, [hours]:[minutes] in Bengaluru. */
export function at(hours: number, minutes = 0): Date {
  return new Date(2026, 10, 21, hours, minutes)
}

export function makeHub(): Hub {
  return new Hub(':memory:', SEED)
}

export function testConfig(overrides: Record<string, string> = {}): Config {
  return loadConfig({
    PUBLIC_URL: 'https://luna.example',
    APP_KEY: 'app-key',
    LLM_PROXY_SECRET: 'proxy-secret',
    AGORA_APP_ID: '0123456789abcdef0123456789abcdef',
    AGORA_APP_CERTIFICATE: 'fedcba9876543210fedcba9876543210',
    AGORA_CUSTOMER_ID: 'customer',
    AGORA_CUSTOMER_SECRET: 'secret',
    MURF_API_KEY: 'murf-key',
    ...overrides,
  })
}

/** Lakshmi's number: the account Luna sends from in the tests. */
export const ACCOUNT = '919000000001@s.whatsapp.net'
export const RAHUL = '919000000002@s.whatsapp.net'

/** WhatsApp's side: keeps what Luna sent, and knows some LIDs. */
export class FakeSocket implements WhatsAppSocket {
  readonly sent: Array<{ jid: string; text: string; quoted: string | null; id: string }> = []
  /** Luna's reactions: [emoji] on the message [on]. */
  readonly reactions: Array<{ jid: string; emoji: string; on: string }> = []
  readonly lids = new Map<string, string>()
  /** Numbers that aren't on WhatsApp. */
  readonly missing = new Set<string>()
  private next = 100

  async sendMessage(jid: string, content: Parameters<WhatsAppSocket['sendMessage']>[1], options?: Parameters<WhatsAppSocket['sendMessage']>[2]) {
    const id = `LUNA${this.next++}`
    if ('react' in content) {
      this.reactions.push({ jid, emoji: String(content.react.text), on: String(content.react.key?.id) })
      return { key: { remoteJid: jid, id, fromMe: true } } as WAMessage
    }
    this.sent.push({ jid, text: (content as { text: string }).text, quoted: options?.quoted?.key.id ?? null, id })
    return { key: { remoteJid: jid, id, fromMe: true } } as WAMessage
  }

  async pnForLid(lid: string) {
    return this.lids.get(lid) ?? null
  }

  async download() {
    return Buffer.from('voice')
  }

  async exists(jid: string) {
    return !this.missing.has(jid)
  }

  to(jid: string) {
    return this.sent.filter(m => m.jid === jid)
  }
}

/** A WhatsApp signed in as Lakshmi, on a fake socket. */
export function fakeWhatsApp(hub: Hub, clock: () => Date = () => at(9, 5), understand: Understand | null = null) {
  const socket = new FakeSocket()
  const whatsapp = new WhatsApp(hub, clock, understand)
  whatsapp.attach(socket, ACCOUNT)
  return { whatsapp, socket }
}

let incomingId = 0

/**
 * A message as Baileys hands it over, from the member with id [from] (or a raw JID), in their
 * own chat or in [group]; [quoted] is the id of the message it replies to.
 */
export function incoming(hub: Hub, from: string, text: string, options: { quoted?: string; group?: string; lid?: string; fromMe?: boolean } = {}): WAMessage {
  const jid = hub.member(from) ? chatOf(hub.member(from)!)! : from
  const sender = options.lid ?? jid
  return {
    key: {
      id: `IN${incomingId++}`,
      fromMe: options.fromMe ?? false,
      remoteJid: options.group ?? sender,
      ...(options.group ? { participant: sender } : {}),
    },
    message: options.quoted
      ? { extendedTextMessage: { text, contextInfo: { stanzaId: options.quoted } } }
      : { conversation: text },
  } as WAMessage
}

export function reaction(hub: Hub, from: string, messageId: string, emoji: string): WAMessage {
  return {
    key: { id: `IN${incomingId++}`, fromMe: false, remoteJid: chatOf(hub.member(from)!)! },
    message: { reactionMessage: { key: { id: messageId }, text: emoji } },
  } as WAMessage
}

export class FakeAgents implements AgentApi {
  readonly joins: unknown[] = []
  readonly spoken: Array<{ agentId: string; text: string }> = []
  readonly left: string[] = []

  async join(body: unknown) {
    this.joins.push(body)
    return { agent_id: `agent-${this.joins.length}`, status: 'RUNNING' }
  }

  async leave(agentId: string) {
    this.left.push(agentId)
  }

  async speak(agentId: string, text: string) {
    this.spoken.push({ agentId, text })
  }
}

/**
 * A phone on the bridge: answers each tool_call with [answer]'s result (or not at all when it
 * returns null), and keeps everything the server sent it.
 */
export class FakePhone implements BridgeSocket {
  readonly received: Array<Record<string, unknown>> = []
  private onMessage: (data: unknown) => void = () => {}
  private onClose: () => void = () => {}
  private readonly answer: (call: Record<string, unknown>) => unknown
  /** More of the tool_result than its result: the lists a list tool sends back. */
  private readonly extra: (call: Record<string, unknown>) => Record<string, unknown>

  constructor(answer: (call: Record<string, unknown>) => unknown = () => ({ ok: true }), extra: (call: Record<string, unknown>) => Record<string, unknown> = () => ({})) {
    this.answer = answer
    this.extra = extra
  }

  send(data: string) {
    const message = JSON.parse(data)
    this.received.push(message)
    if (message.type !== 'tool_call') return
    const result = this.answer(message)
    if (result === null) return
    setImmediate(() => this.onMessage(JSON.stringify({ type: 'tool_result', call_id: message.call_id, result: JSON.stringify(result), chip: null, ...this.extra(message) })))
  }

  close() {
    this.onClose()
  }

  on(event: 'message' | 'close', listener: ((data: unknown) => void) | (() => void)) {
    if (event === 'message') this.onMessage = listener as (data: unknown) => void
    else this.onClose = listener as () => void
    return this
  }
}

/**
 * A model that plays [rounds] in order, one per request: each round is the chunks it streams.
 * Every request's params are kept, to check what the model was sent.
 */
export function scriptedChat(rounds: ChunkLike[][]): ChatFn & { requests: Array<Record<string, unknown>> } {
  const requests: Array<Record<string, unknown>> = []
  const chat: ChatFn = async params => {
    requests.push(structuredClone(params))
    const chunks = rounds[requests.length - 1] ?? [text('…')]
    return (async function* () {
      for (const chunk of chunks) yield chunk
    })()
  }
  return Object.assign(chat, { requests })
}

export function text(content: string): ChunkLike {
  return { choices: [{ delta: { content } }] }
}

export function toolCall(name: string, args: Record<string, unknown>, extra: Record<string, unknown> = {}): ChunkLike {
  return { choices: [{ delta: { tool_calls: [{ index: 0, function: { name, arguments: JSON.stringify(args) }, ...extra }] } }] }
}

/** The family's side, wired as index.ts wires it: WhatsApp in and out, announcements, the tools. */
export function family(now = at(9, 5), understand: Understand | null = null) {
  const hub = makeHub()
  const sessions = new Sessions()
  const bridge = new Bridge()
  const agents = new FakeAgents()
  const clock = () => now
  const { whatsapp, socket } = fakeWhatsApp(hub, clock, understand)
  const announce = announcer({ hub, sessions, bridge, agents, clock })
  whatsapp.onEvent(event => handleEvent(event, { hub, whatsapp, announce, clock }))
  const tool = (name: string, args: Record<string, unknown>, session = sessions.create('lakshmi', 0, now.getTime()), when = now) =>
    runServerTool(name, args, { hub, whatsapp, session, now: when })
  return { hub, sessions, bridge, agents, whatsapp, socket, tool, now }
}

export function liveCall(f: ReturnType<typeof family>) {
  const session = f.sessions.create('lakshmi', 0, f.now.getTime())
  session.agentId = 'agent-1'
  const phone = new FakePhone()
  f.bridge.attach(session.id, phone)
  return { session, phone }
}
