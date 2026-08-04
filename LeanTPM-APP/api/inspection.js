import { apiRequest } from './request.js'

export const inspectionApi = Object.freeze({
	createTask: (payload, idempotencyKey) => apiRequest({
		path: '/inspection/tasks',
		method: 'POST',
		data: payload,
		idempotencyKey
	}),
	tasks: (query = {}) => apiRequest({ path: '/inspection/tasks', data: query }),
	task: (id) => apiRequest({ path: `/inspection/tasks/${id}` })
})
