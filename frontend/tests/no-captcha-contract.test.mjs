import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const frontendRoot = new URL('../', import.meta.url)

async function source(relativePath) {
  return readFile(new URL(relativePath, frontendRoot), 'utf8')
}

test('web login contract contains only username and password', async () => {
  const [api, store, view] = await Promise.all([
    source('src/api/auth.ts'),
    source('src/stores/auth.ts'),
    source('src/views/auth/LoginView.vue'),
  ])

  assert.doesNotMatch(api, /captcha/i)
  assert.doesNotMatch(store, /captcha/i)
  assert.doesNotMatch(view, /captcha|验证码/i)
  assert.match(api, /http\.post[^]*?['"]\/auth\/login['"],\s*\{\s*username,\s*password,?\s*\}/)
})

test('web server setup performs a strict live public branding probe', async () => {
  const setup = await source('src/views/mobile/profile/MobileServerSetupView.vue')

  assert.doesNotMatch(setup, /captcha/i)
  assert.match(setup, /http\.get[^\n]*['"]\/public\/branding['"]/)
  assert.match(setup, /response\.data\.code\s*!==\s*['"]OK['"]/)
  assert.match(setup, /response\.data\.data/)
})
