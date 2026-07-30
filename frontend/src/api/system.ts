import { http } from '@/utils/http'
import type { ApiResponse, MenuItem, PageResult } from '@/types/api'

export interface UserRow {
  id: number
  username: string
  realName: string
  employeeNo?: string
  mobile?: string
  email?: string
  organizationId: number
  organizationName: string
  status: number
  mobileEnabled: boolean
  mustChangePassword: boolean
  lastLoginTime?: string
  createdTime: string
  version: number
  roleIds: number[]
}

export interface RoleRow {
  id: number
  roleCode: string
  roleName: string
  dataScope: string
  status: number
  sortOrder: number
  remark?: string
  version: number
  menuIds: number[]
  customOrganizationIds: number[]
}

export interface OrganizationNode {
  id: number
  parentId: number
  organizationCode: string
  organizationName: string
  organizationType: string
  status: number
}

export interface DataScopeDefinition {
  id: number
  scopeCode: string
  scopeName: string
  scopeType: string
  description?: string
  sortOrder: number
}

export interface DictionaryItem {
  id: number
  dictTypeId: number
  itemValue: string
  itemLabel: string
  color?: string
  icon?: string
  status: number
  sortOrder: number
  isDefault: boolean
  version: number
}

export interface DictionaryType {
  id: number
  dictCode: string
  dictName: string
  status: number
  remark?: string
  version: number
  items: DictionaryItem[]
}

export interface LoginLogRow {
  id: number
  username: string
  userId?: number
  loginIp?: string
  userAgent?: string
  success: boolean
  failureReason?: string
  loginTime: string
}

export interface OperationLogRow {
  id: number
  userId?: number
  username?: string
  requestMethod: string
  requestPath: string
  requestIp?: string
  success: boolean
  errorMessage?: string
  durationMs: number
  operationTime: string
}

export interface AttachmentRow {
  id: number
  businessType?: string
  businessId?: number
  originalName: string
  storedName: string
  storagePath: string
  contentType?: string
  extension: string
  fileSize: number
  sha256: string
  createdTime: string
}

export interface ParameterRow {
  id: number
  parameterKey: string
  parameterName: string
  parameterValue: string
  valueType: 'STRING' | 'BOOLEAN' | 'INTEGER' | 'DECIMAL'
  groupCode: string
  description?: string
  builtIn: boolean
  status: number
  updatedTime: string
  version: number
}

export interface NumberRuleRow {
  id: number
  ruleCode: string
  ruleName: string
  prefix: string
  datePattern: string
  separatorValue: string
  sequenceLength: number
  resetPeriod: 'DAILY' | 'MONTHLY' | 'YEARLY' | 'NEVER'
  status: number
  description?: string
  updatedTime: string
  version: number
  preview: string
}

export interface GeneratedNumber {
  ruleCode: string
  businessNumber: string
  sequence: number
}

export interface OnlineSessionRow {
  sessionId: string
  userId: number
  username: string
  realName: string
  loginIp: string
  userAgent: string
  loginTime: string
  lastActiveTime: string
  expiresAt: string
  currentSession: boolean
}

export interface PageQuery {
  keyword?: string
  status?: number
  page: number
  pageSize: number
}

async function getData<T>(url: string, params?: object): Promise<T> {
  const response = await http.get<ApiResponse<T>>(url, { params })
  return response.data.data
}

export const systemApi = {
  users: (query: PageQuery) => getData<PageResult<UserRow>>('/system/users', query),
  createUser: (data: object) => http.post('/system/users', data),
  updateUser: (id: number, data: object) => http.put(`/system/users/${id}`, data),
  updateUserStatus: (id: number, data: object) => http.patch(`/system/users/${id}/status`, data),
  resetUserPassword: (id: number, newPassword: string) =>
    http.post(`/system/users/${id}/reset-password`, { newPassword }),

  roles: () => getData<RoleRow[]>('/system/roles'),
  createRole: (data: object) => http.post('/system/roles', data),
  updateRole: (id: number, data: object) => http.put(`/system/roles/${id}`, data),
  updateRoleDataScope: (id: number, data: object) =>
    http.put(`/system/roles/${id}/data-scope`, data),

  organizations: () => getData<OrganizationNode[]>('/system/organizations/tree'),
  dataScopes: () => getData<DataScopeDefinition[]>('/system/data-scopes'),

  menus: () => getData<MenuItem[]>('/system/menus/tree'),

  dictionaries: () => getData<DictionaryType[]>('/system/dictionaries'),
  createDictionary: (data: object) => http.post('/system/dictionaries', data),
  updateDictionary: (id: number, data: object) => http.put(`/system/dictionaries/${id}`, data),
  deleteDictionary: (id: number) => http.delete(`/system/dictionaries/${id}`),
  createDictionaryItem: (typeId: number, data: object) =>
    http.post(`/system/dictionaries/${typeId}/items`, data),
  updateDictionaryItem: (id: number, data: object) => http.put(`/system/dictionary-items/${id}`, data),
  deleteDictionaryItem: (id: number) => http.delete(`/system/dictionary-items/${id}`),

  loginLogs: (query: PageQuery) => getData<PageResult<LoginLogRow>>('/system/login-logs', query),
  operationLogs: (query: PageQuery) =>
    getData<PageResult<OperationLogRow>>('/system/operation-logs', query),

  attachments: (query: PageQuery) =>
    getData<PageResult<AttachmentRow>>('/system/attachments', query),
  uploadAttachment: (formData: FormData) =>
    http.post<ApiResponse<AttachmentRow>>('/system/attachments', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }),
  downloadAttachment: (id: number) =>
    http.get<Blob>(`/system/attachments/${id}/content`, { responseType: 'blob' }),

  parameters: (params?: { keyword?: string; groupCode?: string }) =>
    getData<ParameterRow[]>('/system/parameters', params),
  createParameter: (data: object) => http.post('/system/parameters', data),
  updateParameter: (id: number, data: object) => http.put(`/system/parameters/${id}`, data),
  deleteParameter: (id: number) => http.delete(`/system/parameters/${id}`),

  numberRules: (params?: { keyword?: string }) =>
    getData<NumberRuleRow[]>('/system/number-rules', params),
  createNumberRule: (data: object) => http.post('/system/number-rules', data),
  updateNumberRule: (id: number, data: object) => http.put(`/system/number-rules/${id}`, data),
  generateNumber: (ruleCode: string) =>
    http.post<ApiResponse<GeneratedNumber>>(
      `/system/number-rules/${encodeURIComponent(ruleCode)}/generate`,
    ),

  onlineUsers: () => getData<OnlineSessionRow[]>('/system/online-users'),
  kickoutOnlineUser: (sessionId: string) =>
    http.delete(`/system/online-users/${encodeURIComponent(sessionId)}`),
}
