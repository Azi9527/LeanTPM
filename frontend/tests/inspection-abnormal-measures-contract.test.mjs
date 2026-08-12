import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const view = readFileSync(
  new URL('../src/views/inspection/abnormal/InspectionAbnormalView.vue', import.meta.url),
  'utf8',
)
const api = readFileSync(new URL('../src/api/inspection.ts', import.meta.url), 'utf8')

test('inspection abnormal handling saves three measures and marks the row processed', () => {
  assert.match(view, /label="原因分析"[\s\S]*v-model="form\.causeAnalysis"/)
  assert.match(view, /label="临时措施"[\s\S]*v-model="form\.temporaryAction"/)
  assert.match(view, /label="恒久对策"[\s\S]*v-model="form\.permanentCountermeasure"/)
  assert.match(view, /targetStatus:\s*'PROCESSING'/)
  assert.match(view, /PROCESSING:\s*\{\s*label:\s*'已处理'/)
  assert.doesNotMatch(view, /处理状态|提交验证|async function verify|verifyAbnormal/)
  assert.doesNotMatch(view, />通过<|>退回</)
})

test('inspection abnormal API exposes cause, temporary and permanent measures', () => {
  const abnormalRow = api.slice(
    api.indexOf('export interface AbnormalRow'),
    api.indexOf('export interface TaskDetail'),
  )
  assert.match(abnormalRow, /causeAnalysis\?: string/)
  assert.match(abnormalRow, /temporaryAction\?: string/)
  assert.match(abnormalRow, /permanentCountermeasure\?: string/)
  assert.doesNotMatch(abnormalRow, /finalResult/)
})

test('PC abnormal handling uses a compact non-scrolling maintenance layout', () => {
  assert.match(view, /class="abnormal-handle-dialog"[\s\S]*width="min\(1080px, 96vw\)"/)
  assert.match(view, /class="assignment-grid"/)
  assert.match(view, /class="measure-grid"/)
  assert.match(view, /label="原因分析" class="measure-field"/)
  assert.match(view, /label="临时措施" class="measure-field"/)
  assert.match(view, /label="恒久对策" class="measure-field"/)
  assert.match(view, /v-if="attachments\.length"[\s\S]*InspectionAttachmentList/)
  assert.match(view, /v-else class="attachment-empty-inline">暂无附件</)
  assert.match(view, /\.assignment-grid[^{]*\{[^}]*grid-template-columns:\s*repeat\(3,/)
  assert.match(view, /\.measure-grid[^{]*\{[^}]*grid-template-columns:\s*repeat\(3,/)
})
