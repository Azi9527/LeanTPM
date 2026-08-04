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
const { brandingLogoSource, normalizeBranding } = await import('../utils/branding.js')
const { extractEquipmentToken, requireEquipmentToken } = await import('../utils/equipment-token.js')
const { initialResultDraft, inferAbnormal, validateInspectionResults, buildInspectionPayload } = await import('../utils/inspection-results.js')
const { saveDraftEnvelope, loadDraftEnvelope, queuePhoto, attachQueuedPhotoToDraft, listQueuedPhotos, removeDraftEnvelope } = await import('../stores/offline.js')
const { formatBusinessDateTime, watermarkLines } = await import('../platform/photo.js')
const { compareVersionCodes } = await import('../utils/version.js')
const { ApiError, errorMessage, isConflict } = await import('../utils/errors.js')
const secureStorage = await import('../platform/secure-storage.js')
const storage = await import('../platform/storage.js')

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
	assert.equal(brandingLogoSource('/branding/baoshan-mining-logo.png'), '/static/branding/baoshan-mining-logo.png')
	assert.match(brandingLogoSource('data:image/png;base64,AA=='), /^data:image/)
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

test('queues photos before pending drafts and writes attachment ids back', () => {
	const envelope = saveDraftEnvelope({ workflow: 'inspection', taskId: 9, taskVersion: 2, pendingSubmit: true, idempotencyKey: 'submit-9', payload: { taskVersion: 2, results: [{ taskItemId: 12, attachmentIds: [] }] } })
	queuePhoto({ id: 'photo-1', workflow: 'inspection', taskId: 9, taskItemId: 12, originalPath: 'a.jpg', watermarkedPath: 'b.jpg' })
	assert.equal(listQueuedPhotos().length, 1)
	assert.equal(attachQueuedPhotoToDraft(listQueuedPhotos()[0], 88), true)
	assert.deepEqual(loadDraftEnvelope('inspection', 9).payload.results[0].attachmentIds, [88])
	removeDraftEnvelope('inspection', 9)
})

test('builds a GPS-free equipment location watermark', () => {
	const capturedAt = new Date(2026, 7, 4, 11, 49, 21)
	const lines = watermarkLines({ brandName: '客户矿业', equipmentName: '循环泵', equipmentCode: 'P-01', taskCode: 'DJ-1', itemName: '油位', executorName: '操作工01', faultLocationText: '机加二线', capturedAt })
	assert.equal(lines.length, 5)
	assert.match(lines.join(' '), /机加二线/)
	assert.match(lines.at(-1), /^2026-08-04 11:49:21 · 执行人 操作工01$/)
	assert.equal(formatBusinessDateTime(capturedAt), '2026-08-04 11:49:21')
	assert.doesNotMatch(lines.at(-1), /Tue|GMT|CST/)
	assert.doesNotMatch(lines.join(' '), /GPS|经度|纬度/)
})

test('enforces minimum Android version codes', () => {
	assert.equal(compareVersionCodes(99, 100).upgradeRequired, true)
	assert.equal(compareVersionCodes('100', 100).upgradeRequired, false)
	assert.equal(compareVersionCodes(101, 100).upgradeRequired, false)
})

test('preserves API conflict semantics', () => {
	const error = new ApiError('TASK_ALREADY_COMPLETED', '任务已经完成', 409)
	assert.equal(errorMessage(error), '任务已经完成')
	assert.equal(isConflict(error), true)
})

test('uses a readable fallback in the HBuilder Android standard base', () => {
	globalThis.plus = { os: { name: 'Android' }, runtime: { appid: 'HBuilder' } }
	secureStorage.secureSet('debug-token', { token: 'fresh-token' })
	assert.match(values.get('leantpm_secure_debug-token'), /^S1:/)
	assert.deepEqual(secureStorage.secureGet('debug-token'), { token: 'fresh-token' })
	storage.setStored(storage.STORAGE_KEYS.rememberedCredentials, { username: 'admin', password: '888888' })
	assert.deepEqual(
		storage.getStored(storage.STORAGE_KEYS.rememberedCredentials),
		{ username: 'admin', password: '888888' }
	)
	delete globalThis.plus
})
