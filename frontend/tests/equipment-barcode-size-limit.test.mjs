import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(
  new URL('../src/views/equipment/barcodes/EquipmentBarcodeView.vue', import.meta.url),
  'utf8',
)

test('equipment barcode label keeps its default size and allows width and height up to 500 mm', () => {
  assert.match(source, /reactive\(\{ widthMm:\s*60, heightMm:\s*80, imagePixels:\s*600 \}\)/)
  assert.match(source, /v-model="label\.widthMm"[^>]*:min="20"[^>]*:max="500"/)
  assert.match(source, /v-model="label\.heightMm"[^>]*:min="20"[^>]*:max="500"/)
})
