import { http } from '@/utils/http'
import type { ApiResponse } from '@/types/api'

export interface MobileWorkCount {
  dueToday: number
  pending: number
  overdue: number
  completedToday: number
}

export interface MobileMessage {
  id: number
  messageType: string
  severity: string
  title: string
  content: string
  businessType: string
  businessId: number
  acknowledgeRequired: boolean
  readTime?: string
  acknowledgedTime?: string
  occurredTime: string
  routePath: string
}

export interface MobileBootstrap {
  serverTime: string
  draftRetentionDays: number
  maxUploadMb: number
  inspection: MobileWorkCount
  maintenance: MobileWorkCount
  messages: MobileMessage[]
}

export interface MobileEquipment {
  equipmentId: number
  equipmentCode: string
  equipmentName: string
  categoryName: string
  organizationName: string
  locationName: string
  statusCode: string
  statusName: string
  statusColor: string
  statusSince?: string
  responsibleName?: string
}

export interface MobileTaskLink {
  taskId: number
  taskCode: string
  workflowType: 'INSPECTION' | 'MAINTENANCE'
  schemeName: string
  taskStatus: string
  dueTime: string
  routePath: string
}

export interface MobileEquipmentContext {
  equipment: MobileEquipment
  activeTasks: MobileTaskLink[]
}

async function getData<T>(url: string): Promise<T> {
  const response = await http.get<ApiResponse<T>>(url)
  return response.data.data
}

export const mobileApi = {
  bootstrap: () => getData<MobileBootstrap>('/mobile/bootstrap'),
  equipment: (token: string) =>
    getData<MobileEquipmentContext>(`/mobile/equipment/${token}`),
}
