import { type Hub, MISSED_AFTER_MS } from './hub.ts'
import { type Button, type Messenger, esc, familyRecipients } from './telegram.ts'
import { clock } from './tools.ts'
import { atClock, dayKey, formatTime } from './when.ts'

/**
 * What happens after the call, run every half minute (index.ts) and on demand (/demo/*):
 * - a task with no answer by its follow-up time is asked again, and 15 minutes later the rest
 *   of the family is told;
 * - a dose she hasn't confirmed 30 minutes after its time is flagged to her caregiver;
 * - at 8 PM the family gets the day's digest.
 */

const ESCALATE_AFTER_MS = 15 * 60_000
/** A dose missed longer ago than this when the server starts isn't news any more. */
const STALE_AFTER_MS = 3 * 60 * 60_000
const DIGEST_HOUR = 20

export interface FollowUpOptions {
  /** Flag this dose now (or the latest one due) whatever the time: "Skip to 9:30" in the demo. */
  forceDose?: string | true
  /** Ask every unanswered task's assignee again now, then escalate on the next force. */
  forceNudge?: boolean
  forceDigest?: boolean
}

/** Runs what's due at [now]; returns a line for each thing it did. */
export async function runFollowUps(hub: Hub, messenger: Messenger | null, now: Date, options: FollowUpOptions = {}): Promise<string[]> {
  if (!messenger) return []
  const did: string[] = []
  const elder = hub.elder()
  const t = now.getTime()

  for (const task of hub.tasks().filter(x => x.status === 'open')) {
    const assignee = hub.member(task.assignee)
    if (!assignee) continue
    const title = `<b>${esc(task.title)}</b>${task.dueAt ? `\n🕓 ${esc(formatTime(new Date(task.dueAt), now))}` : ''}`
    if (task.nudgedAt === null && (options.forceNudge || (task.followUpAt !== null && task.followUpAt <= t))) {
      if (task.telegramChatId === null) continue
      await messenger.send(task.telegramChatId, `⏰ ${esc(elder.name)} is waiting to hear from you:\n\n${title}`, [
        [{ text: "👍 I'll do it", data: `task:${task.id}:yes` }, { text: "🙅 Can't", data: `task:${task.id}:no` }],
      ])
      hub.updateTask(task.id, { nudgedAt: t }, t)
      did.push(`nudged ${assignee.name} about task ${task.id}`)
    } else if (task.nudgedAt !== null && !task.escalated && (options.forceNudge || task.nudgedAt + ESCALATE_AFTER_MS <= t)) {
      const others = familyRecipients(hub).filter(m => m.id !== assignee.id)
      const group = hub.familyGroupChat()
      const html = `⚠️ ${esc(assignee.name)} hasn't answered ${esc(elder.name)}'s request yet:\n\n${title}`
      if (group !== null) await messenger.send(group, html)
      else for (const m of others) await messenger.send(m.telegramChatId!, html)
      hub.updateTask(task.id, { escalated: true }, t)
      hub.addEvent('alert', `${assignee.name} hasn't answered yet about: ${task.title}. I've let the family know.`, { task: task.id }, t)
      did.push(`told the family ${assignee.name} hasn't answered task ${task.id}`)
    }
  }

  const day = dayKey(now)
  const doses = hub.doseStates(now)
  const forced = options.forceDose === true
    ? [...doses].reverse().find(d => d.at.getTime() <= t) ?? doses[0]
    : options.forceDose ? doses.find(d => d.dose.id === options.forceDose) : undefined
  for (const { dose, state, at } of doses) {
    const missedFor = t - (at.getTime() + MISSED_AFTER_MS)
    const due = forced ? forced.dose.id === dose.id && state !== 'taken' : state === 'missed' && missedFor < STALE_AFTER_MS && !hub.doseAlerted(dose.id, day)
    if (!due) continue
    const carers = hub.caregivers().filter(c => c.telegramChatId !== null)
    const group = hub.familyGroupChat()
    const chats = carers.length ? carers.map(c => c.telegramChatId!) : group !== null ? [group] : []
    if (!chats.length) continue
    const buttons: Button[][] = [[{ text: `📞 Call ${elder.name}`, data: `call:${elder.id}` }, { text: '✅ Took it', data: `dose:${dose.id}:${day}:took` }]]
    for (const chat of chats) {
      await messenger.send(chat, `🔴 <b>${esc(elder.name)} hasn't confirmed the ${clock(dose.time)} ${esc(dose.name)}</b>\n${esc(dose.detail)}`, buttons)
    }
    hub.markDoseAlerted(dose.id, day, t)
    const told = carers.length ? carers.map(c => c.name).join(' and ') : 'your family'
    hub.addEvent('alert', `I let ${told} know you hadn't confirmed your ${clock(dose.time)} ${dose.name}.`, { dose: dose.id, day }, t)
    did.push(`flagged the ${dose.time} ${dose.id} dose`)
  }

  if (options.forceDigest || (now.getHours() >= DIGEST_HOUR && hub.meta('digest_day') !== day)) {
    const group = hub.familyGroupChat()
    const chats = group !== null ? [group] : familyRecipients(hub).map(m => m.telegramChatId!)
    for (const chat of chats) await messenger.send(chat, digestText(hub, now))
    hub.setMeta('digest_day', day)
    if (chats.length) did.push('sent the digest')
  }
  return did
}

/** The day at a glance, for the family: medicines, what was asked and answered, orders. */
export function digestText(hub: Hub, now: Date): string {
  const elder = hub.elder()
  const start = atClock(now, '00:00').getTime()
  const name = (id: string) => hub.member(id)?.name ?? id
  const lines = [`🌙 <b>${esc(elder.name)}'s day</b> · ${now.toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short' })}`]
  for (const { dose, state } of hub.doseStates(now)) {
    const mark = state === 'taken' ? '✅ taken' : state === 'missed' ? '❌ not confirmed' : state === 'not_yet' ? '⏳ not yet' : '🕓 later'
    lines.push(`💊 ${clock(dose.time)} ${esc(dose.name)}: ${mark}`)
  }
  for (const task of hub.tasks().filter(x => x.updatedAt >= start || x.status === 'open' || x.status === 'accepted')) {
    const mark = { open: '⏳ no answer', accepted: '👍 said yes', done: '✅ done', declined: "🙅 can't" }[task.status]
    lines.push(`🙏 ${esc(name(task.assignee))}: ${esc(task.title)} · ${mark}`)
  }
  for (const request of hub.requests().filter(r => r.updatedAt >= start)) {
    const mark = { sent: '⏳ not confirmed', confirmed: '✅ confirmed', within_hour: '✅ within the hour', another_time: '🕐 another time' }[request.status]
    lines.push(`🛎️ ${esc(name(request.provider))}: ${esc(request.what)} · ${mark}${request.note ? ` (“${esc(request.note)}”)` : ''}`)
  }
  const calls = hub.eventsSince(start, ['call']).length
  lines.push(`📞 Calls with Luna today: ${calls}`)
  return lines.join('\n')
}
