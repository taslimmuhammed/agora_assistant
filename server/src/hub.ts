import { EventEmitter } from 'node:events'
import { mkdirSync, readFileSync } from 'node:fs'
import { dirname } from 'node:path'
import { DatabaseSync } from 'node:sqlite'
import { atClock, dayKey } from './when.ts'

/**
 * The family's shared state: who's who, the medicines, what was asked of whom and what came of
 * it. The phone keeps the elder's own memory, reminders and lists; this holds only what other
 * people see or answer. One SQLite file (Node's built-in sqlite), seeded from seed/family.json.
 */

export type Role = 'elder' | 'caregiver' | 'family' | 'provider'

export interface Member {
  id: string
  name: string
  relation: string
  role: Role
  phone: string | null
  aliases: string[]
  /** Added from the app (picked from her contacts), not in the seed: the app may remove them. */
  added: boolean
}

export interface Dose {
  id: string
  name: string
  detail: string
  /** "09:00", every day. */
  time: string
}

export type TaskStatus = 'open' | 'accepted' | 'done' | 'declined'

export interface Task {
  id: number
  title: string
  assignee: string
  whenText: string
  dueAt: number | null
  followUpAt: number | null
  status: TaskStatus
  nudgedAt: number | null
  escalated: boolean
  /** The WhatsApp chat it was sent to and Luna's message there, for replies to it. */
  chatId: string | null
  messageId: string | null
  createdAt: number
  updatedAt: number
}

export type RequestStatus = 'sent' | 'confirmed' | 'within_hour' | 'another_time'

export interface ServiceRequest {
  id: number
  provider: string
  what: string
  whenText: string
  status: RequestStatus
  note: string | null
  chatId: string | null
  messageId: string | null
  createdAt: number
  updatedAt: number
}

export interface Checkin {
  id: number
  kind: string
  detail: string
  ok: boolean
  doseId: string | null
  day: string
  at: number
  /** "luna" when she told Luna, "caregiver" when someone answered "She's taken it". */
  source: string
}

export interface HubEvent {
  id: number
  at: number
  /** task, request, checkin, message, alert, call */
  kind: string
  /** Written to be said to the elder: "Rahul will take you to Dr. Rao's at 4:30 PM today." */
  text: string
  item: unknown
  /** She heard it in a call as it came (announce.ts), so the phone needn't tell her again. */
  told?: boolean
}

/** What Luna tells her when it comes: the family's answers, and the shops'. */
export const ANSWER_KINDS = ['reply', 'task_reply', 'request_reply']

/** What the phone tells her outside a call: the answers, and what Luna told the family for her. */
export const UPDATE_KINDS = [...ANSWER_KINDS, 'alert']

export type DoseState = 'taken' | 'not_yet' | 'due' | 'missed' | 'upcoming'

export interface Seed {
  elder: string
  members: Array<Omit<Member, 'phone' | 'aliases' | 'added'> & { phone?: string; aliases?: string[] }>
  doses: Dose[]
}

/** How long after a dose's time it counts as missed, and the caregiver is told. */
export const MISSED_AFTER_MS = 30 * 60_000

export class Hub {
  readonly db: DatabaseSync
  readonly elderId: string
  readonly doses: Dose[]
  /** 'event' with each HubEvent as it's added: the dashboard's live feed. */
  readonly feed = new EventEmitter()

  constructor(path: string, seed: Seed) {
    if (path !== ':memory:') mkdirSync(dirname(path), { recursive: true })
    this.db = new DatabaseSync(path)
    this.db.exec(SCHEMA)
    // Numbers picked from her phone's contacts live in app_phone, so a new seed never loses them.
    const columns = this.db.prepare('PRAGMA table_info(members)').all().map(c => String(c.name))
    if (!columns.includes('app_phone')) this.db.exec('ALTER TABLE members ADD COLUMN app_phone TEXT')
    if (!columns.includes('added')) this.db.exec('ALTER TABLE members ADD COLUMN added INTEGER NOT NULL DEFAULT 0')
    const eventColumns = this.db.prepare('PRAGMA table_info(events)').all().map(c => String(c.name))
    if (!eventColumns.includes('told')) this.db.exec('ALTER TABLE events ADD COLUMN told INTEGER NOT NULL DEFAULT 0')
    this.elderId = seed.elder
    this.doses = seed.doses
    // The seed decides who's in the family, and their numbers are their WhatsApp chats.
    const upsert = this.db.prepare(
      `INSERT INTO members (id, name, relation, role, phone, aliases) VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT(id) DO UPDATE SET name = excluded.name, relation = excluded.relation,
         role = excluded.role, phone = excluded.phone, aliases = excluded.aliases`,
    )
    for (const m of seed.members) upsert.run(m.id, m.name, m.relation, m.role, m.phone ?? null, JSON.stringify(m.aliases ?? []))
  }

  static fromFiles(dbPath: string, seedPath: string): Hub {
    return new Hub(dbPath, JSON.parse(readFileSync(seedPath, 'utf8')) as Seed)
  }

  // Members

  members(): Member[] {
    return this.db.prepare('SELECT * FROM members ORDER BY rowid').all().map(toMember)
  }

  /** A number set from the app (picked from her contacts); empty clears it, back to the seed's. */
  setPhone(id: string, phone: string): Member | null {
    this.db.prepare('UPDATE members SET app_phone = ? WHERE id = ?').run(phone.trim() || null, id)
    return this.member(id)
  }

  /** Someone new, picked from her contacts in the app: family, a caregiver, or a shop or service. */
  addMember(person: { name: string; relation: string; role: Exclude<Role, 'elder'>; phone: string }): Member {
    const base = person.name.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'someone'
    let id = base
    for (let n = 2; this.member(id); n++) id = `${base}-${n}`
    this.db.prepare(`INSERT INTO members (id, name, relation, role, phone, aliases, app_phone, added) VALUES (?, ?, ?, ?, NULL, '[]', ?, 1)`)
      .run(id, person.name.trim(), person.relation.trim(), person.role, person.phone.trim() || null)
    return this.member(id)!
  }

  /** Removes someone added from the app; the seed's people stay. */
  removeMember(id: string): boolean {
    return Number(this.db.prepare('DELETE FROM members WHERE id = ? AND added = 1').run(id).changes) > 0
  }

  member(id: string): Member | null {
    const row = this.db.prepare('SELECT * FROM members WHERE id = ?').get(id)
    return row ? toMember(row) : null
  }

  elder(): Member {
    const elder = this.member(this.elderId)
    if (!elder) throw new Error(`The seed names elder "${this.elderId}" but has no such member`)
    return elder
  }

  caregivers(): Member[] {
    return this.members().filter(m => m.role === 'caregiver')
  }

  /**
   * The member someone means by [words]: "Rahul", "my son", "the grocery shop", "ramesh stores".
   * A whole name or alias beats a word of one; [roles] narrows who may be meant.
   */
  findMember(words: string, roles?: Role[]): Member | null {
    const said = normalise(words)
    if (!said) return null
    const candidates = this.members().filter(m => !roles || roles.includes(m.role))
    const names = (m: Member) => [m.id, m.name, m.relation, ...m.aliases].map(normalise).filter(Boolean)
    return (
      candidates.find(m => names(m).includes(said)) ??
      candidates.find(m => names(m).some(n => n.length >= 3 && said.length >= 3 && (said.includes(n) || n.includes(said)))) ??
      candidates.find(m => names(m).some(n => n.split(' ').some(word => word.length > 2 && said.split(' ').includes(word)))) ??
      null
    )
  }

  // Settings kept between restarts: the family group's chat, when each call ended, the digest,
  // and the choices under Luna's WhatsApp messages (whatsapp.ts).

  meta(key: string): string | null {
    const row = this.db.prepare('SELECT value FROM meta WHERE key = ?').get(key) as { value: string } | undefined
    return row?.value ?? null
  }

  setMeta(key: string, value: string) {
    this.db.prepare('INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value').run(key, value)
  }

  deleteMeta(key: string) {
    this.db.prepare('DELETE FROM meta WHERE key = ?').run(key)
  }

  /** The WhatsApp group that gets family-wide news, once someone sends /family in it. */
  familyGroupChat(): string | null {
    return this.meta('family_group_chat')
  }

  // Tasks asked of family members

  addTask(task: { title: string; assignee: string; whenText: string; dueAt: number | null; followUpAt: number | null }, now: number): Task {
    const info = this.db
      .prepare(`INSERT INTO tasks (title, assignee, when_text, due_at, follow_up_at, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'open', ?, ?)`)
      .run(task.title, task.assignee, task.whenText, task.dueAt, task.followUpAt, now, now)
    return this.task(Number(info.lastInsertRowid))!
  }

  task(id: number): Task | null {
    const row = this.db.prepare('SELECT * FROM tasks WHERE id = ?').get(id)
    return row ? toTask(row) : null
  }

  tasks(): Task[] {
    return this.db.prepare('SELECT * FROM tasks ORDER BY id').all().map(toTask)
  }

  updateTask(id: number, patch: Partial<Pick<Task, 'status' | 'nudgedAt' | 'escalated' | 'chatId' | 'messageId'>>, now: number) {
    const columns: Record<string, string> = {
      status: 'status', nudgedAt: 'nudged_at', escalated: 'escalated', chatId: 'chat_id', messageId: 'message_id',
    }
    for (const [key, value] of Object.entries(patch)) {
      const v = typeof value === 'boolean' ? Number(value) : value
      this.db.prepare(`UPDATE tasks SET ${columns[key]} = ?, updated_at = ? WHERE id = ?`).run(v as number | string | null, now, id)
    }
  }

  /** For a task that never reached anyone. */
  removeTask(id: number) {
    this.db.prepare('DELETE FROM tasks WHERE id = ?').run(id)
  }

  // Requests to shops, clinics and services

  addRequest(request: { provider: string; what: string; whenText: string }, now: number): ServiceRequest {
    const info = this.db
      .prepare(`INSERT INTO requests (provider, what, when_text, status, created_at, updated_at) VALUES (?, ?, ?, 'sent', ?, ?)`)
      .run(request.provider, request.what, request.whenText, now, now)
    return this.request(Number(info.lastInsertRowid))!
  }

  request(id: number): ServiceRequest | null {
    const row = this.db.prepare('SELECT * FROM requests WHERE id = ?').get(id)
    return row ? toRequest(row) : null
  }

  requests(): ServiceRequest[] {
    return this.db.prepare('SELECT * FROM requests ORDER BY id').all().map(toRequest)
  }

  updateRequest(id: number, patch: Partial<Pick<ServiceRequest, 'status' | 'note' | 'chatId' | 'messageId'>>, now: number) {
    const columns: Record<string, string> = {
      status: 'status', note: 'note', chatId: 'chat_id', messageId: 'message_id',
    }
    for (const [key, value] of Object.entries(patch)) {
      this.db.prepare(`UPDATE requests SET ${columns[key]} = ?, updated_at = ? WHERE id = ?`).run(value as number | string | null, now, id)
    }
  }

  removeRequest(id: number) {
    this.db.prepare('DELETE FROM requests WHERE id = ?').run(id)
  }

  /** The task or request Luna's WhatsApp message [messageId] in [chatId] was about, for replies to it. */
  aboutMessage(chatId: string, messageId: string): { task?: Task; request?: ServiceRequest } | null {
    const task = this.db.prepare('SELECT * FROM tasks WHERE chat_id = ? AND message_id = ?').get(chatId, messageId)
    if (task) return { task: toTask(task) }
    const request = this.db.prepare('SELECT * FROM requests WHERE chat_id = ? AND message_id = ?').get(chatId, messageId)
    return request ? { request: toRequest(request) } : null
  }

  // Check-ins: medicines, meals, mood, walks

  addCheckin(checkin: Omit<Checkin, 'id'>): Checkin {
    const info = this.db
      .prepare('INSERT INTO checkins (kind, detail, ok, dose_id, day, at, source) VALUES (?, ?, ?, ?, ?, ?, ?)')
      .run(checkin.kind, checkin.detail, Number(checkin.ok), checkin.doseId, checkin.day, checkin.at, checkin.source)
    return { ...checkin, id: Number(info.lastInsertRowid) }
  }

  checkinsOn(day: string): Checkin[] {
    return this.db.prepare('SELECT * FROM checkins WHERE day = ? ORDER BY at').all(day).map(toCheckin)
  }

  dose(id: string): Dose | null {
    return this.doses.find(d => d.id === id) ?? null
  }

  /** Each of today's doses and where it stands at [now]. */
  doseStates(now: Date): Array<{ dose: Dose; state: DoseState; at: Date }> {
    const checkins = this.checkinsOn(dayKey(now)).filter(c => c.kind === 'medicine')
    return this.doses.map(dose => {
      const at = atClock(now, dose.time)
      const said = checkins.filter(c => c.doseId === dose.id)
      const state: DoseState = said.some(c => c.ok)
        ? 'taken'
        : now.getTime() >= at.getTime() + MISSED_AFTER_MS
          ? 'missed'
          : said.length > 0
            ? 'not_yet'
            : now.getTime() >= at.getTime() - 60 * 60_000
              ? 'due'
              : 'upcoming'
      return { dose, state, at }
    })
  }

  /** The dose she most likely means when she talks about her medicine at [now]. */
  nearestDose(now: Date): Dose | null {
    let best: Dose | null = null
    let distance = Infinity
    for (const dose of this.doses) {
      const d = Math.abs(atClock(now, dose.time).getTime() - now.getTime())
      if (d < distance) [best, distance] = [dose, d]
    }
    return best
  }

  doseAlerted(doseId: string, day: string): boolean {
    return !!this.db.prepare('SELECT 1 FROM dose_alerts WHERE dose_id = ? AND day = ?').get(doseId, day)
  }

  markDoseAlerted(doseId: string, day: string, now: number) {
    this.db.prepare('INSERT OR REPLACE INTO dose_alerts (dose_id, day, at) VALUES (?, ?, ?)').run(doseId, day, now)
  }

  clearDoseAlert(doseId: string, day: string) {
    this.db.prepare('DELETE FROM dose_alerts WHERE dose_id = ? AND day = ?').run(doseId, day)
  }

  // What happened, for the elder's next call, the digest and the dashboard

  addEvent(kind: string, text: string, item: unknown, now: number): HubEvent {
    const info = this.db.prepare('INSERT INTO events (at, kind, text, item) VALUES (?, ?, ?, ?)').run(now, kind, text, JSON.stringify(item ?? null))
    const event = { id: Number(info.lastInsertRowid), at: now, kind, text, item }
    this.feed.emit('event', event)
    return event
  }

  eventsSince(since: number, kinds?: string[]): HubEvent[] {
    return this.db
      .prepare('SELECT * FROM events WHERE at > ? ORDER BY at')
      .all(since)
      .map(toEvent)
      .filter(e => !kinds || kinds.includes(e.kind))
  }

  /** She was told [id] in her call as it came. */
  markTold(id: number) {
    this.db.prepare('UPDATE events SET told = 1 WHERE id = ?').run(id)
  }

  /** The answers and alerts after event [afterId], for the phone to tell her outside a call. */
  answersAfter(afterId: number): HubEvent[] {
    return this.db.prepare('SELECT * FROM events WHERE id > ? ORDER BY id').all(afterId).map(toEvent).filter(e => UPDATE_KINDS.includes(e.kind))
  }

  lastEventId(): number {
    return Number((this.db.prepare('SELECT MAX(id) AS id FROM events').get() as Row | undefined)?.id ?? 0)
  }

  /** Someone may answer soon: a task or request with no answer yet, or a message in the last hour. */
  awaitingAnswers(now: number): boolean {
    return this.tasks().some(t => t.status === 'open') ||
      this.requests().some(r => r.status === 'sent') ||
      this.eventsSince(now - 60 * 60_000, ['message', 'task', 'request']).length > 0
  }

  /** Back to the seed, keeping the family group: between rehearsals. */
  reset() {
    this.db.exec('DELETE FROM tasks; DELETE FROM requests; DELETE FROM checkins; DELETE FROM dose_alerts; DELETE FROM events;')
    this.db.prepare("DELETE FROM meta WHERE key LIKE 'last_call_end:%' OR key = 'digest_day' OR key LIKE 'wa:%'").run()
  }

  /** Everything the dashboard shows. */
  snapshot(now: Date) {
    return {
      now: now.toISOString(),
      elder: this.elder(),
      members: this.members(),
      familyGroupLinked: this.familyGroupChat() !== null,
      doses: this.doseStates(now).map(d => ({ ...d.dose, state: d.state })),
      tasks: this.tasks(),
      requests: this.requests(),
      checkins: this.checkinsOn(dayKey(now)),
      events: this.eventsSince(now.getTime() - 24 * 60 * 60_000).reverse().slice(0, 50),
    }
  }
}

const SCHEMA = `
CREATE TABLE IF NOT EXISTS members (
  id TEXT PRIMARY KEY, name TEXT NOT NULL, relation TEXT NOT NULL, role TEXT NOT NULL,
  phone TEXT, aliases TEXT NOT NULL DEFAULT '[]'
);
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS tasks (
  id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, assignee TEXT NOT NULL,
  when_text TEXT NOT NULL DEFAULT '', due_at INTEGER, follow_up_at INTEGER, status TEXT NOT NULL,
  nudged_at INTEGER, escalated INTEGER NOT NULL DEFAULT 0, chat_id TEXT, message_id TEXT,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS requests (
  id INTEGER PRIMARY KEY AUTOINCREMENT, provider TEXT NOT NULL, what TEXT NOT NULL,
  when_text TEXT NOT NULL DEFAULT '', status TEXT NOT NULL, note TEXT, chat_id TEXT, message_id TEXT,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS checkins (
  id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '',
  ok INTEGER NOT NULL, dose_id TEXT, day TEXT NOT NULL, at INTEGER NOT NULL, source TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS dose_alerts (dose_id TEXT NOT NULL, day TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY (dose_id, day));
CREATE TABLE IF NOT EXISTS events (
  id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, item TEXT
);
`

type Row = Record<string, unknown>

function toMember(row: Row): Member {
  return {
    id: String(row.id),
    name: String(row.name),
    relation: String(row.relation),
    role: row.role as Role,
    phone: (row.app_phone as string | null) || (row.phone as string | null) || null,
    aliases: JSON.parse(String(row.aliases ?? '[]')),
    added: Number(row.added ?? 0) === 1,
  }
}

function toTask(row: Row): Task {
  return {
    id: Number(row.id),
    title: String(row.title),
    assignee: String(row.assignee),
    whenText: String(row.when_text ?? ''),
    dueAt: row.due_at == null ? null : Number(row.due_at),
    followUpAt: row.follow_up_at == null ? null : Number(row.follow_up_at),
    status: row.status as TaskStatus,
    nudgedAt: row.nudged_at == null ? null : Number(row.nudged_at),
    escalated: Number(row.escalated) === 1,
    chatId: (row.chat_id as string | null) ?? null,
    messageId: (row.message_id as string | null) ?? null,
    createdAt: Number(row.created_at),
    updatedAt: Number(row.updated_at),
  }
}

function toRequest(row: Row): ServiceRequest {
  return {
    id: Number(row.id),
    provider: String(row.provider),
    what: String(row.what),
    whenText: String(row.when_text ?? ''),
    status: row.status as RequestStatus,
    note: (row.note as string | null) ?? null,
    chatId: (row.chat_id as string | null) ?? null,
    messageId: (row.message_id as string | null) ?? null,
    createdAt: Number(row.created_at),
    updatedAt: Number(row.updated_at),
  }
}

function toCheckin(row: Row): Checkin {
  return {
    id: Number(row.id),
    kind: String(row.kind),
    detail: String(row.detail ?? ''),
    ok: Number(row.ok) === 1,
    doseId: (row.dose_id as string | null) ?? null,
    day: String(row.day),
    at: Number(row.at),
    source: String(row.source),
  }
}

function toEvent(row: Row): HubEvent {
  return { id: Number(row.id), at: Number(row.at), kind: String(row.kind), text: String(row.text), item: JSON.parse(String(row.item ?? 'null')), told: Number(row.told ?? 0) === 1 }
}

function normalise(words: string): string {
  return words.toLowerCase().replace(/[^a-z0-9ऀ-ॿ ]+/g, ' ').replace(/\b(the|my|our|to|at)\b/g, ' ').replace(/\s+/g, ' ').trim()
}
