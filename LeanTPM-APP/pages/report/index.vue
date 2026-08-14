<template>
	<view class="page" :style="$brandTheme()">
		<view class="hero">
			<view>
				<text class="eyebrow">INSPECTION PERFORMANCE</text>
				<text class="title">{{ report.canManage ? '管理点检绩效' : '我的点检绩效' }}</text>
				<text class="range">{{ report.startDate || startDate }} 至 {{ report.endDate || endDate }}</text>
			</view>
			<view class="hero-badge"><text>{{ activePeriodLabel }}</text><text>统计周期</text></view>
		</view>

		<view class="filter-card">
			<text class="section-title">查询条件</text>
			<view class="quick-periods">
				<view
					v-for="item in quickPeriods"
					:key="item.key"
					:class="['period-chip', { active: activePeriod === item.key }]"
					@click="applyPeriod(item.key)"
				>{{ item.label }}</view>
			</view>
			<view class="date-row">
				<picker mode="date" :value="startDate" @change="changeStartDate">
					<view class="picker-box"><text>开始日期</text><text>{{ startDate }}</text></view>
				</picker>
				<picker mode="date" :value="endDate" @change="changeEndDate">
					<view class="picker-box"><text>结束日期</text><text>{{ endDate }}</text></view>
				</picker>
			</view>

			<view v-if="report.canManage" class="manager-filters">
				<text class="scope-tip">可查看本部门及下属部门</text>
				<picker :range="organizationLabels" :value="organizationIndex" @change="changeOrganization">
					<view class="selector"><text>部门筛选</text><text>{{ organizationLabels[organizationIndex] || '全部部门' }} ›</text></view>
				</picker>
				<picker :range="employeeLabels" :value="employeeIndex" @change="changeEmployee">
					<view class="selector"><text>员工筛选</text><text>{{ employeeLabels[employeeIndex] || '全部员工' }} ›</text></view>
				</picker>
			</view>
			<button class="query-button" :loading="loading" @click="load">查询</button>
		</view>

		<view v-if="error" class="error" @click="load">{{ error }} · 点击重试</view>

		<view class="summary-card">
			<view class="card-head">
				<view><text class="section-title">计划考核</text><text class="section-subtitle">按点检任务统计，扫码直检不纳入计划考核</text></view>
				<text class="rate tappable" @click="openDetails('COMPLETED')">{{ completionRate }}%</text>
			</view>
			<view class="metrics">
				<view class="tappable" @click="openDetails('DUE')"><text>{{ due }}</text><text>应检任务</text></view>
				<view class="tappable" @click="openDetails('COMPLETED')"><text class="green">{{ completed }}</text><text>完成任务</text></view>
				<view class="tappable" @click="openDetails('PENDING')"><text class="orange">{{ pending }}</text><text>待完成</text></view>
				<view class="tappable" @click="openDetails('OVERDUE')"><text class="red">{{ overdue }}</text><text>已逾期</text></view>
			</view>
			<view class="secondary-metrics">
				<view class="tappable" @click="openDetails('ON_TIME')"><text>{{ onTime }}</text><text>按期完成</text></view>
				<view class="tappable" @click="openDetails('LATE')"><text>{{ late }}</text><text>逾期完成</text></view>
				<view class="tappable abnormal-metric" @click="openDetails('ABNORMAL')"><text>{{ abnormal }}</text><text>异常任务</text></view>
				<view class="tappable" @click="openDetails('ON_TIME')"><text>{{ onTimeRate }}%</text><text>按期率</text></view>
			</view>
			<text class="detail-hint">点击任一指标查看任务清单，再点击任务查看项目明细</text>
		</view>

		<view class="quick-card">
			<view class="card-head"><view><text class="section-title">扫码直检</text><text class="section-subtitle">仅展示现场登记，不计入绩效排名</text></view><text class="pill">{{ quick.completedTaskCount || 0 }} 次</text></view>
			<view class="quick-metrics">
				<view class="tappable" @click="openDetails('QUICK')"><text>{{ quick.completedTaskCount || 0 }}</text><text>登记任务</text></view>
				<view class="tappable" @click="openDetails('QUICK')"><text>{{ quick.equipmentCovered || 0 }}</text><text>覆盖设备</text></view>
				<view class="tappable abnormal-metric" @click="openDetails('QUICK_ABNORMAL')"><text>{{ quick.abnormalTaskCount || 0 }}</text><text>异常任务</text></view>
			</view>
		</view>

		<view class="abnormal-card">
			<view class="card-head"><view><text class="section-title">异常统计</text><text class="section-subtitle">按存在异常的任务统计，可继续下钻点检项目</text></view><text class="abnormal-total">{{ abnormal + number(quick.abnormalTaskCount) }}</text></view>
			<view class="abnormal-summary">
				<view class="tappable" @click="openDetails('ABNORMAL')"><text>{{ abnormal }}</text><text>计划异常任务</text></view>
				<view class="tappable" @click="openDetails('QUICK_ABNORMAL')"><text>{{ quick.abnormalTaskCount || 0 }}</text><text>扫码异常任务</text></view>
			</view>
		</view>

		<template v-if="report.canManage">
			<view class="ranking-card">
				<view class="card-head"><view><text class="section-title">Top员工</text><text class="section-subtitle">按完成任务数排名</text></view><text class="pill">前 {{ topEmployees.length }} 名</text></view>
				<view v-if="topEmployees.length" class="ranking-list">
					<view v-for="(item, index) in topEmployees" :key="item.userId" class="ranking-row tappable" @click="openEmployeeDetails(item, 'COMPLETED')">
						<text :class="['rank', { top: index < 3 }]">{{ index + 1 }}</text>
						<view class="rank-person"><text>{{ item.userName }}</text><text>{{ item.organizationName }}</text></view>
						<view class="rank-value"><text>{{ item.completedTaskCount }}</text><text>完成任务数 · 异常 {{ item.abnormalTaskCount || 0 }}</text></view>
					</view>
				</view>
				<text v-else class="empty">当前条件下暂无已完成项目</text>
			</view>

			<view class="performance-card">
				<text class="section-title">部门绩效</text>
				<view v-if="organizationPerformance.length" class="performance-list">
					<view v-for="item in organizationPerformance" :key="item.organizationId" class="performance-row tappable" @click="openOrganizationDetails(item)">
						<view class="performance-name"><text>{{ item.organizationName }}</text><text>应检 {{ item.dueTaskCount }} 个任务</text></view>
						<view class="performance-values"><text>{{ item.completedTaskCount }} 完成</text><text>{{ item.overdueTaskCount }} 逾期</text><text class="abnormal-text">{{ item.abnormalTaskCount || 0 }} 异常</text><text>{{ itemRate(item) }}%</text></view>
					</view>
				</view>
				<text v-else class="empty">当前条件下暂无部门数据</text>
			</view>

			<view class="performance-card">
				<text class="section-title">个人绩效</text>
				<view v-if="employeePerformance.length" class="performance-list">
					<view v-for="item in employeePerformance" :key="item.userId || 'unassigned'" class="performance-row tappable" @click="openEmployeeDetails(item, 'DUE')">
						<view class="performance-name"><text>{{ item.userName }}</text><text>{{ item.organizationName }} · 应检 {{ item.dueTaskCount }} 个任务</text></view>
						<view class="performance-values"><text>{{ item.completedTaskCount }} 完成</text><text>{{ unfinished(item) }} 未完成</text><text class="abnormal-text">{{ item.abnormalTaskCount || 0 }} 异常</text><text>{{ itemRate(item) }}%</text></view>
					</view>
				</view>
				<text v-else class="empty">当前条件下暂无个人数据</text>
			</view>
		</template>

		<view class="rules-card">
			<text class="section-title">统计口径</text>
			<text>已完成任务计入实际提交人的绩效；未完成任务计入当前主责任人；扫码直检只展示、不参与计划考核；取消和作废任务不统计。</text>
		</view>

		<view v-if="detailVisible" class="detail-mask" @click.self="closeDetails">
			<view class="detail-panel">
				<view v-if="selectedTask" class="detail-back" @click="backToTaskList">‹ 返回任务清单</view>
				<view class="detail-header"><view><text class="section-title">{{ selectedTask ? selectedTask.taskCode + ' · 项目明细' : detailTitle }}</text><text class="section-subtitle">共 {{ selectedTask ? itemTotal : taskTotal }} {{ selectedTask ? '个项目' : '个任务' }} · {{ startDate }} 至 {{ endDate }}</text></view><text class="detail-close" @click="closeDetails">×</text></view>
				<scroll-view scroll-y class="detail-scroll">
					<view v-if="detailError" class="detail-error" @click="selectedTask ? loadTaskItems(true) : loadTasks(true)">{{ detailError }} · 点击重试</view>
					<template v-if="!selectedTask">
						<view v-for="task in taskRows" :key="task.taskId" class="detail-row tappable" @click="openTaskItems(task)">
							<view class="detail-row-head"><text>{{ task.taskCode }}</text><text :class="['detail-status', detailTone(task)]">{{ detailStatusLabel(task) }}</text></view>
							<text class="detail-device">{{ task.equipmentName }}（{{ task.equipmentCode }}）</text>
							<text class="detail-task">{{ sourceLabel(task.sourceType) }} · {{ task.schemeName || '未命名方案' }}</text>
							<text class="detail-meta">{{ task.organizationName }} · {{ task.attributedUserName || '未分配' }} · 截止 {{ formatTime(task.dueTime) }}</text>
							<text class="task-summary">共 {{ task.itemCount }} 个项目 · 已完成 {{ task.completedItemCount }} · 异常 {{ task.abnormalItemCount }}　›</text>
						</view>
					</template>
					<template v-else>
					<view v-for="item in itemRows" :key="`${item.taskId}-${item.taskItemId}`" class="detail-row">
						<view class="detail-row-head"><text>{{ item.itemName }}</text><text :class="['detail-status', detailTone(item)]">{{ detailStatusLabel(item) }}</text></view>
						<text class="detail-task">{{ item.taskCode }} · {{ sourceLabel(item.sourceType) }} · {{ item.schemeName || '未命名方案' }}</text>
						<text class="detail-device">{{ item.equipmentName }}（{{ item.equipmentCode }}）</text>
						<text class="detail-meta">{{ item.organizationName }} · {{ item.attributedUserName || '未分配' }} · 截止 {{ formatTime(item.dueTime) }}</text>
						<view v-if="item.abnormal" class="detail-abnormal">
							<text>{{ item.abnormalCode }} · {{ item.abnormalTitle }}</text>
							<text>异常说明：{{ item.abnormalDescription || '未填写' }}</text>
							<text>异常状态：{{ abnormalStatusLabel(item.abnormalStatus) }}</text>
						</view>
					</view>
					</template>
					<text v-if="!detailLoading && !(selectedTask ? itemRows.length : taskRows.length) && !detailError" class="empty">当前条件下暂无明细</text>
					<button v-if="(selectedTask ? itemRows.length < itemTotal : taskRows.length < taskTotal)" class="load-more" :loading="detailLoading" @click="selectedTask ? loadTaskItems(false) : loadTasks(false)">加载更多</button>
					<text v-else-if="detailLoading" class="detail-loading">正在加载…</text>
				</scroll-view>
			</view>
		</view>

		<AppBottomNav active="report" />
	</view>
</template>

<script setup>
	import { computed, ref } from 'vue'
	import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app'
	import { mobileApi } from '../../api/mobile.js'
	import { ApiError, errorMessage } from '../../utils/errors.js'
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
	const selectedOrganizationId = ref(null)
	const selectedUserId = ref(null)
	const report = ref(emptyReport())
	const loading = ref(false)
	const error = ref('')
	const detailVisible = ref(false)
	const detailLoading = ref(false)
	const detailError = ref('')
	const taskRows = ref([])
	const taskTotal = ref(0)
	const taskPage = ref(1)
	const selectedTask = ref(null)
	const itemRows = ref([])
	const itemTotal = ref(0)
	const itemPage = ref(1)
	const detailMetric = ref('DUE')
	const detailTitle = ref('任务清单')
	const detailOrganizationId = ref(null)
	const detailUserId = ref(null)
	let loadSequence = 0
	const metricLabels = Object.freeze({
		DUE: '应检任务清单',
		COMPLETED: '完成任务清单',
		PENDING: '待完成任务清单',
		OVERDUE: '逾期未完成任务',
		ON_TIME: '按期完成任务',
		LATE: '逾期完成任务',
		ABNORMAL: '计划异常任务',
		QUICK: '扫码直检任务',
		QUICK_ABNORMAL: '扫码异常任务'
	})

	const summary = computed(() => report.value.summary || {})
	const quick = computed(() => report.value.quickInspection || {})
	const due = computed(() => number(summary.value.dueTaskCount))
	const completed = computed(() => number(summary.value.completedTaskCount))
	const onTime = computed(() => number(summary.value.onTimeTaskCount))
	const late = computed(() => number(summary.value.lateTaskCount))
	const pending = computed(() => number(summary.value.pendingTaskCount))
	const overdue = computed(() => number(summary.value.overdueTaskCount))
	const abnormal = computed(() => number(summary.value.abnormalTaskCount))
	const completionRate = computed(() => rate(completed.value, due.value))
	const onTimeRate = computed(() => rate(onTime.value, completed.value))
	const topEmployees = computed(() => report.value.topEmployees || [])
	const organizationPerformance = computed(() => report.value.organizationPerformance || [])
	const employeePerformance = computed(() => report.value.employeePerformance || [])
	const organizations = computed(() => report.value.organizations || [])
	const employees = computed(() => report.value.employees || [])
	const visibleOrganizationIds = computed(() => {
		if (!selectedOrganizationId.value) return new Set(organizations.value.map((item) => item.organizationId))
		const ids = new Set([selectedOrganizationId.value])
		let changed = true
		while (changed) {
			changed = false
			organizations.value.forEach((item) => {
				if (ids.has(item.parentId) && !ids.has(item.organizationId)) {
					ids.add(item.organizationId)
					changed = true
				}
			})
		}
		return ids
	})
	const visibleEmployees = computed(() => employees.value.filter(
		(item) => visibleOrganizationIds.value.has(item.organizationId)
	))
	const organizationLabels = computed(() => ['全部部门', ...organizations.value.map((item) => item.organizationName)])
	const employeeLabels = computed(() => ['全部员工', ...visibleEmployees.value.map((item) => `${item.realName}（${item.username}）`)])
	const organizationIndex = computed(() => Math.max(0, organizations.value.findIndex((item) => item.organizationId === selectedOrganizationId.value) + 1))
	const employeeIndex = computed(() => Math.max(0, visibleEmployees.value.findIndex((item) => item.userId === selectedUserId.value) + 1))
	const activePeriodLabel = computed(() => quickPeriods.find((item) => item.key === activePeriod.value)?.label || '自定义')

	onLoad(load)
	onPullDownRefresh(async () => {
		try { await load() } finally { uni.stopPullDownRefresh() }
	})

	function emptyReport() {
		return {
			canManage: false,
			summary: {},
			quickInspection: {},
			organizations: [],
			employees: [],
			topEmployees: [],
			organizationPerformance: [],
			employeePerformance: []
		}
	}

	function number(value) {
		return Number(value || 0)
	}

	function rate(value, total) {
		return total ? Math.min(100, Math.round(number(value) * 100 / number(total))) : 0
	}

	function itemRate(item) {
		return rate(item.completedTaskCount, item.dueTaskCount)
	}

	function unfinished(item) {
		return number(item.pendingTaskCount) + number(item.overdueTaskCount)
	}

	function openDetails(metric, options = {}) {
		detailMetric.value = metric
		detailTitle.value = options.title || metricLabels[metric] || '任务清单'
		detailOrganizationId.value = options.organizationId === undefined
			? selectedOrganizationId.value : options.organizationId
		detailUserId.value = options.userId === undefined ? selectedUserId.value : options.userId
		selectedTask.value = null
		detailVisible.value = true
		loadTasks(true)
	}

	function openOrganizationDetails(item) {
		openDetails('DUE', {
			organizationId: item.organizationId,
			userId: null,
			title: `${item.organizationName} · 任务清单`
		})
	}

	function openEmployeeDetails(item, metric = 'DUE') {
		openDetails(metric, {
			organizationId: null,
			userId: item.userId || null,
			title: `${item.userName} · ${metricLabels[metric] || '任务清单'}`
		})
	}

	function closeDetails() {
		detailVisible.value = false
		selectedTask.value = null
	}

	async function loadTasks(reset = false) {
		if (detailLoading.value) return
		if (reset) {
			taskPage.value = 1
			taskRows.value = []
			taskTotal.value = 0
		}
		detailLoading.value = true
		detailError.value = ''
		try {
			const result = await mobileApi.inspectionPerformanceTasks({
				startDate: startDate.value,
				endDate: endDate.value,
				metric: detailMetric.value,
				page: taskPage.value,
				pageSize: 20,
				...(detailOrganizationId.value ? { organizationId: detailOrganizationId.value } : {}),
				...(detailUserId.value ? { userId: detailUserId.value } : {})
			})
			const rows = result?.records || []
			taskRows.value = reset ? rows : [...taskRows.value, ...rows]
			taskTotal.value = number(result?.total)
			taskPage.value += 1
		} catch (cause) {
			detailError.value = errorMessage(cause, '点检任务清单加载失败')
		} finally {
			detailLoading.value = false
		}
	}

	function openTaskItems(task) {
		selectedTask.value = task
		loadTaskItems(true)
	}

	function backToTaskList() {
		selectedTask.value = null
		itemRows.value = []
		itemTotal.value = 0
	}

	async function loadTaskItems(reset = false) {
		if (!selectedTask.value || detailLoading.value) return
		if (reset) {
			itemPage.value = 1
			itemRows.value = []
			itemTotal.value = 0
		}
		detailLoading.value = true
		detailError.value = ''
		try {
			const result = await mobileApi.inspectionPerformanceTaskItems(selectedTask.value.taskId, {
				startDate: startDate.value,
				endDate: endDate.value,
				page: itemPage.value,
				pageSize: 50
			})
			const rows = result?.records || []
			itemRows.value = reset ? rows : [...itemRows.value, ...rows]
			itemTotal.value = number(result?.total)
			itemPage.value += 1
		} catch (cause) {
			detailError.value = errorMessage(cause, '点检项目明细加载失败')
		} finally {
			detailLoading.value = false
		}
	}

	function formatTime(value) {
		return value ? String(value).replace('T', ' ').slice(0, 19) : '未设置'
	}

	function sourceLabel(sourceType) {
		return sourceType === 'QUICK_ENTRY' ? '扫码直检' : '计划任务'
	}

	function detailStatusLabel(item) {
		return ({ ON_TIME: '按期完成', LATE: '逾期完成', OVERDUE: '已逾期', PENDING: '待完成', QUICK: '扫码登记' })[item.timeliness] || item.taskStatus || '未知'
	}

	function detailTone(item) {
		return ({ ON_TIME: 'success', LATE: 'warning', OVERDUE: 'danger', PENDING: 'muted', QUICK: 'success' })[item.timeliness] || 'muted'
	}

	function abnormalStatusLabel(status) {
		return ({ OPEN: '待处理', PROCESSING: '处理中', PENDING_VERIFY: '待验证', CLOSED: '已关闭' })[status] || status || '未知'
	}

	function changeStartDate(event) {
		startDate.value = event.detail.value
		activePeriod.value = 'custom'
	}

	function changeEndDate(event) {
		endDate.value = event.detail.value
		activePeriod.value = 'custom'
	}

	function changeOrganization(event) {
		const index = Number(event.detail.value || 0)
		selectedOrganizationId.value = index ? organizations.value[index - 1]?.organizationId || null : null
		selectedUserId.value = null
		load()
	}

	function changeEmployee(event) {
		const index = Number(event.detail.value || 0)
		selectedUserId.value = index ? visibleEmployees.value[index - 1]?.userId || null : null
		load()
	}

	function applyPeriod(period) {
		const periodRange = reportPeriodRange(period)
		activePeriod.value = period
		startDate.value = periodRange.startDate
		endDate.value = periodRange.endDate
		load()
	}

	function adaptPersonalReport(personal) {
		return {
			...emptyReport(),
			startDate: personal.startDate,
			endDate: personal.endDate,
			summary: {
				dueTaskCount: personal.planDue,
				completedTaskCount: personal.planCompleted,
				onTimeTaskCount: Math.max(0, number(personal.planCompleted) - number(personal.planOverdue)),
				lateTaskCount: 0,
				pendingTaskCount: Math.max(0, number(personal.planDue) - number(personal.planCompleted) - number(personal.planOverdue)),
				overdueTaskCount: personal.planOverdue,
				abnormalTaskCount: personal.abnormal
			},
			quickInspection: {
				completedTaskCount: personal.quickRegistered,
				completedItemCount: personal.quickRegistered,
				equipmentCovered: personal.equipmentCovered,
				abnormalTaskCount: personal.abnormal
			}
		}
	}

	async function load() {
		if (endDate.value < startDate.value) {
			error.value = '结束日期不能早于开始日期'
			return
		}
		const sequence = ++loadSequence
		loading.value = true
		error.value = ''
		const query = {
			startDate: startDate.value,
			endDate: endDate.value,
			...(selectedOrganizationId.value ? { organizationId: selectedOrganizationId.value } : {}),
			...(selectedUserId.value ? { userId: selectedUserId.value } : {})
		}
		try {
			let result
			try {
				result = await mobileApi.inspectionPerformanceReport(query)
			} catch (cause) {
				if (!(cause instanceof ApiError) || cause.statusCode !== 404) throw cause
				result = adaptPersonalReport(await mobileApi.personalInspectionReport({
					startDate: startDate.value,
					endDate: endDate.value
				}))
			}
			if (sequence === loadSequence) report.value = result || emptyReport()
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
	.eyebrow, .title, .range, .hero-badge text, .section-title, .section-subtitle { display: block; }
	.eyebrow { color: #78d2a4; font-size: 19rpx; font-weight: 800; letter-spacing: 2rpx; }
	.title { margin-top: 10rpx; font-size: 36rpx; font-weight: 850; }
	.range { margin-top: 9rpx; font-size: 21rpx; opacity: .72; }
	.hero-badge { min-width: 112rpx; padding: 18rpx 12rpx; border: 1rpx solid rgba(255,255,255,.24); border-radius: 18rpx; background: rgba(255,255,255,.08); text-align: center; }
	.hero-badge text:first-child { font-size: 27rpx; font-weight: 800; }
	.hero-badge text:last-child { margin-top: 5rpx; font-size: 18rpx; opacity: .68; }
	.filter-card, .summary-card, .quick-card, .abnormal-card, .ranking-card, .performance-card, .rules-card { margin-top: 20rpx; padding: 28rpx; border-radius: 22rpx; background: #fff; box-shadow: 0 10rpx 32rpx rgba(25,53,42,.05); }
	.section-title { color: #264236; font-size: 29rpx; font-weight: 800; }
	.section-subtitle { margin-top: 6rpx; color: #8a9690; font-size: 20rpx; line-height: 1.5; }
	.quick-periods { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12rpx; margin-top: 20rpx; }
	.period-chip { padding: 17rpx 8rpx; border: 2rpx solid #dfe8e3; border-radius: 14rpx; color: #65736c; font-size: 23rpx; text-align: center; }
	.period-chip.active { border-color: var(--brand-primary, #1c7d50); color: #fff; background: var(--brand-primary, #1c7d50); font-weight: 700; }
	.date-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14rpx; margin-top: 18rpx; }
	.picker-box { padding: 16rpx 12rpx; border: 2rpx solid #dfe8e3; border-radius: 14rpx; }
	.picker-box text { display: block; color: #264236; font-size: 22rpx; text-align: center; }
	.picker-box text:first-child { margin-bottom: 6rpx; color: #8b9690; font-size: 18rpx; }
	.manager-filters { margin-top: 20rpx; }
	.scope-tip { display: block; margin-bottom: 10rpx; color: var(--brand-primary, #1c7d50); font-size: 20rpx; }
	.selector { display: flex; justify-content: space-between; margin-top: 10rpx; padding: 20rpx; border: 2rpx solid #dfe8e3; border-radius: 14rpx; color: #506159; font-size: 22rpx; }
	.selector text:last-child { max-width: 68%; color: #264236; font-weight: 700; text-align: right; }
	.query-button { margin-top: 20rpx; color: #fff; background: var(--brand-primary, #1c7d50); font-size: 24rpx; }
	.card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16rpx; }
	.rate { color: var(--brand-primary, #1c7d50); font-size: 38rpx; font-weight: 900; }
	.metrics, .secondary-metrics { display: grid; grid-template-columns: repeat(4, 1fr); margin-top: 24rpx; }
	.metrics view, .secondary-metrics view, .quick-metrics view { text-align: center; }
	.metrics text, .secondary-metrics text, .quick-metrics text { display: block; color: #31483e; font-size: 32rpx; font-weight: 800; }
	.metrics text:last-child, .secondary-metrics text:last-child, .quick-metrics text:last-child { margin-top: 7rpx; color: #89938e; font-size: 19rpx; font-weight: 400; }
	.secondary-metrics { padding-top: 20rpx; border-top: 1rpx solid #edf1ef; }
	.secondary-metrics text { font-size: 27rpx; }
	.tappable { position: relative; border-radius: 12rpx; transition: opacity .15s; }
	.tappable:active { opacity: .55; }
	.detail-hint { display: block; margin-top: 18rpx; color: var(--brand-primary, #1c7d50); font-size: 18rpx; text-align: center; }
	.abnormal-metric text:first-child, .abnormal-text { color: var(--brand-accent, #c4000a) !important; }
	.quick-metrics { display: grid; grid-template-columns: repeat(3, 1fr); margin-top: 24rpx; }
	.abnormal-total { color: var(--brand-accent, #c4000a); font-size: 40rpx; font-weight: 900; }
	.abnormal-summary { display: grid; grid-template-columns: 1fr 1fr; gap: 16rpx; margin-top: 24rpx; }
	.abnormal-summary view { padding: 22rpx; border: 1rpx solid #f0d7d9; border-radius: 16rpx; background: #fff7f7; text-align: center; }
	.abnormal-summary text { display: block; color: var(--brand-accent, #c4000a); font-size: 32rpx; font-weight: 850; }
	.abnormal-summary text:last-child { margin-top: 7rpx; color: #8d7375; font-size: 20rpx; font-weight: 400; }
	.pill { padding: 8rpx 14rpx; border-radius: 999rpx; color: #176c46; background: #e6f4ec; font-size: 19rpx; white-space: nowrap; }
	.ranking-list, .performance-list { margin-top: 18rpx; }
	.ranking-row { display: grid; grid-template-columns: 48rpx 1fr auto; align-items: center; gap: 12rpx; padding: 18rpx 0; border-bottom: 1rpx solid #edf1ef; }
	.rank { display: flex; width: 40rpx; height: 40rpx; align-items: center; justify-content: center; border-radius: 50%; color: #728078; background: #edf2ef; font-size: 20rpx; font-weight: 800; }
	.rank.top { color: #fff; background: var(--brand-primary, #1c7d50); }
	.rank-person text, .rank-value text, .performance-name text { display: block; }
	.rank-person text:first-child, .performance-name text:first-child { color: #294238; font-size: 24rpx; font-weight: 800; }
	.rank-person text:last-child, .performance-name text:last-child { margin-top: 5rpx; color: #8a9690; font-size: 19rpx; }
	.rank-value { text-align: right; }
	.rank-value text:first-child { color: var(--brand-primary, #1c7d50); font-size: 30rpx; font-weight: 900; }
	.rank-value text:last-child { color: #89938e; font-size: 16rpx; }
	.performance-row { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; padding: 19rpx 0; border-bottom: 1rpx solid #edf1ef; }
	.performance-name { flex: 1; }
	.performance-values { display: flex; gap: 12rpx; flex-wrap: wrap; justify-content: flex-end; color: #52635b; font-size: 19rpx; }
	.performance-values text:last-child { color: var(--brand-primary, #1c7d50); font-weight: 800; }
	.rules-card { margin-bottom: 10rpx; }
	.rules-card > text:last-child { display: block; margin-top: 14rpx; color: #7f8c86; font-size: 21rpx; line-height: 1.75; }
	.empty { display: block; padding: 34rpx 0 10rpx; color: #9aa39e; font-size: 21rpx; text-align: center; }
	.green { color: var(--brand-primary, #1c7d50) !important; }
	.orange { color: #d68b11 !important; }
	.red { color: var(--brand-accent, #c4000a) !important; }
	.error { margin-top: 20rpx; padding: 30rpx; border-radius: 18rpx; color: #a00008; background: #fff0f0; text-align: center; }
	.detail-mask { position: fixed; z-index: 1100; inset: 0; display: flex; align-items: flex-end; background: rgba(12, 28, 22, .52); }
	.detail-panel { width: 100%; max-height: 86vh; padding: 28rpx 26rpx calc(28rpx + env(safe-area-inset-bottom)); border-radius: 30rpx 30rpx 0 0; background: #f5f8f6; box-sizing: border-box; }
	.detail-back { display: inline-block; margin: 0 4rpx 14rpx; color: var(--brand-primary, #1c7d50); font-size: 22rpx; font-weight: 700; }
	.detail-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20rpx; padding: 0 4rpx 20rpx; }
	.detail-close { display: flex; width: 54rpx; height: 54rpx; align-items: center; justify-content: center; color: #486057; font-size: 44rpx; }
	.detail-scroll { height: 68vh; }
	.detail-row { margin-bottom: 16rpx; padding: 24rpx; border-radius: 18rpx; background: #fff; box-shadow: 0 6rpx 20rpx rgba(25,53,42,.05); }
	.detail-row-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16rpx; }
	.detail-row-head > text:first-child { flex: 1; color: #263f35; font-size: 25rpx; font-weight: 800; }
	.detail-status { padding: 6rpx 11rpx; border-radius: 999rpx; color: #65736c; background: #edf2ef; font-size: 18rpx; white-space: nowrap; }
	.detail-status.success { color: #176c46; background: #e6f4ec; }
	.detail-status.warning { color: #9a6408; background: #fff3d9; }
	.detail-status.danger { color: #a00008; background: #ffe7e8; }
	.detail-task, .detail-device, .detail-meta { display: block; margin-top: 10rpx; color: #718078; font-size: 19rpx; line-height: 1.5; }
	.detail-device { color: #344b41; font-size: 22rpx; font-weight: 700; }
	.task-summary { display: block; margin-top: 14rpx; color: var(--brand-primary, #1c7d50); font-size: 20rpx; font-weight: 700; }
	.detail-abnormal { margin-top: 16rpx; padding: 17rpx; border-left: 6rpx solid var(--brand-accent, #c4000a); border-radius: 10rpx; background: #fff2f2; }
	.detail-abnormal text { display: block; color: #8d2227; font-size: 20rpx; line-height: 1.55; }
	.detail-abnormal text:first-child { font-weight: 800; }
	.detail-error { margin-bottom: 16rpx; padding: 24rpx; border-radius: 14rpx; color: #a00008; background: #fff0f0; text-align: center; }
	.load-more { margin: 20rpx auto 40rpx; color: #fff; background: var(--brand-primary, #1c7d50); font-size: 22rpx; }
	.detail-loading { display: block; padding: 28rpx; color: #85918b; font-size: 20rpx; text-align: center; }
</style>
