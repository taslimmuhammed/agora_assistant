import assert from 'node:assert/strict'
import { test } from 'node:test'
import { AgoraAgents, joinBody } from '../src/agora.ts'
import { Sessions } from '../src/sessions.ts'
import { testConfig } from './helpers.ts'

// Agora's REST calls are signed with the agent's own token unless a Customer ID and Secret are set.

function recordFetch() {
  const calls: Array<{ url: string; auth: string }> = []
  const real = globalThis.fetch
  globalThis.fetch = (async (url: string, init: RequestInit) => {
    calls.push({ url, auth: String((init.headers as Record<string, string>).Authorization) })
    return new Response(JSON.stringify({ agent_id: 'agent-9', status: 'RUNNING' }), { status: 200 })
  }) as typeof fetch
  return { calls, restore: () => { globalThis.fetch = real } }
}

test('without a Customer ID, join, speak and leave carry the agent token', async () => {
  const config = testConfig({ AGORA_CUSTOMER_ID: '', AGORA_CUSTOMER_SECRET: '' })
  const session = new Sessions().create('lakshmi', 0, Date.now())
  const body = joinBody(config, session, 'prompt', 'hello')
  const token = body.properties.token
  assert.match(token, /^007/)
  const fetches = recordFetch()
  try {
    const agents = new AgoraAgents(config)
    await agents.join(body)
    await agents.speak('agent-9', 'Rahul said yes.')
    await agents.leave('agent-9')
  } finally {
    fetches.restore()
  }
  assert.deepEqual(fetches.calls.map(c => c.url.split('/').slice(-2).join('/')), ['0123456789abcdef0123456789abcdef/join', 'agent-9/speak', 'agent-9/leave'])
  assert.ok(fetches.calls.every(c => c.auth === `agora token="${token}"`))
})

test('with a Customer ID and Secret, calls use Basic auth', async () => {
  const fetches = recordFetch()
  try {
    await new AgoraAgents(testConfig()).leave('agent-1')
  } finally {
    fetches.restore()
  }
  assert.equal(fetches.calls[0].auth, `Basic ${Buffer.from('customer:secret').toString('base64')}`)
})
