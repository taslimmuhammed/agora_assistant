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

export async function handleEvent(event: WhatsAppEvent, deps: ReplyDeps) {
  if (event.kind === 'choice') await onChoice(event, deps)
  else await onText(event, deps)
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

  const about = event.about ? hub.aboutMessage(event.chat, event.about) : null
  if (about?.request) {
    hub.updateRequest(about.request.id, { note: text }, deps.clock().getTime())
    await announce('reply', `${hub.member(about.request.provider)?.name ?? sender.name} says: ${text}`, { request: about.request.id })
  } else if (about?.task) {
    await announce('reply', `${sender.name} says: ${text}`, { task: about.task.id })
  } else if (!event.group) {
    await announce('reply', `${sender.name} says: ${text}`, { from: sender.id })
  } else {
    return // the family group's own chatter isn't for her
  }
  await whatsapp.send(event.chat, `✅ Luna will tell ${elder.name}.`)
}

async function onChoice(event: Extract<WhatsAppEvent, { kind: 'choice' }>, deps: ReplyDeps) {
  const { hub, whatsapp, announce, clock } = deps
  const now = clock().getTime()
  const elder = hub.elder()
  const [kind, id, choice, extra] = event.data.split(':')
  const reply = (text: string, options?: Option[]) =>
    whatsapp.reply(event.chat, event.message, text, options).catch(e => console.warn(`[whatsapp] reply not sent: ${(e as Error).message}`))

  if (kind === 'task') {
    const task = hub.task(Number(id))
    if (!task) return void (await reply('That request is no longer open.'))
    const who = hub.member(task.assignee)?.name ?? 'Your family'
    const when = task.dueAt ? `, ${formatTime(new Date(task.dueAt), clock())}` : task.whenText ? `, ${task.whenText}` : ''
    const title = lowerFirst(task.title)
    if (choice === 'yes' && task.status === 'open') {
      hub.updateTask(task.id, { status: 'accepted' }, now)
      await reply(`👍 Thanks! ${elder.name} will be told.`, [{ text: '✅ Done', data: `task:${task.id}:done` }])
      await announce('task_reply', `${who} said yes: ${title}${when}.`, { task: task.id, status: 'accepted' })
    } else if (choice === 'no' && (task.status === 'open' || task.status === 'accepted')) {
      hub.updateTask(task.id, { status: 'declined' }, now)
      await reply(`🙅 Okay, ${elder.name} will be told.`)
      await announce('task_reply', `${who} can't do it: ${title}${when}. Shall I ask someone else?`, { task: task.id, status: 'declined' })
    } else if (choice === 'done' && task.status !== 'done') {
      hub.updateTask(task.id, { status: 'done' }, now)
      await reply('✅ Done. Thank you!')
      await announce('task_reply', `${who} has done it: ${title}.`, { task: task.id, status: 'done' })
    }
    return
  }

  if (kind === 'req') {
    const request = hub.request(Number(id))
    if (!request) return void (await reply('That request is no longer open.'))
    const provider = hub.member(request.provider)?.name ?? 'The shop'
    if (choice === 'ok' && request.status !== 'confirmed') {
      hub.updateRequest(request.id, { status: 'confirmed' }, now)
      await reply(`✅ Confirmed. ${elder.name} will be told. Send a message here to add a note.`)
      await announce('request_reply', `${provider} confirmed: ${request.what}.`, { request: request.id, status: 'confirmed' })
    } else if (choice === 'hour' && request.status !== 'within_hour') {
      hub.updateRequest(request.id, { status: 'within_hour' }, now)
      await reply(`✅ Within the hour. ${elder.name} will be told.`)
      await announce('request_reply', `${provider} confirmed and will have it done within the hour: ${request.what}.`, { request: request.id, status: 'within_hour' })
    } else if (choice === 'later' && request.status !== 'another_time') {
      hub.updateRequest(request.id, { status: 'another_time' }, now)
      await reply(`🕐 Noted. Send a time that works, and ${elder.name} will be told.`)
      await announce('request_reply', `${provider} can't do it then: ${request.what}. They'll suggest another time.`, { request: request.id, status: 'another_time' })
    }
    return
  }

  // "dose:bp-am:2026-11-21:took": someone checked, and she did take it.
  if (kind === 'dose' && choice && extra === 'took') {
    const dose = hub.dose(id)
    if (!dose) return
    if (hub.checkinsOn(choice).some(c => c.doseId === dose.id && c.ok)) return void (await reply('✅ Already noted. Thank you!'))
    const by = event.from?.name ?? 'Your family'
    hub.addCheckin({ kind: 'medicine', detail: `confirmed by ${by}`, ok: true, doseId: dose.id, day: choice, at: now, source: 'caregiver' })
    hub.addEvent('checkin', `${by} confirmed you took your ${dose.name}.`, { dose: dose.id, day: choice }, now)
    await reply('✅ Noted. Thank you!')
    return
  }

  if (kind === 'ack') {
    const by = event.from?.name ?? 'Someone'
    await reply(`👍 ${by} is on it. ${elder.name} will be told.`)
    await announce('reply', `${event.from?.name ?? 'Your family'} got your message and is on it.`, { event: Number(id) })
  }
}

function lowerFirst(text: string): string {
  return /^[A-Z][a-z]/.test(text) ? text[0].toLowerCase() + text.slice(1) : text
}
