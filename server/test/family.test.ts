import assert from 'node:assert/strict'
import { test } from 'node:test'
import { announcer } from '../src/announce.ts'
import { Bridge } from '../src/bridge.ts'
import { runFollowUps } from '../src/followups.ts'
import { Sessions } from '../src/sessions.ts'
import { handleUpdate } from '../src/telegram.ts'
import { runServerTool } from '../src/tools.ts'
import { FakeAgents, FakeMessenger, FakePhone, at, makeHub } from './helpers.ts'

function family(now = at(9, 5)) {
  const hub = makeHub()
  const sessions = new Sessions()
  const bridge = new Bridge()
  const agents = new FakeAgents()
  const messenger = new FakeMessenger()
  const clock = () => now
  const announce = announcer({ hub, sessions, bridge, agents, clock })
  const deps = { hub, messenger, announce, clock }
  hub.linkTelegram('rahul', 555)
  hub.linkTelegram('ramesh-stores', 777)
  return { hub, sessions, bridge, agents, messenger, announce, deps, now }
}

function liveCall(f: ReturnType<typeof family>) {
  const session = f.sessions.create('lakshmi', 0, f.now.getTime())
  session.agentId = 'agent-1'
  const phone = new FakePhone()
  f.bridge.attach(session.id, phone)
  return { session, phone }
}

test('/start links a family member to their Telegram chat', async () => {
  const f = family()
  await handleUpdate({ message: { message_id: 1, chat: { id: 888, type: 'private' }, text: '/start priya' } }, f.deps)
  assert.equal(f.hub.member('priya')?.telegramChatId, 888)
  assert.match(f.messenger.sent[0].html, /Hi Priya/)
})

test("Rahul's \"I'll do it\" is said in her call at once and shown on her phone", async () => {
  const f = family()
  const { session, phone } = liveCall(f)
  const asked = await runServerTool('assign_task', { assignee: 'Rahul', task: "Take Amma to Dr. Rao's clinic", when: 'at 4:30' }, { hub: f.hub, messenger: f.messenger, session, now: f.now })
  assert.equal(asked.result.ok, true)
  const message = f.messenger.sent[0]

  await handleUpdate({ callback_query: { id: 'q1', from: { id: 555 }, data: 'task:1:yes', message: { message_id: message.id, chat: { id: 555 }, text: 'asked' } } }, f.deps)

  assert.equal(f.hub.task(1)?.status, 'accepted')
  assert.deepEqual(f.agents.spoken, [{ agentId: 'agent-1', text: "Rahul said yes: take Amma to Dr. Rao's clinic, 4:30 PM today." }])
  assert.equal(phone.received.find(m => m.type === 'hub_update')?.kind, 'task_reply')
  assert.match(session.actions.at(-1)!, /Rahul said yes/)
  assert.deepEqual(f.messenger.edits[0].buttons?.[0].map(b => b.data), ['task:1:done'])
  assert.equal(f.messenger.answers[0].id, 'q1')
})

test("the shop's confirmation and note reach her; with no call they wait for the next one", async () => {
  const f = family()
  const session = f.sessions.create('lakshmi', 0, f.now.getTime())
  await runServerTool('request_service', { provider: 'the grocery shop', what: 'Milk, 2 kg atta' }, { hub: f.hub, messenger: f.messenger, session, now: f.now })
  const order = f.messenger.sent[0]
  assert.equal(order.chatId, 777)

  await handleUpdate({ callback_query: { id: 'q2', from: { id: 777 }, data: 'req:1:ok', message: { message_id: order.id, chat: { id: 777 }, text: 'order' } } }, f.deps)
  await handleUpdate({ message: { message_id: 9, chat: { id: 777, type: 'private' }, from: { id: 777 }, text: 'Delivery by 11', reply_to_message: { message_id: order.id } } }, f.deps)

  assert.equal(f.hub.request(1)?.status, 'confirmed')
  assert.equal(f.hub.request(1)?.note, 'Delivery by 11')
  assert.equal(f.agents.spoken.length, 0, 'no bridge connected, so nothing is spoken')
  const news = f.hub.eventsSince(0).map(e => e.text)
  assert.ok(news.includes('Ramesh Stores confirmed: Milk, 2 kg atta.'))
  assert.ok(news.includes('Ramesh Stores says: Delivery by 11'))
})

test('assign_task refuses a shop and an unknown name, saying who there is', async () => {
  const f = family()
  const session = f.sessions.create('lakshmi', 0, f.now.getTime())
  const shop = await runServerTool('assign_task', { assignee: 'Ramesh Stores', task: 'Bring milk' }, { hub: f.hub, messenger: f.messenger, session, now: f.now })
  assert.match(String(shop.result.error), /request_service/)
  const nobody = await runServerTool('assign_task', { assignee: 'Suresh', task: 'Bring milk' }, { hub: f.hub, messenger: f.messenger, session, now: f.now })
  assert.match(String(nobody.result.error), /Rahul \(son\)/)
  assert.equal(f.messenger.sent.length, 0)
})

test("a dose not confirmed 30 minutes after its time is flagged to her caregiver, once", async () => {
  const f = family()
  assert.deepEqual(await runFollowUps(f.hub, f.messenger, at(9, 20)), [])
  const did = await runFollowUps(f.hub, f.messenger, at(9, 31))
  assert.equal(did.length, 1)
  assert.equal(f.messenger.sent[0].chatId, 555)
  assert.match(f.messenger.sent[0].html, /hasn't confirmed the 9:00 AM BP tablet/)
  assert.deepEqual(await runFollowUps(f.hub, f.messenger, at(9, 32)), [])
})

test('a dose she said she took is never flagged; "not yet" can be flagged on demand', async () => {
  const f = family(at(9, 2))
  const session = f.sessions.create('lakshmi', 0, f.now.getTime())
  session.context = { purpose: 'checkin', dose_id: 'bp-am' }
  await runServerTool('log_checkin', { kind: 'medicine', ok: false, detail: 'after breakfast' }, { hub: f.hub, messenger: f.messenger, session, now: f.now })
  assert.equal(f.hub.doseStates(at(9, 2))[0].state, 'not_yet')
  const forced = await runFollowUps(f.hub, f.messenger, at(9, 3), { forceDose: true })
  assert.equal(forced.length, 1)

  await runServerTool('log_checkin', { kind: 'tablet', ok: true }, { hub: f.hub, messenger: f.messenger, session, now: at(9, 40) })
  assert.match(f.messenger.sent.at(-1)!.html, /has taken the BP tablet \(9:00 AM\) now/)
  assert.deepEqual(await runFollowUps(f.hub, f.messenger, at(9, 45), { forceDose: 'bp-am' }), [])
})

test('an unanswered task is asked again, then the family is told', async () => {
  const f = family()
  f.hub.linkTelegram('priya', 888)
  const session = f.sessions.create('lakshmi', 0, f.now.getTime())
  await runServerTool('assign_task', { assignee: 'Rahul', task: 'Pick up my medicines' }, { hub: f.hub, messenger: f.messenger, session, now: f.now })
  assert.deepEqual(await runFollowUps(f.hub, f.messenger, at(9, 29)), [])
  assert.match((await runFollowUps(f.hub, f.messenger, at(10, 6)))[0], /nudged Rahul/)
  assert.match((await runFollowUps(f.hub, f.messenger, at(10, 22)))[0], /told the family/)
  assert.equal(f.messenger.sent.at(-1)!.chatId, 888)
})
