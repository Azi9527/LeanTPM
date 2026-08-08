import { apiRequest } from './request.js'

export const mobileApi = Object.freeze({
	bootstrap: () => apiRequest({ path: '/mobile/bootstrap' }),
	personalInspectionReport: (query = {}) => apiRequest({ path: '/mobile/personal-inspection-report', data: query }),
	equipment: (token) => apiRequest({ path: `/mobile/equipment/${encodeURIComponent(token)}` }),
	createInspectionReport: (token, payload, idempotencyKey) => apiRequest({
		path: `/mobile/equipment/${encodeURIComponent(token)}/inspection-reports`,
		method: 'POST',
		data: payload,
		idempotencyKey
	}),
	registerPhotoEvidence: (payload, idempotencyKey) => apiRequest({
		path: '/mobile/photo-evidence',
		method: 'POST',
		data: payload,
		idempotencyKey
	})
})
