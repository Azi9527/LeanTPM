<template>
	<view class="page" :class="{ expanded: managementExpanded }" :style="$brandTheme()">
		<view v-if="loading && !context" class="loading">正在读取设备信息…</view>
		<view v-if="error && !context" class="error-card">
			<text class="error-title">无法查看设备</text><text>{{ error }}</text>
			<button @click="load">重新加载</button>
		</view>

		<template v-if="context">
			<view class="hero">
				<view class="hero-line"><text class="code">{{ context.equipment.equipmentCode }}</text><text class="status" :style="{ backgroundColor: context.equipment.statusColor || undefined }">{{ context.equipment.statusName }}</text></view>
				<text class="equipment-name">{{ context.equipment.equipmentName }}</text>
				<text class="category">{{ context.equipment.categoryName }}</text>
			</view>

			<view class="detail-card">
				<view class="section-head management-head" @click="managementExpanded = !managementExpanded">
					<text class="section-title">管理信息</text>
					<text class="collapse-label">{{ managementExpanded ? '收起' : '展开' }}</text>
				</view>
				<template v-if="managementExpanded">
					<view v-for="row in managementRows" :key="row.label"><text>{{ row.label }}</text><text>{{ row.value }}</text></view>
				</template>
			</view>

			<view class="action-card compact-action">
				<text class="section-title">现场作业</text>
				<button class="primary report-primary" :loading="quickReporting" :disabled="!context.inspectionSchemes.length || !canDirectReport" @click="openQuickReport">直接点检报告</button>
				<text v-if="!context.inspectionSchemes.length" class="tip">当前没有已启用且已发布的点检模板</text>
				<text v-else-if="!canDirectReport" class="tip">当前账号没有点检执行权限，请联系班组长或管理员</text>
			</view>

			<view class="task-card">
				<view class="section-head"><text class="section-title">我的可执行点检</text><text>{{ taskPreview.total }}</text></view>
				<view v-for="task in taskPreview.visible" :key="`${task.workflowType}-${task.taskId}`" class="task" @click="openTask(task)">
					<text :class="['task-type', { maintenance: task.workflowType !== 'INSPECTION' }]">{{ task.workflowType === 'INSPECTION' ? '点检' : '保养' }}</text>
					<view><text class="task-code">{{ task.taskCode }}</text><text class="task-name">{{ task.schemeName }}</text><text class="due">截止 {{ dateTime(task.dueTime) }}</text></view>
					<text>›</text>
				</view>
				<text v-if="!taskPreview.total" class="empty compact">当前没有分派给你的未关闭任务</text>
				<text v-if="taskPreview.hasMore" class="all-tasks" @click="openAllTasks">查看全部 {{ taskPreview.total }} 项任务 ›</text>
			</view>
		</template>

		<view v-if="quickVisible && context" class="mask" @click.self="quickVisible = false">
			<view class="sheet quick-sheet">
				<view class="sheet-head"><text>直接点检报告</text><text @click="quickVisible = false">×</text></view>
				<view class="quick-equipment"><text>{{ context.equipment.equipmentName }}</text><text>{{ context.equipment.equipmentCode }}</text></view>
				<template v-if="context.inspectionSchemes.length > 1">
					<text class="label">选择点检模板 *</text>
					<picker :range="schemeLabels" :value="quickSchemeIndex" @change="changeQuickScheme"><view class="picker">{{ schemeLabels[quickSchemeIndex] || '请选择' }}<text>⌄</text></view></picker>
				</template>
				<view v-else class="single-scheme"><text>点检模板</text><text>{{ schemeLabels[0] }}</text></view>
				<view v-if="matchingTodayInspection" class="duplicate-warning">
					<text>今日已有同方案点检记录</text>
					<text>{{ matchingTodayInspection.taskCode }} · {{ todayInspectionStatus(matchingTodayInspection.taskStatus) }}</text>
					<text>{{ matchingTodayInspection.executorName || '尚未提交' }}{{ matchingTodayInspection.completedTime ? ` · ${dateTime(matchingTodayInspection.completedTime)}` : '' }}</text>
				</view>
				<view class="quick-note"><text>执行人</text><text>当前登录用户</text><text>计划日期</text><text>今天</text><text>完成规则</text><text>提交报告即完成任务</text></view>
				<button v-if="matchingTodayInspection" class="secondary-action" @click="openTodayInspection">查看/继续今日记录</button>
				<button class="primary submit" :loading="quickReporting" @click="createQuickReport(Boolean(matchingTodayInspection))">{{ matchingTodayInspection ? '仍要新增一条点检登记' : '开始点检报告' }}</button>
				<view class="safe-space" />
			</view>
		</view>

		<view v-if="createVisible && context" class="mask" @click.self="createVisible = false">
			<scroll-view scroll-y class="sheet">
				<view class="sheet-head"><text>创建点检任务</text><text @click="createVisible = false">×</text></view>
				<text class="label">点检方案 *</text>
				<picker :range="schemeLabels" :value="schemeIndex" @change="changeScheme"><view class="picker">{{ schemeLabels[schemeIndex] || '请选择' }}<text>⌄</text></view></picker>

				<text class="label">执行人员 *（可多选）</text>
				<view class="chips">
					<view v-for="user in context.assignees" :key="user.userId" :class="['chip', { selected: createForm.assigneeUserIds.includes(user.userId) }]" @click="toggleAssignee(user.userId)">
						{{ user.realName }}（{{ user.username }}）
					</view>
				</view>

				<text class="label">班组（选填）</text>
				<picker :range="teamLabels" :value="teamIndex" @change="changeTeam"><view class="picker">{{ teamLabels[teamIndex] || '不指定班组' }}<text>⌄</text></view></picker>

				<text class="label">计划日期 *</text>
				<picker mode="date" :value="createForm.plannedDate" @change="changeDate"><view class="picker">{{ createForm.plannedDate }}<text>⌄</text></view></picker>
				<text class="label">截止时间 *</text>
				<picker mode="time" :value="createForm.dueClock" @change="changeDueClock"><view class="picker">{{ createForm.dueClock }}<text>⌄</text></view></picker>

				<text class="label">备注</text>
				<textarea v-model="createForm.remark" class="remark" maxlength="1000" placeholder="可填写临时点检原因" />
				<button class="primary submit" :loading="creating" @click="createTask">创建并进入任务</button>
				<view class="safe-space" />
			</scroll-view>
		</view>
	</view>
</template>

<script setup>
	import { computed, reactive, ref } from 'vue'
	import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app'
	import { inspectionApi } from '../../api/inspection.js'
	import { mobileApi } from '../../api/mobile.js'
	import { navigateTo, routeWithQuery } from '../../constants/routes.js'
	import { can, sessionState } from '../../stores/session.js'
	import { createIdempotencyKey } from '../../utils/idempotency.js'
	import { equipmentScanErrorMessage, errorMessage } from '../../utils/errors.js'
	import { requireEquipmentToken } from '../../utils/equipment-token.js'
	import { equipmentManagementRows, equipmentTaskPreview } from '../../utils/equipment-context.js'
	import { inspectionTaskTarget } from '../../utils/inspection-navigation.js'
	import { rememberEquipment } from '../../stores/recent-equipment.js'

	const token = ref('')
	const context = ref(null)
	const loading = ref(false)
	const error = ref('')
	const managementExpanded = ref(false)
	const createVisible = ref(false)
	const creating = ref(false)
	const quickVisible = ref(false)
	const quickReporting = ref(false)
	const quickSchemeIndex = ref(0)
	const quickKey = ref('')
	const createKey = ref('')
	const schemeIndex = ref(0)
	const teamIndex = ref(0)
	const createForm = reactive({ assigneeUserIds: [], plannedDate: '', dueClock: '23:59', teamCode: '', remark: '设备扫码手工创建' })
	const schemeLabels = computed(() => context.value?.inspectionSchemes?.map((item) => `${item.schemeName}（${item.schemeCode}）`) || [])
	const matchingTodayInspection = computed(() => {
		const scheme = context.value?.inspectionSchemes?.[quickSchemeIndex.value]
		if (!scheme) return null
		return context.value?.todayInspections?.find((item) => item.schemeVersionId === scheme.schemeVersionId) || null
	})
	const teamLabels = computed(() => ['不指定班组'].concat(context.value?.teams?.map((item) => item.teamName) || []))
	const canCreateTask = computed(() => can('inspection:task:create'))
	const canDirectReport = computed(() => can('inspection:task:execute'))
	const managementRows = computed(() => equipmentManagementRows(context.value?.equipment))
	const taskPreview = computed(() => equipmentTaskPreview(
		(context.value?.activeTasks || []).filter((task) => task.workflowType === 'INSPECTION'),
		1
	))

	onLoad((query) => {
		try { token.value = requireEquipmentToken(query?.token) } catch (cause) { error.value = cause.message; return }
		load()
	})
	onPullDownRefresh(async () => { try { await load() } finally { uni.stopPullDownRefresh() } })

	function today() {
		const date = new Date()
		const pad = (value) => String(value).padStart(2, '0')
		return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
	}
	function dateTime(value) { return value ? String(value).replace('T', ' ').slice(0, 16) : '—' }
	function displayValue(value) { return String(value || '').trim() || '未设置' }
	function inspectionTypeLabel(value) { return value === 'PROFESSIONAL' ? '专业点检' : value === 'ROUTINE' ? '日常点检' : displayValue(value) }

	async function load() {
		if (!token.value || loading.value) return
		loading.value = true; error.value = ''
		try {
			context.value = await mobileApi.equipment(token.value)
			rememberEquipment(token.value, context.value?.equipment)
		}
		catch (cause) { error.value = equipmentScanErrorMessage(cause) }
		finally { loading.value = false }
	}

	function openCreate() {
		if (!canCreateTask.value) return uni.showToast({ title: '当前账号无创建任务权限', icon: 'none' })
		if (!context.value?.inspectionSchemes?.length) return
		const myId = sessionState.user?.id
		const me = context.value.assignees.find((item) => item.userId === myId)
		schemeIndex.value = 0; teamIndex.value = 0
		Object.assign(createForm, { assigneeUserIds: myId ? [myId] : [], plannedDate: today(), dueClock: '23:59', teamCode: me?.teamCode || '', remark: '设备扫码手工创建' })
		const matchedTeam = context.value.teams.findIndex((item) => item.teamCode === createForm.teamCode)
		teamIndex.value = matchedTeam >= 0 ? matchedTeam + 1 : 0
		createKey.value = createIdempotencyKey('mobile-create')
		createVisible.value = true
	}
	function openQuickReport() {
		if (!canDirectReport.value) return uni.showToast({ title: '当前账号无点检执行权限', icon: 'none' })
		if (!context.value?.inspectionSchemes?.length) return uni.showToast({ title: '暂无可用点检模板', icon: 'none' })
		quickSchemeIndex.value = 0
		quickKey.value = createIdempotencyKey('mobile-direct-report')
		if (context.value.inspectionSchemes.length === 1 && !matchingTodayInspection.value) {
			createQuickReport(false)
			return
		}
		quickVisible.value = true
	}
	function changeQuickScheme(event) {
		quickSchemeIndex.value = Number(event.detail.value)
		quickKey.value = createIdempotencyKey('mobile-direct-report')
	}
	function todayInspectionStatus(status) {
		return ({ PENDING: '待执行', IN_PROGRESS: '执行中', OVERDUE: '已逾期', PENDING_REVIEW: '已提交', COMPLETED: '已完成' })[status] || status
	}
	function openTodayInspection() {
		const record = matchingTodayInspection.value
		if (!record) return
		quickVisible.value = false
		navigateTo(routeWithQuery('/pages/inspection/detail', { id: record.taskId }))
	}
	async function createQuickReport(allowRepeat = false) {
		const scheme = context.value?.inspectionSchemes?.[quickSchemeIndex.value]
		if (!scheme) return uni.showToast({ title: '请选择点检方案', icon: 'none' })
		if (quickReporting.value) return
		quickReporting.value = true
		try {
			const result = await mobileApi.createInspectionReport(token.value, {
				schemeVersionId: scheme.schemeVersionId,
				remark: '设备扫码直接点检报告',
				allowRepeat
			}, quickKey.value)
			quickVisible.value = false
			uni.showToast({ title: '点检报告已创建', icon: 'success' })
			navigateTo(routeWithQuery('/pages/inspection/detail', { id: result.id }))
		} catch (cause) {
			await load()
			quickVisible.value = true
			uni.showModal({ title: '无法开始点检', content: errorMessage(cause), showCancel: false })
		} finally { quickReporting.value = false }
	}
	function changeScheme(event) { schemeIndex.value = Number(event.detail.value) }
	function changeDate(event) { createForm.plannedDate = event.detail.value }
	function changeDueClock(event) { createForm.dueClock = event.detail.value }
	function changeTeam(event) {
		teamIndex.value = Number(event.detail.value)
		createForm.teamCode = teamIndex.value ? context.value.teams[teamIndex.value - 1]?.teamCode || '' : ''
	}
	function toggleAssignee(id) {
		const index = createForm.assigneeUserIds.indexOf(id)
		if (index >= 0) createForm.assigneeUserIds.splice(index, 1)
		else if (createForm.assigneeUserIds.length < 20) createForm.assigneeUserIds.push(id)
		else uni.showToast({ title: '最多选择 20 人', icon: 'none' })
	}

	async function createTask() {
		const scheme = context.value.inspectionSchemes[schemeIndex.value]
		if (!scheme) return uni.showToast({ title: '请选择点检方案', icon: 'none' })
		if (!createForm.assigneeUserIds.length) return uni.showToast({ title: '至少选择一名执行人', icon: 'none' })
		creating.value = true
		try {
			const start = `${createForm.plannedDate}T00:00:00`
			const due = `${createForm.plannedDate}T${createForm.dueClock}:00`
			const result = await inspectionApi.createTask({
				equipmentId: context.value.equipment.equipmentId,
				schemeVersionId: scheme.schemeVersionId,
				plannedDate: createForm.plannedDate,
				plannedStartTime: start,
				dueTime: due,
				assigneeUserIds: createForm.assigneeUserIds,
				teamCode: createForm.teamCode || null,
				backfill: false,
				remark: createForm.remark || null
			}, createKey.value)
			createVisible.value = false
			uni.showToast({ title: '点检任务已创建', icon: 'success' })
			navigateTo(routeWithQuery('/pages/inspection/detail', { id: result.id }))
		} catch (cause) {
			uni.showModal({ title: '创建失败', content: errorMessage(cause), showCancel: false })
		} finally { creating.value = false }
	}

	function openTask(task) {
		if (task.workflowType !== 'INSPECTION') return uni.showToast({ title: '设备保养尚未开发', icon: 'none' })
		navigateTo(inspectionTaskTarget({
			...task,
			id: task.taskId,
			equipmentId: context.value?.equipment?.equipmentId,
			equipmentName: context.value?.equipment?.equipmentName
		}).url)
	}
	function openAllTasks() { navigateTo('/pages/inspection/index') }
</script>

<style>
	.page { box-sizing: border-box; height: 100vh; overflow: hidden; padding: 14rpx 20rpx 22rpx; background: #f4f7f5; }
	.page.expanded { height: auto; min-height: 100vh; overflow: visible; padding-bottom: 50rpx; }
	.loading, .empty { padding: 70rpx 20rpx; color: #89938e; text-align: center; font-size: 25rpx; }
	.error-card { padding: 40rpx 30rpx; border-radius: 24rpx; color: #7a4a4d; background: #fff; text-align: center; }
	.error-card text { display: block; margin-bottom: 18rpx; }
	.error-title { color: #a00008; font-size: 32rpx; font-weight: 800; }
	.hero { padding: 22rpx 26rpx; border-radius: 22rpx; color: #fff; background: linear-gradient(140deg, #183e30, var(--brand-primary, #1c7d50)); }
	.hero-line { display: flex; align-items: center; justify-content: space-between; }
	.code { font-family: monospace; font-size: 24rpx; opacity: .76; }
	.status { padding: 8rpx 18rpx; border-radius: 24rpx; background: rgba(255,255,255,.18); font-size: 22rpx; }
	.equipment-name, .category { display: block; }
	.equipment-name { margin-top: 10rpx; font-size: 34rpx; font-weight: 800; }
	.category { margin-top: 9rpx; font-size: 23rpx; opacity: .72; }
	.detail-card, .action-card, .task-card, .scheme-card { margin-top: 13rpx; padding: 19rpx 22rpx; border-radius: 20rpx; background: #fff; box-shadow: 0 8rpx 26rpx rgba(25,53,42,.05); }
	.detail-card .section-title { display: block; margin-bottom: 10rpx; }
	.detail-card .management-head { padding-top: 0; cursor: pointer; }
	.detail-card .management-head .section-title { margin-bottom: 0; }
	.detail-card .management-head .collapse-label { color: var(--brand-primary, #1c7d50); }
	.detail-card view { display: flex; justify-content: space-between; gap: 20rpx; padding: 10rpx 0; border-bottom: 1rpx solid #edf1ef; font-size: 22rpx; }
	.detail-card view:last-child { border: 0; }
	.detail-card view text:first-child { color: #88938d; }
	.detail-card view text:last-child { color: #31483e; text-align: right; }
	.section-title { color: #213e32; font-size: 30rpx; font-weight: 750; }
	.action-card button { margin-top: 12rpx; border-radius: 14rpx; font-size: 25rpx; }
	.primary { color: #fff; background: var(--brand-primary, #1c7d50); }
	.report-primary { min-height: 72rpx; font-size: 28rpx !important; font-weight: 800; box-shadow: 0 8rpx 20rpx rgba(28,125,80,.18); }
	.secondary-action { color: var(--brand-primary, #1c7d50); border: 2rpx solid var(--brand-primary, #1c7d50); background: #fff; }
	.disabled { color: #9ca5a0; background: #eef1f0; }
	.tip { display: block; margin-top: 15rpx; color: #b0760e; font-size: 22rpx; }
	.section-head { display: flex; justify-content: space-between; }
	.section-head text:last-child { color: var(--brand-primary, #1c7d50); font-size: 28rpx; font-weight: 800; }
	.scheme-row { display: flex; align-items: center; justify-content: space-between; gap: 20rpx; padding: 22rpx 0; border-top: 1rpx solid #edf1ef; }
	.scheme-row view { min-width: 0; flex: 1; }
	.scheme-row view text { display: block; }
	.scheme-row view text:first-child { color: #294338; font-size: 25rpx; font-weight: 700; }
	.scheme-row view text:last-child { margin-top: 7rpx; color: #84908a; font-size: 21rpx; }
	.scheme-row > text { flex: none; padding: 7rpx 13rpx; border-radius: 18rpx; color: #176f47; background: #e6f5ed; font-size: 20rpx; }
	.empty.compact { display: block; padding: 35rpx 10rpx 12rpx; }
	.task { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: 14rpx; padding: 14rpx 0 8rpx; border-top: 1rpx solid #edf1ef; }
	.all-tasks { display: block; padding-top: 10rpx; color: var(--brand-primary, #1c7d50); text-align: right; font-size: 22rpx; }
	.task-type { padding: 12rpx; border-radius: 12rpx; color: #147145; background: #e5f6ed; font-size: 22rpx; }
	.task-type.maintenance { color: #9e6709; background: #fff3df; }
	.task-code, .task-name, .due { display: block; }
	.task-code { color: #294338; font-size: 25rpx; font-weight: 700; }
	.task-name, .due { margin-top: 5rpx; color: #84908a; font-size: 21rpx; }
	.mask { position: fixed; z-index: 90; top: 0; right: 0; bottom: 0; left: 0; display: flex; align-items: flex-end; background: rgba(10,28,20,.48); }
	.sheet { box-sizing: border-box; width: 100%; max-height: 91vh; padding: 32rpx 30rpx 0; border-radius: 34rpx 34rpx 0 0; background: #fff; }
	.sheet-head { display: flex; justify-content: space-between; color: #213f32; font-size: 32rpx; font-weight: 800; }
	.sheet-head text:last-child { padding: 0 12rpx; font-size: 42rpx; font-weight: 400; }
	.quick-sheet { max-height: 76vh; }
	.quick-equipment { display: flex; align-items: baseline; justify-content: space-between; gap: 18rpx; margin-top: 22rpx; padding: 22rpx; border-radius: 16rpx; background: #edf7f2; }
	.quick-equipment text:first-child { color: #203f31; font-size: 28rpx; font-weight: 800; }
	.quick-equipment text:last-child { color: #718079; font-family: monospace; font-size: 22rpx; }
	.single-scheme { display: grid; grid-template-columns: 150rpx 1fr; gap: 16rpx; margin-top: 22rpx; padding: 20rpx 22rpx; border-radius: 16rpx; background: #f6f8f7; font-size: 23rpx; }
	.single-scheme text:first-child { color: #87928c; }
	.single-scheme text:last-child { color: #30483d; font-weight: 700; text-align: right; }
	.duplicate-warning { margin-top: 20rpx; padding: 20rpx 22rpx; border: 2rpx solid #f2c56e; border-radius: 16rpx; color: #8b5a08; background: #fff7e5; }
	.duplicate-warning text { display: block; font-size: 22rpx; line-height: 1.55; }
	.duplicate-warning text:first-child { font-size: 25rpx; font-weight: 800; }
	.quick-note { display: grid; grid-template-columns: 150rpx 1fr; gap: 14rpx 20rpx; margin-top: 26rpx; padding: 22rpx; border-radius: 16rpx; color: #30483d; background: #f6f8f7; font-size: 23rpx; }
	.quick-note text:nth-child(odd) { color: #87928c; }
	.label { display: block; margin: 28rpx 0 12rpx; color: #46574f; font-size: 24rpx; font-weight: 650; }
	.picker { display: flex; height: 84rpx; align-items: center; justify-content: space-between; padding: 0 22rpx; border: 2rpx solid #dce5e0; border-radius: 16rpx; font-size: 25rpx; }
	.chips { display: flex; flex-wrap: wrap; gap: 12rpx; }
	.chip { padding: 13rpx 17rpx; border: 2rpx solid #dce5e0; border-radius: 12rpx; color: #68766f; font-size: 22rpx; }
	.chip.selected { border-color: var(--brand-primary, #1c7d50); color: #176d46; background: #e9f6ef; }
	.remark { box-sizing: border-box; width: 100%; height: 130rpx; padding: 18rpx; border: 2rpx solid #dce5e0; border-radius: 16rpx; font-size: 24rpx; }
	.submit { margin-top: 32rpx; border-radius: 17rpx; }
	.safe-space { height: calc(34rpx + env(safe-area-inset-bottom)); }
</style>
