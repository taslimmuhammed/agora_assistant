import { randomBytes } from 'node:crypto'

/** A tool the phone declared, as ToolCatalog writes it: `{name, description, parameters}`. */
export interface ToolDeclaration {
  name: string
  description: string
  parameters: Record<string, unknown>
}

/**
 * What the phone knows that the call needs, sent once as it starts (POST /session/start): its
 * memory, lists and tools. The phone stays where the elder's own data lives; this is the part
 * it chooses to share for one call.
 */
export interface LiveContext {
  user_name?: string
  locale?: string
  time_zone?: string
  now?: string
  /** "user" when she called, "checkin" when Luna rang her for a dose. */
  purpose?: 'user' | 'checkin'
  dose_id?: string
  /** On the lock screen: the phone sends only the tools allowed there, and no memory. */
  locked?: boolean
  /** The phone's prompt prefix: core facts, last summary, agenda, notes index. */
  memory?: string
  lists?: Array<{ title: string; items: Array<{ text: string; done: boolean }> }>
  health?: string | null
  tool_rules?: string
  device_tools?: ToolDeclaration[]
}

/** One call, from its token to the agent leaving. */
export interface Session {
  id: string
  userId: string
  channel: string
  uid: number
  agentUid: number
  agentId: string | null
  context: LiveContext
  createdAt: number
  /** When this user's previous call ended: what happened since goes in the prompt. */
  previousCallEnd: number
  /** One line per thing done in this call, for the prompt: Agora's history keeps only words. */
  actions: string[]
  ended: boolean
  /** Rotates the short "Okay." said before a tool round with nothing else to say. */
  fillers: number
}

export const USER_UID = 1001
export const AGENT_UID = 2001

export class Sessions {
  private readonly byId = new Map<string, Session>()

  create(userId: string, previousCallEnd: number, now: number): Session {
    const id = randomBytes(9).toString('base64url')
    const session: Session = {
      id,
      userId,
      channel: `luna-${userId}-${id.slice(0, 6)}`,
      uid: USER_UID,
      agentUid: AGENT_UID,
      agentId: null,
      context: {},
      createdAt: now,
      previousCallEnd,
      actions: [],
      ended: false,
      fillers: 0,
    }
    this.byId.set(id, session)
    return session
  }

  get(id: string | undefined | null): Session | null {
    return (id && this.byId.get(id)) || null
  }

  /** The user's call in progress, if any: the latest one that hasn't ended. */
  activeFor(userId: string): Session | null {
    let latest: Session | null = null
    for (const s of this.byId.values()) {
      if (s.userId === userId && !s.ended && (!latest || s.createdAt > latest.createdAt)) latest = s
    }
    return latest
  }

  end(id: string) {
    const session = this.byId.get(id)
    if (session) session.ended = true
  }
}
