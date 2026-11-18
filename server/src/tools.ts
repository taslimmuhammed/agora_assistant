import type { Hub, Member } from './hub.ts'
import type { Session, ToolDeclaration } from './sessions.ts'
import { type Button, type Messenger, esc, familyRecipients } from './telegram.ts'
import { dayKey, formatTime, resolveWhen } from './when.ts'

/**
 * The tools that reach beyond her phone: asking family to do something, telling them things,
 * requests to shops and clinics, and check-ins the family can see. The phone's own tools
 * (reminders, lists, alarms, health…) come with each call and run on the phone (bridge.ts).
 *
 * Declared the way the phone's ToolCatalog declares its own: flat string arguments, guidance in
 * the description, so the two sets read alike to the model.
 */
export const SERVER_TOOLS: ToolDeclaration[] = [
  declare('assign_task', "Asks a family member to do something for her (\"ask Rahul to take me to the doctor\"). They get it on Telegram and answer; she is told. Not for shops or services: use request_service.", {
    assignee: 'string', task: 'string', when: 'string',
  }, ['assignee', 'task'], "task: what to do, as an instruction to them (\"Take Amma to Dr. Rao's clinic\"). when: her own words for when (\"at 4:30\", \"kal subah\"), never a worked-out date."),
  declare('message_family', "Tells someone in her family something, or everyone (\"tell Priya I reached home\", \"let everyone know\"). urgent=true for anything about her safety or health: it also goes to her caregiver at once.", {
    to: 'string', text: 'string', urgent: 'boolean',
  }, ['to', 'text'], 'to: a name, a relation, or "everyone". text: the message, written for them to read.'),
  declare('request_service', 'Sends an order or a booking to a shop, clinic or service she uses ("send my list to Ramesh Stores", "book Dr. Rao for tomorrow"). They confirm on Telegram; she is told.', {
    provider: 'string', what: 'string', when: 'string',
  }, ['provider', 'what'], 'what: the whole order or request, with every item. when: her own words for when.'),
  declare('log_checkin', 'Records what she says about her medicine, meals, mood or a walk, so her family can see it ("I took my tablet", "not yet", "I had lunch"). Use it every time she says whether she took a medicine.', {
    kind: 'string', ok: 'boolean', detail: 'string',
  }, ['kind'], 'kind: medicine, meal, mood or walk. ok: false when she hasn\'t (yet). detail: anything she added.'),
  declare('family_status', 'What her family is doing for her and what is still pending: tasks asked of them and their answers, requests to shops and clinics, and her medicines today.', {
    who: 'string',
  }, [], 'who: one person, or leave empty for everyone.'),
]

const NAMES = new Set(SERVER_TOOLS.map(t => t.name))

export function isServerTool(name: string): boolean {
  return NAMES.has(name)
}

export interface ToolDeps {
  hub: Hub
  messenger: Messenger | null
  session: Session
  now: Date
}

/** A tool's answer, a card for the call screen, and the line the prompt keeps of it. */
export interface ToolOutcome {
  result: Record<string, unknown>
  card?: { title: string; detail: string }
  action?: string
}

/** What a tool can refuse with: said to the model as `{ok:false,error}` so it tells her. */
class ToolError extends Error {}

export async function runServerTool(name: string, args: Record<string, unknown>, deps: ToolDeps): Promise<ToolOutcome> {
  try {
    switch (name) {
      case 'assign_task': return await assignTask(args, deps)
      case 'message_family': return await messageFamily(args, deps)
      case 'request_service': return await requestService(args, deps)
      case 'log_checkin': return await logCheckin(args, deps)
      case 'family_status': return familyStatus(args, deps)
      default: throw new ToolError(`No tool called ${name}`)
    }
  } catch (e) {
    if (!(e instanceof ToolError)) console.warn(`[tools] ${name} failed:`, e)
    return { result: { ok: false, error: e instanceof ToolError ? e.message : 'it went wrong on the server; try once more' } }
  }
}

async function assignTask(args: Record<string, unknown>, { hub, messenger, now }: ToolDeps): Promise<ToolOutcome> {
  const title = text(args.task)
  if (!title) throw new ToolError('Say what they should do.')
  const who = hub.findMember(text(args.assignee), ['caregiver', 'family'])
  if (!who) {
    const provider = hub.findMember(text(args.assignee), ['provider'])
    if (provider) throw new ToolError(`${provider.name} is a service: use request_service for it.`)
    throw new ToolError(`No one called "${text(args.assignee)}" in her family. Her family: ${familyList(hub)}.`)
  }
  const when = resolveWhen(text(args.when), now)
  const dueAt = when.at?.getTime() ?? null
  // Asked again if there's no answer in an hour, or 15 minutes before it's due if that's sooner;
  // never sooner than 10 minutes from now.
  const hour = now.getTime() + 60 * 60_000
  const followUpAt = Math.max(now.getTime() + 10 * 60_000, dueAt ? Math.min(hour, dueAt - 15 * 60_000) : hour)
  const task = hub.addTask({ title, assignee: who.id, whenText: when.label || text(args.when), dueAt, followUpAt }, now.getTime())
  const elder = hub.elder()
  const html = `🙏 <b>${esc(elder.name)}</b> asked you, through Luna:\n\n<b>${esc(title)}</b>${when.label ? `\n🕓 ${esc(when.label)}` : ''}`
  const sent = await deliver(hub, messenger, who, html, [[{ text: "👍 I'll do it", data: `task:${task.id}:yes` }, { text: "🙅 Can't", data: `task:${task.id}:no` }]])
  hub.updateTask(task.id, { telegramChatId: sent.chatId, telegramMessageId: sent.messageId }, now.getTime())
  hub.addEvent('task', `You asked ${who.name}: ${title}${when.label ? `, ${when.label}` : ''}.`, { task: task.id }, now.getTime())
  return {
    result: { ok: true, task: task.id, asked: who.name, when: when.label || undefined, next: `${who.name} answers on Telegram; she'll be told when they do.` },
    card: { title: `Asked ${who.name}`, detail: `${title}${when.label ? ` · ${when.label}` : ''}` },
    action: `assign_task: asked ${who.name} to "${title}"${when.label ? ` (${when.label})` : ''}, task ${task.id}, waiting for an answer`,
  }
}

async function messageFamily(args: Record<string, unknown>, { hub, messenger, now }: ToolDeps): Promise<ToolOutcome> {
  const body = text(args.text)
  if (!body) throw new ToolError('Say what the message is.')
  const urgent = truthy(args.urgent)
  const elder = hub.elder()
  const to = text(args.to)
  const everyone = !to || /\b(every|everyone|everybody|all|family|sab|sabko)\b/i.test(to)
  const event = hub.addEvent('message', `${urgent ? 'Urgent message' : 'Message'} to ${everyone ? 'the family' : to}: ${body}`, { urgent }, now.getTime())
  const html = urgent
    ? `🔴 <b>Urgent from ${esc(elder.name)}</b>, through Luna:\n\n${esc(body)}`
    : `💬 <b>${esc(elder.name)}</b>, through Luna:\n\n${esc(body)}`
  const buttons: Button[][] | undefined = urgent
    ? [[{ text: `📞 Call ${elder.name}`, data: `call:${elder.id}` }, { text: "👍 I'm on it", data: `ack:${event.id}` }]]
    : undefined

  const told: string[] = []
  const group = hub.familyGroupChat()
  if (everyone && group !== null) {
    await send(messenger, group, html, buttons)
    told.push('the family group')
  } else {
    const people = everyone ? familyRecipients(hub) : [hub.findMember(to, ['caregiver', 'family', 'provider'])].filter((m): m is Member => !!m)
    if (!people.length) throw new ToolError(everyone ? 'No one in her family is connected to Luna on Telegram yet.' : `No one called "${to}". Her family: ${familyList(hub)}.`)
    for (const person of people) {
      await deliver(hub, messenger, person, html, buttons)
      told.push(person.name)
    }
  }
  if (urgent) {
    for (const carer of hub.caregivers()) {
      if (told.includes(carer.name) || carer.telegramChatId === null || told.includes('the family group')) continue
      await deliver(hub, messenger, carer, html, buttons)
      told.push(carer.name)
    }
  }
  const names = told.join(' and ')
  return {
    result: { ok: true, sent_to: names, urgent: urgent || undefined },
    card: { title: urgent ? `Urgent message to ${names}` : `Told ${names}`, detail: body },
    action: `message_family: ${urgent ? 'urgent ' : ''}message to ${names}: "${body}"`,
  }
}

async function requestService(args: Record<string, unknown>, { hub, messenger, now }: ToolDeps): Promise<ToolOutcome> {
  const what = text(args.what)
  if (!what) throw new ToolError('Say what to order or book.')
  const provider = hub.findMember(text(args.provider), ['provider'])
  if (!provider) {
    const providers = hub.members().filter(m => m.role === 'provider').map(m => `${m.name} (${m.relation})`).join(', ')
    throw new ToolError(`No shop or service called "${text(args.provider)}". Hers: ${providers}.`)
  }
  const when = resolveWhen(text(args.when), now)
  const request = hub.addRequest({ provider: provider.id, what, whenText: when.label || text(args.when) }, now.getTime())
  const elder = hub.elder()
  const html = `🛎️ <b>New request from ${esc(elder.name)}</b>, through Luna:\n\n${esc(what)}${when.label ? `\n🕓 ${esc(when.label)}` : ''}\n\n<i>Reply to this message to send her a note.</i>`
  const sent = await deliver(hub, messenger, provider, html, [
    [{ text: '✅ Confirm', data: `req:${request.id}:ok` }, { text: '⏱ Within the hour', data: `req:${request.id}:hour` }],
    [{ text: '🕐 Another time', data: `req:${request.id}:later` }],
  ])
  hub.updateRequest(request.id, { telegramChatId: sent.chatId, telegramMessageId: sent.messageId }, now.getTime())
  hub.addEvent('request', `You sent ${provider.name}: ${what}${when.label ? `, ${when.label}` : ''}.`, { request: request.id }, now.getTime())
  return {
    result: { ok: true, request: request.id, sent_to: provider.name, when: when.label || undefined, next: `${provider.name} confirms on Telegram; she'll be told.` },
    card: { title: `Sent to ${provider.name}`, detail: what },
    action: `request_service: sent ${provider.name} "${what}"${when.label ? ` (${when.label})` : ''}, request ${request.id}, waiting for confirmation`,
  }
}

async function logCheckin(args: Record<string, unknown>, { hub, messenger, session, now }: ToolDeps): Promise<ToolOutcome> {
  const kind = checkinKind(text(args.kind))
  const ok = args.ok === undefined || args.ok === null || args.ok === '' ? true : truthy(args.ok)
  const detail = text(args.detail)
  const day = dayKey(now)
  const dose = kind === 'medicine'
    ? (session.context.purpose === 'checkin' && session.context.dose_id ? hub.dose(session.context.dose_id) : null) ?? hub.nearestDose(now)
    : null
  hub.addCheckin({ kind, detail, ok, doseId: dose?.id ?? null, day, at: now.getTime(), source: 'luna' })
  const elder = hub.elder()
  const doseName = dose ? `${dose.name} (${clock(dose.time)})` : 'medicine'
  if (kind === 'medicine') {
    hub.addEvent('checkin', ok ? `You took your ${doseName}.` : `You hadn't taken your ${doseName} yet.`, { dose: dose?.id, ok }, now.getTime())
    // Her caregiver was told she'd missed it: tell them it's sorted.
    if (ok && dose && hub.doseAlerted(dose.id, day)) {
      for (const carer of hub.caregivers().filter(c => c.telegramChatId !== null)) {
        await send(messenger, carer.telegramChatId!, `✅ ${esc(elder.name)} has taken the ${esc(doseName)} now.`)
      }
    }
  } else {
    hub.addEvent('checkin', `You told Luna about your ${kind}${detail ? `: ${detail}` : ''}.`, { kind, ok }, now.getTime())
  }
  return {
    result: { ok: true, logged: kind === 'medicine' ? `${doseName}: ${ok ? 'taken' : 'not taken yet'}` : `${kind}${detail ? `: ${detail}` : ''}` },
    card: kind === 'medicine'
      ? { title: ok ? 'Medicine taken' : 'Not taken yet', detail: doseName }
      : { title: `Noted: ${kind}`, detail: detail || (ok ? 'done' : 'not yet') },
    action: `log_checkin: ${kind}${dose ? ` ${doseName}` : ''} ${ok ? 'done' : 'not yet'}${detail ? ` (${detail})` : ''}`,
  }
}

function familyStatus(args: Record<string, unknown>, { hub, now }: ToolDeps): ToolOutcome {
  const who = text(args.who) ? hub.findMember(text(args.who)) : null
  const since = now.getTime() - 2 * 24 * 60 * 60_000
  const name = (id: string) => hub.member(id)?.name ?? id
  const tasks = hub.tasks()
    .filter(t => (t.status !== 'done' || t.updatedAt > since) && (!who || t.assignee === who.id))
    .map(t => ({ task: t.title, who: name(t.assignee), when: t.dueAt ? formatTime(new Date(t.dueAt), now) : t.whenText || undefined, answer: TASK_STATES[t.status] }))
  const requests = hub.requests()
    .filter(r => r.updatedAt > since && (!who || r.provider === who.id))
    .map(r => ({ to: name(r.provider), what: r.what, answer: REQUEST_STATES[r.status], note: r.note ?? undefined }))
  const medicines = hub.doseStates(now).map(d => ({ medicine: `${d.dose.name} at ${clock(d.dose.time)}`, state: d.state.replace('_', ' ') }))
  return { result: { ok: true, tasks, requests, medicines } }
}

const TASK_STATES = { open: 'no answer yet', accepted: 'said yes', done: 'done', declined: "can't" }
const REQUEST_STATES = { sent: 'not confirmed yet', confirmed: 'confirmed', within_hour: 'confirmed, within the hour', another_time: 'asked for another time' }

/** Sends to [member] on Telegram, or to the family group naming them when they haven't linked. */
async function deliver(hub: Hub, messenger: Messenger | null, member: Member, html: string, buttons?: Button[][]) {
  if (member.telegramChatId !== null) {
    return { chatId: member.telegramChatId, messageId: await send(messenger, member.telegramChatId, html, buttons) }
  }
  const group = hub.familyGroupChat()
  if (group !== null && member.role !== 'provider') {
    return { chatId: group, messageId: await send(messenger, group, `<b>For ${esc(member.name)}</b>\n${html}`, buttons) }
  }
  throw new ToolError(`${member.name} isn't connected to Luna on Telegram yet, so nothing was sent.`)
}

async function send(messenger: Messenger | null, chatId: number, html: string, buttons?: Button[][]): Promise<number> {
  if (!messenger) throw new ToolError('Messages to the family are not set up on the server (no Telegram bot), so nothing was sent.')
  return messenger.send(chatId, html, buttons)
}

function familyList(hub: Hub): string {
  return hub.members().filter(m => m.role === 'caregiver' || m.role === 'family').map(m => `${m.name} (${m.relation})`).join(', ')
}

function checkinKind(said: string): string {
  const s = said.toLowerCase()
  if (/medic|tablet|pill|dawa|dose|bp|sugar|insulin/.test(s)) return 'medicine'
  if (/meal|food|lunch|dinner|breakfast|ate|khana|nashta/.test(s)) return 'meal'
  if (/walk|exercise|steps/.test(s)) return 'walk'
  if (/mood|feel|sleep/.test(s)) return 'mood'
  return s || 'other'
}

/** "09:00" → "9:00 AM". */
export function clock(time: string): string {
  const [h, m] = time.split(':').map(Number)
  return `${h % 12 || 12}:${String(m).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`
}

function text(value: unknown): string {
  return value === undefined || value === null ? '' : String(value).trim()
}

function truthy(value: unknown): boolean {
  if (typeof value === 'boolean') return value
  return !/^(false|no|not yet|nahi|nahin|0|)$/i.test(String(value ?? '').trim())
}

function declare(name: string, description: string, properties: Record<string, string>, required: string[], argsNote: string): ToolDeclaration {
  return {
    name,
    description: `${description} ${argsNote}`,
    parameters: {
      type: 'object',
      properties: Object.fromEntries(Object.entries(properties).map(([k, type]) => [k, { type }])),
      required,
    },
  }
}
