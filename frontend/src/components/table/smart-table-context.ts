import type { ComputedRef, InjectionKey } from 'vue'

export type SmartFilterOperator =
  | 'CONTAINS'
  | 'NOT_CONTAINS'
  | 'EQ'
  | 'NE'
  | 'STARTS_WITH'
  | 'ENDS_WITH'
  | 'GT'
  | 'GTE'
  | 'LT'
  | 'LTE'
  | 'BETWEEN'
  | 'EMPTY'
  | 'NOT_EMPTY'

export interface SmartTableFilter {
  field: string
  operator: SmartFilterOperator
  value?: string
  values?: string[]
  label?: string
}

export interface SmartTableServerQuery {
  logic: 'AND' | 'OR'
  filters: SmartTableFilter[]
  sortBy?: string
  sortDirection?: 'asc' | 'desc'
}

export interface SmartTableQueryParams {
  tableFilters?: string
  sortBy?: string
  sortDirection?: 'asc' | 'desc'
}

export function applySmartTableQuery<T extends SmartTableQueryParams>(
  target: T,
  tableQuery: SmartTableServerQuery,
) {
  target.tableFilters = tableQuery.filters.length
    ? JSON.stringify({ logic: tableQuery.logic, filters: tableQuery.filters })
    : undefined
  target.sortBy = tableQuery.sortBy
  target.sortDirection = tableQuery.sortDirection
}

export interface SmartTableContext {
  rows: ComputedRef<Record<string, unknown>[]>
  serverMode: ComputedRef<boolean>
  query: ComputedRef<SmartTableServerQuery>
  updateFilter: (filter: SmartTableFilter) => void
  removeFilter: (field: string) => void
}

export const smartTableContextKey: InjectionKey<SmartTableContext> = Symbol('lean-smart-table')

const labelCandidates: Record<string, string[]> = {
  '任务': ['taskCode', 'planCode'],
  '方案': ['schemeName', 'schemeCode'],
  '设备': ['equipmentName', 'equipmentCode'],
  '设备/故障': ['equipmentName', 'faultTitle'],
  '设备/项目': ['equipmentName', 'itemName'],
  '异常': ['abnormalTitle', 'abnormalCode'],
  '类型': ['locationType', 'categoryType', 'inspectionType', 'maintenanceType', 'menuType', 'dataType', 'dayType'],
  '结果类型': ['resultType'],
  '状态': ['taskStatus', 'planStatus', 'abnormalStatus', 'reportStatus', 'repairStatus', 'deliveryStatus', 'dataStatus', 'status'],
  '当前状态': ['currentStatusCode', 'status'],
  '任务状态': ['taskStatus'],
  '派工进度': ['dispatchStatus'],
  '进度': ['completedItemCount', 'progress'],
  '等级': ['severity', 'abnormalLevel'],
  '位置': ['locationName', 'organizationName'],
  '周期': ['cycleType', 'resetCycle'],
  '当前版本': ['currentVersionNumber', 'version'],
  '日期类型': ['dayType'],
  '结果': ['resultCode', 'success'],
  '标识': ['criticalFlag', 'specialFlag'],
  '启用': ['status', 'enabled'],
  '必填': ['requiredFlag'],
  '停机联动': ['equipmentStopRequired'],
  '故障时间': ['faultTime'],
  '登录时间': ['loginTime'],
  '操作时间': ['operationTime'],
  '变更时间': ['changeTime'],
  '发送时间': ['sentTime', 'createdTime'],
  '产生时间': ['occurredTime'],
  '持续时间': ['statusDurationSeconds', 'durationMs'],
  '耗时': ['durationMs'],
  '工时': ['effectiveWorkSeconds'],
  '大小': ['fileSize'],
  '数量': ['quantity'],
  '金额': ['totalAmount', 'totalCost'],
  '层级': ['targetLevel', 'treeLevel'],
  '范围策略': ['scopeType'],
  '渠道': ['channelCode', 'channels'],
  '坐标': ['positionX'],
  '后备颜色': ['fallbackColor'],
  '状态名称': ['statusName'],
  '显示颜色': ['displayColor'],
  '发光颜色': ['emissiveColor'],
  '脉冲': ['pulseFlag'],
}

const valueLabels: Record<string, string> = {
  RUNNING: '运行', IDLE: '空闲', STOPPED: '停机', SCRAPPED: '报废',
  ACTIVE: '启用', ENABLED: '启用', DISABLED: '停用', PAUSED: '暂停', CANCELLED: '已取消',
  PENDING: '待处理', UNASSIGNED: '未派工', PENDING_ASSIGNMENT: '待派工', ASSIGNED: '已派工',
  PENDING_EXECUTION: '待执行', IN_PROGRESS: '执行中', SUBMITTED: '已提交', PENDING_REVIEW: '待复核',
  COMPLETED: '已完成', CLOSED: '已关闭', VOIDED: '已作废', OVERDUE: '已逾期',
  PUBLISHED: '已发布', DRAFT: '草稿', REPORTED: '已上报', ACCEPTED: '已受理',
  REJECTED: '已驳回', PENDING_ACCEPTANCE: '待验收', READY: '待发送', SENT: '已发送',
  FAILED: '失败', SKIPPED: '已跳过', WORKDAY: '上班', RESTDAY: '休息',
  QUALIFIED: '合格', UNQUALIFIED: '不合格', NORMAL: '正常', ABNORMAL: '异常',
}

export function inferField(label: unknown, row?: Record<string, unknown>): string {
  if (!row || typeof label !== 'string' || ['操作', '动作'].includes(label)) return ''
  return (labelCandidates[label] || []).find((candidate) => candidate in row) || ''
}

export function readField(row: Record<string, unknown>, path: string): unknown {
  return path.split('.').reduce<unknown>((value, key) => {
    if (!value || typeof value !== 'object') return undefined
    return (value as Record<string, unknown>)[key]
  }, row)
}

export function filterText(value: unknown): string {
  if (value === null || value === undefined || value === '') return '（空白）'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value)
    } catch {
      return String(value)
    }
  }
  const text = String(value)
  return valueLabels[text] || text
}
