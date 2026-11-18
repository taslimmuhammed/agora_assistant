/**
 * Turns the user's own time words ("4:30", "tomorrow at 9", "kal subah 9 baje", "in an hour")
 * into a time, in the process's zone (index.ts sets TZ). The model copies the words and never
 * works out dates itself: the rule the phone's WhenResolver keeps for its own tools, so both
 * sides of a call read "4:30" the same way.
 *
 * Small on purpose: it serves the family tools (when a task is due, when to nudge), whose words
 * are short. What it can't read keeps the words as they were said, with no time.
 */
export interface When {
  at: Date | null
  /** How to say it back: "4:30 PM today", "tomorrow", or the words themselves. */
  label: string
}

const MINUTE = 60_000

const PERIODS: Array<{ words: RegExp; hour: number; pm: boolean }> = [
  { words: /\b(morning|subah|savere)\b/, hour: 9, pm: false },
  { words: /\b(noon|dopahar|afternoon)\b/, hour: 14, pm: true },
  { words: /\b(evening|shaam|sham)\b/, hour: 18, pm: true },
  { words: /\b(night|tonight|raat)\b/, hour: 21, pm: true },
]

const WEEKDAYS = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday']

export function resolveWhen(words: string | undefined | null, now: Date): When {
  const said = (words ?? '').trim()
  const text = said.toLowerCase()
  if (!text) return { at: null, label: '' }

  const relative = text.match(/\b(?:in\s+)?(an?|half an?|\d+(?:\.\d+)?)\s*(minutes?|mins?|hours?|hrs?|ghante|ghanta)\b(?:\s*(?:mein|me|baad))?/)
  if (relative && (/\bin\b/.test(text) || /\b(mein|me|baad)\b/.test(text))) {
    const amount = relative[1].startsWith('half') ? 0.5 : /^an?$/.test(relative[1]) ? 1 : Number(relative[1])
    const perUnit = /^(m|min)/.test(relative[2]) ? MINUTE : 60 * MINUTE
    const at = new Date(now.getTime() + amount * perUnit)
    return { at, label: formatTime(at, now) }
  }

  let dayOffset: number | null = null
  if (/\b(day after tomorrow|parso|parson)\b/.test(text)) dayOffset = 2
  else if (/\b(tomorrow|kal)\b/.test(text)) dayOffset = 1
  else if (/\b(today|aaj|tonight|this (morning|afternoon|evening))\b/.test(text)) dayOffset = 0
  else {
    const weekday = WEEKDAYS.findIndex(day => new RegExp(`\\b${day}\\b`).test(text))
    if (weekday >= 0) dayOffset = ((weekday - now.getDay() + 7) % 7) || 7
  }

  const period = PERIODS.find(p => p.words.test(text))
  const clock = text.match(/\b(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?(?=\s|$|\b)/)
  let hour: number | null = null
  let minute = 0
  if (clock) {
    hour = Number(clock[1])
    minute = clock[2] ? Number(clock[2]) : 0
    const meridiem = clock[3]?.[0]
    if (hour > 23 || minute > 59) return { at: null, label: said }
    if (meridiem === 'p' && hour < 12) hour += 12
    else if (meridiem === 'a' && hour === 12) hour = 0
    else if (!meridiem && hour <= 12) {
      // A bare hour reads as daytime, as people mean it: 1–6 afternoon, 7–11 morning, 12 noon,
      // unless the words say which part of the day.
      if (period?.pm && hour < 12) hour += 12
      else if (!period && hour >= 1 && hour <= 6) hour += 12
    }
  } else if (period) {
    hour = period.hour
  }

  if (hour === null && dayOffset === null) return { at: null, label: said }

  const at = new Date(now)
  at.setSeconds(0, 0)
  if (dayOffset !== null) at.setDate(at.getDate() + dayOffset)
  if (hour === null) {
    // A day with no time: kept as that day, said back without a time it never had.
    at.setHours(9, 0)
    return { at, label: dayLabel(at, now) }
  }
  at.setHours(hour, minute)
  if (dayOffset === null && at.getTime() < now.getTime() - MINUTE) at.setDate(at.getDate() + 1)
  return { at, label: formatTime(at, now) }
}

/** "4:30 PM today", "9:00 AM tomorrow", "Mon 23 Nov, 9:00 AM". */
export function formatTime(at: Date, now: Date): string {
  const hours = at.getHours() % 12 || 12
  const clock = `${hours}:${String(at.getMinutes()).padStart(2, '0')} ${at.getHours() < 12 ? 'AM' : 'PM'}`
  const day = dayLabel(at, now)
  return day === 'today' || day === 'tomorrow' ? `${clock} ${day}` : `${day}, ${clock}`
}

function dayLabel(at: Date, now: Date): string {
  const days = Math.round((startOfDay(at) - startOfDay(now)) / (24 * 60 * MINUTE))
  if (days === 0) return 'today'
  if (days === 1) return 'tomorrow'
  return at.toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short' })
}

function startOfDay(date: Date): number {
  const day = new Date(date)
  day.setHours(0, 0, 0, 0)
  return day.getTime()
}

/** "2026-11-21", the local day a dose or a digest belongs to. */
export function dayKey(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

/** "09:00" on [day] as a time. */
export function atClock(day: Date, clock: string): Date {
  const [hours, minutes] = clock.split(':').map(Number)
  const at = new Date(day)
  at.setHours(hours, minutes, 0, 0)
  return at
}
