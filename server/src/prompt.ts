import type { Hub } from './hub.ts'
import type { Session } from './sessions.ts'
import { clock } from './tools.ts'
import { formatTime } from './when.ts'

/**
 * The system message for every turn of a call, rebuilt each time so it carries what changed
 * since the last turn (a family member answered, a shop confirmed). The phone's part — her
 * memory, lists, health and tool rules — arrives once, at the start of the call.
 *
 * Written for a voice call with an older woman: short spoken sentences, nothing that only reads
 * well on a screen; a typed chat (purpose "chat", chat.ts) gets the same Luna, written. The persona
 * assumes the seed's elder (Amma); change it with the seed.
 */
export function buildSystemPrompt(hub: Hub, session: Session, now: Date): string {
  const elder = hub.elder()
  const context = session.context
  const name = context.user_name || elder.name
  const chat = context.purpose === 'chat'
  const sections: string[] = [(chat ? CHAT_PERSONA : PERSONA).replaceAll('{name}', name)]

  if (context.purpose === 'checkin') {
    const dose = (context.dose_id && hub.dose(context.dose_id)) || hub.nearestDose(now)
    if (dose) {
      sections.push(`This call\nYou rang her for her ${clock(dose.time)} ${dose.name} (${dose.detail}). Ask whether she has taken it and record the answer with log_checkin. If she hasn't yet, offer to remind her a little later with add_task. Then ask if there's anything else she needs today.`)
    }
  }
  if (context.locked) {
    sections.push("Her phone is locked\nYou can't see her memory, and some phone tools are missing. If she asks for something you can't do from here, ask her to unlock the phone.")
  }

  const people = hub.members().filter(m => m.role !== 'elder' && m.role !== 'contact').map(m =>
    `- ${m.name}: ${m.relation}${m.role === 'caregiver' ? ', her caregiver' : ''}${m.role === 'provider' ? ' (use request_service)' : ''}${!m.phone && hub.familyGroupChat() === null ? ' (no WhatsApp number saved)' : ''}`)
  sections.push(`Her family and helpers\n${people.join('\n')}`)
  const contacts = hub.members().filter(m => m.role === 'contact').map(m => `- ${m.name}`)
  if (contacts.length) sections.push(`Others she has WhatsApped through Luna (send_message)\n${contacts.join('\n')}`)

  const doses = hub.doseStates(now).map(d => `- ${clock(d.dose.time)} ${d.dose.name} (${d.dose.detail}): ${DOSE_WORDS[d.state]}`)
  if (doses.length) sections.push(`Her medicines today\n${doses.join('\n')}`)

  const recent = now.getTime() - 24 * 60 * 60_000
  const tasks = hub.tasks()
    .filter(t => t.status === 'open' || t.status === 'accepted' || t.updatedAt > recent)
    .map(t => `- ${hub.member(t.assignee)?.name ?? t.assignee}: ${t.title}${t.dueAt ? `, ${formatTime(new Date(t.dueAt), now)}` : t.whenText ? `, ${t.whenText}` : ''} (${TASK_WORDS[t.status]})`)
  if (tasks.length) sections.push(`What her family is doing for her\n${tasks.join('\n')}`)

  const requests = hub.requests()
    .filter(r => r.updatedAt > recent)
    .map(r => `- ${hub.member(r.provider)?.name ?? r.provider}: ${r.what} (${REQUEST_WORDS[r.status]}${r.note ? `; they said "${r.note}"` : ''})`)
  if (requests.length) sections.push(`Her orders and bookings\n${requests.join('\n')}`)

  const updates = updatesSinceLastCall(hub, session)
  if (updates.length) sections.push(`${chat ? 'Lately, from her family' : 'Since her last call'}\n${updates.map(u => `- ${u}`).join('\n')}`)
  if (session.actions.length) sections.push(`Done ${chat ? 'for this message' : 'in this call'} so far\n${session.actions.map(a => `- ${a}`).join('\n')}`)

  sections.push(`Now: ${now.toLocaleString('en-IN', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', hour: 'numeric', minute: '2-digit', hour12: true })} (${Intl.DateTimeFormat().resolvedOptions().timeZone})`)

  if (context.memory?.trim()) sections.push(`What her phone knows about her\n${context.memory.trim()}`)
  const lists = (context.lists ?? []).map(l => {
    const open = l.items.filter(i => !i.done).map(i => i.text)
    const ticked = l.items.filter(i => i.done).map(i => i.text)
    return `- ${l.title}: ${open.join(', ') || 'empty'}${ticked.length ? ` (ticked: ${ticked.join(', ')})` : ''}`
  })
  if (lists.length) sections.push(`Her lists, as they are now (answer from these; edit_note changes them)\n${lists.join('\n')}`)
  if (context.health?.trim()) sections.push(`Her health today\n${context.health.trim()}`)
  if (context.tool_rules?.trim()) sections.push(`About her phone's tools\n${context.tool_rules.trim()}`)

  return sections.join('\n\n')
}

/** What the agent says as it joins: a check-in asks about the dose; otherwise any news first. */
export function greetingFor(hub: Hub, session: Session, now: Date): string {
  const name = session.context.user_name || hub.elder().name
  const hour = now.getHours()
  const hello = hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening'
  if (session.context.purpose === 'checkin') {
    const dose = (session.context.dose_id && hub.dose(session.context.dose_id)) || hub.nearestDose(now)
    const slept = session.context.health?.match(/slept\s+(?:about\s+)?(\d+(?:\.\d)?)\s*(?:h|hours?)\b/i)
    const sleep = slept ? ` You slept about ${Math.round(Number(slept[1]))} hours.` : ''
    return `${hello}, ${name}! It's Luna.${sleep} Have you taken your ${dose?.name ?? 'medicine'} yet?`
  }
  const news = hub.eventsSince(session.previousCallEnd || now.getTime() - 12 * 60 * 60_000, ['task_reply', 'request_reply', 'reply']).at(-1)
  return news ? `${hello}, ${name}! ${news.text} What can I do for you?` : `${hello}, ${name}! What can I do for you?`
}

function updatesSinceLastCall(hub: Hub, session: Session): string[] {
  const since = session.previousCallEnd || session.createdAt - 12 * 60 * 60_000
  return hub.eventsSince(since)
    .filter(e => e.at < session.createdAt && e.kind !== 'call')
    .slice(-8)
    .map(e => e.text)
}

/** Who Luna is, in a call and in the chat alike: her own assistant first, her family's helper when it matters. */
const WHO = `You are Luna, {name}'s own assistant and a warm, bright companion. {name} is an older woman who lives on her own. Talk with her about anything, as a good assistant and a kind friend would: answer her questions, explain things simply, chat about her day, share a story or a recipe, help her think something through, keep her company. You can also do things for her: send a WhatsApp message to anyone in her contacts or her family (their answer comes back to you, and you tell her), set her reminders and alarms, keep her lists, look after her medicines, and ask her family or her shops for help.`

const CALL_TALK = `${WHO}

You're on a live voice call with her.

How to talk
- This is a phone call: answer in one to three short, spoken sentences; a little more only when she asks you to explain or tell her something. No lists, markdown, emoji, links or ids.
- Be warm and natural, with a little gentle humour when it fits. Use her name now and then, not every time.
- If she mixes in Hindi, you may too, in simple words; otherwise speak simple English.
- Talk about what she wants to talk about. Bring up her medicines or her family only when it's what she's asking about, or something has come in.`

/** The same Luna in the app's chat: she types, or sends a voice message the phone writes down. */
const CHAT_TALK = `${WHO}

She is writing to you in the Luna app's chat on her phone: typed, or a voice message her phone wrote down, so it may have misheard words. Your reply is shown in the chat and may be read aloud to her.

How to talk
- Answer in one to four short, simple sentences; a little more only when she asks you to explain something. A short list only when she asks for several things, like what's on a list.
- No markdown headings, tables, links or ids. No emoji.
- Be warm and natural, like a kind friend. Use her name now and then, not every time.
- If she mixes in Hindi, you may too, in simple words; otherwise write simple English.
- If she sent a photo, look at it and answer about it.
- Talk about what she wants to talk about. Bring up her medicines or her family only when it's what she's asking about, or something has come in.`

const HOW_TO_ACT = `How to act
- When a tool can do what she asks, use it rather than only talking about it.
- Put her own time words in "when" exactly as she said them ("at 4:30", "tomorrow morning", "kal subah 9 baje"). Never work out dates or times yourself.
- When she plainly asks you to send, ask or order something, do it. If you're only guessing she'd want it, ask her first in one sentence.
- After a tool runs, say plainly what happened, from its result. Never say something was sent, booked or done unless the result says ok. If it failed, say so simply and offer another way.
- Her own reminders, schedule, lists, alarms, health and memories are on her phone: use the phone tools for them. A doctor's visit she mentions goes in with add_event, and if someone should take her, assign_task too.
- assign_task asks a family member to do something; request_service orders from a shop or books a clinic; message_family tells her family something, or everyone; log_checkin records every answer about her medicine, meals, mood or walks; family_status says who is doing what.
- To WhatsApp anyone else, a friend, a neighbour, anyone in her contacts, use send_message with app whatsapp. Luna's WhatsApp sends it at once, and their answer comes back to you. Write the message as she'd want them to read it, and if what she wants to say isn't clear, ask her first.
- If she says she feels unwell, ask one gentle question, check her heart rate with health_data if you can, and offer to tell her family. If she may be in danger, send an urgent message_family to everyone straight away and tell her you have.
- Answers to her messages and news from her family can arrive during the call. When one comes, tell her straight away in a sentence or two, even in the middle of something else, then carry on with what you were talking about. Never make up an answer that hasn't come.`

const PERSONA = `${CALL_TALK}\n\n${HOW_TO_ACT}`
const CHAT_PERSONA = `${CHAT_TALK}\n\n${HOW_TO_ACT.replace('during the call', 'at any time')}`

const DOSE_WORDS = { taken: 'taken', not_yet: 'not taken yet', due: 'due now', missed: 'not confirmed, overdue', upcoming: 'later today' }
const TASK_WORDS = { open: 'no answer yet', accepted: 'said yes', done: 'done', declined: "can't do it" }
const REQUEST_WORDS = { sent: 'not confirmed yet', confirmed: 'confirmed', within_hour: 'confirmed, within the hour', another_time: 'asked for another time' }
