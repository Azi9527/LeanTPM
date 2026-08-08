import { mobileApi, type PhotoEvidence } from '@/api/mobile'
import { systemApi } from '@/api/system'
import type { CapturedPhotoEvidence } from './device'

export async function uploadPhotoEvidence(
  capture: CapturedPhotoEvidence,
): Promise<{ attachmentId: number, evidence: PhotoEvidence }> {
  const original = capture.originalFile ? await upload(
    capture.originalFile, 'MOBILE_PHOTO_ORIGINAL', capture.metadata.taskId,
  ) : undefined
  const watermarked = capture.watermarkedFile ? await upload(
    capture.watermarkedFile, `MOBILE_${capture.metadata.workflowType}_WATERMARK`, capture.metadata.taskId,
  ) : undefined
  const retained = watermarked ?? original
  if (!retained) throw new Error('没有可上传的现场照片')
  const evidence = await mobileApi.registerPhotoEvidence({
    ...capture.metadata,
    originalAttachmentId: original?.id,
    watermarkedAttachmentId: watermarked?.id,
  }, evidenceKey(capture, retained.id))
  return { attachmentId: retained.id, evidence }
}

async function upload(file: File, businessType: string, businessId: number) {
  const form = new FormData()
  form.append('file', file)
  form.append('businessType', businessType)
  form.append('businessId', String(businessId))
  const response = await systemApi.uploadAttachment(form)
  return response.data.data
}

function evidenceKey(capture: CapturedPhotoEvidence, attachmentId: number): string {
  return [
    'mobile-evidence', capture.metadata.workflowType, capture.metadata.taskId,
    capture.metadata.taskItemId, attachmentId,
  ].join('-')
}
