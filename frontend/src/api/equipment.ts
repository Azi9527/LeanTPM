import { http } from '@/utils/http'
import type { ApiResponse, PageResult } from '@/types/api'

export interface EquipmentRow {
  id: number
  equipmentCode: string
  equipmentName: string
  categoryId: number
  categoryCode: string
  categoryName: string
  model?: string
  specification?: string
  brand?: string
  manufacturer?: string
  factorySerialNumber?: string
  productionDate?: string
  commissioningDate?: string
  organizationId: number
  organizationCode: string
  organizationName: string
  locationId: number
  locationCode: string
  locationName: string
  primaryResponsibleUserId?: number
  primaryResponsibleUsername?: string
  primaryResponsibleName?: string
  assetNumber?: string
  lifecycleStage: LifecycleStage
  criticalFlag: boolean
  specialFlag: boolean
  oeeEnabled: boolean
  status: number
  description?: string
  currentStatusCode: string
  statusSince: string
  statusDurationSeconds: number
  currentStatusVersion: number
  activeBarcodeId?: number
  activeBarcodeToken?: string
  createdTime: string
  updatedTime: string
  version: number
}

export type LifecycleStage =
  | 'PLANNING'
  | 'INSTALLATION'
  | 'COMMISSIONING'
  | 'IN_SERVICE'
  | 'IDLE'
  | 'SEALED'
  | 'SCRAPPED'

export interface AttributeValueRow {
  definitionId: number
  attributeCode: string
  attributeName: string
  dataType: 'STRING' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN' | 'DATE' | 'ENUM'
  unit?: string
  requiredFlag: boolean
  value?: string
}

export interface ResponsiblePersonRow {
  id: number
  userId: number
  username: string
  realName: string
  responsibilityType: 'PRIMARY' | 'OPERATOR' | 'INSPECTOR' | 'MAINTAINER'
  startDate?: string
  endDate?: string
  status: number
}

export interface StatusHistoryRow {
  id: number
  fromStatusCode?: string
  toStatusCode: string
  startedTime: string
  endedTime?: string
  durationSeconds?: number
  reason?: string
  sourceType: string
  changedByName?: string
}

export interface TransferRow {
  id: number
  fromOrganizationId?: number
  fromOrganizationName?: string
  toOrganizationId: number
  toOrganizationName: string
  fromLocationId?: number
  fromLocationName?: string
  toLocationId: number
  toLocationName: string
  fromResponsibleUserId?: number
  fromResponsibleName?: string
  toResponsibleUserId?: number
  toResponsibleName?: string
  transferReason: string
  transferredByName?: string
  transferredTime: string
}

export interface BarcodeRow {
  id: number
  equipmentId: number
  equipmentCode: string
  equipmentName: string
  accessToken: string
  barcodeType: 'QR' | 'CODE128'
  active: boolean
  generatedTime: string
  invalidatedTime?: string
  invalidationReason?: string
}

export interface BulkBarcodeResult {
  equipmentCount: number
  generatedCount: number
  existingCount: number
}

export interface DocumentRow {
  attachmentId: number
  originalName: string
  contentType: string
  relationType: string
  remark?: string
  createdTime: string
}

export interface EquipmentDetail {
  equipment: EquipmentRow
  attributes: AttributeValueRow[]
  responsiblePersons: ResponsiblePersonRow[]
  barcodes: BarcodeRow[]
  statusHistory: StatusHistoryRow[]
  transfers: TransferRow[]
  documents: DocumentRow[]
  changeLogs: Array<{
    id: number
    operationType: string
    operatorName: string
    changedFields: string
    changeTime: string
  }>
}

export interface EquipmentQuery {
  keyword?: string
  categoryId?: number
  organizationId?: number
  locationId?: number
  currentStatusCode?: string
  lifecycleStage?: string
  status?: number
  page?: number
  pageSize?: number
}

export interface PublicEquipmentView {
  accessToken: string
  equipmentCode: string
  equipmentName: string
  categoryName: string
  locationName: string
  currentStatusCode: string
  statusSince: string
}

export interface ImportResult {
  totalRows: number
  importedRows: number
  errors: Array<{ rowNumber: number; field?: string; message: string }>
}

async function getData<T>(url: string, params?: object): Promise<T> {
  const response = await http.get<ApiResponse<T>>(url, { params })
  return response.data.data
}

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}

export const equipmentApi = {
  page: (params: EquipmentQuery) =>
    getData<PageResult<EquipmentRow>>('/equipment', params),
  detail: (id: number) => getData<EquipmentDetail>(`/equipment/${id}`),
  create: (data: object) => http.post('/equipment', data),
  update: (id: number, data: object) => http.put(`/equipment/${id}`, data),
  delete: (id: number, version: number) =>
    http.delete(`/equipment/${id}`, { params: { version } }),
  copy: (id: number, data: object) => http.post(`/equipment/${id}/copy`, data),
  transfer: (id: number, data: object) => http.post(`/equipment/${id}/transfer`, data),
  changeStatus: (id: number, data: object) =>
    http.put(`/equipment/${id}/current-status`, data),
  statusHistory: (id: number) =>
    getData<StatusHistoryRow[]>(`/equipment/${id}/status-history`),

  barcodes: (params?: { equipmentId?: number; activeOnly?: boolean }) =>
    getData<BarcodeRow[]>('/equipment/barcodes', params),
  generateBarcode: (id: number, data: object) =>
    http.post(`/equipment/${id}/barcode`, data),
  regenerateBarcode: (id: number, data: object) =>
    http.post(`/equipment/${id}/barcode/regenerate`, data),
  generateAllBarcodes: async (data: object) => {
    const response = await http.post<ApiResponse<BulkBarcodeResult>>(
      '/equipment/barcodes/generate-all',
      data,
    )
    return response.data.data
  },
  unbindBarcode: (id: number, reason?: string) =>
    http.delete(`/equipment/${id}/barcode`, { params: { reason } }),
  barcodeImageUrl: (id: number, width = 320, height = 120) =>
    `/api/v1/equipment/barcodes/${id}/image?width=${width}&height=${height}`,
  barcodeImage: async (id: number, width = 320, height = 120) => {
    const response = await http.get<Blob>(`/equipment/barcodes/${id}/image`, {
      params: { width, height },
      responseType: 'blob',
    })
    return response.data
  },
  barcodeArchive: async (ids: number[] | undefined, width: number, height: number) => {
    const response = await http.get<Blob>('/equipment/barcodes/archive', {
      params: { ids, width, height },
      paramsSerializer: { indexes: null },
      responseType: 'blob',
    })
    return response.data
  },

  publicView: (token: string) =>
    getData<PublicEquipmentView>(`/public/equipment/${token}`),

  importWorkbook: async (file: File) => {
    const form = new FormData()
    form.append('file', file)
    const response = await http.post<ApiResponse<ImportResult>>('/equipment/import', form)
    return response.data.data
  },
  downloadTemplate: async () => {
    const response = await http.get<Blob>('/equipment/import-template', { responseType: 'blob' })
    downloadBlob(response.data, 'LeanTPM-equipment-import-template.xlsx')
  },
  exportWorkbook: async (params: EquipmentQuery) => {
    const response = await http.get<Blob>('/equipment/export', {
      params,
      responseType: 'blob',
    })
    downloadBlob(response.data, 'LeanTPM-equipment-ledger.xlsx')
  },
}
