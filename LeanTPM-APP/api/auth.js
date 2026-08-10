import { apiRequest } from './request.js'

export const authApi = Object.freeze({
	login: (username, password) => apiRequest({
		path: '/auth/login',
		method: 'POST',
		auth: false,
		data: { username, password }
	}),
	currentUser: () => apiRequest({ path: '/auth/me' }),
	changePassword: (currentPassword, newPassword) => apiRequest({
		path: '/auth/password',
		method: 'PUT',
		data: { currentPassword, newPassword }
	}),
	logout: () => apiRequest({ path: '/auth/logout', method: 'POST' })
})
