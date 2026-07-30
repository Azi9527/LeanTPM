import { http } from '@/utils/http'
import type { ApiResponse, PageResult } from '@/types/api'

export type DataStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'LOCKED'
export type SourceType = 'MANUAL' | 'EXCEL' | 'MES' | 'IOT'

export interface ShiftRow {
  id: number
  shiftCode: string
  shiftName: string
  startTime: string
  endTime: string
  crossDayFlag: boolean
  breakMinutes: number
  standardWorkMinutes: number
  sortOrder: number
  status: number
  description?: string
  version: number
}

export interface CalendarRow {
  id: number
  organizationId: number
  organizationName: string
  workDate: string
  shiftId: number
  shiftName: string
  dayType: 'WORKDAY' | 'HOLIDAY' | 'OVERTIME'
  plannedWorkMinutes: number
  plannedDowntimeMinutes: number
  calendarStatus: 'ENABLED' | 'DISABLED'
  remark?: string
  version: number
}

export interface TargetRow {
  id: number
  targetName: string
  targetLevel: 'ENTERPRISE' | 'FACTORY' | 'WORKSHOP' | 'LINE' | 'EQUIPMENT'
  organizationId?: number
  organizationName?: string
  equipmentId?: number
  equipmentCode?: string
  equipmentName?: string
  availabilityTarget: number
  performanceTarget: number
  qualityTarget: number
  oeeTarget: number
  effectiveStartDate: string
  effectiveEndDate?: string
  status: number
  description?: string
  version: number
}

export interface LossReasonRow {
  id: number
  parentId: number
  reasonCode: string
  reasonName: string
  lossCategory: string
  affectsMetric: 'AVAILABILITY' | 'PERFORMANCE' | 'QUALITY' | 'EXCLUDED'
  plannedFlag: boolean
  color?: string
  sortOrder: number
  status: number
  description?: string
  referenceCount: number
  version: number
}

export interface OutputRow {
  id: number
  equipmentId: number
  equipmentCode: string
  equipmentName: string
  organizationId: number
  organizationName: string
  productionDate: string
  shiftId: number
  shiftName: string
  plannedQuantity: number
  actualQuantity: number
  goodQuantity: number
  defectiveQuantity: number
  sourceType: SourceType
  sourceReference?: string
  remark?: string
  version: number
}

export interface DowntimeRow {
  id: number
  equipmentId: number
  equipmentCode: string
  equipmentName: string
  organizationId: number
  organizationName: string
  productionDate: string
  shiftId: number
  shiftName: string
  lossReasonId: number
  reasonCode: string
  reasonName: string
  lossCategory: string
  affectsMetric: string
  startedTime?: string
  endedTime?: string
  durationMinutes: number
  plannedFlag: boolean
  sourceType: string
  sourceReference?: string
  description?: string
  version: number
}

export interface OeeRecordRow {
  id: number
  equipmentId: number
  equipmentCode: string
  equipmentName: string
  organizationId: number
  organizationName: string
  productionDate: string
  shiftId: number
  shiftName: string
  standardCycleSeconds: number
  plannedWorkMinutes: number
  plannedDowntimeMinutes: number
  loadingTimeMinutes: number
  unplannedDowntimeMinutes: number
  runTimeMinutes: number
  plannedQuantity: number
  actualQuantity: number
  goodQuantity: number
  defectiveQuantity: number
  availabilityRate: number
  performanceRate: number
  qualityRate: number
  oeeRate: number
  targetOeeRate?: number
  dataStatus: DataStatus
  anomalyFlag: boolean
  anomalyMessage?: string
  sourceType: SourceType
  calculatedTime?: string
  approvedByName?: string
  approvedTime?: string
  lockedByName?: string
  lockedTime?: string
  version: number
}

export interface CalculationLogRow {
  id: number
  oeeRecordId: number
  calculationVersion: number
  triggerType: string
  formulaVersion: string
  inputSnapshot: string
  outputSnapshot: string
  validationMessage?: string
  calculatedByName?: string
  calculatedTime: string
}

export interface AnalysisSummary {
  availabilityRate: number
  performanceRate: number
  qualityRate: number
  oeeRate: number
  targetOeeRate: number
  recordCount: number
  belowTargetCount: number
  plannedWorkMinutes: number
  runTimeMinutes: number
  actualQuantity: number
  goodQuantity: number
}

export interface TrendPoint {
  period: string
  availabilityRate: number
  performanceRate: number
  qualityRate: number
  oeeRate: number
  targetOeeRate: number
  recordCount: number
}

export interface RankingRow {
  scopeId: number
  scopeCode: string
  scopeName: string
  scopeType: string
  availabilityRate: number
  performanceRate: number
  qualityRate: number
  oeeRate: number
  targetOeeRate: number
  recordCount: number
}

export interface LossAnalysisRow {
  lossReasonId: number
  reasonCode: string
  reasonName: string
  lossCategory: string
  affectsMetric: string
  durationMinutes: number
  proportion: number
  occurrenceCount: number
}

export interface AnalysisResult {
  summary: AnalysisSummary
  trend: TrendPoint[]
  ranking: RankingRow[]
  losses: LossAnalysisRow[]
  records: OeeRecordRow[]
}

export interface ImportResult {
  totalRows: number
  successRows: number
  failureRows: number
  errors: string[]
}

async function getData<T>(url: string, params?: object): Promise<T> {
  const response = await http.get<ApiResponse<T>>(url, { params })
  return response.data.data
}

export const oeeApi = {
  shifts: (params: object) => getData<PageResult<ShiftRow>>('/oee/shifts', params),
  createShift: (data: object) => http.post('/oee/shifts', data),
  updateShift: (id: number, data: object) => http.put(`/oee/shifts/${id}`, data),
  deleteShift: (id: number, version: number) =>
    http.delete(`/oee/shifts/${id}`, { params: { version } }),

  calendars: (params: object) =>
    getData<PageResult<CalendarRow>>('/oee/calendars', params),
  createCalendar: (data: object) => http.post('/oee/calendars', data),
  updateCalendar: (id: number, data: object) => http.put(`/oee/calendars/${id}`, data),
  deleteCalendar: (id: number, version: number) =>
    http.delete(`/oee/calendars/${id}`, { params: { version } }),

  targets: (params: object) => getData<PageResult<TargetRow>>('/oee/targets', params),
  createTarget: (data: object) => http.post('/oee/targets', data),
  updateTarget: (id: number, data: object) => http.put(`/oee/targets/${id}`, data),
  deleteTarget: (id: number, version: number) =>
    http.delete(`/oee/targets/${id}`, { params: { version } }),

  lossReasons: (params: object) =>
    getData<PageResult<LossReasonRow>>('/oee/loss-reasons', params),
  createLossReason: (data: object) => http.post('/oee/loss-reasons', data),
  updateLossReason: (id: number, data: object) => http.put(`/oee/loss-reasons/${id}`, data),
  deleteLossReason: (id: number, version: number) =>
    http.delete(`/oee/loss-reasons/${id}`, { params: { version } }),

  outputs: (params: object) => getData<PageResult<OutputRow>>('/oee/outputs', params),
  createOutput: (data: object) => http.post('/oee/outputs', data),
  updateOutput: (id: number, data: object) => http.put(`/oee/outputs/${id}`, data),
  deleteOutput: (id: number, version: number) =>
    http.delete(`/oee/outputs/${id}`, { params: { version } }),

  downtimes: (params: object) =>
    getData<PageResult<DowntimeRow>>('/oee/downtimes', params),
  createDowntime: (data: object) => http.post('/oee/downtimes', data),
  updateDowntime: (id: number, data: object) => http.put(`/oee/downtimes/${id}`, data),
  deleteDowntime: (id: number, version: number) =>
    http.delete(`/oee/downtimes/${id}`, { params: { version } }),

  records: (params: object) => getData<PageResult<OeeRecordRow>>('/oee/records', params),
  record: (id: number) => getData<OeeRecordRow>(`/oee/records/${id}`),
  createRecord: (data: object) => http.post('/oee/records', data),
  updateRecord: (id: number, data: object) => http.put(`/oee/records/${id}`, data),
  recalculate: async (id: number) => {
    const response = await http.post<ApiResponse<OeeRecordRow>>(`/oee/records/${id}/recalculate`)
    return response.data.data
  },
  workflow: (id: number, data: object) => http.put(`/oee/records/${id}/workflow`, data),
  calculationLogs: (id: number) =>
    getData<CalculationLogRow[]>(`/oee/records/${id}/calculation-logs`),
  analysis: (params: object) => getData<AnalysisResult>('/oee/analysis', params),
  importRecords: async (file: File) => {
    const body = new FormData()
    body.append('file', file)
    const response = await http.post<ApiResponse<ImportResult>>('/oee/records/import', body)
    return response.data.data
  },
  downloadTemplate: () => http.get('/oee/records/import-template', { responseType: 'blob' }),
}
