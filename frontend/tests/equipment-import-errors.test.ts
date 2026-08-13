import test from 'node:test'
import assert from 'node:assert/strict'

import {
  buildEquipmentImportErrorRows,
  equipmentImportSuggestion,
  groupEquipmentImportErrors,
} from '../src/utils/equipment-import-errors.ts'

test('groups equipment import errors by Excel row without hiding any issue', () => {
  const groups = groupEquipmentImportErrors([
    { rowNumber: 3, field: '所属组织', message: '编码不存在或已停用' },
    { rowNumber: 2, field: '设备名称', message: '设备名称不能为空' },
    { rowNumber: 3, field: '主负责人', message: '主负责人账号不存在或已停用' },
  ])

  assert.deepEqual(groups, [
    {
      rowNumber: 2,
      issues: [{ rowNumber: 2, field: '设备名称', message: '设备名称不能为空' }],
    },
    {
      rowNumber: 3,
      issues: [
        { rowNumber: 3, field: '所属组织', message: '编码不存在或已停用' },
        { rowNumber: 3, field: '主负责人', message: '主负责人账号不存在或已停用' },
      ],
    },
  ])
})

test('provides actionable suggestions for common equipment import errors', () => {
  assert.match(
    equipmentImportSuggestion({
      rowNumber: 3,
      field: '所属组织',
      message: '编码不存在或已停用',
    }),
    /组织编码.*组织管理/,
  )
  assert.match(
    equipmentImportSuggestion({
      rowNumber: 3,
      field: '主负责人',
      message: '主负责人账号不存在或已停用',
    }),
    /用户账号.*用户管理/,
  )
  assert.match(
    equipmentImportSuggestion({
      rowNumber: 4,
      field: '投产日期',
      message: '投产日期格式不正确',
    }),
    /2026-08-11.*2026\/08\/11/,
  )
})

test('builds visible table rows with row, field, original value, reason and suggestion', () => {
  assert.deepEqual(
    buildEquipmentImportErrorRows([
      {
        rowNumber: 2,
        field: '所属组织',
        originalValue: 'LINE-FX',
        message: '所属组织编码不存在或已停用',
      },
      {
        rowNumber: 3,
        message: '',
      },
    ]),
    [
      {
        key: '2-0',
        rowNumber: 2,
        field: '所属组织',
        originalValue: 'LINE-FX',
        message: '所属组织编码不存在或已停用',
        suggestion: '请填写系统中已启用的组织编码；可在组织管理中查询。',
      },
      {
        key: '3-1',
        rowNumber: 3,
        field: '整行数据',
        originalValue: '（空）',
        message: '服务端未返回具体错误说明',
        suggestion: '请根据问题说明修改“该项数据”，然后重新选择 Excel 进行预校验。',
      },
    ],
  )
})
