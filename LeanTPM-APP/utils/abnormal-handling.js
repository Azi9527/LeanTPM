import { ApiError, errorMessage } from './errors.js'

const retainedMessage = '当前填写内容仍保留在本页面。'

export function abnormalHandlingErrorMessage(error) {
	if (!(error instanceof ApiError)) {
		return `${errorMessage(error, '异常处置登记保存失败')}；${retainedMessage}`
	}
	if (error.statusCode === 404 || error.statusCode === 405
		|| ['HTTP_404', 'HTTP_405', 'METHOD_NOT_ALLOWED'].includes(error.code)) {
		return `当前 Backend 尚未发布 APP 异常处置登记接口，请先发布匹配版本的 Backend 并将数据库升级到 V53。${retainedMessage}`
	}
	if (error.statusCode === 403 || error.code === 'FORBIDDEN') {
		return `当前账号没有异常处置登记权限，请联系管理员授予“点检异常处理”权限。${retainedMessage}`
	}
	if (error.statusCode === 409 || error.code === 'OPTIMISTIC_LOCK_CONFLICT') {
		return `该异常已被其他人修改，请关闭详情并刷新异常清单后重新登记。${retainedMessage}`
	}
	if (error.code === 'NETWORK_ERROR' || error.code === 'SERVICE_UNAVAILABLE'
		|| error.statusCode === 0 || error.statusCode >= 500) {
		return `异常处置登记接口连接失败。若异常清单仍可正常刷新，说明手机网络与基础服务正常；请检查 Backend 是否已发布匹配版本、反向代理是否放行该接口，并查看 Backend 日志。${retainedMessage}`
	}
	return `${errorMessage(error, '异常处置登记保存失败')}；${retainedMessage}`
}
