import { http } from '@/utils/http'
import type { ApiResponse, PageResult } from '@/types/api'

export type ResultType =
  | 'NORMAL_ABNORMAL'
  | 'PASS_FAIL'
  | 'NUMBER'
  | 'TEXT'
  | 'SINGLE_CHOICE'
  | 'MULTIPLE_CHOICE'
  | 'IMAGE'
  | 'ATTACHMENT'

export type TaskStatus =
  | 'PENDING'
  | 'IN_PROGRESS'
  | 'PENDING_REVIEW'
  | 'COMPLETED'
  | 'OVERDUE'
  | 'CANCELLED'
  | 'VOIDED'

export interface ItemRow {
  id: number
  itemCode: string
  itemName: string
  itemCategory: string
  inspectionPart?: string
  inspectionContent: string
  inspectionMethod?: string
  inspectionTool?: string
  inspectionStandard: string
  standardValue?: string
  minimumValue?: number
  maximumValue?: number
  unit?: string
  resultType: ResultType
  resultOptionsJson?: string
  requiredFlag: boolean
  photoRequiredFlag: boolean
  numericRequiredFlag: boolean
  skipAllowedFlag: boolean
  abnormalSeverity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  abnormalAdvice?: string
  standardMinutes: number
  safetyNotes?: string
  status: number
  description?: string
  version: number
}

export interface SchemeRow {
  id: number
  schemeCode: string
  schemeName: string
  inspectionType: string
  currentVersionId?: number
  currentVersionNumber?: number
  currentVersionStatus?: string
  cycleType?: string
  cycleInterval?: number
  scheduledTime?: string
  itemCount: number
  applicableEquipmentCount: number
  activePlanCount: number
  status: number
  description?: string
  version: number
}

export interface SchemeVersionRow {
  id: number
  schemeId: number
  versionNumber: number
  versionStatus: 'DRAFT' | 'PUBLISHED' | 'RETIRED'
  cycleType: 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'INTERVAL_DAYS'
  cycleInterval: number
  weekDays?: string
  monthDays?: string
  scheduledTime?: string
  shiftCode?: string
  defaultAssigneeUserId?: number
  defaultAssigneeName?: string
  defaultTeamCode?: string
  reviewRequiredFlag: boolean
  backfillAllowedFlag: boolean
  effectiveDate: string
  expiryDate?: string
  publishedByName?: string
  publishedTime?: string
  changeSummary?: string
  version: number
}

export interface SchemeItemRow {
  relationId: number
  inspectionItemId: number
  itemCode: string
  itemName: string
  itemCategory: string
  resultType: ResultType
  unit?: string
  requiredFlag: boolean
  photoRequiredFlag: boolean
  skipAllowedFlag: boolean
  sortOrder: number
}

export interface SchemeDetail {
  scheme: SchemeRow
  version: SchemeVersionRow
  items: SchemeItemRow[]
  applicability: { categoryIds: number[]; equipmentIds: number[] }
  versionHistory: SchemeVersionRow[]
}

export interface PlanRow {
  id: number
  schemeId: number
  schemeCode: string
  schemeName: string
  schemeVersionNumber: number
  equipmentId: number
  equipmentCode: string
  equipmentName: string
  organizationId: number
  organizationName: string
  locationName: string
  cycleType: string
  cycleInterval: number
  scheduledTime?: string
  assigneeUserId?: number
  assigneeName?: string
  nextGenerationDate: string
  lastGenerationDate?: string
  planStatus: 'ACTIVE' | 'PAUSED' | 'CANCELLED'
  pausedReason?: string
  version: number
}

export interface TaskRow {
  id: number
  taskCode: string
  planId?: number
  schemeId?: number
  schemeVersionId?: number
  schemeCodeSnapshot?: string
  schemeNameSnapshot?: string
  schemeVersionNumber?: number
  inspectionType: string
  equipmentId: number
  equipmentCode: string
  equipmentName: string
  organizationId: number
  organizationName: string
  locationId: number
  locationName: string
  plannedDate: string
  plannedStartTime?: string
  dueTime: string
  assigneeUserId?: number
  assigneeName?: string
  teamCode?: string
  taskStatus: TaskStatus
  sourceType: 'PLAN' | 'MANUAL' | 'BACKFILL'
  backfillFlag: boolean
  reviewRequiredFlag: boolean
  startedTime?: string
  submittedTime?: string
  completedTime?: string
  reviewerName?: string
  reviewComment?: string
  executionRemark?: string
  itemCount: number
  completedItemCount: number
  abnormalItemCount: number
  version: number
}

export interface ResultRow {
  id?: number
  resultStatus?: 'DRAFT' | 'SUBMITTED'
  resultCode?: string
  numericValue?: number
  textValue?: string
  selectedValue?: string
  selectedValuesJson?: string
  abnormalFlag?: boolean
  abnormalDescription?: string
  skippedFlag?: boolean
  skipReason?: string
  executedByName?: string
  executedTime?: string
  version?: number
  attachmentIds?: number[]
}

export interface TaskItemRow {
  id: number
  taskId: number
  sourceItemId?: number
  itemCode: string
  itemName: string
  itemCategory: string
  inspectionPart?: string
  inspectionContent: string
  inspectionMethod?: string
  inspectionTool?: string
  inspectionStandard: string
  standardValue?: string
  minimumValue?: number
  maximumValue?: number
  unit?: string
  resultType: ResultType
  resultOptionsJson?: string
  requiredFlag: boolean
  photoRequiredFlag: boolean
  numericRequiredFlag: boolean
  skipAllowedFlag: boolean
  abnormalSeverity: string
  abnormalAdvice?: string
  standardMinutes: number
  safetyNotes?: string
  sortOrder: number
  result?: ResultRow
}

export interface TaskEventRow {
  id: number
  eventType: string
  fromStatus?: string
  toStatus?: string
  eventRemark?: string
  operatorName?: string
  eventTime: string
}

export interface AbnormalRow {
  id: number
  abnormalCode: string
  taskId: number
  taskCode: string
  equipmentId: number
  equipmentCode: string
  equipmentName: string
  taskItemId?: number
  itemName?: string
  abnormalTitle: string
  abnormalDescription: string
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  abnormalStatus: 'OPEN' | 'PROCESSING' | 'PENDING_VERIFY' | 'CLOSED'
  responsibleUserId?: number
  responsibleUserName?: string
  dueTime?: string
  temporaryAction?: string
  finalResult?: string
  requestedEquipmentStatus?: string
  closedByName?: string
  closedTime?: string
  verifiedByName?: string
  verifiedTime?: string
  verificationComment?: string
  createdTime: string
  version: number
}

export interface TaskDetail {
  task: TaskRow
  items: TaskItemRow[]
  events: TaskEventRow[]
  abnormalities: AbnormalRow[]
}

export interface Statistics {
  dueToday: number
  completedToday: number
  pendingToday: number
  overdue: number
  abnormal: number
  completionRate: number
  onTimeRate: number
}

export interface GenerationResult {
  consideredPlans: number
  generatedTasks: number
  skippedOccurrences: number
  taskCodes: string[]
}

async function getData<T>(url: string, params?: object): Promise<T> {
  const response = await http.get<ApiResponse<T>>(url, { params })
  return response.data.data
}

export const inspectionApi = {
  items: (params: object) =>
    getData<PageResult<ItemRow>>('/inspection/items', params),
  item: (id: number) => getData<ItemRow>(`/inspection/items/${id}`),
  createItem: (data: object) => http.post('/inspection/items', data),
  updateItem: (id: number, data: object) => http.put(`/inspection/items/${id}`, data),
  deleteItem: (id: number, version: number) =>
    http.delete(`/inspection/items/${id}`, { params: { version } }),

  schemes: (params: object) =>
    getData<PageResult<SchemeRow>>('/inspection/schemes', params),
  scheme: (id: number, versionId?: number) =>
    getData<SchemeDetail>(`/inspection/schemes/${id}`, { versionId }),
  createScheme: (data: object) => http.post('/inspection/schemes', data),
  createSchemeVersion: (id: number, data: object) =>
    http.post(`/inspection/schemes/${id}/versions`, data),
  publishScheme: (id: number, versionId: number) =>
    http.post(`/inspection/schemes/${id}/versions/${versionId}/publish`),

  plans: (params: object) =>
    getData<PageResult<PlanRow>>('/inspection/plans', params),
  updatePlanStatus: (id: number, data: object) =>
    http.put(`/inspection/plans/${id}/status`, data),
  generateTasks: async () => {
    const response = await http.post<ApiResponse<GenerationResult>>('/inspection/plans/generate')
    return response.data.data
  },

  tasks: (params: object) =>
    getData<PageResult<TaskRow>>('/inspection/tasks', params),
  task: (id: number) => getData<TaskDetail>(`/inspection/tasks/${id}`),
  createTask: (data: object) => http.post('/inspection/tasks', data),
  assignTask: (id: number, data: object) =>
    http.put(`/inspection/tasks/${id}/assignment`, data),
  saveDraft: (id: number, data: object) =>
    http.put(`/inspection/tasks/${id}/draft`, data),
  submitTask: (id: number, data: object) =>
    http.post(`/inspection/tasks/${id}/submit`, data),
  reviewTask: (id: number, data: object) =>
    http.post(`/inspection/tasks/${id}/review`, data),
  closeTask: (id: number, targetStatus: 'CANCELLED' | 'VOIDED', data: object) =>
    http.post(`/inspection/tasks/${id}/close`, data, { params: { targetStatus } }),

  abnormalities: (params: object) =>
    getData<PageResult<AbnormalRow>>('/inspection/abnormalities', params),
  handleAbnormal: (id: number, data: object) =>
    http.put(`/inspection/abnormalities/${id}`, data),
  verifyAbnormal: (id: number, data: object) =>
    http.post(`/inspection/abnormalities/${id}/verify`, data),
  statistics: () => getData<Statistics>('/inspection/statistics'),
}
