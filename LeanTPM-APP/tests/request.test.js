import test from 'node:test'
import assert from 'node:assert/strict'

const values = new Map()
const requests = []
let protectedCalls = 0
let refreshFailureStatus = 0
let authFailureCalls = 0

globalThis.uni = {
	getStorageSync: (key) => values.get(key) ?? '',
	setStorageSync: (key, value) => values.set(key, value),
	removeStorageSync: (key) => values.delete(key),
	request: (options) => {
		requests.push(options)
		if (options.url.endsWith('/auth/refresh')) {
			if (refreshFailureStatus) {
				options.success({ statusCode: refreshFailureStatus, data: { code: 'AUTH_STATE_UNAVAILABLE' } })
				return
			}
			options.success({
				statusCode: 200,
				data: { code: 'OK', data: { accessToken: 'new-access', refreshToken: 'new-refresh' } }
			})
			return
		}
		protectedCalls += 1
		if (protectedCalls === 1) {
			options.success({ statusCode: 401, data: { code: 'TOKEN_EXPIRED', message: 'expired' } })
			return
		}
		options.success({ statusCode: 200, data: { code: 'OK', data: { value: 42 } } })
	}
}

const storage = await import('../platform/storage.js')
const request = await import('../api/request.js')

test('refreshes an expired access token and retries once', async () => {
	values.clear()
	requests.length = 0
	protectedCalls = 0
	refreshFailureStatus = 0
	storage.setStored(storage.STORAGE_KEYS.serverBaseUrl, 'https://tpm.example.com/api/v1')
	request.storeTokens({ accessToken: 'old-access', refreshToken: 'old-refresh' })

	const data = await request.apiRequest({ path: '/mobile/bootstrap' })
	assert.deepEqual(data, { value: 42 })
	assert.equal(requests.length, 3)
	assert.equal(requests[2].header.Authorization, 'Bearer new-access')
})

test('adds an idempotency key to mutating business requests', async () => {
	requests.length = 0
	protectedCalls = 1
	await request.apiRequest({ path: '/inspection/tasks/1/submit', method: 'POST', data: {} })
	assert.match(requests[0].header['Idempotency-Key'], /^app-/)
})

test('keeps the session when token refresh cannot reach the service', async () => {
	values.clear()
	requests.length = 0
	protectedCalls = 0
	refreshFailureStatus = 503
	authFailureCalls = 0
	storage.setStored(storage.STORAGE_KEYS.serverBaseUrl, 'https://tpm.example.com/api/v1')
	request.storeTokens({ accessToken: 'offline-access', refreshToken: 'offline-refresh' })
	request.configureAuthFailureHandler(() => { authFailureCalls += 1 })

	await assert.rejects(
		request.apiRequest({ path: '/mobile/bootstrap' }),
		/企业服务暂时不可用/
	)
	assert.equal(request.hasToken(), true)
	assert.equal(authFailureCalls, 0)
	refreshFailureStatus = 0
})
