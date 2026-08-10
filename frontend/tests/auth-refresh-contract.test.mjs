import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'

const http = fs.readFileSync(new URL('../src/utils/http.ts', import.meta.url), 'utf8')
const authStore = fs.readFileSync(new URL('../src/stores/auth.ts', import.meta.url), 'utf8')

test('refresh infrastructure failures preserve tokens for a later retry', () => {
  assert.match(http, /catch \(refreshError\)/)
  assert.match(http, /refreshError\.response\?\.status === 401/)
  assert.match(http, /if \(refreshAuthenticationFailed\) \{[\s\S]*await clearTokens\(\)[\s\S]*window\.location\.assign\('\/login'\)[\s\S]*\}/)
  assert.match(http, /throw refreshError/)
})

test('profile bootstrap keeps tokens on transient persistent-auth outages', () => {
  assert.match(authStore, /catch \(error\)/)
  assert.match(authStore, /if \(isAuthenticationFailure\(error\)\) \{[\s\S]*await clearTokens\(\)[\s\S]*user\.value = null[\s\S]*return null[\s\S]*\}/)
  assert.match(authStore, /throw error/)
  assert.match(http, /export function isAuthenticationFailure/)
  assert.match(http, /status === 401 \|\| status === 403/)
})
