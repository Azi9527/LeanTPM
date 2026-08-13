import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(
  new URL('../src/views/system/parameters/ParameterView.vue', import.meta.url),
  'utf8',
)

test('equipment QR preview uses three large rows and maximizes the system title', () => {
  assert.match(source, /class="qr-device-row"[^>]*>[\s\S]*?设备名称：循环泵站一号/)
  assert.match(source, /class="qr-device-row"[^>]*>[\s\S]*?设备编码：VIZ-PUMP-01/)
  assert.match(source, /class="qr-scan-action"[^>]*>[\s\S]*?扫码查看设备档案/)
  assert.match(source, /\.qr-preview-title[\s\S]*?font-size:\s*clamp\(24px,\s*3vw,\s*48px\)/)
  assert.match(source, /\.qr-device-row[\s\S]*?font-size:\s*clamp\(17px,\s*1\.9vw,\s*30px\)/)
  assert.match(source, /\.qr-scan-action[\s\S]*?font-size:\s*clamp\(19px,\s*2\.2vw,\s*34px\)/)
})
