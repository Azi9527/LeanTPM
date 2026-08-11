import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

import {
  schemeItemConfigMap,
  schemeItemPayload,
} from '../src/utils/inspection-scheme.ts'

test('copies every scheme-level item override without changing source order', () => {
  const configs = schemeItemConfigMap([
    {
      inspectionItemId: 11,
      sortOrder: 35,
      requiredFlag: false,
      photoRequiredFlag: true,
      skipAllowedFlag: true,
      abnormalStopFlag: true,
    },
  ])

  assert.deepEqual(schemeItemPayload([11], configs, { 11: false }), [
    {
      inspectionItemId: 11,
      sortOrder: 35,
      required: false,
      photoRequired: true,
      skipAllowed: true,
      abnormalStop: false,
    },
  ])
})

test('scheme list exposes a current-version detail action with execution rules', () => {
  const source = readFileSync(
    new URL('../src/views/inspection/schemes/InspectionSchemeView.vue', import.meta.url),
    'utf8',
  )

  assert.match(source, />查看明细</)
  assert.match(source, /label="项目分类"/)
  assert.match(source, /label="必填"/)
  assert.match(source, /label="拍照"/)
  assert.match(source, /label="允许跳过"/)
  assert.match(source, /适用设备分类/)
})
