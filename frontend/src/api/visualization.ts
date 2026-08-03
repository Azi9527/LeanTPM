import { http, accessToken } from '@/utils/http'
import type { ApiResponse } from '@/types/api'
import type { AnalysisResult } from '@/api/oee'

export interface CoreMetrics {
  total: number
  running: number
  stopped: number
  fault: number
  repair: number
  maintenance: number
  offline: number
  inspection: number
  other: number
}

export interface StatusMetric {
  statusCode: string
  statusName: string
  displayColor: string
  equipmentCount: number
  proportion?: number
}

export interface OrganizationMetric {
  organizationId: number
  organizationCode: string
  organizationName: string
  organizationType: string
  equipmentCount: number
  runningCount: number
  faultCount: number
  stoppedCount: number
  offlineCount: number
}

export interface LiveEquipment {
  equipmentId: number
  equipmentCode: string
  equipmentName: string
  organizationId: number
  organizationName: string
  statusCode: string
  statusName: string
  displayColor: string
  statusSince?: string
  durationSeconds: number
  todayOee?: number
  longDuration: boolean
}

export interface WorkflowMetrics {
  workflowType: 'INSPECTION' | 'MAINTENANCE'
  due: number
  completed: number
  pending: number
  overdue: number
  abnormal: number
  completionRate?: number
  onTimeRate?: number
}

export interface WorkflowTrend {
  statisticDate: string
  workflowType: 'INSPECTION' | 'MAINTENANCE'
  due: number
  completed: number
  overdue: number
  abnormal: number
}

export interface DashboardResult {
  generatedAt: string
  startDate: string
  endDate: string
  organizationId?: number
  periodType: 'DAY' | 'WEEK' | 'MONTH'
  refreshSeconds: number
  core: CoreMetrics
  statusDistribution: StatusMetric[]
  organizationDistribution: OrganizationMetric[]
  liveEquipment: LiveEquipment[]
  inspection: WorkflowMetrics
  maintenance: WorkflowMetrics
  workflowTrend: WorkflowTrend[]
  oee: AnalysisResult
}

export interface ModelResource {
  id: number
  resourceCode: string
  resourceName: string
  resourceLevel: string
  attachmentId?: number
  modelFormat: 'PRIMITIVE' | 'GLB' | 'GLTF'
  primitiveType?: string
  fallbackColor: string
  thumbnailAttachmentId?: number
  description?: string
  status: number
  version: number
}

export interface StatusColor {
  id: number
  statusCode: string
  statusName: string
  displayColor: string
  emissiveColor: string
  pulseFlag: boolean
  sortOrder: number
  status: number
  description?: string
  version: number
}

export interface SceneSummary {
  id: number
  parentSceneId: number
  sceneCode: string
  sceneName: string
  sceneLevel: string
  organizationId: number
  organizationName: string
  modelResourceId?: number
  sortOrder: number
  status: number
  nodeCount: number
  version: number
}

export interface SceneConfig {
  id: number
  parentSceneId: number
  sceneCode: string
  sceneName: string
  sceneLevel: string
  organizationId: number
  organizationName: string
  modelResourceId?: number
  backgroundColor: string
  gridColor: string
  cameraX: number
  cameraY: number
  cameraZ: number
  targetX: number
  targetY: number
  targetZ: number
  autoRotateFlag: boolean
  sortOrder: number
  status: number
  description?: string
  version: number
}

export interface SceneNode {
  id: number
  sceneId: number
  nodeCode: string
  displayName: string
  nodeType: 'ORGANIZATION' | 'EQUIPMENT' | 'DECORATION'
  organizationId?: number
  equipmentId?: number
  targetSceneId?: number
  modelResourceId?: number
  modelFormat?: 'PRIMITIVE' | 'GLB' | 'GLTF'
  primitiveType?: string
  attachmentId?: number
  fallbackColor: string
  positionX: number
  positionY: number
  positionZ: number
  rotationX: number
  rotationY: number
  rotationZ: number
  scaleX: number
  scaleY: number
  scaleZ: number
  labelVisibleFlag: boolean
  visibleFlag: boolean
  sortOrder: number
  statusCode: string
  statusName: string
  displayColor: string
  pulseFlag: boolean
  version: number
}

export interface SceneDetail {
  scene: SceneConfig
  breadcrumb: SceneSummary[]
  nodes: SceneNode[]
  statusColors: StatusColor[]
}

export interface EquipmentEvent {
  eventType: string
  eventCode: string
  eventStatus: string
  description?: string
  eventTime: string
}

export interface EquipmentSnapshot {
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
  statusDurationSeconds: number
  responsibleName?: string
  todayRunMinutes: number
  todayStopMinutes: number
  todayOee?: number
  todayInspectionDue: number
  todayInspectionCompleted: number
  todayMaintenanceDue: number
  todayMaintenanceCompleted: number
  openAbnormalCount: number
  recentEvents: EquipmentEvent[]
}

export interface SaveModelRequest {
  resourceCode: string
  resourceName: string
  resourceLevel: string
  attachmentId?: number
  modelFormat: 'PRIMITIVE' | 'GLB' | 'GLTF'
  primitiveType?: string
  fallbackColor: string
  thumbnailAttachmentId?: number
  description?: string
  status: number
  version?: number
}

export interface SaveSceneRequest {
  parentSceneId: number
  sceneCode: string
  sceneName: string
  sceneLevel: string
  organizationId: number
  modelResourceId?: number
  backgroundColor: string
  gridColor: string
  cameraX: number
  cameraY: number
  cameraZ: number
  targetX: number
  targetY: number
  targetZ: number
  autoRotateFlag: boolean
  sortOrder: number
  status: number
  description?: string
  version?: number
}

export interface SaveNodeRequest {
  nodeCode: string
  displayName: string
  nodeType: string
  organizationId?: number
  equipmentId?: number
  targetSceneId?: number
  modelResourceId?: number
  positionX: number
  positionY: number
  positionZ: number
  rotationX: number
  rotationY: number
  rotationZ: number
  scaleX: number
  scaleY: number
  scaleZ: number
  labelVisibleFlag: boolean
  visibleFlag: boolean
  sortOrder: number
  description?: string
  version?: number
}

async function data<T>(promise: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await promise).data.data
}

export const visualizationApi = {
  dashboard: (params: { startDate?: string; endDate?: string; organizationId?: number; periodType?: 'DAY' | 'WEEK' | 'MONTH' }) =>
    data<DashboardResult>(http.get('/visualization/dashboard', { params })),
  scenes: () => data<SceneSummary[]>(http.get('/visualization/scenes')),
  scene: (id: number) => data<SceneDetail>(http.get(`/visualization/scenes/${id}`)),
  snapshot: (id: number) =>
    data<EquipmentSnapshot>(http.get(`/visualization/equipment/${id}/snapshot`)),
  models: () => data<ModelResource[]>(http.get('/visualization/models')),
  statusColors: () => data<StatusColor[]>(http.get('/visualization/status-colors')),
  createModel: (request: SaveModelRequest) =>
    data<{ id: number }>(http.post('/visualization/models', request)),
  updateModel: (id: number, request: SaveModelRequest) =>
    data<void>(http.put(`/visualization/models/${id}`, request)),
  deleteModel: (id: number, version: number) =>
    data<void>(http.delete(`/visualization/models/${id}`, { params: { version } })),
  uploadModel: async (file: File, request: SaveModelRequest) => {
    const form = new FormData()
    form.append('file', file)
    form.append(
      'request',
      new Blob([JSON.stringify(request)], { type: 'application/json' }),
    )
    return data<{ id: number }>(http.post('/visualization/models/upload', form))
  },
  createScene: (request: SaveSceneRequest) =>
    data<{ id: number }>(http.post('/visualization/scenes', request)),
  updateScene: (id: number, request: SaveSceneRequest) =>
    data<void>(http.put(`/visualization/scenes/${id}`, request)),
  deleteScene: (id: number, version: number) =>
    data<void>(http.delete(`/visualization/scenes/${id}`, { params: { version } })),
  createNode: (sceneId: number, request: SaveNodeRequest) =>
    data<{ id: number }>(http.post(`/visualization/scenes/${sceneId}/nodes`, request)),
  updateNode: (id: number, request: SaveNodeRequest) =>
    data<void>(http.put(`/visualization/nodes/${id}`, request)),
  deleteNode: (id: number, version: number) =>
    data<void>(http.delete(`/visualization/nodes/${id}`, { params: { version } })),
  updateStatusColor: (statusCode: string, request: Omit<StatusColor, 'id' | 'statusCode'>) =>
    data<void>(http.put(`/visualization/status-colors/${statusCode}`, request)),
}

export async function subscribeVisualization(
  onRefresh: () => void,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch('/api/v1/visualization/stream', {
    headers: { Authorization: `Bearer ${accessToken() ?? ''}` },
    signal,
  })
  if (!response.ok || !response.body) throw new Error('实时刷新通道连接失败')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (!signal.aborted) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const events = buffer.split('\n\n')
    buffer = events.pop() ?? ''
    for (const event of events) {
      if (event.includes('event:refresh')) onRefresh()
    }
  }
}
