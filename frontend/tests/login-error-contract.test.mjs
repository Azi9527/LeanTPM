import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const httpSource = readFileSync(new URL('../src/utils/http.ts', import.meta.url), 'utf8')
const loginSource = readFileSync(new URL('../src/views/auth/LoginView.vue', import.meta.url), 'utf8')

test('login reports backend connectivity and proxy failures explicitly', () => {
  assert.match(httpSource, /export function loginErrorMessage/)
  assert.match(httpSource, /无法连接后端服务，请确认后端已启动并检查网络/)
  assert.match(httpSource, /后端服务暂时不可用（HTTP/)
  assert.match(loginSource, /loginErrorMessage\(error\)/)
})
