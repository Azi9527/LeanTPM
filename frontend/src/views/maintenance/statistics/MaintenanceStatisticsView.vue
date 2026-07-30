<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { maintenanceApi, type Statistics } from '@/api/maintenance'
import { errorMessage } from '@/utils/http'

const loading = ref(false)
const value = ref<Statistics>({
  dueToday: 0,
  completedToday: 0,
  pendingToday: 0,
  overdue: 0,
  abnormal: 0,
  completionRate: 0,
  onTimeRate: 0,
})
const refreshedAt = ref('')

const cards = computed(() => [
  { label: '今日应检', value: value.value.dueToday, tone: 'blue', hint: '按计划日期统计' },
  { label: '今日完成', value: value.value.completedToday, tone: 'green', hint: '已完成任务' },
  { label: '今日待办', value: value.value.pendingToday, tone: 'orange', hint: '待执行/执行中/待复核' },
  { label: '逾期任务', value: value.value.overdue, tone: 'red', hint: '超过截止时间' },
  { label: '未闭环异常', value: value.value.abnormal, tone: 'purple', hint: '不含已关闭异常' },
])

onMounted(load)

async function load() {
  loading.value = true
  try {
    value.value = await maintenanceApi.statistics()
    refreshedAt.value = new Date().toLocaleString()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page-shell" v-loading="loading">
    <header class="page-header">
      <div><h1>维保统计</h1><p>聚焦今日达成、按时完成、逾期积压和异常闭环，指标受当前组织数据范围约束。</p></div>
      <el-button plain @click="load">刷新数据</el-button>
    </header>
    <section class="metric-grid">
      <article v-for="card in cards" :key="card.label" class="surface-card metric-card" :class="card.tone">
        <span>{{ card.label }}</span><strong>{{ card.value }}</strong><small>{{ card.hint }}</small>
      </article>
    </section>
    <section class="rate-grid">
      <article class="surface-card rate-card">
        <div><span>今日完成率</span><strong>{{ value.completionRate }}%</strong></div>
        <el-progress type="dashboard" :percentage="Number(value.completionRate)" :width="180" />
        <p>今日已完成任务 / 今日应检任务</p>
      </article>
      <article class="surface-card rate-card">
        <div><span>按时完成率</span><strong>{{ value.onTimeRate }}%</strong></div>
        <el-progress type="dashboard" :percentage="Number(value.onTimeRate)" :width="180" color="#22a06b" />
        <p>截止时间前完成 / 今日已完成任务</p>
      </article>
      <article class="surface-card health-card">
        <h3>现场维保健康度</h3>
        <div class="health-row"><span>执行达成</span><el-progress :percentage="Number(value.completionRate)" /></div>
        <div class="health-row"><span>准时水平</span><el-progress :percentage="Number(value.onTimeRate)" color="#22a06b" /></div>
        <div class="health-row"><span>异常压力</span><el-progress :percentage="Math.min(100, value.abnormal * 10)" status="exception" /></div>
        <small>最近刷新：{{ refreshedAt || '—' }}</small>
      </article>
    </section>
  </div>
</template>

<style scoped>
.metric-grid { display: grid; grid-template-columns: repeat(5, minmax(150px, 1fr)); gap: 14px; }
.metric-card { position: relative; overflow: hidden; display: grid; gap: 8px; }
.metric-card::before { content: ''; position: absolute; inset: 0 auto 0 0; width: 4px; background: var(--tone); }
.metric-card strong { font-size: clamp(28px, 4vw, 42px); line-height: 1; }
.metric-card small, .rate-card p, .health-card small { color: var(--el-text-color-secondary); }
.metric-card.blue { --tone: #3b82f6; } .metric-card.green { --tone: #22a06b; }
.metric-card.orange { --tone: #f59e0b; } .metric-card.red { --tone: #e5484d; }
.metric-card.purple { --tone: #8b5cf6; }
.rate-grid { display: grid; grid-template-columns: repeat(2, minmax(260px, 1fr)) minmax(320px, 1.3fr); gap: 14px; }
.rate-card { display: grid; place-items: center; text-align: center; }
.rate-card > div { width: 100%; display: flex; justify-content: space-between; align-items: baseline; }
.rate-card strong { font-size: 28px; }
.health-card { display: grid; gap: 18px; }
.health-row { display: grid; grid-template-columns: 90px 1fr; align-items: center; gap: 12px; }
@media (max-width: 1000px) { .metric-grid { grid-template-columns: repeat(2, 1fr); } .rate-grid { grid-template-columns: 1fr; } }
@media (max-width: 520px) { .metric-grid { grid-template-columns: 1fr; } }
</style>
