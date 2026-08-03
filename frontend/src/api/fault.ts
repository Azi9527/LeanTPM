import { http } from '@/utils/http'
import type { ApiResponse, PageResult } from '@/types/api'

export interface FaultReport {
  id: number; reportCode: string; equipmentId: number; equipmentCode: string; equipmentName: string
  organizationId: number; organizationName: string; faultTime: string; faultTitle: string
  faultDescription: string; severity: string; sourceType: string; sourceBusinessId?: number
  reporterUserId: number; reporterName: string; reportStatus: string; rejectedReason?: string
  createdTime: string; version: number; repairOrderId?: number; repairCode?: string
}

export interface RepairOrder {
  id: number; repairCode: string; faultReportId: number; reportCode: string
  equipmentId: number; equipmentCode: string; equipmentName: string
  organizationId: number; organizationName: string; faultTitle: string; severity: string
  repairStatus: string; primaryRepairerUserId?: number; primaryRepairerName?: string
  collaboratorUserIds: number[]; assignedTime?: string; startedTime?: string; pausedTime?: string
  completedTime?: string; acceptedTime?: string; totalPausedSeconds: number; effectiveWorkSeconds: number
  repairMeasure?: string; repairConclusion?: string; acceptanceResult?: string
  acceptanceComment?: string; restoreStatusCode: string; createdTime: string; version: number
}

export interface RepairMaterial {
  id: number; materialCode?: string; materialName: string; quantity: number; unit?: string
  unitPrice: number; totalAmount: number; remark?: string; version: number
}

export interface RepairEvent {
  id: number; eventType: string; fromStatus?: string; toStatus?: string
  eventRemark?: string; operatorName?: string; eventTime: string
}

export interface FaultStatistics {
  openReports: number; activeRepairs: number; pendingAcceptance: number
  closedRepairs: number; materialCost: number; averageRepairMinutes: number
}

async function getData<T>(url: string, params?: object): Promise<T> {
  const response = await http.get<ApiResponse<T>>(url, { params })
  return response.data.data
}

export const faultApi = {
  reports: (params: object) => getData<PageResult<FaultReport>>('/faults/reports', params),
  report: (id: number) => getData<FaultReport>(`/faults/reports/${id}`),
  createReport: (data: object) => http.post('/faults/reports', data),
  acceptReport: (id: number, version: number) => http.post(`/faults/reports/${id}/accept`, { version }),
  rejectReport: (id: number, data: object) => http.post(`/faults/reports/${id}/reject`, data),
  cancelReport: (id: number, data: object) => http.post(`/faults/reports/${id}/cancel`, data),
  createRepair: (id: number, data: object) => http.post(`/faults/reports/${id}/repair-order`, data),
  repairs: (params: object) => getData<PageResult<RepairOrder>>('/faults/repairs', params),
  repair: (id: number) => getData<RepairOrder>(`/faults/repairs/${id}`),
  assign: (id: number, data: object) => http.put(`/faults/repairs/${id}/assignment`, data),
  start: (id: number, data: object) => http.post(`/faults/repairs/${id}/start`, data),
  pause: (id: number, data: object) => http.post(`/faults/repairs/${id}/pause`, data),
  resume: (id: number, data: object) => http.post(`/faults/repairs/${id}/resume`, data),
  complete: (id: number, data: object) => http.post(`/faults/repairs/${id}/complete`, data),
  acceptance: (id: number, data: object) => http.post(`/faults/repairs/${id}/acceptance`, data),
  materials: (id: number) => getData<RepairMaterial[]>(`/faults/repairs/${id}/materials`),
  addMaterial: (id: number, data: object) => http.post(`/faults/repairs/${id}/materials`, data),
  events: (id: number) => getData<RepairEvent[]>(`/faults/repairs/${id}/events`),
  statistics: () => getData<FaultStatistics>('/faults/statistics'),
}
