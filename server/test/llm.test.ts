import assert from 'node:assert/strict'
import { test } from 'node:test'
import { Bridge } from '../src/bridge.ts'
import { ToolCallAccumulator, runTurn } from '../src/llm.ts'
import { Sessions } from '../src/sessions.ts'
import { FakeMessenger, FakePhone, at, makeHub, scriptedChat, testConfig, text, toolCall } from './helpers.ts'

const ADD_TASK = { name: 'add_task', description: 'A reminder', parameters: { type: 'object', properties: { title: { type: 'string' } }, required: ['title'] } }

function setup(rounds: Parameters<typeof scriptedChat>[0], phone = new FakePhone(), bridgeTimeout = 8_000) {
  const hub = makeHub()
  const sessions = new Sessions()
  const bridge = new Bridge(bridgeTimeout)
  const messenger = new FakeMessenger()
  const chat = scriptedChat(rounds)
  const session = sessions.create('lakshmi', 0, at(9).getTime())
  session.context = { user_name: 'Lakshmi', device_tools: [ADD_TASK], memory: 'About the user: likes filter coffee.' }
  bridge.attach(session.id, phone)
  const deps = { config: testConfig(), hub, sessions, bridge, messenger, chat, clock: () => at(9, 5) }
  let said = ''
  const out = { text: (t: string) => { said += t } }
  return { deps, session, phone, chat, messenger, hub, out, said: () => said }
}

const USER = [{ role: 'user', content: 'Remind me to call Meena tomorrow at 9', turn_id: 7 }]

test("OpenAI-style pieces of one call come together under their index", () => {
  const calls = new ToolCallAccumulator()
  calls.add([{ index: 0, id: 'call_a', function: { name: 'add_task', arguments: '{"tit' } }])
  calls.add([{ index: 0, function: { arguments: 'le":"Milk"}' } }])
  const list = calls.list()
  assert.equal(list.length, 1)
  assert.equal(list[0].id, 'call_a')
  assert.deepEqual(JSON.parse(list[0].function.arguments), { title: 'Milk' })
})

test("Gemini-style whole calls under one index stay apart, get ids, and keep their signature", () => {
  const calls = new ToolCallAccumulator()
  calls.add([{ index: 0, function: { name: 'edit_note', arguments: '{"note":"list"}' }, extra_content: { google: { thought_signature: 'sig' } } }])
  calls.add([{ index: 0, function: { name: 'request_service', arguments: '{"provider":"shop"}' } }])
  const list = calls.list()
  assert.deepEqual(list.map(c => c.function.name), ['edit_note', 'request_service'])
  assert.ok(list.every(c => c.id))
  assert.notEqual(list[0].id, list[1].id)
  assert.deepEqual(list[0].extra_content, { google: { thought_signature: 'sig' } })
})

test('a phone tool runs on the phone and the model hears its result', async () => {
  const phone = new FakePhone(() => ({ ok: true, id: 42, due: 'Sun 22 Nov 09:00' }))
  const t = setup([[toolCall('add_task', { title: 'Call Meena', when: 'tomorrow at 9' })], [text("Done, I'll remind you tomorrow at 9.")]], phone)
  await runTurn(t.deps, t.session, USER, t.out, new AbortController().signal)

  assert.equal(t.said(), "Okay. Done, I'll remind you tomorrow at 9.")
  const call = t.phone.received.find(m => m.type === 'tool_call')!
  assert.equal(call.name, 'add_task')
  assert.deepEqual(call.args, { title: 'Call Meena', when: 'tomorrow at 9' })
  assert.equal(call.user_text, USER[0].content)
  assert.equal(call.turn_id, '7')

  const second = t.chat.requests[1].messages as Array<Record<string, unknown>>
  const assistant = second.at(-2)!
  const tool = second.at(-1)!
  assert.equal(assistant.role, 'assistant')
  assert.equal(tool.role, 'tool')
  assert.equal(tool.tool_call_id, (assistant.tool_calls as Array<{ id: string }>)[0].id)
  assert.match(String(tool.content), /"id":42/)
  assert.equal(t.session.actions.length, 1)
})

test('both tool sets are offered, and the system message is rebuilt with the phone memory', async () => {
  const t = setup([[text('Hello!')]])
  await runTurn(t.deps, t.session, [{ role: 'system', content: "Agora's copy" }, ...USER], t.out, new AbortController().signal)
  const request = t.chat.requests[0]
  const names = (request.tools as Array<{ function: { name: string } }>).map(x => x.function.name)
  assert.ok(names.includes('assign_task') && names.includes('add_task'))
  const messages = request.messages as Array<{ role: string; content: string }>
  assert.equal(messages.filter(m => m.role === 'system').length, 1)
  assert.match(messages[0].content, /likes filter coffee/)
  assert.match(messages[0].content, /Rahul: son, her caregiver/)
})

test('a server tool acts, shows a card on the phone, and is remembered for the call', async () => {
  const t = setup([[toolCall('assign_task', { assignee: 'my son', task: "Take Amma to Dr. Rao's clinic", when: 'at 4:30' })], [text("I've asked Rahul.")]])
  t.hub.linkTelegram('rahul', 555)
  await runTurn(t.deps, t.session, [{ role: 'user', content: 'Ask Rahul to take me to Dr. Rao at 4:30' }], t.out, new AbortController().signal)

  const message = t.messenger.sent[0]
  assert.equal(message.chatId, 555)
  assert.match(message.html, /Take Amma to Dr\. Rao's clinic/)
  assert.match(message.html, /4:30 PM today/)
  assert.deepEqual(message.buttons?.[0].map(b => b.data), ['task:1:yes', 'task:1:no'])
  const card = t.phone.received.find(m => m.type === 'action')!
  assert.equal(card.title, 'Asked Rahul')
  assert.match(t.session.actions[0], /asked Rahul/)
  assert.equal(t.hub.task(1)?.telegramMessageId, message.id)
})

test("a phone that doesn't answer becomes an error the model can explain", async () => {
  const t = setup([[toolCall('add_task', { title: 'Milk' })], [text("Sorry, your phone didn't respond.")]], new FakePhone(() => null), 50)
  await runTurn(t.deps, t.session, USER, t.out, new AbortController().signal)
  const tool = (t.chat.requests[1].messages as Array<{ role: string; content: string }>).at(-1)!
  assert.match(tool.content, /didn't answer in time/)
})

test('the last round must answer in words', async () => {
  const loop = Array.from({ length: 5 }, () => [toolCall('family_status', {})])
  const t = setup([...loop, [text('Everything is on track.')]])
  await runTurn(t.deps, t.session, USER, t.out, new AbortController().signal)
  assert.equal(t.chat.requests.length, 5)
  assert.equal(t.chat.requests.at(-1)!.tool_choice, 'none')
})
