import type { Announce } from './announce.ts'
import { digestText } from './followups.ts'
import type { Hub } from './hub.ts'
import { formatTime } from './when.ts'
import type { Option, WhatsApp, WhatsAppEvent } from './whatsapp.ts'

/**
 * What the family's and the shops' answers do: a choice under one of Luna's messages ("1",
 * "haan", 👍) settles a task, an order or a missed dose and is passed on to her; anything else
 * they write to her is passed on as it is. "Passed on" is announce(): said in her call if she's
 * on one, otherwise kept for her next call.
 */

export interface ReplyDeps {
  hub: Hub
  whatsapp: WhatsApp
  announce: Announce
  clock: () => Date
}

/** After a note, "✅ Luna will tell her" is written again only this long after the last time; a ✅ on the note says it meanwhile. */
const TOLD_AGAIN_MS = 6 * 60 * 60_000

export async function handleEvent(event: WhatsAppEvent, deps: ReplyDeps) {
  if (event.kind === 'choice') {
    const settled = await onChoice(event, deps)
    // A choice already made ("1" twice) settles nothing, but what came with it is still for her.
    if (!settled && event.note) {
      await onText({ kind: 'text', text: event.note, chat: event.chat, from: event.from, fromMe: false, group: event.group, about: event.message, key: event.key }, deps)
    }
  } else if (event.kind === 'reaction') {
    const words = REACTIONS[event.emoji.replace(/[\u{1F3FB}-\u{1F3FF}\u{FE0F}]/gu, '')] ?? 'reacted to'
    await deps.announce('reply', `${event.from.name} ${words} your message${event.text ? `: "${event.text}"` : ''}.`, { from: event.from.id })
  } else {
    await onText(event, deps)
  }
}

/** How a reaction on one of her messages is said to her: emoji aren't read aloud. */
const REACTIONS: Record<string, string> = {
  '👍': 'liked', '❤': 'sent love for', '🥰': 'sent love for', '😍': 'loved', '😘': 'sent a kiss for', '🙏': 'sent folded hands for',
  '😂': 'laughed at', '🤣': 'laughed at', '😮': 'was surprised by', '😯': 'was surprised by', '😢': 'felt sad about', '😭': 'felt sad about',
  '👏': 'clapped for', '🎉': 'celebrated', '🔥': 'loved', '💯': 'fully agreed with', '👌': 'liked', '✅': 'ticked',
}

async function onText(event: Extract<WhatsAppEvent, { kind: 'text' }>, deps: ReplyDeps) {
  const { hub, whatsapp, announce } = deps
  const elder = hub.elder()
  const text = event.text.trim()
  const command = text.match(/^\/(\w+)/)
  if (command) {
    if (command[1] === 'family' && event.group) {
      hub.setMeta('family_group_chat', event.chat)
      await whatsapp.send(event.chat, `This group will get ${elder.name}'s family updates and the evening digest from Luna.`)
    } else if (command[1] === 'status') {
      await whatsapp.send(event.chat, digestText(hub, deps.clock()))
    }
    return
  }
  const sender = event.from
  if (event.fromMe || !sender || sender.role === 'elder') return

  if (!text && !event.sent) return
  // "Rahul says: …", or what came without words: "Rahul sent you a voice note."
  const said = (name: string) => event.sent ? `${name} sent you a ${event.sent}${text ? `: ${text}` : '.'}` : `${name} says: ${text}`
  const about = event.about ? hub.aboutMessage(event.chat, event.about) : null
  if (about?.request) {
    if (text) hub.updateRequest(about.request.id, { note: text }, deps.clock().getTime())
    await announce('reply', said(hub.member(about.request.provider)?.name ?? sender.name), { request: about.request.id })
  } else if (about?.task) {
    await announce('reply', said(sender.name), { task: about.task.id })
  } else if (!event.group) {
    await announce('reply', said(sender.name), { from: sender.id })
  } else {
    return // the family group's own chatter isn't for her
  }
  await acknowledge(event, deps)
}

/** A ✅ on their message; and the first time in a while, a line saying what it means. */
async function acknowledge(event: Extract<WhatsAppEvent, { kind: 'text' }>, { hub, whatsapp, clock }: ReplyDeps) {
  if (event.key) await whatsapp.react(event.chat, event.key, '✅').catch(e => console.warn(`[whatsapp] ✅ not sent: ${(e as Error).message}`))
  const now = clock().getTime()
  const last = Number(hub.meta(`wa:told:${event.chat}`) ?? 0)
  if (event.key && now - last < TOLD_AGAIN_MS) return
  hub.setMeta(`wa:told:${event.chat}`, String(now))
  await whatsapp.send(event.chat, `✅ Luna will tell ${hub.elder().name}.`)
}

/** Settles what the choice is about; false when there was nothing (left) to settle. */
async function onChoice(event: Extract<WhatsAppEvent, { kind: 'choice' }>, deps: ReplyDeps): Promise<boolean> {
  const { hub, whatsapp, clock } = deps
  const now = clock().getTime()
  const elder = hub.elder()
  const [kind, id, choice, extra] = event.data.split(':')
  const reply = (text: string, options?: Option[]) =>
    whatsapp.reply(event.chat, event.message, text, options).catch(e => console.warn(`[whatsapp] reply not sent: ${(e as Error).message}`))
  // What they wrote with the choice goes with it, in one update: "Rahul can't do it: … Rahul says: …"
  const note = event.note?.trim()
  const announce = (kind: string, text: string, item: unknown, ask = '') =>
    deps.announce(kind, [text, note ? `${event.from?.name ?? 'They'} says: ${note}${/[.!?…]$/.test(note) ? '' : '.'}` : '', ask].filter(Boolean).join(' '), item)

  if (kind === 'task') {
    const task = hub.task(Number(id))
    if (!task) {
      await reply('That request is no longer open.')
      return true
    }
    const who = hub.member(task.assignee)?.name ?? 'Your family'
    const when = task.dueAt ? `, ${formatTime(new Date(task.dueAt), clock())}` : task.whenText ? `, ${task.whenText}` : ''
    const title = lowerFirst(task.title)
    if (choice === 'yes' && task.status === 'open') {
      hub.updateTask(task.id, { status: 'accepted' }, now)
      await reply(`👍 Thanks! ${elder.name} will be told.`, [{ text: '✅ Done', data: `task:${task.id}:done` }])
      await announce('task_reply', `${who} said yes: ${title}${when}.`, { task: task.id, status: 'accepted' })
      return true
    } else if (choice === 'no' && (task.status === 'open' || task.status === 'accepted')) {
      hub.updateTask(task.id, { status: 'declined' }, now)
      await reply(`🙅 Okay, ${elder.name} will be told.`)
      await announce('task_reply', `${who} can't do it: ${title}${when}.`, { task: task.id, status: 'declined' }, 'Shall I ask someone else?')
      return true
    } else if (choice === 'done' && task.status !== 'done') {
      hub.updateTask(task.id, { status: 'done' }, now)
      await reply('✅ Done. Thank you!')
      await announce('task_reply', `${who} has done it: ${title}.`, { task: task.id, status: 'done' })
      return true
    }
    return false
  }

  if (kind === 'req') {
    const request = hub.request(Number(id))
    if (!request) {
      await reply('That request is no longer open.')
      return true
    }
    const provider = hub.member(request.provider)?.name ?? 'The shop'
    const settle = (status: Parameters<Hub['updateRequest']>[1]['status']) => hub.updateRequest(request.id, { status, ...(note ? { note } : {}) }, now)
    if (choice === 'ok' && request.status !== 'confirmed') {
      settle('confirmed')
      await reply(`✅ Confirmed. ${elder.name} will be told. Send a message here to add a note.`)
      await announce('request_reply', `${provider} confirmed: ${request.what}.`, { request: request.id, status: 'confirmed' })
      return true
    } else if (choice === 'hour' && request.status !== 'within_hour') {
      settle('within_hour')
      await reply(`✅ Within the hour. ${elder.name} will be told.`)
      await announce('request_reply', `${provider} confirmed and will have it done within the hour: ${request.what}.`, { request: request.id, status: 'within_hour' })
      return true
    } else if (choice === 'later' && request.status !== 'another_time') {
      settle('another_time')
      await reply(`🕐 Noted. Send a time that works, and ${elder.name} will be told.`)
      await announce('request_reply', `${provider} can't do it then: ${request.what}.`, { request: request.id, status: 'another_time' }, note ? '' : "They'll suggest another time.")
      return true
    }
    return false
  }

  // "dose:bp-am:2026-11-21:took": someone checked, and she did take it.
  if (kind === 'dose' && choice && extra === 'took') {
    const dose = hub.dose(id)
    if (!dose) return false
    if (hub.checkinsOn(choice).some(c => c.doseId === dose.id && c.ok)) {
      await reply('✅ Already noted. Thank you!')
      return false
    }
    const by = event.from?.name ?? 'Your family'
    hub.addCheckin({ kind: 'medicine', detail: `confirmed by ${by}`, ok: true, doseId: dose.id, day: choice, at: now, source: 'caregiver' })
    hub.addEvent('checkin', `${by} confirmed you took your ${dose.name}.`, { dose: dose.id, day: choice }, now)
    await reply('✅ Noted. Thank you!')
    return true
  }

  if (kind === 'ack') {
    const by = event.from?.name ?? 'Someone'
    await reply(`👍 ${by} is on it. ${elder.name} will be told.`)
    await announce('reply', `${event.from?.name ?? 'Your family'} got your message and is on it.`, { event: Number(id) })
    return true
  }
  return false
}

function lowerFirst(text: string): string {
  return /^[A-Z][a-z]/.test(text) ? text[0].toLowerCase() + text.slice(1) : text
}
