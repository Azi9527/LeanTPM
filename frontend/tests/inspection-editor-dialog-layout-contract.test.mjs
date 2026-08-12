import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

function source(relativePath) {
  return readFileSync(new URL(`../${relativePath}`, import.meta.url), 'utf8')
}

function assertSectionedEditor(sourceText, sectionLabels) {
  assert.match(sourceText, /class="inspection-editor-dialog"/)
  assert.match(sourceText, /align-center/)
  assert.match(sourceText, /class="inspection-editor-tabs"/)
  for (const label of sectionLabels) {
    assert.match(sourceText, new RegExp(`<el-tab-pane[^>]+label="${label}"`))
  }
  assert.match(sourceText, /:global\(\.inspection-editor-dialog\)[\s\S]*max-height:\s*calc\(100vh\s*-\s*32px\)/)
  assert.match(sourceText, /:global\(\.inspection-editor-dialog\s+\.el-dialog__body\)[\s\S]*overflow:\s*hidden/)
  assert.match(sourceText, /\.inspection-editor-tabs[\s\S]*overflow:\s*hidden/)
  assert.match(sourceText, /\.inspection-editor-pane[\s\S]*overflow-y:\s*auto/)
  assert.match(sourceText, /@media\s*\(max-width:[\s\S]*grid-template-columns:\s*1fr/)
}

test('inspection item editor uses three compact sections with a fixed action area', () => {
  assertSectionedEditor(
    source('src/views/inspection/items/InspectionItemView.vue'),
    ['基础标准', '判定与异常', '拍照与执行']
  )
})

test('inspection scheme editor uses three compact sections with a fixed action area', () => {
  assertSectionedEditor(
    source('src/views/inspection/schemes/InspectionSchemeView.vue'),
    ['基础与周期', '人员与适用', '提交与版本']
  )
})
