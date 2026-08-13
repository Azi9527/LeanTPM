<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { inspectionApi, type InspectionCalendarRow, type ItemRow, type SchemeDetail, type SchemeRow } from '@/api/inspection'
import { equipmentApi, type EquipmentRow } from '@/api/equipment'
import { masterDataApi, type EquipmentCategoryRow, type ReferenceUser } from '@/api/masterData'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'
import { schemeItemConfigMap, schemeItemPayload, type SchemeItemConfig } from '@/utils/inspection-scheme'
import InspectionImportDialog from '@/components/inspection/InspectionImportDialog.vue'
import { applySmartTableQuery, type SmartTableServerQuery } from '@/components/table/smart-table-context'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const rows = ref<SchemeRow[]>([])
const total = ref(0)
const page = ref(1)
const keyword = ref('')
const dialogVisible = ref(false)
const activeEditorSection = ref('basic')
const detailVisible = ref(false)
const importVisible = ref(false)
const editing = ref<SchemeRow | null>(null)
const copying = ref(false)
const detail = ref<SchemeDetail | null>(null)
const items = ref<ItemRow[]>([])
const equipment = ref<EquipmentRow[]>([])
const categories = ref<EquipmentCategoryRow[]>([])
const users = ref<ReferenceUser[]>([])
const calendars = ref<InspectionCalendarRow[]>([])
const smartTableQuery = reactive({
  tableFilters: undefined as string | undefined,
  sortBy: undefined as string | undefined,
  sortDirection: undefined as 'asc' | 'desc' | undefined,
})

const form = reactive({
  schemeCode: '',
  schemeName: '',
  inspectionType: 'DAILY',
  cycleType: 'DAILY',
  cycleInterval: 1,
  weekDays: [] as number[],
  monthDays: [] as number[],
  scheduledTime: '08:00:00',
  generationLeadMinutes: 60,
  workCalendarId: undefined as number | undefined,
  shiftCode: '',
  defaultAssigneeUserIds: [] as number[],
  defaultTeamCode: '',
  backfillAllowed: false,
  submissionPhotoRequired: false,
  submissionPhotoMaxCount: 9,
  effectiveDate: new Date().toISOString().slice(0, 10),
  expiryDate: '',
  itemIds: [] as number[],
  itemConfigs: {} as Record<number, SchemeItemConfig>,
  categoryIds: [] as number[],
  equipmentIds: [] as number[],
  enabled: true,
  description: '',
  changeSummary: '',
})

const typeLabels: Record<string, string> = {
  DAILY: '日常点检',
  PRE_SHIFT: '班前点检',
  POST_SHIFT: '班后点检',
  PROFESSIONAL: '专业点检',
  PRECISION: '精密点检',
  SAFETY: '安全点检',
  SPECIAL: '专项点检',
}
const cycleLabels: Record<string, string> = {
  DAILY: '每日',
  HOURLY: '每小时',
  WEEKLY: '每周',
  MONTHLY: '每月',
  INTERVAL_DAYS: '间隔天数',
}

function equipmentIdentity(equipmentId: number) {
  const row = equipment.value.find((item) => item.id === equipmentId)
  return row ? `${row.equipmentName}（${row.equipmentCode}）` : `设备 #${equipmentId}`
}

onMounted(async () => {
  await Promise.all([load(), loadReferences()])
})

async function load() {
  loading.value = true
  try {
    const result = await inspectionApi.schemes({
      keyword: keyword.value || undefined,
      ...smartTableQuery,
      page: page.value,
      pageSize: 100,
    })
    rows.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function applyTableQuery(query: SmartTableServerQuery) {
  applySmartTableQuery(smartTableQuery, query)
  page.value = 1
  void load()
}

async function loadReferences() {
  try {
    const [itemPage, equipmentPage, categoryRows, userRows, calendarRows] = await Promise.all([
      inspectionApi.items({ status: 1, page: 1, pageSize: 200 }),
      equipmentApi.page({ status: 1, page: 1, pageSize: 200 }),
      masterDataApi.categories(),
      masterDataApi.referenceUsers(),
      inspectionApi.calendars({ status: 1 }),
    ])
    items.value = itemPage.records
    equipment.value = equipmentPage.records
    categories.value = categoryRows.filter((row) => row.status === 1)
    users.value = userRows
    calendars.value = calendarRows
  } catch (error) {
    ElMessage.error(errorMessage(error, '加载方案引用数据失败'))
  }
}

function resetForm() {
  Object.assign(form, {
    schemeCode: '',
    schemeName: '',
    inspectionType: 'DAILY',
    cycleType: 'DAILY',
    cycleInterval: 1,
    weekDays: [],
    monthDays: [],
    scheduledTime: '08:00:00',
    generationLeadMinutes: 60,
    workCalendarId: calendars.value.find((row) => row.defaultFlag)?.id,
    shiftCode: '',
    defaultAssigneeUserIds: [],
    defaultTeamCode: '',
    backfillAllowed: false,
    submissionPhotoRequired: false,
    submissionPhotoMaxCount: 9,
    effectiveDate: new Date().toISOString().slice(0, 10),
    expiryDate: '',
    itemIds: [],
    itemConfigs: {},
    categoryIds: [],
    equipmentIds: [],
    enabled: true,
    description: '',
    changeSummary: '',
  })
}

async function open(row?: SchemeRow, copy = false) {
  activeEditorSection.value = 'basic'
  editing.value = copy ? null : row || null
  copying.value = Boolean(row && copy)
  resetForm()
  if (row) {
    try {
      const value = await inspectionApi.scheme(row.id)
      Object.assign(form, {
        schemeCode: copy ? '' : value.scheme.schemeCode,
        schemeName: copy ? `${value.scheme.schemeName}（副本）` : value.scheme.schemeName,
        inspectionType: value.scheme.inspectionType,
        cycleType: value.version.cycleType,
        cycleInterval: value.version.cycleInterval,
        weekDays: csvNumbers(value.version.weekDays),
        monthDays: csvNumbers(value.version.monthDays),
        scheduledTime: value.version.scheduledTime || '08:00:00',
        generationLeadMinutes: value.version.generationLeadMinutes ?? 60,
        workCalendarId: value.version.workCalendarId
          ?? calendars.value.find((calendar) => calendar.defaultFlag)?.id,
        shiftCode: value.version.shiftCode || '',
        defaultAssigneeUserIds: value.version.defaultAssigneeUserIdsCsv
          ? csvNumbers(value.version.defaultAssigneeUserIdsCsv)
          : value.version.defaultAssigneeUserId ? [value.version.defaultAssigneeUserId] : [],
        defaultTeamCode: value.version.defaultTeamCode || '',
        backfillAllowed: value.version.backfillAllowedFlag,
        submissionPhotoRequired: value.version.submissionPhotoRequiredFlag,
        submissionPhotoMaxCount: value.version.submissionPhotoMaxCount ?? 9,
        effectiveDate: value.version.effectiveDate,
        expiryDate: value.version.expiryDate || '',
        itemIds: value.items.map((item) => item.inspectionItemId),
        itemConfigs: schemeItemConfigMap(value.items),
        categoryIds: value.applicability.categoryIds,
        equipmentIds: value.applicability.equipmentIds,
        enabled: value.scheme.status === 1,
        description: value.scheme.description || '',
        changeSummary: copy ? `复制自方案 ${value.scheme.schemeCode}` : '',
      })
    } catch (error) {
      ElMessage.error(errorMessage(error))
      return
    }
  }
  dialogVisible.value = true
}

async function save() {
  if (!form.schemeName || !form.workCalendarId || !form.itemIds.length || (!form.categoryIds.length && !form.equipmentIds.length)) {
    ElMessage.warning('请填写方案名称、工作日历，并选择项目及适用设备范围')
    return
  }
  saving.value = true
  try {
    const payload = {
      ...form,
      reviewRequired: false,
      schemeCode: form.schemeCode || null,
      weekDays: form.weekDays.join(',') || null,
      monthDays: form.monthDays.join(',') || null,
      expiryDate: form.expiryDate || null,
      defaultAssigneeUserId: form.defaultAssigneeUserIds[0] || null,
      defaultAssigneeUserIds: form.defaultAssigneeUserIds,
      items: schemeItemPayload(form.itemIds, form.itemConfigs),
      version: editing.value?.version,
    }
    if (editing.value) await inspectionApi.createAndPublishSchemeVersion(editing.value.id, payload)
    else await inspectionApi.createAndPublishScheme(payload)
    dialogVisible.value = false
    ElMessage.success(editing.value ? '方案新版本已发布' : copying.value ? '方案副本已创建并发布' : '点检方案已创建并发布')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function showDetail(row: SchemeRow, versionId?: number) {
  try {
    detail.value = await inspectionApi.scheme(row.id, versionId)
    detailVisible.value = true
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function restoreVersion(row: SchemeRow, versionId: number, versionNumber: number) {
  await ElMessageBox.confirm(
    `以历史版本 V${versionNumber} 的配置创建并发布一个新版本？现有历史记录不会被修改。`,
    '恢复历史版本',
    { type: 'warning', confirmButtonText: '创建并发布' },
  )
  try {
    const value = await inspectionApi.scheme(row.id, versionId)
    await inspectionApi.createAndPublishSchemeVersion(row.id, {
      schemeCode: value.scheme.schemeCode,
      schemeName: value.scheme.schemeName,
      inspectionType: value.scheme.inspectionType,
      cycleType: value.version.cycleType,
      cycleInterval: value.version.cycleInterval,
      weekDays: value.version.weekDays || null,
      monthDays: value.version.monthDays || null,
      scheduledTime: value.version.scheduledTime || null,
      generationLeadMinutes: value.version.generationLeadMinutes,
      workCalendarId: value.version.workCalendarId,
      shiftCode: value.version.shiftCode || null,
      defaultAssigneeUserId: value.version.defaultAssigneeUserId || null,
      defaultAssigneeUserIds: value.version.defaultAssigneeUserIdsCsv
        ? csvNumbers(value.version.defaultAssigneeUserIdsCsv)
        : value.version.defaultAssigneeUserId ? [value.version.defaultAssigneeUserId] : [],
      defaultTeamCode: value.version.defaultTeamCode || null,
      reviewRequired: false,
      backfillAllowed: value.version.backfillAllowedFlag,
      submissionPhotoRequired: value.version.submissionPhotoRequiredFlag,
      submissionPhotoMaxCount: value.version.submissionPhotoMaxCount,
      effectiveDate: value.version.effectiveDate,
      expiryDate: value.version.expiryDate || null,
      items: value.items.map((item) => ({
        inspectionItemId: item.inspectionItemId,
        sortOrder: item.sortOrder,
      })),
      categoryIds: value.applicability.categoryIds,
      equipmentIds: value.applicability.equipmentIds,
      enabled: value.scheme.status === 1,
      description: value.scheme.description || null,
      changeSummary: `由历史版本 V${versionNumber} 恢复`,
      version: value.scheme.version,
    })
    ElMessage.success(`历史版本 V${versionNumber} 已恢复为最新发布版本`)
    detailVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function changeSchemeStatus(row: SchemeRow) {
  const enabled = row.status !== 1
  await ElMessageBox.confirm(
    `${enabled ? '启用' : '停用'}方案“${row.schemeName}”？${enabled ? '' : '停用后将不再生成新任务。'}`,
    `${enabled ? '启用' : '停用'}点检方案`,
    { type: enabled ? 'success' : 'warning' },
  )
  try {
    await inspectionApi.updateSchemeStatus(row.id, enabled, row.version)
    ElMessage.success(enabled ? '点检方案已启用' : '点检方案已停用')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function removeScheme(row: SchemeRow) {
  if (row.status === 1 || row.activePlanCount > 0) {
    ElMessage.warning(row.status === 1 ? '请先停用方案后再删除' : '方案存在启用计划，不能删除')
    return
  }
  await ElMessageBox.confirm(
    `确认删除方案“${row.schemeName}”？方案版本、计划、任务及审计历史将保留。`,
    '删除点检方案',
    { type: 'warning', confirmButtonText: '确认删除' },
  )
  try {
    await inspectionApi.deleteScheme(row.id, row.version)
    ElMessage.success('点检方案已删除')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

function csvNumbers(value?: string) {
  return value ? value.split(',').map(Number).filter(Number.isFinite) : []
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div><h1>点检方案</h1><p>新增或调整后直接发布新版本；历史版本可查看并按指定版本恢复。</p></div>
      <div class="page-actions">
        <el-button v-if="auth.can('inspection:import')" @click="importVisible = true">批量导入</el-button>
        <el-button v-if="auth.can('inspection:scheme:manage')" type="primary" @click="open()">新增方案</el-button>
      </div>
    </header>
    <section class="surface-card query-bar">
      <el-input v-model="keyword" clearable placeholder="方案编码或名称" @keyup.enter="page = 1; load()" />
      <el-button type="primary" @click="page = 1; load()">查询</el-button>
    </section>
    <section class="surface-card table-card" v-loading="loading">
      <div class="table-toolbar"><span class="table-title">方案版本库</span><span>共 {{ total }} 个方案</span></div>
      <el-table :data="rows" row-key="id" server-query @smart-query-change="applyTableQuery">
        <el-table-column prop="schemeCode" label="方案编码" min-width="180"><template #default="{ row }"><span class="mono">{{ row.schemeCode }}</span></template></el-table-column>
        <el-table-column prop="schemeName" label="方案名称" min-width="190" />
        <el-table-column prop="inspectionType" label="类型" smart-filter="select" width="120"><template #default="{ row }">{{ typeLabels[row.inspectionType] || row.inspectionType }}</template></el-table-column>
        <el-table-column prop="currentVersionStatus" label="当前版本" smart-filter="select" width="120"><template #default="{ row }"><el-tag :type="row.currentVersionStatus === 'PUBLISHED' ? 'success' : 'warning'">V{{ row.currentVersionNumber || '—' }} · {{ row.currentVersionStatus === 'PUBLISHED' ? '已发布' : '未发布' }}</el-tag></template></el-table-column>
        <el-table-column prop="cycleType" label="周期" smart-filter="select" width="120"><template #default="{ row }">{{ cycleLabels[row.cycleType] || row.cycleType || '—' }}</template></el-table-column>
        <el-table-column prop="itemCount" label="项目" smart-filter="number" width="80" />
        <el-table-column prop="activePlanCount" label="有效计划" smart-filter="number" width="100" />
        <el-table-column prop="status" label="状态" smart-filter="select" width="100">
          <template #default="{ row }"><el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" smart-filter="none" width="410" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="showDetail(row)">查看明细</el-button>
            <el-button v-if="auth.can('inspection:scheme:manage')" link type="primary" @click="open(row)">新版本</el-button>
            <el-button v-if="auth.can('inspection:scheme:manage') && auth.can('inspection:scheme:publish')" link type="primary" @click="open(row, true)">复制</el-button>
            <el-button v-if="auth.can('inspection:scheme:manage')" link :type="row.status === 1 ? 'warning' : 'success'" @click="changeSchemeStatus(row)">{{ row.status === 1 ? '停用' : '启用' }}</el-button>
            <el-button v-if="auth.can('inspection:scheme:manage')" link type="danger" :disabled="row.status === 1 || row.activePlanCount > 0" @click="removeScheme(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @change="load" />
    </section>

    <el-dialog v-model="dialogVisible" :title="editing ? '创建方案新版本' : copying ? '复制点检方案' : '新增点检方案'" width="min(1080px, 97vw)" class="inspection-editor-dialog" align-center>
      <el-form label-position="top" class="inspection-editor-form">
        <el-tabs v-model="activeEditorSection" class="inspection-editor-tabs">
          <el-tab-pane label="基础与周期" name="basic">
            <div class="form-grid inspection-editor-pane">
        <el-form-item label="方案编码"><el-input v-model="form.schemeCode" :disabled="Boolean(editing)" placeholder="留空自动编号" /></el-form-item>
        <el-form-item label="方案名称" required><el-input v-model="form.schemeName" /></el-form-item>
        <el-form-item label="点检类型" required><el-select v-model="form.inspectionType"><el-option v-for="(label, value) in typeLabels" :key="value" :label="label" :value="value" /></el-select></el-form-item>
        <el-form-item label="周期类型" required><el-select v-model="form.cycleType"><el-option v-for="(label, value) in cycleLabels" :key="value" :label="label" :value="value" /></el-select></el-form-item>
        <el-form-item :label="form.cycleType === 'HOURLY' ? '间隔小时' : '周期间隔'"><el-input-number v-model="form.cycleInterval" :min="1" /></el-form-item>
        <el-form-item :label="form.cycleType === 'HOURLY' ? '首次计划时间' : '计划时间'"><el-time-picker v-model="form.scheduledTime" value-format="HH:mm:ss" /></el-form-item>
        <el-form-item label="提前生成（分钟）"><el-input-number v-model="form.generationLeadMinutes" :min="0" :max="43200" :step="30" /><div class="field-hint">默认提前 60 分钟，最大 30 天</div></el-form-item>
        <el-form-item label="点检工作日历" required><el-select v-model="form.workCalendarId" filterable><el-option v-for="calendar in calendars" :key="calendar.id" :label="calendar.defaultFlag ? `${calendar.calendarName}（默认）` : calendar.calendarName" :value="calendar.id" /></el-select></el-form-item>
        <el-form-item v-if="form.cycleType === 'WEEKLY'" label="执行星期" class="full"><el-checkbox-group v-model="form.weekDays"><el-checkbox v-for="day in 7" :key="day" :value="day">周{{ '一二三四五六日'[day - 1] }}</el-checkbox></el-checkbox-group></el-form-item>
        <el-form-item v-if="form.cycleType === 'MONTHLY'" label="每月日期" class="full"><el-select v-model="form.monthDays" multiple collapse-tags><el-option v-for="day in 31" :key="day" :label="`${day}日`" :value="day" /></el-select></el-form-item>
        <el-form-item label="生效日期" required><el-date-picker v-model="form.effectiveDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="失效日期"><el-date-picker v-model="form.expiryDate" type="date" value-format="YYYY-MM-DD" clearable /></el-form-item>
            </div>
          </el-tab-pane>
          <el-tab-pane label="人员与适用" name="scope">
            <div class="form-grid inspection-editor-pane">
        <el-form-item label="默认执行人"><el-select v-model="form.defaultAssigneeUserIds" multiple clearable filterable collapse-tags collapse-tags-tooltip :max-collapse-tags="3"><el-option v-for="user in users" :key="user.id" :label="`${user.realName} (${user.username})`" :value="user.id" /></el-select><div class="field-hint">最多选择 20 人；第一位作为主执行人，任一执行人提交即完成任务。</div></el-form-item>
        <el-form-item label="默认班组"><el-input v-model="form.defaultTeamCode" /></el-form-item>
        <el-form-item label="点检项目" class="full" required><el-select v-model="form.itemIds" multiple filterable collapse-tags collapse-tags-tooltip><el-option v-for="item in items" :key="item.id" :label="`${item.itemCode} · ${item.itemName}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="适用设备分类" class="full"><el-select v-model="form.categoryIds" multiple filterable collapse-tags><el-option v-for="category in categories" :key="category.id" :label="`${category.categoryCode} · ${category.categoryName}`" :value="category.id" /></el-select><div class="field-hint">适用设备分类和指定设备至少填写一项。</div></el-form-item>
        <el-form-item label="指定设备" class="full"><el-select v-model="form.equipmentIds" multiple filterable collapse-tags collapse-tags-tooltip><el-option v-for="row in equipment" :key="row.id" :label="`${row.equipmentName}（${row.equipmentCode}）`" :value="row.id" /></el-select><div class="field-hint">适用设备分类和指定设备至少填写一项。</div></el-form-item>
            </div>
          </el-tab-pane>
          <el-tab-pane label="提交与版本" name="release">
            <div class="form-grid inspection-editor-pane">
        <el-form-item label="执行控制" class="full"><el-checkbox v-model="form.backfillAllowed">允许补录</el-checkbox><el-checkbox v-model="form.enabled">启用方案</el-checkbox><span class="field-hint">点检结果提交后任务直接完成</span></el-form-item>
        <el-form-item label="提交图片" class="full scheme-photo-policy">
          <el-switch v-model="form.submissionPhotoRequired" active-text="提交时必须上传水印图片" inactive-text="图片选传" />
          <span>整单最多</span><el-input-number v-model="form.submissionPhotoMaxCount" :min="1" :max="20" /><span>张</span>
          <span class="field-hint">必传开启后，任务至少需要 1 张通过拍照/相册入口生成的水印图片；单个项目的拍照规则仍同时生效。</span>
        </el-form-item>
        <el-form-item label="版本说明" class="full"><el-input v-model="form.changeSummary" type="textarea" placeholder="说明本版本调整内容" /></el-form-item>
            </div>
          </el-tab-pane>
        </el-tabs>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存并发布</el-button></template>
    </el-dialog>

    <el-drawer v-model="detailVisible" :title="`${detail?.scheme.schemeName || ''} · 版本详情`" size="min(820px, 96vw)">
      <template v-if="detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="方案编码">{{ detail.scheme.schemeCode }}</el-descriptions-item>
          <el-descriptions-item label="版本">V{{ detail.version.versionNumber }} · {{ detail.version.versionStatus }}</el-descriptions-item>
          <el-descriptions-item label="周期">{{ cycleLabels[detail.version.cycleType] }} / {{ detail.version.cycleInterval }}</el-descriptions-item>
          <el-descriptions-item label="提前生成">{{ detail.version.generationLeadMinutes }} 分钟</el-descriptions-item>
          <el-descriptions-item label="工作日历">{{ calendars.find((row) => row.id === detail?.version.workCalendarId)?.calendarName || '未指定' }}</el-descriptions-item>
          <el-descriptions-item label="默认执行人">{{ detail.version.defaultAssigneeNames || detail.version.defaultAssigneeName || '未指定' }}</el-descriptions-item>
          <el-descriptions-item label="生效">{{ detail.version.effectiveDate }} ~ {{ detail.version.expiryDate || '长期' }}</el-descriptions-item>
          <el-descriptions-item label="控制">提交即完成 / {{ detail.version.backfillAllowedFlag ? '可补录' : '不可补录' }}</el-descriptions-item>
          <el-descriptions-item label="提交图片">{{ detail.version.submissionPhotoRequiredFlag ? '必须上传水印图片' : '图片选传' }} / 最多 {{ detail.version.submissionPhotoMaxCount }} 张</el-descriptions-item>
          <el-descriptions-item label="适用设备分类" :span="2">{{ detail.applicability.categoryIds.map((id) => categories.find((row) => row.id === id)?.categoryName || `分类 #${id}`).join('、') || '未指定' }}</el-descriptions-item>
          <el-descriptions-item label="指定设备" :span="2">{{ detail.applicability.equipmentIds.map((id) => equipmentIdentity(id)).join('、') || '未指定' }}</el-descriptions-item>
        </el-descriptions>
        <h3>点检项目</h3>
        <el-table :data="detail.items" size="small" max-height="420">
          <el-table-column prop="sortOrder" label="#" width="60" />
          <el-table-column prop="itemCode" label="编码" min-width="140" />
          <el-table-column prop="itemName" label="名称" min-width="160" />
          <el-table-column prop="itemCategory" label="项目分类" min-width="110" />
          <el-table-column prop="resultType" label="结果类型" width="120" />
          <el-table-column prop="unit" label="单位" width="80" />
          <el-table-column label="必填" width="70"><template #default="{ row }">{{ row.requiredFlag ? '是' : '否' }}</template></el-table-column>
          <el-table-column label="拍照" width="70"><template #default="{ row }">{{ row.photoRequiredFlag ? '是' : '否' }}</template></el-table-column>
          <el-table-column label="允许跳过" width="90"><template #default="{ row }">{{ row.skipAllowedFlag ? '是' : '否' }}</template></el-table-column>
          <el-table-column label="异常停机" width="90"><template #default="{ row }">{{ row.abnormalStopFlag ? '是' : '否' }}</template></el-table-column>
        </el-table>
        <h3>版本历史</h3>
        <el-timeline>
          <el-timeline-item v-for="version in detail.versionHistory" :key="version.id" :timestamp="version.publishedTime || version.effectiveDate">
            <el-button link type="primary" @click="showDetail(detail!.scheme, version.id)">V{{ version.versionNumber }} · {{ version.versionStatus === 'PUBLISHED' ? '已发布' : version.versionStatus === 'RETIRED' ? '历史版本' : '未发布' }}</el-button>
            {{ version.changeSummary }}
            <el-button
              v-if="auth.can('inspection:scheme:manage') && version.id !== detail.scheme.currentVersionId"
              link
              type="warning"
              @click="restoreVersion(detail!.scheme, version.id, version.versionNumber)"
            >恢复此版本</el-button>
          </el-timeline-item>
        </el-timeline>
      </template>
    </el-drawer>
    <InspectionImportDialog v-model="importVisible" @committed="load" />
  </div>
</template>

<style scoped>
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.inspection-editor-form { height: 100%; }
.inspection-editor-tabs { display: flex; height: 100%; flex-direction: column; overflow: hidden; }
.inspection-editor-tabs :deep(.el-tabs__header) { flex: none; margin-bottom: 12px; }
.inspection-editor-tabs :deep(.el-tabs__content) { flex: 1; min-height: 0; overflow: hidden; }
.inspection-editor-tabs :deep(.el-tab-pane) { height: 100%; }
.inspection-editor-pane { max-height: 100%; align-content: start; overflow-y: auto; padding-right: 8px; }
:global(.inspection-editor-dialog) { display: flex; max-height: calc(100vh - 32px); margin: 0 auto; flex-direction: column; }
:global(.inspection-editor-dialog .el-dialog__header),
:global(.inspection-editor-dialog .el-dialog__footer) { flex: none; }
:global(.inspection-editor-dialog .el-dialog__body) { flex: 1; min-height: 0; overflow: hidden; padding-top: 8px; padding-bottom: 8px; }
.page-actions { display: flex; gap: 10px; flex-wrap: wrap; }
.full { grid-column: 1 / -1; }
.field-hint { margin-top: 4px; color: var(--el-text-color-secondary); font-size: 12px; }
.scheme-photo-policy :deep(.el-form-item__content) { align-items: center; gap: 12px; }
@media (max-width: 700px) { .form-grid { grid-template-columns: 1fr; } .full { grid-column: auto; } }
</style>
