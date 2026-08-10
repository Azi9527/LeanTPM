import test from 'node:test'
import assert from 'node:assert/strict'

const values = new Map()
const requests = []

globalThis.uni = {
	getStorageSync: (key) => values.get(key) ?? '',
	setStorageSync: (key, value) => values.set(key, value),
	removeStorageSync: (key) => values.delete(key),
	request: (options) => {
		requests.push(options)
		options.success({
			statusCode: 200,
			data: {
				code: 'OK',
				data: {
					tokens: { accessToken: 'access', refreshToken: 'refresh' },
					user: { id: 1, username: 'operator' }
				}
			}
		})
	}
}

const storage = await import('../platform/storage.js')
const { authApi } = await import('../api/auth.js')

test('APP login sends only username and password and exposes no captcha API', async () => {
	storage.setStored(storage.STORAGE_KEYS.serverBaseUrl, 'https://tpm.example.com/api/v1')
	requests.length = 0

	assert.equal('captcha' in authApi, false)
	await authApi.login('operator', 'secret')
	assert.equal(requests.length, 1)
	assert.equal(requests[0].url, 'https://tpm.example.com/api/v1/auth/login')
	assert.deepEqual(requests[0].data, { username: 'operator', password: 'secret' })
})
