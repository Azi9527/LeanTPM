export const API_BASE_URL_KEY = 'leantpm_api_base_url'

export const DEFAULT_SERVER_URL = 'http://192.168.31.91:18080'

export function normalizeServerBaseUrl(value) {
	const clean = String(value || '').trim().replace(/\/+$/, '')
	if (!/^https?:\/\/[^\s/]+(?::\d+)?(?:\/.*)?$/i.test(clean)) {
		throw new Error('请输入完整的 HTTP 或 HTTPS 服务地址')
	}
	return clean.endsWith('/api/v1') ? clean : `${clean}/api/v1`
}

export function getServerBaseUrl() {
	return uni.getStorageSync(API_BASE_URL_KEY) || ''
}

export function getServerDisplayUrl() {
	return (getServerBaseUrl() || DEFAULT_SERVER_URL).replace(/\/api\/v1$/, '')
}

export function saveServerBaseUrl(value) {
	const normalized = normalizeServerBaseUrl(value)
	uni.setStorageSync(API_BASE_URL_KEY, normalized)
	return normalized
}

function sendRequest(options) {
	return new Promise((resolve, reject) => {
		uni.request({
			...options,
			success: resolve,
			fail: reject
		})
	})
}

export async function testServerConnection(baseUrl) {
	const response = await sendRequest({
		url: `${normalizeServerBaseUrl(baseUrl)}/auth/captcha`,
		method: 'GET',
		timeout: 10000
	})
	if (response.statusCode !== 200 || !response.data || response.data.code !== 'OK') {
		throw new Error(response.data?.message || `服务器返回状态 ${response.statusCode}`)
	}
	return response.data.data
}
