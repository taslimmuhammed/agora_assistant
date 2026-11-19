import assert from 'node:assert/strict'
import { test } from 'node:test'
import { runFollowUps } from '../src/followups.ts'
import { RAHUL, at, family, incoming, liveCall } from './helpers.ts'

// The family's side, over WhatsApp: what Luna sends them, and what their answers do.

test("Rahul's \"1\" is said in her call at once and shown on her phone", async () => {
  const f = family()
  const { session, phone } = liveCall(f)
  const asked = await f.tool('assign_task', { assignee: 'Rahul', task: "Take Amma to Dr. Rao's clinic", when: 'at 4:30' }, session)
  assert.equal(asked.result.ok, true)
  const message = f.socket.sent[0]
  assert.equal(message.jid, RAHUL)
  assert.match(message.text, /\*Take Amma to Dr\. Rao's clinic\*\n🕓 4:30 PM today/)
  assert.match(message.text, /Reply with a number:\n\*1\* {2}👍 I'll do it\n\*2\* {2}🙅 Can't$/)

  await f.whatsapp.receive(incoming(f.hub, 'rahul', '1'))

  assert.equal(f.hub.task(1)?.status, 'accepted')
  assert.deepEqual(f.agents.spoken, [{ agentId: 'agent-1', text: "Rahul said yes: take Amma to Dr. Rao's clinic, 4:30 PM today." }])
  assert.equal(phone.received.find(m => m.type === 'hub_update')?.kind, 'task_reply')
  assert.match(session.actions.at(-1)!, /Rahul said yes/)
  const thanks = f.socket.sent[1]
  assert.equal(thanks.quoted, message.id)
  assert.match(thanks.text, /\*1\* {2}✅ Done/)

  await f.whatsapp.receive(incoming(f.hub, 'rahul', 'done'))
  assert.equal(f.hub.task(1)?.status, 'done')
  assert.equal(f.agents.spoken.at(-1)?.text, "Rahul has done it: take Amma to Dr. Rao's clinic.")
})

test("the shop's confirmation and note reach her; with no call they wait for the next one", async () => {
  const f = family()
  await f.tool('request_service', { provider: 'the grocery shop', what: 'Milk, 2 kg atta' })
  const order = f.socket.sent[0]
  assert.equal(order.jid, '919000000004@s.whatsapp.net')
  assert.match(order.text, /\*1\* {2}✅ Confirm\n\*2\* {2}⏱ Within the hour\n\*3\* {2}🕐 Another time/)

  await f.whatsapp.receive(incoming(f.hub, 'ramesh-stores', 'ok'))
  await f.whatsapp.receive(incoming(f.hub, 'ramesh-stores', 'Delivery by 11'))

  assert.equal(f.hub.request(1)?.status, 'confirmed')
  assert.equal(f.hub.request(1)?.note, 'Delivery by 11')
  assert.equal(f.agents.spoken.length, 0, 'no call, so nothing is spoken')
  const news = f.hub.eventsSince(0).map(e => e.text)
  assert.ok(news.includes('Ramesh Stores confirmed: Milk, 2 kg atta.'))
  assert.ok(news.includes('Ramesh Stores says: Delivery by 11'))
  assert.match(f.socket.sent.at(-1)!.text, /Luna will tell Lakshmi/)
})

test('assign_task refuses a shop and an unknown name, saying who there is', async () => {
  const f = family()
  const shop = await f.tool('assign_task', { assignee: 'Ramesh Stores', task: 'Bring milk' })
  assert.match(String(shop.result.error), /request_service/)
  const nobody = await f.tool('assign_task', { assignee: 'Suresh', task: 'Bring milk' })
  assert.match(String(nobody.result.error), /Rahul \(son\)/)
  assert.equal(f.socket.sent.length, 0)
})

test('without WhatsApp signed in, the tool says nothing was sent', async () => {
  const f = family()
  f.whatsapp.detach()
  const asked = await f.tool('assign_task', { assignee: 'Rahul', task: 'Bring milk' })
  assert.equal(asked.result.ok, false)
  assert.match(String(asked.result.error), /couldn't be sent/)
})

test('a dose not confirmed 30 minutes after its time is flagged to her caregiver, once, and he can answer for her', async () => {
  const f = family()
  assert.deepEqual(await runFollowUps(f.hub, f.whatsapp, at(9, 20)), [])
  const did = await runFollowUps(f.hub, f.whatsapp, at(9, 31))
  assert.equal(did.length, 1)
  const alert = f.socket.sent[0]
  assert.equal(alert.jid, RAHUL)
  assert.match(alert.text, /hasn't confirmed the 9:00 AM BP tablet/)
  assert.match(alert.text, /Call her: \+91 90000 00001/)
  assert.deepEqual(await runFollowUps(f.hub, f.whatsapp, at(9, 32)), [])

  await f.whatsapp.receive(incoming(f.hub, 'rahul', 'she took it'))
  assert.equal(f.hub.doseStates(at(9, 33))[0].state, 'taken')
  assert.ok(f.hub.eventsSince(0).some(e => e.text === 'Rahul confirmed you took your BP tablet.'))
})

test('a dose she said she took is never flagged; "not yet" can be flagged on demand', async () => {
  const f = family(at(9, 2))
  const session = f.sessions.create('lakshmi', 0, f.now.getTime())
  session.context = { purpose: 'checkin', dose_id: 'bp-am' }
  await f.tool('log_checkin', { kind: 'medicine', ok: false, detail: 'after breakfast' }, session)
  assert.equal(f.hub.doseStates(at(9, 2))[0].state, 'not_yet')
  const forced = await runFollowUps(f.hub, f.whatsapp, at(9, 3), { forceDose: true })
  assert.equal(forced.length, 1)

  await f.tool('log_checkin', { kind: 'tablet', ok: true }, session, at(9, 40))
  assert.match(f.socket.sent.at(-1)!.text, /has taken the BP tablet \(9:00 AM\) now/)
  assert.deepEqual(await runFollowUps(f.hub, f.whatsapp, at(9, 45), { forceDose: 'bp-am' }), [])
})

test('an unanswered task is asked again under the first message, then the family is told', async () => {
  const f = family()
  await f.tool('assign_task', { assignee: 'Rahul', task: 'Pick up my medicines' })
  assert.deepEqual(await runFollowUps(f.hub, f.whatsapp, at(9, 29)), [])
  assert.match((await runFollowUps(f.hub, f.whatsapp, at(10, 6)))[0], /nudged Rahul/)
  const nudge = f.socket.sent.find(m => /waiting to hear from you/.test(m.text))!
  assert.equal(nudge.quoted, f.socket.sent[0].id)
  assert.match((await runFollowUps(f.hub, f.whatsapp, at(10, 22)))[0], /told the family/)
  assert.equal(f.socket.sent.at(-1)!.jid, '919000000003@s.whatsapp.net', 'Priya')
})
