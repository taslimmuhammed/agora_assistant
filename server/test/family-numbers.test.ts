import assert from 'node:assert/strict'
import type { AddressInfo } from 'node:net'
import { after, before, test } from 'node:test'
import { createApp } from '../src/app.ts'
import { Bridge } from '../src/bridge.ts'
import { Sessions } from '../src/sessions.ts'
import { FakeAgents, at, fakeWhatsApp, makeHub, scriptedChat, testConfig } from './helpers.ts'

// The app picks the family's numbers from her contacts, and adds people: kept on the server.

const hub = makeHub()
const { whatsapp, socket } = fakeWhatsApp(hub)
const { app } = createApp({
  config: testConfig(), hub, sessions: new Sessions(), bridge: new Bridge(), agents: new FakeAgents(),
  chat: scriptedChat([]), whatsapp, clock: () => at(9, 5),
})
const server = app.listen(0)
let base = ''
before(() => {
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
})
after(() => {
  server.close()
  server.closeAllConnections()
})

const call = (method: string, path: string, body?: unknown) =>
  fetch(base + path, { method, headers: { 'Content-Type': 'application/json', 'X-App-Key': 'app-key' }, body: body === undefined ? undefined : JSON.stringify(body) })
const family = async () => (await (await call('GET', '/family')).json()).members as Array<{ id: string; phone: string; added: boolean }>

test('a number picked from her contacts is saved over the seed, and Luna says hello there', async () => {
  const saved = await (await call('PUT', '/family/members/priya/phone', { phone: '098765 43210', introduce: true })).json()
  assert.equal(saved.phone, '098765 43210')
  assert.equal(saved.on_whatsapp, true)
  assert.equal(saved.introduced, true)
  const hello = socket.sent.at(-1)!
  assert.equal(hello.jid, '919876543210@s.whatsapp.net', 'the leading 0 dropped, +91 added')
  assert.match(hello.text, /Hi Priya! I'm \*Luna\*, Lakshmi's voice assistant/)
  assert.equal((await family()).find(m => m.id === 'priya')?.phone, '098765 43210')

  // The same number again: no second hello.
  const again = await (await call('PUT', '/family/members/priya/phone', { phone: '+91 98765 43210', introduce: true })).json()
  assert.equal(again.introduced, false)
  assert.equal((await call('PUT', '/family/members/priya/phone', { phone: '123' })).status, 400)
  assert.equal((await call('PUT', '/family/members/nobody/phone', { phone: '+91 98765 43210' })).status, 404)
  assert.equal((await call('PUT', '/family/members/lakshmi/phone', { phone: '+91 98765 43210' })).status, 404, 'not her own')
})

test('someone new is added from the contacts, found by name in a call, and can be removed', async () => {
  socket.missing.add('919811122233@s.whatsapp.net')
  const res = await call('POST', '/family/members', { name: 'Meera Iyer', relation: 'neighbour', role: 'family', phone: '+91 98111 22233', introduce: true })
  assert.equal(res.status, 201)
  const meera = await res.json()
  assert.equal(meera.id, 'meera-iyer')
  assert.equal(meera.added, true)
  assert.equal(meera.on_whatsapp, false, 'the app says so')
  assert.equal(meera.introduced, false, 'no hello to a number without WhatsApp')
  assert.equal(hub.findMember('my neighbour')?.id, 'meera-iyer')

  assert.equal((await call('POST', '/family/members', { name: 'Again', phone: '+91 98111 22233' })).status, 409, 'that number is Meera’s')
  assert.equal((await call('POST', '/family/members', { name: 'Shop', role: 'boss', phone: '+91 98111 22244' })).status, 400)
  assert.equal((await call('DELETE', '/family/members/rahul')).status, 400, 'the seed’s people stay')
  assert.equal((await call('DELETE', '/family/members/meera-iyer')).status, 200)
  assert.ok(!(await family()).some(m => m.id === 'meera-iyer'))
})

test('someone added without a relation is greeted plainly, and asked for help from the chat', async () => {
  const filu = await (await call('POST', '/family/members', { name: 'Filu', phone: '+91 79079 75494', introduce: true })).json()
  assert.equal(filu.introduced, true)
  const hello = socket.sent.at(-1)!
  assert.match(hello.text, /Lakshmi added you here\. When she asks/, 'not "as her family"')

  const res = await call('POST', '/family/tool', { name: 'assign_task', args: { assignee: 'Filu', task: 'Bring my BP tablets', when: 'tomorrow evening' } })
  assert.equal(res.status, 200)
  assert.equal((await res.json()).asked, 'Filu')
  const ask = socket.sent.at(-1)!
  assert.equal(ask.jid, '917907975494@s.whatsapp.net')
  assert.match(ask.text, /Lakshmi\* asked you, through Luna:\n\n\*Bring my BP tablets\*/)
  assert.match(ask.text, /\*1\*.*I'll do it/)

  assert.equal((await call('POST', '/family/tool', { name: 'assign_task', args: { assignee: 'Nobody', task: 'x' } })).status, 400)
  assert.equal((await call('POST', '/family/tool', { name: 'set_alarm', args: {} })).status, 400, 'only the family tools')
  await call('DELETE', `/family/members/${filu.id}`)
})

test('the phone gets the answers that came outside a call, once, and knows when to look again soon', async () => {
  const answers = async (after?: number) => (await (await call('GET', `/family/answers${after === undefined ? '' : `?after=${after}`}`)).json()) as {
    answers: Array<{ id: number; kind: string; text: string; told: boolean }>; last: number; waiting: boolean
  }
  const start = (await answers()).last
  const asked = await call('POST', '/family/tool', { name: 'message_family', args: { to: 'Rahul', text: 'Can you bring my BP tablets?' } })
  assert.equal(asked.status, 200)
  assert.equal((await asked.json()).sent_to, 'Rahul')
  assert.equal((await answers(start)).waiting, true, 'a message just went: an answer may come')

  const heard = hub.addEvent('task_reply', 'Rahul said yes: take you to the clinic.', null, at(9, 6).getTime())
  hub.markTold(heard.id)
  hub.addEvent('reply', 'Rahul says: Yes, I will bring them at 6.', { from: 'rahul' }, at(9, 7).getTime())
  hub.addEvent('call', 'Lakshmi called Luna.', null, at(9, 7).getTime())
  const got = await answers(start)
  assert.deepEqual(got.answers.map(a => [a.text, a.told]), [
    ['Rahul said yes: take you to the clinic.', true],
    ['Rahul says: Yes, I will bring them at 6.', false],
  ])
  assert.deepEqual((await answers(got.last)).answers, [], 'nothing new')

  // A name not in the family: nothing sent, nothing logged, and the phone opens WhatsApp itself.
  const before = (await answers()).last
  const res = await call('POST', '/family/tool', { name: 'message_family', args: { to: 'Anjali', text: 'Hi' } })
  assert.equal(res.status, 400)
  assert.match((await res.json()).error, /No one called "Anjali"/)
  assert.equal((await answers()).last, before)
})

test('opening the app, she hears one message about what came, not each answer as it was written', async () => {
  const start = (await (await call('GET', '/family/answers')).json()).last as number
  const empty = await (await call('POST', '/family/briefing', { after: start })).json()
  assert.deepEqual(empty, { text: null, up_to: null, audio: null }, 'nothing new: nothing said')

  const heard = hub.addEvent('reply', 'Rahul says: Heard in the call.', null, at(9, 6).getTime())
  hub.markTold(heard.id)
  hub.addEvent('reply', 'Rahul says: Sure, I will call.', { from: 'rahul' }, at(9, 7).getTime())
  const alert = hub.addEvent('alert', "I let Rahul know you hadn't confirmed your 9:00 AM BP tablet.", null, at(9, 8).getTime())
  hub.addEvent('reply', 'Rahul says: Later than the phone asked for.', null, at(9, 9).getTime())

  const got = await (await call('POST', '/family/briefing', { after: start, up_to: alert.id, audio: false })).json()
  assert.equal(got.text, "Good morning, Lakshmi! While you were away: Rahul says: Sure, I will call. I let Rahul know you hadn't confirmed your 9:00 AM BP tablet.")
  assert.equal(got.up_to, alert.id)
  assert.equal(got.audio, null)
  const inApp = await (await call('POST', '/family/briefing', { after: alert.id, greet: false, audio: false })).json()
  assert.equal(inApp.text, 'Rahul says: Later than the phone asked for.', 'already in the app: no greeting')
})

test('the phone WhatsApps anyone through Luna: asked for the number when no one in the hub has that name', async () => {
  const unknown = await call('POST', '/family/whatsapp', { to: 'Anil', text: 'Happy birthday!' })
  assert.equal(unknown.status, 404)
  assert.equal((await unknown.json()).unknown, true)
  const res = await call('POST', '/family/whatsapp', { to: 'Anil', name: 'Anil Kumar', phone: '+91 98222 33344', text: 'Happy birthday!' })
  assert.equal(res.status, 200)
  const sent = await res.json()
  assert.equal(sent.sent_to, 'Anil Kumar')
  assert.equal(sent.card.title, 'WhatsApp to Anil Kumar')
  assert.equal(socket.sent.at(-1)!.jid, '919822233344@s.whatsapp.net')
  assert.ok(!(await family()).some(m => m.id === 'anil-kumar'), "contacts aren't listed with her family")
  assert.equal((await call('POST', '/family/whatsapp', { to: 'Anil', text: '' })).status, 400)
})
