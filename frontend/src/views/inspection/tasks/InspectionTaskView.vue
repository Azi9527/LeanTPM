<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pinyin } from 'pinyin-pro'
import { inspectionApi, type SchemeRow, type TaskDetail, type TaskRow, type TaskStatus } from '@/api/inspection'
import { equipmentApi, type EquipmentRow } from '@/api/equipment'
import { masterDataApi, type OrganizationRow, type ReferenceUser } from '@/api/masterData'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const rows = ref<TaskRow[]>([])
const total = ref(0)
const page = ref(1)
const keyword = ref('')
const status = ref<TaskStatus>()
const detailVisible = ref(false)
const createVisible = ref(false)
const assignVisible = ref(false)
const assigning = ref(false)
const detail = ref<TaskDetail | null>(null)
const assignTarget = ref<TaskRow | null>(null)
const equipment = ref<EquipmentRow[]>([])
const schemes = ref<SchemeRow[]>([])
const users = ref<ReferenceUser[]>([])
const teams = ref<OrganizationRow[]>([])
const userKeyword = ref('')

const createForm = reactive({
  equipmentId: undefined as number | undefined,
  schemeVersionId: undefined as number | undefined,
  plannedDate: new Date().toISOString().slice(0, 10),
  plannedStartTime: '',
  dueTime: '',
  assigneeUserIds: [] as number[],
  teamCode: '',
  backfill: false,
  remark: '',
})

const assignForm = reactive({
  assigneeUserIds: [] as number[],
  teamCode: '',
})

const filteredUsers = computed(() => {
  const keyword = normalizeSearch(userKeyword.value)
  if (!keyword) return users.value
  return users.value.filter((user) => userSearchText(user).includes(keyword))
})

const statusMeta: Record<TaskStatus, { label: string; type: '' | 'success' | 'warning' | 'danger' | 'info' }> = {
  PENDING: { label: '待执行', type: 'info' },
  IN_PROGRESS: { label: '执行中', type: 'warning' },
  PENDING_REVIEW: { label: '待复核', type: '' },
  COMPLETED: { label: '已完成', type: 'success' },
  OVERDUE: { label: '已逾期', type: 'danger' },
  CANCELLED: { label: '已取消', type: 'info' },
  VOIDED: { label: '已作废', type: 'info' },
}

onMounted(async () => {
  await Promise.all([load(), loadReferences()])
})

async function load() {
  loading.value = true
  try {
    const result = await inspectionApi.tasks({
      keyword: keyword.value || undefined,
      taskStatus: status.value,
      page: page.value,
      pageSize: 20,
    })
    rows.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function loadReferences() {
  try {
    const [equipmentPage, schemePage, userRows, organizationRows] = await Promise.all([
      equipmentApi.page({ status: 1, page: 1, pageSize: 200 }),
      inspectionApi.schemes({ status: 1, page: 1, pageSize: 200 }),
      masterDataApi.referenceUsers(),
      masterDataApi.organizations(),
    ])
    equipment.value = equipmentPage.records
    schemes.value = schemePage.records.filter((row) => row.currentVersionStatus === 'PUBLISHED')
    users.value = userRows
    teams.value = organizationRows.filter(
      (row) => row.organizationType === 'TEAM' && row.status === 1,
    )
  } catch (error) {
    ElMessage.error(errorMessage(error, '加载任务引用数据失败'))
  }
}

function openCreate() {
  userKeyword.value = ''
  Object.assign(createForm, {
    equipmentId: undefined,
    schemeVersionId: undefined,
    plannedDate: new Date().toISOString().slice(0, 10),
    plannedStartTime: '',
    dueTime: '',
    assigneeUserIds: [],
    teamCode: '',
    backfill: false,
    remark: '',
  })
  createVisible.value = true
}

async function createTask() {
  if (!createForm.equipmentId || !createForm.schemeVersionId || !createForm.dueTime) {
    ElMessage.warning('请选择设备、方案版本并填写截止时间')
    return
  }
  try {
    await inspectionApi.createTask({
      ...createForm,
      plannedStartTime: createForm.plannedStartTime || null,
    })
    createVisible.value = false
    ElMessage.success('手工点检任务已创建')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function showDetail(row: TaskRow) {
  try {
    detail.value = await inspectionApi.task(row.id)
    detailVisible.value = true
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

function openAssign(row: TaskRow) {
  assignTarget.value = row
  assignForm.assigneeUserIds = parseAssigneeIds(row)
  assignForm.teamCode = row.teamCode || ''
  userKeyword.value = ''
  assignVisible.value = true
}

async function saveAssignment() {
  if (!assignTarget.value) return
  if (!assignForm.assigneeUserIds.length) {
    ElMessage.warning('请至少选择一名执行人')
    return
  }
  assigning.value = true
  try {
    await inspectionApi.assignTask(assignTarget.value.id, {
      assigneeUserIds: assignForm.assigneeUserIds,
      teamCode: assignForm.teamCode || null,
      version: assignTarget.value.version,
    })
    assignVisible.value = false
    ElMessage.success(`任务已派工给 ${assignForm.assigneeUserIds.length} 人`)
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    assigning.value = false
  }
}

function searchUsers(query: string) {
  userKeyword.value = query
}

function normalizeSearch(value: string) {
  return value.trim().toLowerCase().replace(/\s+/g, '')
}

function userSearchText(user: ReferenceUser) {
  const name = user.realName || ''
  const fullPinyin = pinyin(name, { toneType: 'none', type: 'array' }).join('')
  const initials = pinyin(name, {
    pattern: 'first',
    toneType: 'none',
    type: 'array',
  }).join('')
  return normalizeSearch([
    name,
    user.username,
    user.organizationName || '',
    fullPinyin,
    initials,
  ].join(' '))
}

function parseAssigneeIds(row: TaskRow) {
  const ids = row.assigneeUserIdsCsv
    ?.split(',')
    .map((value) => Number(value))
    .filter((value) => Number.isSafeInteger(value) && value > 0)
  if (ids?.length) return ids
  return row.assigneeUserId ? [row.assigneeUserId] : []
}

async function review(row: TaskRow, approved: boolean) {
  const value = await ElMessageBox.prompt(
    approved ? '可填写复核意见' : '请输入驳回原因',
    approved ? '复核通过' : '复核驳回',
    { inputPattern: approved ? undefined : /\S+/, inputErrorMessage: '驳回原因不能为空' },
  )
  try {
    await inspectionApi.reviewTask(row.id, { approved, comment: value.value || null, version: row.version })
    ElMessage.success(approved ? '任务已完成' : '任务已退回执行')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function close(row: TaskRow, targetStatus: 'CANCELLED' | 'VOIDED') {
  const value = await ElMessageBox.prompt('请输入原因', targetStatus === 'CANCELLED' ? '取消任务' : '作废任务', {
    inputPattern: /\S+/,
    inputErrorMessage: '原因不能为空',
  })
  try {
    await inspectionApi.closeTask(row.id, targetStatus, { reason: value.value, version: row.version })
    ElMessage.success('任务状态已更新')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div><h1>点检任务</h1><p>统一管理计划与手工任务，支持派工、复核、取消、作废和全量事件追踪。</p></div>
      <el-button v-if="auth.can('inspection:task:create')" type="primary" @click="openCreate">创建任务</el-button>
    </header>
    <section class="surface-card query-bar">
      <el-input v-model="keyword" clearable placeholder="任务、方案或设备" @keyup.enter="page = 1; load()" />
      <el-select v-model="status" clearable placeholder="任务状态"><el-option v-for="(meta, value) in statusMeta" :key="value" :label="meta.label" :value="value" /></el-select>
      <el-button type="primary" @click="page = 1; load()">查询</el-button>
    </section>
    <section class="surface-card table-card" v-loading="loading">
      <div class="table-toolbar"><span class="table-title">点检任务台账</span><span>共 {{ total }} 条</span></div>
      <el-table :data="rows" row-key="id">
        <el-table-column label="任务" min-width="210"><template #default="{ row }"><strong class="mono">{{ row.taskCode }}</strong><div class="muted">{{ row.schemeNameSnapshot }} · V{{ row.schemeVersionNumber }}</div></template></el-table-column>
        <el-table-column label="设备" min-width="190"><template #default="{ row }"><strong>{{ row.equipmentName }}</strong><div class="muted mono">{{ row.equipmentCode }}</div></template></el-table-column>
        <el-table-column prop="plannedDate" label="计划日期" width="115" />
        <el-table-column prop="dueTime" label="截止时间" min-width="175" />
        <el-table-column prop="assigneeName" label="执行人" min-width="150"><template #default="{ row }">{{ row.assigneeName || '待派工' }}</template></el-table-column>
        <el-table-column label="进度" width="110"><template #default="{ row }">{{ row.completedItemCount }}/{{ row.itemCount }}<el-badge v-if="row.abnormalItemCount" :value="row.abnormalItemCount" type="danger" /></template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="statusMeta[row.taskStatus as TaskStatus].type">{{ statusMeta[row.taskStatus as TaskStatus].label }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="showDetail(row)">详情</el-button>
            <el-button v-if="auth.can('inspection:task:assign') && ['PENDING','IN_PROGRESS','OVERDUE'].includes(row.taskStatus)" link type="primary" @click="openAssign(row)">派工</el-button>
            <template v-if="auth.can('inspection:task:review') && row.taskStatus === 'PENDING_REVIEW'">
              <el-button link type="success" @click="review(row, true)">通过</el-button>
              <el-button link type="warning" @click="review(row, false)">驳回</el-button>
            </template>
            <el-dropdown v-if="auth.can('inspection:task:cancel') && !['COMPLETED','CANCELLED','VOIDED'].includes(row.taskStatus)">
              <el-button link type="danger">关闭</el-button>
              <template #dropdown><el-dropdown-menu><el-dropdown-item @click="close(row, 'CANCELLED')">取消</el-dropdown-item><el-dropdown-item @click="close(row, 'VOIDED')">作废</el-dropdown-item></el-dropdown-menu></template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @change="load" />
    </section>

    <el-dialog v-model="createVisible" title="创建手工点检任务" width="min(700px, 96vw)">
      <el-form label-position="top" class="form-grid">
        <el-form-item label="设备"><el-select v-model="createForm.equipmentId" filterable><el-option v-for="row in equipment" :key="row.id" :label="`${row.equipmentCode} · ${row.equipmentName}`" :value="row.id" /></el-select></el-form-item>
        <el-form-item label="已发布方案"><el-select v-model="createForm.schemeVersionId" filterable><el-option v-for="row in schemes" :key="row.id" :label="`${row.schemeCode} · ${row.schemeName} V${row.currentVersionNumber}`" :value="row.currentVersionId" /></el-select></el-form-item>
        <el-form-item label="计划日期"><el-date-picker v-model="createForm.plannedDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="计划开始"><el-date-picker v-model="createForm.plannedStartTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" clearable /></el-form-item>
        <el-form-item label="截止时间"><el-date-picker v-model="createForm.dueTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item>
        <el-form-item label="执行人（可多选）">
          <el-select
            v-model="createForm.assigneeUserIds"
            multiple
            :multiple-limit="20"
            filterable
            collapse-tags
            collapse-tags-tooltip
            clearable
            :filter-method="searchUsers"
            placeholder="姓名、账号、全拼或拼音首字母"
          >
            <el-option
              v-for="user in filteredUsers"
              :key="user.id"
              :label="`${user.realName}（${user.username}）`"
              :value="user.id"
            >
              <div class="user-option"><strong>{{ user.realName }}</strong><span>{{ user.username }} · {{ user.organizationName || '未分配组织' }}</span></div>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="班组（选填）">
          <el-select
            v-model="createForm.teamCode"
            filterable
            clearable
            placeholder="选择班组"
          >
            <el-option
              v-for="team in teams"
              :key="team.id"
              :label="`${team.organizationName}（${team.organizationCode}）`"
              :value="team.organizationCode"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="补录"><el-switch v-model="createForm.backfill" /></el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="createForm.remark" type="textarea" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="createVisible = false">取消</el-button><el-button type="primary" @click="createTask">创建</el-button></template>
    </el-dialog>

    <el-dialog v-model="assignVisible" :title="`任务派工 · ${assignTarget?.taskCode || ''}`" width="min(620px, 96vw)">
      <el-alert
        v-if="assignTarget"
        :title="`${assignTarget.equipmentName} · ${assignTarget.schemeNameSnapshot}`"
        type="info"
        :closable="false"
      />
      <el-form label-position="top" class="assign-form">
        <el-form-item label="执行人员">
          <el-select
            v-model="assignForm.assigneeUserIds"
            multiple
            :multiple-limit="20"
            filterable
            collapse-tags
            collapse-tags-tooltip
            :max-collapse-tags="4"
            :filter-method="searchUsers"
            placeholder="输入姓名、账号、全拼或拼音首字母"
          >
            <el-option
              v-for="user in filteredUsers"
              :key="user.id"
              :label="`${user.realName}（${user.username}）`"
              :value="user.id"
            >
              <div class="user-option"><strong>{{ user.realName }}</strong><span>{{ user.username }} · {{ user.organizationName || '未分配组织' }}</span></div>
            </el-option>
          </el-select>
          <div class="field-hint">最多选择 20 人；第一位作为主执行人，所有人员均可在“我的点检”中处理任务。</div>
        </el-form-item>
        <el-form-item label="班组（选填）">
          <el-select
            v-model="assignForm.teamCode"
            filterable
            clearable
            placeholder="选择班组"
          >
            <el-option
              v-for="team in teams"
              :key="team.id"
              :label="`${team.organizationName}（${team.organizationCode}）`"
              :value="team.organizationCode"
            />
          </el-select>
          <div class="field-hint">班组来自“基础数据 → 组织管理”中的班组类型组织。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" :loading="assigning" @click="saveAssignment">确认派工（{{ assignForm.assigneeUserIds.length }} 人）</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" :title="detail?.task.taskCode" size="min(860px, 97vw)">
      <template v-if="detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="设备">{{ detail.task.equipmentCode }} · {{ detail.task.equipmentName }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusMeta[detail.task.taskStatus].label }}</el-descriptions-item>
          <el-descriptions-item label="执行人">{{ detail.task.assigneeName || '待派工' }}</el-descriptions-item>
          <el-descriptions-item label="截止">{{ detail.task.dueTime }}</el-descriptions-item>
        </el-descriptions>
        <h3>项目结果</h3>
        <el-table :data="detail.items" size="small"><el-table-column prop="itemName" label="项目" min-width="160" /><el-table-column prop="inspectionStandard" label="标准" min-width="220" /><el-table-column label="结果" min-width="170"><template #default="{ row }">{{ row.result?.numericValue ?? row.result?.textValue ?? row.result?.selectedValue ?? row.result?.resultCode ?? '未填写' }}</template></el-table-column><el-table-column label="异常" width="80"><template #default="{ row }"><el-tag v-if="row.result?.abnormalFlag" type="danger">异常</el-tag><span v-else>—</span></template></el-table-column></el-table>
        <h3>事件轨迹</h3>
        <el-timeline><el-timeline-item v-for="event in detail.events" :key="event.id" :timestamp="event.eventTime"><strong>{{ event.eventType }}</strong> {{ event.fromStatus }} → {{ event.toStatus }}<div class="muted">{{ event.eventRemark }} · {{ event.operatorName }}</div></el-timeline-item></el-timeline>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.muted { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 4px; }
.form-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 0 16px; }
.full { grid-column: 1 / -1; }
.assign-form { margin-top: 18px; }
.user-option { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.user-option span, .field-hint { color: var(--el-text-color-secondary); font-size: 12px; }
.field-hint { margin-top: 7px; line-height: 1.5; }
@media (max-width: 640px) { .form-grid { grid-template-columns: 1fr; } .full { grid-column: auto; } }
</style>
