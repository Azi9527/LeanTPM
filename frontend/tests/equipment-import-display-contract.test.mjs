import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const view = readFileSync(
  new URL('../src/views/equipment/ledger/EquipmentLedgerView.vue', import.meta.url),
  'utf8',
)

test('equipment import renders every error as a visible diagnostic table row', () => {
  assert.match(view, /<el-table\s+:data="importErrorRows"/)
  for (const label of ['Excel 行号', '错误字段', 'Excel 原值', '错误原因', '修改建议']) {
    assert.match(view, new RegExp(`label="${label}"`))
  }
  assert.doesNotMatch(view, /<article\s+v-for="group in importErrorGroups"/)
})
