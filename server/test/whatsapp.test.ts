import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { WAMessage } from 'baileys'
import { runFollowUps } from '../src/followups.ts'
import { type Option, type Understand, chatOf, guessOption, pickOption } from '../src/whatsapp.ts'
import { ACCOUNT, RAHUL, at, family, incoming, reaction } from './helpers.ts'

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

test('with one number, Luna writes to "Message yourself" and what is typed there answers', async () => {
  const f = family()
  f.whatsapp.attach(f.socket, RAHUL, '555@lid') // signed in with Rahul's own number
  await f.tool('assign_task', { assignee: 'Rahul', task: 'Bring milk' })
  const ask = f.socket.sent[0]
  assert.equal(ask.jid, RAHUL)

  // Luna's own message coming back, and something typed to someone else: neither answers.
  await f.whatsapp.receive({ ...incoming(f.hub, RAHUL, ask.text, { fromMe: true }), key: { id: ask.id, fromMe: true, remoteJid: RAHUL } })
  await f.whatsapp.receive(incoming(f.hub, 'priya', '1', { fromMe: true }))
  assert.equal(f.hub.task(1)?.status, 'open')

  // The self chat, named by its LID: "1" there is Rahul's answer.
  await f.whatsapp.receive(incoming(f.hub, '555@lid', '1', { fromMe: true }))
  assert.equal(f.hub.task(1)?.status, 'accepted')
  assert.equal(f.socket.sent.at(-1)!.jid, RAHUL)
  assert.match(f.socket.sent.at(-1)!.text, /Thanks!/)
})

// With the model: anything that isn't a plain number or word is read by it.

function modelSays(...answers: Array<{ choice: number | null; message: string } | Error>) {
  const asks: Array<Parameters<Understand>[0]> = []
  const understand: Understand = async ask => {
    asks.push(ask)
    const answer = answers[asks.length - 1] ?? { choice: null, message: '' }
    if (answer instanceof Error) throw answer
    return answer
  }
  return { understand, asks }
}

test('a sentence is read by the model: its choice is made and its words passed on', async () => {
  const model = modelSays({ choice: 2, message: "I'm in Pune till Sunday." })
  const f = family(at(9, 5), model.understand)
  await f.tool('assign_task', { assignee: 'Rahul', task: 'Bring milk' })
  await f.whatsapp.receive(incoming(f.hub, 'rahul', 'Sorry amma, Pune mein hoon Sunday tak'))
  assert.equal(model.asks[0].reply.text, 'Sorry amma, Pune mein hoon Sunday tak')
  assert.deepEqual(model.asks[0].options.map(o => o.data), ['task:1:yes', 'task:1:no'])
  assert.match(model.asks[0].luna!, /Bring milk/)
  assert.equal(f.hub.task(1)?.status, 'declined')
  // The choice and what came with it are one update for her, and one answer on WhatsApp.
  assert.equal(f.hub.eventsSince(0).at(-1)?.text, "Rahul can't do it: bring milk. Rahul says: I'm in Pune till Sunday. Shall I ask someone else?")
  assert.equal(f.hub.eventsSince(0).filter(e => e.kind === 'reply').length, 0)
  assert.deepEqual(f.socket.sent.slice(1).map(m => m.text), ['🙅 Okay, Lakshmi will be told.'])
})

test('a voice note is heard by the model', async () => {
  const model = modelSays({ choice: 1, message: "I'll be there at 4." })
  const f = family(at(9, 5), model.understand)
  await f.tool('assign_task', { assignee: 'Rahul', task: "Take Amma to Dr. Rao's clinic" })
  await f.whatsapp.receive({
    key: { id: 'VOICE1', fromMe: false, remoteJid: RAHUL },
    message: { audioMessage: { mimetype: 'audio/ogg; codecs=opus', ptt: true } },
  } as WAMessage)
  assert.equal(model.asks[0].reply.media?.kind, 'voice note')
  assert.equal(model.asks[0].reply.media?.mimeType, 'audio/ogg; codecs=opus')
  assert.equal(model.asks[0].reply.media?.data.toString(), 'voice')
  assert.equal(f.hub.task(1)?.status, 'accepted')
  assert.ok(f.hub.eventsSince(0).some(e => e.text === "Rahul said yes: take Amma to Dr. Rao's clinic. Rahul says: I'll be there at 4."))
})

test('a 🥰 is read by the model; 👍 and "1" never reach it', async () => {
  const model = modelSays({ choice: 1, message: '' })
  const f = family(at(9, 5), model.understand)
  await f.tool('assign_task', { assignee: 'Rahul', task: 'Bring milk' })
  await f.whatsapp.receive(reaction(f.hub, 'rahul', f.socket.sent[0].id, '🥰'))
  assert.equal(model.asks[0].reply.reaction, '🥰')
  assert.equal(f.hub.task(1)?.status, 'accepted')
  await f.tool('assign_task', { assignee: 'Rahul', task: 'Call the plumber' })
  await f.whatsapp.receive(incoming(f.hub, 'rahul', '1'))
  assert.equal(f.hub.task(2)?.status, 'accepted')
  assert.equal(model.asks.length, 1)
})

test('when the model fails, the words still reach her', async () => {
  const model = modelSays(new Error('Gemini answered 503'))
  const f = family(at(9, 5), model.understand)
  await f.tool('assign_task', { assignee: 'Rahul', task: 'Bring milk' })
  await f.whatsapp.receive(incoming(f.hub, 'rahul', 'Will check after office'))
  assert.equal(f.hub.task(1)?.status, 'open')
  assert.ok(f.hub.eventsSince(0).some(e => e.text === 'Rahul says: Will check after office'))
})

test('a photo and a sticker go to the model as images', async () => {
  const model = modelSays({ choice: 0 as unknown as null, message: "Here's the medicine I bought: Amlodipine 5 mg." }, { choice: 1, message: '' })
  const f = family(at(9, 5), model.understand)
  await f.tool('assign_task', { assignee: 'Rahul', task: 'Pick up my medicines' })
  await f.whatsapp.receive({
    key: { id: 'PHOTO1', fromMe: false, remoteJid: RAHUL },
    message: { imageMessage: { mimetype: 'image/jpeg', caption: 'got these' } },
  } as WAMessage)
  assert.equal(model.asks[0].reply.media?.kind, 'photo')
  assert.equal(model.asks[0].reply.text, 'got these')
  assert.ok(f.hub.eventsSince(0).some(e => e.text === "Rahul says: Here's the medicine I bought: Amlodipine 5 mg."))
  assert.equal(f.hub.task(1)?.status, 'open')

  await f.whatsapp.receive({
    key: { id: 'STICKER1', fromMe: false, remoteJid: RAHUL },
    message: { stickerMessage: { mimetype: 'image/webp' } },
  } as WAMessage)
  assert.equal(model.asks[1].reply.media?.kind, 'sticker')
  assert.equal(f.hub.task(1)?.status, 'accepted')
})

test('without the model, a voice note and a location still reach her', async () => {
  const f = family()
  await f.whatsapp.receive({ key: { id: 'V2', fromMe: false, remoteJid: RAHUL }, message: { audioMessage: { mimetype: 'audio/ogg', ptt: true } } } as WAMessage)
  assert.equal(f.hub.eventsSince(0).at(-1)?.text, 'Rahul sent you a voice note.')
  await f.whatsapp.receive({ key: { id: 'L1', fromMe: false, remoteJid: RAHUL }, message: { locationMessage: { degreesLatitude: 12.97, degreesLongitude: 77.59, name: 'Apollo Pharmacy', address: 'MG Road' } } } as WAMessage)
  assert.equal(f.hub.eventsSince(0).at(-1)?.text, "Rahul says: I'm at Apollo Pharmacy, MG Road.")
})

test('"1, I\'ll be there by 4 with the car" is one update: the choice and the words', async () => {
  const f = family()
  await f.tool('assign_task', { assignee: 'Rahul', task: "Take Amma to Dr. Rao's clinic" })
  await f.whatsapp.receive(incoming(f.hub, 'rahul', "1, I'll be there by 4 with the car"))
  assert.equal(f.hub.task(1)?.status, 'accepted')
  assert.equal(f.hub.eventsSince(0).at(-1)?.text, "Rahul said yes: take Amma to Dr. Rao's clinic. Rahul says: I'll be there by 4 with the car.")
  assert.equal(f.hub.eventsSince(0).filter(e => e.kind === 'reply').length, 0)
})

test('notes get a ✅ on them, and "Luna will tell her" is written once in a while, not each time', async () => {
  const f = family()
  const first = incoming(f.hub, 'rahul', 'Reached office, will call at lunch')
  await f.whatsapp.receive(first)
  await f.whatsapp.receive(incoming(f.hub, 'rahul', 'Also the plumber is coming at 5'))
  assert.deepEqual(f.socket.reactions.map(r => [r.emoji, r.on]), [['✅', first.key.id], ['✅', `IN${Number(first.key.id!.slice(2)) + 1}`]])
  assert.equal(f.socket.sent.filter(m => /Luna will tell Lakshmi/.test(m.text)).length, 1)
  assert.equal(f.hub.eventsSince(0).filter(e => e.kind === 'reply').length, 2)
})

test('a ❤️ on one of her messages is told to her in words', async () => {
  const f = family()
  await f.tool('message_family', { to: 'Rahul', text: 'I reached home safely' })
  await f.whatsapp.receive(reaction(f.hub, 'rahul', f.socket.sent[0].id, '❤️'))
  assert.equal(f.hub.eventsSince(0).at(-1)?.text, 'Rahul sent love for your message: "I reached home safely".')
  // A reaction on Luna's own "thanks" is nothing to tell.
  await f.tool('assign_task', { assignee: 'Rahul', task: 'Bring milk' })
  await f.whatsapp.receive(incoming(f.hub, 'rahul', '1'))
  const events = f.hub.eventsSince(0).length
  await f.whatsapp.receive(reaction(f.hub, 'rahul', f.socket.sent.at(-1)!.id, '😂'))
  assert.equal(f.hub.eventsSince(0).length, events)
})

test('with every model busy, a plain sentence still makes its choice', async () => {
  const pick = (text: string, options: Option[]) => guessOption(text, options)?.data ?? null
  const DOSE: Option[] = [{ text: "✅ She's taken it", data: 'dose:bp-am:2026-11-21:took' }]
  assert.equal(pick('Ok, she have taken it', DOSE), 'dose:bp-am:2026-11-21:took')
  assert.equal(pick("She hasn't taken it yet", DOSE), null)
  assert.equal(pick('Sure, I will pick it up on the way home', TASK), 'task:1:yes')
  assert.equal(pick("Sorry, can't today, I'm travelling", TASK), 'task:1:no')
  assert.equal(pick('Will check after office', TASK), null, 'nothing plain enough')

  const model = modelSays(new Error('gemini-3.5-flash-lite answered 503'))
  const f = family(at(9, 31), model.understand)
  await runFollowUps(f.hub, f.whatsapp, at(9, 31))
  await f.whatsapp.receive(incoming(f.hub, 'rahul', 'Ok, she have taken it'))
  assert.equal(f.hub.doseStates(at(9, 33))[0].state, 'taken')
})
