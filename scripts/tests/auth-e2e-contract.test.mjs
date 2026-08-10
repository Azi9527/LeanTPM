import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'

const script = fs.readFileSync(new URL('../verify-auth-e2e.ps1', import.meta.url), 'utf8')

test('isolated auth E2E exercises refresh replay, restart persistence and removed captcha route', () => {
  assert.match(script, /\$refreshToken0/)
  assert.match(script, /REFRESH_TOKEN_REUSED/)
  assert.match(script, /REFRESH_ROTATION_REUSE_REVOKES_SESSION=PASS/)
  assert.match(script, /Stop-Backend[\s\S]*Start-Backend[\s\S]*\$third/)
  assert.match(script, /DATABASE_IDEMPOTENCY_REPLAY_SURVIVES_RESTART=PASS/)
  assert.match(script, /\/auth\/captcha/)
  assert.match(script, /CAPTCHA_ENDPOINT_REMOVED_404=PASS/)
  assert.match(script, /auth_login_security_state/)
  assert.match(script, /locked_until > CURRENT_TIMESTAMP/)
  assert.doesNotMatch(script, /LOGIN_TEMPORARILY_LOCKED|LOGIN_LOCKED/)
})
