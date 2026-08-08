<template>
	<view class="page" :style="$brandTheme()">
		<view class="hero">
			<view>
				<text class="eyebrow">INSPECTION REPORT</text>
				<text class="title">我的点检绩效</text>
				<text class="range">{{ report.startDate || startDate }} 至 {{ report.endDate || endDate }}</text>
			</view>
			<view class="hero-badge"><text>{{ activePeriodLabel }}</text><text>统计周期</text></view>
		</view>

		<view class="filter-card">
			<text class="section-title">快捷日期</text>
			<view class="quick-periods">
				<view
					v-for="item in quickPeriods"
					:key="item.key"
					:class="['period-chip', { active: activePeriod === item.key }]"
					@click="applyPeriod(item.key)"
				>{{ item.label }}</view>
			</view>
			<view class="custom-head"><text>自定义日期</text><text>{{ startDate }} 至 {{ endDate }}</text></view>
			<view class="date-row">
				<picker mode="date" :value="startDate" @change="changeStartDate">
					<view class="date-picker"><text>开始日期</text><text>{{ startDate }}</text></view>
				</picker>
				<text class="date-separator">至</text>
				<picker mode="date" :value="endDate" @change="changeEndDate">
					<view class="date-picker"><text>结束日期</text><text>{{ endDate }}</text></view>
				</picker>
			</view>
			<button class="query-button" :loading="loading" @click="load">查询自定义范围</button>
		</view>

		<view v-if="error" class="error" @click="load">{{ error }} · 点击重试</view>

		<text class="metric-group-title">全部点检任务</text>
		<view class="metrics">
			<view><text>{{ due }}</text><text>应完成</text></view>
			<view><text class="green">{{ completed }}</text><text>已完成</text></view>
			<view><text class="orange">{{ pending }}</text><text>未完成</text></view>
			<view><text class="red">{{ overdue }}</text><text>已逾期</text></view>
		</view>

		<view class="registration-card">
			<view class="card-head"><view><text class="section-title">现场登记指标</text><text class="section-subtitle">按实际提交时间统计，不替代计划考核</text></view><text class="registration-badge">{{ quickRegistered }} 条扫码登记</text></view>
			<view class="registration-metrics">
				<view><text>{{ registered }}</text><text>完成登记</text></view>
				<view><text>{{ equipmentCovered }}</text><text>覆盖设备</text></view>
				<view><text>{{ quickRegistered }}</text><text>扫码直检</text></view>
				<view><text>{{ abnormal }}</text><text>异常记录</text></view>
			</view>
		</view>

		<view class="plan-card">
			<view class="card-head"><view><text class="section-title">计划执行指标</text><text class="section-subtitle">仅统计由点检计划生成的任务</text></view><text class="plan-rate">{{ planCompletionRate }}%</text></view>
			<view class="plan-metrics">
				<view><text>{{ planDue }}</text><text>计划应检</text></view>
				<view><text>{{ planCompleted }}</text><text>计划完成</text></view>
				<view><text>{{ planOverdue }}</text><text>计划逾期</text></view>
			</view>
			<view class="plan-progress"><view :style="{ width: `${planCompletionRate}%` }" /></view>
		</view>

		<view class="chart-card completion-card">
			<view class="card-head">
				<view><text class="section-title">任务完成率</text><text class="section-subtitle">已完成任务 ÷ 应完成任务</text></view>
				<text :class="['rate-status', completionRate >= 90 ? 'good' : 'attention']">{{ completionStatus }}</text>
			</view>
			<view class="completion-chart">
				<view class="donut" :style="ringStyle">
					<view class="donut-inner"><text>{{ completionRate }}%</text><text>完成率</text></view>
				</view>
				<view class="completion-notes">
					<view><text class="note-dot completed-dot" /><text>已完成</text><text>{{ completed }} 项</text></view>
					<view><text class="note-dot pending-dot" /><text>待完成</text><text>{{ pendingWithoutOverdue }} 项</text></view>
					<view><text class="note-dot overdue-dot" /><text>已逾期</text><text>{{ overdue }} 项</text></view>
				</view>
			</view>
			<view class="stacked-track">
				<view v-for="segment in distributionSegments" :key="segment.key" :class="['segment', segment.key]" :style="{ width: `${segment.width}%` }" />
			</view>
		</view>

		<view class="chart-card">
			<view class="card-head">
				<view><text class="section-title">任务状态对比</text><text class="section-subtitle">快速识别积压、逾期和异常</text></view>
				<text class="abnormal-rate">异常率 {{ abnormalRate }}%</text>
			</view>
			<view class="bar-chart">
				<view v-for="bar in statusBars" :key="bar.key" class="bar-column">
					<text class="bar-value">{{ bar.value }}</text>
					<view class="bar-track"><view :class="['bar-fill', bar.key]" :style="{ height: `${bar.height}rpx` }" /></view>
					<text class="bar-label">{{ bar.label }}</text>
				</view>
			</view>
		</view>

		<view class="insight-card">
			<text class="section-title">本期数据说明</text>
			<view class="insight-row"><text>统计范围</text><text>{{ report.startDate || startDate }} 至 {{ report.endDate || endDate }}</text></view>
			<view class="insight-row"><text>异常任务</text><text class="red">{{ abnormal }} 项</text></view>
			<view class="insight-row"><text>任务口径</text><text>主执行人及协作人</text></view>
			<text class="tip">取消和作废的任务不计入应完成任务；逾期任务包含超过截止时间仍未完成的任务。</text>
		</view>

		<AppBottomNav active="report" />
	</view>
</template>

<script setup>
	import { computed, ref } from 'vue'
	import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app'
	import { mobileApi } from '../../api/mobile.js'
	import { errorMessage } from '../../utils/errors.js'
	import { reportPeriodRange } from '../../utils/report-period.js'
	import AppBottomNav from '../../components/AppBottomNav.vue'

	const quickPeriods = Object.freeze([
		{ key: 'month', label: '本月' },
		{ key: 'previousMonth', label: '上月' },
		{ key: 'week', label: '本周' },
		{ key: 'today', label: '今天' }
	])
	const initialRange = reportPeriodRange('month')
	const startDate = ref(initialRange.startDate)
	const endDate = ref(initialRange.endDate)
	const activePeriod = ref('month')
	const report = ref({})
	const loading = ref(false)
	const error = ref('')
	let loadSequence = 0

	const due = computed(() => Number(report.value.due || 0))
	const completed = computed(() => Number(report.value.completed || 0))
	const pending = computed(() => Number(report.value.pending || 0))
	const overdue = computed(() => Number(report.value.overdue || 0))
	const abnormal = computed(() => Number(report.value.abnormal || 0))
	const planDue = computed(() => Number(report.value.planDue || 0))
	const planCompleted = computed(() => Number(report.value.planCompleted || 0))
	const planOverdue = computed(() => Number(report.value.planOverdue || 0))
	const registered = computed(() => Number(report.value.registered || 0))
	const quickRegistered = computed(() => Number(report.value.quickRegistered || 0))
	const equipmentCovered = computed(() => Number(report.value.equipmentCovered || 0))
	const planCompletionRate = computed(() => planDue.value
		? Math.min(100, Math.round(planCompleted.value * 100 / planDue.value))
		: 0)
	const pendingWithoutOverdue = computed(() => Math.max(0, pending.value - overdue.value))
	const completionRate = computed(() => due.value
		? Math.min(100, Math.round(completed.value * 100 / due.value))
		: 0)
	const abnormalRate = computed(() => due.value
		? Math.min(100, Math.round(abnormal.value * 100 / due.value))
		: 0)
	const activePeriodLabel = computed(() => quickPeriods.find((item) => item.key === activePeriod.value)?.label || '自定义')
	const completionStatus = computed(() => {
		if (!due.value) return '暂无任务'
		if (completionRate.value >= 90) return '表现良好'
		if (completionRate.value >= 70) return '需要关注'
		return '建议跟进'
	})
	const ringStyle = computed(() => ({
		background: `conic-gradient(var(--brand-primary, #1c7d50) ${completionRate.value * 3.6}deg, #e4ebe7 0deg)`
	}))
	const distributionSegments = computed(() => {
		const total = Math.max(1, due.value)
		return [
			{ key: 'completed', width: completed.value * 100 / total },
			{ key: 'pending', width: pendingWithoutOverdue.value * 100 / total },
			{ key: 'overdue', width: overdue.value * 100 / total }
		].filter((item) => item.width > 0)
	})
	const statusBars = computed(() => {
		const values = [completed.value, pendingWithoutOverdue.value, overdue.value, abnormal.value]
		const maximum = Math.max(1, ...values)
		return [
			{ key: 'completed', label: '已完成', value: completed.value },
			{ key: 'pending', label: '待完成', value: pendingWithoutOverdue.value },
			{ key: 'overdue', label: '已逾期', value: overdue.value },
			{ key: 'abnormal', label: '有异常', value: abnormal.value }
		].map((item) => ({
			...item,
			height: item.value ? Math.max(22, Math.round(item.value * 150 / maximum)) : 8
		}))
	})

	onLoad(load)
	onPullDownRefresh(async () => {
		try { await load() } finally { uni.stopPullDownRefresh() }
	})

	function changeStartDate(event) {
		startDate.value = event.detail.value
		activePeriod.value = 'custom'
	}

	function changeEndDate(event) {
		endDate.value = event.detail.value
		activePeriod.value = 'custom'
	}

	function applyPeriod(period) {
		const range = reportPeriodRange(period)
		activePeriod.value = period
		startDate.value = range.startDate
		endDate.value = range.endDate
		load()
	}

	async function load() {
		if (endDate.value < startDate.value) {
			error.value = '结束日期不能早于开始日期'
			return
		}
		const sequence = ++loadSequence
		loading.value = true
		error.value = ''
		try {
			const result = await mobileApi.personalInspectionReport({
				startDate: startDate.value,
				endDate: endDate.value
			})
			if (sequence === loadSequence) report.value = result
		} catch (cause) {
			if (sequence === loadSequence) error.value = errorMessage(cause, '点检报表加载失败')
		} finally {
			if (sequence === loadSequence) loading.value = false
		}
	}
</script>

<style>
	.page { min-height: 100vh; padding: 25rpx 26rpx 0; background: #f4f7f5; }
	.hero { display: flex; align-items: center; justify-content: space-between; padding: 36rpx 32rpx; border-radius: 27rpx; color: #fff; background: linear-gradient(140deg, #30302f, var(--brand-secondary, #3e3a39)); }
	.eyebrow, .title, .range, .hero-badge text { display: block; }
	.eyebrow { color: #78d2a4; font-size: 19rpx; font-weight: 800; letter-spacing: 3rpx; }
	.title { margin-top: 10rpx; font-size: 36rpx; font-weight: 850; }
	.range { margin-top: 9rpx; font-size: 21rpx; opacity: .72; }
	.hero-badge { min-width: 112rpx; padding: 18rpx 12rpx; border: 1rpx solid rgba(255,255,255,.24); border-radius: 18rpx; background: rgba(255,255,255,.08); text-align: center; }
	.hero-badge text:first-child { font-size: 28rpx; font-weight: 800; }
	.hero-badge text:last-child { margin-top: 5rpx; font-size: 18rpx; opacity: .68; }
	.filter-card, .chart-card, .insight-card, .registration-card, .plan-card { margin-top: 20rpx; padding: 28rpx; border-radius: 22rpx; background: #fff; box-shadow: 0 10rpx 32rpx rgba(25,53,42,.05); }
	.metric-group-title { display: block; margin: 24rpx 4rpx -6rpx; color: #264236; font-size: 25rpx; font-weight: 800; }
	.section-title { display: block; color: #264236; font-size: 29rpx; font-weight: 800; }
	.section-subtitle { display: block; margin-top: 6rpx; color: #8a9690; font-size: 20rpx; }
	.quick-periods { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12rpx; margin-top: 20rpx; }
	.period-chip { padding: 17rpx 8rpx; border: 2rpx solid #dfe8e3; border-radius: 14rpx; color: #65736c; font-size: 23rpx; text-align: center; }
	.period-chip.active { border-color: var(--brand-primary, #1c7d50); color: #fff; background: var(--brand-primary, #1c7d50); font-weight: 700; }
	.custom-head { display: flex; justify-content: space-between; margin-top: 25rpx; color: #89948e; font-size: 20rpx; }
	.date-row { display: flex; align-items: center; gap: 12rpx; margin-top: 12rpx; }
	.date-row picker { flex: 1; }
	.date-picker { padding: 16rpx 12rpx; border: 2rpx solid #dfe8e3; border-radius: 14rpx; }
	.date-picker text { display: block; color: #264236; font-size: 23rpx; text-align: center; }
	.date-picker text:first-child { margin-bottom: 6rpx; color: #8b9690; font-size: 18rpx; }
	.date-separator { color: #7d8983; font-size: 22rpx; }
	.query-button { margin-top: 20rpx; color: #fff; background: var(--brand-primary, #1c7d50); font-size: 24rpx; }
	.metrics { display: grid; grid-template-columns: repeat(4, 1fr); margin-top: 20rpx; padding: 27rpx 5rpx; border-radius: 22rpx; background: #fff; }
	.metrics view { text-align: center; }
	.metrics text { display: block; color: #31483e; font-size: 32rpx; font-weight: 800; }
	.metrics text:last-child { margin-top: 6rpx; color: #89938e; font-size: 20rpx; font-weight: 400; }
	.registration-badge { padding: 8rpx 13rpx; border-radius: 999rpx; color: #176c46; background: #e6f4ec; font-size: 19rpx; white-space: nowrap; }
	.registration-metrics { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8rpx; margin-top: 24rpx; }
	.registration-metrics view { text-align: center; }
	.registration-metrics text { display: block; color: var(--brand-primary, #1c7d50); font-size: 34rpx; font-weight: 850; }
	.registration-metrics text:last-child { margin-top: 7rpx; color: #87928c; font-size: 19rpx; font-weight: 400; }
	.plan-rate { color: var(--brand-primary, #1c7d50); font-size: 35rpx; font-weight: 900; }
	.plan-metrics { display: grid; grid-template-columns: repeat(3, 1fr); margin-top: 24rpx; }
	.plan-metrics view { text-align: center; }
	.plan-metrics text { display: block; color: #30483d; font-size: 31rpx; font-weight: 850; }
	.plan-metrics text:last-child { margin-top: 6rpx; color: #89938e; font-size: 20rpx; font-weight: 400; }
	.plan-progress { overflow: hidden; height: 14rpx; margin-top: 22rpx; border-radius: 999rpx; background: #e8eeeb; }
	.plan-progress view { height: 100%; border-radius: inherit; background: var(--brand-primary, #1c7d50); }
	.card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16rpx; }
	.rate-status { padding: 8rpx 13rpx; border-radius: 999rpx; font-size: 19rpx; white-space: nowrap; }
	.rate-status.good { color: #176c46; background: #e6f4ec; }
	.rate-status.attention { color: #a26708; background: #fff3d9; }
	.completion-chart { display: flex; align-items: center; justify-content: space-around; margin-top: 28rpx; }
	.donut { display: flex; width: 210rpx; height: 210rpx; align-items: center; justify-content: center; border-radius: 50%; }
	.donut-inner { display: flex; width: 154rpx; height: 154rpx; align-items: center; justify-content: center; flex-direction: column; border-radius: 50%; background: #fff; box-shadow: inset 0 0 0 1rpx #edf1ef; }
	.donut-inner text:first-child { color: #263d33; font-size: 40rpx; font-weight: 900; }
	.donut-inner text:last-child { margin-top: 3rpx; color: #89948e; font-size: 19rpx; }
	.completion-notes { min-width: 235rpx; }
	.completion-notes view { display: grid; grid-template-columns: 20rpx 1fr auto; align-items: center; gap: 10rpx; padding: 12rpx 0; color: #66746d; font-size: 22rpx; }
	.completion-notes view text:last-child { color: #2f473c; font-weight: 800; }
	.note-dot { width: 15rpx; height: 15rpx; border-radius: 50%; }
	.completed-dot, .segment.completed, .bar-fill.completed { background: var(--brand-primary, #1c7d50); }
	.pending-dot, .segment.pending, .bar-fill.pending { background: #d99927; }
	.overdue-dot, .segment.overdue, .bar-fill.overdue, .bar-fill.abnormal { background: var(--brand-accent, #c4000a); }
	.stacked-track { display: flex; overflow: hidden; height: 18rpx; margin-top: 25rpx; border-radius: 999rpx; background: #e8eeeb; }
	.segment { height: 100%; }
	.abnormal-rate { color: var(--brand-accent, #c4000a); font-size: 21rpx; font-weight: 750; }
	.bar-chart { display: grid; height: 235rpx; grid-template-columns: repeat(4, 1fr); gap: 18rpx; align-items: end; margin-top: 24rpx; padding-top: 12rpx; border-bottom: 1rpx solid #dfe6e2; }
	.bar-column { display: flex; height: 100%; align-items: center; justify-content: flex-end; flex-direction: column; }
	.bar-value { margin-bottom: 8rpx; color: #35483f; font-size: 22rpx; font-weight: 800; }
	.bar-track { display: flex; width: 55rpx; height: 150rpx; align-items: flex-end; justify-content: center; }
	.bar-fill { width: 100%; min-height: 8rpx; border-radius: 12rpx 12rpx 2rpx 2rpx; transition: height .25s ease; }
	.bar-label { margin: 12rpx 0 -32rpx; color: #748079; font-size: 19rpx; white-space: nowrap; }
	.insight-card { margin-bottom: 10rpx; }
	.insight-row { display: flex; justify-content: space-between; gap: 16rpx; padding: 18rpx 0; border-bottom: 1rpx solid #edf1ef; color: #718079; font-size: 23rpx; }
	.insight-row text:last-child { color: #324a40; font-weight: 750; text-align: right; }
	.tip { display: block; margin-top: 20rpx; color: #909a95; font-size: 20rpx; line-height: 1.65; }
	.green { color: var(--brand-primary, #1c7d50) !important; }
	.orange { color: #d68b11 !important; }
	.red { color: var(--brand-accent, #c4000a) !important; }
	.error { margin-top: 20rpx; padding: 30rpx; border-radius: 18rpx; color: #a00008; background: #fff0f0; text-align: center; }
</style>
