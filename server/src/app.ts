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
import type { Hub } from './hub.ts'
import { type ChatFn, chatCompletions, sameSecret } from './llm.ts'
import { buildSystemPrompt, greetingFor } from './prompt.ts'
import type { LiveContext, Session, Sessions } from './sessions.ts'
import { type WhatsApp, chatOf } from './whatsapp.ts'

export interface AppDeps {
  config: Config
  hub: Hub
  sessions: Sessions
  bridge: Bridge
  agents: AgentApi
  chat: ChatFn
  whatsapp: WhatsApp | null
  clock: () => Date
}

/**
 * The HTTP side. The phone: /family, /session/{token,start,stop}, the /bridge WebSocket and
 * /demo/*. Agora: /v1/chat/completions. Signing in to WhatsApp: /whatsapp/qr. The dashboard: /hub/*.
 */
export function createApp(deps: AppDeps) {
  const { config, hub, sessions, bridge, agents, clock } = deps
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
      members: hub.members().filter(m => m.role !== 'elder').map(m => ({ id: m.id, name: m.name, relation: m.relation, role: m.role, reachable: chatOf(m) !== null })),
      doses: hub.doseStates(now).map(d => ({ ...d.dose, state: d.state })),
    })
  })

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
