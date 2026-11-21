import type { IncomingMessage, Server } from 'node:http'
import type { Duplex } from 'node:stream'
import { fileURLToPath } from 'node:url'
import express, { type NextFunction, type Request, type Response } from 'express'
import QRCode from 'qrcode'
import { WebSocketServer } from 'ws'
import { type AgentApi, joinBody, rtcRtmToken } from './agora.ts'
import { type Announce, announcer } from './announce.ts'
import type { Bridge } from './bridge.ts'
import type { Config } from './config.ts'
import { runFollowUps } from './followups.ts'
import type { Hub, Member } from './hub.ts'
import { type ChatFn, chatCompletions, sameSecret } from './llm.ts'
import { chatRoutes } from './chat.ts'
import type { WebLookup } from './websearch.ts'
import { buildSystemPrompt, greetingFor } from './prompt.ts'
import { isServerTool, runServerTool, whatsappSomeone } from './tools.ts'
import { type Speak, type WriteBriefing, plainBriefing, updatesFor } from './briefing.ts'
import type { LiveContext, Session, Sessions } from './sessions.ts'
import { type WhatsApp, chatOf } from './whatsapp.ts'

export interface AppDeps {
  config: Config
  hub: Hub
  /** Writes what Luna says when she opens the app (briefing.ts); without it the answers as they are. */
  briefing?: WriteBriefing
  /** Murf's voice for it; without it the phone speaks it. */
  speak?: Speak
  sessions: Sessions
  bridge: Bridge
  agents: AgentApi
  chat: ChatFn
  /** Google Search for calls and chats (websearch.ts); without it, only a phone with its own web_lookup looks things up. */
  webLookup?: WebLookup
  whatsapp: WhatsApp | null
  clock: () => Date
}

/**
 * The HTTP side. The phone: /family (and its members, answers and tool), /session/{token,start,stop}, the /bridge WebSocket and
 * /demo/* (rehearsals). Agora: /v1/chat/completions. Signing in to WhatsApp: /whatsapp/qr. The dashboard: /hub/*.
 */
export function createApp(deps: AppDeps) {
  const { config, hub, sessions, bridge, agents, clock, whatsapp } = deps
  const announce: Announce = announcer(deps)
  const app = express()
  app.use(express.json({ limit: '2mb' }))

  const requireKey = (req: Request, res: Response, next: NextFunction) => {
    const given = String(req.headers['x-app-key'] ?? req.query.key ?? '')
    if (!config.appKey || sameSecret(given, config.appKey)) return next()
    res.status(401).json({ error: 'Wrong or missing app key' })
  }

  app.get('/health', (_req, res) => {
    res.json({ ok: true })
  })

  // Who's in the family and the medicines: the phone sets its check-in calls from these.
  app.get('/family', requireKey, (_req, res) => {
    const now = clock()
    const elder = hub.elder()
    res.json({
      elder: { id: elder.id, name: elder.name, phone: elder.phone },
      members: hub.members().filter(m => m.role !== 'elder' && m.role !== 'contact').map(personJson),
      doses: hub.doseStates(now).map(d => ({ ...d.dose, state: d.state })),
    })
  })

  // The app sets someone's WhatsApp number, picked from her contacts, or adds someone new. Kept
  // across restarts, and over the seed's. With introduce, Luna says hello to them on WhatsApp.
  app.put('/family/members/:id/phone', requireKey, async (req, res) => {
    const member = hub.member(String(req.params.id))
    if (!member || member.role === 'elder') return void res.status(404).json({ error: `No one called ${req.params.id} in the family` })
    const phone = String(req.body?.phone ?? '').trim()
    if (phone && !chatOf({ phone })) return void res.status(400).json({ error: 'That number looks too short' })
    const changed = chatOf({ phone }) !== chatOf(member)
    const saved = hub.setPhone(member.id, phone)!
    res.json(await withWhatsApp(saved, changed && req.body?.introduce === true))
  })

  app.post('/family/members', requireKey, async (req, res) => {
    const name = String(req.body?.name ?? '').trim().slice(0, 60)
    const relation = String(req.body?.relation ?? '').trim().slice(0, 60)
    const role = String(req.body?.role ?? 'family')
    const phone = String(req.body?.phone ?? '').trim()
    if (!name) return void res.status(400).json({ error: 'Say who it is' })
    if (!['family', 'caregiver', 'provider'].includes(role)) return void res.status(400).json({ error: 'role is family, caregiver or provider' })
    if (!chatOf({ phone })) return void res.status(400).json({ error: 'That number looks too short' })
    const same = hub.members().find(m => chatOf(m) === chatOf({ phone }))
    if (same) return void res.status(409).json({ error: `${same.name} already has that number` })
    const added = hub.addMember({ name, relation: relation || (role === 'provider' ? 'shop' : 'family'), role: role as 'family', phone })
    res.status(201).json(await withWhatsApp(added, req.body?.introduce === true))
  })

  // The family's answers for her that came outside a call, for the phone to tell her: those after
  // [after], or on a first visit the last few of the past 12 hours, as her next call would. [waiting]: someone may answer
  // soon, so the phone looks again in a minute rather than a quarter of an hour.
  const since = (after: number) => Number.isInteger(after) && after >= 0
    ? hub.answersAfter(after)
    : hub.answersAfter(0).filter(e => e.at > clock().getTime() - 12 * 60 * 60_000).slice(-3)
  app.get('/family/answers', requireKey, (req, res) => {
    const answers = since(Number(req.query.after ?? -1))
    res.json({
      answers: answers.map(e => ({ id: e.id, kind: e.kind, text: e.text, at: e.at, told: e.told === true })),
      last: hub.lastEventId(),
      waiting: hub.awaitingAnswers(clock().getTime()),
    })
  })

  // What Luna says as she opens the app: the updates after [after] (up to [up_to]) she didn't hear
  // in a call, in one short message, with Murf's audio of it unless [audio] is false. [greet]
  // false: she's in the app already, so no "Good morning".
  app.post('/family/briefing', requireKey, async (req, res) => {
    const upTo = Number(req.body?.up_to ?? Number.MAX_SAFE_INTEGER)
    const events = since(Number(req.body?.after ?? -1)).filter(e => e.id <= upTo && !e.told)
    if (!events.length) return void res.json({ text: null, up_to: null, audio: null })
    const input = { elder: hub.elder().name, now: clock(), updates: updatesFor(hub, events), greet: req.body?.greet !== false }
    const text = await (deps.briefing?.(input) ?? Promise.resolve(plainBriefing(input))).catch(e => {
      console.warn(`[briefing] written plainly: ${(e as Error).message}`)
      return plainBriefing(input)
    })
    const audio = req.body?.audio === false ? null : ((await deps.speak?.(text)) ?? null)
    res.json({ text, up_to: events.at(-1)!.id, audio })
  })

  // [text] in Murf's voice, as the briefing's audio: for the phone to show the words first.
  app.post('/family/speech', requireKey, async (req, res) => {
    const text = String(req.body?.text ?? '').trim().slice(0, 1_000)
    if (!text) return void res.status(400).json({ error: 'Say what to say' })
    res.json({ audio: (await deps.speak?.(text)) ?? null })
  })

  // The chat on the phone, outside a call, sends to the family the way a call does: one of Luna's
  // family tools, as if she'd asked for it in a call ({ name: 'message_family', args: { to, text } }).
  // Also for rehearsals. A name not in the family is a 400, and the phone opens WhatsApp instead.
  app.post('/family/tool', requireKey, async (req, res) => {
    const name = String(req.body?.name ?? '')
    if (!isServerTool(name)) return void res.status(400).json({ error: `No family tool called "${name}"` })
    if (!whatsapp?.connected) return void res.status(503).json({ error: "WhatsApp isn't signed in on the server: open /whatsapp/qr and scan the code." })
    const now = clock()
    const session = sessions.create(`chat:${hub.elderId}`, 0, now.getTime())
    try {
      const outcome = await runServerTool(name, (req.body?.args ?? {}) as Record<string, unknown>, { hub, whatsapp, session, now })
      res.status(outcome.result.ok === false ? 400 : 200).json({ ...outcome.result, card: outcome.card })
    } finally {
      sessions.end(session.id)
    }
  })

  // "WhatsApp Ravi …" from the phone's send_message: someone in the hub by name, or the number
  // her phone found in her contacts. 404 when it's no one in the hub and came without a number.
  app.post('/family/whatsapp', requireKey, async (req, res) => {
    if (!whatsapp?.connected) return void res.status(503).json({ error: "WhatsApp isn't signed in on the server: open /whatsapp/qr and scan the code." })
    const outcome = await whatsappSomeone(req.body ?? {}, { hub, whatsapp, now: clock() })
    res.status(outcome.result.unknown ? 404 : outcome.result.ok === false ? 400 : 200).json({ ...outcome.result, card: outcome.card })
  })

  app.delete('/family/members/:id', requireKey, (req, res) => {
    const member = hub.member(String(req.params.id))
    if (!member) return void res.status(404).json({ error: `No one called ${req.params.id} in the family` })
    if (!hub.removeMember(member.id)) return void res.status(400).json({ error: `${member.name} is in the seed; clear the number instead` })
    res.json({ ok: true })
  })

  /** [member] for the app, with whether WhatsApp knows the number, after Luna's hello if asked. */
  async function withWhatsApp(member: Member, introduce: boolean) {
    const onWhatsApp = member.phone && whatsapp ? await whatsapp.hasWhatsApp(member.phone) : null
    let introduced = false
    const chat = chatOf(member)
    if (introduce && chat && whatsapp?.connected && onWhatsApp !== false) {
      introduced = await whatsapp.send(chat, introduction(hub, member)).then(() => true, e => {
        console.warn(`[whatsapp] hello to ${member.name} not sent: ${(e as Error).message}`)
        return false
      })
    }
    return { ...personJson(member), on_whatsapp: onWhatsApp, introduced }
  }

  // A call starts in two steps, because the phone must be listening on RTM before the agent joins.
  app.post('/session/token', requireKey, (req, res) => {
    if (!config.agora.appId || !config.agora.appCertificate) {
      res.status(503).json({ error: 'Agora is not set up on the server (AGORA_APP_ID, AGORA_APP_CERTIFICATE).' })
      return
    }
    const userId = String(req.body?.user_id || hub.elderId)
    const previousCallEnd = Number(hub.meta(`last_call_end:${userId}`) ?? 0)
    const session = sessions.create(userId, previousCallEnd, clock().getTime())
    res.json({
      session_id: session.id,
      app_id: config.agora.appId,
      channel: session.channel,
      uid: session.uid,
      token: rtcRtmToken(config, session.channel, session.uid),
      agent_uid: session.agentUid,
      bridge_url: `${socketBase(config, req)}/bridge?session=${session.id}`,
    })
  })

  app.post('/session/start', requireKey, async (req, res) => {
    const session = sessions.get(req.body?.session_id)
    if (!session || session.ended) {
      res.status(404).json({ error: 'No such call; get a token first.' })
      return
    }
    if (!config.publicUrl) {
      res.status(503).json({ error: "PUBLIC_URL is not set, so Agora can't reach this server's LLM." })
      return
    }
    session.context = (req.body?.context ?? {}) as LiveContext
    const now = clock()
    try {
      const joined = await agents.join(joinBody(config, session, buildSystemPrompt(hub, session, now), greetingFor(hub, session, now)))
      session.agentId = joined.agent_id
      hub.addEvent('call', `${hub.elder().name} ${session.context.purpose === 'checkin' ? 'answered a check-in call' : 'called Luna'}.`, { session: session.id }, now.getTime())
      console.log(`[call] ${session.id} started: agent ${joined.agent_id}, ${(session.context.device_tools ?? []).length} phone tools`)
      res.json({ agent_id: joined.agent_id, status: joined.status })
    } catch (e) {
      console.warn(`[call] ${session.id} could not start:`, (e as Error).message)
      res.status(502).json({ error: (e as Error).message })
    }
  })

  app.post('/session/stop', requireKey, async (req, res) => {
    const session = sessions.get(req.body?.session_id)
    if (session) await endCall(session)
    res.json({ ok: true })
  })

  app.post('/v1/chat/completions', chatCompletions(deps))
  // The app's typed chat: the same brain, written (chat.ts).
  chatRoutes(app, deps, requireKey)

  // WhatsApp: whether it's signed in, and the code to scan when it isn't.
  app.get('/whatsapp', requireKey, (_req, res) => {
    const wa = deps.whatsapp
    res.json({ enabled: !!wa, signed_in: !!wa?.connected, account: wa?.account ?? null, waiting_for_scan: !!wa?.qr })
  })
  app.get('/whatsapp/qr', requireKey, async (_req, res) => {
    const wa = deps.whatsapp
    const page = (body: string) => `<!doctype html><meta charset="utf-8"><meta http-equiv="refresh" content="5"><title>Luna · WhatsApp</title><body style="font:16px system-ui;display:grid;place-items:center;min-height:90vh;text-align:center">${body}</body>`
    if (!wa) return void res.status(503).send(page('<p>WhatsApp is turned off on this server (WHATSAPP=off).</p>'))
    if (wa.connected) return void res.send(page(`<p>✅ Signed in to WhatsApp as +${(wa.account ?? '').split('@')[0]}.</p>`))
    if (!wa.qr) return void res.send(page('<p>Waiting for WhatsApp to send a code…</p>'))
    const svg = await QRCode.toString(wa.qr, { type: 'svg', margin: 2, width: 320 })
    res.send(page(`<div><p>On the phone Luna sends from: WhatsApp → <b>Linked devices</b> → <b>Link a device</b>, then scan.</p>${svg}</div>`))
  })

  // The dashboard: everything at once, then each event as it happens.
  app.get('/hub/state', requireKey, (_req, res) => {
    res.json(hub.snapshot(clock()))
  })
  app.get('/hub/stream', requireKey, (req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' })
    const send = (event: unknown) => res.write(`data: ${JSON.stringify(event)}\n\n`)
    const beat = setInterval(() => res.write(': still here\n\n'), 25_000)
    hub.feed.on('event', send)
    req.on('close', () => {
      clearInterval(beat)
      hub.feed.off('event', send)
    })
  })

  // For rehearsals and the stage: run the after-the-call rules now rather than at their time.
  app.post('/demo/reset', requireKey, (_req, res) => {
    hub.reset()
    res.json({ ok: true })
  })
  const followUp = (options: Parameters<typeof runFollowUps>[3]) => async (_req: Request, res: Response) => {
    if (!deps.whatsapp?.connected) {
      res.status(503).json({ error: "WhatsApp isn't signed in on the server: open /whatsapp/qr and scan the code." })
      return
    }
    try {
      res.json({ ok: true, did: await runFollowUps(hub, deps.whatsapp, clock(), options) })
    } catch (e) {
      res.status(502).json({ error: (e as Error).message })
    }
  }
  app.post('/demo/missed-dose', requireKey, (req, res) => followUp({ forceDose: req.body?.dose_id || true })(req, res))
  app.post('/demo/nudge', requireKey, followUp({ forceNudge: true }))
  app.post('/demo/digest', requireKey, followUp({ forceDigest: true }))

  app.use(express.static(fileURLToPath(new URL('../public', import.meta.url))))

  async function endCall(session: Session) {
    if (session.ended) return
    sessions.end(session.id)
    hub.setMeta(`last_call_end:${session.userId}`, String(clock().getTime()))
    if (session.agentId) {
      await agents.leave(session.agentId).catch(e => console.warn(`[call] ${session.id} leave:`, (e as Error).message))
    }
    console.log(`[call] ${session.id} ended`)
  }

  /** The phone's bridge: WebSocket /bridge?session=…, with the app key as a header or ?key=. */
  function attachBridge(server: Server) {
    const sockets = new WebSocketServer({ noServer: true })
    server.on('upgrade', (req: IncomingMessage, socket: Duplex, head: Buffer) => {
      const url = new URL(req.url ?? '/', 'http://localhost')
      const session = sessions.get(url.searchParams.get('session'))
      const key = String(req.headers['x-app-key'] ?? url.searchParams.get('key') ?? '')
      if (url.pathname !== '/bridge' || !session || (config.appKey && !sameSecret(key, config.appKey))) {
        socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n')
        socket.destroy()
        return
      }
      sockets.handleUpgrade(req, socket, head, ws => {
        bridge.attach(session.id, ws)
        // Hosting proxies close quiet sockets after a minute or so.
        const ping = setInterval(() => ws.ping(), 20_000)
        ws.on('close', () => clearInterval(ping))
        console.log(`[bridge] ${session.id} connected`)
      })
    })
  }

  return { app, attachBridge, announce }
}

function socketBase(config: Config, req: Request): string {
  if (config.publicUrl) return config.publicUrl.replace(/^http/, 'ws')
  return `ws://${req.headers.host}`
}

/** Someone in her family, as the app shows them. */
function personJson(m: Member) {
  return { id: m.id, name: m.name, relation: m.relation, role: m.role, phone: m.phone ?? '', reachable: chatOf(m) !== null, added: m.added }
}

/** Luna's hello to someone whose number was just added, so her first message isn't from a stranger. */
export function introduction(hub: Hub, member: Member): string {
  const elder = hub.elder().name
  const how = 'Answer any way you like: the number shown, a few words in any language, a voice note, a photo or an emoji.'
  return member.role === 'provider'
    ? `👋 Hello ${member.name}! I'm *Luna*, ${elder}'s voice assistant. ${elder}'s orders and bookings for you will come here. ${how}`
    : `👋 Hi ${member.name}! I'm *Luna*, ${elder}'s voice assistant. ${elder} added you here${member.relation && member.relation !== 'family' ? ` as her ${member.relation}` : ''}. When she asks me to tell you something or needs your help, it comes to this chat, and I'll tell her what you say. ${how}`
}
