const COMPLETED_STATUSES = new Set(['PENDING_REVIEW', 'COMPLETED', 'CLOSED'])

export function inspectionConflictResolution(error, task) {
	const completed = COMPLETED_STATUSES.has(String(task?.taskStatus || '').toUpperCase())
	return {
		completed,
		preserveDraft: !completed,
		rotateIdempotencyKey: !completed
	}
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
