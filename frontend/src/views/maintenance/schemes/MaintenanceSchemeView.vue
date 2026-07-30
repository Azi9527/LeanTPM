<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { maintenanceApi, type ItemRow, type SchemeDetail, type SchemeRow } from '@/api/maintenance'
import { equipmentApi, type EquipmentRow } from '@/api/equipment'
import { masterDataApi, type EquipmentCategoryRow, type ReferenceUser } from '@/api/masterData'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const rows = ref<SchemeRow[]>([])
const total = ref(0)
const page = ref(1)
const keyword = ref('')
const dialogVisible = ref(false)
const detailVisible = ref(false)
const editing = ref<SchemeRow | null>(null)
const detail = ref<SchemeDetail | null>(null)
const items = ref<ItemRow[]>([])
const equipment = ref<EquipmentRow[]>([])
const categories = ref<EquipmentCategoryRow[]>([])
const users = ref<ReferenceUser[]>([])

const form = reactive({
  schemeCode: '',
  schemeName: '',
  maintenanceType: 'LEVEL_1',
  cycleType: 'DAILY',
  cycleInterval: 1,
  triggerThreshold: undefined as number | undefined,
  weekDays: [] as number[],
  monthDays: [] as number[],
  scheduledTime: '08:00:00',
  reminderDays: 3,
  generationLeadDays: 7,
  shiftCode: '',
  defaultAssigneeUserId: undefined as number | undefined,
  defaultTeamCode: '',
  reviewRequired: true,
  backfillAllowed: false,
  stopRequired: true,
  restoreStatusCode: 'IDLE',
  effectiveDate: new Date().toISOString().slice(0, 10),
  expiryDate: '',
  itemIds: [] as number[],
  categoryIds: [] as number[],
  equipmentIds: [] as number[],
  enabled: true,
  description: '',
  changeSummary: '',
})

const typeLabels: Record<string, string> = {
  DAILY: '日常保养',
  LEVEL_1: '一级保养',
  LEVEL_2: '二级保养',
  LEVEL_3: '三级保养',
  SPECIAL: '专项保养',
  ANNUAL: '年度保养',
}
const cycleLabels: Record<string, string> = {
  DAILY: '按日',
  WEEKLY: '按周',
  MONTHLY: '按月',
  QUARTERLY: '按季度',
  HALF_YEARLY: '按半年',
  YEARLY: '按年',
  RUNNING_HOURS: '累计运行小时',
  PRODUCTION_QUANTITY: '累计生产数量',
  MANUAL: '手工触发',
}

onMounted(async () => {
  await Promise.all([load(), loadReferences()])
})

async function load() {
  loading.value = true
  try {
    const result = await maintenanceApi.schemes({ keyword: keyword.value || undefined, page: page.value, pageSize: 20 })
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
    const [itemPage, equipmentPage, categoryRows, userRows] = await Promise.all([
      maintenanceApi.items({ status: 1, page: 1, pageSize: 200 }),
      equipmentApi.page({ status: 1, page: 1, pageSize: 200 }),
      masterDataApi.categories(),
      masterDataApi.referenceUsers(),
    ])
    items.value = itemPage.records
    equipment.value = equipmentPage.records
    categories.value = categoryRows.filter((row) => row.status === 1)
    users.value = userRows
  } catch (error) {
    ElMessage.error(errorMessage(error, '加载方案引用数据失败'))
  }
}

function resetForm() {
  Object.assign(form, {
    schemeCode: '',
    schemeName: '',
    maintenanceType: 'LEVEL_1',
    cycleType: 'DAILY',
    cycleInterval: 1,
    triggerThreshold: undefined,
    weekDays: [],
    monthDays: [],
    scheduledTime: '08:00:00',
    reminderDays: 3,
    generationLeadDays: 7,
    shiftCode: '',
    defaultAssigneeUserId: undefined,
    defaultTeamCode: '',
    reviewRequired: true,
    backfillAllowed: false,
    stopRequired: true,
    restoreStatusCode: 'IDLE',
    effectiveDate: new Date().toISOString().slice(0, 10),
    expiryDate: '',
    itemIds: [],
    categoryIds: [],
    equipmentIds: [],
    enabled: true,
    description: '',
    changeSummary: '',
  })
}

async function open(row?: SchemeRow) {
  editing.value = row || null
  resetForm()
  if (row) {
    try {
      const value = await maintenanceApi.scheme(row.id)
      Object.assign(form, {
        schemeCode: value.scheme.schemeCode,
        schemeName: value.scheme.schemeName,
        maintenanceType: value.scheme.maintenanceType,
        cycleType: value.version.cycleType,
        cycleInterval: value.version.cycleInterval,
        triggerThreshold: value.version.triggerThreshold,
        weekDays: csvNumbers(value.version.weekDays),
        monthDays: csvNumbers(value.version.monthDays),
        scheduledTime: value.version.scheduledTime || '08:00:00',
        reminderDays: value.version.reminderDays,
        generationLeadDays: value.version.generationLeadDays,
        shiftCode: value.version.shiftCode || '',
        defaultAssigneeUserId: value.version.defaultAssigneeUserId,
        defaultTeamCode: value.version.defaultTeamCode || '',
        reviewRequired: value.version.reviewRequiredFlag,
        backfillAllowed: value.version.backfillAllowedFlag,
        stopRequired: value.version.stopRequiredFlag,
        restoreStatusCode: value.version.restoreStatusCode || 'IDLE',
        effectiveDate: value.version.effectiveDate,
        expiryDate: value.version.expiryDate || '',
        itemIds: value.items.map((item) => item.maintenanceItemId),
        categoryIds: value.applicability.categoryIds,
        equipmentIds: value.applicability.equipmentIds,
        enabled: value.scheme.status === 1,
        description: value.scheme.description || '',
        changeSummary: '',
      })
    } catch (error) {
      ElMessage.error(errorMessage(error))
      return
    }
  }
  dialogVisible.value = true
}

async function save() {
  if (!form.schemeName || !form.itemIds.length || (!form.categoryIds.length && !form.equipmentIds.length)) {
    ElMessage.warning('请填写方案名称，并选择项目及适用设备范围')
    return
  }
  saving.value = true
  try {
    const payload = {
      ...form,
      schemeCode: form.schemeCode || null,
      weekDays: form.weekDays.join(',') || null,
      monthDays: form.monthDays.join(',') || null,
      expiryDate: form.expiryDate || null,
      defaultAssigneeUserId: form.defaultAssigneeUserId || null,
      triggerThreshold: ['RUNNING_HOURS', 'PRODUCTION_QUANTITY'].includes(form.cycleType)
        ? form.triggerThreshold
        : null,
      items: form.itemIds.map((maintenanceItemId, index) => ({
        maintenanceItemId,
        sortOrder: (index + 1) * 10,
        required: null,
        photoRequired: null,
        attachmentRequired: null,
        skipAllowed: null,
        stopRequired: null,
      })),
      version: editing.value?.version,
    }
    if (editing.value) await maintenanceApi.createSchemeVersion(editing.value.id, payload)
    else await maintenanceApi.createScheme(payload)
    dialogVisible.value = false
    ElMessage.success(editing.value ? '方案新草稿版本已创建' : '维保方案已创建')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function showDetail(row: SchemeRow, versionId?: number) {
  try {
    detail.value = await maintenanceApi.scheme(row.id, versionId)
    detailVisible.value = true
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function publish(row: SchemeRow) {
  const value = await maintenanceApi.scheme(row.id)
  const draft = value.versionHistory.find((version) => version.versionStatus === 'DRAFT')
  if (!draft) {
    ElMessage.warning('当前方案没有待发布草稿版本')
    return
  }
  await ElMessageBox.confirm(`发布 ${row.schemeName} V${draft.versionNumber} 并生成设备计划？`, '发布方案', { type: 'warning' })
  try {
    await maintenanceApi.publishScheme(row.id, draft.id)
    ElMessage.success('方案已发布，维保计划已同步')
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
      <div><h1>维保方案</h1><p>方案按版本发布；已发布版本不可修改，历史任务永久保留项目快照。</p></div>
      <el-button v-if="auth.can('maintenance:scheme:manage')" type="primary" @click="open()">新增方案</el-button>
    </header>
    <section class="surface-card query-bar">
      <el-input v-model="keyword" clearable placeholder="方案编码或名称" @keyup.enter="page = 1; load()" />
      <el-button type="primary" @click="page = 1; load()">查询</el-button>
    </section>
    <section class="surface-card table-card" v-loading="loading">
      <div class="table-toolbar"><span class="table-title">方案版本库</span><span>共 {{ total }} 个方案</span></div>
      <el-table :data="rows" row-key="id">
        <el-table-column prop="schemeCode" label="方案编码" min-width="180"><template #default="{ row }"><span class="mono">{{ row.schemeCode }}</span></template></el-table-column>
        <el-table-column prop="schemeName" label="方案名称" min-width="190" />
        <el-table-column label="类型" width="120"><template #default="{ row }">{{ typeLabels[row.maintenanceType] || row.maintenanceType }}</template></el-table-column>
        <el-table-column label="当前版本" width="120"><template #default="{ row }"><el-tag :type="row.currentVersionStatus === 'PUBLISHED' ? 'success' : 'warning'">V{{ row.currentVersionNumber || '—' }} · {{ row.currentVersionStatus || '草稿' }}</el-tag></template></el-table-column>
        <el-table-column label="周期" width="120"><template #default="{ row }">{{ cycleLabels[row.cycleType] || row.cycleType || '—' }}</template></el-table-column>
        <el-table-column prop="itemCount" label="项目" width="80" />
        <el-table-column prop="activePlanCount" label="有效计划" width="100" />
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="showDetail(row)">详情</el-button>
            <el-button v-if="auth.can('maintenance:scheme:manage')" link type="primary" @click="open(row)">新版本</el-button>
            <el-button v-if="auth.can('maintenance:scheme:publish')" link type="success" @click="publish(row)">发布草稿</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @change="load" />
    </section>

    <el-dialog v-model="dialogVisible" :title="editing ? '创建方案新版本' : '新增维保方案'" width="min(980px, 97vw)">
      <el-form label-position="top" class="form-grid">
        <el-form-item label="方案编码"><el-input v-model="form.schemeCode" :disabled="Boolean(editing)" placeholder="留空自动编号" /></el-form-item>
        <el-form-item label="方案名称"><el-input v-model="form.schemeName" /></el-form-item>
        <el-form-item label="维保类型"><el-select v-model="form.maintenanceType"><el-option v-for="(label, value) in typeLabels" :key="value" :label="label" :value="value" /></el-select></el-form-item>
        <el-form-item label="周期类型"><el-select v-model="form.cycleType"><el-option v-for="(label, value) in cycleLabels" :key="value" :label="label" :value="value" /></el-select></el-form-item>
        <el-form-item label="周期间隔"><el-input-number v-model="form.cycleInterval" :min="1" /></el-form-item>
        <el-form-item label="计划时间"><el-time-picker v-model="form.scheduledTime" value-format="HH:mm:ss" /></el-form-item>
        <el-form-item v-if="['RUNNING_HOURS','PRODUCTION_QUANTITY'].includes(form.cycleType)" label="触发阈值"><el-input-number v-model="form.triggerThreshold" :min="0.001" :precision="3" /></el-form-item>
        <el-form-item label="提前提醒（天）"><el-input-number v-model="form.reminderDays" :min="0" /></el-form-item>
        <el-form-item label="提前生成（天）"><el-input-number v-model="form.generationLeadDays" :min="0" /></el-form-item>
        <el-form-item v-if="form.cycleType === 'WEEKLY'" label="执行星期" class="full"><el-checkbox-group v-model="form.weekDays"><el-checkbox v-for="day in 7" :key="day" :value="day">周{{ '一二三四五六日'[day - 1] }}</el-checkbox></el-checkbox-group></el-form-item>
        <el-form-item v-if="form.cycleType === 'MONTHLY'" label="每月日期" class="full"><el-select v-model="form.monthDays" multiple collapse-tags><el-option v-for="day in 31" :key="day" :label="`${day}日`" :value="day" /></el-select></el-form-item>
        <el-form-item label="默认执行人"><el-select v-model="form.defaultAssigneeUserId" clearable filterable><el-option v-for="user in users" :key="user.id" :label="`${user.realName} (${user.username})`" :value="user.id" /></el-select></el-form-item>
        <el-form-item label="默认班组"><el-input v-model="form.defaultTeamCode" /></el-form-item>
        <el-form-item label="生效日期"><el-date-picker v-model="form.effectiveDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="失效日期"><el-date-picker v-model="form.expiryDate" type="date" value-format="YYYY-MM-DD" clearable /></el-form-item>
        <el-form-item label="维保项目" class="full"><el-select v-model="form.itemIds" multiple filterable collapse-tags collapse-tags-tooltip><el-option v-for="item in items" :key="item.id" :label="`${item.itemCode} · ${item.itemName}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="适用设备分类" class="full"><el-select v-model="form.categoryIds" multiple filterable collapse-tags><el-option v-for="category in categories" :key="category.id" :label="`${category.categoryCode} · ${category.categoryName}`" :value="category.id" /></el-select></el-form-item>
        <el-form-item label="指定设备" class="full"><el-select v-model="form.equipmentIds" multiple filterable collapse-tags collapse-tags-tooltip><el-option v-for="row in equipment" :key="row.id" :label="`${row.equipmentCode} · ${row.equipmentName}`" :value="row.id" /></el-select></el-form-item>
        <el-form-item label="完成后恢复状态"><el-select v-model="form.restoreStatusCode"><el-option label="待机" value="IDLE" /><el-option label="停机" value="STOPPED" /><el-option label="故障" value="FAULT" /><el-option label="离线" value="OFFLINE" /></el-select></el-form-item>
        <el-form-item label="执行控制" class="full"><el-checkbox v-model="form.reviewRequired">提交后确认</el-checkbox><el-checkbox v-model="form.backfillAllowed">允许补录</el-checkbox><el-checkbox v-model="form.stopRequired">需要停机</el-checkbox><el-checkbox v-model="form.enabled">启用方案</el-checkbox></el-form-item>
        <el-form-item label="版本说明" class="full"><el-input v-model="form.changeSummary" type="textarea" placeholder="说明本版本调整内容" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存草稿</el-button></template>
    </el-dialog>

    <el-drawer v-model="detailVisible" :title="`${detail?.scheme.schemeName || ''} · 版本详情`" size="min(820px, 96vw)">
      <template v-if="detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="方案编码">{{ detail.scheme.schemeCode }}</el-descriptions-item>
          <el-descriptions-item label="版本">V{{ detail.version.versionNumber }} · {{ detail.version.versionStatus }}</el-descriptions-item>
          <el-descriptions-item label="周期">{{ cycleLabels[detail.version.cycleType] }} / {{ detail.version.cycleInterval }}</el-descriptions-item>
          <el-descriptions-item label="默认执行人">{{ detail.version.defaultAssigneeName || '未指定' }}</el-descriptions-item>
          <el-descriptions-item label="生效">{{ detail.version.effectiveDate }} ~ {{ detail.version.expiryDate || '长期' }}</el-descriptions-item>
          <el-descriptions-item label="控制">{{ detail.version.reviewRequiredFlag ? '需确认' : '标准确认' }} / {{ detail.version.stopRequiredFlag ? '需停机' : '不停机' }} / {{ detail.version.backfillAllowedFlag ? '可补录' : '不可补录' }}</el-descriptions-item>
        </el-descriptions>
        <h3>维保项目</h3>
        <el-table :data="detail.items" size="small"><el-table-column prop="sortOrder" label="#" width="60" /><el-table-column prop="itemCode" label="编码" min-width="150" /><el-table-column prop="itemName" label="名称" min-width="150" /><el-table-column prop="resultType" label="结果类型" width="140" /></el-table>
        <h3>版本历史</h3>
        <el-timeline><el-timeline-item v-for="version in detail.versionHistory" :key="version.id" :timestamp="version.publishedTime || version.effectiveDate"><el-button link type="primary" @click="showDetail(detail!.scheme, version.id)">V{{ version.versionNumber }} · {{ version.versionStatus }}</el-button> {{ version.changeSummary }}</el-timeline-item></el-timeline>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.full { grid-column: 1 / -1; }
@media (max-width: 700px) { .form-grid { grid-template-columns: 1fr; } .full { grid-column: auto; } }
</style>
