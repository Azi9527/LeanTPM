<template>
	<view class="page" :style="$brandTheme()">
		<view v-if="loading && !detail" class="loading">正在加载点检任务…</view>
		<view v-if="error && !detail" class="error" @click="load">{{ error }} · 点击重试</view>
		<template v-if="detail">
			<view class="task-hero">
				<view class="task-line"><text class="code">{{ detail.task.taskCode }}</text><text class="status">{{ statusLabel(detail.task.taskStatus) }}</text></view>
				<text class="equipment">{{ detail.task.equipmentName }}</text>
				<text class="scheme">{{ detail.task.schemeNameSnapshot }}</text>
				<view class="task-meta"><text>截止 {{ dateTime(detail.task.dueTime) }}</text><text>{{ detail.task.locationName }}</text></view>
				<text v-if="detail.task.assigneeName" class="assignee">主执行：{{ detail.task.assigneeName }}；所有协作人均可提交，任一人提交即完成任务</text>
			</view>

			<view v-if="['COMPLETED', 'PENDING_REVIEW'].includes(detail.task.taskStatus)" class="notice success">任务已完成，当前为只读结果</view>

			<view v-for="(item, index) in detail.items" :key="item.id" class="item-card">
				<view class="item-title"><text class="index">{{ index + 1 }}</text><view><text class="name">{{ item.itemName }} <text v-if="item.requiredFlag" class="required">必填</text></text><text class="content">{{ item.inspectionContent }}</text></view></view>
				<view class="standard"><text class="standard-label">标准：</text>{{ item.inspectionStandard }}<text v-if="hasRange(item)">（{{ item.minimumValue ?? '—' }} ~ {{ item.maximumValue ?? '—' }} {{ item.unit || '' }}）</text></view>
				<view v-if="item.safetyNotes" class="safety">⚠ {{ item.safetyNotes }}</view>

				<template v-if="drafts[item.id]">
					<view v-if="['NORMAL_ABNORMAL', 'PASS_FAIL'].includes(item.resultType)" class="choice-row">
						<view v-for="choice in qualitativeChoices(item.resultType)" :key="choice.value" :class="['choice', { selected: drafts[item.id].resultCode === choice.value, bad: choice.bad && drafts[item.id].resultCode === choice.value }]" @click="selectQualitative(item, choice)">{{ choice.label }}</view>
					</view>
					<view v-else-if="item.resultType === 'NUMBER'" class="number-row">
						<input class="number-input" type="digit" :disabled="!executable" :value="drafts[item.id].numericValue" placeholder="输入测量值" @input="updateNumber(item, $event)" />
						<text>{{ item.unit || '' }}</text>
					</view>
					<textarea v-else-if="item.resultType === 'TEXT'" class="textarea" :disabled="!executable" :value="drafts[item.id].textValue" maxlength="2000" placeholder="输入点检结果" @input="drafts[item.id].textValue = eventValue($event)" />
					<picker v-else-if="item.resultType === 'SINGLE_CHOICE'" :disabled="!executable" :range="options(item)" @change="selectSingle(item, $event)"><view class="picker">{{ drafts[item.id].selectedValue || '请选择结果' }}<text>⌄</text></view></picker>
					<view v-else-if="item.resultType === 'MULTIPLE_CHOICE'" class="multi-row">
						<view v-for="option in options(item)" :key="option" :class="['multi', { selected: drafts[item.id].selectedValues.includes(option) }]" @click="toggleMultiple(item, option)">{{ option }}</view>
					</view>
					<view v-else class="unsupported">该结果类型将在附件功能中处理</view>

					<view v-if="executable" class="flags">
						<view :class="['flag', { active: drafts[item.id].abnormal }]" @click="toggleAbnormal(item)">异常</view>
						<view v-if="item.skipAllowedFlag" :class="['flag', { active: drafts[item.id].skipped }]" @click="toggleSkip(item)">跳过本项</view>
						<view class="flag photo" @click="capturePhoto(item)">{{ uploadingItemId === item.id ? '处理中…' : (item.photoRequiredFlag ? '拍照/相册（必需）' : '拍照/相册') }} {{ drafts[item.id].attachmentIds.length }}/{{ item.photoMaxCount }}</view>
					</view>
					<view v-if="attachmentsForItem(item.id).length" class="attachments">
						<text class="attachments-title">现场附件（{{ attachmentsForItem(item.id).length }}）</text>
						<view v-for="attachment in attachmentsForItem(item.id)" :key="attachment.id" class="attachment" @click="previewAttachment(attachment)">
							<view><text>{{ attachment.originalName }}</text><text>{{ fileSize(attachment.fileSize) }} · {{ attachment.createdTime ? dateTime(attachment.createdTime) : '待同步' }}</text></view><text>{{ String(attachment.id).startsWith('queued:') ? '待上传' : '查看' }}</text>
						</view>
					</view>

					<template v-if="drafts[item.id].abnormal">
						<textarea class="textarea abnormal-text" :disabled="!executable" :value="drafts[item.id].abnormalDescription" maxlength="1000" placeholder="请描述异常现象" @input="drafts[item.id].abnormalDescription = eventValue($event)" />
						<view class="stop-row" @click="toggleStop(item)"><text>设备需要停机</text><switch :color="brandingState.neutralColor" :disabled="!executable" :checked="drafts[item.id].equipmentStopRequired" @change="changeStop(item, $event)" /></view>
						<textarea v-if="Boolean(drafts[item.id].equipmentStopRequired) !== Boolean(item.abnormalDefaultStopFlag)" class="textarea reason" :disabled="!executable" :value="drafts[item.id].stopOverrideReason" maxlength="500" placeholder="与默认停机规则不同，请填写调整原因" @input="drafts[item.id].stopOverrideReason = eventValue($event)" />
					</template>
					<input v-if="drafts[item.id].skipped" class="skip-reason" :disabled="!executable" :value="drafts[item.id].skipReason" placeholder="请填写跳过原因" @input="drafts[item.id].skipReason = eventValue($event)" />
				</template>
			</view>

			<view v-if="executable" class="remark-card">
				<text>执行备注</text><textarea :value="executionRemark" maxlength="1000" placeholder="可填写本次点检补充说明" @input="executionRemark = eventValue($event)" />
			</view>
			<view v-if="executable" class="bottom-space" />
			<view v-if="executable" class="actions">
				<button :loading="saving" class="draft" @click="save(false)">保存草稿</button>
				<button :loading="saving" class="submit" @click="save(true)">提交点检</button>
			</view>
		</template>
		<canvas canvas-id="inspectionWatermark" id="inspectionWatermark" class="watermark-canvas" />
	</view>
</template>

<script setup>
	import { computed, getCurrentInstance, reactive, ref, watch } from 'vue'
	import { onLoad, onPullDownRefresh, onUnload } from '@dcloudio/uni-app'
	import { inspectionApi } from '../../api/inspection.js'
	import { downloadFile } from '../../api/request.js'
	import { connected } from '../../platform/network.js'
	import { choosePhotos, persistTempFile, renderWatermark, watermarkLines } from '../../platform/photo.js'
	import { uploadPhotoEvidence, newPhotoQueueId } from '../../services/photo-evidence.js'
	import { syncPendingWork } from '../../services/offline-sync.js'
	import { brandingState } from '../../stores/branding.js'
	import { mobileState } from '../../stores/mobile.js'
	import { listQueuedPhotos, loadDraftEnvelope, queuePhoto, removeDraftEnvelope, saveDraftEnvelope } from '../../stores/offline.js'
	import { displayName } from '../../stores/session.js'
	import { createIdempotencyKey } from '../../utils/idempotency.js'
	import { errorMessage, isConflict } from '../../utils/errors.js'
	import { applyNumericAbnormalState, buildInspectionPayload, initialResultDraft, resultOptions, validateInspectionResults } from '../../utils/inspection-results.js'

	const taskId = ref(0)
	const detail = ref(null)
	const drafts = reactive({})
	const executionRemark = ref('')
	const loading = ref(false)
	const saving = ref(false)
	const error = ref('')
	const submitKey = ref('')
	const taskAttachments = ref([])
	const localAttachments = ref([])
	const uploadingItemId = ref(0)
	const pendingSubmit = ref(false)
	const localSavedAt = ref('')
	const restoring = ref(false)
	const page = getCurrentInstance()?.proxy
	let autosaveTimer = null
	const executable = computed(() => detail.value && ['PENDING', 'IN_PROGRESS', 'OVERDUE'].includes(detail.value.task.taskStatus))
	const labels = { PENDING: '待执行', IN_PROGRESS: '执行中', PENDING_REVIEW: '已完成', COMPLETED: '已完成', OVERDUE: '已逾期', CANCELLED: '已取消', VOIDED: '已作废' }

	onLoad((query) => { taskId.value = Number(query?.id || 0); submitKey.value = createIdempotencyKey(`inspection-${taskId.value}`); load() })
	onPullDownRefresh(async () => { try { await load() } finally { uni.stopPullDownRefresh() } })
	onUnload(() => { if (autosaveTimer) clearTimeout(autosaveTimer); persistLocal(pendingSubmit.value) })
	watch(drafts, scheduleLocalSave, { deep: true })
	watch(executionRemark, scheduleLocalSave)

	function eventValue(event) { return String(event?.detail?.value ?? '') }
	function statusLabel(value) { return labels[value] || value }
	function dateTime(value) { return value ? String(value).replace('T', ' ').slice(0, 16) : '—' }
	function hasRange(item) { return item.minimumValue !== null && item.minimumValue !== undefined || item.maximumValue !== null && item.maximumValue !== undefined }
	function options(item) { return resultOptions(item) }
	function qualitativeChoices(type) { return type === 'PASS_FAIL' ? [{ label: '合格', value: 'PASS' }, { label: '不合格', value: 'FAIL', bad: true }] : [{ label: '正常', value: 'NORMAL' }, { label: '异常', value: 'ABNORMAL', bad: true }] }

	async function load() {
		if (!taskId.value || loading.value) return
		loading.value = true; error.value = ''
		try {
			restoring.value = true
			const result = await inspectionApi.task(taskId.value)
			detail.value = result
			executionRemark.value = result.task.executionRemark || ''
			for (const key of Object.keys(drafts)) delete drafts[key]
			for (const item of result.items) drafts[item.id] = initialResultDraft(item)
			try { taskAttachments.value = await inspectionApi.taskAttachments(taskId.value) } catch { taskAttachments.value = [] }
			localAttachments.value = listQueuedPhotos().filter((item) => item.workflow === 'inspection' && item.taskId === taskId.value).map((item) => ({ id: `queued:${item.id}`, taskItemId: item.taskItemId, originalName: '离线现场照片.jpg', fileSize: 0, createdTime: item.createdAt, localPath: item.watermarkedPath, contentType: 'image/jpeg' }))
			const local = loadDraftEnvelope('inspection', taskId.value)
			if (local && local.taskVersion === result.task.version) {
				applyLocalPayload(local.payload)
				pendingSubmit.value = Boolean(local.pendingSubmit)
				submitKey.value = local.idempotencyKey || submitKey.value
				localSavedAt.value = local.updatedAt || ''
				uni.showToast({ title: local.pendingSubmit ? '已恢复待提交草稿' : '已恢复本地草稿', icon: 'none' })
			} else if (local) removeDraftEnvelope('inspection', taskId.value)
			if (executable.value) {
				for (const item of result.items) if (item.resultType === 'NUMBER') applyNumericAbnormalState(item, drafts[item.id])
			}
		} catch (cause) { error.value = errorMessage(cause, '点检任务加载失败') }
		finally { restoring.value = false; loading.value = false }
	}

	function applyLocalPayload(payload) {
		if (!payload) return
		executionRemark.value = payload.executionRemark || ''
		for (const result of payload.results || []) if (drafts[result.taskItemId]) Object.assign(drafts[result.taskItemId], result)
	}

	function scheduleLocalSave() {
		if (restoring.value || !executable.value) return
		if (autosaveTimer) clearTimeout(autosaveTimer)
		autosaveTimer = setTimeout(() => persistLocal(pendingSubmit.value), 700)
	}

	function persistLocal(submit) {
		if (!detail.value || !executable.value) return
		const payload = buildInspectionPayload(detail.value.task, detail.value.items, drafts, executionRemark.value)
		payload.results.forEach((result) => {
			result.attachmentIds = (drafts[result.taskItemId]?.attachmentIds || []).slice()
		})
		const saved = saveDraftEnvelope({ workflow: 'inspection', taskId: taskId.value, taskVersion: detail.value.task.version, pendingSubmit: Boolean(submit), idempotencyKey: submitKey.value, payload })
		pendingSubmit.value = Boolean(submit)
		localSavedAt.value = saved.updatedAt
	}

	function selectQualitative(item, choice) {
		if (!executable.value) return
		const draft = drafts[item.id]
		draft.resultCode = choice.value
		draft.abnormal = Boolean(choice.bad)
		draft.equipmentStopRequired = draft.abnormal ? Boolean(item.abnormalDefaultStopFlag) : false
		if (!draft.abnormal) { draft.abnormalDescription = ''; draft.stopOverrideReason = '' }
	}
	function updateNumber(item, event) {
		const draft = drafts[item.id]
		draft.numericValue = eventValue(event)
		applyNumericAbnormalState(item, draft)
	}
	function selectSingle(item, event) { drafts[item.id].selectedValue = options(item)[Number(event.detail.value)] || '' }
	function toggleMultiple(item, option) {
		if (!executable.value) return
		const values = drafts[item.id].selectedValues
		const index = values.indexOf(option)
		if (index >= 0) values.splice(index, 1); else values.push(option)
	}
	function toggleAbnormal(item) {
		if (!executable.value) return
		const draft = drafts[item.id]
		draft.abnormal = !draft.abnormal
		draft.equipmentStopRequired = draft.abnormal ? Boolean(item.abnormalDefaultStopFlag) : false
	}
	function toggleSkip(item) {
		if (!executable.value) return
		drafts[item.id].skipped = !drafts[item.id].skipped
	}
	function changeStop(item, event) { if (executable.value) drafts[item.id].equipmentStopRequired = Boolean(event.detail.value) }
	function toggleStop() { /* switch handles the value; row click is intentionally inert */ }
	function attachmentsForItem(itemId) {
		return taskAttachments.value.filter((item) => item.taskItemId === itemId).concat(localAttachments.value.filter((item) => item.taskItemId === itemId))
	}
	function fileSize(value) {
		const bytes = Number(value || 0)
		return bytes > 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`
	}

	async function capturePhoto(item) {
		if (!executable.value || uploadingItemId.value) return
		const remaining = Number(item.photoMaxCount || 10) - drafts[item.id].attachmentIds.length
		if (remaining <= 0) return uni.showToast({ title: `最多 ${item.photoMaxCount} 张`, icon: 'none' })
		uploadingItemId.value = item.id
		try {
			const sourceType = await choosePhotoSource()
			if (!sourceType) return
			const originalPaths = await choosePhotos([sourceType], sourceType === 'camera' ? 1 : remaining)
			if (!originalPaths.length) return
			let clockSkewWarning = false
			for (const originalPath of originalPaths) {
				const result = await processSelectedPhoto(item, originalPath)
				clockSkewWarning ||= result.clockSkewWarning
			}
			if (!connected.value) persistLocal(false)
			uni.showToast({
				title: !connected.value
					? `${originalPaths.length} 张照片已加入离线队列`
					: clockSkewWarning ? `${originalPaths.length} 张已上传，设备时钟有偏差` : `${originalPaths.length} 张水印照片已上传`,
				icon: 'none'
			})
		} catch (cause) {
			if (!String(cause?.errMsg || cause?.message || '').includes('cancel')) uni.showToast({ title: errorMessage(cause, '图片处理失败'), icon: 'none' })
		} finally { uploadingItemId.value = 0 }
	}

	async function processSelectedPhoto(item, originalPath) {
		const capturedAt = new Date()
		const lines = watermarkLines({ brandName: brandingState.shortName, equipmentName: detail.value.task.equipmentName, equipmentCode: detail.value.task.equipmentCode, taskCode: detail.value.task.taskCode, itemName: item.itemName, executorName: displayName(), faultLocationText: item.inspectionPart || detail.value.task.locationName || item.itemName, capturedAt })
		const watermarkedPath = await renderWatermark({ src: originalPath, canvasId: 'inspectionWatermark', page, lines })
		const serverTime = mobileState.bootstrap?.serverTime || capturedAt.toISOString()
		const serverDate = new Date(serverTime)
		const id = newPhotoQueueId(taskId.value, item.id)
		const record = {
			id, workflow: 'inspection', taskId: taskId.value, taskItemId: item.id,
			originalPath, watermarkedPath,
			metadata: {
				workflowType: 'INSPECTION', taskId: taskId.value, taskItemId: item.id,
				capturedDeviceTime: capturedAt.toISOString(), serverReferenceTime: serverTime,
				deviceClockOffsetSeconds: Number.isNaN(serverDate.getTime()) ? 0 : Math.round((capturedAt.getTime() - serverDate.getTime()) / 1000),
				faultLocationText: item.inspectionPart || detail.value.task.locationName || item.itemName,
				watermarkText: lines.join('\n')
			}
		}
		if (!connected.value) {
			record.originalPath = await persistTempFile(originalPath)
			record.watermarkedPath = await persistTempFile(watermarkedPath)
			queuePhoto(record)
			drafts[item.id].attachmentIds.push(`queued:${id}`)
			localAttachments.value.push({ id: `queued:${id}`, taskItemId: item.id, originalName: `现场照片-${capturedAt.getTime()}.jpg`, fileSize: 0, createdTime: capturedAt.toISOString(), localPath: record.watermarkedPath, contentType: 'image/jpeg' })
			return { clockSkewWarning: false }
		}
		const uploaded = await uploadPhotoEvidence(record)
		drafts[item.id].attachmentIds.push(uploaded.attachmentId)
		localAttachments.value.push({ id: uploaded.attachmentId, taskItemId: item.id, originalName: uploaded.attachment.originalName || `现场照片-${capturedAt.getTime()}.jpg`, fileSize: uploaded.attachment.fileSize, createdTime: uploaded.attachment.createdTime, localPath: watermarkedPath, contentType: 'image/jpeg' })
		return { clockSkewWarning: Boolean(uploaded.evidence?.clockSkewWarning) }
	}

	function choosePhotoSource() {
		return new Promise((resolve) => {
			uni.showActionSheet({
				itemList: ['相机拍照', '从手机相册选择'],
				success: ({ tapIndex }) => resolve(tapIndex === 0 ? 'camera' : 'album'),
				fail: () => resolve('')
			})
		})
	}

	async function previewAttachment(attachment) {
		try {
			if (attachment.localPath) return uni.previewImage({ urls: [attachment.localPath], current: attachment.localPath })
			const path = await downloadFile(`/inspection/tasks/${taskId.value}/attachments/${attachment.id}/content`)
			if (String(attachment.contentType || '').startsWith('image/')) uni.previewImage({ urls: [path], current: path })
			else uni.openDocument({ filePath: path, showMenu: true, fail: () => uni.showToast({ title: '当前设备无法打开该附件', icon: 'none' }) })
		} catch (cause) { uni.showToast({ title: errorMessage(cause, '附件打开失败'), icon: 'none' }) }
	}

	async function save(submit) {
		if (!detail.value || saving.value) return
		if (submit) {
			const validation = validateInspectionResults(detail.value.items, drafts)
			if (validation) return uni.showModal({ title: '无法提交', content: validation, showCancel: false })
		}
		saving.value = true
		try {
			let payload = buildInspectionPayload(detail.value.task, detail.value.items, drafts, executionRemark.value)
			persistLocal(submit)
			if (!connected.value) {
				uni.showToast({ title: submit ? '已离线排队，联网后自动提交' : '草稿已保存在本机', icon: 'none' })
				return
			}
			if (listQueuedPhotos().some((item) => item.workflow === 'inspection' && item.taskId === taskId.value)) {
				await syncPendingWork()
				if (listQueuedPhotos().some((item) => item.workflow === 'inspection' && item.taskId === taskId.value)) {
					uni.showToast({ title: '照片尚未同步，请检查网络后重试', icon: 'none' })
					return
				}
				const updated = loadDraftEnvelope('inspection', taskId.value)
				if (!updated && submit) {
					uni.showToast({ title: '照片及点检结果已同步', icon: 'success' })
					await load()
					return
				}
				if (updated) { applyLocalPayload(updated.payload); payload = buildInspectionPayload(detail.value.task, detail.value.items, drafts, executionRemark.value) }
			}
			if (submit) await inspectionApi.submitTask(taskId.value, payload, submitKey.value)
			else await inspectionApi.saveDraft(taskId.value, payload)
			uni.showToast({ title: submit ? '点检任务已完成' : '草稿已保存', icon: 'success' })
			if (submit) submitKey.value = createIdempotencyKey(`inspection-${taskId.value}`)
			removeDraftEnvelope('inspection', taskId.value)
			pendingSubmit.value = false
			localSavedAt.value = ''
			await load()
		} catch (cause) {
			if (isConflict(cause)) {
				uni.showModal({ title: '任务状态已变化', content: '其他执行人员可能已完成该任务，将刷新为最新结果。', showCancel: false })
				await load()
			} else uni.showModal({ title: submit ? '提交失败' : '保存失败', content: `${errorMessage(cause)}；本地草稿仍保留`, showCancel: false })
		} finally { saving.value = false }
	}
</script>

<style>
	.page { min-height: 100vh; padding: 23rpx 24rpx 50rpx; background: #f4f7f5; }
	.loading, .error { padding: 80rpx 24rpx; color: #839089; text-align: center; font-size: 25rpx; }
	.error { color: #a00008; }
	.task-hero { padding: 32rpx; border-radius: 26rpx; color: #fff; background: linear-gradient(140deg, #193f31, var(--brand-primary, #1c7d50)); }
	.task-line, .task-meta, .stop-row { display: flex; align-items: center; justify-content: space-between; gap: 18rpx; }
	.code { font-family: monospace; font-size: 22rpx; opacity: .75; }
	.status { padding: 8rpx 16rpx; border-radius: 20rpx; background: rgba(255,255,255,.18); font-size: 21rpx; }
	.equipment, .scheme, .assignee { display: block; }
	.equipment { margin-top: 17rpx; font-size: 37rpx; font-weight: 800; }
	.scheme { margin-top: 7rpx; font-size: 23rpx; opacity: .77; }
	.task-meta { margin-top: 23rpx; font-size: 21rpx; opacity: .72; }
	.assignee { margin-top: 19rpx; padding-top: 18rpx; border-top: 1rpx solid rgba(255,255,255,.16); font-size: 21rpx; line-height: 1.5; opacity: .78; }
	.notice { margin-top: 18rpx; padding: 22rpx; border-radius: 17rpx; font-size: 24rpx; }
	.notice.warning { color: #8d5d06; background: #fff4df; }
	.notice.success { color: #126e43; background: #e7f6ee; }
	.item-card { margin-top: 19rpx; padding: 28rpx; border-radius: 23rpx; background: #fff; box-shadow: 0 10rpx 32rpx rgba(25,53,42,.06); }
	.item-title { display: flex; gap: 17rpx; }
	.index { display: flex; width: 50rpx; height: 50rpx; align-items: center; justify-content: center; flex: 0 0 auto; border-radius: 50%; color: #fff; background: var(--brand-primary, #1c7d50); font-size: 24rpx; }
	.name, .content { display: block; }
	.name { color: #203d31; font-size: 29rpx; font-weight: 750; }
	.required { color: var(--brand-primary, #1c7d50); font-size: 19rpx; }
	.content { margin-top: 8rpx; color: #6e7b75; font-size: 23rpx; line-height: 1.5; }
	.standard { margin-top: 22rpx; padding: 20rpx; border-radius: 15rpx; color: #4e5d56; background: #f3f6f4; font-size: 23rpx; line-height: 1.55; }
	.standard-label { color: #263f35; font-weight: 700; }
	.safety { margin-top: 15rpx; padding: 17rpx; border-radius: 14rpx; color: #9a680d; background: #fff5e3; font-size: 22rpx; }
	.choice-row { display: grid; grid-template-columns: 1fr 1fr; gap: 15rpx; margin-top: 23rpx; }
	.choice { padding: 21rpx; border: 2rpx solid #dce5e0; border-radius: 15rpx; color: #68756f; text-align: center; font-size: 26rpx; }
	.choice.selected { border-color: var(--brand-primary, #1c7d50); color: #166f47; background: #e9f6ef; }
	.choice.selected.bad { border-color: var(--brand-accent, #c4000a); color: #a00008; background: #fff0f0; }
	.number-row { display: flex; align-items: center; gap: 16rpx; margin-top: 23rpx; }
	.number-input, .skip-reason { box-sizing: border-box; height: 82rpx; padding: 0 20rpx; border: 2rpx solid #dce5e0; border-radius: 15rpx; font-size: 26rpx; }
	.number-input { flex: 1; }
	.textarea { box-sizing: border-box; width: 100%; height: 135rpx; margin-top: 22rpx; padding: 18rpx; border: 2rpx solid #dce5e0; border-radius: 15rpx; font-size: 24rpx; }
	.picker { display: flex; height: 82rpx; align-items: center; justify-content: space-between; margin-top: 22rpx; padding: 0 20rpx; border: 2rpx solid #dce5e0; border-radius: 15rpx; font-size: 25rpx; }
	.multi-row, .flags { display: flex; flex-wrap: wrap; gap: 12rpx; margin-top: 22rpx; }
	.multi, .flag { padding: 13rpx 18rpx; border: 2rpx solid #dce5e0; border-radius: 13rpx; color: #68756f; font-size: 22rpx; }
	.multi.selected, .flag.active { border-color: var(--brand-primary, #1c7d50); color: #166e46; background: #e9f6ef; }
	.flag.photo { margin-left: auto; color: var(--brand-primary, #1c7d50); }
	.attachments { margin-top: 18rpx; padding: 17rpx; border-radius: 14rpx; background: #f4f7f5; }
	.attachments-title { display: block; margin-bottom: 10rpx; color: #506158; font-size: 23rpx; font-weight: 700; }
	.attachment { display: flex; align-items: center; justify-content: space-between; gap: 14rpx; padding: 15rpx 0; border-top: 1rpx solid #e3e9e6; color: var(--brand-primary, #1c7d50); font-size: 22rpx; }
	.attachment view text { display: block; color: #45574e; }
	.attachment view text:last-child { margin-top: 5rpx; color: #8c9691; font-size: 19rpx; }
	.abnormal-text { border-color: #efb2b5; background: #fffafa; }
	.stop-row { margin-top: 16rpx; padding: 19rpx; border-radius: 15rpx; color: #68494a; background: #fff2f2; font-size: 24rpx; }
	.reason { height: 110rpx; }
	.skip-reason { width: 100%; margin-top: 16rpx; }
	.unsupported { margin-top: 20rpx; color: #8b9690; }
	.remark-card { margin-top: 20rpx; padding: 26rpx; border-radius: 23rpx; background: #fff; }
	.remark-card > text { color: #273f35; font-size: 27rpx; font-weight: 700; }
	.remark-card textarea { box-sizing: border-box; width: 100%; height: 120rpx; margin-top: 15rpx; padding: 15rpx; border: 2rpx solid #dce5e0; border-radius: 14rpx; font-size: 24rpx; }
	.bottom-space { height: calc(125rpx + env(safe-area-inset-bottom)); }
	.actions { position: fixed; z-index: 20; right: 0; bottom: 0; left: 0; display: grid; grid-template-columns: 1fr 1.4fr; gap: 16rpx; padding: 18rpx 24rpx calc(18rpx + env(safe-area-inset-bottom)); border-top: 1rpx solid #e3e9e6; background: rgba(255,255,255,.97); }
	.actions button { margin: 0; border-radius: 16rpx; font-size: 27rpx; }
	.draft { color: var(--brand-primary, #1c7d50); background: #e9f5ef; }
	.submit { color: #fff; background: var(--brand-primary, #1c7d50); }
	.watermark-canvas { position: fixed; z-index: -10; left: -3000px; bottom: -3000px; width: 1600px; height: 2000px; opacity: 0; }
</style>
