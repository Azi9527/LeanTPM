export function resultOptions(item) {
	if (!item?.resultOptionsJson) return []
	try {
		const value = JSON.parse(item.resultOptionsJson)
		return Array.isArray(value) ? value.map(String) : []
	} catch { return [] }
}

export function initialResultDraft(item) {
	const result = item?.result || {}
	let selectedValues = []
	try { selectedValues = JSON.parse(result.selectedValuesJson || '[]') } catch { selectedValues = [] }
	const abnormal = Boolean(result.abnormalFlag)
	return {
		resultCode: result.resultCode || '',
		numericValue: result.numericValue ?? '',
		textValue: result.textValue || '',
		selectedValue: result.selectedValue || '',
		selectedValues: Array.isArray(selectedValues) ? selectedValues : [],
		abnormal,
		abnormalDescription: result.abnormalDescription || '',
		equipmentStopRequired: result.equipmentStopRequired ?? (abnormal && Boolean(item.abnormalDefaultStopFlag)),
		stopOverrideReason: result.stopOverrideReason || '',
		skipped: Boolean(result.skippedFlag),
		skipReason: result.skipReason || '',
		attachmentIds: Array.isArray(result.attachmentIds) ? result.attachmentIds : [],
		version: result.version
	}
}

export function resultPhotoAttachmentIds(taskItemId, attachments = [], existingIds = [], item = null) {
	const currentQueuedIds = new Set((attachments || [])
		.filter((attachment) => String(attachment?.taskItemId) === String(taskItemId) && attachment?.attachmentType === 'RESULT_PHOTO' && /^queued:.+/.test(String(attachment.id || '')))
		.map((attachment) => String(attachment.id)))
	const result = (Array.isArray(existingIds) ? existingIds : []).filter((id) => {
		const rawId = String(id || '')
		if (currentQueuedIds.has(rawId)) return true
		const numericId = Number(rawId)
		return Number.isSafeInteger(numericId) && numericId > 0
	})
	const selected = new Set(result.map(String))
	for (const attachment of attachments || []) {
		const compatibleLegacyPhoto = isInspectionPhotoRequired(item) && attachment?.attachmentType === 'RESULT_ATTACHMENT'
		if (String(attachment?.taskItemId) !== String(taskItemId) || (attachment?.attachmentType !== 'RESULT_PHOTO' && !compatibleLegacyPhoto)) continue
		const rawId = String(attachment.id || '')
		const numericId = Number(rawId)
		const queuedId = /^queued:.+/.test(rawId)
		const attachmentId = queuedId ? rawId : numericId
		if ((!queuedId && (!Number.isSafeInteger(numericId) || numericId <= 0)) || selected.has(String(attachmentId))) continue
		result.push(attachmentId)
		selected.add(String(attachmentId))
	}
	return result
}

export function inferAbnormal(item, draft) {
	if (draft.skipped) return false
	if (['ABNORMAL', 'FAIL'].includes(draft.resultCode)) return true
	if (item.resultType === 'NUMBER' && draft.numericValue !== '' && draft.numericValue !== null) {
		const value = Number(draft.numericValue)
		if (Number.isFinite(value)) {
			if (item.minimumValue !== null && item.minimumValue !== undefined && value < Number(item.minimumValue)) return true
			if (item.maximumValue !== null && item.maximumValue !== undefined && value > Number(item.maximumValue)) return true
		}
	}
	return false
}

export function applyNumericAbnormalState(item, draft) {
	const abnormal = inferAbnormal(item, draft)
	draft.abnormal = abnormal
	draft.equipmentStopRequired = abnormal ? Boolean(item.abnormalDefaultStopFlag) : false
	if (!abnormal) {
		draft.abnormalDescription = ''
		draft.stopOverrideReason = ''
	}
	return abnormal
}

export function isInspectionPhotoRequired(item) {
	return Boolean(item?.photoRequiredFlag) || Number(item?.photoMinCount || 0) > 0
}

export function validateInspectionResults(items, drafts) {
	for (const item of items) {
		const draft = drafts[item.id]
		if (!draft) return `${item.itemName} 尚未填写`
		if (draft.skipped) {
			if (!item.skipAllowedFlag) return `${item.itemName} 不允许跳过`
			if (!String(draft.skipReason || '').trim()) return `${item.itemName} 必须填写跳过原因`
			continue
		}
		if (['NORMAL_ABNORMAL', 'PASS_FAIL'].includes(item.resultType) && !draft.resultCode) return `${item.itemName} 必须选择结果`
		if (item.resultType === 'NUMBER' && (draft.numericValue === '' || !Number.isFinite(Number(draft.numericValue)))) return `${item.itemName} 必须填写有效数值`
		if (item.resultType === 'TEXT' && !String(draft.textValue || '').trim()) return `${item.itemName} 必须填写结果`
		if (item.resultType === 'SINGLE_CHOICE' && !draft.selectedValue) return `${item.itemName} 必须选择结果`
		if (item.resultType === 'MULTIPLE_CHOICE' && !draft.selectedValues?.length) return `${item.itemName} 必须至少选择一项`
		if (draft.abnormal && !String(draft.abnormalDescription || '').trim()) return `${item.itemName} 的异常必须填写说明`
		if (draft.abnormal && Boolean(draft.equipmentStopRequired) !== Boolean(item.abnormalDefaultStopFlag) && !String(draft.stopOverrideReason || '').trim()) return `${item.itemName} 调整停机规则时必须填写原因`
		if (isInspectionPhotoRequired(item) && (draft.attachmentIds?.length || 0) < Number(item.photoMinCount || 1)) return `${item.itemName} 至少需要 ${item.photoMinCount || 1} 张照片`
	}
	return ''
}

export function buildInspectionPayload(task, items, drafts, executionRemark = '', taskAttachmentIds = []) {
	return {
		taskVersion: task.version,
		executionRemark: executionRemark.trim() || null,
		taskAttachmentIds: (taskAttachmentIds || [])
			.filter((id) => Number.isSafeInteger(Number(id)) && Number(id) > 0)
			.map(Number),
		results: items.map((item) => {
			const draft = drafts[item.id]
			return {
				taskItemId: item.id,
				resultCode: draft.resultCode || null,
				numericValue: draft.numericValue === '' ? null : Number(draft.numericValue),
				textValue: draft.textValue || null,
				selectedValue: draft.selectedValue || null,
				selectedValues: draft.selectedValues || [],
				abnormal: Boolean(draft.abnormal),
				abnormalDescription: draft.abnormalDescription || null,
				equipmentStopRequired: Boolean(draft.equipmentStopRequired),
				stopOverrideReason: draft.stopOverrideReason || null,
				skipped: Boolean(draft.skipped),
				skipReason: draft.skipReason || null,
				attachmentIds: (draft.attachmentIds || []).filter((id) => Number.isSafeInteger(Number(id)) && Number(id) > 0).map(Number),
				version: draft.version
			}
		})
	}
}
