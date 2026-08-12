import { apiRequest } from './request.js'

export const mobileApi = Object.freeze({
	androidRelease: () => apiRequest({
		path: '/public/app/android/latest',
		auth: false
	}),
	bootstrap: () => apiRequest({ path: '/mobile/bootstrap' }),
	personalInspectionReport: (query = {}) => apiRequest({ path: '/mobile/personal-inspection-report', data: query }),
	equipmentStatus: (query = {}) => apiRequest({ path: '/mobile/equipment-status', data: query }),
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
