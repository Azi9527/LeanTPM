import { apiRequest, downloadFile } from './request.js'

export const notificationApi = Object.freeze({
	messages: (query = {}) => apiRequest({ path: '/notifications/messages', data: query }),
	businessDetail: (id) => apiRequest({ path: `/notifications/messages/${id}/business-detail` }),
	businessAttachmentContent: (messageId, attachmentId) => downloadFile(
		`/notifications/messages/${messageId}/attachments/${attachmentId}/content`
	),
	read: (id) => apiRequest({ path: `/notifications/messages/${id}/read`, method: 'POST' }),
	acknowledge: (id) => apiRequest({ path: `/notifications/messages/${id}/acknowledge`, method: 'POST' })
})
