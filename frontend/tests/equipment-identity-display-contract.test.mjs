import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

function source(relativePath) {
  return readFileSync(new URL(`../src/views/${relativePath}`, import.meta.url), 'utf8')
}

test('scheme selector and version detail identify designated equipment by name and code', () => {
  const view = source('inspection/schemes/InspectionSchemeView.vue')
  assert.match(view, /function equipmentIdentity\(equipmentId: number\)/)
  assert.match(view, /equipmentIdentity\(id\)/)
  assert.match(view, /`\$\{row\.equipmentName\}（\$\{row\.equipmentCode\}）`/)
  assert.match(view, /:label="`\$\{row\.equipmentName\}（\$\{row\.equipmentCode\}）`"/)
})

test('task, abnormal and repair surfaces include the equipment code with the name', () => {
  const inspectionMobile = source('inspection/mobile/MyInspectionTaskView.vue')
  assert.match(inspectionMobile, /<h3>\{\{ row\.equipmentCode \}\} · \{\{ row\.equipmentName \}\}<\/h3>/)
  assert.match(inspectionMobile, /<h2>\{\{ detail\.task\.equipmentCode \}\} · \{\{ detail\.task\.equipmentName \}\}<\/h2>/)

  const maintenanceMobile = source('maintenance/mobile/MyMaintenanceTaskView.vue')
  assert.match(maintenanceMobile, /<h3>\{\{ row\.equipmentCode \}\} · \{\{ row\.equipmentName \}\}<\/h3>/)
  assert.match(maintenanceMobile, /<h2>\{\{ detail\.task\.equipmentCode \}\} · \{\{ detail\.task\.equipmentName \}\}<\/h2>/)

  const inspectionAbnormal = source('inspection/abnormal/InspectionAbnormalView.vue')
  assert.match(inspectionAbnormal, /\{\{ row\.equipmentCode \}\} · \{\{ row\.equipmentName \}\}/)
  assert.match(inspectionAbnormal, /\{\{ selected\.equipmentCode \}\} · \{\{ selected\.equipmentName \}\}/)
  assert.match(inspectionAbnormal, /`\$\{selected\.equipmentCode\} · \$\{selected\.equipmentName\} · \$\{selected\.abnormalTitle\}`/)

  const maintenanceAbnormal = source('maintenance/abnormal/MaintenanceAbnormalView.vue')
  assert.match(maintenanceAbnormal, /\{\{ row\.equipmentCode \}\} · \{\{ row\.equipmentName \}\}/)
  assert.match(maintenanceAbnormal, /`\$\{selected\.equipmentCode\} · \$\{selected\.equipmentName\} · \$\{selected\.abnormalTitle\}`/)

  const repair = source('faults/RepairOrderView.vue')
  assert.match(repair, /<b>\{\{row\.equipmentCode\}\} · \{\{row\.equipmentName\}\}<\/b>/)
})

test('secondary dialogs and risk lists retain the same equipment identity rule', () => {
  const status = source('equipment/statuses/EquipmentStatusView.vue')
  assert.match(status, /selected\?\.equipmentCode.*selected\?\.equipmentName.*状态履历/)

  const tasks = source('inspection/tasks/InspectionTaskView.vue')
  assert.match(tasks, /`\$\{assignTarget\.equipmentCode\} · \$\{assignTarget\.equipmentName\} · \$\{assignTarget\.schemeNameSnapshot\}`/)

  const plans = source('inspection/plans/InspectionPlanView.vue')
  assert.match(plans, /row\.equipmentCode\} · \$\{row\.equipmentName/)

  const ledger = source('equipment/ledger/EquipmentLedgerView.vue')
  assert.match(ledger, /row\.equipmentCode\} · \$\{row\.equipmentName/)

  const topic = source('visualization/topic/VisualizationTopicView.vue')
  assert.match(topic, /<b>\{\{ item\.equipmentCode \}\} · \{\{ item\.equipmentName \}\}<\/b>/)
})
