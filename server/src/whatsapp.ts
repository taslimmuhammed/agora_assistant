import { rmSync } from 'node:fs'
import makeWASocket, {
  type AnyMessageContent,
  Browsers,
  DisconnectReason,
  type MiscMessageGenerationOptions,
  type WAMessage,
  fetchLatestWaWebVersion,
  isJidGroup,
  isLidUser,
  jidNormalizedUser,
  normalizeMessageContent,
  useMultiFileAuthState,
} from 'baileys'
import { pino } from 'pino'
import QRCode from 'qrcode'
import type { Hub, Member } from './hub.ts'

/**
 * How Luna reaches the family and the shops: WhatsApp, signed in as her (or a demo number) with
 * Baileys, the way WhatsApp Web is. Everyone is reached at the number in the seed: nobody joins
 * anything. WhatsApp shows no buttons to accounts like this, so the choices under a message are
 * numbered, and an answer is "1", a word ("yes", "haan", "done") or a 👍 on the message.
 *
 * Only the people in the seed are listened to: the account may be someone's own WhatsApp, and
 * nothing anyone else writes to it is read or answered.
 */

/** One numbered choice under a message: what it says, and what choosing it does ("task:3:yes"). */
export interface Option {
  text: string
  data: string
}

/** Something a family member or shop sent, for replies.ts. */
export type WhatsAppEvent =
  | {
      kind: 'choice'
      /** The chosen option's data. */
      data: string
      chat: string
      from: Member | null
      /** Luna's message the choice was under (the first of its thread: the task's or order's own). */
      message: string
    }
  | {
      kind: 'text'
      text: string
      chat: string
      from: Member | null
      /** Sent from the account itself: only its commands count. */
      fromMe: boolean
      group: boolean
      /** Luna's message it answers: the one quoted, or in a person's own chat the latest. */
      about: string | null
    }

/** The part of a Baileys socket this uses; a fake in tests. */
export interface WhatsAppSocket {
  sendMessage(jid: string, content: AnyMessageContent, options?: MiscMessageGenerationOptions): Promise<WAMessage | undefined>
  /** The phone-number JID behind a LID, when WhatsApp names someone by their LID. */
  pnForLid(lid: string): Promise<string | null>
}

/** What Luna keeps of each message she sends: the choices to read answers against. */
interface Sent {
  chat: string
  text: string
  options: Option[]
  /** The first message of its thread: replies under it point back to it. */
  root: string
  at: number
}

/** A choice to "1" or "yes" is only read against Luna's latest message for this long. */
const CHOICES_LAST_MS = 12 * 60 * 60_000

export class WhatsApp {
  /** The code to scan while signed out; null once signed in. */
  qr: string | null = null
  /** The JID it's signed in as. */
  account: string | null = null
  private readonly hub: Hub
  private readonly clock: () => Date
  private socket: WhatsAppSocket | null = null
  private handler: (event: WhatsAppEvent) => Promise<void> = async () => {}

  constructor(hub: Hub, clock: () => Date = () => new Date()) {
    this.hub = hub
    this.clock = clock
  }

  get connected(): boolean {
    return this.socket !== null
  }

  attach(socket: WhatsAppSocket, account: string) {
    this.socket = socket
    this.account = account
    this.qr = null
  }

  detach() {
    this.socket = null
  }

  onEvent(handler: (event: WhatsAppEvent) => Promise<void>) {
    this.handler = handler
  }

  /** Sends [text] (WhatsApp's *bold* and _italic_) with numbered [options]; returns its id. */
  send(chat: string, text: string, options: Option[] = []): Promise<string> {
    return this.post(chat, text, options, null)
  }

  /** Answers under Luna's message [to], quoting it. */
  reply(chat: string, to: string, text: string, options: Option[] = []): Promise<string> {
    return this.post(chat, text, options, to)
  }

  /** The text of a message Luna sent, for WhatsApp's retries. */
  sentText(id: string): string | null {
    return this.sent(id)?.text ?? null
  }

  private async post(chat: string, text: string, options: Option[], to: string | null): Promise<string> {
    if (!this.socket) throw new Error("WhatsApp isn't signed in on the server")
    const body = options.length ? `${text}\n\n${renderOptions(options)}` : text
    const quoted = to ? this.sent(to) : null
    const sent = await this.socket.sendMessage(chat, { text: body }, quoted && to
      ? { quoted: { key: { remoteJid: chat, id: to, fromMe: true }, message: { conversation: quoted.text } } }
      : undefined)
    const id = sent?.key.id
    if (!id) throw new Error('WhatsApp gave no id for the message')
    const root = quoted?.root ?? to ?? id
    this.hub.setMeta(`wa:msg:${id}`, JSON.stringify({ chat, text: body, options, root, at: this.clock().getTime() } satisfies Sent))
    this.hub.setMeta(`wa:recent:${chat}`, id)
    if (options.length) {
      this.hub.setMeta(`wa:choices:${chat}`, id)
    } else {
      // Answered without new choices: the old ones are spent, so a stray "1" doesn't reach them.
      const choices = this.sent(this.hub.meta(`wa:choices:${chat}`))
      if (choices?.root === root) this.hub.deleteMeta(`wa:choices:${chat}`)
    }
    return id
  }

  private sent(id: string | null | undefined): Sent | null {
    const json = id ? this.hub.meta(`wa:msg:${id}`) : null
    return json ? (JSON.parse(json) as Sent) : null
  }

  /** A message as Baileys hands it over: turned into an event for replies.ts, or dropped. */
  async receive(m: WAMessage) {
    const remote = m.key.remoteJid
    if (!remote || !m.message || !/@(s\.whatsapp\.net|lid|g\.us)$/.test(remote)) return
    const content = normalizeMessageContent(m.message)
    if (!content) return
    const group = !!isJidGroup(remote)
    const fromMe = !!m.key.fromMe
    const person = fromMe ? null : await this.personAt(group ? (m.key.participantAlt ?? m.key.participant) : (m.key.remoteJidAlt ?? remote))
    if (!fromMe && !person) return
    const chat = group ? remote : (person?.jid ?? remote)
    const from = person?.member ?? null

    const reaction = content.reactionMessage
    if (reaction) {
      const on = this.sent(reaction.key?.id)
      const option = !fromMe && on && reaction.text ? optionForEmoji(reaction.text, on.options) : null
      if (on && option) await this.handler({ kind: 'choice', data: option.data, chat, from, message: on.root })
      return
    }

    const text = (content.conversation ?? content.extendedTextMessage?.text ?? '').trim()
    if (!text) return
    if (fromMe) {
      // What she (or whoever holds the phone) writes is hers; only a command in a group, like
      // /family in the family group, is for Luna.
      if (group && text.startsWith('/')) await this.handler({ kind: 'text', text, chat, from: null, fromMe, group, about: null })
      return
    }

    const quoted = this.sent(content.extendedTextMessage?.contextInfo?.stanzaId)
    const latest = this.sent(this.hub.meta(`wa:choices:${chat}`))
    const target = quoted ? (quoted.options.length ? quoted : null) : latest && this.clock().getTime() - latest.at < CHOICES_LAST_MS ? latest : null
    const picked = target ? pickOption(text, target.options) : null
    if (target && picked) {
      await this.handler({ kind: 'choice', data: picked.option.data, chat, from, message: target.root })
      // "1, I'll be there by 4": the choice, and the rest for her too.
      if (picked.rest.split(/\s+/).filter(Boolean).length > 3) await this.handler({ kind: 'text', text, chat, from, fromMe, group, about: target.root })
      return
    }
    // A number with nothing (left) to choose is no message for her.
    if (/^[1-9][.)!]?$/.test(text)) return
    // In a group only a quoted reply is about something; in a person's own chat, the latest.
    const about = quoted?.root ?? (group ? null : this.sent(this.hub.meta(`wa:recent:${chat}`))?.root ?? null)
    await this.handler({ kind: 'text', text, chat, from, fromMe, group, about })
  }

  /** The member writing from [jid], by the number in the seed; null for anyone else. */
  private async personAt(jid: string | null | undefined): Promise<{ jid: string; member: Member } | null> {
    if (!jid) return null
    const pn = jidNormalizedUser(isLidUser(jid) ? ((await this.socket?.pnForLid(jid)) ?? jid) : jid)
    const member = this.hub.members().find(m => chatOf(m) === pn)
    return member ? { jid: pn, member } : null
  }
}

/** Where [member] is on WhatsApp: their number from the seed, as a JID. */
export function chatOf(member: Pick<Member, 'phone'>): string | null {
  let digits = (member.phone ?? '').replace(/\D/g, '').replace(/^0+/, '')
  if (digits.length === 10) digits = `91${digits}` // an Indian number written without +91
  return digits.length >= 8 ? `${digits}@s.whatsapp.net` : null
}

export function renderOptions(options: Option[]): string {
  return ['Reply with a number:', ...options.map((o, i) => `*${i + 1}*  ${o.text}`)].join('\n')
}

/** What each kind of choice can be answered with, besides its number. */
const SAYS: Record<string, RegExp> = {
  yes: words("yes|yeah|yep|yup|ya|haan|han|ha|ji|ok|okay|sure|will do|i'll do it|i will|of course|no problem|no worries|👍|👌"),
  no: words("no|nope|nahi|nahin|na|can't|cant|cannot|not possible|busy|sorry|🙅|👎"),
  done: words('done|ho gaya|hogaya|did it|finished|completed|✅'),
  ok: words('ok|okay|confirm|confirmed|yes|haan|ha|sure|done|👍|✅'),
  hour: words('within the hour|within an hour|in an hour|1 hour|one hour|ek ghante|⏱'),
  later: words("later|another time|not now|not today|tomorrow|kal|no|nahi|can't|cannot"),
  took: words('took|taken|she took|she has|she has taken|yes|haan|ha|ok|✅|👍'),
  ack: words("on it|i'm on it|ok|okay|coming|yes|haan|sure|👍"),
}

function words(alternatives: string): RegExp {
  return new RegExp(`^(${alternatives})(?=$|[\\s.,!?])`, 'u')
}

/** "task:3:yes" → yes; "dose:bp-am:2026-11-21:took" → took; "ack:5" → ack. */
function token(data: string): string {
  return data.startsWith('ack:') ? 'ack' : (data.split(':').at(-1) ?? '')
}

/**
 * The option [text] chooses, and what follows it: "1", "1, see you at 4", "haan", "can't".
 * Longer messages that don't start with a number are notes, not choices: "no" in "no, Priya will
 * take her" isn't for Luna to guess at.
 */
export function pickOption(text: string, options: Option[]): { option: Option; rest: string } | null {
  const said = plain(text)
  const number = said.match(/^([1-9])(?=$|[\s.,)!-])[\s.,)!-]*(.*)$/su)
  if (number && !/^(h|hr|hrs|hour|hours|ghant|min|baje|pm|am)\b/.test(number[2])) {
    const option = options[Number(number[1]) - 1]
    return option ? { option, rest: number[2] } : null
  }
  if (said.split(/\s+/).length > 4) return null
  for (const option of options) {
    const hit = SAYS[token(option.data)]?.exec(said)
    if (hit) return { option, rest: said.slice(hit[0].length).trim() }
  }
  return null
}

/** 👍 on a message picks its yes; 👎 its no. */
export function optionForEmoji(emoji: string, options: Option[]): Option | null {
  const e = plain(emoji)
  if (/^(👍|👌|✅|🙏|❤|💯|🆗)$/u.test(e)) return options.find(o => ['yes', 'ok', 'took', 'ack', 'done'].includes(token(o.data))) ?? null
  if (/^(👎|❌|🙅|✖)/u.test(e)) return options.find(o => ['no', 'later'].includes(token(o.data))) ?? null
  return null
}

function plain(text: string): string {
  return text.toLowerCase().replace(/[’`]/g, "'").replace(/[\u{1F3FB}-\u{1F3FF}\u{FE0F}\u{200D}\u{2640}\u{2642}]/gu, '').trim()
}

/**
 * Signs in to WhatsApp and stays signed in: the first time, a QR code to scan with the phone Luna
 * sends from (WhatsApp → Linked devices → Link a device), shown here and at /whatsapp/qr; after
 * that the session in [authDir]. Reconnects when the connection drops.
 */
export async function connectWhatsApp(wa: WhatsApp, hub: Hub, authDir: string): Promise<void> {
  const latest = await fetchLatestWaWebVersion().catch(() => null)
  const { state, saveCreds } = await useMultiFileAuthState(authDir)
  const sock = makeWASocket({
    auth: state,
    ...(latest ? { version: latest.version } : {}),
    logger: pino({ level: 'error' }),
    browser: Browsers.macOS('Luna'),
    // The phone keeps its notifications, and no chat history is pulled in.
    markOnlineOnConnect: false,
    syncFullHistory: false,
    getMessage: async key => {
      const text = key.id ? wa.sentText(key.id) : null
      return text ? { conversation: text } : undefined
    },
  })
  sock.ev.on('creds.update', saveCreds)
  sock.ev.on('connection.update', update => {
    if (update.qr) {
      wa.qr = update.qr
      QRCode.toString(update.qr, { type: 'terminal', small: true })
        .then(code => console.log(`\nWhatsApp: scan this with the phone Luna sends from (Linked devices → Link a device), or open /whatsapp/qr\n${code}`))
        .catch(() => {})
    }
    if (update.connection === 'open') {
      const account = jidNormalizedUser(sock.user?.id)
      wa.attach({
        sendMessage: (jid, content, options) => sock.sendMessage(jid, content, options),
        pnForLid: lid => sock.signalRepository.lidMapping.getPNForLID(lid),
      }, account)
      console.log(`WhatsApp: signed in as +${account.split('@')[0]}`)
      void checkFamily(hub, account, numbers => sock.onWhatsApp(...numbers))
    }
    if (update.connection === 'close') {
      wa.detach()
      const code = (update.lastDisconnect?.error as { output?: { statusCode?: number } } | undefined)?.output?.statusCode
      if (code === DisconnectReason.connectionReplaced) {
        console.warn('WhatsApp: another copy of this server signed in with the same session; this one stops.')
        return
      }
      if (code === DisconnectReason.forbidden) {
        console.warn('WhatsApp: the account was refused (403). Sign in with another number.')
        return
      }
      if (code === DisconnectReason.loggedOut) {
        console.warn('WhatsApp: signed out from the phone. A new QR code follows.')
        rmSync(authDir, { recursive: true, force: true })
      } else if (code !== DisconnectReason.restartRequired) {
        console.warn(`WhatsApp: connection closed (${code ?? 'no code'}), reconnecting`)
      }
      setTimeout(() => {
        connectWhatsApp(wa, hub, authDir).catch(e => console.warn(`WhatsApp: ${(e as Error).message}`))
      }, code === DisconnectReason.restartRequired ? 0 : 3_000)
    }
  })
  sock.ev.on('messages.upsert', ({ messages, type }) => {
    if (type !== 'notify') return
    for (const m of messages) wa.receive(m).catch(e => console.warn(`[whatsapp] message not handled: ${(e as Error).message}`))
  })
}

/** Says at startup who can't be reached, before the demo finds out. */
async function checkFamily(hub: Hub, account: string, onWhatsApp: (numbers: string[]) => Promise<Array<{ jid: string; exists: boolean }> | undefined>) {
  const people = hub.members().filter(m => m.role !== 'elder')
  for (const m of people.filter(m => !chatOf(m))) console.warn(`WhatsApp: no number for ${m.name} in the seed`)
  for (const m of people.filter(m => chatOf(m) === account)) {
    console.warn(`WhatsApp: ${m.name} has the number Luna sends from: their messages would go to "Message yourself" and their answers would be ignored`)
  }
  const reachable = people.filter(m => chatOf(m) && chatOf(m) !== account)
  try {
    const found = new Set((await onWhatsApp(reachable.map(m => chatOf(m)!))) ?? [])
    const on = new Set([...found].filter(r => r.exists).map(r => jidNormalizedUser(r.jid)))
    const missing = reachable.filter(m => !on.has(chatOf(m)!))
    if (missing.length) console.warn(`WhatsApp: not on WhatsApp: ${missing.map(m => `${m.name} (${m.phone})`).join(', ')}`)
    else console.log(`WhatsApp: ${reachable.map(m => m.name).join(', ')} can be reached`)
  } catch (e) {
    console.warn(`WhatsApp: couldn't check the family's numbers: ${(e as Error).message}`)
  }
}
