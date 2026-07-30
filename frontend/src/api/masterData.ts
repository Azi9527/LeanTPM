import { http } from '@/utils/http'
import type { ApiResponse } from '@/types/api'

export interface OrganizationRow {
  id: number
  parentId: number
  organizationCode: string
  organizationName: string
  organizationType: 'ENTERPRISE' | 'FACTORY' | 'DEPARTMENT' | 'WORKSHOP' | 'LINE' | 'TEAM'
  managerUserId?: number
  managerName?: string
  sortOrder: number
  status: number
  description?: string
  version: number
}

export interface LocationRow {
  id: number
  parentId: number
  locationCode: string
  locationName: string
  locationType: 'ENTERPRISE' | 'FACTORY' | 'PLANT_AREA' | 'WORKSHOP' | 'LINE' | 'WORKSTATION'
  organizationId: number
  organizationName: string
  managerUserId?: number
  managerName?: string
  sortOrder: number
  status: number
  description?: string
  version: number
}

export interface EquipmentCategoryRow {
  id: number
  parentId: number
  categoryCode: string
  categoryName: string
  treeLevel: number
  defaultInspectionTemplateId?: number
  defaultMaintenanceTemplateId?: number
  defaultFaultTypeId?: number
  defaultOeeMode?: string
  sortOrder: number
  status: number
  description?: string
  version: number
}

export interface AttributeDefinitionRow {
  id: number
  categoryId: number
  categoryName: string
  attributeCode: string
  attributeName: string
  dataType: 'STRING' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN' | 'DATE' | 'ENUM'
  unit?: string
  requiredFlag: boolean
  defaultValue?: string
  validationPattern?: string
  minimumValue?: number
  maximumValue?: number
  enumOptionsJson?: string
  sortOrder: number
  status: number
  description?: string
  version: number
  inherited: boolean
}

export interface ReferenceUser {
  id: number
  username: string
  realName: string
  organizationId?: number
  organizationName?: string
}

async function getData<T>(url: string, params?: object): Promise<T> {
  const response = await http.get<ApiResponse<T>>(url, { params })
  return response.data.data
}

export const masterDataApi = {
  organizations: () => getData<OrganizationRow[]>('/master-data/organizations'),
  createOrganization: (data: object) => http.post('/master-data/organizations', data),
  updateOrganization: (id: number, data: object) =>
    http.put(`/master-data/organizations/${id}`, data),
  deleteOrganization: (id: number, version: number) =>
    http.delete(`/master-data/organizations/${id}`, { params: { version } }),

  locations: () => getData<LocationRow[]>('/master-data/locations'),
  createLocation: (data: object) => http.post('/master-data/locations', data),
  updateLocation: (id: number, data: object) =>
    http.put(`/master-data/locations/${id}`, data),
  deleteLocation: (id: number, version: number) =>
    http.delete(`/master-data/locations/${id}`, { params: { version } }),

  categories: () => getData<EquipmentCategoryRow[]>('/master-data/equipment-categories'),
  createCategory: (data: object) => http.post('/master-data/equipment-categories', data),
  updateCategory: (id: number, data: object) =>
    http.put(`/master-data/equipment-categories/${id}`, data),
  deleteCategory: (id: number, version: number) =>
    http.delete(`/master-data/equipment-categories/${id}`, { params: { version } }),

  attributes: (categoryId: number, includeInherited = false) =>
    getData<AttributeDefinitionRow[]>(
      `/master-data/equipment-categories/${categoryId}/attributes`,
      { includeInherited },
    ),
  createAttribute: (categoryId: number, data: object) =>
    http.post(`/master-data/equipment-categories/${categoryId}/attributes`, data),
  updateAttribute: (id: number, data: object) =>
    http.put(`/master-data/equipment-attributes/${id}`, data),
  deleteAttribute: (id: number, version: number) =>
    http.delete(`/master-data/equipment-attributes/${id}`, { params: { version } }),

  referenceUsers: () => getData<ReferenceUser[]>('/master-data/reference-users'),
}
