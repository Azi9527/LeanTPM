import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

function source(relativePath) {
	return readFileSync(new URL(`../${relativePath}`, import.meta.url), 'utf8')
}

test('equipment management information is expanded on first entry', () => {
	const context = source('pages/equipment/context.vue')
	assert.match(context, /const managementExpanded = ref\(true\)/)
	assert.match(context, /managementExpanded = !managementExpanded/)
})

test('APP task and abnormal cards identify equipment by code and name', () => {
	const workbench = source('pages/workbench/index.vue')
	assert.match(workbench, /task\.equipmentCode \}\} · \{\{ task\.equipmentName/)

	const detail = source('pages/inspection/detail.vue')
	assert.match(detail, /detail\.task\.equipmentCode \}\} · \{\{ detail\.task\.equipmentName/)

	const abnormal = source('pages/abnormal/index.vue')
	assert.match(abnormal, /row\.equipmentCode \}\} · \{\{ row\.equipmentName/)
})

test('APP scan mismatch prompt identifies both required and scanned equipment', () => {
	const scan = source('pages/scan/index.vue')
	assert.match(scan, /task\.equipmentCode.*task\.equipmentName/)
	assert.match(scan, /context\?\.equipment\?\.equipmentCode.*context\?\.equipment\?\.equipmentName/)
})
