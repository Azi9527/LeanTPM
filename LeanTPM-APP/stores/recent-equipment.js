import { STORAGE_KEYS, getStored, setStored } from '../platform/storage.js'

const MAX_RECENT = 8

export function listRecentEquipment() {
	const value = getStored(STORAGE_KEYS.recentEquipment, [])
	return Array.isArray(value) ? value.slice(0, MAX_RECENT) : []
}

export function rememberEquipment(token, equipment) {
	if (!token || !equipment?.equipmentId) return listRecentEquipment()
	const record = {
		token,
		equipmentId: equipment.equipmentId,
		equipmentCode: equipment.equipmentCode || '',
		equipmentName: equipment.equipmentName || '',
		statusName: equipment.statusName || '',
		organizationName: equipment.organizationName || '',
		visitedAt: new Date().toISOString()
	}
	const next = [record, ...listRecentEquipment().filter((item) => item.equipmentId !== record.equipmentId)]
		.slice(0, MAX_RECENT)
	setStored(STORAGE_KEYS.recentEquipment, next)
	return next
}
