import { apiRequest } from './request.js'

export const publicApi = Object.freeze({
	branding: () => apiRequest({ path: '/public/branding', auth: false, timeout: 5000 })
})
