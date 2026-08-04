<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pinyin } from 'pinyin-pro'
import { inspectionApi, type InspectionExportJob, type SchemeRow, type TaskDetail, type TaskQuery, type TaskRow, type TaskStatus } from '@/api/inspection'
import { equipmentApi, type EquipmentRow } from '@/api/equipment'
import { masterDataApi, type OrganizationRow, type ReferenceUser } from '@/api/masterData'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'
import { useRoute } from 'vue-router'

const auth = useAuthStore()
const route = useRoute()
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
const exporting = ref(false)
const includeImages = ref(false)
const exportJobVisible = ref(false)
const exportJob = ref<InspectionExportJob | null>(null)
const detail = ref<TaskDetail | null>(null)
const assignTarget = ref<TaskRow | null>(null)
const equipment = ref<EquipmentRow[]>([])
const schemes = ref<SchemeRow[]>([])
const users = ref<ReferenceUser[]>([])
const teams = ref<OrganizationRow[]>([])
const organizations = ref<OrganizationRow[]>([])
const userKeyword = ref('')

const filters = reactive({
  timeField: 'PLANNED_DATE' as NonNullable<TaskQuery['timeField']>,
  dateRange: [] as string[],
  organizationId: undefined as number | undefined,
  teamCode: '',
  assigneeUserId: undefined as number | undefined,
  equipmentId: undefined as number | undefined,
  schemeId: undefined as number | undefined,
  abnormalOnly: false,
  abnormalSeverity: undefined as TaskQuery['abnormalSeverity'],
})

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
  PENDING_REVIEW: { label: '已完成', type: 'success' },
  COMPLETED: { label: '已完成', type: 'success' },
  OVERDUE: { label: '已逾期', type: 'danger' },
  CANCELLED: { label: '已取消', type: 'info' },
  VOIDED: { label: '已作废', type: 'info' },
}

onMounted(async () => {
  if (typeof route.query.startDate === 'string' && typeof route.query.endDate === 'string') {
    filters.dateRange = [route.query.startDate, route.query.endDate]
  }
  if (typeof route.query.taskStatus === 'string') {
    status.value = route.query.taskStatus as TaskStatus
  }
  filters.abnormalOnly = route.query.abnormalOnly === 'true'
  const equipmentId = Number(route.query.equipmentId)
  if (Number.isInteger(equipmentId) && equipmentId > 0) filters.equipmentId = equipmentId
  await Promise.all([load(), loadReferences()])
})

async function load() {
  loading.value = true
  try {
    const result = await inspectionApi.tasks(taskQuery(true))
    rows.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function taskQuery(includePage: boolean): TaskQuery {
  return {
    keyword: keyword.value || undefined,
    taskStatus: status.value,
    timeField: filters.timeField,
    startDate: filters.dateRange[0] || undefined,
    endDate: filters.dateRange[1] || undefined,
    organizationId: filters.organizationId,
    teamCode: filters.teamCode || undefined,
    assigneeUserId: filters.assigneeUserId,
    equipmentId: filters.equipmentId,
    schemeId: filters.schemeId,
    abnormalOnly: filters.abnormalOnly || undefined,
    abnormalSeverity: filters.abnormalSeverity,
    page: includePage ? page.value : undefined,
    pageSize: includePage ? 20 : undefined,
  }
}

async function exportResults() {
  exporting.value = true
  try {
    if (!includeImages.value) {
      await inspectionApi.exportResults(taskQuery(false))
      ElMessage.success('点检结果已导出')
      return
    }
    const created = await inspectionApi.createImageExportJob(taskQuery(false))
    ElMessage.success('含图片导出任务已提交，正在后台生成')
    await waitForExport(created.id)
  } catch (error) {
    ElMessage.error(errorMessage(error, '点检结果导出失败'))
  } finally {
    exporting.value = false
  }
}

async function waitForExport(jobId: number) {
  for (let attempt = 0; attempt < 150; attempt += 1) {
    const current = await inspectionApi.imageExportJob(jobId)
    exportJob.value = current
    if (current.job.jobStatus === 'COMPLETED') {
      exportJobVisible.value = true
      if (current.files.length === 1) {
        await inspectionApi.downloadImageExportFile(jobId, current.files[0])
        ElMessage.success(`已导出 ${current.job.imageCount} 张水印图片`)
      } else {
        ElMessage.success(`导出完成，已拆分为 ${current.files.length} 个工作簿`)
      }
      return
    }
    if (current.job.jobStatus === 'FAILED') {
      throw new Error(current.job.errorMessage || '后台生成失败')
    }
    await new Promise((resolve) => window.setTimeout(resolve, 2000))
  }
  throw new Error('后台导出仍在处理中，请稍后重试')
}

async function downloadExportFile(file: InspectionExportJob['files'][number]) {
  if (!exportJob.value) return
  try {
    await inspectionApi.downloadImageExportFile(exportJob.value.job.id, file)
  } catch (error) {
    ElMessage.error(errorMessage(error, '导出文件下载失败'))
  }
}

function formatBytes(value: number) {
  if (value < 1024 * 1024) return `${Math.max(1, Math.round(value / 1024))} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

function resetFilters() {
  keyword.value = ''
  status.value = undefined
  Object.assign(filters, {
    timeField: 'PLANNED_DATE',
    dateRange: [],
    organizationId: undefined,
    teamCode: '',
    assigneeUserId: undefined,
    equipmentId: undefined,
    schemeId: undefined,
    abnormalOnly: false,
    abnormalSeverity: undefined,
  })
  page.value = 1
  load()
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
    organizations.value = organizationRows.filter((row) => row.status === 1)
    teams.value = organizations.value.filter(
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
      <div><h1>点检任务</h1><p>统一管理计划与手工任务，结果提交后直接完成，支持派工、取消、作废和全量事件追踪。</p></div>
      <el-button v-if="auth.can('inspection:task:create')" type="primary" @click="openCreate">创建任务</el-button>
    </header>
    <section class="surface-card query-bar">
      <el-input v-model="keyword" clearable placeholder="任务、方案或设备" @keyup.enter="page = 1; load()" />
      <el-select v-model="status" clearable placeholder="任务状态"><el-option v-for="(meta, value) in statusMeta" :key="value" :label="meta.label" :value="value" /></el-select>
      <el-select v-model="filters.timeField" placeholder="时间口径">
        <el-option label="计划日期" value="PLANNED_DATE" />
        <el-option label="开始时间" value="STARTED_TIME" />
        <el-option label="提交时间" value="SUBMITTED_TIME" />
        <el-option label="完成时间" value="COMPLETED_TIME" />
      </el-select>
      <el-date-picker
        v-model="filters.dateRange"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        range-separator="至"
      />
      <el-select v-model="filters.organizationId" clearable filterable placeholder="组织">
        <el-option v-for="row in organizations" :key="row.id" :label="row.organizationName" :value="row.id" />
      </el-select>
      <el-select v-model="filters.teamCode" clearable filterable placeholder="班组">
        <el-option v-for="row in teams" :key="row.id" :label="row.organizationName" :value="row.organizationCode" />
      </el-select>
      <el-select v-model="filters.assigneeUserId" clearable filterable placeholder="执行人">
        <el-option v-for="user in users" :key="user.id" :label="`${user.realName}（${user.username}）`" :value="user.id" />
      </el-select>
      <el-select v-model="filters.equipmentId" clearable filterable placeholder="设备">
        <el-option v-for="row in equipment" :key="row.id" :label="`${row.equipmentCode} · ${row.equipmentName}`" :value="row.id" />
      </el-select>
      <el-select v-model="filters.schemeId" clearable filterable placeholder="点检方案">
        <el-option v-for="row in schemes" :key="row.id" :label="row.schemeName" :value="row.id" />
      </el-select>
      <el-checkbox v-model="filters.abnormalOnly">仅异常</el-checkbox>
      <el-select v-model="filters.abnormalSeverity" clearable placeholder="异常等级">
        <el-option label="低" value="LOW" />
        <el-option label="中" value="MEDIUM" />
        <el-option label="高" value="HIGH" />
        <el-option label="紧急" value="CRITICAL" />
      </el-select>
      <el-button type="primary" @click="page = 1; load()">查询</el-button>
      <el-button @click="resetFilters">重置</el-button>
      <template v-if="auth.can('inspection:task:export')">
        <el-checkbox v-model="includeImages">包含水印图片</el-checkbox>
        <el-button :loading="exporting" @click="exportResults">导出结果</el-button>
      </template>
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
            <el-dropdown v-if="auth.can('inspection:task:cancel') && !['COMPLETED','CANCELLED','VOIDED'].includes(row.taskStatus)">
              <el-button link type="danger">关闭</el-button>
              <template #dropdown><el-dropdown-menu><el-dropdown-item @click="close(row, 'CANCELLED')">取消</el-dropdown-item><el-dropdown-item @click="close(row, 'VOIDED')">作废</el-dropdown-item></el-dropdown-menu></template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @change="load" />
    </section>

    <el-dialog v-model="exportJobVisible" title="含图片导出文件" width="min(680px, 96vw)">
      <el-alert
        v-if="exportJob"
        type="success"
        :closable="false"
        :title="`已导出 ${exportJob.job.taskCount} 个任务、${exportJob.job.resultCount} 条结果、${exportJob.job.imageCount} 张水印图片`"
        description="超过 1000 张图片或估算 200MB 时系统会自动拆分；非水印图片和读取失败会在图片明细中明确标记。"
      />
      <el-table v-if="exportJob" :data="exportJob.files" class="export-file-table">
        <el-table-column prop="partNumber" label="分卷" width="80" />
        <el-table-column prop="fileName" label="文件" min-width="280" />
        <el-table-column label="图片" width="90"><template #default="{ row }">{{ row.imageCount }} 张</template></el-table-column>
        <el-table-column label="大小" width="100"><template #default="{ row }">{{ formatBytes(row.fileSize) }}</template></el-table-column>
        <el-table-column label="操作" width="90"><template #default="{ row }"><el-button link type="primary" @click="downloadExportFile(row)">下载</el-button></template></el-table-column>
      </el-table>
    </el-dialog>

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
