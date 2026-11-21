import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import type { AddressInfo } from 'node:net'
import { test } from 'node:test'
import { openAiChat } from '../src/llm.ts'
import { ModelChoice } from '../src/models.ts'
import { testConfig } from './helpers.ts'

// When Gemini's big model is busy or hangs, the call carries on with the fallback.

test('a busy model hands over to the fallback for a while, then is tried again', () => {
  let now = 0
  const models = new ModelChoice('big', 'lite', () => now, 60_000)
  assert.deepEqual(models.order(), ['big', 'lite'])
  models.busy('big')
  assert.deepEqual(models.order(), ['lite'])
  now = 61_000
  assert.deepEqual(models.order(), ['big', 'lite'])
  assert.deepEqual(new ModelChoice('big', '').order(), ['big'])
  const chain = new ModelChoice('big', 'lite, lite-latest,big', () => now)
  assert.deepEqual(chain.order(), ['big', 'lite', 'lite-latest'], 'in turn, without the model itself')
})

test('the call streams from the fallback when the model answers 503 or says nothing', async () => {
  const asked: string[] = []
  const server = createServer((req, res) => {
    let body = ''
    req.on('data', c => (body += c))
    req.on('end', () => {
      const { model } = JSON.parse(body) as { model: string }
      asked.push(model)
      if (model === 'busy') return void res.writeHead(503, { 'Content-Type': 'application/json' }).end('{"error":{"message":"high demand"}}')
      if (model === 'hangs') return void res.writeHead(200, { 'Content-Type': 'text/event-stream' }) // and nothing more
      res.writeHead(200, { 'Content-Type': 'text/event-stream' })
      res.end(`data: ${JSON.stringify({ choices: [{ index: 0, delta: { content: `hello from ${model}` } }] })}\n\ndata: [DONE]\n\n`)
    })
  })
  await new Promise<void>(r => server.listen(0, r))
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/`
  const say = async (model: string) => {
    const chat = openAiChat(testConfig({ LLM_BASE_URL: base, LLM_API_KEY: 'k', LLM_MODEL: model, LLM_FALLBACK_MODEL: 'lite' }), undefined, 300)
    let text = ''
    for await (const chunk of await chat({ model, messages: [] }, new AbortController().signal)) text += chunk.choices?.[0]?.delta?.content ?? ''
    return text
  }
  try {
    assert.equal(await say('busy'), 'hello from lite')
    assert.equal(await say('hangs'), 'hello from lite')
    assert.equal(await say('fine'), 'hello from fine')
    assert.deepEqual(asked, ['busy', 'lite', 'hangs', 'lite', 'fine'])
  } finally {
    server.closeAllConnections()
    server.close()
  }
})
