<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { FullScreen, Refresh, VideoPause, VideoPlay } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { useVisualizationDashboard } from '@/views/visualization/useVisualizationDashboard'
import { useBranding } from '@/branding/branding'

type Topic = 'STATUS' | 'INSPECTION' | 'MAINTENANCE' | 'OEE'
interface MetricCard { label: string; value: string | number; color: string; hint?: string }

const route = useRoute()
const router = useRouter()
const branding = useBranding()
const root = ref<HTMLElement>()
const now = ref(new Date())
const playback = reactive({ autoTour: true })
const spotlightIndex = ref(0)
const { filters, loading, dashboard, organizations, load } = useVisualizationDashboard()
let clockTimer: number | undefined
let tourTimer: number | undefined

const topic = computed<Topic>(() => {
  if (route.name === 'VisualizationInspection') return 'INSPECTION'
  if (route.name === 'VisualizationMaintenance') return 'MAINTENANCE'
  if (route.name === 'VisualizationOee') return 'OEE'
  return 'STATUS'
})
const title = computed(() => ({
  STATUS: '设备状态监控大屏',
  INSPECTION: '点检运营分析大屏',
  MAINTENANCE: '维保运营分析大屏',
  OEE: 'OEE 效率分析大屏',
})[topic.value])

const metrics = computed<MetricCard[]>(() => {
  const data = dashboard.value
  if (!data) return []
  if (topic.value === 'OEE') {
    const summary = data.oee.summary
    return [
      { label: '综合 OEE', value: percent(summary.oeeRate), color: '#22d3ee' },
      { label: '目标 OEE', value: percent(summary.targetOeeRate), color: '#f59e0b' },
      { label: '时间开动率', value: percent(summary.availabilityRate), color: '#22c55e' },
      { label: '性能开动率', value: percent(summary.performanceRate), color: '#38bdf8' },
      { label: '良品率', value: percent(summary.qualityRate), color: '#a855f7' },
      { label: '低于目标', value: summary.belowTargetCount, color: '#ef4444' },
    ]
  }
  const workflow = topic.value === 'INSPECTION' ? data.inspection : data.maintenance
  const accent = topic.value === 'INSPECTION' ? '#14b8a6' : '#a855f7'
  return [
    { label: '应完成', value: workflow.due, color: '#38bdf8' },
    { label: '已完成', value: workflow.completed, color: '#22c55e' },
    { label: '待执行', value: workflow.pending, color: '#f59e0b' },
    { label: '逾期', value: workflow.overdue, color: '#ef4444' },
    { label: '异常', value: workflow.abnormal, color: '#fb7185' },
    { label: '完成率', value: percent(workflow.completionRate), color: accent },
    { label: '准时率', value: percent(workflow.onTimeRate), color: '#22d3ee' },
  ]
})

const idleEquipmentCount = computed(() => {
  const value = dashboard.value?.core.idle
  if (Number.isFinite(value)) return Number(value)
  return dashboard.value?.statusDistribution.find((item) => item.statusCode === 'IDLE')?.equipmentCount ?? 0
})
const scrappedEquipmentCount = computed(() => {
  const value = dashboard.value?.core.scrapped
  if (Number.isFinite(value)) return Number(value)
  return dashboard.value?.statusDistribution.find((item) => item.statusCode === 'SCRAPPED')?.equipmentCount ?? 0
})
const healthRate = computed(() => {
  const core = dashboard.value?.core
  if (!core?.total) return 0
  return Math.round(((idleEquipmentCount.value + core.running) / core.total) * 1000) / 10
})
const attentionEquipment = computed(() => dashboard.value?.liveEquipment
  .filter((item) => item.longDuration || item.statusCode === 'STOPPED') ?? [])
const currentSpotlightId = computed(() => {
  const rows = dashboard.value?.liveEquipment ?? []
  return rows.length ? rows[spotlightIndex.value % rows.length]?.equipmentId : undefined
})
const generatedTime = computed(() => dashboard.value?.generatedAt
  ? new Date(dashboard.value.generatedAt).toLocaleTimeString('zh-CN', { hour12: false })
  : '--:--:--')
const clock = computed(() => now.value.toLocaleTimeString('zh-CN', { hour12: false }))
const dateText = computed(() => now.value.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', weekday: 'short' }))
const maxOrganizationTotal = computed(() => Math.max(1, ...(dashboard.value?.organizationDistribution.map((item) => item.equipmentCount) ?? [1])))
const activeWorkflow = computed(() => topic.value === 'INSPECTION' ? dashboard.value?.inspection : dashboard.value?.maintenance)
const activeWorkflowTrend = computed(() => dashboard.value?.workflowTrend.filter((item) => item.workflowType === topic.value) ?? [])
const workflowTrendMaximum = computed(() => Math.max(1, ...activeWorkflowTrend.value.flatMap((item) => [item.due, item.completed, item.overdue, item.abnormal])))
const analysisAccent = computed(() => topic.value === 'INSPECTION' ? '#25d6bd' : topic.value === 'MAINTENANCE' ? '#a777ff' : '#35d9ff')
const analysisEnglish = computed(() => topic.value === 'INSPECTION' ? 'INSPECTION COMMAND CENTER' : topic.value === 'MAINTENANCE' ? 'MAINTENANCE COMMAND CENTER' : 'OEE PERFORMANCE CENTER')
const analysisSubtitle = computed(() => topic.value === 'INSPECTION' ? '点检任务全过程监控与异常闭环' : topic.value === 'MAINTENANCE' ? '维保计划执行、逾期风险与设备保障' : '设备综合效率、损失与改善机会追踪')
const workflowName = computed(() => topic.value === 'INSPECTION' ? '点检' : '维保')
const oeeSummary = computed(() => dashboard.value?.oee.summary)
const oeeTrendMaximum = computed(() => Math.max(0.01, ...(dashboard.value?.oee.trend.flatMap((item) => [item.oeeRate, item.targetOeeRate]) ?? [0.01])))

onMounted(() => {
  clockTimer = window.setInterval(() => { now.value = new Date() }, 1000)
  tourTimer = window.setInterval(() => {
    if (playback.autoTour) spotlightIndex.value += 1
  }, 4000)
})
onBeforeUnmount(() => {
  if (clockTimer) window.clearInterval(clockTimer)
  if (tourTimer) window.clearInterval(tourTimer)
})

function percent(value?: number) {
  return value == null ? '—' : `${(Number(value) * 100).toFixed(1)}%`
}
function duration(seconds: number) {
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  if (days) return `${days}天 ${hours}小时`
  return hours ? `${hours}小时 ${minutes}分` : `${minutes}分钟`
}
function openLiveEquipment(row: { equipmentId: number }) {
  router.push({ path: '/equipment/ledger', query: { equipmentId: row.equipmentId } })
}
function openStatus(statusCode?: string) {
  router.push({ path: '/equipment/ledger', query: { currentStatusCode: statusCode } })
}
function drillMetric(label: string) {
  if (topic.value === 'OEE') {
    router.push({ path: '/oee/analysis', query: { startDate: filters.startDate, endDate: filters.endDate } })
    return
  }
  const path = topic.value === 'INSPECTION' ? '/inspection/tasks' : '/maintenance/tasks'
  const query: Record<string, string> = { startDate: filters.startDate, endDate: filters.endDate }
  if (label === '已完成') query.taskStatus = 'COMPLETED'
  if (label === '待执行') query.taskStatus = 'PENDING'
  if (label === '逾期') query.taskStatus = 'OVERDUE'
  if (label === '异常') query.abnormalOnly = 'true'
  router.push({ path, query })
}
function drillTrend(row: { statisticDate: string }) {
  const path = topic.value === 'INSPECTION' ? '/inspection/tasks' : '/maintenance/tasks'
  router.push({ path, query: { startDate: row.statisticDate, endDate: row.statisticDate } })
}
function statusClass(code: string) {
  if (code === 'RUNNING') return 'running'
  if (code === 'STOPPED') return 'stopped'
  if (code === 'IDLE') return 'idle'
  if (code === 'SCRAPPED') return 'scrapped'
  return 'other'
}
function organizationWidth(count: number) {
  return `${Math.max(3, count / maxOrganizationTotal.value * 100)}%`
}
function workflowHeight(value: number) {
  return `${Math.max(value ? 6 : 0, value / workflowTrendMaximum.value * 100)}%`
}
function oeeHeight(value?: number) {
  return `${Math.max(value ? 6 : 0, Number(value ?? 0) / oeeTrendMaximum.value * 100)}%`
}
function rateNumber(value?: number) {
  return Math.max(0, Math.min(100, Number(value ?? 0) * 100))
}
function openOeeAnalysis() {
  router.push({ path: '/oee/analysis', query: { startDate: filters.startDate, endDate: filters.endDate } })
}
function toggleAutoTour() {
  playback.autoTour = !playback.autoTour
}
async function fullscreen() {
  if (!document.fullscreenElement) await root.value?.requestFullscreen()
  else await document.exitFullscreen()
}
</script>

<template>
  <main v-if="topic === 'STATUS'" ref="root" class="status-command" v-loading="loading">
    <header class="command-header">
      <div class="command-brand">
        <span class="live-signal" />
        <div><span>{{ branding.shortName }} · EQUIPMENT OPERATION CENTER</span><h1>设备实时态势指挥屏</h1></div>
      </div>
      <div class="command-filters">
        <el-segmented v-model="filters.periodType" :options="[{ label: '日', value: 'DAY' }, { label: '周', value: 'WEEK' }, { label: '月', value: 'MONTH' }]" />
        <el-date-picker v-model="filters.startDate" type="date" value-format="YYYY-MM-DD" style="width: 135px" />
        <span>至</span>
        <el-date-picker v-model="filters.endDate" type="date" value-format="YYYY-MM-DD" style="width: 135px" />
        <el-select v-model="filters.organizationId" clearable filterable placeholder="全部组织" style="width: 150px">
          <el-option v-for="item in organizations" :key="item.id" :label="item.organizationName" :value="item.id" />
        </el-select>
      </div>
      <div class="command-tools">
        <div class="command-clock"><strong>{{ clock }}</strong><span>{{ dateText }}</span></div>
        <button :aria-pressed="playback.autoTour" @click="toggleAutoTour()"><el-icon><VideoPause v-if="playback.autoTour" /><VideoPlay v-else /></el-icon>{{ playback.autoTour ? '暂停轮播' : '开始轮播' }}</button>
        <button @click="load()"><el-icon><Refresh /></el-icon>刷新</button>
        <button @click="fullscreen"><el-icon><FullScreen /></el-icon>全屏</button>
      </div>
    </header>

    <section class="status-kpis">
      <button class="kpi-card blue" @click="openStatus()"><span>设备总数</span><strong>{{ dashboard?.core.total ?? 0 }}</strong><small>全部在册设备</small></button>
      <button class="kpi-card green" @click="openStatus('RUNNING')"><span>运行设备</span><strong>{{ dashboard?.core.running ?? 0 }}</strong><small>实时正常运行</small></button>
      <button class="kpi-card cyan" @click="openStatus('IDLE')"><span>空闲设备</span><strong>{{ idleEquipmentCount }}</strong><small>当前可安排作业</small></button>
      <button class="kpi-card gray" @click="openStatus('STOPPED')"><span>停机设备</span><strong>{{ dashboard?.core.stopped ?? 0 }}</strong><small>计划与异常停机</small></button>
      <button class="kpi-card slate" @click="openStatus('SCRAPPED')"><span>报废设备</span><strong>{{ scrappedEquipmentCount }}</strong><small>已退出生产运行</small></button>
      <button class="kpi-card red" @click="router.push('/inspection/abnormal')"><span>业务异常</span><strong>{{ (dashboard?.inspection.abnormal ?? 0) + (dashboard?.maintenance.abnormal ?? 0) }}</strong><small>点检与维保异常</small></button>
      <button class="kpi-card orange" @click="router.push({ path: '/inspection/tasks', query: { taskStatus: 'OVERDUE' } })"><span>逾期任务</span><strong>{{ (dashboard?.inspection.overdue ?? 0) + (dashboard?.maintenance.overdue ?? 0) }}</strong><small>需要立即闭环</small></button>
      <button class="kpi-card pink"><span>长时异常</span><strong>{{ dashboard?.liveEquipment.filter((i) => i.longDuration).length ?? 0 }}</strong><small>持续时长超限</small></button>
    </section>

    <section class="status-grid">
      <article class="command-panel health-panel">
        <div class="panel-title"><span>HEALTH INDEX</span><h2>设备健康指数</h2></div>
        <div class="health-ring" :style="{ '--rate': `${healthRate * 3.6}deg` }"><div><strong>{{ healthRate }}%</strong><span>综合健康度</span></div></div>
        <div class="distribution-list">
          <button v-for="item in dashboard?.statusDistribution" :key="item.statusCode" @click="openStatus(item.statusCode)">
            <i :style="{ backgroundColor: item.displayColor, boxShadow: `0 0 9px ${item.displayColor}` }" />
            <span>{{ item.statusName }}</span><b>{{ item.equipmentCount }}</b>
            <em><i :style="{ width: `${item.proportion ? item.proportion * 100 : 0}%`, backgroundColor: item.displayColor }" /></em>
          </button>
        </div>
      </article>

      <article class="command-panel equipment-map">
        <div class="panel-title horizontal"><div><span>REAL-TIME EQUIPMENT MATRIX</span><h2>设备实时状态矩阵</h2></div><small>状态每 15 秒同步 · 当前聚焦设备自动轮播</small></div>
        <div class="matrix-grid">
          <button
            v-for="item in dashboard?.liveEquipment"
            :key="item.equipmentId"
            :class="['equipment-tile', statusClass(item.statusCode), { spotlight: currentSpotlightId === item.equipmentId }]"
            @click="openLiveEquipment(item)"
          >
            <span class="machine-icon"><i /><i /><i /></span>
            <span class="tile-content"><b>{{ item.equipmentName }}</b><small>{{ item.equipmentCode }} · {{ item.organizationName }}</small></span>
            <span class="tile-status"><i :style="{ backgroundColor: item.displayColor }" />{{ item.statusName }}</span>
            <span class="tile-meta"><em>持续 {{ duration(item.durationSeconds) }}</em><em>OEE {{ percent(item.todayOee) }}</em></span>
            <span v-if="item.longDuration" class="warning-badge">超时</span>
          </button>
          <div v-if="!dashboard?.liveEquipment.length" class="empty-matrix">当前筛选范围暂无设备实时状态</div>
        </div>
        <div class="matrix-flow"><i /><span>实时数据流：设备采集 → 状态引擎 → LeanTPM 态势中心</span></div>
      </article>

      <aside class="command-panel risk-panel">
        <div class="panel-title horizontal"><div><span>RISK RADAR</span><h2>异常风险雷达</h2></div><b>{{ attentionEquipment.length }}</b></div>
        <div class="risk-summary">
          <div><strong>{{ dashboard?.core.stopped ?? 0 }}</strong><span>停机</span></div>
          <div><strong>{{ scrappedEquipmentCount }}</strong><span>报废</span></div>
          <div><strong>{{ (dashboard?.inspection.abnormal ?? 0) + (dashboard?.maintenance.abnormal ?? 0) }}</strong><span>业务异常</span></div>
        </div>
        <div class="radar-visual"><i /><i /><i /><span>风险扫描</span></div>
        <div class="risk-list">
          <button v-for="item in attentionEquipment.slice(0, 4)" :key="item.equipmentId" @click="openLiveEquipment(item)">
            <i :style="{ backgroundColor: item.displayColor }" /><span><b>{{ item.equipmentCode }} · {{ item.equipmentName }}</b><small>{{ item.statusName }} · {{ duration(item.durationSeconds) }}</small></span>
          </button>
          <div v-if="!attentionEquipment.length" class="risk-empty">未发现需要关注的设备</div>
        </div>
      </aside>
    </section>

    <section class="status-bottom">
      <article class="command-panel organization-panel">
        <div class="panel-title horizontal"><div><span>ORGANIZATION LOAD</span><h2>组织设备负载</h2></div><small>按组织实时聚合</small></div>
        <div class="organization-bars">
          <div v-for="item in dashboard?.organizationDistribution" :key="item.organizationId">
            <span>{{ item.organizationName }}</span>
            <em><i :style="{ width: organizationWidth(item.equipmentCount) }" /></em>
            <b>{{ item.equipmentCount }} 台</b><small>{{ item.runningCount }} 运行 · {{ item.stoppedCount }} 停机</small>
          </div>
        </div>
      </article>
      <article class="command-panel ticker-panel">
        <div class="panel-title horizontal"><div><span>LIVE EQUIPMENT FEED</span><h2>设备实时播报</h2></div><small>点击设备进入台账</small></div>
        <div class="ticker-head"><span>设备</span><span>组织</span><span>状态</span><span>持续时间</span><span>今日 OEE</span></div>
        <button v-for="item in dashboard?.liveEquipment.slice(0, 4)" :key="item.equipmentId" @click="openLiveEquipment(item)">
          <span><b>{{ item.equipmentName }}</b><small>{{ item.equipmentCode }}</small></span><span>{{ item.organizationName }}</span><span :style="{ color: item.displayColor }">● {{ item.statusName }}</span><span :class="{ danger: item.longDuration }">{{ duration(item.durationSeconds) }}</span><span>{{ percent(item.todayOee) }}</span>
        </button>
      </article>
    </section>
    <footer class="command-footer"><span>LeanTPM 设备实时态势中心</span><span>数据更新 {{ generatedTime }} · 自动刷新策略由系统参数控制</span><span class="online">● 数据链路在线</span></footer>
  </main>

  <main v-else ref="root" class="analysis-command" :class="topic.toLowerCase()" :style="{ '--accent': analysisAccent }" v-loading="loading">
    <header class="analysis-header">
      <div class="analysis-brand"><span class="analysis-live" /><div><span>{{ branding.shortName }} · {{ analysisEnglish }}</span><h1>{{ title }}</h1><small>{{ analysisSubtitle }}</small></div></div>
      <div class="command-filters">
        <el-segmented v-model="filters.periodType" :options="[{ label: '日', value: 'DAY' }, { label: '周', value: 'WEEK' }, { label: '月', value: 'MONTH' }]" />
        <el-date-picker v-model="filters.startDate" type="date" value-format="YYYY-MM-DD" style="width:135px" /><span>至</span>
        <el-date-picker v-model="filters.endDate" type="date" value-format="YYYY-MM-DD" style="width:135px" />
        <el-select v-model="filters.organizationId" clearable filterable placeholder="全部组织" style="width:145px"><el-option v-for="item in organizations" :key="item.id" :label="item.organizationName" :value="item.id" /></el-select>
      </div>
      <div class="command-tools">
        <div class="command-clock"><strong>{{ clock }}</strong><span>{{ dateText }}</span></div>
        <button @click="toggleAutoTour()"><el-icon><VideoPause v-if="playback.autoTour" /><VideoPlay v-else /></el-icon>{{ playback.autoTour ? '暂停轮播' : '开始轮播' }}</button>
        <button @click="load()"><el-icon><Refresh /></el-icon>刷新</button><button @click="fullscreen"><el-icon><FullScreen /></el-icon>全屏</button>
      </div>
    </header>

    <section class="analysis-kpis">
      <button v-for="metric in metrics" :key="metric.label" :style="{ '--metric': metric.color }" @click="drillMetric(metric.label)"><span>{{ metric.label }}</span><strong>{{ metric.value }}</strong><small>{{ metric.hint || '点击穿透业务明细' }}</small></button>
    </section>

    <template v-if="topic !== 'OEE'">
      <section class="workflow-grid">
        <article class="analysis-panel completion-panel">
          <div class="analysis-panel-title"><span>COMPLETION INDEX</span><h2>{{ workflowName }}闭环完成率</h2></div>
          <div class="completion-ring" :style="{ '--rate': `${rateNumber(activeWorkflow?.completionRate) * 3.6}deg` }"><div><strong>{{ percent(activeWorkflow?.completionRate) }}</strong><span>任务完成率</span></div></div>
          <div class="completion-stats"><div><b>{{ activeWorkflow?.due ?? 0 }}</b><span>应完成</span></div><div><b class="green">{{ activeWorkflow?.completed ?? 0 }}</b><span>已完成</span></div><div><b class="orange">{{ activeWorkflow?.pending ?? 0 }}</b><span>待执行</span></div></div>
          <button class="drill-all" @click="drillMetric('应完成')">查看全部{{ workflowName }}任务 →</button>
        </article>

        <article class="analysis-panel trend-theatre">
          <div class="analysis-panel-title horizontal"><div><span>EXECUTION TREND</span><h2>{{ workflowName }}执行趋势</h2></div><small>点击日期穿透当天任务</small></div>
          <div class="trend-legend"><span><i class="due" />应完成</span><span><i class="completed" />已完成</span><span><i class="overdue" />逾期</span><span><i class="abnormal" />异常</span></div>
          <div class="workflow-bars">
            <button v-for="(item,index) in activeWorkflowTrend" :key="item.statisticDate" :class="{ active: playback.autoTour && index === spotlightIndex % Math.max(activeWorkflowTrend.length,1) }" @click="drillTrend(item)">
              <span class="bar-value">{{ item.completed }}/{{ item.due }}</span>
              <span class="bar-column"><i class="due" :style="{ height: workflowHeight(item.due) }" /><i class="completed" :style="{ height: workflowHeight(item.completed) }" /><i v-if="item.overdue" class="overdue" :style="{ height: workflowHeight(item.overdue) }" /><i v-if="item.abnormal" class="abnormal" :style="{ height: workflowHeight(item.abnormal) }" /></span>
              <span class="bar-date">{{ item.statisticDate.slice(5) }}</span>
            </button>
            <div v-if="!activeWorkflowTrend.length" class="empty-analysis">当前周期暂无{{ workflowName }}趋势数据</div>
          </div>
          <div class="trend-scan"><i /></div>
        </article>

        <aside class="analysis-panel workflow-risk">
          <div class="analysis-panel-title horizontal"><div><span>RISK & EXCEPTION</span><h2>风险与异常</h2></div><b>{{ (activeWorkflow?.overdue ?? 0) + (activeWorkflow?.abnormal ?? 0) }}</b></div>
          <div class="risk-orbit"><span><b>{{ activeWorkflow?.overdue ?? 0 }}</b>逾期</span><span><b>{{ activeWorkflow?.abnormal ?? 0 }}</b>异常</span><i /></div>
          <div class="workflow-alerts">
            <button v-for="item in activeWorkflowTrend.filter((row) => row.overdue || row.abnormal).slice(-4).reverse()" :key="item.statisticDate" @click="drillTrend(item)"><i /><span><b>{{ item.statisticDate }}</b><small>{{ item.overdue }} 逾期 · {{ item.abnormal }} 异常</small></span></button>
            <div v-if="!activeWorkflowTrend.some((item) => item.overdue || item.abnormal)" class="empty-risk">当前周期无逾期和异常</div>
          </div>
        </aside>
      </section>

      <section class="workflow-bottom">
        <article class="analysis-panel stage-flow">
          <div class="analysis-panel-title horizontal"><div><span>PROCESS CLOSED LOOP</span><h2>{{ workflowName }}任务闭环</h2></div><small>点击阶段进入任务台账</small></div>
          <div class="stage-steps">
            <button @click="drillMetric('应完成')"><i>01</i><span><b>计划生成</b><small>{{ activeWorkflow?.due ?? 0 }} 项应执行</small></span></button><em />
            <button @click="drillMetric('待执行')"><i>02</i><span><b>现场执行</b><small>{{ activeWorkflow?.pending ?? 0 }} 项待执行</small></span></button><em />
            <button @click="drillMetric('异常')"><i>03</i><span><b>异常处置</b><small>{{ activeWorkflow?.abnormal ?? 0 }} 项异常</small></span></button><em />
            <button @click="drillMetric('已完成')"><i>04</i><span><b>闭环完成</b><small>{{ activeWorkflow?.completed ?? 0 }} 项完成</small></span></button>
          </div>
        </article>
        <article class="analysis-panel ontime-panel">
          <div class="analysis-panel-title horizontal"><div><span>ON-TIME PERFORMANCE</span><h2>准时完成表现</h2></div><b>{{ percent(activeWorkflow?.onTimeRate) }}</b></div>
          <div class="ontime-track"><i :style="{ width: `${rateNumber(activeWorkflow?.onTimeRate)}%` }" /></div>
          <div class="ontime-copy"><span>逾期任务 <b>{{ activeWorkflow?.overdue ?? 0 }}</b></span><span>异常任务 <b>{{ activeWorkflow?.abnormal ?? 0 }}</b></span><button @click="drillMetric('逾期')">进入逾期任务 →</button></div>
        </article>
      </section>
    </template>

    <template v-else>
      <section class="oee-grid">
        <article class="analysis-panel oee-gauge-panel">
          <div class="analysis-panel-title"><span>OVERALL EQUIPMENT EFFECTIVENESS</span><h2>综合设备效率</h2></div>
          <div class="oee-gauge" :style="{ '--rate': `${rateNumber(oeeSummary?.oeeRate) * 3.6}deg` }"><div><strong>{{ percent(oeeSummary?.oeeRate) }}</strong><span>综合 OEE</span><small>目标 {{ percent(oeeSummary?.targetOeeRate) }}</small></div></div>
          <div class="target-status" :class="{ good: Number(oeeSummary?.oeeRate ?? 0) >= Number(oeeSummary?.targetOeeRate ?? 0) }"><i />{{ Number(oeeSummary?.oeeRate ?? 0) >= Number(oeeSummary?.targetOeeRate ?? 0) ? '达到目标' : '低于目标，需要改善' }}</div>
          <button class="drill-all" @click="openOeeAnalysis">进入 OEE 综合分析 →</button>
        </article>
        <article class="analysis-panel oee-trend-panel">
          <div class="analysis-panel-title horizontal"><div><span>OEE PERFORMANCE TREND</span><h2>OEE 与目标趋势</h2></div><small>按当前周期聚合</small></div>
          <div class="oee-bars"><button v-for="(item,index) in dashboard?.oee.trend" :key="item.period" :class="{ active: playback.autoTour && index === spotlightIndex % Math.max(dashboard?.oee.trend.length ?? 1,1) }" @click="openOeeAnalysis"><span class="oee-values"><b>{{ percent(item.oeeRate) }}</b><small>目标 {{ percent(item.targetOeeRate) }}</small></span><span class="oee-column"><i class="target" :style="{ height: oeeHeight(item.targetOeeRate) }" /><i class="actual" :style="{ height: oeeHeight(item.oeeRate) }" /></span><span>{{ item.period }}</span></button><div v-if="!dashboard?.oee.trend.length" class="empty-analysis">暂无 OEE 趋势数据</div></div>
          <div class="trend-scan"><i /></div>
        </article>
        <aside class="analysis-panel ranking-panel">
          <div class="analysis-panel-title horizontal"><div><span>PERFORMANCE RANKING</span><h2>效率排名</h2></div><small>TOP 5</small></div>
          <button v-for="(item,index) in dashboard?.oee.ranking.slice(0,5)" :key="`${item.scopeType}-${item.scopeId}`" @click="item.scopeType === 'EQUIPMENT' ? router.push({ path:'/equipment/ledger',query:{ equipmentId:item.scopeId } }) : openOeeAnalysis()"><i>{{ index + 1 }}</i><span><b>{{ item.scopeName }}</b><small>{{ item.scopeCode }} · {{ item.recordCount }} 条记录</small></span><strong>{{ percent(item.oeeRate) }}</strong></button>
          <div v-if="!dashboard?.oee.ranking.length" class="empty-risk">暂无排名数据</div>
        </aside>
      </section>
      <section class="oee-bottom">
        <article class="analysis-panel factor-panel">
          <div class="analysis-panel-title horizontal"><div><span>OEE THREE FACTORS</span><h2>OEE 三要素</h2></div><small>OEE = 时间 × 性能 × 质量</small></div>
          <div class="factor-cards"><button @click="openOeeAnalysis"><span>时间开动率</span><b>{{ percent(oeeSummary?.availabilityRate) }}</b><em><i :style="{ width:`${rateNumber(oeeSummary?.availabilityRate)}%` }" /></em></button><button @click="openOeeAnalysis"><span>性能开动率</span><b>{{ percent(oeeSummary?.performanceRate) }}</b><em><i :style="{ width:`${rateNumber(oeeSummary?.performanceRate)}%` }" /></em></button><button @click="openOeeAnalysis"><span>良品率</span><b>{{ percent(oeeSummary?.qualityRate) }}</b><em><i :style="{ width:`${rateNumber(oeeSummary?.qualityRate)}%` }" /></em></button></div>
        </article>
        <article class="analysis-panel loss-panel">
          <div class="analysis-panel-title horizontal"><div><span>LOSS OPPORTUNITY</span><h2>主要效率损失</h2></div><button @click="openOeeAnalysis">全部损失 →</button></div>
          <div class="loss-cards"><button v-for="item in dashboard?.oee.losses.slice(0,4)" :key="item.lossReasonId" @click="openOeeAnalysis"><span><b>{{ item.reasonName }}</b><small>{{ item.occurrenceCount }} 次</small></span><em><i :style="{ width:`${Math.max(4,Number(item.proportion ?? 0)*100)}%` }" /></em><strong>{{ Number(item.durationMinutes).toFixed(0) }} min</strong></button><div v-if="!dashboard?.oee.losses.length" class="empty-analysis">暂无损失数据</div></div>
        </article>
      </section>
    </template>
    <footer class="command-footer"><span>{{ branding.shortName }} · {{ analysisEnglish }}</span><span>统计周期 {{ filters.startDate }} 至 {{ filters.endDate }} · 更新 {{ generatedTime }}</span><span class="online">● 分析数据在线</span></footer>
  </main>
</template>

<style scoped>
.status-command { --cyan:#35d9ff; --green:#29df8c; --red:#ff4b62; height:calc(100vh - 58px); min-height:720px; display:grid; grid-template-rows:auto auto minmax(300px,1fr) 154px 18px; gap:8px; overflow:hidden; padding:9px; color:#dff7ff; background:radial-gradient(circle at 50% 0,rgba(20,102,143,.22),transparent 34%),#050f1b; }
.status-command:fullscreen { height:100vh; }.command-header { display:grid; grid-template-columns:minmax(300px,1fr) auto minmax(270px,1fr); align-items:center; gap:14px; min-height:72px; padding:10px 14px; border:1px solid rgba(66,200,255,.25); border-radius:12px; background:linear-gradient(105deg,rgba(8,40,64,.98),rgba(5,20,35,.96)); box-shadow:inset 0 0 30px rgba(42,188,238,.06); }.command-brand { display:flex; align-items:center; gap:10px; }.live-signal { width:8px;height:8px;border-radius:50%;background:var(--green);box-shadow:0 0 14px var(--green);animation:pulse 1.5s infinite; }.command-brand span { color:#4fcff5;font-size:8px;letter-spacing:.18em; }.command-brand h1 { margin:1px 0 0;font-size:24px;letter-spacing:.04em; }.command-filters { display:flex;align-items:center;gap:5px;color:#63839a;font-size:10px; }.command-tools { display:flex;justify-content:flex-end;align-items:center;gap:5px; }.command-tools button { display:inline-flex;align-items:center;gap:4px;padding:7px 9px;color:#9ebdce;font-size:10px;border:1px solid rgba(75,177,218,.18);border-radius:5px;background:rgba(20,51,70,.6);cursor:pointer; }.command-tools button:hover { color:white;border-color:rgba(53,217,255,.5); }.command-clock { display:grid;margin-right:8px;text-align:right; }.command-clock strong { color:var(--cyan);font:700 18px Consolas,monospace;letter-spacing:.08em; }.command-clock span { color:#5f8298;font-size:8px; }
.status-kpis { display:grid;grid-template-columns:repeat(8,minmax(0,1fr));gap:7px; }.kpi-card { position:relative;min-width:0;padding:9px 10px;text-align:left;color:#9bb9ca;border:1px solid color-mix(in srgb,var(--kpi) 42%,transparent);border-radius:9px;background:linear-gradient(135deg,color-mix(in srgb,var(--kpi) 11%,transparent),rgba(7,25,42,.88));cursor:pointer;overflow:hidden; }.kpi-card::after { position:absolute;right:-18px;top:-20px;width:55px;height:55px;content:'';border:1px solid color-mix(in srgb,var(--kpi) 25%,transparent);border-radius:50%; }.kpi-card span,.kpi-card small { display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap; }.kpi-card span { font-size:10px; }.kpi-card strong { display:block;margin:4px 0 2px;color:var(--kpi);font:700 22px Consolas,monospace;text-shadow:0 0 13px color-mix(in srgb,var(--kpi) 40%,transparent); }.kpi-card small { color:#54758a;font-size:8px; }.kpi-card.blue{--kpi:#39c7ff}.kpi-card.green{--kpi:#29df8c}.kpi-card.cyan{--kpi:#20d8d1}.kpi-card.gray{--kpi:#94a3b8}.kpi-card.red{--kpi:#ff4b62}.kpi-card.orange{--kpi:#ff9a3d}.kpi-card.slate{--kpi:#718297}.kpi-card.pink{--kpi:#fb7185}
.command-panel { min-width:0;overflow:hidden;padding:11px;border:1px solid rgba(70,180,224,.18);border-radius:10px;background:linear-gradient(145deg,rgba(8,32,51,.94),rgba(5,19,33,.9));box-shadow:inset 0 0 24px rgba(28,126,167,.04); }.status-grid { display:grid;grid-template-columns:220px minmax(500px,1fr) 245px;gap:8px;min-height:0; }.panel-title span { color:#42bde6;font-size:7px;letter-spacing:.14em; }.panel-title h2 { margin:1px 0 8px;font-size:14px; }.panel-title.horizontal { display:flex;align-items:flex-start;justify-content:space-between; }.panel-title.horizontal small { color:#52748a;font-size:8px; }.panel-title.horizontal>b { color:#ff6678;font:700 18px Consolas,monospace; }
.health-ring { width:112px;height:112px;margin:2px auto 9px;padding:9px;border-radius:50%;background:conic-gradient(var(--green) var(--rate),rgba(50,91,109,.28) 0);box-shadow:0 0 22px rgba(41,223,140,.12); }.health-ring>div { display:grid;width:100%;height:100%;place-content:center;text-align:center;border-radius:50%;background:#071827; }.health-ring strong { color:var(--green);font:700 21px Consolas,monospace; }.health-ring span { color:#6f91a5;font-size:8px; }.distribution-list { display:grid;gap:2px; }.distribution-list button { display:grid;grid-template-columns:6px 1fr auto 44px;align-items:center;gap:5px;padding:4px;color:#86a8b9;font-size:9px;border:0;background:transparent;cursor:pointer; }.distribution-list>button>i { width:5px;height:5px;border-radius:50%; }.distribution-list b { color:#d3edf8;font:600 9px Consolas,monospace; }.distribution-list em { height:3px;overflow:hidden;border-radius:3px;background:#142f41; }.distribution-list em i { display:block;height:100%; }
.equipment-map { display:grid;grid-template-rows:auto minmax(0,1fr) 20px; }.matrix-grid { display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:7px;align-content:start;overflow:hidden;padding:2px; }.equipment-tile { --state:#4e7185;position:relative;display:grid;grid-template-columns:38px 1fr auto;grid-template-rows:auto auto;gap:3px 7px;min-width:0;padding:9px;color:#a7c3d2;text-align:left;border:1px solid color-mix(in srgb,var(--state) 35%,transparent);border-radius:8px;background:linear-gradient(135deg,color-mix(in srgb,var(--state) 9%,transparent),rgba(6,23,38,.88));cursor:pointer;transition:.25s; }.equipment-tile.running{--state:#28df8a}.equipment-tile.fault{--state:#ff4b62}.equipment-tile.repair{--state:#ff9b42}.equipment-tile.offline{--state:#697b8e}.equipment-tile.stopped{--state:#8ea1b1}.equipment-tile.other{--state:#35d9ff}.equipment-tile.spotlight { border-color:var(--state);box-shadow:0 0 20px color-mix(in srgb,var(--state) 24%,transparent),inset 0 0 18px color-mix(in srgb,var(--state) 8%,transparent);transform:translateY(-1px); }.machine-icon { grid-row:1/3;position:relative;width:38px;height:38px;margin:auto;border:1px solid color-mix(in srgb,var(--state) 48%,transparent);border-radius:7px;background:linear-gradient(145deg,#12364b,#091d2e); }.machine-icon i:nth-child(1){position:absolute;left:7px;right:7px;top:8px;height:13px;border:1px solid var(--state);background:color-mix(in srgb,var(--state) 20%,transparent)}.machine-icon i:nth-child(2){position:absolute;left:5px;right:5px;bottom:7px;height:5px;background:#1d4258}.machine-icon i:nth-child(3){position:absolute;right:5px;top:5px;width:4px;height:4px;border-radius:50%;background:var(--state);box-shadow:0 0 7px var(--state)}.tile-content { display:grid;min-width:0; }.tile-content b { overflow:hidden;color:#d5eef8;font-size:10px;text-overflow:ellipsis;white-space:nowrap; }.tile-content small { overflow:hidden;color:#52768b;font-size:7px;text-overflow:ellipsis;white-space:nowrap; }.tile-status { display:flex;align-items:center;gap:4px;color:var(--state);font-size:8px; }.tile-status i { width:5px;height:5px;border-radius:50%;box-shadow:0 0 7px currentColor; }.tile-meta { grid-column:2/4;display:flex;justify-content:space-between;color:#66879b;font-size:7px; }.tile-meta em { font-style:normal; }.warning-badge { position:absolute;right:-1px;top:-1px;padding:2px 5px;color:#ffd3d8;font-size:7px;border-radius:0 7px 0 6px;background:#b52e40; }.empty-matrix { grid-column:1/-1;padding:60px;text-align:center;color:#587b90; }.matrix-flow { display:flex;align-items:center;gap:8px;overflow:hidden;color:#4e7288;font-size:8px; }.matrix-flow i { width:35%;height:3px;background:linear-gradient(90deg,transparent,var(--cyan),transparent);animation:flow 2.5s linear infinite; }
.risk-summary { display:grid;grid-template-columns:repeat(3,1fr);gap:5px; }.risk-summary div { display:grid;padding:6px;text-align:center;border:1px solid rgba(255,83,104,.12);border-radius:6px;background:rgba(104,26,40,.12); }.risk-summary strong { color:#ff6074;font:700 17px Consolas,monospace; }.risk-summary span { color:#795d67;font-size:8px; }.radar-visual { position:relative;width:96px;height:96px;margin:8px auto;border:1px solid rgba(53,217,255,.22);border-radius:50%;background:repeating-radial-gradient(circle,transparent 0 18px,rgba(53,217,255,.12) 19px 20px),conic-gradient(from 0deg,rgba(53,217,255,.4),transparent 22%,transparent);animation:radar 5s linear infinite; }.radar-visual::before,.radar-visual::after { position:absolute;left:50%;top:0;width:1px;height:100%;content:'';background:rgba(53,217,255,.12); }.radar-visual::after { transform:rotate(90deg); }.radar-visual i { position:absolute;width:5px;height:5px;border-radius:50%;background:#ff5368;box-shadow:0 0 8px #ff5368; }.radar-visual i:nth-child(1){left:25px;top:27px}.radar-visual i:nth-child(2){right:19px;top:46px}.radar-visual i:nth-child(3){left:48px;bottom:17px}.radar-visual span { position:absolute;left:50%;top:50%;color:#57a3bf;font-size:7px;transform:translate(-50%,-50%); }.risk-list { display:grid;gap:4px; }.risk-list button { display:grid;grid-template-columns:5px 1fr;gap:6px;align-items:center;padding:6px;color:inherit;text-align:left;border:1px solid rgba(72,163,200,.1);border-radius:5px;background:rgba(14,42,59,.5);cursor:pointer; }.risk-list button>i { width:5px;height:5px;border-radius:50%;box-shadow:0 0 8px currentColor; }.risk-list span { display:grid;min-width:0; }.risk-list b,.risk-list small { overflow:hidden;text-overflow:ellipsis;white-space:nowrap; }.risk-list b { color:#c8e2ed;font-size:9px; }.risk-list small { color:#647f91;font-size:7px; }.risk-empty { padding:12px;text-align:center;color:#58788a;font-size:8px; }
.status-bottom { display:grid;grid-template-columns:.85fr 1.35fr;gap:8px;min-height:0; }.organization-bars { display:grid;grid-template-columns:1fr 1fr;gap:7px 14px; }.organization-bars>div { display:grid;grid-template-columns:70px 1fr 35px;align-items:center;gap:6px;color:#7192a5;font-size:8px; }.organization-bars em { height:5px;border-radius:5px;background:#142e40;overflow:hidden; }.organization-bars em i { display:block;height:100%;border-radius:5px;background:linear-gradient(90deg,#15789b,#2ad9dd); }.organization-bars b { color:#b8d8e5;font-size:8px; }.organization-bars small { grid-column:2/4;color:#4f7185;font-size:7px; }.ticker-head,.ticker-panel>button { display:grid;grid-template-columns:1.5fr 1fr .65fr .72fr .55fr;align-items:center;gap:8px; }.ticker-head { padding:3px 6px;color:#4f7388;font-size:7px;border-bottom:1px solid rgba(69,161,199,.1); }.ticker-panel>button { width:100%;padding:5px 6px;color:#86a8b8;font-size:8px;text-align:left;border:0;border-bottom:1px solid rgba(69,161,199,.07);background:transparent;cursor:pointer; }.ticker-panel>button:hover { background:rgba(27,101,130,.1); }.ticker-panel>button>span:first-child { display:grid; }.ticker-panel b { color:#c5e1ec;font-size:8px; }.ticker-panel small { color:#4f7185;font-size:7px; }.danger { color:#ff6577!important; }.command-footer { display:flex;justify-content:space-between;align-items:center;padding:0 5px;color:#466a7f;font-size:7px;letter-spacing:.04em; }.command-footer .online { color:#2bcb84; }
.analysis-command { height:calc(100vh - 58px);min-height:720px;display:grid;grid-template-rows:auto auto minmax(300px,1fr) 154px 18px;gap:8px;overflow:hidden;padding:9px;color:#dff7ff;background:radial-gradient(circle at 48% 0,color-mix(in srgb,var(--accent) 15%,transparent),transparent 34%),#050f1b; }.analysis-command:fullscreen{height:100vh}.analysis-header { display:grid;grid-template-columns:minmax(320px,1fr) auto minmax(270px,1fr);align-items:center;gap:12px;min-height:72px;padding:9px 14px;border:1px solid color-mix(in srgb,var(--accent) 32%,transparent);border-radius:12px;background:linear-gradient(105deg,color-mix(in srgb,var(--accent) 10%,#081c2d),rgba(5,20,35,.96));box-shadow:inset 0 0 30px color-mix(in srgb,var(--accent) 7%,transparent); }.analysis-brand{display:flex;align-items:center;gap:10px}.analysis-live{width:8px;height:8px;border-radius:50%;background:var(--accent);box-shadow:0 0 14px var(--accent);animation:pulse 1.5s infinite}.analysis-brand>div{display:grid}.analysis-brand span{color:var(--accent);font-size:8px;letter-spacing:.16em}.analysis-brand h1{margin:1px 0;font-size:23px;letter-spacing:.04em}.analysis-brand small{color:#63849a;font-size:8px}.analysis-kpis{display:grid;grid-template-columns:repeat(7,minmax(0,1fr));gap:7px}.analysis-kpis button{position:relative;min-width:0;padding:8px 10px;color:#93b2c3;text-align:left;border:1px solid color-mix(in srgb,var(--metric) 38%,transparent);border-radius:9px;background:linear-gradient(135deg,color-mix(in srgb,var(--metric) 10%,transparent),rgba(7,25,42,.9));cursor:pointer;overflow:hidden}.analysis-kpis button::after{position:absolute;right:-18px;top:-22px;width:58px;height:58px;content:'';border:1px solid color-mix(in srgb,var(--metric) 22%,transparent);border-radius:50%}.analysis-kpis span,.analysis-kpis small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.analysis-kpis span{font-size:9px}.analysis-kpis strong{display:block;margin:3px 0;color:var(--metric);font:700 21px Consolas,monospace;text-shadow:0 0 12px color-mix(in srgb,var(--metric) 38%,transparent)}.analysis-kpis small{color:#527489;font-size:7px}
.analysis-panel{min-width:0;overflow:hidden;padding:11px;border:1px solid color-mix(in srgb,var(--accent) 18%,transparent);border-radius:10px;background:linear-gradient(145deg,color-mix(in srgb,var(--accent) 4%,rgba(8,32,51,.95)),rgba(5,19,33,.92));box-shadow:inset 0 0 24px color-mix(in srgb,var(--accent) 3%,transparent)}.analysis-panel-title span{color:var(--accent);font-size:7px;letter-spacing:.14em}.analysis-panel-title h2{margin:1px 0 8px;font-size:14px}.analysis-panel-title.horizontal{display:flex;align-items:flex-start;justify-content:space-between}.analysis-panel-title.horizontal small{color:#52748a;font-size:8px}.analysis-panel-title.horizontal>b{color:#ff687b;font:700 18px Consolas,monospace}.workflow-grid{display:grid;grid-template-columns:225px minmax(520px,1fr) 245px;gap:8px;min-height:0}.completion-panel{display:grid;grid-template-rows:auto auto auto 1fr}.completion-ring{width:126px;height:126px;margin:2px auto 9px;padding:10px;border-radius:50%;background:conic-gradient(var(--accent) var(--rate),rgba(50,91,109,.28) 0);box-shadow:0 0 24px color-mix(in srgb,var(--accent) 17%,transparent)}.completion-ring>div{display:grid;width:100%;height:100%;place-content:center;text-align:center;border-radius:50%;background:#071827}.completion-ring strong{color:var(--accent);font:700 23px Consolas,monospace}.completion-ring span{color:#6f91a5;font-size:8px}.completion-stats{display:grid;grid-template-columns:repeat(3,1fr);gap:5px}.completion-stats div{display:grid;padding:6px;text-align:center;border:1px solid color-mix(in srgb,var(--accent) 13%,transparent);border-radius:6px;background:rgba(15,44,61,.4)}.completion-stats b{color:#54c8ef;font:700 15px Consolas,monospace}.completion-stats b.green{color:#2adb8a}.completion-stats b.orange{color:#f6a63d}.completion-stats span{color:#637f91;font-size:7px}.drill-all{align-self:end;width:100%;padding:7px;color:var(--accent);font-size:8px;border:1px solid color-mix(in srgb,var(--accent) 20%,transparent);border-radius:5px;background:color-mix(in srgb,var(--accent) 7%,transparent);cursor:pointer}.trend-theatre{position:relative;display:grid;grid-template-rows:auto auto minmax(0,1fr) 4px}.trend-legend{display:flex;justify-content:center;gap:13px;color:#648499;font-size:7px}.trend-legend span{display:flex;align-items:center;gap:4px}.trend-legend i{width:6px;height:6px;border-radius:2px}.trend-legend .due{background:#215675}.trend-legend .completed{background:var(--accent)}.trend-legend .overdue{background:#f5a524}.trend-legend .abnormal{background:#ff536a}.workflow-bars{display:flex;align-items:stretch;justify-content:space-around;gap:5px;min-height:0;padding:12px 8px 0}.workflow-bars>button{display:grid;grid-template-rows:15px minmax(100px,1fr) 15px;min-width:24px;max-width:58px;flex:1;color:#6e91a5;border:0;background:transparent;cursor:pointer;transition:.2s}.workflow-bars>button.active{color:#dff8ff;filter:drop-shadow(0 0 8px color-mix(in srgb,var(--accent) 50%,transparent));transform:translateY(-2px)}.bar-value{font:600 8px Consolas,monospace}.bar-column{position:relative;display:flex;align-items:flex-end;justify-content:center;height:100%;border-bottom:1px solid rgba(70,156,191,.17);background:repeating-linear-gradient(to top,rgba(62,128,153,.07) 0 1px,transparent 1px 24%)}.bar-column i{position:absolute;bottom:0;width:34%;border-radius:3px 3px 0 0}.bar-column .due{left:13%;background:#1b4e6c}.bar-column .completed{right:13%;background:linear-gradient(to top,color-mix(in srgb,var(--accent) 68%,#09263a),var(--accent));box-shadow:0 0 8px color-mix(in srgb,var(--accent) 24%,transparent)}.bar-column .overdue{left:6%;width:5px;background:#f5a524}.bar-column .abnormal{right:5%;width:5px;background:#ff536a}.bar-date{font-size:7px}.trend-scan{overflow:hidden;background:#122d40}.trend-scan i{display:block;width:28%;height:3px;background:linear-gradient(90deg,transparent,var(--accent),transparent);animation:flow 2.8s linear infinite}.empty-analysis{margin:auto;color:#55788c;font-size:9px}.workflow-risk{display:grid;grid-template-rows:auto auto minmax(0,1fr)}.risk-orbit{position:relative;width:120px;height:120px;margin:2px auto 10px;border:1px solid color-mix(in srgb,var(--accent) 23%,transparent);border-radius:50%;background:repeating-radial-gradient(circle,transparent 0 20px,color-mix(in srgb,var(--accent) 10%,transparent) 21px 22px)}.risk-orbit::before{position:absolute;inset:12px;content:'';border-top:2px solid var(--accent);border-radius:50%;animation:radar 4s linear infinite}.risk-orbit span{position:absolute;display:grid;text-align:center;color:#718fa0;font-size:7px}.risk-orbit span:first-child{left:17px;top:45px}.risk-orbit span:nth-child(2){right:16px;top:45px}.risk-orbit b{color:#ff6076;font:700 17px Consolas,monospace}.risk-orbit>i{position:absolute;left:50%;top:50%;width:6px;height:6px;border-radius:50%;background:var(--accent);box-shadow:0 0 10px var(--accent);transform:translate(-50%,-50%)}.workflow-alerts{display:grid;align-content:start;gap:4px}.workflow-alerts button{display:grid;grid-template-columns:5px 1fr;gap:6px;align-items:center;padding:6px;color:inherit;text-align:left;border:1px solid rgba(255,83,106,.1);border-radius:5px;background:rgba(77,28,43,.15);cursor:pointer}.workflow-alerts button>i{width:5px;height:5px;border-radius:50%;background:#ff536a;box-shadow:0 0 8px #ff536a}.workflow-alerts span{display:grid}.workflow-alerts b{color:#cbe5ef;font-size:8px}.workflow-alerts small{color:#765d69;font-size:7px}.empty-risk{margin:auto;color:#54778a;font-size:8px}.workflow-bottom{display:grid;grid-template-columns:1.4fr .6fr;gap:8px}.stage-flow{display:grid;grid-template-rows:auto 1fr}.stage-steps{display:flex;align-items:center;justify-content:space-around;gap:7px}.stage-steps button{display:flex;align-items:center;gap:7px;min-width:120px;padding:7px;color:#8eadbe;text-align:left;border:1px solid color-mix(in srgb,var(--accent) 14%,transparent);border-radius:7px;background:color-mix(in srgb,var(--accent) 5%,transparent);cursor:pointer}.stage-steps button:hover{border-color:var(--accent)}.stage-steps button>i{display:grid;width:28px;height:28px;place-content:center;color:var(--accent);font:700 9px Consolas,monospace;border:1px solid color-mix(in srgb,var(--accent) 38%,transparent);border-radius:50%;font-style:normal}.stage-steps button>span{display:grid}.stage-steps b{color:#cce7f0;font-size:9px}.stage-steps small{color:#55778a;font-size:7px}.stage-steps>em{width:30px;height:1px;background:linear-gradient(90deg,color-mix(in srgb,var(--accent) 15%,transparent),var(--accent));position:relative}.stage-steps>em::after{position:absolute;right:-1px;top:-2px;content:'';border-left:4px solid var(--accent);border-top:2px solid transparent;border-bottom:2px solid transparent}.ontime-panel{display:grid;grid-template-rows:auto auto 1fr}.ontime-panel .analysis-panel-title>b{color:var(--accent);font:700 20px Consolas,monospace}.ontime-track{height:8px;margin:6px 0 12px;overflow:hidden;border-radius:8px;background:#142f40}.ontime-track i{display:block;height:100%;border-radius:8px;background:linear-gradient(90deg,color-mix(in srgb,var(--accent) 45%,#15384d),var(--accent));box-shadow:0 0 10px color-mix(in srgb,var(--accent) 30%,transparent)}.ontime-copy{display:flex;align-items:center;justify-content:space-between;gap:7px;color:#658497;font-size:8px}.ontime-copy b{color:#ff6377}.ontime-copy button{padding:5px 7px;color:var(--accent);font-size:7px;border:1px solid color-mix(in srgb,var(--accent) 18%,transparent);border-radius:4px;background:transparent;cursor:pointer}
.oee-grid{display:grid;grid-template-columns:235px minmax(520px,1fr) 260px;gap:8px;min-height:0}.oee-gauge-panel{display:grid;grid-template-rows:auto auto auto 1fr}.oee-gauge{width:148px;height:148px;margin:2px auto 8px;padding:11px;border-radius:50%;background:conic-gradient(var(--accent) var(--rate),rgba(50,91,109,.28) 0);box-shadow:0 0 28px color-mix(in srgb,var(--accent) 18%,transparent)}.oee-gauge>div{display:grid;width:100%;height:100%;place-content:center;text-align:center;border-radius:50%;background:#071827}.oee-gauge strong{color:var(--accent);font:700 27px Consolas,monospace}.oee-gauge span{color:#8eafc0;font-size:9px}.oee-gauge small{color:#587a8e;font-size:7px}.target-status{display:flex;align-items:center;justify-content:center;gap:5px;margin-bottom:7px;color:#ff9a4c;font-size:8px}.target-status i{width:5px;height:5px;border-radius:50%;background:currentColor;box-shadow:0 0 8px currentColor}.target-status.good{color:#2bdc8c}.oee-trend-panel{display:grid;grid-template-rows:auto minmax(0,1fr) 4px}.oee-bars{display:flex;align-items:stretch;justify-content:space-around;gap:5px;min-height:0;padding:10px 8px 0}.oee-bars>button{display:grid;grid-template-rows:28px minmax(100px,1fr) 16px;min-width:28px;max-width:65px;flex:1;color:#66899d;border:0;background:transparent;cursor:pointer}.oee-bars>button.active{color:#dff7ff;filter:drop-shadow(0 0 8px color-mix(in srgb,var(--accent) 50%,transparent));transform:translateY(-2px)}.oee-values{display:grid}.oee-values b{color:var(--accent);font:700 9px Consolas,monospace}.oee-values small{font-size:6px}.oee-column{position:relative;display:flex;align-items:flex-end;justify-content:center;height:100%;border-bottom:1px solid rgba(70,156,191,.17);background:repeating-linear-gradient(to top,rgba(62,128,153,.07) 0 1px,transparent 1px 24%)}.oee-column i{position:absolute;bottom:0;width:30%;border-radius:3px 3px 0 0}.oee-column .target{left:16%;background:#f5a524}.oee-column .actual{right:16%;background:linear-gradient(to top,#12617e,var(--accent));box-shadow:0 0 8px color-mix(in srgb,var(--accent) 28%,transparent)}.ranking-panel{display:grid;grid-template-rows:auto repeat(5,1fr)}.ranking-panel>button{display:grid;grid-template-columns:25px 1fr auto;gap:7px;align-items:center;padding:5px;color:inherit;text-align:left;border:0;border-bottom:1px solid rgba(67,159,195,.08);background:transparent;cursor:pointer}.ranking-panel>button:hover{background:color-mix(in srgb,var(--accent) 5%,transparent)}.ranking-panel>button>i{display:grid;width:22px;height:22px;place-content:center;color:var(--accent);font:700 8px Consolas,monospace;border:1px solid color-mix(in srgb,var(--accent) 26%,transparent);border-radius:50%;font-style:normal}.ranking-panel>button>span{display:grid;min-width:0}.ranking-panel b,.ranking-panel small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.ranking-panel b{color:#cbe5ef;font-size:8px}.ranking-panel small{color:#54768a;font-size:6px}.ranking-panel strong{color:var(--accent);font:700 10px Consolas,monospace}.oee-bottom{display:grid;grid-template-columns:.9fr 1.1fr;gap:8px}.factor-panel{display:grid;grid-template-rows:auto 1fr}.factor-cards{display:grid;grid-template-columns:repeat(3,1fr);gap:7px}.factor-cards button{display:grid;padding:8px;color:#7798aa;text-align:left;border:1px solid color-mix(in srgb,var(--accent) 12%,transparent);border-radius:6px;background:color-mix(in srgb,var(--accent) 4%,transparent);cursor:pointer}.factor-cards b{margin:3px 0;color:var(--accent);font:700 16px Consolas,monospace}.factor-cards span{font-size:8px}.factor-cards em{height:4px;overflow:hidden;border-radius:4px;background:#142f41}.factor-cards em i{display:block;height:100%;background:var(--accent)}.loss-panel{display:grid;grid-template-rows:auto 1fr}.loss-panel .analysis-panel-title>button{color:var(--accent);font-size:7px;border:0;background:transparent;cursor:pointer}.loss-cards{display:grid;grid-template-columns:1fr 1fr;gap:6px 10px}.loss-cards>button{display:grid;grid-template-columns:90px 1fr 52px;gap:6px;align-items:center;padding:5px;color:inherit;text-align:left;border:1px solid rgba(245,165,36,.1);border-radius:5px;background:rgba(86,57,15,.1);cursor:pointer}.loss-cards span{display:grid}.loss-cards b{color:#cbe4ee;font-size:8px}.loss-cards small{color:#725f3e;font-size:6px}.loss-cards em{height:5px;overflow:hidden;border-radius:5px;background:#2c2c28}.loss-cards em i{display:block;height:100%;background:linear-gradient(90deg,#8b5a17,#f5a524)}.loss-cards strong{color:#f5a524;font:700 8px Consolas,monospace;text-align:right}
.topic-screen { min-height:100%;display:grid;gap:12px;padding:12px;color:#dff4ff;background:radial-gradient(circle at 10% 0,rgba(14,165,233,.13),transparent 33%),#07111f; }.topic-screen:fullscreen { overflow:auto; }.viz-panel { padding:15px;border:1px solid rgba(82,178,255,.17);border-radius:15px;background:rgba(8,25,43,.9); }.viz-panel header { display:flex;justify-content:space-between;margin-bottom:12px; }.viz-panel h3 { margin:0;font-size:15px; }.viz-panel header span { color:#58768f;font-size:11px; }.detail-grid { display:grid;grid-template-columns:1fr 1fr;gap:12px; }.rank-row { width:100%;display:flex;justify-content:space-between;padding:11px 6px;color:#bdd8ea;border:0;border-bottom:1px solid rgba(82,178,255,.1);background:transparent;cursor:pointer; }.rank-row b { color:#22d3ee; }.loss-row { display:grid;grid-template-columns:130px 1fr 90px;gap:10px;align-items:center;padding:9px 0; }.loss-row b { color:#f59e0b;text-align:right; }
@keyframes pulse { 50%{opacity:.55;transform:scale(1.3)} } @keyframes flow { from{transform:translateX(-90%)}to{transform:translateX(290%)} } @keyframes radar { to{transform:rotate(360deg)} }
@media(max-width:1300px){.command-header{grid-template-columns:1fr auto}.command-filters{grid-row:2;grid-column:1/3}.command-tools{grid-column:2}.status-command{height:auto;min-height:720px;overflow:auto}.status-grid{grid-template-columns:200px minmax(460px,1fr)}.risk-panel{display:none}.matrix-grid{grid-template-columns:repeat(3,1fr)}}
@media(max-width:900px){.status-kpis{grid-template-columns:repeat(4,1fr)}.status-grid{grid-template-columns:1fr}.health-panel{display:none}.matrix-grid{grid-template-columns:repeat(2,1fr)}.status-bottom{grid-template-columns:1fr}.organization-panel{display:none}.detail-grid{grid-template-columns:1fr}}
@media(max-width:1300px){.analysis-header{grid-template-columns:1fr auto}.analysis-header .command-filters{grid-row:2;grid-column:1/3}.analysis-header .command-tools{grid-column:2}.analysis-command{height:auto;min-height:720px;overflow:auto}.workflow-grid{grid-template-columns:210px minmax(460px,1fr)}.workflow-risk{display:none}.oee-grid{grid-template-columns:220px minmax(460px,1fr)}.ranking-panel{display:none}.stage-steps button{min-width:100px}}
@media(max-width:900px){.analysis-kpis{grid-template-columns:repeat(4,minmax(0,1fr))}.workflow-grid,.oee-grid{grid-template-columns:1fr}.completion-panel,.oee-gauge-panel{display:none}.workflow-bottom,.oee-bottom{grid-template-columns:1fr}.stage-steps{display:grid;grid-template-columns:1fr 1fr}.stage-steps>em{display:none}.stage-steps button{width:100%}.loss-cards{grid-template-columns:1fr}}
:deep(.dark-table){--el-table-bg-color:transparent;--el-table-tr-bg-color:transparent;--el-table-header-bg-color:rgba(15,43,68,.9);--el-table-border-color:rgba(82,178,255,.12);--el-table-text-color:#b8d4e8;--el-table-header-text-color:#6f91aa;cursor:pointer}
.equipment-tile.idle{--state:#35d9ff}.equipment-tile.stopped{--state:#f59e0b}.equipment-tile.scrapped{--state:#697b8e}
</style>
