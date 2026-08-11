const SCAN_REQUIRED_STATUSES = new Set(['PENDING', 'IN_PROGRESS', 'OVERDUE'])

export function taskRequiresEquipmentScan(task) {
	return SCAN_REQUIRED_STATUSES.has(String(task?.taskStatus || '').toUpperCase())
}

export function inspectionTaskTarget(task) {
	const taskId = task?.id ?? task?.taskId
	if (!taskRequiresEquipmentScan(task)) {
		return { url: `/pages/inspection/detail?id=${encodeURIComponent(String(taskId))}`, requiresScan: false }
	}
	const query = [
		['taskId', taskId],
		['equipmentId', task.equipmentId],
		['equipmentName', task.equipmentName || '']
	].map(([key, value]) => `${key}=${encodeURIComponent(String(value))}`).join('&')
	return { url: `/pages/scan/index?${query}`, requiresScan: true }
}

export function scannedEquipmentMatchesTask(task, equipment) {
	return Number(task?.equipmentId) > 0
		&& Number(task.equipmentId) === Number(equipment?.equipmentId)
}
