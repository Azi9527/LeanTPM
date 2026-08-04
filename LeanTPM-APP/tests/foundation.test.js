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

test('preserves API conflict semantics', () => {
	const error = new ApiError('TASK_ALREADY_COMPLETED', '任务已经完成', 409)
	assert.equal(errorMessage(error), '任务已经完成')
	assert.equal(isConflict(error), true)
})
