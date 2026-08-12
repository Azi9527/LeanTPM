const COMPLETED_STATUSES = new Set(['PENDING_REVIEW', 'COMPLETED', 'CLOSED'])
const UNCERTAIN_IDEMPOTENCY_CODES = new Set([
	'IDEMPOTENCY_RESULT_UNKNOWN',
	'IDEMPOTENCY_STATE_LOST',
	'IDEMPOTENCY_RESPONSE_SERIALIZATION_FAILED',
	'IDEMPOTENCY_RESPONSE_TOO_LARGE',
	'IDEMPOTENCY_STATE_INVALID'
])

export function inspectionConflictResolution(error, task) {
	const completed = COMPLETED_STATUSES.has(String(task?.taskStatus || '').toUpperCase())
	const code = String(error?.code || '').toUpperCase()
	const requestInProgress = code === 'REQUEST_IN_PROGRESS'
	const resultUnknown = UNCERTAIN_IDEMPOTENCY_CODES.has(code)
	return {
		completed,
		preserveDraft: !completed,
		rotateIdempotencyKey: !completed && !requestInProgress && !resultUnknown,
		rebaseDraft: !completed && !requestInProgress && !resultUnknown,
		requiresUserConfirmation: !completed && resultUnknown
	}
}

export function inspectionOfflineConflictResolution(error, task) {
	const resolution = inspectionConflictResolution(error, task)
	if (resolution.completed) return resolution
	return {
		...resolution,
		rotateIdempotencyKey: false,
		rebaseDraft: false,
		requiresUserConfirmation: true
	}
}

export function inspectionPhotoSyncDecision(submit, updatedDraft) {
	if (!submit) return 'CONTINUE_SAVE'
	return updatedDraft ? 'RETRY_REQUIRED' : 'SUBMITTED_BY_SYNC'
}

export function shouldPreserveLocalInspectionDraft(task, draft) {
	return Boolean(draft) && COMPLETED_STATUSES.has(String(task?.taskStatus || '').toUpperCase())
}

export function inspectionSubmitFailureNeedsConfirmation(error) {
	return Number(error?.statusCode || 0) !== 409
}

export function rebaseInspectionDraft(draft, taskVersion, serverItems = []) {
	if (!draft) return null
	const version = Number(taskVersion)
	if (!Number.isFinite(version)) return draft
	const resultVersions = new Map()
	for (const item of serverItems || []) {
		const resultVersion = item?.result?.version
		if (resultVersion !== undefined && resultVersion !== null) {
			resultVersions.set(String(item.id), resultVersion)
		}
	}
	const payload = draft.payload || {}
	const results = (payload.results || []).map((result) => {
		const resultVersion = resultVersions.get(String(result.taskItemId))
		return resultVersion === undefined ? result : { ...result, version: resultVersion }
	})
	return {
		...draft,
		taskVersion: version,
		payload: { ...payload, taskVersion: version, results }
	}
}
