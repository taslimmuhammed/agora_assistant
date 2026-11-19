import assert from 'node:assert/strict'
import { test } from 'node:test'
import { type Option, chatOf, pickOption } from '../src/whatsapp.ts'
import { ACCOUNT, RAHUL, family, incoming, reaction } from './helpers.ts'

// How WhatsApp answers are read: numbers, words, reactions, quotes, LIDs, and who is listened to.

const TASK: Option[] = [{ text: "👍 I'll do it", data: 'task:1:yes' }, { text: "🙅 Can't", data: 'task:1:no' }]
const ORDER: Option[] = [{ text: '✅ Confirm', data: 'req:1:ok' }, { text: '⏱ Within the hour', data: 'req:1:hour' }, { text: '🕐 Another time', data: 'req:1:later' }]

test('a number, a word or Hinglish picks an option; a time or a sentence does not', () => {
  const pick = (text: string, options = TASK) => pickOption(text, options)?.option.data ?? null
  assert.equal(pick('1'), 'task:1:yes')
  assert.equal(pick('2.'), 'task:1:no')
  assert.equal(pick('haan'), 'task:1:yes')
  assert.equal(pick('Ok 👍🏽'), 'task:1:yes')
  assert.equal(pick('No problem'), 'task:1:yes')
  assert.equal(pick('nahi'), 'task:1:no')
  assert.equal(pick("Can’t, sorry"), 'task:1:no')
  assert.equal(pick('3'), null, 'no third option')
  assert.equal(pick('4:30 works'), null)
  assert.equal(pick('No, Priya will take her instead'), null, 'a sentence is a note')
  assert.equal(pick('nowhere'), null)
  assert.equal(pick('1 hour', ORDER), 'req:1:hour')
  assert.equal(pick('kal', ORDER), 'req:1:later')
  assert.equal(pickOption('1, I will be there by 4', TASK)?.rest, 'i will be there by 4')
})

test('numbers in the seed become WhatsApp chats', () => {
  assert.equal(chatOf({ phone: '+91 90000 00002' }), RAHUL)
  assert.equal(chatOf({ phone: '90000 00002' }), RAHUL, 'ten digits: an Indian number')
  assert.equal(chatOf({ phone: '' }), null)
})

test('a 👍 on the message answers it, and a word answers the next one', async () => {
  const f = family()
  await f.tool('assign_task', { assignee: 'Rahul', task: 'Bring milk' })
  await f.whatsapp.receive(reaction(f.hub, 'rahul', f.socket.sent[0].id, '👍'))
  assert.equal(f.hub.task(1)?.status, 'accepted')

  await f.tool('assign_task', { assignee: 'my son', task: 'Call the plumber' })
  await f.whatsapp.receive(incoming(f.hub, 'rahul', 'nahi'))
  assert.equal(f.hub.task(2)?.status, 'declined')
  assert.equal(f.hub.task(1)?.status, 'accepted')
})

test('a quoted "1" answers the message it quotes, not the latest', async () => {
  const f = family()
  await f.tool('assign_task', { assignee: 'Rahul', task: 'Bring milk' })
  await f.tool('assign_task', { assignee: 'Rahul', task: 'Call the plumber' })
  await f.whatsapp.receive(incoming(f.hub, 'rahul', '1', { quoted: f.socket.sent[0].id }))
  assert.equal(f.hub.task(1)?.status, 'accepted')
  assert.equal(f.hub.task(2)?.status, 'open')
})

test('a sentence is passed on to her as it is, and the task stays open', async () => {
  const f = family()
  await f.tool('assign_task', { assignee: 'Rahul', task: "Take Amma to Dr. Rao's clinic" })
  await f.whatsapp.receive(incoming(f.hub, 'rahul', 'No, Priya will take her instead'))
  assert.equal(f.hub.task(1)?.status, 'open')
  assert.equal(f.hub.eventsSince(0).at(-1)?.text, 'Rahul says: No, Priya will take her instead')
})

test('after "Can\'t", a stray "1" reaches nothing', async () => {
  const f = family()
  await f.tool('assign_task', { assignee: 'Rahul', task: 'Bring milk' })
  await f.whatsapp.receive(incoming(f.hub, 'rahul', '2'))
  const sent = f.socket.sent.length
  await f.whatsapp.receive(incoming(f.hub, 'rahul', '1'))
  assert.equal(f.hub.task(1)?.status, 'declined')
  assert.equal(f.socket.sent.length, sent)
  assert.equal(f.hub.eventsSince(0).filter(e => e.kind === 'reply').length, 0)
})

test('someone WhatsApp names by their LID is found by their number', async () => {
  const f = family()
  f.socket.lids.set('123456789@lid', RAHUL)
  await f.tool('assign_task', { assignee: 'Rahul', task: 'Bring milk' })
  await f.whatsapp.receive(incoming(f.hub, 'rahul', '1', { lid: '123456789@lid' }))
  assert.equal(f.hub.task(1)?.status, 'accepted')
  assert.equal(f.socket.sent.at(-1)!.jid, RAHUL)
})

test("strangers and her own phone's chats are ignored", async () => {
  const f = family()
  await f.tool('assign_task', { assignee: 'Rahul', task: 'Bring milk' })
  await f.whatsapp.receive(incoming(f.hub, '919999999999@s.whatsapp.net', '1'))
  await f.whatsapp.receive(incoming(f.hub, RAHUL, '1', { fromMe: true }))
  assert.equal(f.hub.task(1)?.status, 'open')
  assert.equal(f.socket.sent.length, 1)
  assert.equal(f.hub.eventsSince(0).filter(e => e.kind === 'reply').length, 0)
})

test('/family in a group, sent from her phone, makes it the family group', async () => {
  const f = family()
  const group = '120363000000000001@g.us'
  await f.whatsapp.receive(incoming(f.hub, ACCOUNT, '/family', { group, fromMe: true }))
  assert.equal(f.hub.familyGroupChat(), group)
  const told = await f.tool('message_family', { to: 'everyone', text: 'I reached home' })
  assert.equal(told.result.sent_to, 'the family group')
  assert.equal(f.socket.sent.at(-1)!.jid, group)

  // In the group, only a quoted reply is about something; the rest is the family's own chat.
  await f.whatsapp.receive(incoming(f.hub, 'rahul', 'Good to hear', { group }))
  assert.equal(f.hub.eventsSince(0).filter(e => e.kind === 'reply').length, 0)
})
