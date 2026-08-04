import { createIdempotencyKey } from '../utils/idempotency.js'
import { ApiError } from '../utils/errors.js'
import {
	STORAGE_KEYS,
	clearSessionStorage,
	getStored,
	setStored
} from '../platform/storage.js'

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])
let refreshPromise = null
let authFailureHandler = null

export function configureAuthFailureHandler(handler) {
	authFailureHandler = typeof handler === 'function' ? handler : null
}

export function getAccessToken() {
	return getStored(STORAGE_KEYS.accessToken, '')
}

export function getRefreshToken() {
	return getStored(STORAGE_KEYS.refreshToken, '')
}

export function storeTokens(tokens) {
	if (!tokens?.accessToken || !tokens?.refreshToken) {
		throw new ApiError('TOKEN_INVALID', '服务器返回的登录令牌不完整')
	}
	setStored(STORAGE_KEYS.accessToken, tokens.accessToken)
	setStored(STORAGE_KEYS.refreshToken, tokens.refreshToken)
	setStored(STORAGE_KEYS.accessExpiresAt, tokens.accessExpiresAt || '')
	setStored(STORAGE_KEYS.refreshExpiresAt, tokens.refreshExpiresAt || '')
}

export function clearTokens() {
	clearSessionStorage()
}

export function hasToken() {
	return Boolean(getAccessToken())
}

export function apiBaseUrl() {
	const value = getStored(STORAGE_KEYS.serverBaseUrl, '')
	if (!value) throw new ApiError('SERVER_NOT_CONFIGURED', '请先配置企业服务地址')
	return value
}

export function absoluteApiUrl(path) {
	if (/^https?:\/\//i.test(path)) return path
	return `${apiBaseUrl()}${path.startsWith('/') ? path : `/${path}`}`
}

function rawRequest(options) {
	return new Promise((resolve, reject) => {
		uni.request({
			...options,
			success: resolve,
			fail: (error) => reject(new ApiError(
				'NETWORK_ERROR',
				error?.errMsg || '无法连接服务器，请检查网络和服务地址'
			))
		})
	})
}

function responseError(response) {
	const body = response?.data
	return new ApiError(
		body?.code || `HTTP_${response?.statusCode || 0}`,
		body?.message || `服务器返回状态 ${response?.statusCode || 0}`,
		response?.statusCode || 0,
		body?.data ?? null
	)
}

async function refreshTokens() {
	if (refreshPromise) return refreshPromise
	const refreshToken = getRefreshToken()
	if (!refreshToken) throw new ApiError('SESSION_EXPIRED', '登录状态已失效，请重新登录', 401)

	refreshPromise = rawRequest({
		url: absoluteApiUrl('/auth/refresh'),
		method: 'POST',
		data: { refreshToken },
		header: { 'Content-Type': 'application/json' },
		timeout: 15000
	}).then((response) => {
		if (response.statusCode < 200 || response.statusCode >= 300 || response.data?.code !== 'OK') {
			throw responseError(response)
		}
		storeTokens(response.data.data)
		return response.data.data
	}).finally(() => {
		refreshPromise = null
	})

	return refreshPromise
}

async function notifyAuthFailure(error) {
	clearTokens()
	if (authFailureHandler) await authFailureHandler(error)
}

export async function apiRequest({
	path,
	method = 'GET',
	data,
	headers = {},
	auth = true,
	retryUnauthorized = true,
	idempotencyKey,
	timeout = 15000
}) {
	const normalizedMethod = method.toUpperCase()
	const requestHeaders = { 'Content-Type': 'application/json', ...headers }
	if (auth && getAccessToken()) requestHeaders.Authorization = `Bearer ${getAccessToken()}`
	if (MUTATING_METHODS.has(normalizedMethod) && !path.startsWith('/auth/')) {
		requestHeaders['Idempotency-Key'] = idempotencyKey || createIdempotencyKey('app')
	}

	const response = await rawRequest({
		url: absoluteApiUrl(path),
		method: normalizedMethod,
		data,
		header: requestHeaders,
		timeout
	})

	if (response.statusCode === 401 && auth && retryUnauthorized) {
		try {
			await refreshTokens()
			return apiRequest({
				path,
				method: normalizedMethod,
				data,
				headers,
				auth,
				retryUnauthorized: false,
				idempotencyKey: requestHeaders['Idempotency-Key'],
				timeout
			})
		} catch (error) {
			await notifyAuthFailure(error)
			throw new ApiError('SESSION_EXPIRED', '登录状态已失效，请重新登录', 401)
		}
	}

	if (response.statusCode < 200 || response.statusCode >= 300) throw responseError(response)
	if (response.data?.code && response.data.code !== 'OK') throw responseError(response)
	return response.data?.data
}

export async function uploadFile({ path, filePath, name = 'file', formData = {}, idempotencyKey }) {
	const token = getAccessToken()
	return new Promise((resolve, reject) => {
		uni.uploadFile({
			url: absoluteApiUrl(path),
			filePath,
			name,
			formData,
			header: {
				Authorization: token ? `Bearer ${token}` : '',
				'Idempotency-Key': idempotencyKey || createIdempotencyKey('upload')
			},
			timeout: 30000,
			success: (response) => {
				let body
				try {
					body = typeof response.data === 'string' ? JSON.parse(response.data) : response.data
				} catch {
					reject(new ApiError('UPLOAD_RESPONSE_INVALID', '附件上传响应无法解析', response.statusCode))
					return
				}
				if (response.statusCode < 200 || response.statusCode >= 300 || body?.code !== 'OK') {
					reject(new ApiError(body?.code, body?.message || '附件上传失败', response.statusCode))
					return
				}
				resolve(body.data)
			},
			fail: (error) => reject(new ApiError('UPLOAD_FAILED', error?.errMsg || '附件上传失败'))
		})
	})
}

export function downloadFile(path) {
	return new Promise((resolve, reject) => {
		uni.downloadFile({
			url: absoluteApiUrl(path),
			header: getAccessToken() ? { Authorization: `Bearer ${getAccessToken()}` } : {},
			timeout: 30000,
			success: (response) => {
				if (response.statusCode >= 200 && response.statusCode < 300) resolve(response.tempFilePath)
				else reject(new ApiError(`HTTP_${response.statusCode}`, '附件下载失败', response.statusCode))
			},
			fail: (error) => reject(new ApiError('DOWNLOAD_FAILED', error?.errMsg || '附件下载失败'))
		})
	})
}
