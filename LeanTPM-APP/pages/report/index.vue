<template>
	<view class="page" :style="$brandTheme()">
		<view class="hero">
			<text class="eyebrow">PERSONAL REPORT</text>
			<text class="title">我的点检完成情况</text>
			<text class="range">{{ report.startDate || startDate }} 至 {{ report.endDate || endDate }}</text>
			<text class="rate">{{ completionRate }}%</text>
			<text class="rate-label">任务完成率</text>
		</view>

		<view class="filter-card">
			<text class="filter-title">统计日期</text>
			<view class="date-row">
				<picker mode="date" :value="startDate" @change="changeStartDate">
					<view class="date-picker"><text>开始日期</text><text>{{ startDate }}</text></view>
				</picker>
				<text class="date-separator">至</text>
				<picker mode="date" :value="endDate" @change="changeEndDate">
					<view class="date-picker"><text>结束日期</text><text>{{ endDate }}</text></view>
				</picker>
			</view>
			<button class="query-button" :loading="loading" @click="load">查询个人报表</button>
		</view>

		<view v-if="error" class="error" @click="load">{{ error }} · 点击重试</view>
		<view class="metrics">
			<view><text>{{ report.due || 0 }}</text><text>应完成</text></view>
			<view><text class="green">{{ report.completed || 0 }}</text><text>已完成</text></view>
			<view><text class="orange">{{ report.pending || 0 }}</text><text>未完成</text></view>
			<view><text class="red">{{ report.overdue || 0 }}</text><text>已逾期</text></view>
		</view>

		<view class="card">
			<text class="card-title">个人任务汇总</text>
			<view class="row"><text>统计范围</text><text>{{ report.startDate || startDate }} 至 {{ report.endDate || endDate }}</text></view>
			<view class="row"><text>应完成任务</text><text>{{ report.due || 0 }}</text></view>
			<view class="row"><text>已完成任务</text><text class="green">{{ report.completed || 0 }}</text></view>
			<view class="row"><text>未完成任务</text><text class="orange">{{ report.pending || 0 }}</text></view>
			<view class="row"><text>其中已逾期</text><text class="red">{{ report.overdue || 0 }}</text></view>
			<view class="row"><text>发生异常的任务</text><text class="red">{{ report.abnormal || 0 }}</text></view>
		</view>
		<text class="tip">仅统计当前登录人员作为主执行人或协作人的点检任务；取消和作废任务不计入应完成任务。</text>
		<AppBottomNav active="report" />
	</view>
</template>

<script setup>
	import { computed, ref } from 'vue'
	import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app'
	import { mobileApi } from '../../api/mobile.js'
	import { errorMessage } from '../../utils/errors.js'
	import AppBottomNav from '../../components/AppBottomNav.vue'

	const now = new Date()
	const endDate = ref(formatDate(now))
	const startDate = ref(`${now.getFullYear()}-${pad(now.getMonth() + 1)}-01`)
	const report = ref({})
	const loading = ref(false)
	const error = ref('')
	const completionRate = computed(() => report.value.due
		? Math.min(100, Math.round((report.value.completed || 0) * 100 / report.value.due))
		: 0)

	onLoad(load)
	onPullDownRefresh(async () => { try { await load() } finally { uni.stopPullDownRefresh() } })

	function pad(value) { return String(value).padStart(2, '0') }
	function formatDate(value) { return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}` }
	function changeStartDate(event) { startDate.value = event.detail.value }
	function changeEndDate(event) { endDate.value = event.detail.value }

	async function load() {
		if (loading.value) return
		if (endDate.value < startDate.value) {
			error.value = '结束日期不能早于开始日期'
			return
		}
		loading.value = true
		error.value = ''
		try {
			report.value = await mobileApi.personalInspectionReport({
				startDate: startDate.value,
				endDate: endDate.value
			})
		} catch (cause) {
			error.value = errorMessage(cause, '个人报表加载失败')
		} finally {
			loading.value = false
		}
	}
</script>

<style>
	.page { min-height: 100vh; padding: 25rpx 26rpx 0; background: #f4f7f5; }
	.hero { padding: 38rpx 32rpx; border-radius: 27rpx; color: #fff; background: linear-gradient(140deg, #30302f, var(--brand-secondary, #3e3a39)); }
	.eyebrow, .title, .range, .rate, .rate-label { display: block; }
	.eyebrow { color: #78d2a4; font-size: 19rpx; font-weight: 800; letter-spacing: 4rpx; }
	.title { margin-top: 10rpx; font-size: 36rpx; font-weight: 850; }
	.range { margin-top: 7rpx; font-size: 21rpx; opacity: .7; }
	.rate { margin-top: 28rpx; font-size: 70rpx; font-weight: 900; }
	.rate-label { font-size: 21rpx; opacity: .7; }
	.filter-card, .card { margin-top: 20rpx; padding: 28rpx; border-radius: 22rpx; background: #fff; box-shadow: 0 10rpx 32rpx rgba(25,53,42,.05); }
	.filter-title, .card-title { display: block; margin-bottom: 18rpx; color: #264236; font-size: 29rpx; font-weight: 750; }
	.date-row { display: flex; align-items: center; gap: 12rpx; }
	.date-row picker { flex: 1; }
	.date-picker { padding: 17rpx 14rpx; border: 2rpx solid #dfe8e3; border-radius: 14rpx; }
	.date-picker text { display: block; color: #264236; font-size: 24rpx; text-align: center; }
	.date-picker text:first-child { margin-bottom: 7rpx; color: #8b9690; font-size: 19rpx; }
	.date-separator { color: #7d8983; font-size: 22rpx; }
	.query-button { margin-top: 22rpx; color: #fff; background: var(--brand-primary, #1c7d50); font-size: 25rpx; }
	.metrics { display: grid; grid-template-columns: repeat(4, 1fr); margin-top: 20rpx; padding: 27rpx 5rpx; border-radius: 22rpx; background: #fff; }
	.metrics view { text-align: center; }
	.metrics text { display: block; color: #31483e; font-size: 32rpx; font-weight: 800; }
	.metrics text:last-child { margin-top: 6rpx; color: #89938e; font-size: 20rpx; font-weight: 400; }
	.green { color: var(--brand-primary, #1c7d50) !important; }
	.orange { color: #d68b11 !important; }
	.red { color: var(--brand-accent, #c4000a) !important; }
	.row { display: flex; justify-content: space-between; gap: 20rpx; padding: 18rpx 0; border-bottom: 1rpx solid #edf1ef; color: #718079; font-size: 24rpx; }
	.row text:last-child { color: #324a40; font-size: 25rpx; font-weight: 750; text-align: right; }
	.tip { display: block; margin: 24rpx 10rpx 0; color: #909a95; font-size: 21rpx; line-height: 1.6; text-align: center; }
	.error { margin-top: 20rpx; padding: 30rpx; border-radius: 18rpx; color: #a00008; background: #fff0f0; text-align: center; }
</style>
