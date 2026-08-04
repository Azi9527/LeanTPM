<template>
	<view class="page">
		<view class="hero"><text class="eyebrow">PERSONAL REPORT</text><text class="title">我的点检绩效</text><text class="range">{{ report.startDate || '—' }} 至 {{ report.endDate || '—' }}</text><text class="rate">{{ completionRate }}%</text><text class="rate-label">任务完成率</text></view>
		<view v-if="error" class="error" @click="load">{{ error }} · 点击重试</view>
		<view class="metrics">
			<view><text>{{ report.due || 0 }}</text><text>应点检</text></view><view><text class="green">{{ report.completed || 0 }}</text><text>已完成</text></view><view><text class="red">{{ report.abnormal || 0 }}</text><text>异常项</text></view>
		</view>
		<view class="card"><text class="card-title">当前任务</text><view class="row"><text>待执行</text><text>{{ inspection.pending || 0 }}</text></view><view class="row"><text>今日应检</text><text>{{ inspection.dueToday || 0 }}</text></view><view class="row"><text>已逾期</text><text class="red">{{ inspection.overdue || 0 }}</text></view><view class="row"><text>今日完成</text><text class="green">{{ inspection.completedToday || 0 }}</text></view></view>
		<view class="card"><text class="card-title">异常概览</text><view class="row"><text>未关闭异常</text><text>{{ abnormal.open || 0 }}</text></view><view class="row"><text>严重异常</text><text class="red">{{ abnormal.critical || 0 }}</text></view><view class="row"><text>高风险异常</text><text class="red">{{ abnormal.high || 0 }}</text></view><button class="link" @click="openAbnormal">查看异常明细</button></view>
		<text class="tip">移动端仅展示与当前人员相关的报表；管理层全厂统计与下钻请使用 PC 端。</text>
	</view>
</template>

<script setup>
	import { computed } from 'vue'
	import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app'
	import { mobileState, refreshMobileBootstrap } from '../../stores/mobile.js'
	import { ROUTES, navigateTo } from '../../constants/routes.js'

	const report = computed(() => mobileState.bootstrap?.personalInspectionReport || {})
	const inspection = computed(() => mobileState.bootstrap?.inspection || {})
	const abnormal = computed(() => mobileState.bootstrap?.inspectionAbnormal || {})
	const error = computed(() => mobileState.error)
	const completionRate = computed(() => report.value.due ? Math.round((report.value.completed || 0) * 100 / report.value.due) : 0)
	onLoad(load)
	onPullDownRefresh(async () => { try { await load() } finally { uni.stopPullDownRefresh() } })
	async function load() { try { await refreshMobileBootstrap() } catch { /* visible error */ } }
	function openAbnormal() { navigateTo(ROUTES.abnormalities) }
</script>

<style>
	.page { min-height: 100vh; padding: 25rpx 26rpx 60rpx; background: #f4f7f5; }
	.hero { padding: 38rpx 32rpx; border-radius: 27rpx; color: #fff; background: linear-gradient(140deg, #30302f, #3e3a39); }
	.eyebrow, .title, .range, .rate, .rate-label { display: block; }
	.eyebrow { color: #78d2a4; font-size: 19rpx; font-weight: 800; letter-spacing: 4rpx; }
	.title { margin-top: 10rpx; font-size: 36rpx; font-weight: 850; }
	.range { margin-top: 7rpx; font-size: 21rpx; opacity: .62; }
	.rate { margin-top: 28rpx; font-size: 70rpx; font-weight: 900; }
	.rate-label { font-size: 21rpx; opacity: .65; }
	.metrics { display: grid; grid-template-columns: repeat(3, 1fr); margin-top: 20rpx; padding: 27rpx 8rpx; border-radius: 22rpx; background: #fff; }
	.metrics view { text-align: center; }
	.metrics text { display: block; color: #31483e; font-size: 34rpx; font-weight: 800; }
	.metrics text:last-child { margin-top: 6rpx; color: #89938e; font-size: 21rpx; font-weight: 400; }
	.green { color: #1c7d50 !important; }
	.red { color: #c4000a !important; }
	.card { margin-top: 20rpx; padding: 28rpx; border-radius: 22rpx; background: #fff; box-shadow: 0 10rpx 32rpx rgba(25,53,42,.05); }
	.card-title { display: block; margin-bottom: 12rpx; color: #264236; font-size: 29rpx; font-weight: 750; }
	.row { display: flex; justify-content: space-between; padding: 18rpx 0; border-bottom: 1rpx solid #edf1ef; color: #718079; font-size: 24rpx; }
	.row text:last-child { color: #324a40; font-size: 27rpx; font-weight: 750; }
	.link { margin-top: 22rpx; color: #1c7d50; background: #e9f5ef; font-size: 24rpx; }
	.tip { display: block; margin: 24rpx 10rpx; color: #909a95; font-size: 21rpx; line-height: 1.6; text-align: center; }
	.error { padding: 40rpx; color: #a00008; text-align: center; }
</style>
