import test from 'node:test'
import assert from 'node:assert/strict'

const values = new Map()
globalThis.uni = {
	getStorageSync: (key) => values.get(key) ?? '',
	setStorageSync: (key, value) => values.set(key, value),
	removeStorageSync: (key) => values.delete(key)
}

const {
	DEFAULT_SERVER_URL,
	SERVER_PRESETS,
	getServerBaseUrl,
	getServerDisplayUrl,
	normalizeServerBaseUrl,
	saveServerBaseUrl,
	testServerConnection
} = await import('../utils/server.js')
const { createIdempotencyKey } = await import('../utils/idempotency.js')
const { brandingLogoSource, normalizeBranding } = await import('../utils/branding.js')
const { reportPeriodRange } = await import('../utils/report-period.js')
const { inspectionTaskListQuery, inspectionTodoRows } = await import('../utils/inspection-todos.js')
const { equipmentManagementRows, equipmentTaskPreview } = await import('../utils/equipment-context.js')
const {
	inspectionTaskTarget,
	scannedEquipmentMatchesTask,
	taskRequiresEquipmentScan
} = await import('../utils/inspection-navigation.js')
const { extractEquipmentToken, requireEquipmentToken } = await import('../utils/equipment-token.js')
const { applyNumericAbnormalState, initialResultDraft, inferAbnormal, validateInspectionResults, buildInspectionPayload } = await import('../utils/inspection-results.js')
const { saveDraftEnvelope, loadDraftEnvelope, queuePhoto, attachQueuedPhotoToDraft, listQueuedPhotos, removeDraftEnvelope } = await import('../stores/offline.js')
const { listRecentEquipment, rememberEquipment } = await import('../stores/recent-equipment.js')
const { choosePhoto, choosePhotos, formatBusinessDateTime, normalizePhotoPolicy, watermarkLines } = await import('../platform/photo.js')
const { compareVersionCodes } = await import('../utils/version.js')
const { ApiError, equipmentScanErrorMessage, errorMessage, isConflict } = await import('../utils/errors.js')
const { inspectionConflictResolution, rebaseInspectionDraft } = await import('../utils/inspection-conflict.js')
const secureStorage = await import('../platform/secure-storage.js')
const storage = await import('../platform/storage.js')

test('normalizes enterprise server URLs', () => {
	assert.equal(normalizeServerBaseUrl(' http://192.168.31.91:18080/ '), 'http://192.168.31.91:18080/api/v1')
	assert.equal(normalizeServerBaseUrl('https://tpm.example.com/api/v1'), 'https://tpm.example.com/api/v1')
	assert.throws(() => normalizeServerBaseUrl('127.0.0.1:8080'), /HTTP/)
})

test('explains equipment scan failures with actionable business reasons', () => {
	assert.equal(
		equipmentScanErrorMessage(new ApiError(
			'MOBILE_EQUIPMENT_DATA_SCOPE_DENIED',
			'循环泵站一号（VIZ-PUMP-01）归属「机加二线」，当前账号的数据范围不包含该组织或其下级。',
			403
		)),
		'循环泵站一号（VIZ-PUMP-01）归属「机加二线」，当前账号的数据范围不包含该组织或其下级。'
	)
	assert.match(
		equipmentScanErrorMessage(new ApiError('FORBIDDEN', '无权执行此操作', 403)),
		/设备扫码查看/
	)
})

test('defaults to the production cloud while retaining test presets and manual URL support', () => {
	assert.deepEqual(SERVER_PRESETS, [
		{ label: '正式版云服务', url: 'http://8.163.66.164' },
		{ label: '测试云服务', url: 'https://851xn5pikw00.guyubao.com' },
		{ label: '测试内网服务', url: 'http://192.168.31.91:18080' }
	])
	assert.equal(DEFAULT_SERVER_URL, SERVER_PRESETS[0].url)
	assert.equal(getServerDisplayUrl(), SERVER_PRESETS[0].url)
	assert.equal(
		normalizeServerBaseUrl(SERVER_PRESETS[0].url),
		'http://8.163.66.164/api/v1'
	)
})

test('keeps overdue unfinished tasks visible in the pending inspection tab', () => {
	assert.deepEqual(inspectionTaskListQuery('PENDING'), { statusGroup: 'PENDING' })
	assert.deepEqual(inspectionTaskListQuery('OVERDUE'), { taskStatus: 'OVERDUE' })
	assert.deepEqual(inspectionTaskListQuery('IN_PROGRESS'), { taskStatus: 'IN_PROGRESS' })
	assert.deepEqual(inspectionTaskListQuery('COMPLETED'), {
		statusGroup: 'COMPLETED',
		sortBy: 'completedTime',
		sortDirection: 'DESC'
	})
	assert.deepEqual(inspectionTaskListQuery(''), {})
})

test('persists the exact API base URL used by subsequent login requests', () => {
	const saved = saveServerBaseUrl('http://192.168.31.91:18080')
	assert.equal(saved, 'http://192.168.31.91:18080/api/v1')
	assert.equal(getServerBaseUrl(), saved)
	assert.equal(getServerDisplayUrl(), 'http://192.168.31.91:18080')
})

test('reports WeChat request-domain failures clearly', async () => {
	const previousRequest = globalThis.uni.request
	globalThis.uni.request = ({ fail }) => fail({ errMsg: 'request:fail url not in domain list' })
	try {
		await assert.rejects(
			testServerConnection('https://tpm.example.com'),
			/request 合法域名/
		)
	} finally {
		globalThis.uni.request = previousRequest
	}
})

test('probes the live public branding contract when testing a server', async () => {
	const previousRequest = globalThis.uni.request
	let requestOptions
	globalThis.uni.request = (options) => {
		requestOptions = options
		options.success({
			statusCode: 200,
			data: {
				code: 'OK',
				data: { systemName: 'LeanTPM', shortName: 'LeanTPM' }
			}
		})
	}
	try {
		const branding = await testServerConnection('http://8.163.66.164')
		assert.equal(requestOptions.url, 'http://8.163.66.164/api/v1/public/branding')
		assert.equal(requestOptions.method, 'GET')
		assert.equal(branding.shortName, 'LeanTPM')
	} finally {
		globalThis.uni.request = previousRequest
	}
})

test('rejects an empty public branding response instead of accepting a fallback', async () => {
	const previousRequest = globalThis.uni.request
	globalThis.uni.request = ({ success }) => success({
		statusCode: 200,
		data: { code: 'OK', data: null }
	})
	try {
		await assert.rejects(
			testServerConnection('https://tpm.example.com'),
			/品牌配置/
		)
	} finally {
		globalThis.uni.request = previousRequest
	}
})

test('generates scoped idempotency keys', () => {
	const first = createIdempotencyKey('inspection-submit')
	const second = createIdempotencyKey('inspection-submit')
	assert.match(first, /^inspection-submit-/)
	assert.notEqual(first, second)
})

test('normalizes branding and rejects invalid colors', () => {
	const branding = normalizeBranding({ shortName: '  客户矿业 ', primaryColor: '#ABCDEF', neutralColor: 'red' })
	assert.equal(branding.shortName, '客户矿业')
	assert.equal(branding.primaryColor, '#abcdef')
	assert.equal(branding.neutralColor, '#c4000a')
	assert.equal(brandingLogoSource('/branding/baoshan-mining-logo.png'), '/static/branding/baoshan-mining-logo.png')
	assert.match(brandingLogoSource('data:image/png;base64,AA=='), /^data:image/)
})

test('builds business-friendly report shortcut periods', () => {
	const reference = new Date(2026, 7, 4, 12, 0, 0)
	assert.deepEqual(reportPeriodRange('month', reference), {
		startDate: '2026-08-01', endDate: '2026-08-04'
	})
	assert.deepEqual(reportPeriodRange('previousMonth', reference), {
		startDate: '2026-07-01', endDate: '2026-07-31'
	})
	assert.deepEqual(reportPeriodRange('week', reference), {
		startDate: '2026-08-03', endDate: '2026-08-04'
	})
	assert.deepEqual(reportPeriodRange('today', reference), {
		startDate: '2026-08-04', endDate: '2026-08-04'
	})
})

test('orders active inspection todos for direct execution', () => {
	const rows = [
		{ id: 4, taskStatus: 'COMPLETED', dueTime: '2026-08-04T08:00:00' },
		{ id: 3, taskStatus: 'PENDING', dueTime: '2026-08-05T08:00:00' },
		{ id: 2, taskStatus: 'OVERDUE', dueTime: '2026-08-03T08:00:00' },
		{ id: 1, taskStatus: 'IN_PROGRESS', dueTime: '2026-08-04T09:00:00' }
	]
	assert.deepEqual(inspectionTodoRows(rows).map((task) => task.id), [2, 1, 3])
	assert.deepEqual(inspectionTodoRows(rows, 2).map((task) => task.id), [2, 1])
})

test('shows only customer-approved equipment management fields', () => {
	const rows = equipmentManagementRows({
		organizationName: '磨浮车间',
		locationName: '二层',
		primaryResponsibleUserName: '张三',
		model: 'KYF-100m³',
		lifecycleStatus: 'IN_SERVICE',
		equipmentTagNames: '关键设备',
		description: '主浮选设备',
		statusStartTime: '2026-08-10T13:07:00',
		updatedTime: '2026-08-10T10:37:00',
		specification: '100m³',
		brand: '旧品牌',
		manufacturer: '旧厂家',
		factorySerialNumber: 'SN-01',
		assetNumber: 'ASSET-01',
		productionDate: '2025-01-01',
		commissioningDate: '2025-02-01',
		oeeEnabled: true
	})
	assert.deepEqual(rows.map((row) => row.label), [
		'所属组织', '安装位置', '设备负责人', '型号', '生命周期', '设备标识', '备注'
	])
	assert.doesNotMatch(rows.map((row) => row.label).join(','), /状态时间|档案更新时间|规格|品牌|制造商|出厂编号|资产编号|生产日期|投产日期|OEE/)
})

test('limits the equipment context to one actionable task while preserving the total', () => {
	const tasks = [{ taskId: 1 }, { taskId: 2 }, { taskId: 3 }]
	assert.deepEqual(equipmentTaskPreview(tasks), {
		total: 3,
		visible: [{ taskId: 1 }],
		hasMore: true
	})
})

test('requires unfinished inspection tasks to scan the assigned equipment first', () => {
	const pending = { id: 9, taskStatus: 'PENDING', equipmentId: 18, equipmentName: '浮选机' }
	assert.equal(taskRequiresEquipmentScan(pending), true)
	assert.equal(taskRequiresEquipmentScan({ ...pending, taskStatus: 'COMPLETED' }), false)
	assert.deepEqual(inspectionTaskTarget(pending), {
		url: '/pages/scan/index?taskId=9&equipmentId=18&equipmentName=%E6%B5%AE%E9%80%89%E6%9C%BA',
		requiresScan: true
	})
	assert.equal(
		inspectionTaskTarget({ ...pending, id: undefined, taskId: 9 }).url,
		'/pages/scan/index?taskId=9&equipmentId=18&equipmentName=%E6%B5%AE%E9%80%89%E6%9C%BA'
	)
	assert.deepEqual(inspectionTaskTarget({ ...pending, taskStatus: 'COMPLETED' }), {
		url: '/pages/inspection/detail?id=9',
		requiresScan: false
	})
	assert.equal(scannedEquipmentMatchesTask(pending, { equipmentId: 18 }), true)
	assert.equal(scannedEquipmentMatchesTask(pending, { equipmentId: 19 }), false)
})

test('extracts LeanTPM equipment tokens from labels and URLs', () => {
	const token = 'a'.repeat(64)
	assert.equal(extractEquipmentToken(token.toUpperCase()), token)
	assert.equal(extractEquipmentToken(`https://example.test/m/e/${token}?source=label`), token)
	assert.equal(extractEquipmentToken('not-a-label'), null)
	assert.throws(() => requireEquipmentToken('bad'), /LeanTPM/)
})

test('keeps recently scanned equipment unique and newest first', () => {
	values.delete('leantpm_uni_recent_equipment')
	rememberEquipment('token-1', { equipmentId: 1, equipmentCode: 'E-01', equipmentName: '设备一', statusName: '运行' })
	rememberEquipment('token-2', { equipmentId: 2, equipmentCode: 'E-02', equipmentName: '设备二', statusName: '空闲' })
	rememberEquipment('token-1-new', { equipmentId: 1, equipmentCode: 'E-01', equipmentName: '设备一', statusName: '停机' })
	assert.deepEqual(listRecentEquipment().map((item) => item.equipmentId), [1, 2])
	assert.equal(listRecentEquipment()[0].token, 'token-1-new')
	assert.equal(listRecentEquipment()[0].statusName, '停机')
})

test('validates qualitative and quantitative inspection results', () => {
	const items = [
		{ id: 1, itemName: '防护罩', resultType: 'PASS_FAIL', abnormalDefaultStopFlag: false },
		{ id: 2, itemName: '油压', resultType: 'NUMBER', minimumValue: 30, maximumValue: 80, abnormalDefaultStopFlag: true }
	]
	const drafts = { 1: initialResultDraft(items[0]), 2: initialResultDraft(items[1]) }
	assert.match(validateInspectionResults(items, drafts), /防护罩/)
	drafts[1].resultCode = 'PASS'
	drafts[2].numericValue = 90
	applyNumericAbnormalState(items[1], drafts[2])
	drafts[2].abnormalDescription = '超过上限'
	assert.equal(validateInspectionResults(items, drafts), '')
	const payload = buildInspectionPayload({ version: 3 }, items, drafts, '现场完成', [81, '82', 'queued:local'])
	assert.equal(payload.taskVersion, 3)
	assert.deepEqual(payload.taskAttachmentIds, [81, 82])
	assert.equal(payload.results[1].numericValue, 90)
})

test('recalculates numeric abnormal state when a value returns inside its range', () => {
	const item = { id: 2, resultType: 'NUMBER', minimumValue: 30, maximumValue: 80, abnormalDefaultStopFlag: true }
	const draft = initialResultDraft(item)
	assert.equal(draft.equipmentStopRequired, false)
	draft.numericValue = 90
	assert.equal(applyNumericAbnormalState(item, draft), true)
	assert.equal(draft.abnormal, true)
	assert.equal(draft.equipmentStopRequired, true)
	draft.abnormalDescription = 'above range'
	draft.numericValue = 50
	assert.equal(applyNumericAbnormalState(item, draft), false)
	assert.equal(draft.abnormal, false)
	assert.equal(draft.equipmentStopRequired, false)
	assert.equal(draft.abnormalDescription, '')
	draft.numericValue = 30
	assert.equal(inferAbnormal(item, draft), false)
	draft.numericValue = 80
	assert.equal(inferAbnormal(item, draft), false)
})

test('selects camera or phone album as the photo source', async () => {
	const previousChooseImage = globalThis.uni.chooseImage
	let receivedSourceType = []
	let receivedCount = 0
	globalThis.uni.chooseImage = ({ sourceType, count, success }) => {
		receivedSourceType = sourceType
		receivedCount = count
		success({ tempFilePaths: ['inspection-1.jpg', 'inspection-2.jpg', 'inspection-3.jpg'] })
	}
	try {
		assert.equal(await choosePhoto(['album']), 'inspection-1.jpg')
		assert.deepEqual(receivedSourceType, ['album'])
		assert.equal(receivedCount, 1)
		assert.equal(await choosePhoto(['camera']), 'inspection-1.jpg')
		assert.deepEqual(receivedSourceType, ['camera'])
		assert.deepEqual(await choosePhotos(['album'], 3), ['inspection-1.jpg', 'inspection-2.jpg', 'inspection-3.jpg'])
		assert.equal(receivedCount, 3)
	} finally {
		globalThis.uni.chooseImage = previousChooseImage
	}
})

test('queues photos before pending drafts and writes attachment ids back', () => {
	const envelope = saveDraftEnvelope({ workflow: 'inspection', taskId: 9, taskVersion: 2, pendingSubmit: true, idempotencyKey: 'submit-9', payload: { taskVersion: 2, taskAttachmentIds: [], results: [{ taskItemId: 12, attachmentIds: [] }] } })
	queuePhoto({ id: 'photo-1', workflow: 'inspection', taskId: 9, taskItemId: 12, originalPath: 'a.jpg', watermarkedPath: 'b.jpg' })
	assert.equal(listQueuedPhotos().length, 1)
	assert.equal(attachQueuedPhotoToDraft(listQueuedPhotos()[0], 88), true)
	assert.deepEqual(loadDraftEnvelope('inspection', 9).payload.results[0].attachmentIds, [88])
	queuePhoto({ id: 'task-photo-1', workflow: 'inspection', taskId: 9, taskItemId: null, originalPath: 'c.jpg', watermarkedPath: 'd.jpg' })
	assert.equal(attachQueuedPhotoToDraft(listQueuedPhotos().find((item) => item.id === 'task-photo-1'), 89), true)
	assert.deepEqual(loadDraftEnvelope('inspection', 9).payload.taskAttachmentIds, [89])
	removeDraftEnvelope('inspection', 9)
})

test('builds a GPS-free equipment location watermark', () => {
	const capturedAt = new Date(2026, 7, 4, 11, 49, 21)
	const lines = watermarkLines({ brandName: '客户矿业', equipmentName: '循环泵', equipmentCode: 'P-01', taskCode: 'DJ-1', itemName: '油位', executorName: '操作工01', faultLocationText: '机加二线', capturedAt })
	assert.equal(lines.length, 5)
	assert.match(lines.join(' '), /机加二线/)
	assert.match(lines.at(-1), /^2026-08-04 11:49:21 · 执行人 操作工01$/)
	assert.equal(formatBusinessDateTime(capturedAt), '2026-08-04 11:49:21')
	assert.doesNotMatch(lines.at(-1), /Tue|GMT|CST/)
	assert.doesNotMatch(lines.join(' '), /GPS|经度|纬度/)
})

test('normalizes configurable photo retention and watermark templates', () => {
	assert.deepEqual(
		normalizePhotoPolicy({ watermarkEnabled: false, saveOriginal: false, saveWatermarked: true }),
		{
			allowAlbumSelection: false,
			watermarkEnabled: false,
			saveOriginal: true,
			saveWatermarked: false,
			template: '{brand}\n{equipmentName} ({equipmentCode})\n{taskCode} · {itemName}\n位置/部位 {location}\n{capturedAt} · 执行人 {executor}',
			position: 'BOTTOM',
			backgroundOpacity: 74,
			fontColor: '#ffffff',
			backgroundColor: '#031922'
		}
	)
	const lines = watermarkLines(
		{ brandName: '客户矿业', equipmentCode: 'P-01', executorName: '001', capturedAt: new Date(2026, 7, 5, 9, 8, 7) },
		{ template: '{brand} / {equipmentCode}\n{capturedAt} / {executor}', position: 'TOP' }
	)
	assert.deepEqual(lines, ['客户矿业 / P-01', '2026-08-05 09:08:07 / 001'])
})

test('keeps editable inspection drafts after conflicts and rebases them to the server version', () => {
	const conflict = new ApiError('OPTIMISTIC_LOCK_CONFLICT', '数据已被更新', 409)
	assert.deepEqual(
		inspectionConflictResolution(conflict, { taskStatus: 'IN_PROGRESS' }),
		{ completed: false, preserveDraft: true, rotateIdempotencyKey: true }
	)
	assert.deepEqual(
		rebaseInspectionDraft(
			{ taskVersion: 2, payload: { taskVersion: 2, results: [{ taskItemId: 1, textValue: '保留本地值', version: 4 }] } },
			3,
			[{ id: 1, result: { version: 5 } }]
		),
		{ taskVersion: 3, payload: { taskVersion: 3, results: [{ taskItemId: 1, textValue: '保留本地值', version: 5 }] } }
	)
	assert.equal(
		inspectionConflictResolution(
			new ApiError('INSPECTION_TASK_ALREADY_SUBMITTED', '任务已提交', 409),
			{ taskStatus: 'COMPLETED' }
		).completed,
		true
	)
})

test('enforces minimum Android version codes', () => {
	assert.equal(compareVersionCodes(99, 100).upgradeRequired, true)
	assert.equal(compareVersionCodes('100', 100).upgradeRequired, false)
	assert.equal(compareVersionCodes(101, 100).upgradeRequired, false)
})

test('preserves API conflict semantics', () => {
	const error = new ApiError('TASK_ALREADY_COMPLETED', '任务已经完成', 409)
	assert.equal(errorMessage(error), '任务已经完成')
	assert.equal(isConflict(error), true)
})

test('uses a readable fallback in the HBuilder Android standard base', () => {
	globalThis.plus = { os: { name: 'Android' }, runtime: { appid: 'HBuilder' } }
	secureStorage.secureSet('debug-token', { token: 'fresh-token' })
	assert.match(values.get('leantpm_secure_debug-token'), /^S1:/)
	assert.deepEqual(secureStorage.secureGet('debug-token'), { token: 'fresh-token' })
	storage.setStored(storage.STORAGE_KEYS.rememberedCredentials, { username: 'admin', password: '888888' })
	assert.deepEqual(
		storage.getStored(storage.STORAGE_KEYS.rememberedCredentials),
		{ username: 'admin', password: '888888' }
	)
	delete globalThis.plus
})
