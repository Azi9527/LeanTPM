import { apiRequest } from './request.js'

export const inspectionApi = Object.freeze({
	createTask: (payload, idempotencyKey) => apiRequest({
		path: '/inspection/tasks',
		method: 'POST',
		data: payload,
		idempotencyKey
	}),
	tasks: (query = {}) => apiRequest({ path: '/inspection/tasks', data: query }),
	task: (id) => apiRequest({ path: `/inspection/tasks/${id}` }),
	saveDraft: (id, payload) => apiRequest({ path: `/inspection/tasks/${id}/draft`, method: 'PUT', data: payload }),
	submitTask: (id, payload, idempotencyKey) => apiRequest({ path: `/inspection/tasks/${id}/submit`, method: 'POST', data: payload, idempotencyKey }),
	taskAttachments: (id) => apiRequest({ path: `/inspection/tasks/${id}/attachments` }),
	abnormalities: (query = {}) => apiRequest({ path: '/inspection/abnormalities', data: query }),
	abnormalAttachments: (id) => apiRequest({ path: `/inspection/abnormalities/${id}/attachments` })
})
