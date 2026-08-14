import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const columnSource = readFileSync(
  new URL('../src/components/table/SmartElTableColumn.ts', import.meta.url),
  'utf8',
)

test('server table filter owns a reactive draft so typed text stays visible', () => {
  assert.match(columnSource, /const SmartTableFilterInput = defineComponent\(/)
  assert.match(columnSource, /const draft = ref\(props\.modelValue \?\? ''\)/)
  assert.match(columnSource, /draft\.value = next/)
  assert.match(columnSource, /emit\('update:modelValue', next\)/)
})

test('both filter value inputs use the reactive draft component', () => {
  const usages = columnSource.match(/h\(SmartTableFilterInput,/g) ?? []
  assert.equal(usages.length, 2)
})
