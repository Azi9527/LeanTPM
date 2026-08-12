import { inspectionApi } from '../api/inspection.js'
import { connected, onNetworkReconnect } from '../platform/network.js'
import { removeSavedFile } from '../platform/photo.js'
import { attachQueuedPhotoToDraft, listDraftEnvelopes, listQueuedPhotos, markDraftSubmissionConfirmationRequired, removeDraftEnvelopeIfCurrent, removeQueuedPhoto, saveDraftEnvelopeIfCurrent } from '../stores/offline.js'
import { bindIdempotencyKeyToPayload, createIdempotencyKey } from '../utils/idempotency.js'
import { isConflict } from '../utils/errors.js'
import { inspectionOfflineConflictResolution, inspectionSubmitFailureNeedsConfirmation } from '../utils/inspection-conflict.js'
import { uploadPhotoEvidence } from './photo-evidence.js'

let syncing = null
let initialized = false

export function initializeOfflineSync() {
	if (initialized) return
	initialized = true
	onNetworkReconnect(() => syncPendingWork())
	if (connected.value) syncPendingWork().catch(() => {})
}

export function syncPendingWork() {
	if (syncing) return syncing
	if (!connected.value) return Promise.resolve({ photos: 0, drafts: 0 })
	syncing = doSync().finally(() => { syncing = null })
	return syncing
}

async function doSync() {
	let photoCount = 0
	let draftCount = 0
	for (const photo of listQueuedPhotos()) {
		try {
			const uploaded = await uploadPhotoEvidence(photo)
			attachQueuedPhotoToDraft(photo, uploaded.attachmentId)
			removeQueuedPhoto(photo.id)
			removeSavedFile(photo.originalPath)
			if (photo.watermarkedPath !== photo.originalPath) removeSavedFile(photo.watermarkedPath)
			photoCount += 1
		} catch { break }
	}
	if (listQueuedPhotos().length) return { photos: photoCount, drafts: draftCount }
	for (const draft of listDraftEnvelopes()) {
		if (!draft.pendingSubmit || draft.workflow !== 'inspection') continue
		if (draft.requiresSubmissionConfirmation) continue
		let prepared = draft
		try {
			const binding = bindIdempotencyKeyToPayload({
				idempotencyKey: draft.idempotencyKey,
				payloadSignature: draft.submissionPayloadSignature || '',
				payload: draft.payload,
				legacyPendingSubmit: !draft.submissionPayloadSignature,
				scope: `inspection-${draft.taskId}`
			})
			prepared = saveDraftEnvelopeIfCurrent({
				...draft,
				idempotencyKey: binding.idempotencyKey,
				submissionPayloadSignature: binding.payloadSignature,
				revision: createIdempotencyKey(`inspection-sync-${draft.taskId}`)
			}, draft.revision, draft.updatedAt)
			if (!prepared) continue
			await inspectionApi.submitTask(prepared.taskId, prepared.payload, prepared.idempotencyKey)
			if (removeDraftEnvelopeIfCurrent(draft.workflow, draft.taskId, prepared.revision)) draftCount += 1
			else markDraftSubmissionConfirmationRequired(draft.workflow, draft.taskId, createIdempotencyKey(`inspection-confirm-${draft.taskId}`))
		} catch (error) {
			if (isConflict(error)) {
				try {
					const latest = await inspectionApi.task(draft.taskId)
					const resolution = inspectionOfflineConflictResolution(error, latest?.task)
					if (resolution.completed) {
						if (removeDraftEnvelopeIfCurrent(draft.workflow, draft.taskId, prepared.revision)) draftCount += 1
						else markDraftSubmissionConfirmationRequired(draft.workflow, draft.taskId, createIdempotencyKey(`inspection-confirm-${draft.taskId}`))
						continue
					}
					if (resolution.requiresUserConfirmation) {
						const current = markDraftSubmissionConfirmationRequired(draft.workflow, draft.taskId, createIdempotencyKey(`inspection-confirm-${draft.taskId}`))
						if (current) prepared = current
						break
					}
					break
				} catch {
					markDraftSubmissionConfirmationRequired(
						draft.workflow,
						draft.taskId,
						createIdempotencyKey(`inspection-confirm-${draft.taskId}`)
					)
				}
			} else if (inspectionSubmitFailureNeedsConfirmation(error)) {
				markDraftSubmissionConfirmationRequired(draft.workflow, draft.taskId, createIdempotencyKey(`inspection-confirm-${draft.taskId}`))
			}
			break
		}
	}
	return { photos: photoCount, drafts: draftCount }
}
