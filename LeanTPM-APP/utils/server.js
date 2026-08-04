import {
	STORAGE_KEYS,
	clearEnterpriseStorage,
	getStored,
	setStored
} from '../platform/storage.js'

export const API_BASE_URL_KEY = STORAGE_KEYS.serverBaseUrl

export const DEFAULT_SERVER_URL = 'http://192.168.31.91:18080'

export function normalizeServerBaseUrl(value) {
	const clean = String(value || '').trim().replace(/\/+$/, '')
	if (!/^https?:\/\/[^\s/]+(?::\d+)?(?:\/.*)?$/i.test(clean)) {
		throw new Error('请输入完整的 HTTP 或 HTTPS 服务地址')
	}
	return clean.endsWith('/api/v1') ? clean : `${clean}/api/v1`
}

export function getServerBaseUrl() {
	return getStored(API_BASE_URL_KEY, '')
}

export function getServerDisplayUrl() {
	return (getServerBaseUrl() || DEFAULT_SERVER_URL).replace(/\/api\/v1$/, '')
}

export function saveServerBaseUrl(value) {
	const normalized = normalizeServerBaseUrl(value)
	const previous = getServerBaseUrl()
	if (previous && previous !== normalized) clearEnterpriseStorage()
	setStored(API_BASE_URL_KEY, normalized)
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
	let response
	try {
		response = await sendRequest({
			url: `${normalizeServerBaseUrl(baseUrl)}/auth/captcha`,
			method: 'GET',
			timeout: 10000
		})
	} catch (error) {
		const detail = String(error?.errMsg || error?.message || '').trim()
		if (/url not in domain list|domain list/i.test(detail)) {
			throw new Error('当前域名未加入微信小程序 request 合法域名，请在微信公众平台配置后重试')
		}
		throw new Error(detail ? `网络请求失败：${detail}` : '网络请求失败，请检查服务地址和手机网络')
	}
	if (response.statusCode !== 200 || !response.data || response.data.code !== 'OK') {
		throw new Error(response.data?.message || `服务器返回状态 ${response.statusCode}`)
	}
	return response.data.data
}
