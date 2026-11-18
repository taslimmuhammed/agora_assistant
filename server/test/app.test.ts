import assert from 'node:assert/strict'
import type { AddressInfo } from 'node:net'
import { after, before, test } from 'node:test'
import { WebSocket } from 'ws'
import { createApp } from '../src/app.ts'
import { Bridge } from '../src/bridge.ts'
import { Sessions } from '../src/sessions.ts'
import { FakeAgents, FakeMessenger, at, makeHub, scriptedChat, testConfig, text, toolCall } from './helpers.ts'

// The whole call over HTTP, as the phone and Agora make it: token, bridge, start, one turn.

const agents = new FakeAgents()
const chat = scriptedChat([[toolCall('set_alarm', { when: '6:30' })], [text('Your alarm is set for 6:30.')]])
const { app, attachBridge } = createApp({
  config: testConfig(),
  hub: makeHub(),
  sessions: new Sessions(),
  bridge: new Bridge(),
  agents,
  chat,
  messenger: new FakeMessenger(),
  clock: () => at(9, 5),
})
const server = app.listen(0)
attachBridge(server)
let base = ''

before(() => {
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
})
after(() => {
  server.close()
  server.closeAllConnections()
})

const post = (path: string, body: unknown, headers: Record<string, string> = { 'X-App-Key': 'app-key' }) =>
  fetch(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) })

test('a call: token, bridge, agent joined with Murf and our LLM, then one streamed turn with a phone tool', async () => {
  assert.equal((await post('/session/token', {}, {})).status, 401)

  const token = await (await post('/session/token', { user_id: 'lakshmi' })).json()
  assert.equal(token.uid, 1001)
  assert.equal(token.agent_uid, 2001)
  assert.match(token.token, /^007/)
  assert.equal(token.bridge_url, `wss://luna.example/bridge?session=${token.session_id}`)

  // The phone's end of the bridge: runs set_alarm and answers.
  const phone = new WebSocket(`${base.replace('http', 'ws')}/bridge?session=${token.session_id}&key=app-key`)
  const calls: Array<Record<string, unknown>> = []
  phone.on('message', data => {
    const message = JSON.parse(String(data))
    if (message.type !== 'tool_call') return
    calls.push(message)
    phone.send(JSON.stringify({ type: 'tool_result', call_id: message.call_id, result: '{"ok":true,"alarm":"6:30 AM tomorrow"}', chip: { title: 'Alarm', detail: '6:30 AM' } }))
  })
  await new Promise(resolve => phone.on('open', resolve))

  const context = { user_name: 'Lakshmi', purpose: 'user', device_tools: [{ name: 'set_alarm', description: 'An alarm', parameters: { type: 'object', properties: { when: { type: 'string' } } } }] }
  const started = await (await post('/session/start', { session_id: token.session_id, context })).json()
  assert.equal(started.agent_id, 'agent-1')
  const join = agents.joins[0] as { properties: Record<string, any> }
  assert.equal(join.properties.llm.url, `https://luna.example/v1/chat/completions?session=${token.session_id}`)
  assert.equal(join.properties.llm.api_key, 'proxy-secret')
  assert.equal(join.properties.tts.vendor, 'murf')
  assert.equal(join.properties.remote_rtc_uids[0], '1001')
  assert.equal(join.properties.advanced_features.enable_rtm, true)
  assert.match(join.properties.llm.greeting_message, /Lakshmi/)

  const turn = `/v1/chat/completions?session=${token.session_id}`
  const message = { stream: true, messages: [{ role: 'user', content: 'Set an alarm for 6:30' }] }
  assert.equal((await post(turn, message, { Authorization: 'Bearer wrong' })).status, 401)

  const reply = await post(turn, message, { Authorization: 'Bearer proxy-secret' })
  assert.equal(reply.headers.get('content-type'), 'text/event-stream')
  const events = (await reply.text()).split('\n\n').filter(Boolean)
  assert.equal(events.at(-1), 'data: [DONE]')
  const said = events.slice(0, -1).map(e => JSON.parse(e.slice(6)).choices[0].delta.content ?? '').join('')
  assert.equal(said, 'Okay. Your alarm is set for 6:30.')
  assert.equal(calls[0].name, 'set_alarm')

  assert.deepEqual(await (await post('/session/stop', { session_id: token.session_id })).json(), { ok: true })
  assert.deepEqual(agents.left, ['agent-1'])
  phone.close()
})

test('the family page lists the medicines for the check-in calls', async () => {
  const family = await (await fetch(`${base}/family`, { headers: { 'X-App-Key': 'app-key' } })).json()
  assert.equal(family.elder.name, 'Lakshmi')
  assert.deepEqual(family.doses.map((d: { id: string }) => d.id), ['bp-am', 'bp-pm'])
  assert.ok(!family.members.some((m: { role: string }) => m.role === 'elder'))
})
