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
	const original = record.originalPath
		? await upload(record.originalPath, 'MOBILE_PHOTO_ORIGINAL', record.taskId, `${record.id}-original`)
		: null
	const watermarked = record.watermarkedPath
		? await upload(record.watermarkedPath, 'MOBILE_INSPECTION_WATERMARK', record.taskId, `${record.id}-watermarked`)
		: null
	const evidence = await mobileApi.registerPhotoEvidence({
		...record.metadata,
		originalAttachmentId: original?.id || null,
		watermarkedAttachmentId: watermarked?.id || null
	}, `${record.id}-evidence`)
	const attachment = watermarked || original
	if (!attachment) throw new Error('没有可上传的现场照片')
	return { attachmentId: attachment.id, evidence, attachment }
}

export function newPhotoQueueId(taskId, taskItemId) {
	return `${createIdempotencyKey('photo')}-${taskId}-${taskItemId}`
}
