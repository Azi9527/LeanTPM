<template>
	<view class="page" :style="$brandTheme()">
		<view class="hero">
			<view class="hero-top">
				<view>
					<text class="welcome">您好，{{ displayName() }}</text>
					<text class="date">{{ todayText }}</text>
				</view>
				<view class="avatar" @click="openProfile">{{ avatarText }}</view>
			</view>
			<view class="network-row">
				<view :class="['network-dot', { offline: !connected }]" />
				<text>{{ connected ? `网络已连接 · ${networkType}` : '当前离线，操作将保存为草稿' }}</text>
			</view>
		</view>

		<view v-if="mobileState.error" class="error-card" @click="load">{{ mobileState.error }} · 点击重试</view>

		<view class="scan-entry" @click="openScan">
			<view class="scan-symbol">扫</view>
			<view><text class="scan-title">扫码点检登记</text><text class="scan-subtitle">扫描设备二维码，直接进入设备并开始今日点检</text></view>
			<text class="scan-arrow">›</text>
		</view>

		<view v-if="pendingOfflineWork" class="sync-banner" @click="openProfile">
			<view><text>有 {{ pendingOfflineWork }} 项现场数据待同步</text><text>网络恢复后可在“我的”中立即同步</text></view><text>去同步 ›</text>
		</view>

		<view v-if="recentEquipment.length" class="section recent-section">
			<view class="section-header"><text>最近扫码设备</text><text class="section-link" @click="openScan">继续扫码</text></view>
			<view class="recent-card">
				<view v-for="item in recentEquipment.slice(0, 3)" :key="item.equipmentId" class="recent-row" @click="openRecentEquipment(item)">
					<view><text>{{ item.equipmentName }}</text><text>{{ item.equipmentCode }} · {{ item.organizationName || '未设置组织' }}</text></view>
					<text>{{ item.statusName || '查看' }} ›</text>
				</view>
			</view>
		</view>

		<view class="section">
			<view class="section-header" @click="openEquipmentStatus('')"><text>设备状态</text><text class="section-link">查看明细</text></view>
			<view class="equipment-grid">
				<view class="metric equipment-total" @click="openEquipmentStatus('IDLE')"><text class="metric-value">{{ equipment.idle }}</text><text class="metric-label">空闲</text></view>
				<view class="metric" @click="openEquipmentStatus('RUNNING')"><text class="metric-value running">{{ equipment.running }}</text><text class="metric-label">运行</text></view>
				<view class="metric" @click="openEquipmentStatus('STOPPED')"><text class="metric-value warning">{{ equipment.stopped }}</text><text class="metric-label">停机</text></view>
				<view class="metric" @click="openEquipmentStatus('SCRAPPED')"><text class="metric-value">{{ equipment.scrapped }}</text><text class="metric-label">报废</text></view>
			</view>
		</view>

		<view class="section">
			<view class="section-header" @click="openInspections"><text>我的点检</text><text class="section-link">全部任务</text></view>
			<view class="inspection-card">
				<view class="inspection-main">
					<text class="inspection-number">{{ inspection.pending }}</text>
					<text class="inspection-label">待执行任务</text>
				</view>
				<view class="inspection-stat"><text>{{ inspection.dueToday }}</text><text>今日应检</text></view>
				<view class="inspection-stat danger"><text>{{ inspection.overdue }}</text><text>已超期</text></view>
				<view class="inspection-stat success"><text>{{ inspection.completedToday }}</text><text>今日完成</text></view>
			</view>
		</view>

		<view class="section">
			<view class="section-header" @click="openInspections"><text>我的待办</text><text class="section-link">{{ todoTotal }} 项 · 查看全部</text></view>
			<view class="todo-card">
				<view v-for="task in todoRows" :key="task.id" class="todo-row" @click="openTask(task)">
					<view class="todo-main">
						<view class="todo-title"><text>{{ task.equipmentCode }} · {{ task.equipmentName }}</text><text :class="['todo-status', task.taskStatus.toLowerCase()]">{{ taskStatusLabel(task.taskStatus) }}</text></view>
						<text class="todo-scheme">{{ task.schemeNameSnapshot }}</text>
						<view class="todo-meta"><text>{{ task.taskCode }}</text><text>截止 {{ dateTime(task.dueTime) }}</text></view>
					</view>
					<text class="todo-arrow">›</text>
				</view>
				<view v-if="todoLoading" class="todo-empty">正在加载待办任务…</view>
				<view v-else-if="todoError" class="todo-empty error-text" @click.stop="loadTodos">{{ todoError }} · 点击重试</view>
				<view v-else-if="!todoRows.length" class="todo-empty">当前没有待执行的点检任务</view>
			</view>
		</view>

		<view class="section">
			<view class="section-header"><text>现场作业</text></view>
			<view class="action-grid">
				<view class="action primary" @click="openScan"><text class="action-icon">扫</text><text>设备扫码</text></view>
				<view class="action" @click="openInspections"><text class="action-icon">检</text><text>我的点检</text></view>
				<view class="action" @click="openAbnormalities"><text class="action-icon danger-bg">异</text><text>点检异常</text></view>
			</view>
		</view>

		<view class="section message-card" @click="openMessages">
			<view><text class="message-title">现场消息</text><text class="message-subtitle">逾期提醒、异常升级与确认事项</text></view><text class="message-count">{{ unreadMessages }}</text>
		</view>

		<view class="section report-card" @click="openReport">
			<view><text class="report-title">本月个人点检</text><text class="report-range">{{ report.startDate || '--' }} 至 {{ report.endDate || '--' }}</text></view>
			<text class="report-rate">{{ completionRate }}%</text>
		</view>
		<AppBottomNav active="workbench" />
	</view>
</template>

<script setup>
	import { computed, ref } from 'vue'
	import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
	import { connected, initializeNetwork, networkType } from '../../platform/network.js'
	import { inspectionApi } from '../../api/inspection.js'
	import { mobileState, refreshMobileBootstrap } from '../../stores/mobile.js'
	import { displayName, sessionState } from '../../stores/session.js'
	import { ROUTES, navigateTo, routeWithQuery } from '../../constants/routes.js'
	import { checkAndroidUpgrade } from '../../utils/version.js'
	import { errorMessage, isServiceUnavailable } from '../../utils/errors.js'
	import { inspectionTodoRows } from '../../utils/inspection-todos.js'
	import { inspectionTaskTarget } from '../../utils/inspection-navigation.js'
	import AppBottomNav from '../../components/AppBottomNav.vue'
	import { pendingWorkCount } from '../../stores/offline.js'
	import { listRecentEquipment } from '../../stores/recent-equipment.js'

	let serviceAlertShown = false

	const todayText = ref('')
	const avatarText = computed(() => displayName().slice(0, 1))
	const equipment = computed(() => mobileState.bootstrap?.equipmentStatus || { total: 0, idle: 0, running: 0, stopped: 0, scrapped: 0 })
	const inspection = computed(() => mobileState.bootstrap?.inspection || { dueToday: 0, pending: 0, overdue: 0, completedToday: 0 })
	const report = computed(() => mobileState.bootstrap?.personalInspectionReport || {})
	const completionRate = computed(() => report.value.due ? Math.round((report.value.completed || 0) * 100 / report.value.due) : 0)
	const unreadMessages = computed(() => (mobileState.bootstrap?.messages || []).filter((item) => !item.readTime).length)
	const todoRows = ref([])
	const todoTotal = ref(0)
	const todoLoading = ref(false)
	const todoError = ref('')
	const pendingOfflineWork = ref(0)
	const recentEquipment = ref([])
	const taskLabels = { PENDING: '待执行', IN_PROGRESS: '执行中', OVERDUE: '已逾期' }

	onLoad(() => {
		initializeNetwork()
		const now = new Date()
		todayText.value = `${now.getFullYear()}年${now.getMonth() + 1}月${now.getDate()}日`
	})

	onShow(load)
	onPullDownRefresh(async () => {
		try { await load() } finally { uni.stopPullDownRefresh() }
	})

	async function load() {
		if (!sessionState.user) return
		pendingOfflineWork.value = pendingWorkCount()
		recentEquipment.value = listRecentEquipment()
		try {
			const bootstrap = await refreshMobileBootstrap()
			serviceAlertShown = false
			if (checkAndroidUpgrade(bootstrap?.androidVersion)) return
			await loadTodos()
		} catch (error) {
			if (isServiceUnavailable(error) && !serviceAlertShown) {
				serviceAlertShown = true
				uni.showModal({
					title: '企业服务暂时不可用',
					content: '无法连接 LeanTPM 后端服务。登录状态和离线草稿已保留，请确认服务器启动后点击页面提示重试。',
					showCancel: false
				})
			}
		}
	}

	async function loadTodos() {
		if (todoLoading.value) return
		todoLoading.value = true; todoError.value = ''
		try {
			const result = await inspectionApi.tasks({ mineOnly: true, page: 1, pageSize: 100 })
			const active = inspectionTodoRows(result?.records || [], 100)
			todoTotal.value = active.length
			todoRows.value = active.slice(0, 5)
		} catch (cause) { todoError.value = errorMessage(cause, '待办任务加载失败') }
		finally { todoLoading.value = false }
	}

	function taskStatusLabel(status) { return taskLabels[status] || status }
	function dateTime(value) { return value ? String(value).replace('T', ' ').slice(0, 16) : '—' }
	function openTask(task) { navigateTo(inspectionTaskTarget(task).url) }
	function openRecentEquipment(item) { navigateTo(routeWithQuery(ROUTES.equipmentContext, { token: item.token })) }

	function openProfile() { navigateTo(ROUTES.profile) }
	function openScan() { navigateTo(ROUTES.scan) }
	function openInspections() { navigateTo(ROUTES.inspectionTasks) }
	function openAbnormalities() { navigateTo(ROUTES.abnormalities) }
	function openMessages() { navigateTo(ROUTES.messages) }
	function openReport() { navigateTo(ROUTES.report) }
	function openEquipmentStatus(status) { navigateTo(routeWithQuery(ROUTES.equipmentStatus, { status })) }
</script>

<style>
	.page { min-height: 100vh; background: #f4f7f5; }
	.hero { padding: calc(62rpx + env(safe-area-inset-top)) 36rpx 56rpx; color: #fff; background: linear-gradient(145deg, #173c2f, var(--brand-primary, #1c7d50) 72%, #319567); border-radius: 0 0 42rpx 42rpx; }
	.hero-top { display: flex; align-items: center; justify-content: space-between; }
	.welcome, .date { display: block; }
	.welcome { font-size: 40rpx; font-weight: 800; }
	.date { margin-top: 11rpx; color: rgba(255,255,255,.72); font-size: 24rpx; }
	.avatar { display: flex; width: 76rpx; height: 76rpx; align-items: center; justify-content: center; border: 2rpx solid rgba(255,255,255,.55); border-radius: 24rpx; background: rgba(255,255,255,.14); font-size: 30rpx; font-weight: 700; }
	.network-row { display: flex; align-items: center; margin-top: 36rpx; color: rgba(255,255,255,.78); font-size: 23rpx; }
	.network-dot { width: 15rpx; height: 15rpx; margin-right: 12rpx; border-radius: 50%; background: #77e2a9; box-shadow: 0 0 0 8rpx rgba(119,226,169,.14); }
	.network-dot.offline { background: #ffcd70; box-shadow: 0 0 0 8rpx rgba(255,205,112,.14); }
	.error-card { margin: 26rpx 28rpx 0; padding: 24rpx; border-radius: 18rpx; color: #a00008; background: #fff0f0; font-size: 25rpx; text-align: center; }
	.scan-entry { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: 20rpx; margin: 28rpx 28rpx 0; padding: 28rpx; border-radius: 25rpx; color: #fff; background: linear-gradient(135deg, var(--brand-primary, #1c7d50), #2d9967); box-shadow: 0 16rpx 38rpx rgba(28,125,80,.22); }
	.scan-symbol { display: flex; width: 82rpx; height: 82rpx; align-items: center; justify-content: center; border-radius: 25rpx; background: rgba(255,255,255,.18); font-size: 32rpx; font-weight: 850; }
	.scan-title, .scan-subtitle { display: block; }
	.scan-title { font-size: 31rpx; font-weight: 850; }
	.scan-subtitle { margin-top: 8rpx; color: rgba(255,255,255,.75); font-size: 21rpx; line-height: 1.5; }
	.scan-arrow { font-size: 44rpx; }
	.sync-banner { display: flex; align-items: center; justify-content: space-between; gap: 20rpx; margin: 20rpx 28rpx 0; padding: 22rpx 25rpx; border: 2rpx solid #e8bd65; border-radius: 20rpx; color: #895b0d; background: #fff7e5; }
	.sync-banner view text { display: block; font-size: 23rpx; font-weight: 750; }
	.sync-banner view text:last-child { margin-top: 5rpx; color: #a17b38; font-size: 19rpx; font-weight: 400; }
	.sync-banner > text { flex: none; font-size: 22rpx; font-weight: 750; }
	.recent-section { margin-top: 24rpx; }
	.recent-card { overflow: hidden; border-radius: 22rpx; background: #fff; box-shadow: 0 12rpx 38rpx rgba(25,53,42,.06); }
	.recent-row { display: flex; align-items: center; justify-content: space-between; gap: 20rpx; padding: 22rpx 24rpx; border-bottom: 1rpx solid #edf1ef; }
	.recent-row:last-child { border-bottom: 0; }
	.recent-row view { min-width: 0; }
	.recent-row view text { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
	.recent-row view text:first-child { color: #203d31; font-size: 26rpx; font-weight: 760; }
	.recent-row view text:last-child { margin-top: 6rpx; color: #87928c; font-size: 20rpx; }
	.recent-row > text { flex: none; color: var(--brand-primary, #1c7d50); font-size: 21rpx; }
	.section { margin: 28rpx 28rpx 0; }
	.section-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 18rpx; color: #203c31; font-size: 31rpx; font-weight: 750; }
	.section-link { color: var(--brand-primary, #1c7d50); font-size: 23rpx; font-weight: 500; }
	.equipment-grid { display: grid; grid-template-columns: repeat(4, 1fr); overflow: hidden; border-radius: 24rpx; background: #fff; box-shadow: 0 12rpx 38rpx rgba(25,53,42,.06); }
	.metric { display: flex; align-items: center; flex-direction: column; padding: 30rpx 4rpx; }
	.metric-value, .metric-label { display: block; }
	.metric-value { color: var(--brand-secondary, #3e3a39); font-size: 37rpx; font-weight: 800; }
	.metric-label { margin-top: 8rpx; color: #849089; font-size: 22rpx; }
	.running, .success { color: var(--brand-primary, #1c7d50) !important; }
	.warning { color: #d99218 !important; }
	.danger { color: var(--brand-accent, #c4000a) !important; }
	.inspection-card { display: flex; align-items: center; padding: 30rpx 24rpx; border-radius: 24rpx; background: #fff; box-shadow: 0 12rpx 38rpx rgba(25,53,42,.06); }
	.inspection-main { min-width: 150rpx; padding-right: 24rpx; border-right: 1rpx solid #edf1ef; }
	.inspection-number, .inspection-label, .inspection-stat text { display: block; }
	.inspection-number { color: var(--brand-primary, #1c7d50); font-size: 52rpx; font-weight: 850; }
	.inspection-label { color: #78847e; font-size: 22rpx; }
	.inspection-stat { flex: 1; color: var(--brand-secondary, #3e3a39); text-align: center; }
	.inspection-stat text:first-child { font-size: 31rpx; font-weight: 750; }
	.inspection-stat text:last-child { margin-top: 7rpx; color: #8b9690; font-size: 21rpx; }
	.todo-card { overflow: hidden; border-radius: 24rpx; background: #fff; box-shadow: 0 12rpx 38rpx rgba(25,53,42,.06); }
	.todo-row { display: flex; align-items: center; gap: 18rpx; padding: 25rpx 24rpx; border-bottom: 1rpx solid #edf1ef; }
	.todo-row:last-child { border-bottom: 0; }
	.todo-main { min-width: 0; flex: 1; }
	.todo-title, .todo-meta { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
	.todo-title > text:first-child { overflow: hidden; color: #203d31; font-size: 28rpx; font-weight: 760; text-overflow: ellipsis; white-space: nowrap; }
	.todo-status { flex: none; padding: 6rpx 14rpx; border-radius: 18rpx; color: #176f47; background: #e6f5ed; font-size: 20rpx; }
	.todo-status.overdue { color: #a00008; background: #ffeded; }
	.todo-status.in_progress { color: #986306; background: #fff3dc; }
	.todo-scheme { display: block; overflow: hidden; margin-top: 7rpx; color: #75827c; font-size: 23rpx; text-overflow: ellipsis; white-space: nowrap; }
	.todo-meta { margin-top: 11rpx; color: #929b96; font-size: 20rpx; }
	.todo-arrow { color: var(--brand-primary, #1c7d50); font-size: 42rpx; }
	.todo-empty { padding: 40rpx 24rpx; color: #8a958f; font-size: 23rpx; text-align: center; }
	.todo-empty.error-text { color: #a00008; }
	.action-grid { display: grid; grid-template-columns: repeat(3, 1fr); padding: 26rpx 8rpx; border-radius: 24rpx; background: #fff; box-shadow: 0 12rpx 38rpx rgba(25,53,42,.06); }
	.action { display: flex; align-items: center; flex-direction: column; color: #4e5a54; font-size: 23rpx; }
	.action-icon { display: flex; width: 78rpx; height: 78rpx; align-items: center; justify-content: center; margin-bottom: 13rpx; border-radius: 25rpx; color: #fff; background: var(--brand-primary, #1c7d50); font-size: 29rpx; font-weight: 750; }
	.danger-bg { background: var(--brand-accent, #c4000a); }
	.report-card { display: flex; align-items: center; justify-content: space-between; padding: 30rpx; border-radius: 24rpx; color: #fff; background: linear-gradient(135deg, var(--brand-secondary, #3e3a39), #615d5c); }
	.report-title, .report-range { display: block; }
	.report-title { font-size: 28rpx; font-weight: 700; }
	.report-range { margin-top: 8rpx; color: rgba(255,255,255,.65); font-size: 20rpx; }
	.report-rate { font-size: 44rpx; font-weight: 800; }
	.message-card { display: flex; align-items: center; justify-content: space-between; padding: 28rpx 30rpx; border-radius: 24rpx; background: #fff; box-shadow: 0 12rpx 38rpx rgba(25,53,42,.06); }
	.message-title, .message-subtitle { display: block; }
	.message-title { color: #2b4539; font-size: 28rpx; font-weight: 750; }
	.message-subtitle { margin-top: 6rpx; color: #89938e; font-size: 21rpx; }
	.message-count { display: flex; min-width: 54rpx; height: 54rpx; align-items: center; justify-content: center; border-radius: 27rpx; color: #fff; background: var(--brand-accent, #c4000a); font-size: 23rpx; font-weight: 800; }
</style>
