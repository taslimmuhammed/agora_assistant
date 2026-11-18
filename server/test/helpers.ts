import { readFileSync } from 'node:fs'
import type { AgentApi } from '../src/agora.ts'
import type { BridgeSocket } from '../src/bridge.ts'
import { type Config, loadConfig } from '../src/config.ts'
import { Hub, type Seed } from '../src/hub.ts'
import type { ChatFn, ChunkLike } from '../src/llm.ts'
import type { Button, Messenger } from '../src/telegram.ts'

process.env.TZ = 'Asia/Kolkata'

export const SEED = JSON.parse(readFileSync(new URL('../seed/family.json', import.meta.url), 'utf8')) as Seed

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
    TELEGRAM_BOT_TOKEN: '123:abc',
    ...overrides,
  })
}

export class FakeMessenger implements Messenger {
  readonly sent: Array<{ chatId: number; html: string; buttons?: Button[][]; id: number }> = []
  readonly edits: Array<{ chatId: number; messageId: number; html: string; buttons?: Button[][] }> = []
  readonly answers: Array<{ id: string; text?: string }> = []
  private next = 100

  async send(chatId: number, html: string, buttons?: Button[][]) {
    const id = this.next++
    this.sent.push({ chatId, html, buttons, id })
    return id
  }

  async edit(chatId: number, messageId: number, html: string, buttons?: Button[][]) {
    this.edits.push({ chatId, messageId, html, buttons })
  }

  async answer(id: string, text?: string) {
    this.answers.push({ id, text })
  }
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

  constructor(answer: (call: Record<string, unknown>) => unknown = () => ({ ok: true })) {
    this.answer = answer
  }

  send(data: string) {
    const message = JSON.parse(data)
    this.received.push(message)
    if (message.type !== 'tool_call') return
    const result = this.answer(message)
    if (result === null) return
    setImmediate(() => this.onMessage(JSON.stringify({ type: 'tool_result', call_id: message.call_id, result: JSON.stringify(result), chip: null })))
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
