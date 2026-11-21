import assert from 'node:assert/strict'
import { test } from 'node:test'
import { Bridge } from '../src/bridge.ts'
import { type LlmDeps, ToolCallAccumulator, runTurn } from '../src/llm.ts'
import { Sessions } from '../src/sessions.ts'
import { FakePhone, RAHUL, at, fakeWhatsApp, makeHub, scriptedChat, testConfig, text, toolCall } from './helpers.ts'

const ADD_TASK = { name: 'add_task', description: 'A reminder', parameters: { type: 'object', properties: { title: { type: 'string' } }, required: ['title'] } }

function setup(rounds: Parameters<typeof scriptedChat>[0], phone = new FakePhone(), bridgeTimeout = 8_000) {
  const hub = makeHub()
  const sessions = new Sessions()
  const bridge = new Bridge(bridgeTimeout)
  const { whatsapp, socket } = fakeWhatsApp(hub)
  const chat = scriptedChat(rounds)
  const session = sessions.create('lakshmi', 0, at(9).getTime())
  session.context = { user_name: 'Lakshmi', device_tools: [ADD_TASK], memory: 'About the user: likes filter coffee.' }
  bridge.attach(session.id, phone)
  const deps: LlmDeps = { config: testConfig(), hub, sessions, bridge, whatsapp, chat, clock: () => at(9, 5) }
  let said = ''
  const out = { text: (t: string) => { said += t } }
  return { deps, session, phone, chat, socket, hub, out, said: () => said }
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
  await runTurn(t.deps, t.session, [{ role: 'user', content: 'Ask Rahul to take me to Dr. Rao at 4:30' }], t.out, new AbortController().signal)

  const message = t.socket.sent[0]
  assert.equal(message.jid, RAHUL)
  assert.match(message.text, /Take Amma to Dr\. Rao's clinic/)
  assert.match(message.text, /4:30 PM today/)
  assert.match(message.text, /\*1\* {2}👍 I'll do it/)
  const card = t.phone.received.find(m => m.type === 'action')!
  assert.equal(card.title, 'Asked Rahul')
  assert.match(t.session.actions[0], /asked Rahul/)
  assert.equal(t.hub.task(1)?.messageId, message.id)
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

test("a history that ends with Luna's words, as a line typed over her leaves it, is sent ending with hers", async () => {
  const t = setup([[text('Milk, eggs and bread.')]])
  const typedOver = [
    { role: 'user', content: 'Add milk to my shopping list' },
    { role: 'user', content: "What's on my shopping list?" },
    { role: 'assistant', content: "Done, I've added milk to your" },
  ]
  await runTurn(t.deps, t.session, typedOver, t.out, new AbortController().signal)
  const sent = (t.chat.requests[0].messages as Array<{ role: string; content: string }>).slice(1)
  assert.deepEqual(sent.map(m => m.role), ['user', 'assistant', 'user'])
  assert.equal(sent.at(-1)!.content, "What's on my shopping list?")
  assert.equal(t.said(), 'Milk, eggs and bread.')
})

test('a list changed in the call is in the next turn as it is now', async () => {
  const shopping = (items: string[]) => [{ title: 'Shopping list', items: items.map(text => ({ text, done: false })) }]
  const phone = new FakePhone(() => ({ ok: true }), call => (call.name === 'edit_note' ? { lists: shopping(['Bread', 'Milk']) } : {}))
  const t = setup([[toolCall('edit_note', { note: 'Shopping list', add: ['Milk'] })], [text('Added milk.')], [text('Bread and milk.')]], phone)
  t.session.context.device_tools = [{ name: 'edit_note', description: 'Lists', parameters: { type: 'object', properties: {} } }]
  t.session.context.lists = shopping(['Bread'])

  await runTurn(t.deps, t.session, [{ role: 'user', content: 'Add milk to my shopping list' }], t.out, new AbortController().signal)
  await runTurn(t.deps, t.session, [{ role: 'user', content: "What's on my shopping list?" }], t.out, new AbortController().signal)

  const system = String((t.chat.requests[2].messages as Array<{ content: string }>)[0].content)
  assert.match(system, /Shopping list: Bread, Milk/)
})

test("a photo the phone's tool found is shown to the model, after the tool's result", async () => {
  const card = { mime: 'image/jpeg', data: 'UEFOIGNhcmQ=' }
  const phone = new FakePhone(() => ({ ok: true, found: 1 }), call => (call.name === 'find_photos' ? { image: card } : {}))
  const t = setup([[toolCall('find_photos', { query: 'PAN card', question: 'What is my PAN number?' })], [text('Your PAN card shows BQXPM4821K.')]], phone)
  t.session.context.device_tools = [{ name: 'find_photos', description: 'Photos', parameters: { type: 'object', properties: {} } }]

  await runTurn(t.deps, t.session, [{ role: 'user', content: 'What is my PAN number?' }], t.out, new AbortController().signal)

  const sent = t.chat.requests[1].messages as Array<{ role: string; content: unknown }>
  assert.deepEqual(sent.slice(-3).map(m => m.role), ['assistant', 'tool', 'user'])
  const parts = sent.at(-1)!.content as Array<{ type: string; text?: string; image_url?: { url: string } }>
  assert.match(parts[0].text!, /find_photos found/)
  assert.match(parts[0].text!, /Never make up a number/)
  assert.equal(parts[1].image_url!.url, 'data:image/jpeg;base64,UEFOIGNhcmQ=')
})

test('a tool result without a photo, or with something else under image, adds no message', async () => {
  const phone = new FakePhone(() => ({ ok: true }), () => ({ image: { mime: 'text/plain', data: 'x' } }))
  const t = setup([[toolCall('find_photos', { query: 'beach' })], [text('Here they are.')]], phone)
  t.session.context.device_tools = [{ name: 'find_photos', description: 'Photos', parameters: { type: 'object', properties: {} } }]

  await runTurn(t.deps, t.session, [{ role: 'user', content: 'Beach photos' }], t.out, new AbortController().signal)

  const sent = t.chat.requests[1].messages as Array<{ role: string }>
  assert.equal(sent.at(-1)!.role, 'tool')
})

test("without the phone's own web_lookup, the server looks it up with Google and shows a card", async () => {
  const t = setup([[toolCall('web_lookup', { query: 'weather in Kochi today' })], [text('It will rain this evening, says the IMD.')]])
  const asked: string[] = []
  t.deps.webLookup = {
    ready: () => true,
    find: async query => {
      asked.push(query)
      return { answer: 'Rain likely after 5 pm, 27°C.', sources: [{ title: 'imd.gov.in', url: 'https://example.com/r' }] }
    },
  }
  await runTurn(t.deps, t.session, [{ role: 'user', content: "What's the weather in Kochi today?" }], t.out, new AbortController().signal)

  const offered = (t.chat.requests[0].tools as Array<{ function: { name: string } }>).map(f => f.function.name)
  assert.ok(offered.includes('web_lookup'))
  assert.deepEqual(asked, ['weather in Kochi today'])
  const tool = (t.chat.requests[1].messages as Array<{ role: string; content: string }>).at(-1)!
  assert.match(tool.content, /Rain likely after 5 pm/)
  assert.match(tool.content, /imd\.gov\.in/)
  const card = t.phone.received.find(m => m.type === 'action')!
  assert.equal(card.tool, 'web_lookup')
})

test("a phone with its own web_lookup keeps it", async () => {
  const t = setup([[toolCall('web_lookup', { query: 'gold price' })], [text('Gold is up.')]])
  t.deps.webLookup = { ready: () => true, find: async () => assert.fail('the server should not look it up') }
  t.session.context.device_tools = [{ name: 'web_lookup', description: 'Tavily', parameters: { type: 'object', properties: {} } }]
  await runTurn(t.deps, t.session, USER, t.out, new AbortController().signal)

  const offered = (t.chat.requests[0].tools as Array<{ function: { name: string } }>).map(f => f.function.name)
  assert.equal(offered.filter(n => n === 'web_lookup').length, 1)
  assert.equal(t.phone.received.find(m => m.type === 'tool_call')!.name, 'web_lookup')
})

test('a turn with no words, a noise Agora took for speech, gets no answer and asks no model', async () => {
  const t = setup([[text('Your list has milk.')]])
  const noise = [
    { role: 'assistant', content: 'Good morning, Lakshmi!' },
    { role: 'user', content: "What's on my shopping list?" },
    { role: 'assistant', content: 'Your list has milk.' },
    { role: 'user', content: '' },
  ]
  await runTurn(t.deps, t.session, noise, t.out, new AbortController().signal)
  assert.equal(t.chat.requests.length, 0)
  assert.equal(t.said(), '')
})

test('a model that says nothing after a tool is asked once more, for words only', async () => {
  const t = setup([[toolCall('add_task', { title: 'Call Meena' })], [], [text("Done, I'll remind you.")]])
  await runTurn(t.deps, t.session, USER, t.out, new AbortController().signal)
  assert.equal(t.chat.requests.length, 3)
  assert.equal(t.chat.requests[2].tool_choice, 'none')
  assert.equal(t.said(), "Okay. Done, I'll remind you.")
})
