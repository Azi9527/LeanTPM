import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const api = readFileSync(new URL('../src/api/appRelease.ts', import.meta.url), 'utf8')
const view = readFileSync(
  new URL('../src/views/system/app-releases/AppReleaseView.vue', import.meta.url),
  'utf8',
)

test('Android release contract exposes and submits the force-upgrade option', () => {
  assert.match(api, /forceUpgrade\?: boolean/)
  assert.match(view, /forceUpgrade: false/)
  assert.match(view, /data\.append\('forceUpgrade', String\(form\.forceUpgrade\)\)/)
  assert.match(view, /强制所有旧版本升级/)
})
