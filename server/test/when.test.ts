import assert from 'node:assert/strict'
import { test } from 'node:test'
import { at } from './helpers.ts'
import { resolveWhen } from '../src/when.ts'

const now = at(9, 0)

function check(words: string, expected: Date | null, label?: string) {
  const when = resolveWhen(words, now)
  assert.equal(when.at?.getTime() ?? null, expected?.getTime() ?? null, `"${words}" → ${when.at?.toString()}`)
  if (label !== undefined) assert.equal(when.label, label)
}

test('a clock time today, read as daytime', () => {
  check('at 4:30', at(16, 30), '4:30 PM today')
  check('by 11', at(11, 0), '11:00 AM today')
  check('5 pm', at(17, 0))
  check('4:30pm', at(16, 30))
})

test('a time already past today means tomorrow', () => {
  check('8', new Date(2026, 10, 22, 8, 0), '8:00 AM tomorrow')
})

test('days and parts of the day, in English and Hinglish', () => {
  check('kal subah 9 baje', new Date(2026, 10, 22, 9, 0), '9:00 AM tomorrow')
  check('tomorrow at 9', new Date(2026, 10, 22, 9, 0))
  check('tonight', at(21, 0), '9:00 PM today')
  check('this evening at 7', at(19, 0))
  check('aaj shaam 6 baje', at(18, 0))
})

test('a day without a time keeps no time in its label', () => {
  check('tomorrow', new Date(2026, 10, 22, 9, 0), 'tomorrow')
})

test('relative times', () => {
  check('in 2 hours', at(11, 0), '11:00 AM today')
  check('in half an hour', at(9, 30))
  check('10 minute mein', at(9, 10))
})

test("words it can't read are kept as said, with no time", () => {
  check('sometime next week', null, 'sometime next week')
  check('on the 23rd', null, 'on the 23rd')
  check('', null, '')
})
