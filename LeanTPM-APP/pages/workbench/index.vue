<template>
	<view class="page">
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

		<view class="section">
			<view class="section-header" @click="openEquipmentStatus('')"><text>设备状态</text><text class="section-link">查看明细</text></view>
			<view class="equipment-grid">
				<view class="metric equipment-total" @click="openEquipmentStatus('')"><text class="metric-value">{{ equipment.total }}</text><text class="metric-label">设备总数</text></view>
				<view class="metric" @click="openEquipmentStatus('RUNNING')"><text class="metric-value running">{{ equipment.running }}</text><text class="metric-label">运行</text></view>
				<view class="metric" @click="openEquipmentStatus('STOPPED')"><text class="metric-value warning">{{ equipment.stopped }}</text><text class="metric-label">停机</text></view>
				<view class="metric" @click="openEquipmentStatus('FAULT')"><text class="metric-value danger">{{ equipment.fault }}</text><text class="metric-label">故障</text></view>
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
			<view class="section-header"><text>现场作业</text></view>
			<view class="action-grid">
				<view class="action primary" @click="openScan"><text class="action-icon">扫</text><text>设备扫码</text></view>
				<view class="action" @click="openInspections"><text class="action-icon">检</text><text>我的点检</text></view>
				<view class="action" @click="openAbnormalities"><text class="action-icon danger-bg">异</text><text>点检异常</text></view>
				<view class="action" @click="openReport"><text class="action-icon neutral-bg">我</text><text>个人报表</text></view>
			</view>
		</view>

		<view class="section message-card" @click="openMessages">
			<view><text class="message-title">现场消息</text><text class="message-subtitle">逾期提醒、异常升级与确认事项</text></view><text class="message-count">{{ unreadMessages }}</text>
		</view>

		<view class="section report-card">
			<view><text class="report-title">近 30 天个人点检</text><text class="report-range">{{ report.startDate || '--' }} 至 {{ report.endDate || '--' }}</text></view>
			<text class="report-rate">{{ completionRate }}%</text>
		</view>

		<view class="bottom-space"></view>
		<view class="bottom-nav">
			<view class="nav-item active"><text>▣</text><text>工作台</text></view>
			<view class="nav-item" @click="openScan"><text>⌁</text><text>扫码</text></view>
			<view class="nav-item" @click="openInspections"><text>✓</text><text>点检</text></view>
			<view class="nav-item" @click="openProfile"><text>●</text><text>我的</text></view>
		</view>
	</view>
</template>

<script setup>
	import { computed, ref } from 'vue'
	import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
	import { connected, initializeNetwork, networkType } from '../../platform/network.js'
	import { mobileState, refreshMobileBootstrap } from '../../stores/mobile.js'
	import { displayName, sessionState } from '../../stores/session.js'
	import { ROUTES, navigateTo, routeWithQuery } from '../../constants/routes.js'
	import { checkAndroidUpgrade } from '../../utils/version.js'

	const todayText = ref('')
	const avatarText = computed(() => displayName().slice(0, 1))
	const equipment = computed(() => mobileState.bootstrap?.equipmentStatus || { total: 0, running: 0, stopped: 0, fault: 0, offline: 0 })
	const inspection = computed(() => mobileState.bootstrap?.inspection || { dueToday: 0, pending: 0, overdue: 0, completedToday: 0 })
	const report = computed(() => mobileState.bootstrap?.personalInspectionReport || {})
	const completionRate = computed(() => report.value.due ? Math.round((report.value.completed || 0) * 100 / report.value.due) : 0)
	const unreadMessages = computed(() => (mobileState.bootstrap?.messages || []).filter((item) => !item.readTime).length)

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
		try {
			const bootstrap = await refreshMobileBootstrap()
			checkAndroidUpgrade(bootstrap?.androidVersion)
		} catch { /* visible error card is populated by the store */ }
	}

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
	.hero { padding: calc(62rpx + env(safe-area-inset-top)) 36rpx 56rpx; color: #fff; background: linear-gradient(145deg, #173c2f, #1c7d50 72%, #319567); border-radius: 0 0 42rpx 42rpx; }
	.hero-top { display: flex; align-items: center; justify-content: space-between; }
	.welcome, .date { display: block; }
	.welcome { font-size: 40rpx; font-weight: 800; }
	.date { margin-top: 11rpx; color: rgba(255,255,255,.72); font-size: 24rpx; }
	.avatar { display: flex; width: 76rpx; height: 76rpx; align-items: center; justify-content: center; border: 2rpx solid rgba(255,255,255,.55); border-radius: 24rpx; background: rgba(255,255,255,.14); font-size: 30rpx; font-weight: 700; }
	.network-row { display: flex; align-items: center; margin-top: 36rpx; color: rgba(255,255,255,.78); font-size: 23rpx; }
	.network-dot { width: 15rpx; height: 15rpx; margin-right: 12rpx; border-radius: 50%; background: #77e2a9; box-shadow: 0 0 0 8rpx rgba(119,226,169,.14); }
	.network-dot.offline { background: #ffcd70; box-shadow: 0 0 0 8rpx rgba(255,205,112,.14); }
	.error-card { margin: 26rpx 28rpx 0; padding: 24rpx; border-radius: 18rpx; color: #a00008; background: #fff0f0; font-size: 25rpx; text-align: center; }
	.section { margin: 28rpx 28rpx 0; }
	.section-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 18rpx; color: #203c31; font-size: 31rpx; font-weight: 750; }
	.section-link { color: #1c7d50; font-size: 23rpx; font-weight: 500; }
	.equipment-grid { display: grid; grid-template-columns: repeat(4, 1fr); overflow: hidden; border-radius: 24rpx; background: #fff; box-shadow: 0 12rpx 38rpx rgba(25,53,42,.06); }
	.metric { display: flex; align-items: center; flex-direction: column; padding: 30rpx 4rpx; }
	.metric-value, .metric-label { display: block; }
	.metric-value { color: #3e3a39; font-size: 37rpx; font-weight: 800; }
	.metric-label { margin-top: 8rpx; color: #849089; font-size: 22rpx; }
	.running, .success { color: #1c7d50 !important; }
	.warning { color: #d99218 !important; }
	.danger { color: #c4000a !important; }
	.inspection-card { display: flex; align-items: center; padding: 30rpx 24rpx; border-radius: 24rpx; background: #fff; box-shadow: 0 12rpx 38rpx rgba(25,53,42,.06); }
	.inspection-main { min-width: 150rpx; padding-right: 24rpx; border-right: 1rpx solid #edf1ef; }
	.inspection-number, .inspection-label, .inspection-stat text { display: block; }
	.inspection-number { color: #1c7d50; font-size: 52rpx; font-weight: 850; }
	.inspection-label { color: #78847e; font-size: 22rpx; }
	.inspection-stat { flex: 1; color: #3e3a39; text-align: center; }
	.inspection-stat text:first-child { font-size: 31rpx; font-weight: 750; }
	.inspection-stat text:last-child { margin-top: 7rpx; color: #8b9690; font-size: 21rpx; }
	.action-grid { display: grid; grid-template-columns: repeat(4, 1fr); padding: 26rpx 8rpx; border-radius: 24rpx; background: #fff; box-shadow: 0 12rpx 38rpx rgba(25,53,42,.06); }
	.action { display: flex; align-items: center; flex-direction: column; color: #4e5a54; font-size: 23rpx; }
	.action-icon { display: flex; width: 78rpx; height: 78rpx; align-items: center; justify-content: center; margin-bottom: 13rpx; border-radius: 25rpx; color: #fff; background: #1c7d50; font-size: 29rpx; font-weight: 750; }
	.danger-bg { background: #c4000a; }
	.neutral-bg { background: #3e3a39; }
	.report-card { display: flex; align-items: center; justify-content: space-between; padding: 30rpx; border-radius: 24rpx; color: #fff; background: linear-gradient(135deg, #3e3a39, #615d5c); }
	.report-title, .report-range { display: block; }
	.report-title { font-size: 28rpx; font-weight: 700; }
	.report-range { margin-top: 8rpx; color: rgba(255,255,255,.65); font-size: 20rpx; }
	.report-rate { font-size: 44rpx; font-weight: 800; }
	.message-card { display: flex; align-items: center; justify-content: space-between; padding: 28rpx 30rpx; border-radius: 24rpx; background: #fff; box-shadow: 0 12rpx 38rpx rgba(25,53,42,.06); }
	.message-title, .message-subtitle { display: block; }
	.message-title { color: #2b4539; font-size: 28rpx; font-weight: 750; }
	.message-subtitle { margin-top: 6rpx; color: #89938e; font-size: 21rpx; }
	.message-count { display: flex; min-width: 54rpx; height: 54rpx; align-items: center; justify-content: center; border-radius: 27rpx; color: #fff; background: #c4000a; font-size: 23rpx; font-weight: 800; }
	.bottom-space { height: calc(130rpx + env(safe-area-inset-bottom)); }
	.bottom-nav { position: fixed; z-index: 20; right: 0; bottom: 0; left: 0; display: grid; grid-template-columns: repeat(4, 1fr); padding: 15rpx 12rpx calc(15rpx + env(safe-area-inset-bottom)); border-top: 1rpx solid #e5ebe8; background: rgba(255,255,255,.97); }
	.nav-item { display: flex; align-items: center; flex-direction: column; color: #89948e; font-size: 21rpx; }
	.nav-item text:first-child { margin-bottom: 4rpx; font-size: 30rpx; }
	.nav-item.active { color: #1c7d50; font-weight: 700; }
</style>
