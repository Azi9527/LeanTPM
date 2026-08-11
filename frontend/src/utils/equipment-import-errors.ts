export interface EquipmentImportError {
  rowNumber: number
  field?: string
  message: string
}

export interface EquipmentImportErrorGroup {
  rowNumber: number
  issues: EquipmentImportError[]
}

const FIELD_REFERENCES: Record<string, { value: string; source: string }> = {
  设备分类: { value: '设备分类编码', source: '设备分类管理' },
  所属组织: { value: '组织编码', source: '组织管理' },
  物理位置: { value: '位置编码', source: '位置管理' },
  位置: { value: '位置编码', source: '位置管理' },
  主负责人: { value: '用户账号', source: '用户管理' },
  负责人: { value: '用户账号', source: '用户管理' },
}

export function groupEquipmentImportErrors(
  errors: EquipmentImportError[] = [],
): EquipmentImportErrorGroup[] {
  const grouped = new Map<number, EquipmentImportError[]>()
  errors.forEach((error) => {
    const rowNumber = Number(error.rowNumber) || 0
    const issues = grouped.get(rowNumber) || []
    issues.push(error)
    grouped.set(rowNumber, issues)
  })
  return [...grouped.entries()]
    .sort(([left], [right]) => left - right)
    .map(([rowNumber, issues]) => ({ rowNumber, issues }))
}

export function equipmentImportSuggestion(error: EquipmentImportError): string {
  const field = error.field?.trim() || '该项数据'
  const message = error.message || ''
  const reference = FIELD_REFERENCES[field]

  if (/日期|yyyy|格式不正确/i.test(message) && /日期/.test(field + message)) {
    return `请填写有效的${field}，例如 2026-08-11 或 2026/08/11。`
  }
  if (/不存在|已停用/.test(message) && reference) {
    return `请填写系统中已启用的${reference.value}；可在${reference.source}中查询。`
  }
  if (/必填|不能为空|未填写/.test(message)) {
    return `请在 Excel 中补充“${field}”。`
  }
  if (/重复/.test(message)) {
    return `请检查“${field}”，删除重复行或改为不重复的值。`
  }
  if (/已存在/.test(message)) {
    return `请将“${field}”改为系统中尚未使用的值。`
  }
  if (/长度|最多|不能超过|过长/.test(message)) {
    return `请缩短“${field}”内容，使其满足提示的长度限制。`
  }
  if (/格式|只能|应为|必须为/.test(message)) {
    return `请按错误说明调整“${field}”的填写格式。`
  }
  return `请根据问题说明修改“${field}”，然后重新选择 Excel 进行预校验。`
}
