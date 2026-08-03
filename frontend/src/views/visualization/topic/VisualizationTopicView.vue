<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DashboardHeader from '@/views/visualization/components/DashboardHeader.vue'
import MetricStrip, { type MetricCard } from '@/views/visualization/components/MetricStrip.vue'
import CockpitCharts from '@/views/visualization/components/CockpitCharts.vue'
import { useVisualizationDashboard } from '@/views/visualization/useVisualizationDashboard'

type Topic = 'STATUS' | 'INSPECTION' | 'MAINTENANCE' | 'OEE'

const route = useRoute()
const router = useRouter()
const root = ref<HTMLElement>()
const { filters, loading, dashboard, organizations, load } = useVisualizationDashboard()
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
  if (topic.value === 'STATUS') {
    return [
      { label: '设备总数', value: data.core.total, color: '#38bdf8' },
      { label: '运行', value: data.core.running, color: '#22c55e' },
      { label: '停机', value: data.core.stopped, color: '#94a3b8' },
      { label: '故障', value: data.core.fault, color: '#ef4444' },
      { label: '维修', value: data.core.repair, color: '#f97316' },
      { label: '离线', value: data.core.offline, color: '#64748b' },
      { label: '长时异常', value: data.liveEquipment.filter((i) => i.longDuration).length, color: '#fb7185' },
    ]
  }
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

function percent(value?: number) {
  return value == null ? '—' : `${(Number(value) * 100).toFixed(1)}%`
}
function duration(seconds: number) {
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  return hours ? `${hours}h ${minutes}m` : `${minutes}m`
}
function openLiveEquipment(row: { equipmentId: number }) {
  router.push({ path: '/equipment/ledger', query: { equipmentId: row.equipmentId } })
}
function drillMetric(label: string) {
  if (topic.value === 'STATUS') {
    const statusByLabel: Record<string, string | undefined> = {
      '运行': 'RUNNING', '停机': 'STOPPED', '故障': 'FAULT',
      '维修': 'REPAIR', '离线': 'OFFLINE',
    }
    router.push({ path: '/equipment/ledger', query: { currentStatusCode: statusByLabel[label] } })
    return
  }
  if (topic.value === 'OEE') {
    router.push({ path: '/oee/analysis', query: { startDate: filters.startDate, endDate: filters.endDate } })
    return
  }
  const path = topic.value === 'INSPECTION' ? '/inspection/tasks' : '/maintenance/tasks'
  const query: Record<string, string> = { startDate: filters.startDate, endDate: filters.endDate }
  if (label === '逾期') query.taskStatus = 'OVERDUE'
  if (label === '异常') query.abnormalOnly = 'true'
  router.push({ path, query })
}
function drillTrend(row: { statisticDate: string }) {
  const path = topic.value === 'INSPECTION' ? '/inspection/tasks' : '/maintenance/tasks'
  router.push({ path, query: { startDate: row.statisticDate, endDate: row.statisticDate } })
}
async function fullscreen() {
  if (!document.fullscreenElement) await root.value?.requestFullscreen()
  else await document.exitFullscreen()
}
</script>

<template>
  <main ref="root" class="topic-screen" v-loading="loading">
    <DashboardHeader
      v-model:start-date="filters.startDate"
      v-model:end-date="filters.endDate"
      v-model:organization-id="filters.organizationId"
      v-model:period-type="filters.periodType"
      :title="title"
      subtitle="按组织和日期聚合，支持指标、图表与设备明细联动下钻"
      :generated-at="dashboard?.generatedAt"
      :organizations="organizations"
      :loading="loading"
      @refresh="load()"
      @fullscreen="fullscreen"
    />
    <MetricStrip :metrics="metrics" @select="drillMetric" />
    <CockpitCharts :dashboard="dashboard" :mode="topic" />

    <section v-if="topic === 'STATUS'" class="viz-panel">
      <header><h3>实时状态与持续时长</h3><span>长停机 / 长离线优先</span></header>
      <el-table
        :data="dashboard?.liveEquipment ?? []"
        row-key="equipmentId"
        class="dark-table"
        @row-click="openLiveEquipment"
      >
        <el-table-column prop="equipmentCode" label="设备编码" width="150" />
        <el-table-column prop="equipmentName" label="设备名称" min-width="170" />
        <el-table-column prop="organizationName" label="组织" min-width="130" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><span :style="{ color: row.displayColor }">● {{ row.statusName }}</span></template>
        </el-table-column>
        <el-table-column label="持续时间" width="120">
          <template #default="{ row }"><strong :class="{ danger: row.longDuration }">{{ duration(row.durationSeconds) }}</strong></template>
        </el-table-column>
        <el-table-column label="今日 OEE" width="110">
          <template #default="{ row }">{{ percent(row.todayOee) }}</template>
        </el-table-column>
      </el-table>
    </section>

    <section v-else-if="topic === 'OEE'" class="detail-grid">
      <article class="viz-panel">
        <header><h3>设备 / 组织 OEE 排名</h3><span>点击设备进入台账</span></header>
        <button
          v-for="item in dashboard?.oee.ranking"
          :key="`${item.scopeType}-${item.scopeId}`"
          class="rank-row"
          @click="item.scopeType === 'EQUIPMENT' && router.push({ path: '/equipment/ledger', query: { equipmentId: item.scopeId } })"
        >
          <span>{{ item.scopeName }}</span><b>{{ percent(item.oeeRate) }}</b>
        </button>
      </article>
      <article class="viz-panel">
        <header><h3>主要损失</h3><span>六大损失聚合</span></header>
        <div v-for="item in dashboard?.oee.losses" :key="item.lossReasonId" class="loss-row">
          <span>{{ item.reasonName }}</span>
          <el-progress :percentage="Number(item.proportion ?? 0) * 100" :stroke-width="8" />
          <b>{{ Number(item.durationMinutes).toFixed(1) }} min</b>
        </div>
        <el-empty v-if="!dashboard?.oee.losses.length" description="暂无损失数据" />
      </article>
    </section>

    <section v-else class="viz-panel">
      <header><h3>{{ topic === 'INSPECTION' ? '点检' : '维保' }}任务趋势明细</h3><span>应完成 / 完成 / 逾期 / 异常</span></header>
      <el-table
        :data="dashboard?.workflowTrend.filter((item) => item.workflowType === topic) ?? []"
        class="dark-table"
        @row-click="drillTrend"
      >
        <el-table-column prop="statisticDate" label="日期" min-width="140" />
        <el-table-column prop="due" label="应完成" />
        <el-table-column prop="completed" label="已完成" />
        <el-table-column prop="overdue" label="逾期" />
        <el-table-column prop="abnormal" label="异常" />
      </el-table>
    </section>
  </main>
</template>

<style scoped>
.topic-screen { min-height: 100%; display: grid; gap: 12px; padding: 12px; color: #dff4ff; background: radial-gradient(circle at 10% 0%, rgba(14,165,233,.13), transparent 33%), #07111f; }
.topic-screen:fullscreen { overflow: auto; }
.viz-panel { padding: 15px; border: 1px solid rgba(82,178,255,.17); border-radius: 15px; background: rgba(8,25,43,.9); }
header { display: flex; justify-content: space-between; margin-bottom: 12px; }
h3 { margin: 0; font-size: 15px; } header span { color: #58768f; font-size: 11px; }
.danger { color: #fb7185; }
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.rank-row { width: 100%; display: flex; justify-content: space-between; padding: 11px 6px; color: #bdd8ea; border: 0; border-bottom: 1px solid rgba(82,178,255,.1); background: transparent; cursor: pointer; }
.rank-row b { color: #22d3ee; }
.loss-row { display: grid; grid-template-columns: 130px 1fr 90px; gap: 10px; align-items: center; padding: 9px 0; }
.loss-row b { color: #f59e0b; text-align: right; }
@media (max-width: 900px) { .detail-grid { grid-template-columns: 1fr; } }
:deep(.dark-table) { --el-table-bg-color: transparent; --el-table-tr-bg-color: transparent; --el-table-header-bg-color: rgba(15, 43, 68, .9); --el-table-border-color: rgba(82,178,255,.12); --el-table-text-color: #b8d4e8; --el-table-header-text-color: #6f91aa; cursor: pointer; }
</style>
