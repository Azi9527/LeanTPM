import test from 'node:test'
import assert from 'node:assert/strict'

const values = new Map()
globalThis.uni = {
	getStorageSync: (key) => values.get(key) ?? '',
	setStorageSync: (key, value) => values.set(key, value),
	removeStorageSync: (key) => values.delete(key)
}

const { normalizeServerBaseUrl } = await import('../utils/server.js')
const { createIdempotencyKey } = await import('../utils/idempotency.js')
const { normalizeBranding } = await import('../utils/branding.js')
const { extractEquipmentToken, requireEquipmentToken } = await import('../utils/equipment-token.js')
const { initialResultDraft, inferAbnormal, validateInspectionResults, buildInspectionPayload } = await import('../utils/inspection-results.js')
const { ApiError, errorMessage, isConflict } = await import('../utils/errors.js')

test('normalizes enterprise server URLs', () => {
	assert.equal(normalizeServerBaseUrl(' http://192.168.31.91:18080/ '), 'http://192.168.31.91:18080/api/v1')
	assert.equal(normalizeServerBaseUrl('https://tpm.example.com/api/v1'), 'https://tpm.example.com/api/v1')
	assert.throws(() => normalizeServerBaseUrl('127.0.0.1:8080'), /HTTP/)
})

test('generates scoped idempotency keys', () => {
	const first = createIdempotencyKey('inspection-submit')
	const second = createIdempotencyKey('inspection-submit')
	assert.match(first, /^inspection-submit-/)
	assert.notEqual(first, second)
})

test('normalizes branding and rejects invalid colors', () => {
	const branding = normalizeBranding({ shortName: '  客户矿业 ', primaryColor: '#ABCDEF', neutralColor: 'red' })
	assert.equal(branding.shortName, '客户矿业')
	assert.equal(branding.primaryColor, '#abcdef')
	assert.equal(branding.neutralColor, '#c4000a')
})

test('extracts LeanTPM equipment tokens from labels and URLs', () => {
	const token = 'a'.repeat(64)
	assert.equal(extractEquipmentToken(token.toUpperCase()), token)
	assert.equal(extractEquipmentToken(`https://example.test/m/e/${token}?source=label`), token)
	assert.equal(extractEquipmentToken('not-a-label'), null)
	assert.throws(() => requireEquipmentToken('bad'), /LeanTPM/)
})

test('validates qualitative and quantitative inspection results', () => {
	const items = [
		{ id: 1, itemName: '防护罩', resultType: 'PASS_FAIL', abnormalDefaultStopFlag: false },
		{ id: 2, itemName: '油压', resultType: 'NUMBER', minimumValue: 30, maximumValue: 80, abnormalDefaultStopFlag: true }
	]
	const drafts = { 1: initialResultDraft(items[0]), 2: initialResultDraft(items[1]) }
	assert.match(validateInspectionResults(items, drafts), /防护罩/)
	drafts[1].resultCode = 'PASS'
	drafts[2].numericValue = 90
	drafts[2].abnormal = inferAbnormal(items[1], drafts[2])
	drafts[2].abnormalDescription = '超过上限'
	assert.equal(validateInspectionResults(items, drafts), '')
	const payload = buildInspectionPayload({ version: 3 }, items, drafts, '现场完成')
	assert.equal(payload.taskVersion, 3)
	assert.equal(payload.results[1].numericValue, 90)
})

test('preserves API conflict semantics', () => {
	const error = new ApiError('TASK_ALREADY_COMPLETED', '任务已经完成', 409)
	assert.equal(errorMessage(error), '任务已经完成')
	assert.equal(isConflict(error), true)
})
