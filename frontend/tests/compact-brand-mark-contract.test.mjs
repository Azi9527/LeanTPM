import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const brandLogo = readFileSync(
  new URL('../src/components/branding/BrandLogo.vue', import.meta.url),
  'utf8',
)
const indexHtml = readFileSync(
  new URL('../index.html', import.meta.url),
  'utf8',
)

test('compact logo uses the isolated Baoshan mining mark', () => {
  assert.match(
    brandLogo,
    /compact\s*\?\s*['"]\/branding\/baoshan-mining-mark\.png\?v=20260810['"]\s*:\s*branding\.logoUrl/,
  )
  assert.match(brandLogo, /<img[^>]+:src="imageSource"/)
})

test('favicon uses the same isolated Baoshan mining mark', () => {
  assert.match(
    indexHtml,
    /<link\s+rel="icon"\s+type="image\/png"\s+href="\/branding\/baoshan-mining-mark\.png\?v=20260810"\s*\/?>/,
  )
})
