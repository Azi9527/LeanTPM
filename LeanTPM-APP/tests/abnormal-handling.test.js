import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

import { ApiError } from '../utils/errors.js'

function source(relativePath) {
	return readFileSync(new URL(`../${relativePath}`, import.meta.url), 'utf8')
}

test('inspection API exposes the existing abnormal handling endpoint', () => {
	const api = source('api/inspection.js')
	assert.match(api, /recordAbnormalMeasures:\s*\(id, payload\).*\/inspection\/abnormalities\/\$\{id\}\/measures.*method:\s*'PUT'/s)
})

test('abnormal detail shows all registered measures and supports permission-aware editing', () => {
	const page = source('pages/abnormal/index.vue')

	for (const field of ['causeAnalysis', 'temporaryAction', 'permanentCountermeasure']) {
		assert.match(page, new RegExp(`selected\\.${field}`))
		assert.match(page, new RegExp(`form\\.${field}`))
	}
	for (const label of ['原因分析', '临时措施', '恒久对策', '尚未登记']) {
		assert.match(page, new RegExp(label))
	}

	assert.match(page, /can\('inspection:abnormal:handle'\)/)
	assert.match(page, /selected\.abnormalStatus\s*!==\s*'CLOSED'/)
	assert.match(page, /inspectionApi\.recordAbnormalMeasures\(selected\.value\.id/)
	assert.match(page, /version:\s*selected\.value\.version/)
	assert.match(page, /PROCESSING:\s*'已处理'/)
	assert.match(page, /abnormalStatus:\s*'PROCESSING'/)
	assert.match(page, /保存后状态自动更新为已处理/)
	assert.doesNotMatch(page, /responsibleUserId:\s*selected\.value\.responsibleUserId/)
	assert.doesNotMatch(page, /dueTime:\s*selected\.value\.dueTime/)
	assert.doesNotMatch(page, /requestedEquipmentStatus:\s*selected\.value\.requestedEquipmentStatus/)
})

test('abnormal handling uses one prominent action after the attachment section', () => {
	const page = source('pages/abnormal/index.vue')
	const attachments = page.indexOf('暂无相关附件')
	const action = page.indexOf('class="primary-handle-button"')

	assert.ok(attachments > 0)
	assert.ok(action > attachments)
	assert.doesNotMatch(page, /class="edit-button"/)
	assert.match(page, /登记异常处置/)
})

test('abnormal save failures distinguish an unpublished backend from phone connectivity', async () => {
	const { abnormalHandlingErrorMessage } = await import('../utils/abnormal-handling.js')

	assert.match(
		abnormalHandlingErrorMessage(new ApiError('HTTP_404', 'Not Found', 404)),
		/Backend.*V53/
	)
	assert.match(
		abnormalHandlingErrorMessage(new ApiError('NETWORK_ERROR', '企业服务暂时无法连接')),
		/异常清单.*Backend.*反向代理/
	)
	assert.match(
		abnormalHandlingErrorMessage(new ApiError('FORBIDDEN', '无权', 403)),
		/没有异常处置登记权限/
	)
	assert.match(
		abnormalHandlingErrorMessage(new ApiError('OPTIMISTIC_LOCK_CONFLICT', '冲突', 409)),
		/其他人修改/
	)
})
