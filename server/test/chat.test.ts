import assert from 'node:assert/strict'
import type { AddressInfo } from 'node:net'
import { after, before, test } from 'node:test'
import { createApp } from '../src/app.ts'
import { Bridge } from '../src/bridge.ts'
import { chatMessages, describe } from '../src/chat.ts'
import { Sessions } from '../src/sessions.ts'
import { FakeAgents, at, makeHub, scriptedChat, testConfig, text, toolCall } from './helpers.ts'

// The app's typed chat over HTTP: one streamed reply, with a phone tool run in between.

const sessions = new Sessions()
const chat = scriptedChat([[toolCall('set_alarm', { when: '6:30' })], [text('Your alarm is set '), text('for 6:30.')]])
const { app } = createApp({
  config: testConfig(),
  hub: makeHub(),
  sessions,
  bridge: new Bridge(),
  agents: new FakeAgents(),
  chat,
  whatsapp: null,
  clock: () => at(9, 5),
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

const post = (path: string, body: unknown, headers: Record<string, string> = { 'X-App-Key': 'app-key' }) =>
  fetch(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) })

const SET_ALARM = { name: 'set_alarm', description: 'An alarm', parameters: { type: 'object', properties: { when: { type: 'string' } } } }

test('a chat message: streamed words, the phone tool asked for on the stream and answered by POST', async () => {
  assert.equal((await post('/chat/turn', { messages: [{ role: 'user', content: 'hi' }] }, {})).status, 401)

  const response = await post('/chat/turn', {
    user_id: 'lakshmi',
    context: { user_name: 'Lakshmi', device_tools: [SET_ALARM] },
    messages: [{ role: 'assistant', content: 'Good morning!' }, { role: 'user', content: 'Wake me at 6:30' }],
  })
  assert.equal(response.status, 200)
  assert.match(response.headers.get('content-type') ?? '', /text\/event-stream/)

  const events: Array<Record<string, unknown>> = []
  const decoder = new TextDecoder()
  let buffered = ''
  for await (const piece of response.body as unknown as AsyncIterable<Uint8Array>) {
    buffered += decoder.decode(piece, { stream: true })
    let end: number
    while ((end = buffered.indexOf('\n\n')) >= 0) {
      const line = buffered.slice(0, end)
      buffered = buffered.slice(end + 2)
      if (!line.startsWith('data: ')) continue
      const event = JSON.parse(line.slice(6))
      events.push(event)
      if (event.type === 'tool_call') {
        const answered = await post('/chat/tool_result', { session_id: events[0].session_id, call_id: event.call_id, result: '{"ok":true}', chip: { title: 'Alarm' } })
        assert.equal(answered.status, 200)
      }
    }
  }

  assert.equal(events[0].type, 'session')
  const call = events.find(e => e.type === 'tool_call')
  assert.equal(call?.name, 'set_alarm')
  assert.deepEqual(call?.args, { when: '6:30' })
  assert.equal(events.filter(e => e.type === 'text').map(e => e.text).join(''), 'Your alarm is set for 6:30.')
  assert.equal(events.at(-1)?.type, 'done')

  // Written for a chat: the chat's wording, no "Okay." filler before the tool round, the history without the greeting.
  const first = chat.requests[0] as { messages: Array<{ role: string; content: string }> }
  assert.match(first.messages[0].content, /writing to you in the Luna app's chat/)
  assert.deepEqual(first.messages.slice(1), [{ role: 'user', content: 'Wake me at 6:30' }])
  assert.equal(sessions.get(String(events[0].session_id)), null)
})

test('a photo goes with the last message, as an image part', () => {
  const messages = chatMessages({
    messages: [{ role: 'user', content: 'What is this?' }],
    image: { mime: 'image/jpeg', data: 'AAAA' },
  })
  assert.deepEqual(messages, [{
    role: 'user',
    content: [{ type: 'text', text: 'What is this?' }, { type: 'image_url', image_url: { url: 'data:image/jpeg;base64,AAAA' } }],
  }])
  assert.deepEqual(chatMessages({ messages: [{ role: 'user', content: 'hi' }], image: { mime: 'text/html', data: 'x' } }), [{ role: 'user', content: 'hi' }])
})

test('a chat that ends without a user message is refused', async () => {
  const response = await post('/chat/turn', { messages: [{ role: 'assistant', content: 'Hello' }] })
  assert.equal(response.status, 400)
})

test("a kept photo's title and details, from the model's JSON", async () => {
  const chat = scriptedChat([[text('```json\n{"title": "Wi-Fi card", '), text('"details": "Network Home_5G, password 4417-ABCD."}\n```')]])
  const described = await describe({ chat, config: testConfig() }, 'image/jpeg', 'AAAA')
  assert.deepEqual(described, { title: 'Wi-Fi card', details: 'Network Home_5G, password 4417-ABCD.' })
  const sent = chat.requests[0] as { messages: Array<{ content: Array<{ type: string }> }> }
  assert.deepEqual(sent.messages[0].content.map(p => p.type), ['text', 'image_url'])
})
