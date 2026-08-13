import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

import {
  schemeItemConfigMap,
  schemeItemPayload,
} from '../src/utils/inspection-scheme.ts'

test('scheme payload only references item ids and preserves source order', () => {
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

  assert.deepEqual(schemeItemPayload([11], configs), [
    {
      inspectionItemId: 11,
      sortOrder: 35,
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

test('inspection schemes and plans expose the hourly cycle consistently', () => {
  const schemeSource = readFileSync(
    new URL('../src/views/inspection/schemes/InspectionSchemeView.vue', import.meta.url),
    'utf8',
  )
  const planSource = readFileSync(
    new URL('../src/views/inspection/plans/InspectionPlanView.vue', import.meta.url),
    'utf8',
  )
  const apiSource = readFileSync(
    new URL('../src/api/inspection.ts', import.meta.url),
    'utf8',
  )

  assert.match(schemeSource, /HOURLY:\s*'每小时'/)
  assert.match(planSource, /HOURLY:\s*'每小时'/)
  assert.match(apiSource, /'HOURLY'/)
})
