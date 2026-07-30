<script setup lang="ts">
import { init, use, type ECharts } from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { DashboardResult } from '@/api/visualization'

use([BarChart, LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{ dashboard?: DashboardResult; mode?: 'ALL' | 'STATUS' | 'INSPECTION' | 'MAINTENANCE' | 'OEE' }>()
const statusElement = ref<HTMLDivElement>()
const organizationElement = ref<HTMLDivElement>()
const workflowElement = ref<HTMLDivElement>()
const oeeElement = ref<HTMLDivElement>()
let statusChart: ECharts | undefined
let organizationChart: ECharts | undefined
let workflowChart: ECharts | undefined
let oeeChart: ECharts | undefined

watch(() => props.dashboard, () => nextTick(render), { deep: true })
onMounted(() => {
  window.addEventListener('resize', resize)
  render()
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  ;[statusChart, organizationChart, workflowChart, oeeChart].forEach((chart) => chart?.dispose())
})

function render() {
  const data = props.dashboard
  if (!data) return
  if (statusElement.value) {
    statusChart ??= init(statusElement.value)
    statusChart.setOption({
      tooltip: { trigger: 'item' },
      legend: { bottom: 0, textStyle: { color: '#8ba8bf' } },
      series: [{
        type: 'pie', radius: ['48%', '72%'], center: ['50%', '43%'],
        label: { color: '#b8d4e8', formatter: '{b}\n{c}' },
        data: data.statusDistribution
          .filter((item) => item.equipmentCount > 0)
          .map((item) => ({
            name: item.statusName,
            value: item.equipmentCount,
            itemStyle: { color: item.displayColor },
          })),
      }],
    }, true)
  }
  if (organizationElement.value) {
    organizationChart ??= init(organizationElement.value)
    organizationChart.setOption({
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      legend: { top: 0, textStyle: { color: '#8ba8bf' } },
      grid: { left: 34, right: 18, top: 42, bottom: 65 },
      xAxis: {
        type: 'category',
        data: data.organizationDistribution.map((item) => item.organizationName),
        axisLabel: { color: '#7693aa', rotate: 25 },
      },
      yAxis: { type: 'value', axisLabel: { color: '#7693aa' }, splitLine: { lineStyle: { color: '#17324b' } } },
      series: [
        { name: '运行', type: 'bar', stack: 'status', data: data.organizationDistribution.map((i) => i.runningCount), itemStyle: { color: '#22c55e' } },
        { name: '停机', type: 'bar', stack: 'status', data: data.organizationDistribution.map((i) => i.stoppedCount), itemStyle: { color: '#64748b' } },
        { name: '故障', type: 'bar', stack: 'status', data: data.organizationDistribution.map((i) => i.faultCount), itemStyle: { color: '#ef4444' } },
        { name: '离线', type: 'bar', stack: 'status', data: data.organizationDistribution.map((i) => i.offlineCount), itemStyle: { color: '#334155' } },
      ],
    }, true)
  }
  if (workflowElement.value) {
    workflowChart ??= init(workflowElement.value)
    const dates = [...new Set(data.workflowTrend.map((item) => item.statisticDate))].sort()
    const series = (type: 'INSPECTION' | 'MAINTENANCE', key: 'completed' | 'overdue') =>
      dates.map((date) => data.workflowTrend.find((item) => item.statisticDate === date && item.workflowType === type)?.[key] ?? 0)
    workflowChart.setOption({
      tooltip: { trigger: 'axis' },
      legend: { top: 0, textStyle: { color: '#8ba8bf' } },
      grid: { left: 35, right: 20, top: 42, bottom: 30 },
      xAxis: { type: 'category', data: dates, axisLabel: { color: '#7693aa' } },
      yAxis: { type: 'value', axisLabel: { color: '#7693aa' }, splitLine: { lineStyle: { color: '#17324b' } } },
      series: [
        { name: '点检完成', type: 'line', smooth: true, data: series('INSPECTION', 'completed'), lineStyle: { color: '#14b8a6' } },
        { name: '维保完成', type: 'line', smooth: true, data: series('MAINTENANCE', 'completed'), lineStyle: { color: '#a855f7' } },
        { name: '点检逾期', type: 'bar', data: series('INSPECTION', 'overdue'), itemStyle: { color: '#f59e0b' } },
        { name: '维保逾期', type: 'bar', data: series('MAINTENANCE', 'overdue'), itemStyle: { color: '#ef4444' } },
      ],
    }, true)
  }
  if (oeeElement.value) {
    oeeChart ??= init(oeeElement.value)
    oeeChart.setOption({
      tooltip: { trigger: 'axis', valueFormatter: (value: unknown) => `${(Number(value) * 100).toFixed(2)}%` },
      legend: { top: 0, textStyle: { color: '#8ba8bf' } },
      grid: { left: 44, right: 20, top: 42, bottom: 30 },
      xAxis: { type: 'category', data: data.oee.trend.map((item) => item.period), axisLabel: { color: '#7693aa' } },
      yAxis: { type: 'value', min: 0, max: 1, axisLabel: { color: '#7693aa', formatter: (v: number) => `${Math.round(v * 100)}%` }, splitLine: { lineStyle: { color: '#17324b' } } },
      series: [
        { name: 'OEE', type: 'line', smooth: true, areaStyle: { color: 'rgba(34,211,238,.12)' }, data: data.oee.trend.map((i) => i.oeeRate), lineStyle: { width: 3, color: '#22d3ee' } },
        { name: '目标', type: 'line', symbol: 'none', data: data.oee.trend.map((i) => i.targetOeeRate), lineStyle: { type: 'dashed', color: '#f59e0b' } },
      ],
    }, true)
  }
}

function resize() {
  ;[statusChart, organizationChart, workflowChart, oeeChart].forEach((chart) => chart?.resize())
}
</script>

<template>
  <div class="charts-grid" :class="{ focused: mode && mode !== 'ALL' }">
    <article v-if="!mode || mode === 'ALL' || mode === 'STATUS'" class="viz-panel">
      <header><h3>设备状态分布</h3><span>实时状态</span></header>
      <div ref="statusElement" class="chart" />
    </article>
    <article v-if="!mode || mode === 'ALL' || mode === 'STATUS'" class="viz-panel">
      <header><h3>组织运行态势</h3><span>产线级</span></header>
      <div ref="organizationElement" class="chart" />
    </article>
    <article v-if="!mode || ['ALL', 'INSPECTION', 'MAINTENANCE'].includes(mode)" class="viz-panel">
      <header><h3>点检与维保趋势</h3><span>任务闭环</span></header>
      <div ref="workflowElement" class="chart" />
    </article>
    <article v-if="!mode || mode === 'ALL' || mode === 'OEE'" class="viz-panel">
      <header><h3>OEE 趋势</h3><span>目标对标</span></header>
      <div ref="oeeElement" class="chart" />
    </article>
  </div>
</template>

<style scoped>
.charts-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.charts-grid.focused { grid-template-columns: minmax(0, 1fr); }
.viz-panel {
  min-width: 0;
  padding: 15px;
  border: 1px solid rgba(82, 178, 255, 0.17);
  border-radius: 15px;
  background: rgba(8, 25, 43, 0.88);
}
header { display: flex; justify-content: space-between; align-items: center; }
h3 { margin: 0; color: #dff4ff; font-size: 15px; }
header span { color: #58768f; font-size: 11px; }
.chart { height: 290px; }
.focused .chart { height: 430px; }
@media (max-width: 980px) { .charts-grid { grid-template-columns: 1fr; } }
</style>
