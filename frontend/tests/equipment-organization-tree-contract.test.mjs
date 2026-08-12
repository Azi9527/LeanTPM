import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const view = readFileSync(
  new URL('../src/views/equipment/ledger/EquipmentLedgerView.vue', import.meta.url),
  'utf8',
)

test('equipment organization selectors preserve the organization hierarchy', () => {
  assert.match(view, /interface OrganizationTreeNode extends OrganizationRow/)
  assert.match(view, /function buildOrganizationTree\(source: OrganizationRow\[\]\)/)
  assert.match(view, /const organizationTree = computed\(\(\) => buildOrganizationTree\(organizations\.value\)\)/)
  assert.equal([...view.matchAll(/<el-tree-select/g)].length, 2)
  assert.doesNotMatch(view, /v-for="item in activeOrganizations"/)
})
