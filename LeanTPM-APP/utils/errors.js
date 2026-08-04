export class ApiError extends Error {
	constructor(code, message, statusCode = 0, data = null) {
		super(message || '请求失败')
		this.name = 'ApiError'
		this.code = code || 'REQUEST_FAILED'
		this.statusCode = Number(statusCode || 0)
		this.data = data
	}
}

export function errorMessage(error, fallback = '操作失败，请稍后重试') {
	if (error instanceof ApiError || error instanceof Error) return error.message || fallback
	if (typeof error === 'string' && error.trim()) return error.trim()
	return fallback
}

export function isConflict(error) {
	return error instanceof ApiError && error.statusCode === 409
}
