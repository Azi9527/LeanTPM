<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { equipmentApi, type EquipmentRow } from '@/api/equipment'
import { inspectionApi, type PlanRow, type SchemeRow } from '@/api/inspection'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'
import { applySmartTableQuery, type SmartTableServerQuery } from '@/components/table/smart-table-context'

const auth = useAuthStore()
const router = useRouter()
const loading = ref(false)
const generating = ref(false)
const rows = ref<PlanRow[]>([])
const total = ref(0)
const page = ref(1)
const keyword = ref('')
const status = ref<string>()
const smartTableQuery = reactive({
  tableFilters: undefined as string | undefined,
  sortBy: undefined as string | undefined,
  sortDirection: undefined as 'asc' | 'desc' | undefined,
})
const manualVisible = ref(false)
const manualLoading = ref(false)
const manualSaving = ref(false)
const schemes = ref<SchemeRow[]>([])
const equipment = ref<EquipmentRow[]>([])
const applicableEquipmentIds = ref<Set<number>>(new Set())
const applicabilityLoading = ref(false)
const manualForm = reactive<{ schemeId?: number; equipmentIds: number[] }>({
  schemeId: undefined,
  equipmentIds: [],
})

const statusMeta: Record<string, { label: string; type: 'success' | 'warning' | 'info' }> = {
  ACTIVE: { label: '执行中', type: 'success' },
  PAUSED: { label: '已暂停', type: 'warning' },
  CANCELLED: { label: '已取消', type: 'info' },
}
const cycleLabels: Record<string, string> = {
  DAILY: '每日',
  HOURLY: '每小时',
  WEEKLY: '每周',
  MONTHLY: '每月',
  INTERVAL_DAYS: '间隔天数',
}
const selectedScheme = computed(() =>
  schemes.value.find((item) => item.id === manualForm.schemeId),
)
const availableEquipment = computed(() =>
  equipment.value.filter((item) => applicableEquipmentIds.value.has(item.id)),
)

watch(() => manualForm.schemeId, async (schemeId) => {
  manualForm.equipmentIds = []
  applicableEquipmentIds.value = new Set()
  if (!schemeId) return
  applicabilityLoading.value = true
  try {
    applicableEquipmentIds.value = new Set(await inspectionApi.applicableEquipmentIds(schemeId))
  } catch (error) {
    ElMessage.error(errorMessage(error, '适用设备加载失败'))
  } finally {
    applicabilityLoading.value = false
  }
})

function optionLabel(...parts: unknown[]) {
  return parts
    .map((part) => String(part ?? '').trim())
    .filter((part) => part && !['undefined', 'null'].includes(part.toLowerCase()))
    .join(' · ')
}

function nextGenerationLabel(row: PlanRow) {
  if (!row.nextGenerationDate) return '—'
  const time = row.scheduledTime?.slice(0, 5)
  return time ? `${row.nextGenerationDate} ${time}` : row.nextGenerationDate
}

function schemeOptionLabel(scheme: SchemeRow) {
  const version = Number(scheme.currentVersionNumber)
  return optionLabel(
    scheme.schemeCode,
    scheme.schemeName,
    Number.isFinite(version) && version > 0 ? `V${version}` : '',
  )
}

function equipmentOptionLabel(item: EquipmentRow) {
  return optionLabel(item.equipmentCode, item.equipmentName, item.locationName)
}

onMounted(load)

async function load() {
  loading.value = true
  try {
    const result = await inspectionApi.plans({
      keyword: keyword.value || undefined,
      planStatus: status.value,
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

async function generate() {
  generating.value = true
  try {
    const result = await inspectionApi.generateTasks()
    ElMessage.success(`已生成 ${result.generatedTasks} 个任务，跳过 ${result.skippedOccurrences} 个已存在时点`)
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    generating.value = false
  }
}

async function openManual() {
  manualVisible.value = true
  manualForm.schemeId = undefined
  manualForm.equipmentIds = []
  applicableEquipmentIds.value = new Set()
  manualLoading.value = true
  try {
    const [schemePage, equipmentPage] = await Promise.all([
      inspectionApi.schemes({ status: 1, page: 1, pageSize: 200 }),
      equipmentApi.page({ status: 1, page: 1, pageSize: 200 }),
    ])
    schemes.value = schemePage.records.filter(
      (item) => item.currentVersionId && item.currentVersionStatus === 'PUBLISHED',
    )
    equipment.value = equipmentPage.records
  } catch (error) {
    ElMessage.error(errorMessage(error, '手工计划选项加载失败'))
  } finally {
    manualLoading.value = false
  }
}

async function createManualPlans() {
  if (!manualForm.schemeId || !manualForm.equipmentIds.length) {
    ElMessage.warning('请选择已发布点检方案和至少一台设备')
    return
  }
  manualSaving.value = true
  try {
    const result = await inspectionApi.createPlans({
      schemeId: manualForm.schemeId,
      equipmentIds: manualForm.equipmentIds,
    })
    ElMessage.success(
      `已处理 ${result.processedPlans} 条设备计划，下次生成日 ${result.nextGenerationDate}`,
    )
    manualVisible.value = false
    page.value = 1
    keyword.value = ''
    status.value = undefined
    smartTableQuery.tableFilters = undefined
    smartTableQuery.sortBy = undefined
    smartTableQuery.sortDirection = undefined
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error, '手工创建计划失败'))
  } finally {
    manualSaving.value = false
  }
}

async function changeStatus(row: PlanRow, target: 'ACTIVE' | 'PAUSED' | 'CANCELLED') {
  let reason: string | undefined
  if (target !== 'ACTIVE') {
    const value = await ElMessageBox.prompt(`请输入${target === 'PAUSED' ? '暂停' : '取消'}原因`, '变更计划状态', {
      inputPattern: /\S+/,
      inputErrorMessage: '原因不能为空',
    })
    reason = value.value
  }
  try {
    await inspectionApi.updatePlanStatus(row.id, { planStatus: target, reason, version: row.version })
    ElMessage.success('计划状态已更新')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function removePlan(row: PlanRow) {
  await ElMessageBox.confirm(
    `删除“${row.schemeName} / ${row.equipmentCode} · ${row.equipmentName}”计划？该计划及其已生成任务会一并逻辑删除，且不再参与任何查询和报表。`,
    '删除点检计划',
    { type: 'warning', confirmButtonText: '确认删除' },
  )
  try {
    await inspectionApi.deletePlan(row.id, row.version)
    ElMessage.success('点检计划及关联任务已删除')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div><h1>点检计划</h1><p>支持方案发布自动建计划，也可手工选择一个或多个设备批量创建。</p></div>
      <div class="header-actions">
        <el-button v-if="auth.can('inspection:plan:manage')" @click="openManual">手工创建计划</el-button>
        <el-button v-if="auth.can('inspection:plan:generate')" type="primary" :loading="generating" @click="generate">立即生成任务</el-button>
      </div>
    </header>
    <section class="surface-card query-bar">
      <el-input v-model="keyword" clearable placeholder="方案或设备编码、名称" @keyup.enter="page = 1; load()" />
      <el-select v-model="status" clearable placeholder="计划状态"><el-option v-for="(meta, value) in statusMeta" :key="value" :label="meta.label" :value="value" /></el-select>
      <el-button type="primary" @click="page = 1; load()">查询</el-button>
    </section>
    <section class="surface-card table-card" v-loading="loading">
      <div class="table-toolbar"><span class="table-title">设备计划</span><span>共 {{ total }} 条</span></div>
      <el-table :data="rows" row-key="id" server-query @smart-query-change="applyTableQuery">
        <el-table-column prop="schemeName" label="方案" min-width="220"><template #default="{ row }"><strong>{{ row.schemeName }}</strong><div class="muted mono">{{ row.schemeCode }} · V{{ row.schemeVersionNumber }}</div></template></el-table-column>
        <el-table-column prop="equipmentName" label="设备" min-width="200"><template #default="{ row }"><strong>{{ row.equipmentName }}</strong><div class="muted mono">{{ row.equipmentCode }}</div></template></el-table-column>
        <el-table-column prop="organizationName" label="所属部门" min-width="150" />
        <el-table-column prop="locationName" label="物理位置" min-width="140"><template #default="{ row }">{{ row.locationName || '未设置' }}</template></el-table-column>
        <el-table-column prop="cycleType" label="周期" smart-filter="select" width="130"><template #default="{ row }">{{ cycleLabels[row.cycleType] || row.cycleType }} × {{ row.cycleInterval }}</template></el-table-column>
        <el-table-column prop="assigneeName" label="执行人" width="120"><template #default="{ row }">{{ row.assigneeName || '待派工' }}</template></el-table-column>
        <el-table-column prop="nextGenerationDate" label="下次生成" smart-filter="date" width="170"><template #default="{ row }">{{ nextGenerationLabel(row) }}</template></el-table-column>
        <el-table-column prop="planStatus" label="状态" smart-filter="select" width="100"><template #default="{ row }"><el-tag :type="statusMeta[row.planStatus].type">{{ statusMeta[row.planStatus].label }}</el-tag></template></el-table-column>
        <el-table-column v-if="auth.can('inspection:plan:manage')" label="操作" smart-filter="none" width="220" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.planStatus === 'ACTIVE'" link type="warning" @click="changeStatus(row, 'PAUSED')">暂停</el-button>
            <el-button v-if="row.planStatus === 'PAUSED'" link type="success" @click="changeStatus(row, 'ACTIVE')">恢复</el-button>
            <el-button v-if="row.planStatus !== 'CANCELLED'" link type="danger" @click="changeStatus(row, 'CANCELLED')">取消</el-button>
            <el-button link type="danger" @click="removePlan(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @change="load" />
    </section>

    <el-dialog v-model="manualVisible" title="手工创建点检计划" width="min(720px, 94vw)">
      <div v-loading="manualLoading">
        <el-alert
          title="选择已发布方案并勾选多台设备，系统将为每台设备创建或恢复一条计划。"
          type="info"
          :closable="false"
          show-icon
        />
        <el-form class="manual-form" label-width="96px">
          <el-form-item label="点检方案" required>
            <el-select
              v-model="manualForm.schemeId"
              filterable
              placeholder="请选择已发布点检方案"
              style="width: 100%"
            >
              <el-option
                v-for="scheme in schemes"
                :key="scheme.id"
                :label="schemeOptionLabel(scheme)"
                :value="scheme.id"
              />
            </el-select>
            <div class="field-help">
              没有可用方案？
              <el-button link type="primary" @click="manualVisible = false; router.push('/inspection/schemes')">
                前往点检方案录入并发布
              </el-button>
            </div>
          </el-form-item>
          <el-form-item label="选择设备" required>
            <el-select
              v-model="manualForm.equipmentIds"
              multiple
              filterable
              :disabled="!manualForm.schemeId"
              :loading="applicabilityLoading"
              collapse-tags
              collapse-tags-tooltip
              :max-collapse-tags="4"
              placeholder="可同时选择多台启用设备"
              style="width: 100%"
            >
              <el-option
                v-for="item in availableEquipment"
                :key="item.id"
                :label="equipmentOptionLabel(item)"
                :value="item.id"
              />
            </el-select>
            <p class="field-help">
              <span v-if="!manualForm.schemeId">请先选择点检方案，系统只显示该方案适用的设备。</span>
              <span v-else-if="applicabilityLoading">正在加载适用设备……</span>
              <span v-else>可选 {{ availableEquipment.length }} 台，已选择 {{ manualForm.equipmentIds.length }} 台，最多一次处理 200 台。</span>
            </p>
          </el-form-item>
          <el-form-item v-if="selectedScheme" label="计划周期">
            <el-tag type="info">
              {{ cycleLabels[selectedScheme.cycleType || ''] || selectedScheme.cycleType }}
              × {{ selectedScheme.cycleInterval }}
            </el-tag>
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="manualVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="manualSaving"
          :disabled="!manualForm.schemeId || !manualForm.equipmentIds.length"
          @click="createManualPlans"
        >
          批量创建（{{ manualForm.equipmentIds.length }} 台设备）
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.muted { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 4px; }
.header-actions { display: flex; gap: 10px; }
.manual-form { margin-top: 22px; }
.field-help {
  width: 100%;
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}
</style>
