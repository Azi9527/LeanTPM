<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useMobileStore } from '@/stores/mobile'

const router = useRouter()
const auth = useAuthStore()
const mobile = useMobileStore()

const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 11) return '早上好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

const inspection = computed(() => mobile.bootstrap?.inspection)
const equipmentStatus = computed(() => mobile.bootstrap?.equipmentStatus)
const abnormalities = computed(() => mobile.bootstrap?.inspectionAbnormal)
const report = computed(() => mobile.bootstrap?.personalInspectionReport)
const completionRate = computed(() => inspection.value?.dueToday
  ? Math.min(100, Math.round((inspection.value.completedToday * 100) / inspection.value.dueToday))
  : 100)
const reportRate = computed(() => report.value?.due
  ? Math.min(100, Math.round((report.value.completed * 100) / report.value.due))
  : 100)

function scrollToReport() {
  document.querySelector('#personal-report')?.scrollIntoView({ behavior: 'smooth' })
}
</script>

<template>
  <div v-loading="mobile.loading" class="mobile-page">
    <section class="welcome-card">
      <p>{{ greeting }}</p>
      <h1>{{ auth.displayName }}</h1>
      <span>设备状态、点检进度与异常信息已为你汇总</span>
    </section>

    <section class="today-board">
      <div><span>今日点检完成度</span><strong>{{ completionRate }}%</strong></div>
      <el-progress :percentage="completionRate" :stroke-width="10" :show-text="false" />
      <div class="board-metrics">
        <span>今日 {{ inspection?.dueToday ?? 0 }}</span>
        <span>已完成 {{ inspection?.completedToday ?? 0 }}</span>
        <span class="danger">逾期 {{ inspection?.overdue ?? 0 }}</span>
      </div>
    </section>

    <section class="section-title"><div><h2>全厂设备状态</h2><p>普通用户可只读查看全部设备状态</p></div></section>
    <section class="status-grid">
      <button type="button" @click="router.push('/mobile/equipment-status')"><b>{{ equipmentStatus?.total ?? 0 }}</b><span>设备总数</span></button>
      <button type="button" @click="router.push({ path: '/mobile/equipment-status', query: { status: 'RUNNING' } })"><b class="success">{{ equipmentStatus?.running ?? 0 }}</b><span>运行</span></button>
      <button type="button" @click="router.push({ path: '/mobile/equipment-status', query: { status: 'STOPPED' } })"><b>{{ equipmentStatus?.stopped ?? 0 }}</b><span>停机</span></button>
      <button type="button" @click="router.push({ path: '/mobile/equipment-status', query: { status: 'FAULT' } })"><b class="danger">{{ equipmentStatus?.fault ?? 0 }}</b><span>故障/维修</span></button>
      <button type="button" @click="router.push({ path: '/mobile/equipment-status', query: { status: 'OFFLINE' } })"><b>{{ equipmentStatus?.offline ?? 0 }}</b><span>离线</span></button>
    </section>

    <section class="quick-grid">
      <button type="button" @click="router.push('/mobile/scan')">
        <el-icon><FullScreen /></el-icon><strong>扫码点检</strong><span>扫码查看或创建点检任务</span>
      </button>
      <button type="button" @click="router.push('/mobile/inspection')">
        <el-icon><Finished /></el-icon><strong>我的点检</strong><span>待执行 {{ inspection?.pending ?? 0 }} 项</span>
      </button>
      <button type="button" @click="router.push('/mobile/messages')">
        <el-icon><Warning /></el-icon><strong>点检异常</strong><span>{{ abnormalities?.open ?? 0 }} 条未关闭</span>
      </button>
      <button type="button" @click="scrollToReport">
        <el-icon><DataAnalysis /></el-icon><strong>个人报表</strong><span>最近 30 天点检统计</span>
      </button>
    </section>

    <section class="abnormal-card" @click="router.push('/mobile/messages')">
      <div><span>本人相关未关闭异常</span><strong>{{ abnormalities?.open ?? 0 }}</strong></div>
      <div class="severity"><span>紧急 {{ abnormalities?.critical ?? 0 }}</span><span>高 {{ abnormalities?.high ?? 0 }}</span></div>
    </section>

    <section id="personal-report" class="report-card">
      <header><div><h2>个人点检报表</h2><p>{{ report?.startDate }} 至 {{ report?.endDate }}</p></div><strong>{{ reportRate }}%</strong></header>
      <el-progress :percentage="reportRate" :stroke-width="12" />
      <div class="report-metrics">
        <div><b>{{ report?.due ?? 0 }}</b><span>应完成</span></div>
        <div><b>{{ report?.completed ?? 0 }}</b><span>已完成</span></div>
        <div><b class="danger">{{ report?.abnormal ?? 0 }}</b><span>异常任务</span></div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.mobile-page { display: grid; gap: 16px; }
.welcome-card { padding: 22px; border-radius: 20px; color: white; background: linear-gradient(135deg, var(--tpm-sidebar), var(--tpm-primary)); box-shadow: 0 14px 34px rgba(var(--tpm-primary-rgb), .22); }
.welcome-card p, .welcome-card h1 { margin: 0; }
.welcome-card h1 { margin: 4px 0 10px; font-size: 26px; }
.welcome-card span { opacity: .82; font-size: 13px; }
.today-board, .report-card, .abnormal-card { display: grid; gap: 10px; padding: 18px; border-radius: 18px; background: white; box-shadow: 0 6px 20px rgba(23, 58, 69, .07); }
.today-board > div:first-child, .report-card header, .abnormal-card > div { display: flex; align-items: center; justify-content: space-between; }
.today-board strong, .report-card header > strong, .abnormal-card strong { color: var(--tpm-primary); font-size: 24px; }
.board-metrics, .severity { display: flex; justify-content: space-between; color: #71838b; font-size: 12px; }
.section-title h2, .section-title p, .report-card h2, .report-card p { margin: 0; }
.section-title p, .report-card p { margin-top: 3px; color: #7b8b92; font-size: 12px; }
.status-grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 8px; }
.status-grid button { display: grid; gap: 4px; padding: 12px 4px; border: 0; border-radius: 14px; background: white; color: #71838b; font-size: 11px; }
.status-grid b { color: #334e58; font-size: 21px; }
.quick-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
.quick-grid button { display: grid; min-height: 112px; padding: 16px; text-align: left; border: 0; border-radius: 18px; color: #213b47; background: white; box-shadow: 0 6px 20px rgba(23, 58, 69, .07); }
.quick-grid .el-icon { margin-bottom: 10px; color: var(--tpm-primary); font-size: 26px; }
.quick-grid span { margin-top: 3px; color: #7c8e95; font-size: 12px; }
.abnormal-card { cursor: pointer; border-left: 4px solid var(--tpm-danger); }
.severity { justify-content: flex-start; gap: 24px; color: var(--tpm-danger); }
.report-metrics { display: grid; grid-template-columns: repeat(3, 1fr); padding-top: 12px; border-top: 1px solid #edf1f2; }
.report-metrics div { display: grid; text-align: center; }
.report-metrics b { font-size: 20px; }
.report-metrics span { color: #87969c; font-size: 11px; }
.danger { color: var(--tpm-danger) !important; }
.success { color: var(--tpm-success, #1c7d50) !important; }
</style>
