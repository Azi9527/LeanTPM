import { apiRequest } from './request.js'

export const notificationApi = Object.freeze({
	messages: (query = {}) => apiRequest({ path: '/notifications/messages', data: query }),
	read: (id) => apiRequest({ path: `/notifications/messages/${id}/read`, method: 'POST' }),
	acknowledge: (id) => apiRequest({ path: `/notifications/messages/${id}/acknowledge`, method: 'POST' })
})
