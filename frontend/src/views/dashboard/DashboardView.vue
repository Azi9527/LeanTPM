<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { visualizationApi, type DashboardResult, type RecentInspectionRegistration } from '@/api/visualization'
import { inspectionApi, type TaskRow as InspectionTask } from '@/api/inspection'
import { maintenanceApi, type TaskRow as MaintenanceTask } from '@/api/maintenance'
import { notificationApi, type NotificationMessage } from '@/api/notification'
import { useAuthStore } from '@/stores/auth'

type TodoItem = {
  id: number
  type: 'INSPECTION' | 'MAINTENANCE'
  code: string
  title: string
  equipment: string
  dueTime: string
  status: string
  overdue: boolean
}

const auth = useAuthStore()
const router = useRouter()
const loading = ref(false)
const dashboard = ref<DashboardResult>()
const todos = ref<TodoItem[]>([])
const messages = ref<NotificationMessage[]>([])
const lastUpdated = ref('')

const today = new Date()
const range = ref<[Date, Date]>([
  new Date(today.getFullYear(), today.getMonth(), 1),
  new Date(today.getFullYear(), today.getMonth() + 1, 0),
])

const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 11) return '早上好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

const canInspection = computed(() =>
  auth.can('inspection:task:view') || auth.can('inspection:my-task:view'),
)
const canMaintenance = computed(() =>
  auth.can('maintenance:task:view') || auth.can('maintenance:my-task:view'),
)
const canMessages = computed(() => auth.can('notification:message:view'))
const quickActions = computed(() => [
  {
    label: '创建点检任务',
    description: '现场临时登记或补充任务',
    icon: '登',
    tone: 'primary',
    path: '/inspection/tasks',
    query: { create: 'true' },
    visible: auth.can('inspection:task:create'),
  },
  {
    label: '点检任务台账',
    description: '查询计划与现场登记明细',
    icon: '检',
    tone: 'green',
    path: '/inspection/tasks',
    visible: auth.can('inspection:task:view'),
  },
  {
    label: '点检统计',
    description: '查看完成、异常与趋势',
    icon: '统',
    tone: 'blue',
    path: '/inspection/statistics',
    visible: auth.can('inspection:statistics:view'),
  },
  {
    label: '点检计划',
    description: '维护计划型任务指标',
    icon: '计',
    tone: 'amber',
    path: '/inspection/plans',
    visible: auth.can('inspection:plan:view'),
  },
].filter((item) => item.visible))

const abnormalEquipment = computed(() => {
  const core = dashboard.value?.core
  return core ? core.stopped : 0
})
const normalEquipment = computed(() =>
  (dashboard.value?.core.idle ?? 0) + (dashboard.value?.core.running ?? 0),
)
const equipmentHealthRate = computed(() => {
  const total = dashboard.value?.core.total ?? 0
  return total ? normalEquipment.value / total : undefined
})
const taskDue = computed(() =>
  (dashboard.value?.inspection.due ?? 0) + (dashboard.value?.maintenance.due ?? 0),
)
const taskCompleted = computed(() =>
  (dashboard.value?.inspection.completed ?? 0) + (dashboard.value?.maintenance.completed ?? 0),
)
const taskCompletionRate = computed(() =>
  taskDue.value ? taskCompleted.value / taskDue.value : undefined,
)
const inspectionOnTimeRate = computed(() => {
  const inspection = dashboard.value?.inspection
  return inspection?.completed ? inspection.onTimeRate : undefined
})
const oeeRate = computed(() => {
  const summary = dashboard.value?.oee.summary
  return summary?.recordCount ? summary.oeeRate : undefined
})
const openExceptionCount = computed(() =>
  abnormalEquipment.value
  + (dashboard.value?.inspection.abnormal ?? 0)
  + (dashboard.value?.maintenance.abnormal ?? 0),
)

const metrics = computed(() => [
  {
    label: '设备正常率',
    value: percent(equipmentHealthRate.value),
    detail: `${normalEquipment.value} 台正常 / ${dashboard.value?.core.total ?? 0} 台设备`,
    tone: 'green',
    tip: '正常设备口径：空闲与运行；停机设备单独计入异常，报废设备不计入正常。',
  },
  {
    label: '任务完成率',
    value: percent(taskCompletionRate.value),
    detail: `${taskCompleted.value} / ${taskDue.value} 项（点检 + 维保）`,
    tone: 'green',
    tip: '统计所选期间内应完成的点检与维保任务。',
  },
  {
    label: '点检准时完成率',
    value: percent(inspectionOnTimeRate.value),
    detail: `${dashboard.value?.inspection.completed ?? 0} 项已完成`,
    tone: 'blue',
    tip: '完成时间不晚于任务截止时间的点检任务占比。',
  },
  {
    label: 'OEE',
    value: percent(oeeRate.value),
    detail: `${dashboard.value?.oee.summary.recordCount ?? 0} 条已批准记录`,
    tone: 'blue',
    tip: '设备综合效率 = 时间开动率 × 性能开动率 × 合格品率。',
  },
  {
    label: 'MTTR',
    value: duration(dashboard.value?.reliability.mttrSeconds, 'hour'),
    detail: `${dashboard.value?.reliability.completedRepairCount ?? 0} 张已关闭维修单`,
    tone: 'amber',
    tip: '平均修复时间：所选期间关闭维修单的平均有效维修时长。',
  },
  {
    label: 'MTBF',
    value: duration(dashboard.value?.reliability.mtbfSeconds, 'hour'),
    detail: `${dashboard.value?.reliability.faultCount ?? 0} 次故障`,
    tone: 'amber',
    tip: '平均故障间隔：所选期间已批准运行时长 ÷ 有效故障次数。',
  },
  {
    label: 'MTTF',
    value: duration(dashboard.value?.reliability.mttfSeconds, 'day'),
    detail: '投产至首次有效故障',
    tone: 'amber',
    tip: '平均失效前时间：首次故障落在所选期间的设备，从投产到首次故障的平均时长。',
  },
  {
    label: '异常与逾期',
    value: String(openExceptionCount.value
      + (dashboard.value?.inspection.overdue ?? 0)
      + (dashboard.value?.maintenance.overdue ?? 0)),
    detail: `${abnormalEquipment.value} 台异常设备 · ${(dashboard.value?.inspection.overdue ?? 0) + (dashboard.value?.maintenance.overdue ?? 0)} 项逾期`,
    tone: 'red',
    tip: '设备异常、任务异常及逾期任务的运营提醒汇总。',
  },
])

const statusRows = computed(() =>
  (dashboard.value?.statusDistribution ?? [])
    .filter((item) => item.equipmentCount > 0)
    .sort((a, b) => b.equipmentCount - a.equipmentCount),
)

function dateText(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function percent(value?: number) {
  return value === undefined || value === null ? '暂无数据' : `${(Number(value) * 100).toFixed(1)}%`
}

function duration(seconds?: number, unit: 'hour' | 'day' = 'hour') {
  if (seconds === undefined || seconds === null || Number(seconds) < 0) return '暂无数据'
  const divisor = unit === 'day' ? 86400 : 3600
  return `${(Number(seconds) / divisor).toFixed(1)} ${unit === 'day' ? '天' : '小时'}`
}

function readableTime(value?: string) {
  if (!value) return '时间未设置'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function statusText(status: string) {
  const map: Record<string, string> = {
    PENDING_ASSIGNMENT: '待派工', PENDING: '待执行', IN_PROGRESS: '执行中',
    PAUSED: '已暂停', PENDING_CONFIRMATION: '待确认', OVERDUE: '已逾期',
  }
  return map[status] ?? status
}

function sourceTypeText(sourceType: RecentInspectionRegistration['sourceType']) {
  const map: Record<RecentInspectionRegistration['sourceType'], string> = {
    PLAN: '计划任务',
    MANUAL: '手工任务',
    BACKFILL: '补录任务',
    QUICK_ENTRY: '扫码直检',
  }
  return map[sourceType] ?? '现场登记'
}

function openRegistration(item: RecentInspectionRegistration) {
  void router.push({ path: '/inspection/tasks', query: { taskId: item.taskId } })
}

function toTodo(task: InspectionTask | MaintenanceTask, type: TodoItem['type']): TodoItem {
  const due = new Date(task.dueTime).getTime()
  return {
    id: task.id,
    type,
    code: task.taskCode,
    title: type === 'INSPECTION' ? '点检任务' : '维保任务',
    equipment: `${task.equipmentName}（${task.equipmentCode}）`,
    dueTime: task.dueTime,
    status: task.taskStatus,
    overdue: task.taskStatus === 'OVERDUE' || (!Number.isNaN(due) && due < Date.now()),
  }
}

async function loadDashboard() {
  loading.value = true
  try {
    const [start, end] = range.value
    dashboard.value = await visualizationApi.dashboard({
      startDate: dateText(start),
      endDate: dateText(end),
      periodType: 'DAY',
    })

    const sideRequests: Promise<void>[] = []
    if (canInspection.value) {
      sideRequests.push(inspectionApi.tasks({ mineOnly: true, page: 1, pageSize: 50 })
        .then((page) => {
          const active = page.records.filter((task) =>
            !['COMPLETED', 'CANCELLED', 'VOIDED'].includes(task.taskStatus),
          )
          todos.value = todos.value.filter((item) => item.type !== 'INSPECTION')
            .concat(active.map((task) => toTodo(task, 'INSPECTION')))
        }))
    }
    if (canMaintenance.value) {
      sideRequests.push(maintenanceApi.tasks({ mineOnly: true, page: 1, pageSize: 50 })
        .then((page) => {
          const active = page.records.filter((task) =>
            !['COMPLETED', 'CANCELLED', 'VOIDED'].includes(task.taskStatus),
          )
          todos.value = todos.value.filter((item) => item.type !== 'MAINTENANCE')
            .concat(active.map((task) => toTodo(task, 'MAINTENANCE')))
        }))
    }
    if (canMessages.value) {
      sideRequests.push(notificationApi.messages({ unreadOnly: true, page: 1, pageSize: 6 })
        .then((page) => { messages.value = page.records }))
    }
    await Promise.allSettled(sideRequests)
    todos.value = todos.value
      .sort((a, b) => Number(b.overdue) - Number(a.overdue)
        || new Date(a.dueTime).getTime() - new Date(b.dueTime).getTime())
      .slice(0, 8)
    lastUpdated.value = readableTime(dashboard.value.generatedAt)
  } catch (error) {
    ElMessage.error('工作台数据加载失败，请稍后重试')
    console.error(error)
  } finally {
    loading.value = false
  }
}

function openTodo(item: TodoItem) {
  const path = item.type === 'INSPECTION' ? '/inspection/my-tasks' : '/maintenance/my-tasks'
  void router.push({ path, query: { taskId: item.id } })
}

function openMessage(item: NotificationMessage) {
  const path = item.routePath?.startsWith('/') ? item.routePath : '/notifications'
  void router.push(path)
}

onMounted(loadDashboard)
</script>

<template>
  <div class="page-shell dashboard-page" v-loading="loading">
    <header class="dashboard-header">
      <div>
        <p>{{ greeting }}，{{ auth.displayName }}</p>
        <h1>设备运营工作台</h1>
        <span>聚焦我的待办、异常风险与设备绩效，所有指标均来自当前业务数据。</span>
      </div>
      <div class="header-actions">
        <el-date-picker
          v-model="range"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          :clearable="false"
          @change="loadDashboard"
        />
        <el-button type="primary" @click="loadDashboard">刷新数据</el-button>
        <small>更新于 {{ lastUpdated || '—' }}</small>
      </div>
    </header>

    <section class="metric-grid">
      <el-tooltip v-for="metric in metrics" :key="metric.label" :content="metric.tip" placement="top">
        <article class="metric-card" :class="metric.tone">
          <span>{{ metric.label }}</span>
          <strong :class="{ empty: metric.value === '暂无数据' }">{{ metric.value }}</strong>
          <small>{{ metric.detail }}</small>
        </article>
      </el-tooltip>
    </section>

    <section class="operation-grid">
      <article class="surface-card registration-card">
        <div class="section-heading compact-heading">
          <div><span>FIELD REGISTRATION</span><h2>现场点检登记</h2></div>
          <el-button v-if="auth.can('inspection:statistics:view')" link type="primary" @click="router.push('/inspection/statistics')">查看统计</el-button>
        </div>
        <p class="section-note">按实际完成时间统计，与计划任务完成率分别核算。</p>
        <div class="registration-metrics">
          <button type="button" @click="router.push('/inspection/tasks')">
            <strong>{{ dashboard?.inspectionRegistration?.registered ?? 0 }}</strong><span>登记完成</span>
          </button>
          <button type="button" @click="router.push('/inspection/tasks')">
            <strong>{{ dashboard?.inspectionRegistration?.quickRegistered ?? 0 }}</strong><span>扫码直检</span>
          </button>
          <button type="button" @click="router.push('/equipment/ledger')">
            <strong>{{ dashboard?.inspectionRegistration?.equipmentCovered ?? 0 }}</strong><span>覆盖设备</span>
          </button>
          <button class="danger" type="button" @click="router.push('/inspection/abnormal')">
            <strong>{{ dashboard?.inspectionRegistration?.abnormalRegistered ?? 0 }}</strong><span>异常登记</span>
          </button>
        </div>
      </article>

      <article class="surface-card recent-card">
        <div class="section-heading compact-heading">
          <div><span>LATEST RECORDS</span><h2>最近点检登记</h2></div>
          <el-button v-if="auth.can('inspection:task:view')" link type="primary" @click="router.push('/inspection/tasks')">全部记录</el-button>
        </div>
        <div v-if="dashboard?.recentInspectionRegistrations?.length" class="registration-list">
          <button
            v-for="item in dashboard.recentInspectionRegistrations"
            :key="item.taskId"
            type="button"
            @click="openRegistration(item)"
          >
            <span class="registration-main">
              <strong>{{ item.equipmentName }}（{{ item.equipmentCode }}）</strong>
              <small>{{ item.taskCode }} · {{ item.schemeName }}</small>
            </span>
            <span class="registration-meta">
              <el-tag size="small" :type="item.sourceType === 'QUICK_ENTRY' ? 'success' : 'info'">{{ sourceTypeText(item.sourceType) }}</el-tag>
              <small>{{ item.executorName }} · {{ readableTime(item.completedTime) }}</small>
            </span>
            <el-tag v-if="item.abnormalCount" size="small" type="danger">{{ item.abnormalCount }} 项异常</el-tag>
            <span v-else class="normal-result">正常</span>
          </button>
        </div>
        <el-empty v-else description="所选日期暂无点检登记" :image-size="58" />
      </article>

      <article class="surface-card quick-card">
        <div class="section-heading compact-heading">
          <div><span>QUICK ACCESS</span><h2>常用入口</h2></div>
        </div>
        <div v-if="quickActions.length" class="quick-actions">
          <button
            v-for="item in quickActions"
            :key="item.label"
            type="button"
            :class="item.tone"
            @click="router.push({ path: item.path, query: item.query })"
          >
            <i>{{ item.icon }}</i>
            <span><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span>
          </button>
        </div>
        <el-empty v-else description="暂无可用快捷入口" :image-size="55" />
      </article>
    </section>

    <section class="dashboard-grid">
      <article class="surface-card todo-card">
        <div class="section-heading">
          <div><span>MY TODO</span><h2>我的待办</h2></div>
          <el-tag type="danger" effect="plain">{{ todos.length }} 项待处理</el-tag>
        </div>
        <div v-if="todos.length" class="todo-list">
          <button v-for="item in todos" :key="`${item.type}-${item.id}`" class="todo-row" @click="openTodo(item)">
            <i :class="item.type.toLowerCase()">{{ item.type === 'INSPECTION' ? '检' : '保' }}</i>
            <span class="todo-copy">
              <strong>{{ item.equipment }}</strong>
              <small>{{ item.code }} · 截止 {{ readableTime(item.dueTime) }}</small>
            </span>
            <el-tag :type="item.overdue ? 'danger' : 'warning'" size="small">{{ statusText(item.status) }}</el-tag>
          </button>
        </div>
        <el-empty v-else description="当前没有待办任务" :image-size="70" />
      </article>

      <article class="surface-card message-card">
        <div class="section-heading">
          <div><span>EXCEPTION</span><h2>异常消息</h2></div>
          <el-button v-if="canMessages" link type="primary" @click="router.push('/notifications')">全部消息</el-button>
        </div>
        <div class="exception-summary">
          <router-link to="/equipment/statuses" class="exception-summary-link" aria-label="查看异常设备明细">
            <strong>{{ abnormalEquipment }}</strong><span>异常设备</span>
          </router-link>
          <div><strong>{{ (dashboard?.inspection.overdue ?? 0) + (dashboard?.maintenance.overdue ?? 0) }}</strong><span>逾期任务</span></div>
          <div><strong>{{ dashboard?.inspection.abnormal ?? 0 }}</strong><span>点检异常</span></div>
        </div>
        <div v-if="messages.length" class="message-list">
          <button v-for="item in messages" :key="item.id" @click="openMessage(item)">
            <span class="severity-dot" :class="item.severity.toLowerCase()" />
            <span><strong>{{ item.title }}</strong><small>{{ item.content }}</small></span>
            <time>{{ readableTime(item.occurredTime) }}</time>
          </button>
        </div>
        <el-empty v-else description="暂无未读异常消息" :image-size="62" />
      </article>
    </section>

    <section class="surface-card status-card">
      <div class="section-heading">
        <div><span>EQUIPMENT HEALTH</span><h2>设备状态分布</h2></div>
        <p>正常 {{ normalEquipment }} 台 · 异常 {{ abnormalEquipment }} 台</p>
      </div>
      <div class="health-bar" aria-label="设备正常异常比例">
        <span class="normal" :style="{ width: `${(equipmentHealthRate ?? 0) * 100}%` }" />
        <span class="abnormal" :style="{ width: `${100 - (equipmentHealthRate ?? 0) * 100}%` }" />
      </div>
      <div class="status-list">
        <div v-for="item in statusRows" :key="item.statusCode">
          <i :style="{ backgroundColor: item.displayColor }" />
          <span>{{ item.statusName }}</span>
          <strong>{{ item.equipmentCount }}</strong>
          <small>{{ dashboard?.core.total ? `${(item.equipmentCount / dashboard.core.total * 100).toFixed(1)}%` : '—' }}</small>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped lang="scss">
.dashboard-page { gap: 16px; }

.dashboard-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 145px;
  padding: 26px 30px;
  border-radius: 14px;
  color: #fff;
  background: radial-gradient(circle at 82% 20%, rgb(90 174 126 / 42%), transparent 28%), linear-gradient(115deg, #123b2d, #1c7d50);

  p { margin: 0 0 7px; color: #bfe1ce; font-size: 13px; }
  h1 { margin: 0 0 8px; font-size: 27px; }
  span { color: #d8eadf; font-size: 13px; }
}

.header-actions {
  display: grid;
  grid-template-columns: minmax(260px, 330px) auto;
  gap: 8px;
  align-items: center;
  position: relative;
  z-index: 1;
  small { grid-column: 1 / -1; color: #c6ded0; text-align: right; }
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.metric-card {
  min-height: 125px;
  padding: 18px 20px;
  border: 1px solid var(--tpm-border);
  border-top: 3px solid #1c7d50;
  border-radius: 12px;
  background: #fff;
  span, small { display: block; color: var(--tpm-text-secondary); }
  span { font-size: 13px; font-weight: 650; }
  strong { display: block; margin: 9px 0 7px; color: #163528; font-size: 27px; line-height: 1; }
  strong.empty { color: #8b9690; font-size: 19px; }
  small { font-size: 11px; }
  &.blue { border-top-color: #3685b5; }
  &.amber { border-top-color: #d49a25; }
  &.red { border-top-color: #c4000a; strong { color: #a90912; } }
}

.operation-grid {
  display: grid;
  grid-template-columns: minmax(250px, .8fr) minmax(480px, 1.5fr) minmax(250px, .8fr);
  gap: 16px;
}
.registration-card, .recent-card, .quick-card { min-height: 275px; padding: 20px; }
.compact-heading { margin-bottom: 8px; }
.section-note { margin: 0 0 15px; color: var(--tpm-text-secondary); font-size: 11px; }
.registration-metrics { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
.registration-metrics button {
  display: grid; gap: 5px; padding: 17px 12px; border: 1px solid #e5ece8; border-radius: 10px;
  color: #263b31; background: linear-gradient(145deg, #f8fcfa, #fff); cursor: pointer; text-align: left;
  strong { color: #137647; font-size: 25px; line-height: 1; }
  span { color: #74827b; font-size: 11px; }
  &:hover { border-color: #7db997; box-shadow: 0 7px 18px rgb(19 118 71 / 9%); transform: translateY(-1px); }
  &.danger strong { color: #b31c25; }
}
.registration-list { display: grid; gap: 7px; }
.registration-list > button {
  display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(180px, .9fr) auto; gap: 12px; align-items: center;
  width: 100%; padding: 9px 11px; border: 1px solid #e6ebe8; border-radius: 9px; background: #fff; cursor: pointer; text-align: left;
  &:hover { border-color: #82bd9b; background: #f7fbf9; }
}
.registration-main, .registration-meta { display: grid; gap: 3px; min-width: 0; }
.registration-main strong, .registration-main small, .registration-meta small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.registration-main strong { color: #23352d; font-size: 12px; }
.registration-main small, .registration-meta small { color: #87928c; font-size: 9px; }
.registration-meta { justify-items: start; }
.normal-result { color: #1c7d50; font-size: 11px; font-weight: 700; }
.quick-actions { display: grid; gap: 9px; }
.quick-actions > button {
  display: flex; align-items: center; gap: 10px; padding: 11px; border: 1px solid #e6ebe8; border-radius: 10px;
  background: #fff; cursor: pointer; text-align: left;
  i { display: grid; flex: 0 0 34px; place-items: center; width: 34px; height: 34px; border-radius: 9px; color: #fff; background: #1c7d50; font-style: normal; font-weight: 750; }
  span { display: grid; gap: 3px; min-width: 0; }
  strong { color: #24362e; font-size: 12px; }
  small { color: #87928c; font-size: 9px; }
  &:hover { border-color: #8cc4a5; background: #f6fbf8; }
  &.blue i { background: #3685b5; }
  &.amber i { background: #bd811e; }
  &.primary i { background: #b31c25; }
}

.dashboard-grid { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(360px, .9fr); gap: 16px; }
.todo-card, .message-card, .status-card { padding: 21px; }

.section-heading {
  display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 15px;
  span { color: var(--tpm-primary); font-size: 9px; font-weight: 800; letter-spacing: .13em; }
  h2 { margin: 3px 0 0; color: #22332b; font-size: 18px; }
  p { margin: 8px 0 0; color: var(--tpm-text-secondary); font-size: 12px; }
}

.todo-list, .message-list { display: grid; gap: 7px; }
.todo-row, .message-list button {
  width: 100%; border: 1px solid #e6ebe8; border-radius: 9px; background: #fff; cursor: pointer; text-align: left;
  &:hover { border-color: #8cc4a5; background: #f6fbf8; }
}
.todo-row { display: flex; align-items: center; gap: 11px; padding: 10px 12px; }
.todo-row > i { display: grid; flex: 0 0 34px; place-items: center; width: 34px; height: 34px; border-radius: 9px; color: #fff; background: #1c7d50; font-style: normal; font-weight: 750; }
.todo-row > i.maintenance { background: #b77a16; }
.todo-copy { display: grid; flex: 1; gap: 3px; min-width: 0; strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; } small { color: #849089; font-size: 11px; } }

.exception-summary { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-bottom: 12px; }
.exception-summary > :is(div, a) { padding: 11px 8px; border-radius: 8px; text-align: center; background: #f6f8f7; strong { display: block; color: #b31c25; font-size: 21px; } span { color: #78837d; font-size: 11px; } }
.exception-summary-link { color: inherit; text-decoration: none; cursor: pointer; transition: border-color .18s ease, background .18s ease, transform .18s ease; border: 1px solid transparent; }
.exception-summary-link:hover, .exception-summary-link:focus-visible { border-color: #79b992; background: #f0f9f4; transform: translateY(-1px); outline: none; }
.message-list button { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: 9px; align-items: center; padding: 9px 10px; }
.message-list button > span:nth-child(2) { display: grid; gap: 2px; min-width: 0; strong, small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } strong { font-size: 12px; } small { color: #849089; font-size: 10px; } }
.message-list time { color: #9aa39e; font-size: 9px; }
.severity-dot { width: 7px; height: 7px; border-radius: 50%; background: #d49a25; &.high, &.critical { background: #c4000a; } &.low { background: #1c7d50; } }

.health-bar { display: flex; overflow: hidden; height: 12px; margin-bottom: 18px; border-radius: 999px; background: #edf1ef; .normal { background: #1c7d50; } .abnormal { background: #c4000a; } }
.status-list { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 10px; }
.status-list > div { display: grid; grid-template-columns: auto 1fr auto; gap: 7px; align-items: center; padding: 11px; border: 1px solid #edf0ee; border-radius: 8px; i { width: 8px; height: 8px; border-radius: 50%; } span { font-size: 12px; } strong { font-size: 16px; } small { grid-column: 2 / -1; color: #8b9690; font-size: 10px; } }

@media (max-width: 1200px) {
  .metric-grid { grid-template-columns: repeat(2, 1fr); }
  .operation-grid { grid-template-columns: 1fr 1.4fr; }
  .quick-card { grid-column: 1 / -1; min-height: auto; }
  .quick-actions { grid-template-columns: repeat(4, 1fr); }
  .dashboard-grid { grid-template-columns: 1fr; }
  .status-list { grid-template-columns: repeat(3, 1fr); }
}
@media (max-width: 760px) {
  .dashboard-header { align-items: flex-start; flex-direction: column; gap: 16px; }
  .header-actions { width: 100%; grid-template-columns: 1fr; small { grid-column: 1; text-align: left; } }
  .metric-grid { grid-template-columns: 1fr; }
  .operation-grid { grid-template-columns: 1fr; }
  .quick-card { grid-column: auto; }
  .quick-actions { grid-template-columns: 1fr; }
  .registration-list > button { grid-template-columns: 1fr auto; }
  .registration-meta { grid-column: 1; }
  .status-list { grid-template-columns: repeat(2, 1fr); }
}
</style>
