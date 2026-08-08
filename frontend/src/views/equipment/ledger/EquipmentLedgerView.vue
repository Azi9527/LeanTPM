<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import {
  equipmentApi,
  type AttributeValueRow,
  type EquipmentDetail,
  type EquipmentQuery,
  type EquipmentRow,
  type LifecycleStage,
} from '@/api/equipment'
import { applySmartTableQuery, type SmartTableServerQuery } from '@/components/table/smart-table-context'
import {
  masterDataApi,
  type AttributeDefinitionRow,
  type EquipmentCategoryRow,
  type LocationRow,
  type OrganizationRow,
  type ReferenceUser,
} from '@/api/masterData'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'
import { equipmentStatusLabel, equipmentStatusType } from '@/utils/equipment-status'
import { useRoute } from 'vue-router'

interface ResponsibleDraft {
  userId?: number
  responsibilityType: 'PRIMARY' | 'OPERATOR' | 'INSPECTOR' | 'MAINTAINER'
  startDate?: string
  endDate?: string
}

const auth = useAuthStore()
const route = useRoute()
const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const rows = ref<EquipmentRow[]>([])
const total = ref(0)
const categories = ref<EquipmentCategoryRow[]>([])
const organizations = ref<OrganizationRow[]>([])
const locations = ref<LocationRow[]>([])
const users = ref<ReferenceUser[]>([])
const definitions = ref<AttributeDefinitionRow[]>([])
const query = reactive<EquipmentQuery>({ page: 1, pageSize: 100 })

const formVisible = ref(false)
const detailVisible = ref(false)
const transferVisible = ref(false)
const copyVisible = ref(false)
const importVisible = ref(false)
const editing = ref<EquipmentRow | null>(null)
const selected = ref<EquipmentRow | null>(null)
const detail = ref<EquipmentDetail | null>(null)
const importFile = ref<File | null>(null)
const importResult = ref<Awaited<ReturnType<typeof equipmentApi.importWorkbook>> | null>(null)

const form = reactive({
  equipmentCode: '',
  equipmentName: '',
  categoryId: undefined as number | undefined,
  model: '',
  specification: '',
  brand: '',
  manufacturer: '',
  factorySerialNumber: '',
  productionDate: '',
  commissioningDate: '',
  organizationId: undefined as number | undefined,
  locationId: undefined as number | undefined,
  primaryResponsibleUserId: undefined as number | undefined,
  assetNumber: '',
  lifecycleStage: 'IN_SERVICE' as LifecycleStage,
  critical: false,
  special: false,
  oeeEnabled: true,
  enabled: true,
  description: '',
  attributeValues: {} as Record<number, string | undefined>,
  responsiblePersons: [] as ResponsibleDraft[],
})

const transferForm = reactive({
  organizationId: undefined as number | undefined,
  locationId: undefined as number | undefined,
  primaryResponsibleUserId: undefined as number | undefined,
  reason: '',
})
const copyForm = reactive({ equipmentCode: '', equipmentName: '' })

const lifecycleLabels: Record<LifecycleStage, string> = {
  PLANNING: '规划',
  INSTALLATION: '安装',
  COMMISSIONING: '调试',
  IN_SERVICE: '在役',
  IDLE: '闲置',
  SEALED: '封存',
  SCRAPPED: '报废',
}
const responsibilityLabels = {
  PRIMARY: '主负责人',
  OPERATOR: '操作人',
  INSPECTOR: '点检人',
  MAINTAINER: '维保人',
}
const changeOperationLabels: Record<string, string> = {
  CREATE: '新增', UPDATE: '修改', DELETE: '删除', ENABLE: '启用', DISABLE: '停用',
  TRANSFER: '调拨', COPY: '复制', BIND: '关联', UNBIND: '解除关联', IMPORT: '导入',
}
const changeFieldLabels: Record<string, string> = {
  equipmentCode: '设备编码', equipmentName: '设备名称', categoryId: '设备分类',
  model: '型号', specification: '规格', brand: '品牌', manufacturer: '制造商',
  factorySerialNumber: '出厂编号', productionDate: '生产日期', commissioningDate: '投产日期',
  organizationId: '所属部门', locationId: '物理位置', primaryResponsibleUserId: '负责人',
  assetNumber: '资产编号', lifecycleStage: '生命周期', critical: '关键设备', special: '特种设备',
  oeeEnabled: '纳入 OEE', enabled: '启用状态', description: '设备说明', statusCode: '设备状态',
}

function changedFieldText(value?: string) {
  try {
    const fields = JSON.parse(value || '[]')
    if (!Array.isArray(fields) || !fields.length) return '—'
    return fields.map((field) => changeFieldLabels[String(field)] || String(field)).join('、')
  } catch {
    return '—'
  }
}

const filteredLocations = computed(() =>
  locations.value.filter((row) =>
    !form.organizationId || row.organizationId === form.organizationId,
  ),
)
const transferLocations = computed(() =>
  locations.value.filter((row) =>
    !transferForm.organizationId || row.organizationId === transferForm.organizationId,
  ),
)
const activeCategories = computed(() => categories.value.filter((row) => row.status === 1))
const activeOrganizations = computed(() => organizations.value.filter((row) => row.status === 1))

watch(() => form.organizationId, () => {
  if (!filteredLocations.value.some((row) => row.id === form.locationId)) {
    form.locationId = undefined
  }
})
watch(() => transferForm.organizationId, () => {
  if (!transferLocations.value.some((row) => row.id === transferForm.locationId)) {
    transferForm.locationId = undefined
  }
})

onMounted(async () => {
  if (typeof route.query.currentStatusCode === 'string') {
    query.currentStatusCode = route.query.currentStatusCode
  }
  await Promise.all([loadReferences(), load()])
  const equipmentId = Number(route.query.equipmentId)
  if (Number.isInteger(equipmentId) && equipmentId > 0) {
    await showDetailById(equipmentId)
  }
})

async function loadReferences() {
  try {
    const [categoryRows, organizationRows, locationRows, userRows] = await Promise.all([
      masterDataApi.categories(),
      masterDataApi.organizations(),
      masterDataApi.locations(),
      masterDataApi.referenceUsers(),
    ])
    categories.value = categoryRows
    organizations.value = organizationRows
    locations.value = locationRows
    users.value = userRows
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const result = await equipmentApi.page(query)
    rows.value = result.records
    total.value = result.total
  } catch (error) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

function applyTableQuery(tableQuery: SmartTableServerQuery) {
  applySmartTableQuery(query, tableQuery)
  query.page = 1
  void load()
}

async function changeCategory(categoryId?: number, existing?: AttributeValueRow[]) {
  definitions.value = categoryId
    ? await masterDataApi.attributes(categoryId, true)
    : []
  form.attributeValues = {}
  definitions.value.forEach((definition) => {
    const saved = existing?.find((value) => value.definitionId === definition.id)
    form.attributeValues[definition.id] = saved?.value || definition.defaultValue
  })
}

async function openForm(row?: EquipmentRow) {
  editing.value = row || null
  let currentDetail: EquipmentDetail | null = null
  if (row) currentDetail = await equipmentApi.detail(row.id)
  Object.assign(form, {
    equipmentCode: row?.equipmentCode || '',
    equipmentName: row?.equipmentName || '',
    categoryId: row?.categoryId,
    model: row?.model || '',
    specification: row?.specification || '',
    brand: row?.brand || '',
    manufacturer: row?.manufacturer || '',
    factorySerialNumber: row?.factorySerialNumber || '',
    productionDate: row?.productionDate || '',
    commissioningDate: row?.commissioningDate || '',
    organizationId: row?.organizationId,
    locationId: row?.locationId,
    primaryResponsibleUserId: row?.primaryResponsibleUserId,
    assetNumber: row?.assetNumber || '',
    lifecycleStage: row?.lifecycleStage || 'IN_SERVICE',
    critical: row?.criticalFlag || false,
    special: row?.specialFlag || false,
    oeeEnabled: row?.oeeEnabled ?? true,
    enabled: row ? row.status === 1 : true,
    description: row?.description || '',
    responsiblePersons: currentDetail?.responsiblePersons.map((person) => ({
      userId: person.userId,
      responsibilityType: person.responsibilityType,
      startDate: person.startDate,
      endDate: person.endDate,
    })) || [],
  })
  await changeCategory(row?.categoryId, currentDetail?.attributes)
  formVisible.value = true
}

function addResponsible() {
  form.responsiblePersons.push({
    userId: undefined,
    responsibilityType: 'OPERATOR',
    startDate: undefined,
    endDate: undefined,
  })
}

async function save() {
  if (!form.equipmentName.trim() || !form.categoryId || !form.organizationId) {
    ElMessage.warning('请完整填写设备名称、分类和所属组织')
    return
  }
  saving.value = true
  try {
    const payload = {
      ...form,
      equipmentCode: form.equipmentCode.trim().toUpperCase(),
      attributes: definitions.value.map((definition) => ({
        definitionId: definition.id,
        value: form.attributeValues[definition.id] || null,
      })),
      responsiblePersons: form.responsiblePersons.filter((person) => person.userId),
      version: editing.value?.version,
    }
    delete (payload as Partial<typeof payload>).attributeValues
    if (editing.value) await equipmentApi.update(editing.value.id, payload)
    else await equipmentApi.create(payload)
    formVisible.value = false
    ElMessage.success(editing.value ? '设备已更新' : '设备已创建')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function showDetail(row: EquipmentRow) {
  selected.value = row
  detailVisible.value = true
  detail.value = null
  try {
    detail.value = await equipmentApi.detail(row.id)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function showDetailById(equipmentId: number) {
  detailVisible.value = true
  detail.value = null
  try {
    const loaded = await equipmentApi.detail(equipmentId)
    detail.value = loaded
    selected.value = loaded.equipment
  } catch (error) {
    detailVisible.value = false
    ElMessage.error(errorMessage(error))
  }
}

function openTransfer(row: EquipmentRow) {
  selected.value = row
  Object.assign(transferForm, {
    organizationId: row.organizationId,
    locationId: row.locationId,
    primaryResponsibleUserId: row.primaryResponsibleUserId,
    reason: '',
  })
  transferVisible.value = true
}

async function transfer() {
  if (!selected.value || !transferForm.organizationId || !transferForm.reason.trim()) {
    ElMessage.warning('请完整填写调拨目标和原因')
    return
  }
  saving.value = true
  try {
    await equipmentApi.transfer(selected.value.id, {
      ...transferForm,
      version: selected.value.version,
    })
    transferVisible.value = false
    ElMessage.success('设备调拨完成')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

function openCopy(row: EquipmentRow) {
  selected.value = row
  copyForm.equipmentCode = ''
  copyForm.equipmentName = `${row.equipmentName}-副本`
  copyVisible.value = true
}

async function copyEquipment() {
  if (!selected.value || !copyForm.equipmentName.trim()) return
  saving.value = true
  try {
    await equipmentApi.copy(selected.value.id, {
      equipmentCode: copyForm.equipmentCode.trim().toUpperCase(),
      equipmentName: copyForm.equipmentName.trim(),
    })
    copyVisible.value = false
    ElMessage.success('设备已复制')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function remove(row: EquipmentRow) {
  await ElMessageBox.confirm(
    `确认删除设备“${row.equipmentName}”吗？已有业务记录的设备只能停用。`,
    '删除设备',
    { type: 'warning' },
  )
  try {
    await equipmentApi.delete(row.id, row.version)
    ElMessage.success('设备已删除')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

function selectImportFile(uploadFile: UploadFile) {
  importFile.value = uploadFile.raw || null
  importResult.value = null
}

async function runImport() {
  if (!importFile.value) {
    ElMessage.warning('请选择 Excel 文件')
    return
  }
  saving.value = true
  try {
    importResult.value = await equipmentApi.importWorkbook(importFile.value)
    ElMessage.success(`成功导入 ${importResult.value.importedRows} 台设备`)
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function exportData() {
  try {
    await equipmentApi.exportWorkbook(query)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

function duration(seconds?: number) {
  if (seconds == null) return '—'
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  return [days ? `${days}天` : '', hours ? `${hours}时` : '', `${minutes}分`]
    .filter(Boolean).join(' ')
}

function equipmentFlagLabel(_: unknown, row: Record<string, unknown>) {
  if (row.criticalFlag && row.specialFlag) return '关键、特种'
  if (row.criticalFlag) return '关键'
  if (row.specialFlag) return '特种'
  return '无标识'
}

function enabledLabel(value: unknown) {
  return Number(value) === 1 ? '启用' : '停用'
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div>
        <h1>设备台账</h1>
        <p>统一管理设备档案、分类属性、责任人、归属、状态履历、二维码和技术文档。</p>
      </div>
      <div class="header-actions">
        <el-button
          v-if="auth.can('equipment:ledger:import')"
          @click="importVisible = true"
        >导入</el-button>
        <el-button
          v-if="auth.can('equipment:ledger:export')"
          @click="exportData"
        >导出</el-button>
        <el-button
          v-if="auth.can('equipment:ledger:create')"
          type="primary"
          @click="openForm()"
        >新增设备</el-button>
      </div>
    </header>

    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      show-icon
      :closable="false"
    />

    <section class="surface-card table-card" v-loading="loading">
      <div class="table-toolbar">
        <span class="table-title">设备列表</span>
        <span class="result-count">共 {{ total }} 台</span>
      </div>
      <el-table :data="rows" row-key="id" server-query @smart-query-change="applyTableQuery">
        <el-table-column prop="equipmentCode" label="设备编码" min-width="160">
          <template #default="{ row }">
            <el-button link type="primary" @click="showDetail(row)">{{ row.equipmentCode }}</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="equipmentName" label="设备名称" min-width="180" />
        <el-table-column prop="categoryName" label="分类" min-width="120" />
        <el-table-column prop="organizationName" label="组织" min-width="140" />
        <el-table-column prop="locationName" label="位置" min-width="130" />
        <el-table-column prop="primaryResponsibleName" label="负责人" width="110">
          <template #default="{ row }">{{ row.primaryResponsibleName || '—' }}</template>
        </el-table-column>
        <el-table-column
          prop="currentStatusCode"
          label="当前状态"
          width="110"
          :filter-formatter="equipmentStatusLabel"
          smart-filter="select"
        >
          <template #default="{ row }">
            <el-tag :type="equipmentStatusType(row.currentStatusCode)">
              {{ equipmentStatusLabel(row.currentStatusCode) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="statusDurationSeconds"
          label="持续时间"
          width="120"
          :filter-formatter="duration"
          smart-filter="number"
        >
          <template #default="{ row }">{{ duration(row.statusDurationSeconds) }}</template>
        </el-table-column>
        <el-table-column
          prop="criticalFlag"
          label="标识"
          width="120"
          :filter-formatter="equipmentFlagLabel"
          smart-filter="number"
        >
          <template #default="{ row }">
            <el-tag v-if="row.criticalFlag" size="small" type="warning">关键</el-tag>
            <el-tag v-if="row.specialFlag" size="small" type="danger">特种</el-tag>
            <span v-if="!row.criticalFlag && !row.specialFlag">—</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="status"
          label="启用"
          width="80"
          :filter-formatter="enabledLabel"
          smart-filter="number"
        >
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button v-if="auth.can('equipment:ledger:update')" link type="primary" @click="openForm(row)">编辑</el-button>
            <el-button v-if="auth.can('equipment:ledger:copy')" link type="primary" @click="openCopy(row)">复制</el-button>
            <el-button v-if="auth.can('equipment:ledger:transfer')" link type="primary" @click="openTransfer(row)">调拨</el-button>
            <el-button v-if="auth.can('equipment:ledger:delete')" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="当前数据范围内暂无设备" /></template>
      </el-table>
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        @change="load"
      />
    </section>

    <el-dialog
      v-model="formVisible"
      :title="editing ? '编辑设备' : '新增设备'"
      width="min(1040px, 96vw)"
      destroy-on-close
    >
      <el-form label-position="top" class="equipment-form">
        <el-divider content-position="left">基本档案</el-divider>
        <el-form-item label="设备编码">
          <el-input v-model="form.equipmentCode" :disabled="Boolean(editing)" placeholder="留空按编号规则自动生成" />
        </el-form-item>
        <el-form-item label="设备名称"><el-input v-model="form.equipmentName" /></el-form-item>
        <el-form-item label="设备分类">
          <el-select v-model="form.categoryId" filterable @change="changeCategory(form.categoryId)">
            <el-option v-for="item in activeCategories" :key="item.id" :label="item.categoryName" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="生命周期">
          <el-select v-model="form.lifecycleStage">
            <el-option v-for="(label, value) in lifecycleLabels" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="型号"><el-input v-model="form.model" /></el-form-item>
        <el-form-item label="规格"><el-input v-model="form.specification" /></el-form-item>
        <el-form-item label="品牌"><el-input v-model="form.brand" /></el-form-item>
        <el-form-item label="制造商"><el-input v-model="form.manufacturer" /></el-form-item>
        <el-form-item label="出厂编号"><el-input v-model="form.factorySerialNumber" /></el-form-item>
        <el-form-item label="资产编号"><el-input v-model="form.assetNumber" /></el-form-item>
        <el-form-item label="生产日期"><el-date-picker v-model="form.productionDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="投产日期"><el-date-picker v-model="form.commissioningDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>

        <el-divider content-position="left">组织与位置</el-divider>
        <el-form-item label="所属组织">
          <el-select v-model="form.organizationId" filterable>
            <el-option v-for="item in activeOrganizations" :key="item.id" :label="item.organizationName" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="物理位置">
          <el-select
            v-model="form.locationId"
            clearable
            filterable
            placeholder="可选；未指定物理位置"
          >
            <el-option v-for="item in filteredLocations" :key="item.id" :label="item.locationName" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="主负责人">
          <el-select v-model="form.primaryResponsibleUserId" clearable filterable>
            <el-option v-for="user in users" :key="user.id" :label="`${user.realName}（${user.username}）`" :value="user.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="设备标识" class="flag-item">
          <el-checkbox v-model="form.critical">关键设备</el-checkbox>
          <el-checkbox v-model="form.special">特种设备</el-checkbox>
          <el-checkbox v-model="form.oeeEnabled">纳入 OEE</el-checkbox>
          <el-checkbox
            v-model="form.enabled"
            :disabled="Boolean(editing) && !auth.can('equipment:ledger:status')"
          >启用</el-checkbox>
        </el-form-item>

        <template v-if="definitions.length">
          <el-divider content-position="left">分类扩展属性</el-divider>
          <el-form-item
            v-for="definition in definitions"
            :key="definition.id"
            :label="`${definition.attributeName}${definition.unit ? `（${definition.unit}）` : ''}`"
            :required="definition.requiredFlag"
          >
            <el-select
              v-if="definition.dataType === 'ENUM'"
              v-model="form.attributeValues[definition.id]"
              clearable
            >
              <el-option
                v-for="option in JSON.parse(definition.enumOptionsJson || '[]')"
                :key="option"
                :label="option"
                :value="option"
              />
            </el-select>
            <el-switch
              v-else-if="definition.dataType === 'BOOLEAN'"
              :model-value="form.attributeValues[definition.id] === 'true'"
              @update:model-value="form.attributeValues[definition.id] = $event ? 'true' : 'false'"
            />
            <el-date-picker
              v-else-if="definition.dataType === 'DATE'"
              v-model="form.attributeValues[definition.id]"
              type="date"
              value-format="YYYY-MM-DD"
            />
            <el-input v-else v-model="form.attributeValues[definition.id]" />
          </el-form-item>
        </template>

        <el-divider content-position="left">责任人</el-divider>
        <div class="responsible-list full-row">
          <div v-for="(person, index) in form.responsiblePersons" :key="index" class="responsible-row">
            <el-select v-model="person.userId" filterable placeholder="选择人员">
              <el-option v-for="user in users" :key="user.id" :label="user.realName" :value="user.id" />
            </el-select>
            <el-select v-model="person.responsibilityType">
              <el-option v-for="(label, value) in responsibilityLabels" :key="value" :label="label" :value="value" />
            </el-select>
            <el-date-picker v-model="person.startDate" type="date" value-format="YYYY-MM-DD" placeholder="开始日期" />
            <el-date-picker v-model="person.endDate" type="date" value-format="YYYY-MM-DD" placeholder="结束日期" />
            <el-button type="danger" link @click="form.responsiblePersons.splice(index, 1)">移除</el-button>
          </div>
          <el-button plain @click="addResponsible">添加责任人</el-button>
        </div>
        <el-form-item label="设备说明" class="full-row">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="1000" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" title="设备详情" size="min(900px, 96vw)">
      <div v-if="detail" class="detail-content">
        <div class="detail-hero">
          <div>
            <span class="mono">{{ detail.equipment.equipmentCode }}</span>
            <h2>{{ detail.equipment.equipmentName }}</h2>
          </div>
          <el-tag :type="equipmentStatusType(detail.equipment.currentStatusCode)">{{ equipmentStatusLabel(detail.equipment.currentStatusCode) }}</el-tag>
        </div>
        <el-tabs>
          <el-tab-pane label="档案">
            <el-descriptions :column="2" border>
              <el-descriptions-item label="分类">{{ detail.equipment.categoryName }}</el-descriptions-item>
              <el-descriptions-item label="生命周期">{{ lifecycleLabels[detail.equipment.lifecycleStage] }}</el-descriptions-item>
              <el-descriptions-item label="型号">{{ detail.equipment.model || '—' }}</el-descriptions-item>
              <el-descriptions-item label="资产编号">{{ detail.equipment.assetNumber || '—' }}</el-descriptions-item>
              <el-descriptions-item label="所属组织">{{ detail.equipment.organizationName }}</el-descriptions-item>
              <el-descriptions-item label="物理位置">{{ detail.equipment.locationName }}</el-descriptions-item>
            </el-descriptions>
          </el-tab-pane>
          <el-tab-pane label="扩展属性">
            <el-descriptions :column="2" border>
              <el-descriptions-item v-for="item in detail.attributes" :key="item.definitionId" :label="item.attributeName">
                {{ item.value || '—' }} {{ item.unit || '' }}
              </el-descriptions-item>
            </el-descriptions>
            <el-empty v-if="!detail.attributes.length" description="暂无扩展属性" />
          </el-tab-pane>
          <el-tab-pane label="责任人">
            <el-table :data="detail.responsiblePersons">
              <el-table-column prop="realName" label="姓名" />
              <el-table-column prop="username" label="账号" />
              <el-table-column label="责任类型">
                <template #default="{ row }">{{ responsibilityLabels[row.responsibilityType as keyof typeof responsibilityLabels] }}</template>
              </el-table-column>
              <el-table-column prop="startDate" label="开始日期" />
              <el-table-column prop="endDate" label="结束日期" />
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`状态履历 (${detail.statusHistory.length})`">
            <el-timeline>
              <el-timeline-item v-for="item in detail.statusHistory" :key="item.id" :timestamp="item.startedTime">
                {{ item.fromStatusCode ? equipmentStatusLabel(item.fromStatusCode) : '初始状态' }} →
                {{ equipmentStatusLabel(item.toStatusCode) }}
                <span v-if="item.reason"> · {{ item.reason }}</span>
              </el-timeline-item>
            </el-timeline>
          </el-tab-pane>
          <el-tab-pane :label="`调拨履历 (${detail.transfers.length})`">
            <el-table :data="detail.transfers">
              <el-table-column prop="transferredTime" label="时间" min-width="170" />
              <el-table-column prop="fromOrganizationName" label="原组织" />
              <el-table-column prop="toOrganizationName" label="新组织" />
              <el-table-column prop="fromLocationName" label="原位置" />
              <el-table-column prop="toLocationName" label="新位置" />
              <el-table-column prop="transferReason" label="原因" min-width="180" />
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`技术文档 (${detail.documents.length})`">
            <el-table :data="detail.documents">
              <el-table-column prop="originalName" label="文件名" />
              <el-table-column prop="relationType" label="文档类型" />
              <el-table-column prop="remark" label="说明" />
            </el-table>
            <el-empty v-if="!detail.documents.length" description="尚未关联技术文档" />
          </el-tab-pane>
          <el-tab-pane :label="`操作记录 (${detail.changeLogs.length})`">
            <el-table :data="detail.changeLogs">
              <el-table-column prop="changeTime" label="时间" min-width="180" />
              <el-table-column prop="operationType" label="操作" width="120">
                <template #default="{ row }">{{ changeOperationLabels[row.operationType] || '数据变更' }}</template>
              </el-table-column>
              <el-table-column prop="operatorName" label="操作人" width="120" />
              <el-table-column label="变更字段" min-width="220">
                <template #default="{ row }">
                  {{ changedFieldText(row.changedFields) }}
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!detail.changeLogs.length" description="暂无操作记录" />
          </el-tab-pane>
        </el-tabs>
      </div>
      <el-skeleton v-else :rows="8" animated />
    </el-drawer>

    <el-dialog v-model="transferVisible" title="设备调拨" width="min(620px, 94vw)">
      <el-form label-position="top">
        <el-form-item label="目标组织">
          <el-select v-model="transferForm.organizationId" filterable>
            <el-option v-for="item in activeOrganizations" :key="item.id" :label="item.organizationName" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标位置">
          <el-select
            v-model="transferForm.locationId"
            clearable
            filterable
            placeholder="可选；未指定物理位置"
          >
            <el-option v-for="item in transferLocations" :key="item.id" :label="item.locationName" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="新负责人">
          <el-select v-model="transferForm.primaryResponsibleUserId" clearable filterable>
            <el-option v-for="user in users" :key="user.id" :label="user.realName" :value="user.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="调拨原因">
          <el-input v-model="transferForm.reason" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="transfer">确认调拨</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="copyVisible" title="复制设备" width="min(520px, 94vw)">
      <el-form label-position="top">
        <el-form-item label="新设备编码">
          <el-input v-model="copyForm.equipmentCode" placeholder="留空自动生成" />
        </el-form-item>
        <el-form-item label="新设备名称">
          <el-input v-model="copyForm.equipmentName" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="copyVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="copyEquipment">复制</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importVisible" title="导入设备台账" width="min(700px, 94vw)">
      <el-alert
        title="请先下载模板；分类、组织、位置和负责人须使用系统中的编码或账号。"
        type="info"
        show-icon
        :closable="false"
      />
      <div class="import-actions">
        <el-button @click="equipmentApi.downloadTemplate()">下载模板</el-button>
        <el-upload
          :auto-upload="false"
          :limit="1"
          accept=".xlsx"
          :on-change="selectImportFile"
        >
          <el-button type="primary" plain>选择 Excel</el-button>
        </el-upload>
      </div>
      <el-result
        v-if="importResult"
        :icon="importResult.errors.length ? 'warning' : 'success'"
        :title="`成功 ${importResult.importedRows} / ${importResult.totalRows} 行`"
      >
        <template #extra>
          <el-table v-if="importResult.errors.length" :data="importResult.errors" max-height="240">
            <el-table-column prop="rowNumber" label="行号" width="80" />
            <el-table-column prop="message" label="错误原因" />
          </el-table>
        </template>
      </el-result>
      <template #footer>
        <el-button @click="importVisible = false">关闭</el-button>
        <el-button type="primary" :loading="saving" @click="runImport">开始导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.header-actions,
.import-actions,
.responsible-row {
  display: flex;
  gap: 12px;
  align-items: center;
}

.equipment-query {
  display: grid;
  grid-template-columns: minmax(220px, 1.5fr) repeat(3, minmax(150px, 1fr)) auto auto;
}

.equipment-form {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0 16px;
}

.equipment-form :deep(.el-divider),
.full-row {
  grid-column: 1 / -1;
}

.responsible-list {
  display: grid;
  gap: 10px;
  margin-bottom: 18px;
}

.responsible-row {
  display: grid;
  grid-template-columns: 1.2fr 1fr 1fr 1fr auto;
}

.detail-hero {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 18px;
  margin-bottom: 18px;
  border-radius: 14px;
  background: var(--el-fill-color-light);
}

.detail-hero h2 {
  margin: 6px 0 0;
}

.import-actions {
  margin: 20px 0;
}

@media (max-width: 900px) {
  .equipment-query,
  .equipment-form {
    grid-template-columns: 1fr 1fr;
  }

  .responsible-row {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 620px) {
  .equipment-query,
  .equipment-form,
  .responsible-row {
    grid-template-columns: 1fr;
  }

  .header-actions {
    flex-wrap: wrap;
  }
}
</style>
