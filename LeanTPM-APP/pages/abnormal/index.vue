<template>
	<view class="page" :style="$brandTheme()">
		<scroll-view scroll-x class="tabs"><view v-for="item in tabs" :key="item.value" :class="['tab', { active: status === item.value }]" @click="selectStatus(item.value)">{{ item.label }}</view></scroll-view>
		<view v-if="error" class="error" @click="load">{{ error }} · 点击重试</view>
		<view v-for="row in rows" :key="row.id" class="card" @click="open(row)">
			<view class="head"><text class="code">{{ row.abnormalCode }}</text><text :class="['severity', row.severity.toLowerCase()]">{{ severityName(row.severity) }}</text></view>
			<text class="title">{{ row.abnormalTitle }}</text><text class="equipment">{{ row.equipmentName }} · {{ row.itemName || '任务级异常' }}</text>
			<text class="description">{{ row.abnormalDescription }}</text>
			<view class="foot"><text>{{ statusName(row.abnormalStatus) }}</text><text>{{ dateTime(row.createdTime) }}</text></view>
		</view>
		<view v-if="!loading && !rows.length" class="empty">当前没有点检异常</view>

		<view v-if="selected" class="mask" @click.self="selected = null">
			<scroll-view scroll-y class="sheet">
				<view class="sheet-head"><view><text>{{ selected.abnormalCode }}</text><text>{{ selected.abnormalTitle }}</text></view><text @click="selected = null">×</text></view>
				<view class="detail"><text>设备</text><text>{{ selected.equipmentName }}（{{ selected.equipmentCode }}）</text></view>
				<view class="detail"><text>点检项目</text><text>{{ selected.itemName || '任务级异常' }}</text></view>
				<view class="detail"><text>异常说明</text><text>{{ selected.abnormalDescription }}</text></view>
				<view class="detail"><text>停机联动</text><text>{{ selected.equipmentStopRequired ? (selected.equipmentStatusChanged ? '已联动停机' : '要求停机') : '无需停机' }}</text></view>
				<text class="attachment-title">相关附件（{{ attachments.length }}）</text>
				<view v-for="file in attachments" :key="file.id" class="attachment" @click="preview(file)"><view><text>{{ file.originalName }}</text><text>{{ file.contentType || file.extension }}</text></view><text>查看</text></view>
				<view v-if="!attachmentsLoading && !attachments.length" class="empty small">暂无相关附件</view>
				<view class="safe-space" />
			</scroll-view>
		</view>
	</view>
</template>

<script setup>
	import { ref } from 'vue'
	import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app'
	import { inspectionApi } from '../../api/inspection.js'
	import { downloadFile } from '../../api/request.js'
	import { errorMessage } from '../../utils/errors.js'

	const tabs = [{ label: '全部', value: '' }, { label: '待处理', value: 'OPEN' }, { label: '处理中', value: 'PROCESSING' }, { label: '待验证', value: 'PENDING_VERIFY' }, { label: '已关闭', value: 'CLOSED' }]
	const status = ref('')
	const rows = ref([])
	const selected = ref(null)
	const attachments = ref([])
	const loading = ref(false)
	const attachmentsLoading = ref(false)
	const error = ref('')

	onLoad(load)
	onPullDownRefresh(async () => { try { await load() } finally { uni.stopPullDownRefresh() } })
	function dateTime(value) { return value ? String(value).replace('T', ' ').slice(0, 16) : '—' }
	function severityName(value) { return ({ LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '严重' })[value] || value }
	function statusName(value) { return ({ OPEN: '待处理', PROCESSING: '处理中', PENDING_VERIFY: '待验证', CLOSED: '已关闭' })[value] || value }
	async function selectStatus(value) { status.value = value; await load() }
	async function load() {
		if (loading.value) return
		loading.value = true; error.value = ''
		try { rows.value = (await inspectionApi.abnormalities({ abnormalStatus: status.value || undefined, page: 1, pageSize: 100 }))?.records || [] }
		catch (cause) { error.value = errorMessage(cause, '异常清单加载失败') }
		finally { loading.value = false }
	}
	async function open(row) {
		selected.value = row; attachments.value = []; attachmentsLoading.value = true
		try { attachments.value = await inspectionApi.abnormalAttachments(row.id) }
		catch (cause) { uni.showToast({ title: errorMessage(cause, '附件加载失败'), icon: 'none' }) }
		finally { attachmentsLoading.value = false }
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
	.head, .foot, .sheet-head, .detail, .attachment { display: flex; justify-content: space-between; gap: 18rpx; }
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
	.attachment-title { display: block; margin: 27rpx 0 10rpx; color: #2d473b; font-size: 27rpx; font-weight: 750; }
	.attachment { align-items: center; padding: 19rpx 0; border-bottom: 1rpx solid #e9eeeb; color: var(--brand-primary, #1c7d50); font-size: 22rpx; }
	.attachment view text { display: block; color: #44564d; }
	.attachment view text:last-child { margin-top: 5rpx; color: #8b9590; font-size: 19rpx; }
	.empty.small { padding: 28rpx; }
	.safe-space { height: calc(30rpx + env(safe-area-inset-bottom)); }
</style>
