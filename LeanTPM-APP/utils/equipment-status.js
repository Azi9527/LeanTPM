import { ApiError, errorMessage } from './errors.js'

function diagnosticCode(error) {
	if (error.statusCode) return `HTTP ${error.statusCode}`
	return error.code && error.code !== 'REQUEST_FAILED' ? error.code : '请求失败'
}

export function equipmentStatusErrorMessage(error) {
	if (!(error instanceof ApiError)) {
		return errorMessage(error, '设备清单加载失败，请稍后重试')
	}
	if (error.statusCode === 404 || error.statusCode === 405
		|| ['HTTP_404', 'HTTP_405', 'METHOD_NOT_ALLOWED'].includes(error.code)) {
		return '当前 Backend 尚未发布设备状态接口，请先发布与本 APP 匹配的 Backend 版本。'
	}
	if (error.statusCode === 403 || error.code === 'FORBIDDEN') {
		return '当前账号没有“移动端设备扫码查看”权限，请联系管理员检查角色权限和设备数据范围。'
	}
	if (error.code === 'NETWORK_ERROR' || error.code === 'SERVICE_UNAVAILABLE'
		|| error.statusCode === 0 || error.statusCode >= 500) {
		return `设备状态专用接口响应异常（${diagnosticCode(error)}）。如果其他功能正常，说明手机网络和登录状态通常正常；请检查 Backend 是否已发布该接口、反向代理是否放行，并查看 Backend 日志。`
	}
	return errorMessage(error, '设备清单加载失败，请稍后重试')
}
