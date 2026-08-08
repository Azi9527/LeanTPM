import { http } from '@/utils/http'
import type { ApiResponse, PageResult } from '@/types/api'

export interface NotificationRule {
  id: number
  ruleCode: string
  ruleName: string
  businessType: 'INSPECTION' | 'MAINTENANCE'
  triggerType: 'DUE_SOON' | 'MANUAL_CREATED' | 'OVERDUE'
  advanceMinutes: number
  repeatMinutes: number
  escalationLevel: number
  recipientType: 'ASSIGNEE' | 'TEAM_LEADER' | 'WORKSHOP_MANAGER'
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  channels: string[]
  acknowledgeRequired: boolean
  enabled: boolean
  version: number
}

export interface NotificationMessage {
  id: number
  messageType: string
  severity: string
  title: string
  content: string
  businessType: string
  businessId: number
  businessCode?: string
  routePath?: string
  acknowledgeRequired: boolean
  readTime?: string
  acknowledgedTime?: string
  occurredTime: string
}

export interface NotificationBusinessItemDetail {
  id: number
  itemCode: string
  itemName: string
  itemPart?: string
  itemContent?: string
  itemStandard?: string
  resultType: string
  unit?: string
  resultCode?: string
  numericValue?: number
  textValue?: string
  selectedValue?: string
  abnormalFlag: boolean
  abnormalDescription?: string
  skippedFlag: boolean
  skipReason?: string
  executedByName?: string
  executedTime?: string
}

export interface NotificationBusinessDetail {
  messageId: number
  businessType: 'INSPECTION' | 'MAINTENANCE'
  businessId: number
  businessCode?: string
  taskCode: string
  schemeName?: string
  equipmentCode: string
  equipmentName: string
  organizationName: string
  locationName?: string
  plannedDate: string
  dueTime: string
  taskStatus: string
  sourceType: string
  assigneeNames?: string
  startedTime?: string
  submittedTime?: string
  completedTime?: string
  items: NotificationBusinessItemDetail[]
  attachments: NotificationBusinessAttachmentDetail[]
}

export interface NotificationBusinessAttachmentDetail {
  id: number
  taskResultId?: number
  taskItemId?: number
  itemName?: string
  originalName: string
  contentType?: string
  extension?: string
  fileSize: number
  attachmentType: string
  createdTime: string
}

export interface NotificationDelivery {
  id: number
  messageId: number
  recipientName: string
  title: string
  channelCode: string
  deliveryStatus: string
  sentTime?: string
  failureReason?: string
  retryCount: number
  nextRetryTime?: string
  createdTime: string
}

export interface NotificationScanResult {
  scannedTasks: number
  createdMessages: number
  duplicateMessages: number
  missingRecipients: number
  stoppedEscalations: number
}

async function getData<T>(url: string, params?: object): Promise<T> {
  const response = await http.get<ApiResponse<T>>(url, { params })
  return response.data.data
}

export const notificationApi = {
  rules: () => getData<NotificationRule[]>('/notifications/rules'),
  createRule: (data: object) => http.post('/notifications/rules', data),
  updateRule: (id: number, data: object) => http.put(`/notifications/rules/${id}`, data),
  messages: (params: { unreadOnly?: boolean; page: number; pageSize: number }) =>
    getData<PageResult<NotificationMessage>>('/notifications/messages', params),
  businessDetail: (id: number) =>
    getData<NotificationBusinessDetail>(`/notifications/messages/${id}/business-detail`),
  businessAttachmentContent: async (messageId: number, attachmentId: number) => {
    const response = await http.get<Blob>(
      `/notifications/messages/${messageId}/attachments/${attachmentId}/content`,
      { responseType: 'blob' },
    )
    return response.data
  },
  read: (id: number) => http.post(`/notifications/messages/${id}/read`),
  acknowledge: (id: number) => http.post(`/notifications/messages/${id}/acknowledge`),
  deliveries: (params: { status?: string; page: number; pageSize: number }) =>
    getData<PageResult<NotificationDelivery>>('/notifications/deliveries', params),
  scan: async () => {
    const response = await http.post<ApiResponse<NotificationScanResult>>('/notifications/scan')
    return response.data.data
  },
}
