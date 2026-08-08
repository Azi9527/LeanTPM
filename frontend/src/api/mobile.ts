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
  photoPolicy: {
    clockSkewWarningSeconds: number
    watermarkEnabled: boolean
    saveOriginal: boolean
    saveWatermarked: boolean
    template: string
    position: 'TOP' | 'BOTTOM'
    backgroundOpacity: number
    fontColor: string
    backgroundColor: string
  }
  androidVersion: {
    minimumVersionCode: number
    latestVersionName: string
    downloadUrl: string
    releaseNotes: string
  }
  equipmentStatus: {
    total: number
    running: number
    stopped: number
    fault: number
    offline: number
    idle: number
    scrapped: number
  }
  inspection: MobileWorkCount
  inspectionAbnormal: {
    open: number
    critical: number
    high: number
  }
  personalInspectionReport: {
    startDate: string
    endDate: string
    due: number
    completed: number
    abnormal: number
  }
  maintenance: MobileWorkCount
  messages: MobileMessage[]
}

export interface PhotoEvidencePayload {
  workflowType: 'INSPECTION' | 'MAINTENANCE'
  taskId: number
  taskItemId: number
  originalAttachmentId?: number
  watermarkedAttachmentId?: number
  capturedDeviceTime: string
  serverReferenceTime: string
  deviceClockOffsetSeconds: number
  faultLocationText: string
  watermarkText: string
}

export interface PhotoEvidence extends PhotoEvidencePayload {
  id: number
  receivedServerTime: string
  clockSkewWarning: boolean
  originalSha256: string
  watermarkedSha256: string
  latitude?: number
  longitude?: number
  locationAccuracyMeters?: number
  locationProvider?: string
  addressText?: string
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

export interface MobileInspectionScheme {
  schemeId: number
  schemeVersionId: number
  schemeCode: string
  schemeName: string
  inspectionType: string
  backfillAllowed: boolean
}

export interface MobileAssigneeOption {
  userId: number
  username: string
  realName: string
  teamCode?: string
}

export interface MobileTeamOption {
  teamCode: string
  teamName: string
}

export interface MobileEquipmentContext {
  equipment: MobileEquipment
  activeTasks: MobileTaskLink[]
  inspectionSchemes: MobileInspectionScheme[]
  assignees: MobileAssigneeOption[]
  teams: MobileTeamOption[]
}

async function getData<T>(url: string): Promise<T> {
  const response = await http.get<ApiResponse<T>>(url)
  return response.data.data
}

export const mobileApi = {
  bootstrap: () => getData<MobileBootstrap>('/mobile/bootstrap'),
  equipment: (token: string) =>
    getData<MobileEquipmentContext>(`/mobile/equipment/${token}`),
  registerPhotoEvidence: async (payload: PhotoEvidencePayload, idempotencyKey: string) => {
    const response = await http.post<ApiResponse<PhotoEvidence>>('/mobile/photo-evidence', payload, {
      headers: { 'Idempotency-Key': idempotencyKey },
    })
    return response.data.data
  },
  photoEvidence: (id: number) => getData<PhotoEvidence>(`/mobile/photo-evidence/${id}`),
}
