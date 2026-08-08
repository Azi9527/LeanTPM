export type EquipmentOperatingStatus = 'IDLE' | 'RUNNING' | 'STOPPED' | 'SCRAPPED'

export const EQUIPMENT_STATUS_META: Record<EquipmentOperatingStatus, {
  label: string
  type: 'success' | 'warning' | 'info'
}> = {
  IDLE: { label: '空闲', type: 'info' },
  RUNNING: { label: '运行', type: 'success' },
  STOPPED: { label: '停机', type: 'warning' },
  SCRAPPED: { label: '报废', type: 'info' },
}

export const EQUIPMENT_STATUS_OPTIONS = Object.entries(EQUIPMENT_STATUS_META).map(([value, meta]) => ({
  value: value as EquipmentOperatingStatus,
  label: meta.label,
}))

export const EQUIPMENT_STATUS_TRANSITIONS: Record<EquipmentOperatingStatus, EquipmentOperatingStatus[]> = {
  IDLE: ['RUNNING', 'STOPPED', 'SCRAPPED'],
  RUNNING: ['IDLE', 'STOPPED', 'SCRAPPED'],
  STOPPED: ['IDLE', 'RUNNING', 'SCRAPPED'],
  SCRAPPED: [],
}

export function normalizeEquipmentStatus(code?: string | null): EquipmentOperatingStatus {
  if (code === 'RUNNING') return 'RUNNING'
  if (code === 'SCRAPPED') return 'SCRAPPED'
  if (code === 'IDLE' || code === 'NOT_ENABLED' || !code) return 'IDLE'
  return 'STOPPED'
}

export function equipmentStatusLabel(code?: string | null) {
  return EQUIPMENT_STATUS_META[normalizeEquipmentStatus(code)].label
}

export function equipmentStatusType(code?: string | null) {
  return EQUIPMENT_STATUS_META[normalizeEquipmentStatus(code)].type
}
