<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  inspectionApi,
  type InspectionAttachmentRow,
  type Statistics,
  type TaskDetail,
  type TaskRow,
  type TaskStatus,
} from '@/api/inspection'
import { masterDataApi, type OrganizationRow } from '@/api/masterData'
import InspectionAttachmentList from '@/components/InspectionAttachmentList.vue'
import { errorMessage } from '@/utils/http'
import { applySmartTableQuery, type SmartTableServerQuery } from '@/components/table/smart-table-context'

const loading = ref(false)
const detailLoading = ref(false)
const detailVisible = ref(false)
const rows = ref<TaskRow[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const organizations = ref<OrganizationRow[]>([])
const detail = ref<TaskDetail | null>(null)
const attachments = ref<InspectionAttachmentRow[]>([])
const refreshedAt = ref('')
const activeShortcut = ref<'TODAY' | 'WEEK' | 'MONTH' | 'LAST_MONTH' | 'CUSTOM'>('MONTH')
const smartTableQuery = reactive({
  tableFilters: undefined as string | undefined,
  sortBy: undefined as string | undefined,
  sortDirection: undefined as 'asc' | 'desc' | undefined,
})

const summary = ref<Statistics>({
  dueToday: 0,
  completedToday: 0,
  pendingToday: 0,
  overdue: 0,
  abnormal: 0,
  completionRate: 0,
  onTimeRate: 0,
})

const filters = reactive({
  dateRange: currentMonthRange() as [string, string],
  organizationId: undefined as number | undefined,
  taskStatus: undefined as TaskStatus | undefined,
  keyword: '',
})

const statusMeta: Record<TaskStatus, { label: string; type: '' | 'success' | 'warning' | 'danger' | 'info' }> = {
  PENDING: { label: '待执行', type: 'info' },
  IN_PROGRESS: { label: '执行中', type: 'warning' },
  PENDING_REVIEW: { label: '已完成', type: 'success' },
  COMPLETED: { label: '已完成', type: 'success' },
  OVERDUE: { label: '已逾期', type: 'danger' },
  CANCELLED: { label: '已取消', type: 'info' },
  VOIDED: { label: '已作废', type: 'info' },
}

const periodLabel = computed(() => `${filters.dateRange[0]} 至 ${filters.dateRange[1]}`)
const incomplete = computed(() => summary.value.pendingToday + summary.value.overdue)
const normalTasks = computed(() => Math.max(0, summary.value.dueToday - summary.value.abnormal))
const abnormalRate = computed(() => summary.value.dueToday
  ? Number(((summary.value.abnormal / summary.value.dueToday) * 100).toFixed(1))
  : 0)
const completionSegments = computed(() => [
  { label: '已完成', value: summary.value.completedToday, color: '#1c7d50' },
  { label: '待完成', value: summary.value.pendingToday, color: '#d99a18' },
  { label: '已逾期', value: summary.value.overdue, color: '#c4000a' },
])

onMounted(async () => {
  await Promise.all([loadOrganizations(), load()])
})

async function loadOrganizations() {
  try {
    organizations.value = (await masterDataApi.organizations()).filter((row) => row.status === 1)
  } catch (error) {
    ElMessage.error(errorMessage(error, '组织数据加载失败'))
  }
}

async function load() {
  loading.value = true
  try {
    const params = {
      startDate: filters.dateRange[0],
      endDate: filters.dateRange[1],
      organizationId: filters.organizationId,
    }
    const [statistics, tasks] = await Promise.all([
      inspectionApi.statistics(params),
      inspectionApi.tasks({
        ...params,
        ...smartTableQuery,
        keyword: filters.keyword || undefined,
        taskStatus: filters.taskStatus,
        timeField: 'PLANNED_DATE',
        page: page.value,
        pageSize: pageSize.value,
      }),
    ])
    summary.value = statistics
    rows.value = tasks.records
    total.value = tasks.total
    refreshedAt.value = formatDateTime(new Date())
  } catch (error) {
    ElMessage.error(errorMessage(error, '点检统计加载失败'))
  } finally {
    loading.value = false
  }
}

function applyTableQuery(query: SmartTableServerQuery) {
  applySmartTableQuery(smartTableQuery, query)
  page.value = 1
  load()
}

function applyShortcut(shortcut: typeof activeShortcut.value) {
  const today = startOfDay(new Date())
  activeShortcut.value = shortcut
  if (shortcut === 'TODAY') filters.dateRange = [dateText(today), dateText(today)]
  if (shortcut === 'WEEK') {
    const monday = new Date(today)
    monday.setDate(today.getDate() - ((today.getDay() + 6) % 7))
    filters.dateRange = [dateText(monday), dateText(today)]
  }
  if (shortcut === 'MONTH') filters.dateRange = currentMonthRange()
  if (shortcut === 'LAST_MONTH') {
    const first = new Date(today.getFullYear(), today.getMonth() - 1, 1)
    const last = new Date(today.getFullYear(), today.getMonth(), 0)
    filters.dateRange = [dateText(first), dateText(last)]
  }
  page.value = 1
  load()
}

function search() {
  activeShortcut.value = 'CUSTOM'
  page.value = 1
  load()
}

function reset() {
  filters.dateRange = currentMonthRange()
  filters.organizationId = undefined
  filters.taskStatus = undefined
  filters.keyword = ''
  activeShortcut.value = 'MONTH'
  page.value = 1
  load()
}

async function openDetail(row: TaskRow) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  attachments.value = []
  try {
    const [taskDetail, taskAttachments] = await Promise.all([
      inspectionApi.task(row.id),
      inspectionApi.taskAttachments(row.id),
    ])
    detail.value = taskDetail
    attachments.value = taskAttachments
  } catch (error) {
    ElMessage.error(errorMessage(error, '点检明细加载失败'))
  } finally {
    detailLoading.value = false
  }
}

function resultText(item: TaskDetail['items'][number]) {
  const result = item.result
  if (!result) return '未填写'
  if (result.skippedFlag) return `已跳过：${result.skipReason || '未填写原因'}`
  if (result.numericValue !== undefined && result.numericValue !== null) {
    return `${result.numericValue}${item.unit || ''}`
  }
  return result.textValue || result.selectedValue || result.resultCode || '已填写'
}

function progress(row: TaskRow) {
  if (!row.itemCount) return 0
  return Math.min(100, Math.round((row.completedItemCount / row.itemCount) * 100))
}

function taskStatusMeta(status: TaskStatus) {
  return statusMeta[status] || { label: status, type: 'info' as const }
}

function currentMonthRange(): [string, string] {
  const today = startOfDay(new Date())
  return [dateText(new Date(today.getFullYear(), today.getMonth(), 1)), dateText(today)]
}

function startOfDay(value: Date) {
  return new Date(value.getFullYear(), value.getMonth(), value.getDate())
}

function dateText(value: Date) {
  const year = value.getFullYear()
  const month = `${value.getMonth() + 1}`.padStart(2, '0')
  const day = `${value.getDate()}`.padStart(2, '0')
  return `${year}-${month}-${day}`
}

function formatDateTime(value: Date) {
  return `${dateText(value)} ${`${value.getHours()}`.padStart(2, '0')}:${`${value.getMinutes()}`.padStart(2, '0')}:${`${value.getSeconds()}`.padStart(2, '0')}`
}
</script>

<template>
  <div class="page-shell statistics-page" v-loading="loading">
    <header class="page-header statistics-header">
      <div>
        <span class="eyebrow">INSPECTION PERFORMANCE</span>
        <h1>点检统计分析</h1>
        <p>以任务为主线查看完成、准时、逾期与异常情况，所有数据均受当前账号组织范围约束。</p>
      </div>
      <div class="header-meta"><span>统计周期</span><strong>{{ periodLabel }}</strong><small>更新于 {{ refreshedAt || '—' }}</small></div>
    </header>

    <section class="surface-card filter-card">
      <div class="shortcut-row">
        <el-button :type="activeShortcut === 'MONTH' ? 'primary' : 'default'" @click="applyShortcut('MONTH')">本月</el-button>
        <el-button :type="activeShortcut === 'LAST_MONTH' ? 'primary' : 'default'" @click="applyShortcut('LAST_MONTH')">上月</el-button>
        <el-button :type="activeShortcut === 'WEEK' ? 'primary' : 'default'" @click="applyShortcut('WEEK')">本周</el-button>
        <el-button :type="activeShortcut === 'TODAY' ? 'primary' : 'default'" @click="applyShortcut('TODAY')">今天</el-button>
      </div>
      <div class="filter-grid">
        <el-date-picker v-model="filters.dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" />
        <el-select v-model="filters.organizationId" clearable filterable placeholder="全部组织">
          <el-option v-for="org in organizations" :key="org.id" :label="org.organizationName" :value="org.id" />
        </el-select>
        <el-select v-model="filters.taskStatus" clearable placeholder="全部任务状态">
          <el-option v-for="(meta, key) in statusMeta" :key="key" :label="meta.label" :value="key" />
        </el-select>
        <el-input v-model="filters.keyword" clearable placeholder="任务、设备或方案" @keyup.enter="search" />
        <div class="filter-actions"><el-button type="primary" @click="search">查询</el-button><el-button @click="reset">重置</el-button></div>
      </div>
    </section>

    <section class="metric-grid">
      <article class="metric-card total"><span>应完成任务</span><strong>{{ summary.dueToday }}</strong><small>统计周期内全部计划任务</small></article>
      <article class="metric-card completed"><span>已完成</span><strong>{{ summary.completedToday }}</strong><small>完成率 {{ summary.completionRate }}%</small></article>
      <article class="metric-card pending"><span>待完成</span><strong>{{ incomplete }}</strong><small>待执行 {{ summary.pendingToday }} · 逾期 {{ summary.overdue }}</small></article>
      <article class="metric-card abnormal"><span>异常任务</span><strong>{{ summary.abnormal }}</strong><small>异常率 {{ abnormalRate }}%</small></article>
      <article class="metric-card ontime"><span>准时完成率</span><strong>{{ summary.onTimeRate }}%</strong><small>完成时间不晚于截止时间</small></article>
    </section>

    <section class="analysis-grid">
      <article class="surface-card completion-card">
        <div class="card-title"><div><h2>任务完成率</h2><p>已完成任务 ÷ 应完成任务</p></div><el-tag :type="Number(summary.completionRate) >= 90 ? 'success' : 'warning'">{{ Number(summary.completionRate) >= 90 ? '达成良好' : '需要关注' }}</el-tag></div>
        <div class="completion-content">
          <el-progress type="dashboard" :percentage="Number(summary.completionRate)" :width="190" :stroke-width="16" color="#1c7d50" />
          <div class="legend-list"><div v-for="item in completionSegments" :key="item.label"><i :style="{ background: item.color }" /><span>{{ item.label }}</span><strong>{{ item.value }} 项</strong></div></div>
        </div>
      </article>
      <article class="surface-card quality-card">
        <div class="card-title"><div><h2>点检质量概览</h2><p>准时性与异常压力对比</p></div></div>
        <div class="quality-row"><span>准时完成</span><el-progress :percentage="Number(summary.onTimeRate)" color="#1c7d50" /><strong>{{ summary.onTimeRate }}%</strong></div>
        <div class="quality-row"><span>正常任务</span><el-progress :percentage="summary.dueToday ? Math.round(normalTasks / summary.dueToday * 100) : 0" color="#2f8f68" /><strong>{{ normalTasks }}</strong></div>
        <div class="quality-row"><span>异常任务</span><el-progress :percentage="abnormalRate" color="#c4000a" /><strong>{{ summary.abnormal }}</strong></div>
      </article>
    </section>

    <section class="surface-card ledger-card">
      <div class="card-title ledger-title"><div><h2>点检任务明细</h2><p>共 {{ total }} 条，点击“查看明细”可查看项目结果、现场图片和事件轨迹</p></div></div>
      <el-table :data="rows" stripe server-query empty-text="当前筛选范围内暂无点检任务" @smart-query-change="applyTableQuery">
        <el-table-column prop="taskCode" label="任务编号" min-width="170" />
        <el-table-column prop="equipmentName" label="设备" min-width="180"><template #default="{ row }"><strong>{{ row.equipmentName }}</strong><div class="muted">{{ row.equipmentCode }}</div></template></el-table-column>
        <el-table-column prop="organizationName" label="组织" min-width="130" />
        <el-table-column prop="schemeNameSnapshot" label="点检方案" min-width="170" show-overflow-tooltip />
        <el-table-column prop="plannedDate" label="计划日期" smart-filter="date" width="120" />
        <el-table-column prop="assigneeName" label="执行人" min-width="140"><template #default="{ row }">{{ row.assigneeName || '未派工' }}</template></el-table-column>
        <el-table-column prop="completedItemCount" label="完成进度" smart-filter="number" width="150"><template #default="{ row }"><el-progress :percentage="progress(row)" :stroke-width="8" /><span class="progress-text">{{ row.completedItemCount }}/{{ row.itemCount }}</span></template></el-table-column>
        <el-table-column label="异常" width="85" align="center"><template #default="{ row }"><el-tag v-if="row.abnormalItemCount" type="danger">{{ row.abnormalItemCount }} 项</el-tag><span v-else>—</span></template></el-table-column>
        <el-table-column prop="taskStatus" label="状态" smart-filter="select" width="105"><template #default="{ row }"><el-tag :type="taskStatusMeta(row.taskStatus).type">{{ taskStatusMeta(row.taskStatus).label }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="105" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row)">查看明细</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" v-model:page-size="pageSize" layout="total, sizes, prev, pager, next" :page-sizes="[20, 50, 100]" :total="total" @change="load" />
    </section>

    <el-drawer v-model="detailVisible" :title="`点检明细 · ${detail?.task.taskCode || ''}`" size="min(980px, 98vw)">
      <div v-loading="detailLoading" class="detail-body">
        <template v-if="detail">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="设备">{{ detail.task.equipmentName }}（{{ detail.task.equipmentCode }}）</el-descriptions-item>
            <el-descriptions-item label="点检方案">{{ detail.task.schemeNameSnapshot }}</el-descriptions-item>
            <el-descriptions-item label="组织/位置">{{ detail.task.organizationName }} / {{ detail.task.locationName || '未设置' }}</el-descriptions-item>
            <el-descriptions-item label="执行人">{{ detail.task.assigneeName || '未派工' }}</el-descriptions-item>
            <el-descriptions-item label="计划/截止">{{ detail.task.plannedDate }} / {{ detail.task.dueTime }}</el-descriptions-item>
            <el-descriptions-item label="完成时间">{{ detail.task.completedTime || '未完成' }}</el-descriptions-item>
          </el-descriptions>

          <section class="detail-section"><h3>项目执行明细</h3>
            <el-table :data="detail.items" border size="small">
              <el-table-column type="index" label="#" width="48" />
              <el-table-column prop="itemName" label="点检项目" min-width="140" />
              <el-table-column prop="inspectionContent" label="点检内容" min-width="180" show-overflow-tooltip />
              <el-table-column prop="inspectionStandard" label="标准" min-width="200" show-overflow-tooltip />
              <el-table-column label="结果" min-width="150"><template #default="{ row }">{{ resultText(row) }}</template></el-table-column>
              <el-table-column label="判定" width="90"><template #default="{ row }"><el-tag v-if="row.result?.abnormalFlag" type="danger">异常</el-tag><el-tag v-else-if="row.result" type="success">正常</el-tag><span v-else>未填写</span></template></el-table-column>
              <el-table-column label="异常说明" min-width="160"><template #default="{ row }">{{ row.result?.abnormalDescription || '—' }}</template></el-table-column>
              <el-table-column label="执行信息" min-width="170"><template #default="{ row }"><span>{{ row.result?.executedByName || '—' }}</span><div class="muted">{{ row.result?.executedTime || '' }}</div></template></el-table-column>
            </el-table>
          </section>

          <section class="detail-section"><div class="detail-title"><h3>现场图片与附件</h3><el-tag v-if="attachments.length" type="success">{{ attachments.length }} 个</el-tag></div>
            <InspectionAttachmentList :attachments="attachments" :load-content="(id) => inspectionApi.taskAttachmentContent(detail!.task.id, id)" empty-text="本任务暂无现场附件" />
          </section>

          <section class="detail-section"><h3>异常记录</h3>
            <el-table :data="detail.abnormalities" size="small" empty-text="本任务无异常记录"><el-table-column prop="abnormalCode" label="异常编号" min-width="150" /><el-table-column prop="itemName" label="点检项目" min-width="140" /><el-table-column prop="abnormalDescription" label="异常描述" min-width="220" /><el-table-column prop="severity" label="等级" width="90" /><el-table-column prop="abnormalStatus" label="状态" width="110" /></el-table>
          </section>

          <section class="detail-section"><h3>事件轨迹</h3><el-timeline><el-timeline-item v-for="event in detail.events" :key="event.id" :timestamp="event.eventTime"><strong>{{ event.eventType }}</strong><span> {{ event.eventRemark || '' }}</span><div class="muted">{{ event.operatorName || '系统' }}</div></el-timeline-item></el-timeline></section>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.statistics-page { display: grid; gap: 16px; }
.statistics-header { padding: 22px 24px; border-radius: 14px; color: #fff; background: linear-gradient(125deg, #113e2d, #1c7d50 65%, #2d9967); }
.statistics-header h1 { margin: 4px 0 6px; color: #fff; }
.statistics-header p { margin: 0; color: rgba(255,255,255,.78); }
.eyebrow { color: #8fe1b8; font-size: 11px; font-weight: 800; letter-spacing: .14em; }
.header-meta { display: grid; justify-items: end; gap: 4px; }
.header-meta span, .header-meta small { color: rgba(255,255,255,.7); }
.header-meta strong { font-size: 16px; }
.filter-card { display: grid; gap: 14px; }
.shortcut-row { display: flex; gap: 8px; }
.shortcut-row :deep(.el-button + .el-button) { margin-left: 0; }
.filter-grid { display: grid; grid-template-columns: minmax(280px, 1.4fr) repeat(3, minmax(150px, 1fr)) auto; gap: 10px; }
.filter-actions { display: flex; }
.metric-grid { display: grid; grid-template-columns: repeat(5, minmax(150px, 1fr)); gap: 12px; }
.metric-card { position: relative; display: grid; gap: 7px; padding: 18px; overflow: hidden; border: 1px solid var(--tpm-border); border-radius: 12px; background: #fff; }
.metric-card::before { content: ''; position: absolute; inset: 0 auto 0 0; width: 4px; background: var(--metric-color); }
.metric-card span { color: var(--el-text-color-secondary); }
.metric-card strong { color: var(--metric-color); font-size: 32px; line-height: 1; }
.metric-card small { color: var(--el-text-color-placeholder); }
.metric-card.total { --metric-color: #1976a3; }.metric-card.completed,.metric-card.ontime { --metric-color: #1c7d50; }.metric-card.pending { --metric-color: #d08a00; }.metric-card.abnormal { --metric-color: #c4000a; }
.analysis-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.card-title { display: flex; justify-content: space-between; align-items: flex-start; gap: 18px; }
.card-title h2 { margin: 0 0 4px; font-size: 18px; }.card-title p { margin: 0; color: var(--el-text-color-secondary); }
.completion-content { display: grid; grid-template-columns: 210px 1fr; align-items: center; margin-top: 18px; }
.legend-list { display: grid; gap: 14px; }.legend-list div { display: grid; grid-template-columns: 10px 1fr auto; align-items: center; gap: 10px; }.legend-list i { width: 9px; height: 9px; border-radius: 50%; }
.quality-card { display: grid; gap: 18px; }.quality-row { display: grid; grid-template-columns: 82px 1fr 48px; align-items: center; gap: 12px; }
.ledger-card { overflow: hidden; padding: 0; }.ledger-title { padding: 18px 20px; border-bottom: 1px solid var(--tpm-border); }.ledger-card :deep(.el-pagination) { padding: 14px 18px; justify-content: flex-end; }
.muted { margin-top: 3px; color: var(--el-text-color-secondary); font-size: 12px; }.progress-text { color: var(--el-text-color-secondary); font-size: 11px; }
.detail-body { min-height: 320px; }.detail-section { margin-top: 20px; }.detail-section h3 { margin: 0 0 12px; }.detail-title { display: flex; align-items: center; justify-content: space-between; }
@media (max-width: 1180px) { .metric-grid { grid-template-columns: repeat(3, 1fr); }.filter-grid { grid-template-columns: repeat(2, 1fr); }.filter-actions { grid-column: 1 / -1; }.analysis-grid { grid-template-columns: 1fr; } }
@media (max-width: 720px) { .statistics-header { align-items: flex-start; }.header-meta { justify-items: start; }.metric-grid,.filter-grid { grid-template-columns: 1fr; }.completion-content { grid-template-columns: 1fr; }.shortcut-row { flex-wrap: wrap; } }
</style>
