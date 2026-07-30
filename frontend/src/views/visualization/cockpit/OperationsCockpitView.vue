<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import DashboardHeader from '@/views/visualization/components/DashboardHeader.vue'
import MetricStrip, { type MetricCard } from '@/views/visualization/components/MetricStrip.vue'
import CockpitCharts from '@/views/visualization/components/CockpitCharts.vue'
import { useVisualizationDashboard } from '@/views/visualization/useVisualizationDashboard'

const router = useRouter()
const root = ref<HTMLElement>()
const { filters, loading, dashboard, organizations, load } = useVisualizationDashboard()

const metrics = computed<MetricCard[]>(() => {
  const core = dashboard.value?.core
  const inspection = dashboard.value?.inspection
  const maintenance = dashboard.value?.maintenance
  return [
    { label: '设备总数', value: core?.total ?? 0, color: '#38bdf8' },
    { label: '运行', value: core?.running ?? 0, color: '#22c55e' },
    { label: '停机', value: core?.stopped ?? 0, color: '#94a3b8' },
    { label: '故障', value: core?.fault ?? 0, color: '#ef4444' },
    { label: '维修', value: core?.repair ?? 0, color: '#f97316' },
    { label: '保养', value: core?.maintenance ?? 0, color: '#a855f7' },
    { label: '离线', value: core?.offline ?? 0, color: '#64748b' },
    { label: '点检完成率', value: percent(inspection?.completionRate), hint: `${inspection?.completed ?? 0}/${inspection?.due ?? 0}`, color: '#14b8a6' },
    { label: '维保完成率', value: percent(maintenance?.completionRate), hint: `${maintenance?.completed ?? 0}/${maintenance?.due ?? 0}`, color: '#c084fc' },
    { label: '平均 OEE', value: percent(dashboard.value?.oee.summary.oeeRate), hint: `目标 ${percent(dashboard.value?.oee.summary.targetOeeRate)}`, color: '#22d3ee' },
    { label: '开放异常', value: (inspection?.abnormal ?? 0) + (maintenance?.abnormal ?? 0), color: '#fb7185' },
    { label: '逾期任务', value: (inspection?.overdue ?? 0) + (maintenance?.overdue ?? 0), color: '#f59e0b' },
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

async function fullscreen() {
  if (!document.fullscreenElement) await root.value?.requestFullscreen()
  else await document.exitFullscreen()
}

function openEquipment(id: number) {
  router.push({ path: '/equipment/ledger', query: { equipmentId: id } })
}
</script>

<template>
  <main ref="root" class="cockpit" v-loading="loading">
    <DashboardHeader
      v-model:start-date="filters.startDate"
      v-model:end-date="filters.endDate"
      v-model:organization-id="filters.organizationId"
      title="设备综合运行驾驶舱"
      subtitle="设备状态、点检、维保与 OEE 的统一实时视图"
      :generated-at="dashboard?.generatedAt"
      :organizations="organizations"
      :loading="loading"
      @refresh="load()"
      @fullscreen="fullscreen"
    />
    <MetricStrip :metrics="metrics" />
    <CockpitCharts :dashboard="dashboard" mode="ALL" />
    <section class="bottom-grid">
      <article class="viz-panel live-list">
        <header><h3>设备实时状态</h3><span>{{ dashboard?.liveEquipment.length ?? 0 }} 台可见设备</span></header>
        <div class="table-wrap">
          <button
            v-for="item in dashboard?.liveEquipment"
            :key="item.equipmentId"
            class="live-row"
            :class="{ alert: item.longDuration }"
            @click="openEquipment(item.equipmentId)"
          >
            <i :style="{ backgroundColor: item.displayColor }" />
            <span class="device"><b>{{ item.equipmentCode }}</b><small>{{ item.equipmentName }}</small></span>
            <span>{{ item.organizationName }}</span>
            <span :style="{ color: item.displayColor }">{{ item.statusName }}</span>
            <span>{{ duration(item.durationSeconds) }}</span>
            <span>{{ percent(item.todayOee) }}</span>
          </button>
        </div>
      </article>
      <article class="viz-panel ranking">
        <header><h3>低 OEE 设备关注榜</h3><span>点击进入设备台账</span></header>
        <button
          v-for="(item, index) in dashboard?.oee.ranking.slice().reverse()"
          :key="item.scopeId"
          @click="item.scopeType === 'EQUIPMENT' && openEquipment(item.scopeId)"
        >
          <em>{{ String(index + 1).padStart(2, '0') }}</em>
          <span><b>{{ item.scopeName }}</b><small>{{ item.scopeCode }}</small></span>
          <strong>{{ percent(item.oeeRate) }}</strong>
        </button>
        <el-empty v-if="!dashboard?.oee.ranking.length" description="暂无已审核 OEE 数据" />
      </article>
    </section>
  </main>
</template>

<style scoped>
.cockpit {
  min-height: 100%;
  display: grid;
  gap: 12px;
  padding: 12px;
  color: #dff4ff;
  background:
    radial-gradient(circle at 15% 0%, rgba(14, 165, 233, 0.14), transparent 34%),
    radial-gradient(circle at 100% 70%, rgba(34, 211, 238, 0.08), transparent 32%),
    #07111f;
}
.cockpit:fullscreen { overflow: auto; }
.bottom-grid { display: grid; grid-template-columns: minmax(0, 1.6fr) minmax(300px, 0.7fr); gap: 12px; }
.viz-panel { padding: 15px; border: 1px solid rgba(82, 178, 255, 0.17); border-radius: 15px; background: rgba(8, 25, 43, 0.88); }
header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
h3 { margin: 0; font-size: 15px; }
header span { color: #58768f; font-size: 11px; }
.table-wrap { max-height: 350px; overflow: auto; }
.live-row {
  width: 100%;
  display: grid;
  grid-template-columns: 10px minmax(180px, 1.2fr) minmax(100px, 0.8fr) 80px 80px 70px;
  align-items: center;
  gap: 10px;
  padding: 10px 8px;
  color: #a9c4d8;
  border: 0;
  border-bottom: 1px solid rgba(82, 178, 255, 0.1);
  background: transparent;
  cursor: pointer;
  text-align: left;
}
.live-row:hover { background: rgba(56, 189, 248, 0.07); }
.live-row.alert { background: rgba(239, 68, 68, 0.06); }
.live-row i { width: 8px; height: 8px; border-radius: 50%; box-shadow: 0 0 12px currentColor; }
.device, .ranking span { display: grid; gap: 2px; }
.device b, .ranking b { color: #e6f6ff; }
.device small, .ranking small { color: #58768f; }
.ranking button { width: 100%; display: grid; grid-template-columns: 36px 1fr auto; gap: 10px; align-items: center; padding: 10px 0; color: #b8d4e8; border: 0; border-bottom: 1px solid rgba(82, 178, 255, 0.1); background: transparent; cursor: pointer; text-align: left; }
.ranking em { color: #58768f; font-style: normal; }
.ranking strong { color: #22d3ee; font-size: 18px; }
@media (max-width: 1050px) { .bottom-grid { grid-template-columns: 1fr; } }
@media (max-width: 760px) { .live-row { grid-template-columns: 8px 1fr 70px; } .live-row > span:nth-of-type(2), .live-row > span:nth-of-type(4), .live-row > span:nth-of-type(5) { display: none; } }
</style>
