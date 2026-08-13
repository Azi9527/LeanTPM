import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const view = readFileSync('src/views/inspection/statistics/InspectionStatisticsView.vue', 'utf8')
const api = readFileSync('src/api/inspection.ts', 'utf8')

assert.match(api, /export interface StatisticsQuery/)
assert.match(api, /sourceType\?:/)
assert.match(api, /timelinessStatus\?:/)
assert.match(api, /statisticsTasks:/)
assert.match(api, /exportStatisticsDetails:/)

assert.match(view, /任务来源/)
assert.match(view, /完成时效/)
assert.match(view, /扫码直接点检/)
assert.match(view, /逾期完成/)
assert.match(view, /逾期未完成/)
assert.match(view, /包含下属组织/)
assert.match(view, /导出明细清单/)
assert.match(view, /auth\.can\('inspection:task:export'\)/)

console.log('inspection statistics management contract passed')
