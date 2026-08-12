import { STORAGE_KEYS, getStored, setStored } from '../platform/storage.js'

function drafts() {
	const value = getStored(STORAGE_KEYS.drafts, [])
	return Array.isArray(value) ? value : []
}

function photos() {
	const value = getStored(STORAGE_KEYS.photoQueue, [])
	return Array.isArray(value) ? value : []
}

export function saveDraftEnvelope(envelope) {
	const next = drafts().filter((item) => !(item.workflow === envelope.workflow && item.taskId === envelope.taskId))
	next.push({ schemaVersion: 1, ...envelope, updatedAt: new Date().toISOString() })
	setStored(STORAGE_KEYS.drafts, next)
	return next[next.length - 1]
}

export function loadDraftEnvelope(workflow, taskId) {
	return drafts().find((item) => item.schemaVersion === 1 && item.workflow === workflow && item.taskId === Number(taskId)) || null
}

export function removeDraftEnvelope(workflow, taskId) {
	setStored(STORAGE_KEYS.drafts, drafts().filter((item) => !(item.workflow === workflow && item.taskId === Number(taskId))))
}

export function removeDraftEnvelopeIfCurrent(workflow, taskId, expectedRevision) {
	const current = loadDraftEnvelope(workflow, taskId)
	if (!current || !expectedRevision || current.revision !== expectedRevision) return false
	removeDraftEnvelope(workflow, taskId)
	return true
}

export function saveDraftEnvelopeIfCurrent(envelope, expectedRevision, expectedUpdatedAt = '') {
	const current = loadDraftEnvelope(envelope.workflow, envelope.taskId)
	if (!current) return null
	const revisionMatches = Boolean(expectedRevision) && current.revision === expectedRevision
	const legacySnapshotMatches = !expectedRevision && !current.revision && Boolean(expectedUpdatedAt) && current.updatedAt === expectedUpdatedAt
	if (!revisionMatches && !legacySnapshotMatches) return null
	return saveDraftEnvelope(envelope)
}

export function markDraftSubmissionConfirmationRequired(workflow, taskId, revision) {
	const current = loadDraftEnvelope(workflow, taskId)
	if (!current || !revision) return null
	return saveDraftEnvelope({ ...current, pendingSubmit: false, requiresSubmissionConfirmation: true, revision })
}

export function listDraftEnvelopes() { return drafts().slice().sort((a, b) => String(a.updatedAt).localeCompare(String(b.updatedAt))) }

export function queuePhoto(record) {
	const next = photos().filter((item) => item.id !== record.id)
	next.push({ ...record, createdAt: record.createdAt || new Date().toISOString() })
	setStored(STORAGE_KEYS.photoQueue, next)
	return record
}

export function listQueuedPhotos() { return photos().slice().sort((a, b) => String(a.createdAt).localeCompare(String(b.createdAt))) }

export function removeQueuedPhoto(id) {
	setStored(STORAGE_KEYS.photoQueue, photos().filter((item) => item.id !== id))
}

export function attachQueuedPhotoToDraft(record, attachmentId) {
	const envelope = loadDraftEnvelope(record.workflow, record.taskId)
	if (!envelope?.payload?.results) return false
	if (record.taskItemId === null || record.taskItemId === undefined) {
		envelope.payload.taskAttachmentIds = Array.from(new Set([
			...(envelope.payload.taskAttachmentIds || []).filter((id) => id !== `queued:${record.id}`),
			attachmentId
		]))
		saveDraftEnvelope(envelope)
		return true
	}
	const result = envelope.payload.results.find((item) => item.taskItemId === record.taskItemId)
	if (!result) return false
	result.attachmentIds = Array.from(new Set([
		...(result.attachmentIds || []).filter((id) => id !== `queued:${record.id}`),
		attachmentId
	]))
	saveDraftEnvelope(envelope)
	return true
}

export function pendingWorkCount() { return drafts().length + photos().length }
