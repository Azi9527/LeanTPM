import { inspectionApi } from '../api/inspection.js'
import { connected, onNetworkReconnect } from '../platform/network.js'
import { removeSavedFile } from '../platform/photo.js'
import { attachQueuedPhotoToDraft, listDraftEnvelopes, listQueuedPhotos, removeDraftEnvelope, removeQueuedPhoto, saveDraftEnvelope } from '../stores/offline.js'
import { createIdempotencyKey } from '../utils/idempotency.js'
import { isConflict } from '../utils/errors.js'
import { inspectionConflictResolution, rebaseInspectionDraft } from '../utils/inspection-conflict.js'
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
		try {
			await inspectionApi.submitTask(draft.taskId, draft.payload, draft.idempotencyKey)
			removeDraftEnvelope(draft.workflow, draft.taskId)
			draftCount += 1
		} catch (error) {
			if (isConflict(error)) {
				try {
					const latest = await inspectionApi.task(draft.taskId)
					if (inspectionConflictResolution(error, latest?.task).completed) {
						removeDraftEnvelope(draft.workflow, draft.taskId)
						draftCount += 1
						continue
					}
					const rebased = rebaseInspectionDraft(draft, latest.task.version, latest.items)
					if (rebased) saveDraftEnvelope({
						...rebased,
						idempotencyKey: createIdempotencyKey(`inspection-${draft.taskId}`)
					})
				} catch {}
			}
			break
		}
	}
	return { photos: photoCount, drafts: draftCount }
}
