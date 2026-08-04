export const ROUTES = Object.freeze({
	launch: '/pages/launch/index',
	setup: '/pages/setup/index',
	login: '/pages/login/index',
	workbench: '/pages/workbench/index',
	equipmentStatus: '/pages/equipment/status',
	scan: '/pages/scan/index',
	equipmentContext: '/pages/equipment/context',
	inspectionTasks: '/pages/inspection/index',
	abnormalities: '/pages/abnormal/index',
	messages: '/pages/messages/index',
	report: '/pages/report/index',
	profile: '/pages/profile/index'
})

export function reLaunchTo(url) {
	return new Promise((resolve, reject) => {
		uni.reLaunch({ url, success: resolve, fail: reject })
	})
}

export function navigateTo(url) {
	return new Promise((resolve, reject) => {
		uni.navigateTo({ url, success: resolve, fail: reject })
	})
}

export function routeWithQuery(path, query = {}) {
	const entries = Object.entries(query).filter(([, value]) => value !== undefined && value !== null && value !== '')
	if (!entries.length) return path
	const serialized = entries.map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
	return `${path}?${serialized.join('&')}`
}
