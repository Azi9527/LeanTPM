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
  | 'PENDING_ASSIGNMENT'
  | 'PENDING'
  | 'IN_PROGRESS'
  | 'PAUSED'
  | 'PENDING_CONFIRMATION'
  | 'COMPLETED'
  | 'OVERDUE'
  | 'CANCELLED'
  | 'VOIDED'

export interface ItemRow {
  id: number
  itemCode: string
  itemName: string
  itemCategory: string
  maintenancePart?: string
  maintenanceContent: string
  maintenanceMethod?: string
  maintenanceTool?: string
  maintenanceStandard: string
  standardValue?: string
  minimumValue?: number
  maximumValue?: number
  unit?: string
  resultType: ResultType
  resultOptionsJson?: string
  requiredFlag: boolean
  photoRequiredFlag: boolean
  attachmentRequiredFlag: boolean
  numericRequiredFlag: boolean
  skipAllowedFlag: boolean
  stopRequiredFlag: boolean
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
  maintenanceType: string
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
  cycleType:
    | 'DAILY'
    | 'WEEKLY'
    | 'MONTHLY'
    | 'QUARTERLY'
    | 'HALF_YEARLY'
    | 'YEARLY'
    | 'RUNNING_HOURS'
    | 'PRODUCTION_QUANTITY'
    | 'MANUAL'
  cycleInterval: number
  triggerThreshold?: number
  weekDays?: string
  monthDays?: string
  scheduledTime?: string
  reminderDays: number
  generationLeadDays: number
  shiftCode?: string
  defaultAssigneeUserId?: number
  defaultAssigneeName?: string
  defaultTeamCode?: string
  reviewRequiredFlag: boolean
  backfillAllowedFlag: boolean
  stopRequiredFlag: boolean
  restoreStatusCode?: string
  effectiveDate: string
  expiryDate?: string
  publishedByName?: string
  publishedTime?: string
  changeSummary?: string
  version: number
}

export interface SchemeItemRow {
  relationId: number
  maintenanceItemId: number
  itemCode: string
  itemName: string
  itemCategory: string
  resultType: ResultType
  unit?: string
  requiredFlag: boolean
  photoRequiredFlag: boolean
  attachmentRequiredFlag: boolean
  skipAllowedFlag: boolean
  stopRequiredFlag: boolean
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
  triggerThreshold?: number
  currentMeterValue: number
  nextTriggerValue?: number
  meterUpdatedTime?: string
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
  maintenanceType: string
  equipmentId: number
  equipmentCode: string
  equipmentName: string
  organizationId: number
  organizationName: string
  locationId?: number
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
  stopRequiredFlag: boolean
  restoreStatusCode?: string
  previousEquipmentStatus?: string
  startedTime?: string
  pausedTime?: string
  submittedTime?: string
  completedTime?: string
  confirmedTime?: string
  totalPausedSeconds: number
  effectiveWorkMinutes: number
  reviewerName?: string
  reviewComment?: string
  executionRemark?: string
  itemCount: number
  completedItemCount: number
  abnormalItemCount: number
  collaboratorCount: number
  materialCost: number
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
  maintenancePart?: string
  maintenanceContent: string
  maintenanceMethod?: string
  maintenanceTool?: string
  maintenanceStandard: string
  standardValue?: string
  minimumValue?: number
  maximumValue?: number
  unit?: string
  resultType: ResultType
  resultOptionsJson?: string
  requiredFlag: boolean
  photoRequiredFlag: boolean
  attachmentRequiredFlag: boolean
  numericRequiredFlag: boolean
  skipAllowedFlag: boolean
  stopRequiredFlag: boolean
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
  repairOrderId?: number
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
  collaborators: CollaboratorRow[]
  pauses: PauseRow[]
  materials: MaterialUsageRow[]
}

export interface CollaboratorRow {
  userId: number
  userName: string
}

export interface PauseRow {
  id: number
  pauseReason: string
  pausedByName: string
  pausedTime: string
  resumedByName?: string
  resumedTime?: string
  durationSeconds?: number
}

export interface MaterialUsageRow {
  id: number
  materialCode: string
  materialName: string
  specification?: string
  quantity: number
  unit: string
  unitCost?: number
  totalCost?: number
  batchNumber?: string
  remark?: string
  version: number
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

export const maintenanceApi = {
  items: (params: object) =>
    getData<PageResult<ItemRow>>('/maintenance/items', params),
  item: (id: number) => getData<ItemRow>(`/maintenance/items/${id}`),
  createItem: (data: object) => http.post('/maintenance/items', data),
  updateItem: (id: number, data: object) => http.put(`/maintenance/items/${id}`, data),
  deleteItem: (id: number, version: number) =>
    http.delete(`/maintenance/items/${id}`, { params: { version } }),

  schemes: (params: object) =>
    getData<PageResult<SchemeRow>>('/maintenance/schemes', params),
  scheme: (id: number, versionId?: number) =>
    getData<SchemeDetail>(`/maintenance/schemes/${id}`, { versionId }),
  createScheme: (data: object) => http.post('/maintenance/schemes', data),
  createSchemeVersion: (id: number, data: object) =>
    http.post(`/maintenance/schemes/${id}/versions`, data),
  publishScheme: (id: number, versionId: number) =>
    http.post(`/maintenance/schemes/${id}/versions/${versionId}/publish`),

  plans: (params: object) =>
    getData<PageResult<PlanRow>>('/maintenance/plans', params),
  updatePlanStatus: (id: number, data: object) =>
    http.put(`/maintenance/plans/${id}/status`, data),
  updatePlanMeter: (id: number, data: object) =>
    http.put(`/maintenance/plans/${id}/meter`, data),
  generateTasks: async () => {
    const response = await http.post<ApiResponse<GenerationResult>>('/maintenance/plans/generate')
    return response.data.data
  },

  tasks: (params: object) =>
    getData<PageResult<TaskRow>>('/maintenance/tasks', params),
  task: (id: number) => getData<TaskDetail>(`/maintenance/tasks/${id}`),
  createTask: (data: object) => http.post('/maintenance/tasks', data),
  assignTask: (id: number, data: object) =>
    http.put(`/maintenance/tasks/${id}/assignment`, data),
  replaceCollaborators: (id: number, data: object) =>
    http.put(`/maintenance/tasks/${id}/collaborators`, data),
  startTask: (id: number, data: object) =>
    http.post(`/maintenance/tasks/${id}/start`, data),
  pauseTask: (id: number, data: object) =>
    http.post(`/maintenance/tasks/${id}/pause`, data),
  resumeTask: (id: number, data: object) =>
    http.post(`/maintenance/tasks/${id}/resume`, data),
  saveDraft: (id: number, data: object) =>
    http.put(`/maintenance/tasks/${id}/draft`, data),
  submitTask: (id: number, data: object, idempotencyKey?: string) =>
    http.post(`/maintenance/tasks/${id}/submit`, data, {
      headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
    }),
  confirmTask: (id: number, data: object) =>
    http.post(`/maintenance/tasks/${id}/confirm`, data),
  saveMaterial: (id: number, data: object) =>
    http.put(`/maintenance/tasks/${id}/materials`, data),
  deleteMaterial: (id: number, materialId: number, version: number) =>
    http.delete(`/maintenance/tasks/${id}/materials/${materialId}`, { params: { version } }),
  closeTask: (id: number, targetStatus: 'CANCELLED' | 'VOIDED', data: object) =>
    http.post(`/maintenance/tasks/${id}/close`, data, { params: { targetStatus } }),

  abnormalities: (params: object) =>
    getData<PageResult<AbnormalRow>>('/maintenance/abnormalities', params),
  handleAbnormal: (id: number, data: object) =>
    http.put(`/maintenance/abnormalities/${id}`, data),
  abnormalToRepair: (id: number) => http.post(`/maintenance/abnormalities/${id}/repair-order`),
  verifyAbnormal: (id: number, data: object) =>
    http.post(`/maintenance/abnormalities/${id}/verify`, data),
  statistics: () => getData<Statistics>('/maintenance/statistics'),
}
