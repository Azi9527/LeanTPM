<template>
	<view class="page" :style="$brandTheme()">
		<scroll-view scroll-x class="tabs"><view v-for="item in tabs" :key="item.value" :class="['tab', { active: status === item.value }]" @click="selectStatus(item.value)">{{ item.label }}</view></scroll-view>
		<view v-if="error" class="error" @click="load">{{ error }} · 点击重试</view>
		<view v-for="row in rows" :key="row.id" class="card" @click="open(row)">
			<view class="head"><text class="code">{{ row.abnormalCode }}</text><text :class="['severity', row.severity.toLowerCase()]">{{ severityName(row.severity) }}</text></view>
			<text class="title">{{ row.abnormalTitle }}</text><text class="equipment">{{ row.equipmentCode }} · {{ row.equipmentName }} · {{ row.itemName || '任务级异常' }}</text>
			<text class="description">{{ row.abnormalDescription }}</text>
			<view class="foot"><text>{{ statusName(row.abnormalStatus) }}</text><text>{{ dateTime(row.createdTime) }}</text></view>
		</view>
		<view v-if="!loading && !rows.length" class="empty">当前没有点检异常</view>

		<view v-if="selected" class="mask" @click.self="closeDetail">
			<scroll-view scroll-y class="sheet">
				<view class="sheet-head"><view><text>{{ selected.abnormalCode }}</text><text>{{ selected.abnormalTitle }}</text></view><text @click="closeDetail">×</text></view>
				<view class="detail"><text>设备</text><text>{{ selected.equipmentName }}（{{ selected.equipmentCode }}）</text></view>
				<view class="detail"><text>点检项目</text><text>{{ selected.itemName || '任务级异常' }}</text></view>
				<view class="detail"><text>异常说明</text><text>{{ selected.abnormalDescription }}</text></view>
				<view class="detail"><text>停机联动</text><text>{{ selected.equipmentStopRequired ? (selected.equipmentStatusChanged ? '已联动停机' : '要求停机') : '无需停机' }}</text></view>
				<view class="handling-head">
					<view><text>异常处理登记</text><text>保存后状态自动更新为已处理</text></view>
				</view>
				<view v-if="editing" class="handling-form">
					<label><text>原因分析</text><textarea v-model="form.causeAnalysis" maxlength="2000" placeholder="填写异常发生的原因" /></label>
					<label><text>临时措施</text><textarea v-model="form.temporaryAction" maxlength="2000" placeholder="填写当前采取的临时措施" /></label>
					<label><text>恒久对策</text><textarea v-model="form.permanentCountermeasure" maxlength="2000" placeholder="填写防止问题再次发生的长期对策" /></label>
					<view class="handling-actions"><button :disabled="saving" @click="cancelEdit">取消</button><button :loading="saving" class="save-button" @click="saveHandling">保存登记</button></view>
				</view>
				<view v-else class="handling-view">
					<view><text>原因分析</text><text>{{ selected.causeAnalysis || '尚未登记' }}</text></view>
					<view><text>临时措施</text><text>{{ selected.temporaryAction || '尚未登记' }}</text></view>
					<view><text>恒久对策</text><text>{{ selected.permanentCountermeasure || '尚未登记' }}</text></view>
				</view>
				<text class="attachment-title">相关附件（{{ attachments.length }}）</text>
				<view v-for="file in attachments" :key="file.id" class="attachment" @click="preview(file)"><view><text>{{ file.originalName }}</text><text>{{ file.contentType || file.extension }}</text></view><text>查看</text></view>
				<view v-if="!attachmentsLoading && !attachments.length" class="empty small">暂无相关附件</view>
				<button v-if="can('inspection:abnormal:handle') && selected.abnormalStatus !== 'CLOSED' && !editing" class="primary-handle-button" @click="beginEdit">{{ hasHandlingInfo(selected) ? '修改异常处置' : '登记异常处置' }}</button>
				<view class="safe-space" />
			</scroll-view>
		</view>
	</view>
</template>

<script setup>
	import { reactive, ref } from 'vue'
	import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app'
	import { inspectionApi } from '../../api/inspection.js'
	import { downloadFile } from '../../api/request.js'
	import { can } from '../../stores/session.js'
	import { abnormalHandlingErrorMessage } from '../../utils/abnormal-handling.js'
	import { errorMessage } from '../../utils/errors.js'

	const tabs = [{ label: '全部', value: '' }, { label: '待处理', value: 'OPEN' }, { label: '已处理', value: 'PROCESSING' }, { label: '待验证', value: 'PENDING_VERIFY' }, { label: '已关闭', value: 'CLOSED' }]
	const status = ref('')
	const rows = ref([])
	const selected = ref(null)
	const attachments = ref([])
	const loading = ref(false)
	const attachmentsLoading = ref(false)
	const editing = ref(false)
	const saving = ref(false)
	const error = ref('')
	const form = reactive({ causeAnalysis: '', temporaryAction: '', permanentCountermeasure: '' })

	onLoad(load)
	onPullDownRefresh(async () => { try { await load() } finally { uni.stopPullDownRefresh() } })
	function dateTime(value) { return value ? String(value).replace('T', ' ').slice(0, 16) : '—' }
	function severityName(value) { return ({ LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '严重' })[value] || value }
	function statusName(value) { return ({ OPEN: '待处理', PROCESSING: '已处理', PENDING_VERIFY: '待验证', CLOSED: '已关闭' })[value] || value }
	async function selectStatus(value) { status.value = value; await load() }
	async function load() {
		if (loading.value) return
		loading.value = true; error.value = ''
		try { rows.value = (await inspectionApi.abnormalities({ abnormalStatus: status.value || undefined, page: 1, pageSize: 100 }))?.records || [] }
		catch (cause) { error.value = errorMessage(cause, '异常清单加载失败') }
		finally { loading.value = false }
	}
	async function open(row) {
		selected.value = row; editing.value = false; attachments.value = []; attachmentsLoading.value = true
		try { attachments.value = await inspectionApi.abnormalAttachments(row.id) }
		catch (cause) { uni.showToast({ title: errorMessage(cause, '附件加载失败'), icon: 'none' }) }
		finally { attachmentsLoading.value = false }
	}
	function closeDetail() {
		if (saving.value) return
		selected.value = null
		editing.value = false
	}
	function hasHandlingInfo(row) {
		return Boolean(row?.causeAnalysis || row?.temporaryAction || row?.permanentCountermeasure)
	}
	function beginEdit() {
		if (!selected.value || !can('inspection:abnormal:handle') || selected.value.abnormalStatus === 'CLOSED') return
		Object.assign(form, {
			causeAnalysis: selected.value.causeAnalysis || '',
			temporaryAction: selected.value.temporaryAction || '',
			permanentCountermeasure: selected.value.permanentCountermeasure || ''
		})
		editing.value = true
	}
	function cancelEdit() {
		if (!saving.value) editing.value = false
	}
	async function saveHandling() {
		if (!selected.value || saving.value) return
		const original = selected.value
		const measures = {
			causeAnalysis: form.causeAnalysis.trim(),
			temporaryAction: form.temporaryAction.trim(),
			permanentCountermeasure: form.permanentCountermeasure.trim()
		}
		saving.value = true
		try {
			await inspectionApi.recordAbnormalMeasures(selected.value.id, {
				...measures,
				version: selected.value.version
			})
			const saved = { ...original, ...measures, abnormalStatus: 'PROCESSING', version: Number(original.version || 0) + 1 }
			selected.value = saved
			rows.value = rows.value.map((row) => row.id === saved.id ? saved : row)
			editing.value = false
			uni.showToast({ title: '异常处理登记已保存', icon: 'success' })
			await load()
			selected.value = rows.value.find((row) => row.id === saved.id) || saved
		} catch (cause) {
			uni.showModal({ title: '保存失败', content: abnormalHandlingErrorMessage(cause), showCancel: false })
		} finally { saving.value = false }
	}
	async function preview(file) {
		try {
			const path = await downloadFile(`/inspection/abnormalities/${selected.value.id}/attachments/${file.id}/content`)
			if (String(file.contentType || '').startsWith('image/')) uni.previewImage({ urls: [path], current: path })
			else uni.openDocument({ filePath: path, showMenu: true, fail: () => uni.showToast({ title: '当前设备无法打开该附件', icon: 'none' }) })
		} catch (cause) { uni.showToast({ title: errorMessage(cause, '附件打开失败'), icon: 'none' }) }
	}
</script>

<style>
	.page { min-height: 100vh; padding: 22rpx 25rpx 60rpx; background: #f4f7f5; }
	.tabs { width: calc(100% + 50rpx); margin: 0 -25rpx 22rpx; white-space: nowrap; }
	.tab { display: inline-flex; height: 62rpx; align-items: center; margin-left: 17rpx; padding: 0 25rpx; border-radius: 31rpx; color: #68756f; background: #fff; font-size: 24rpx; }
	.tab.active { color: #fff; background: var(--brand-primary, #1c7d50); }
	.card { margin-bottom: 18rpx; padding: 27rpx; border-radius: 22rpx; background: #fff; box-shadow: 0 10rpx 32rpx rgba(25,53,42,.06); }
	.head, .foot, .sheet-head, .detail, .attachment, .handling-head { display: flex; justify-content: space-between; gap: 18rpx; }
	.code { color: #77847e; font-family: monospace; font-size: 21rpx; }
	.severity { padding: 7rpx 14rpx; border-radius: 18rpx; color: #78610f; background: #fff4df; font-size: 20rpx; }
	.severity.high, .severity.critical { color: #a00008; background: #ffeded; }
	.title, .equipment, .description { display: block; }
	.title { margin-top: 17rpx; color: #233f33; font-size: 29rpx; font-weight: 750; }
	.equipment { margin-top: 7rpx; color: #74817b; font-size: 22rpx; }
	.description { margin-top: 18rpx; color: #56635d; font-size: 24rpx; line-height: 1.5; }
	.foot { margin-top: 20rpx; color: #8d9692; font-size: 20rpx; }
	.error, .empty { padding: 55rpx 20rpx; color: #8b9690; text-align: center; font-size: 24rpx; }
	.error { color: #a00008; }
	.mask { position: fixed; z-index: 80; top: 0; right: 0; bottom: 0; left: 0; display: flex; align-items: flex-end; background: rgba(10,28,20,.46); }
	.sheet { box-sizing: border-box; width: 100%; max-height: 88vh; padding: 32rpx 29rpx 0; border-radius: 32rpx 32rpx 0 0; background: #fff; }
	.sheet-head { align-items: flex-start; color: #234033; }
	.sheet-head view text { display: block; }
	.sheet-head view text:first-child { color: #7c8882; font-family: monospace; font-size: 21rpx; }
	.sheet-head view text:last-child { margin-top: 7rpx; font-size: 32rpx; font-weight: 800; }
	.sheet-head > text { font-size: 42rpx; }
	.detail { padding: 20rpx 0; border-bottom: 1rpx solid #e9eeeb; font-size: 23rpx; }
	.detail text:first-child { flex: 0 0 130rpx; color: #8b9590; }
	.detail text:last-child { color: #43564c; text-align: right; }
	.handling-head { align-items: center; margin-top: 28rpx; }
	.handling-head view text { display: block; }
	.handling-head view text:first-child { color: #2d473b; font-size: 27rpx; font-weight: 750; }
	.handling-head view text:last-child { margin-top: 5rpx; color: #8b9590; font-size: 19rpx; }
	.handling-view { margin-top: 14rpx; padding: 0 20rpx; border-radius: 18rpx; background: #f6f9f7; }
	.handling-view view { padding: 20rpx 0; border-bottom: 1rpx solid #e4ebe7; }
	.handling-view view:last-child { border-bottom: 0; }
	.handling-view text { display: block; }
	.handling-view text:first-child { color: #809087; font-size: 21rpx; }
	.handling-view text:last-child { margin-top: 8rpx; color: #40554a; font-size: 23rpx; line-height: 1.55; white-space: pre-wrap; }
	.handling-form { margin-top: 14rpx; }
	.handling-form label { display: block; margin-top: 18rpx; color: #40554a; font-size: 22rpx; }
	.handling-form textarea { box-sizing: border-box; width: 100%; height: 150rpx; margin-top: 10rpx; padding: 17rpx; border: 2rpx solid #dce6e1; border-radius: 15rpx; background: #fff; font-size: 23rpx; line-height: 1.5; }
	.handling-actions { display: grid; grid-template-columns: 1fr 1.4fr; gap: 16rpx; margin-top: 20rpx; }
	.handling-actions button { margin: 0; border-radius: 15rpx; font-size: 24rpx; }
	.save-button { color: #fff; background: var(--brand-primary, #1c7d50); }
	.attachment-title { display: block; margin: 27rpx 0 10rpx; color: #2d473b; font-size: 27rpx; font-weight: 750; }
	.attachment { align-items: center; padding: 19rpx 0; border-bottom: 1rpx solid #e9eeeb; color: var(--brand-primary, #1c7d50); font-size: 22rpx; }
	.attachment view text { display: block; color: #44564d; }
	.attachment view text:last-child { margin-top: 5rpx; color: #8b9590; font-size: 19rpx; }
	.empty.small { padding: 28rpx; }
	.primary-handle-button { width: 100%; height: 84rpx; margin: 26rpx 0 0; border: 0; border-radius: 18rpx; color: #fff; background: var(--brand-primary, #1c7d50); box-shadow: 0 10rpx 24rpx rgba(28,125,80,.24); font-size: 28rpx; font-weight: 750; line-height: 84rpx; }
	.safe-space { height: calc(30rpx + env(safe-area-inset-bottom)); }
</style>
