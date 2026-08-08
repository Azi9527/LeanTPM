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

export function equipmentScanErrorMessage(error) {
	if (!(error instanceof ApiError)) {
		return errorMessage(error, '设备二维码识别失败，请重新扫描设备当前有效标签')
	}
	if (error.code === 'FORBIDDEN') {
		return '当前账号未授予“设备扫码查看”功能权限，请联系系统管理员为所属角色开启移动端设备扫码权限。'
	}
	const knownCodes = new Set([
		'MOBILE_BARCODE_INVALID',
		'MOBILE_BARCODE_EXPIRED',
		'MOBILE_EQUIPMENT_ARCHIVED',
		'MOBILE_EQUIPMENT_DISABLED',
		'MOBILE_EQUIPMENT_ORGANIZATION_DISABLED',
		'MOBILE_EQUIPMENT_DATA_SCOPE_DENIED',
		'MOBILE_ACCESS_DISABLED'
	])
	if (knownCodes.has(error.code)) return error.message
	if (error.statusCode === 404) {
		return '未找到该设备二维码。请确认二维码完整、属于当前企业，并扫描设备上的最新标签。'
	}
	return errorMessage(error, '设备二维码识别失败，请重新扫描或联系设备管理员')
}

export function isConflict(error) {
	return error instanceof ApiError && error.statusCode === 409
}

export function isServiceUnavailable(error) {
	if (!(error instanceof ApiError)) return false
	return error.code === 'NETWORK_ERROR'
		|| error.code === 'SERVICE_UNAVAILABLE'
		|| error.statusCode === 0
		|| error.statusCode >= 500
}

export function isAuthenticationFailure(error) {
	if (!(error instanceof ApiError)) return false
	return error.statusCode === 401 || [
		'SESSION_EXPIRED',
		'TOKEN_EXPIRED',
		'TOKEN_INVALID',
		'INVALID_REFRESH_TOKEN'
	].includes(error.code)
}
