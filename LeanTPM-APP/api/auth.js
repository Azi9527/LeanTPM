import { apiRequest } from './request.js'

export const authApi = Object.freeze({
	captcha: () => apiRequest({ path: '/auth/captcha', auth: false }),
	login: (username, password, captchaId, captchaCode) => apiRequest({
		path: '/auth/login',
		method: 'POST',
		auth: false,
		data: { username, password, captchaId, captchaCode }
	}),
	currentUser: () => apiRequest({ path: '/auth/me' }),
	changePassword: (currentPassword, newPassword) => apiRequest({
		path: '/auth/password',
		method: 'PUT',
		data: { currentPassword, newPassword }
	}),
	logout: () => apiRequest({ path: '/auth/logout', method: 'POST' })
})
