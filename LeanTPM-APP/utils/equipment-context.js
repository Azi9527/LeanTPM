const LIFECYCLE_LABELS = Object.freeze({
	PLANNED: '计划中',
	IN_SERVICE: '在役',
	IDLE: '闲置',
	RETIRED: '已退役',
	SCRAPPED: '已报废'
})

function displayValue(value) {
	return String(value || '').trim() || '未设置'
}

function equipmentTags(equipment) {
	if (String(equipment?.equipmentTagNames || '').trim()) return equipment.equipmentTagNames
	const tags = []
	if (equipment?.criticalFlag) tags.push('关键设备')
	if (equipment?.specialFlag) tags.push('特种设备')
	return tags.join('、') || '普通设备'
}

export function equipmentManagementRows(equipment = {}) {
	const lifecycle = equipment.lifecycleStage || equipment.lifecycleStatus
	const rows = [
		{ label: '所属组织', value: displayValue(equipment.organizationName) },
		{ label: '安装位置', value: displayValue(equipment.locationName) },
		{ label: '设备负责人', value: displayValue(equipment.responsibleName || equipment.primaryResponsibleUserName) },
		{ label: '型号', value: displayValue(equipment.model) },
		{ label: '生命周期', value: LIFECYCLE_LABELS[lifecycle] || displayValue(lifecycle) },
		{ label: '设备标识', value: equipmentTags(equipment) }
	]
	if (String(equipment.description || '').trim()) rows.push({ label: '备注', value: equipment.description })
	return rows
}

export function equipmentTaskPreview(tasks = [], limit = 1) {
	const source = Array.isArray(tasks) ? tasks : []
	const size = Math.max(1, Number(limit) || 1)
	return {
		total: source.length,
		visible: source.slice(0, size),
		hasMore: source.length > size
	}
}

export function inspectionSchemeAvailabilityMessage(schemes = []) {
	return Array.isArray(schemes) && schemes.length
		? ''
		: '当前没有今日已生效且适用于本设备的点检方案，请检查生效日期、发布状态和指定设备'
}
