import { randomUUID } from 'node:crypto'
import type { ListContent } from './sessions.ts'

/** What the bridge needs of a socket: ws's WebSocket, or a fake in tests. */
export interface BridgeSocket {
  send(data: string): void
  close(): void
  on(event: 'message', listener: (data: unknown) => void): unknown
  on(event: 'close', listener: () => void): unknown
}

export interface DeviceResult {
  /** The JSON the phone's ToolExecutor answered, as the model will read it. */
  result: string
  chip: { title?: string; detail?: string } | null
  /** Her lists as they are now, when the tool changed one: the call's prompt takes them. */
  lists?: ListContent[]
  /** A photo the tool found to answer from ("what's my PAN number?"): the model is shown it (llm.ts). */
  image?: ToolImage
}

/** A photo as the phone sends it: a base64 JPEG. */
export interface ToolImage { mime: string; data: string }

/**
 * The phone's end of a call (WebSocket /bridge?session=…), open while the call is. Tools that act
 * on the phone — reminders, lists, alarms, health, photos — are sent to it to run with its own
 * ToolExecutor, and the server waits for the answer. Server-side actions and family replies are
 * pushed the same way, so the call screen can show them.
 */
export class Bridge {
  private readonly sockets = new Map<string, BridgeSocket>()
  private readonly pending = new Map<string, { session: string; resolve: (r: DeviceResult) => void; timer: NodeJS.Timeout }>()

  /** How long a phone tool may take: past it the model is told the phone didn't answer. */
  readonly timeoutMs: number

  constructor(timeoutMs = 8_000) {
    this.timeoutMs = timeoutMs
  }

  attach(session: string, socket: BridgeSocket) {
    const previous = this.sockets.get(session)
    if (previous && previous !== socket) previous.close()
    this.sockets.set(session, socket)
    socket.on('message', data => this.received(String(data)))
    socket.on('close', () => {
      if (this.sockets.get(session) !== socket) return
      this.sockets.delete(session)
      for (const [id, call] of this.pending) {
        if (call.session === session) this.settle(id, failed('the phone disconnected before answering'))
      }
    })
  }

  connected(session: string): boolean {
    return this.sockets.has(session)
  }

  /** Sends [message] to the phone in [session]; false when it isn't connected. */
  push(session: string, message: Record<string, unknown>): boolean {
    const socket = this.sockets.get(session)
    if (!socket) return false
    socket.send(JSON.stringify(message))
    return true
  }

  /** Runs one of the phone's tools and waits for its result. Never throws: failures are results. */
  callDevice(session: string, call: { name: string; args: Record<string, unknown>; turnId?: string; userText?: string }): Promise<DeviceResult> {
    const callId = randomUUID()
    return new Promise(resolve => {
      const timer = setTimeout(() => this.settle(callId, failed("the phone didn't answer in time")), this.timeoutMs)
      this.pending.set(callId, { session, resolve, timer })
      const sent = this.push(session, {
        type: 'tool_call',
        call_id: callId,
        name: call.name,
        args: call.args,
        turn_id: call.turnId ?? null,
        user_text: call.userText ?? '',
      })
      if (!sent) this.settle(callId, failed("the phone isn't connected"))
    })
  }

  private received(text: string) {
    let message: { type?: string; call_id?: string; result?: unknown; chip?: DeviceResult['chip']; lists?: unknown; image?: unknown }
    try {
      message = JSON.parse(text)
    } catch {
      return
    }
    if (message.type !== 'tool_result' || !message.call_id) return
    const result = typeof message.result === 'string' ? message.result : JSON.stringify(message.result ?? { ok: false, error: 'no result' })
    this.settle(message.call_id, {
      result,
      chip: message.chip ?? null,
      ...(isLists(message.lists) ? { lists: message.lists } : {}),
      ...(isImage(message.image) ? { image: message.image } : {}),
    })
  }

  private settle(callId: string, result: DeviceResult) {
    const call = this.pending.get(callId)
    if (!call) return
    this.pending.delete(callId)
    clearTimeout(call.timer)
    call.resolve(result)
  }
}

function isLists(value: unknown): value is ListContent[] {
  return Array.isArray(value) && value.every(l => typeof l?.title === 'string' && Array.isArray(l?.items))
}

function isImage(value: unknown): value is ToolImage {
  const image = value as Partial<ToolImage> | null | undefined
  return typeof image?.mime === 'string' && image.mime.startsWith('image/') && typeof image.data === 'string' && image.data.length > 0
}

function failed(error: string): DeviceResult {
  return { result: JSON.stringify({ ok: false, error }), chip: null }
}
