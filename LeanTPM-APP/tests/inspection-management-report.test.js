import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

function source(relativePath) {
	return readFileSync(new URL(`../${relativePath}`, import.meta.url), 'utf8')
}

test('mobile API exposes the authorized management inspection report query', () => {
	const api = source('api/mobile.js')
	assert.match(api, /inspectionPerformanceReport:[\s\S]*\/mobile\/inspection-performance-report/)
	assert.match(api, /inspectionPerformanceTasks:[\s\S]*\/mobile\/inspection-performance-tasks/)
	assert.match(api, /inspectionPerformanceTaskItems:[\s\S]*\/mobile\/inspection-performance-tasks\/\$\{taskId\}\/items/)
})

test('report page supports management scope, department and employee filters', () => {
	const page = source('pages/report/index.vue')
	assert.match(page, /管理点检绩效/)
	assert.match(page, /本部门及下属部门/)
	assert.match(page, /部门筛选/)
	assert.match(page, /员工筛选/)
	assert.match(page, /Top员工/)
	assert.match(page, /按点检任务统计/)
	assert.match(page, /完成任务数/)
	assert.match(page, /部门绩效/)
	assert.match(page, /个人绩效/)
	assert.match(page, /扫码直检不纳入计划考核/)
	assert.match(page, /mobileApi\.inspectionPerformanceReport/)
})

test('all management metrics drill down from unique task list to task item details', () => {
	const page = source('pages/report/index.vue')
	for (const metric of ['DUE', 'COMPLETED', 'PENDING', 'OVERDUE', 'ON_TIME', 'LATE', 'ABNORMAL', 'QUICK', 'QUICK_ABNORMAL']) {
		assert.match(page, new RegExp(`openDetails\\('${metric}'`))
	}
	assert.match(page, /openEmployeeDetails/)
	assert.match(page, /openOrganizationDetails/)
	assert.match(page, /mobileApi\.inspectionPerformanceTasks/)
	assert.match(page, /mobileApi\.inspectionPerformanceTaskItems/)
	assert.match(page, /任务清单/)
	assert.match(page, /openTaskItems/)
	assert.match(page, /返回任务清单/)
	assert.match(page, /taskRows/)
	assert.match(page, /itemRows/)
	assert.match(page, /异常说明/)
	assert.match(page, /异常状态/)
})

test('ordinary employees keep the personal report title and cannot expose manager filters', () => {
	const page = source('pages/report/index.vue')
	assert.match(page, /report\.canManage[\s\S]*管理点检绩效[\s\S]*我的点检绩效/)
	assert.match(page, /v-if="report\.canManage"[\s\S]*organization/)
})
