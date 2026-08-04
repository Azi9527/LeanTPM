import { apiRequest } from './request.js'

export const equipmentApi = Object.freeze({
	page: (query = {}) => apiRequest({ path: '/equipment', data: query }),
	detail: (id) => apiRequest({ path: `/equipment/${id}` })
})
