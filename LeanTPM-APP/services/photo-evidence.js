import { mobileApi } from '../api/mobile.js'
import { uploadFile } from '../api/request.js'
import { createIdempotencyKey } from '../utils/idempotency.js'

async function upload(path, businessType, businessId, idempotencyKey) {
	return uploadFile({
		path: '/system/attachments',
		filePath: path,
		formData: { businessType, businessId: String(businessId) },
		idempotencyKey
	})
}

export async function uploadPhotoEvidence(record) {
	const original = await upload(record.originalPath, 'MOBILE_PHOTO_ORIGINAL', record.taskId, `${record.id}-original`)
	const watermarked = await upload(record.watermarkedPath, 'MOBILE_INSPECTION_WATERMARK', record.taskId, `${record.id}-watermarked`)
	const evidence = await mobileApi.registerPhotoEvidence({
		...record.metadata,
		originalAttachmentId: original.id,
		watermarkedAttachmentId: watermarked.id
	}, `${record.id}-evidence`)
	return { attachmentId: watermarked.id, evidence, attachment: watermarked }
}

export function newPhotoQueueId(taskId, taskItemId) {
	return `${createIdempotencyKey('photo')}-${taskId}-${taskItemId}`
}
