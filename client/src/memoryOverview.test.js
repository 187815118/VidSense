import test from 'node:test'
import assert from 'node:assert/strict'
import { createMemoryReader } from './memoryOverview.js'

test('demo and signed-out pages never request private memory', async () => {
  let calls = 0
  const reader = createMemoryReader({ request: () => { calls++ }, update: () => {} })
  await reader.load({ authenticated: false })
  await reader.load({ authenticated: true, demo: true })
  assert.equal(calls, 0)
})

test('account change discards stale response even when transport ignores abort', async () => {
  let resolve
  const states = []
  const reader = createMemoryReader({ request: () => new Promise(r => { resolve = r }), update: s => states.push(s) })
  const task = reader.load({ authenticated: true, videoId: 7 })
  reader.clear()
  resolve({ ok: true, json: async () => ({ enabled: true, items: [{ content: 'previous account' }] }) })
  await task
  assert.deepEqual(states.at(-1).items, [])
})

test('disabled and unavailable memory are distinguishable and never show fake entries', async () => {
  let state
  const reader = createMemoryReader({ request: async () => ({ ok: true, json: async () => ({ enabled: false, items: [{ content: 'stale' }] }) }), update: s => { state = s } })
  await reader.load({ authenticated: true })
  assert.equal(state.enabled, false)
  assert.deepEqual(state.items, [])
  const failed = createMemoryReader({ request: async () => ({ ok: false }), update: s => { state = s } })
  await failed.load({ authenticated: true })
  assert.equal(state.enabled, null)
  assert.ok(state.error)
})
