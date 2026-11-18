import type { Announce } from './announce.ts'
import type { Hub, Member } from './hub.ts'
import { formatTime } from './when.ts'
import { digestText } from './followups.ts'

/**
 * How Luna reaches the family and the shops: Telegram, through one bot. Each person links
 * themselves once by sending `/start <their id>` (rahul, priya, ramesh-stores…); a group gets the
 * digest after `/family` is sent in it. Answers come back as button taps and replies.
 */

export interface Button {
  text: string
  /** callback_data, at most 64 bytes: "task:3:yes", "req:2:ok", "dose:bp-am:2026-11-21:took". */
  data: string
}

/** Sending side of the bot; a fake in tests. */
export interface Messenger {
  /** Returns the message's id, for replies to it and edits. */
  send(chatId: number, html: string, buttons?: Button[][]): Promise<number>
  edit(chatId: number, messageId: number, html: string, buttons?: Button[][]): Promise<void>
  answer(callbackId: string, text?: string, alert?: boolean): Promise<void>
}

export class TelegramBot implements Messenger {
  private readonly base: string
  private polling = false

  constructor(token: string) {
    this.base = `https://api.telegram.org/bot${token}`
  }

  async send(chatId: number, html: string, buttons?: Button[][]) {
    const message = (await this.call('sendMessage', { chat_id: chatId, text: html, parse_mode: 'HTML', reply_markup: keyboard(buttons) })) as { message_id: number }
    return message.message_id
  }

  async edit(chatId: number, messageId: number, html: string, buttons?: Button[][]) {
    await this.call('editMessageText', { chat_id: chatId, message_id: messageId, text: html, parse_mode: 'HTML', reply_markup: keyboard(buttons) ?? { inline_keyboard: [] } })
  }

  async answer(callbackId: string, text?: string, alert = false) {
    await this.call('answerCallbackQuery', { callback_query_id: callbackId, text, show_alert: alert })
  }

  /** Telegram calls [url] with each update, repeating [secret] in a header. Needs public https. */
  async setWebhook(url: string, secret: string) {
    await this.call('setWebhook', { url, secret_token: secret, allowed_updates: ['message', 'callback_query'] })
  }

  /** Without a public address (on a laptop): asks Telegram for updates instead. */
  async startPolling(onUpdate: (update: TelegramUpdate) => Promise<void>) {
    await this.call('deleteWebhook', {})
    this.polling = true
    let offset = 0
    while (this.polling) {
      try {
        const updates = (await this.call('getUpdates', { offset, timeout: 30, allowed_updates: ['message', 'callback_query'] }, 40_000)) as Array<TelegramUpdate & { update_id: number }>
        for (const update of updates) {
          offset = update.update_id + 1
          await onUpdate(update).catch(e => console.warn(`[telegram] update failed: ${(e as Error).message}`))
        }
      } catch (e) {
        console.warn(`[telegram] polling: ${(e as Error).message}`)
        await new Promise(r => setTimeout(r, 5_000))
      }
    }
  }

  stopPolling() {
    this.polling = false
  }

  private async call(method: string, body: Record<string, unknown>, timeoutMs = 15_000): Promise<unknown> {
    const response = await fetch(`${this.base}/${method}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(timeoutMs),
    })
    const json = (await response.json()) as { ok: boolean; result?: unknown; description?: string }
    if (!json.ok) throw new Error(`Telegram ${method}: ${json.description}`)
    return json.result
  }
}

function keyboard(buttons?: Button[][]) {
  return buttons?.length ? { inline_keyboard: buttons.map(row => row.map(b => ({ text: b.text, callback_data: b.data }))) } : undefined
}

/** For Telegram's HTML: everything a person or the model wrote goes through this. */
export function esc(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** The parts of a Telegram update the bot reads. */
export interface TelegramUpdate {
  message?: {
    message_id: number
    chat: { id: number; type: string }
    from?: { id: number; first_name?: string }
    text?: string
    reply_to_message?: { message_id: number }
  }
  callback_query?: {
    id: string
    from: { id: number }
    data?: string
    message?: { message_id: number; chat: { id: number }; text?: string }
  }
}

export interface UpdateDeps {
  hub: Hub
  messenger: Messenger
  announce: Announce
  clock: () => Date
}

export async function handleUpdate(update: TelegramUpdate, deps: UpdateDeps) {
  if (update.callback_query) await onButton(update.callback_query, deps)
  else if (update.message?.text) await onText({ ...update.message, text: update.message.text }, deps)
}

async function onText(message: NonNullable<TelegramUpdate['message']> & { text: string }, deps: UpdateDeps) {
  const { hub, messenger, announce } = deps
  const chat = message.chat.id
  const elder = hub.elder()
  const text = message.text.trim()
  const command = text.match(/^\/(\w+)(?:@\w+)?\s*(.*)$/s)

  if (command?.[1] === 'start') {
    const code = command[2].trim()
    const member = code ? (hub.member(code.toLowerCase()) ?? hub.findMember(code)) : null
    if (!member || member.role === 'elder') {
      const codes = hub.members().filter(m => m.role !== 'elder').map(m => `/start ${m.id}`)
      await messenger.send(chat, `Hi! This is Luna, ${esc(elder.name)}'s assistant. Send the line for who you are:\n\n${codes.join('\n')}`)
      return
    }
    hub.linkTelegram(member.id, chat)
    const what = member.role === 'provider' ? `Requests from ${esc(elder.name)} will come here.` : `${esc(elder.name)}'s requests and updates will come here.`
    await messenger.send(chat, `Hi ${esc(member.name)}! You're connected to Luna. ${what}`)
    return
  }
  if (command?.[1] === 'family') {
    hub.setMeta('family_group_chat_id', String(chat))
    await messenger.send(chat, `This chat will get ${esc(elder.name)}'s family updates and the evening digest.`)
    return
  }
  if (command?.[1] === 'status') {
    await messenger.send(chat, digestText(hub, deps.clock()))
    return
  }
  if (command) return

  // A reply to a request or a task: a note for her ("delivery by 11").
  const sender = hub.memberByChat(message.from?.id ?? chat) ?? hub.memberByChat(chat)
  if (message.reply_to_message) {
    const about = hub.aboutMessage(chat, message.reply_to_message.message_id)
    if (about?.request) {
      const provider = hub.member(about.request.provider)
      hub.updateRequest(about.request.id, { note: text }, deps.clock().getTime())
      await announce('reply', `${provider?.name ?? 'The shop'} says: ${text}`, { request: about.request.id })
      await messenger.send(chat, `✅ Sent to ${esc(elder.name)}.`)
      return
    }
    if (about?.task) {
      const who = hub.member(about.task.assignee)
      await announce('reply', `${who?.name ?? 'Your family'} says: ${text}`, { task: about.task.id })
      await messenger.send(chat, `✅ Sent to ${esc(elder.name)}.`)
      return
    }
  }
  // Anything else a linked person writes to the bot directly is passed on to her.
  if (message.chat.type === 'private' && sender && sender.role !== 'elder') {
    await announce('reply', `${sender.name} says: ${text}`, { from: sender.id })
    await messenger.send(chat, `✅ Luna will tell ${esc(elder.name)}.`)
  }
}

async function onButton(query: NonNullable<TelegramUpdate['callback_query']>, deps: UpdateDeps) {
  const { hub, messenger, announce, clock } = deps
  const now = clock().getTime()
  const elder = hub.elder()
  const [kind, id, choice, extra] = (query.data ?? '').split(':')
  const message = query.message
  const original = esc(message?.text ?? '')
  const edit = async (footer: string, buttons?: Button[][]) => {
    if (message) await messenger.edit(message.chat.id, message.message_id, `${original}\n\n${footer}`, buttons).catch(() => {})
  }

  if (kind === 'task') {
    const task = hub.task(Number(id))
    if (!task) return messenger.answer(query.id, 'That request is gone.')
    const who = hub.member(task.assignee)?.name ?? 'Your family'
    const when = task.dueAt ? `, ${formatTime(new Date(task.dueAt), clock())}` : task.whenText ? `, ${task.whenText}` : ''
    const title = lowerFirst(task.title)
    if (choice === 'yes' && task.status === 'open') {
      hub.updateTask(task.id, { status: 'accepted' }, now)
      await edit(`👍 Thanks! ${esc(elder.name)} will be told. Tap Done when it's done.`, [[{ text: '✅ Done', data: `task:${task.id}:done` }]])
      await announce('task_reply', `${who} said yes: ${title}${when}.`, { task: task.id, status: 'accepted' })
    } else if (choice === 'no' && (task.status === 'open' || task.status === 'accepted')) {
      hub.updateTask(task.id, { status: 'declined' }, now)
      await edit(`🙅 Okay, ${esc(elder.name)} will be told.`)
      await announce('task_reply', `${who} can't do it: ${title}${when}. Shall I ask someone else?`, { task: task.id, status: 'declined' })
    } else if (choice === 'done' && task.status !== 'done') {
      hub.updateTask(task.id, { status: 'done' }, now)
      await edit('✅ Done. Thank you!')
      await announce('task_reply', `${who} has done it: ${title}.`, { task: task.id, status: 'done' })
    }
    return messenger.answer(query.id)
  }

  if (kind === 'req') {
    const request = hub.request(Number(id))
    if (!request) return messenger.answer(query.id, 'That request is gone.')
    const provider = hub.member(request.provider)?.name ?? 'The shop'
    if (choice === 'ok') {
      hub.updateRequest(request.id, { status: 'confirmed' }, now)
      await edit(`✅ Confirmed. ${esc(elder.name)} will be told. Reply to this message to add a note.`)
      await announce('request_reply', `${provider} confirmed: ${request.what}.`, { request: request.id, status: 'confirmed' })
    } else if (choice === 'hour') {
      hub.updateRequest(request.id, { status: 'within_hour' }, now)
      await edit(`✅ Within the hour. ${esc(elder.name)} will be told.`)
      await announce('request_reply', `${provider} confirmed and will have it done within the hour: ${request.what}.`, { request: request.id, status: 'within_hour' })
    } else if (choice === 'later') {
      hub.updateRequest(request.id, { status: 'another_time' }, now)
      await edit(`🕐 Noted. Reply to this message with a time that works, and ${esc(elder.name)} will be told.`)
      await announce('request_reply', `${provider} can't do it then: ${request.what}. They'll suggest another time.`, { request: request.id, status: 'another_time' })
    }
    return messenger.answer(query.id)
  }

  // "dose:bp-am:2026-11-21:took": someone checked, and she did take it.
  if (kind === 'dose' && choice && extra === 'took') {
    const dose = hub.dose(id)
    const by = hub.memberByChat(query.from.id)
    if (dose) {
      hub.addCheckin({ kind: 'medicine', detail: `confirmed by ${by?.name ?? 'family'}`, ok: true, doseId: dose.id, day: choice, at: now, source: 'caregiver' })
      hub.addEvent('checkin', `${by?.name ?? 'Your family'} confirmed you took your ${dose.name}.`, { dose: dose.id, day: choice }, now)
      await edit('✅ Noted. Thank you!')
    }
    return messenger.answer(query.id)
  }

  if (kind === 'call') {
    const member = hub.member(id) ?? elder
    return messenger.answer(query.id, member.phone ? `Call ${member.name}: ${member.phone}` : `No number saved for ${member.name}.`, true)
  }

  if (kind === 'ack') {
    const by = hub.memberByChat(query.from.id)
    await edit(`👍 ${esc(by?.name ?? 'Someone')} is on it. ${esc(elder.name)} will be told.`)
    await announce('reply', `${by?.name ?? 'Your family'} got your message and is on it.`, { event: Number(id) })
    return messenger.answer(query.id)
  }

  return messenger.answer(query.id)
}

function lowerFirst(text: string): string {
  return /^[A-Z][a-z]/.test(text) ? text[0].toLowerCase() + text.slice(1) : text
}

/** The members a family-wide message goes to when there's no group: everyone linked but her and the shops. */
export function familyRecipients(hub: Hub): Member[] {
  return hub.members().filter(m => m.telegramChatId !== null && (m.role === 'caregiver' || m.role === 'family'))
}
