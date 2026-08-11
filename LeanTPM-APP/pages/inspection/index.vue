<template>
	<view class="page" :style="$brandTheme()">
		<scroll-view scroll-x class="tabs">
			<view v-for="item in tabs" :key="item.value" :class="['tab', { active: activeStatus === item.value }]" @click="selectStatus(item.value)">{{ item.label }}</view>
		</scroll-view>
		<view v-if="error" class="error" @click="load">{{ error }} · 点击重试</view>
		<view v-for="task in rows" :key="task.id" class="task-card" @click="openTask(task)">
			<view class="task-head"><text class="code">{{ task.taskCode }}</text><text :class="['status', task.taskStatus.toLowerCase()]">{{ statusLabel(task.taskStatus) }}</text></view>
			<text class="equipment">{{ task.equipmentName }}</text>
			<text class="scheme">{{ task.schemeNameSnapshot }}</text>
			<view class="meta"><text>{{ task.locationName || '未设置位置' }}</text><text>截止 {{ dateTime(task.dueTime) }}</text></view>
			<view v-if="isCompleted(task)" class="completion-overview">
				<view><text class="overview-label">完成时间</text><text>{{ dateTime(task.completedTime || task.submittedTime) }}</text></view>
				<view><text class="overview-label">完成人</text><text>{{ task.completedByName || '—' }}</text></view>
				<view><text class="overview-label">异常概况</text><text :class="task.abnormalItemCount > 0 ? 'abnormal' : 'normal'">{{ task.abnormalItemCount > 0 ? `${task.abnormalItemCount} 个异常项` : '无异常项' }}</text></view>
			</view>
			<view class="progress"><view :style="{ width: `${progress(task)}%` }" /></view>
			<view class="progress-text"><text>{{ task.completedItemCount }}/{{ task.itemCount }} 项</text><text v-if="task.assigneeName">主执行：{{ task.assigneeName }}</text></view>
		</view>
		<view v-if="!loading && !rows.length" class="empty">当前没有点检任务</view>
		<AppBottomNav active="inspection" />
	</view>
</template>

<script setup>
	import { ref } from 'vue'
	import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
	import { inspectionApi } from '../../api/inspection.js'
	import { ROUTES, navigateTo, routeWithQuery } from '../../constants/routes.js'
	import { errorMessage } from '../../utils/errors.js'
	import { inspectionTaskTarget } from '../../utils/inspection-navigation.js'
	import { inspectionTaskListQuery } from '../../utils/inspection-todos.js'
	import AppBottomNav from '../../components/AppBottomNav.vue'

	const tabs = [
		{ label: '全部', value: '' }, { label: '待执行', value: 'PENDING' },
		{ label: '执行中', value: 'IN_PROGRESS' }, { label: '已逾期', value: 'OVERDUE' },
		{ label: '已完成', value: 'COMPLETED' }
	]
	const labels = { PENDING: '待执行', IN_PROGRESS: '执行中', PENDING_REVIEW: '已完成', COMPLETED: '已完成', OVERDUE: '已逾期', CANCELLED: '已取消', VOIDED: '已作废' }
	const activeStatus = ref('PENDING')
	const rows = ref([])
	const loading = ref(false)
	const error = ref('')
	let initialTaskId = 0

	onLoad((query) => { initialTaskId = Number(query?.taskId || 0); load() })
	onShow(() => { if (rows.value.length) load() })
	onPullDownRefresh(async () => { try { await load() } finally { uni.stopPullDownRefresh() } })

	function statusLabel(value) { return labels[value] || value }
	function dateTime(value) { return value ? String(value).replace('T', ' ').slice(0, 16) : '—' }
	function isCompleted(task) { return ['COMPLETED', 'PENDING_REVIEW'].includes(task?.taskStatus) }
	function progress(task) { return task.itemCount ? Math.min(100, Math.round(task.completedItemCount * 100 / task.itemCount)) : 0 }
	async function selectStatus(value) { activeStatus.value = value; await load() }

	async function load() {
		if (loading.value) return
		loading.value = true; error.value = ''
		try {
			const result = await inspectionApi.tasks({
				...inspectionTaskListQuery(activeStatus.value),
				mineOnly: true,
				page: 1,
				pageSize: 100
			})
			rows.value = result?.records || []
			if (initialTaskId > 0) {
				const id = initialTaskId
				initialTaskId = 0
				const task = rows.value.find((row) => Number(row.id) === id)
				if (task) openTask(task)
				else openTask((await inspectionApi.task(id)).task)
			}
		} catch (cause) { error.value = errorMessage(cause, '点检任务加载失败') }
		finally { loading.value = false }
	}

	function openTask(task) { navigateTo(inspectionTaskTarget(task).url) }
</script>

<style>
	.page { min-height: 100vh; padding: 22rpx 25rpx 60rpx; background: #f4f7f5; }
	.tabs { width: calc(100% + 50rpx); margin: 0 -25rpx 22rpx; white-space: nowrap; }
	.tab { display: inline-flex; height: 62rpx; align-items: center; margin-left: 17rpx; padding: 0 25rpx; border-radius: 31rpx; color: #6c7872; background: #fff; font-size: 24rpx; }
	.tab.active { color: #fff; background: var(--brand-primary, #1c7d50); }
	.task-card { margin-bottom: 18rpx; padding: 28rpx; border-radius: 23rpx; background: #fff; box-shadow: 0 10rpx 32rpx rgba(25,53,42,.06); }
	.task-head, .meta, .progress-text { display: flex; justify-content: space-between; gap: 18rpx; }
	.code { color: #718079; font-family: monospace; font-size: 22rpx; }
	.status { padding: 7rpx 15rpx; border-radius: 20rpx; color: #6d7772; background: #eef1f0; font-size: 21rpx; }
	.status.pending, .status.in_progress { color: #176f47; background: #e6f5ed; }
	.status.overdue { color: #a00008; background: #ffeded; }
	.status.pending_review, .status.completed { color: #176f47; background: #e6f5ed; }
	.equipment, .scheme { display: block; }
	.equipment { margin-top: 20rpx; color: #213e32; font-size: 31rpx; font-weight: 800; }
	.scheme { margin-top: 7rpx; color: #75827c; font-size: 24rpx; }
	.meta { margin-top: 22rpx; color: #89938e; font-size: 21rpx; }
	.completion-overview { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12rpx; margin-top: 22rpx; padding: 18rpx; border-radius: 16rpx; background: #f4f7f5; color: #315044; font-size: 21rpx; }
	.completion-overview view { display: flex; min-width: 0; flex-direction: column; gap: 7rpx; }
	.overview-label { color: #89938e; font-size: 19rpx; }
	.abnormal { color: #b4232d; font-weight: 700; }
	.normal { color: #1c7d50; font-weight: 700; }
	.progress { overflow: hidden; height: 10rpx; margin-top: 22rpx; border-radius: 5rpx; background: #edf1ef; }
	.progress view { height: 100%; border-radius: 5rpx; background: var(--brand-primary, #1c7d50); }
	.progress-text { margin-top: 10rpx; color: #8c9691; font-size: 20rpx; }
	.error, .empty { padding: 55rpx 20rpx; color: #8a948f; text-align: center; font-size: 25rpx; }
	.error { margin-bottom: 18rpx; border-radius: 18rpx; color: #a00008; background: #fff0f0; }
</style>
