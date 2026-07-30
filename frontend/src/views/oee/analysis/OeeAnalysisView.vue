<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { use, init, type ECharts } from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { ElMessage } from 'element-plus'
import { oeeApi, type AnalysisResult } from '@/api/oee'
import { equipmentApi, type EquipmentRow } from '@/api/equipment'
import { masterDataApi, type OrganizationRow } from '@/api/masterData'
import { errorMessage } from '@/utils/http'

use([
  BarChart,
  LineChart,
  PieChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  CanvasRenderer,
])

const loading = ref(false)
const result = ref<AnalysisResult>()
const equipment = ref<EquipmentRow[]>([])
const organizations = ref<OrganizationRow[]>([])
const trendElement = ref<HTMLDivElement>()
const rankingElement = ref<HTMLDivElement>()
const lossElement = ref<HTMLDivElement>()
let trendChart: ECharts | undefined
let rankingChart: ECharts | undefined
let lossChart: ECharts | undefined

const today = new Date()
const start = new Date(today)
start.setDate(start.getDate() - 29)
const filters = reactive({
  startDate: localDate(start),
  endDate: localDate(today),
  organizationId: undefined as number | undefined,
  equipmentId: undefined as number | undefined,
  period: 'DAY',
  rankingType: 'EQUIPMENT',
  limit: 20,
})

const cards = computed(() => {
  const summary = result.value?.summary
  return [
    { label: '综合 OEE', value: percent(summary?.oeeRate), tone: 'blue', hint: `目标 ${percent(summary?.targetOeeRate)}` },
    { label: '时间开动率', value: percent(summary?.availabilityRate), tone: 'green', hint: '运行时间 / 负荷时间' },
    { label: '性能开动率', value: percent(summary?.performanceRate), tone: 'orange', hint: '后端标准节拍计算' },
    { label: '良品率', value: percent(summary?.qualityRate), tone: 'purple', hint: '良品 / 实际产量' },
    { label: '低于目标记录', value: String(summary?.belowTargetCount ?? 0), tone: 'red', hint: `共 ${summary?.recordCount ?? 0} 条有效记录` },
  ]
})

onMounted(async () => {
  window.addEventListener('resize', resizeCharts)
  await Promise.all([loadReferences(), load()])
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts)
  trendChart?.dispose()
  rankingChart?.dispose()
  lossChart?.dispose()
})

async function loadReferences() {
  try {
    const [orgs, equipmentPage] = await Promise.all([
      masterDataApi.organizations(),
      equipmentApi.page({ status: 1, page: 1, pageSize: 200 }),
    ])
    organizations.value = orgs
    equipment.value = equipmentPage.records.filter((item) => item.oeeEnabled)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function load() {
  loading.value = true
  try {
    result.value = await oeeApi.analysis(filters)
    await nextTick()
    renderCharts()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function renderCharts() {
  if (trendElement.value) {
    trendChart ??= init(trendElement.value)
    trendChart.setOption({
      tooltip: { trigger: 'axis', valueFormatter: (value: unknown) => percent(Number(value)) },
      legend: { data: ['OEE', '目标', '时间开动率', '性能开动率', '良品率'] },
      grid: { left: 48, right: 24, bottom: 38, top: 54 },
      xAxis: { type: 'category', data: result.value?.trend.map((item) => item.period) ?? [] },
      yAxis: { type: 'value', min: 0, max: 1, axisLabel: { formatter: (value: number) => `${Math.round(value * 100)}%` } },
      series: [
        { name: 'OEE', type: 'line', smooth: true, symbolSize: 7, data: result.value?.trend.map((item) => item.oeeRate), lineStyle: { width: 3, color: '#2563eb' } },
        { name: '目标', type: 'line', symbol: 'none', data: result.value?.trend.map((item) => item.targetOeeRate), lineStyle: { type: 'dashed', color: '#e5484d' } },
        { name: '时间开动率', type: 'line', symbol: 'none', data: result.value?.trend.map((item) => item.availabilityRate) },
        { name: '性能开动率', type: 'line', symbol: 'none', data: result.value?.trend.map((item) => item.performanceRate) },
        { name: '良品率', type: 'line', symbol: 'none', data: result.value?.trend.map((item) => item.qualityRate) },
      ],
    }, true)
  }
  if (rankingElement.value) {
    rankingChart ??= init(rankingElement.value)
    rankingChart.setOption({
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, valueFormatter: (value: unknown) => percent(Number(value)) },
      grid: { left: 130, right: 28, bottom: 30, top: 18 },
      xAxis: { type: 'value', min: 0, max: 1, axisLabel: { formatter: (value: number) => `${Math.round(value * 100)}%` } },
      yAxis: { type: 'category', inverse: true, data: result.value?.ranking.map((item) => item.scopeName) ?? [] },
      series: [{ type: 'bar', data: result.value?.ranking.map((item) => item.oeeRate), itemStyle: { color: '#2563eb', borderRadius: [0, 5, 5, 0] }, label: { show: true, position: 'right', formatter: ({ value }: { value: number }) => percent(value) } }],
    }, true)
  }
  if (lossElement.value) {
    lossChart ??= init(lossElement.value)
    lossChart.setOption({
      tooltip: { trigger: 'item', formatter: '{b}<br/>{c} 分钟 · {d}%' },
      legend: { type: 'scroll', bottom: 0 },
      series: [{
        type: 'pie',
        radius: ['42%', '70%'],
        center: ['50%', '44%'],
        data: result.value?.losses.map((item) => ({ name: item.reasonName, value: item.durationMinutes })) ?? [],
        label: { formatter: '{b}\n{d}%' },
      }],
    }, true)
  }
}

function resizeCharts() {
  trendChart?.resize()
  rankingChart?.resize()
  lossChart?.resize()
}

function percent(value?: number) {
  return value == null ? '—' : `${(Number(value) * 100).toFixed(2)}%`
}

function localDate(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}
</script>

<template>
  <div class="page-shell" v-loading="loading">
    <header class="page-header">
      <div><h1>OEE 分析</h1><p>统一查看企业到设备的效率趋势、目标差距、分层排名和六大损失构成，并可下钻原始班次记录。</p></div>
      <el-button type="primary" @click="load">刷新分析</el-button>
    </header>
    <section class="surface-card filter-bar">
      <el-date-picker v-model="filters.startDate" type="date" value-format="YYYY-MM-DD" />
      <span>至</span>
      <el-date-picker v-model="filters.endDate" type="date" value-format="YYYY-MM-DD" />
      <el-select v-model="filters.organizationId" clearable filterable placeholder="全部组织" style="width: 190px"><el-option v-for="item in organizations" :key="item.id" :label="item.organizationName" :value="item.id" /></el-select>
      <el-select v-model="filters.equipmentId" clearable filterable placeholder="全部设备" style="width: 230px"><el-option v-for="item in equipment" :key="item.id" :label="`${item.equipmentCode} · ${item.equipmentName}`" :value="item.id" /></el-select>
      <el-select v-model="filters.period" style="width: 110px"><el-option label="日趋势" value="DAY" /><el-option label="周趋势" value="WEEK" /><el-option label="月趋势" value="MONTH" /></el-select>
      <el-select v-model="filters.rankingType" style="width: 130px"><el-option label="企业排名" value="ENTERPRISE" /><el-option label="工厂排名" value="FACTORY" /><el-option label="车间排名" value="WORKSHOP" /><el-option label="产线排名" value="LINE" /><el-option label="设备排名" value="EQUIPMENT" /></el-select>
      <el-button type="primary" @click="load">分析</el-button>
    </section>
    <section class="metric-grid">
      <article v-for="card in cards" :key="card.label" class="surface-card metric-card" :class="card.tone">
        <span>{{ card.label }}</span><strong>{{ card.value }}</strong><small>{{ card.hint }}</small>
      </article>
    </section>
    <section class="surface-card">
      <h3>OEE 趋势与目标对比</h3>
      <div v-if="result?.trend.length" ref="trendElement" class="chart trend-chart" />
      <el-empty v-else description="暂无已审核的趋势数据" />
    </section>
    <section class="chart-grid">
      <article class="surface-card"><h3>分层 OEE 排名</h3><div v-if="result?.ranking.length" ref="rankingElement" class="chart" /><el-empty v-else description="暂无排名数据" /></article>
      <article class="surface-card"><h3>损失构成</h3><div v-if="result?.losses.length" ref="lossElement" class="chart" /><el-empty v-else description="暂无损失数据" /></article>
    </section>
    <section class="surface-card">
      <h3>原始班次记录下钻</h3>
      <el-table :data="result?.records ?? []" stripe>
        <el-table-column prop="productionDate" label="日期" width="115" />
        <el-table-column prop="equipmentCode" label="设备编码" width="145" />
        <el-table-column prop="equipmentName" label="设备名称" min-width="150" />
        <el-table-column prop="organizationName" label="组织" min-width="140" />
        <el-table-column prop="shiftName" label="班次" width="90" />
        <el-table-column prop="plannedWorkMinutes" label="计划时间" width="100" />
        <el-table-column prop="runTimeMinutes" label="运行时间" width="100" />
        <el-table-column prop="actualQuantity" label="产量" width="90" />
        <el-table-column label="OEE" width="100"><template #default="{ row }"><strong>{{ percent(row.oeeRate) }}</strong></template></el-table-column>
        <el-table-column label="目标" width="100"><template #default="{ row }">{{ percent(row.targetOeeRate) }}</template></el-table-column>
        <template #empty><el-empty description="暂无可下钻记录" /></template>
      </el-table>
    </section>
  </div>
</template>

<style scoped>
.filter-bar { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.metric-grid { display: grid; grid-template-columns: repeat(5, minmax(150px, 1fr)); gap: 14px; }
.metric-card { position: relative; overflow: hidden; display: grid; gap: 8px; }
.metric-card::before { content: ''; position: absolute; inset: 0 auto 0 0; width: 4px; background: var(--tone); }
.metric-card strong { font-size: clamp(27px, 3vw, 40px); line-height: 1; }
.metric-card small { color: var(--el-text-color-secondary); }
.metric-card.blue { --tone: #2563eb; } .metric-card.green { --tone: #22a06b; }
.metric-card.orange { --tone: #f59e0b; } .metric-card.purple { --tone: #8b5cf6; }
.metric-card.red { --tone: #e5484d; }
.chart-grid { display: grid; grid-template-columns: 1.1fr 0.9fr; gap: 14px; }
.chart { width: 100%; height: 360px; }
.trend-chart { height: 390px; }
h3 { margin: 0 0 12px; }
@media (max-width: 1100px) { .metric-grid { grid-template-columns: repeat(2, 1fr); } .chart-grid { grid-template-columns: 1fr; } }
@media (max-width: 560px) { .metric-grid { grid-template-columns: 1fr; } }
</style>
