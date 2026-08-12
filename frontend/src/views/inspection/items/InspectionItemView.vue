<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { inspectionApi, type ItemCategoryOption, type ItemRow, type ResultType } from '@/api/inspection'
import { masterDataApi, type OrganizationRow } from '@/api/masterData'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'
import {
  afterPhotoMinimumChange,
  afterPhotoRequiredChange,
} from '@/utils/inspection-item-photo-rule'
import InspectionImportDialog from '@/components/inspection/InspectionImportDialog.vue'
import { applySmartTableQuery, type SmartTableServerQuery } from '@/components/table/smart-table-context'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const rows = ref<ItemRow[]>([])
const total = ref(0)
const page = ref(1)
const keyword = ref('')
const resultType = ref<ResultType>()
const organizationId = ref<number>()
const organizations = ref<OrganizationRow[]>([])
const fallbackCategories: ItemCategoryOption[] = [
  { value: 'TRANSMISSION', label: '传动系统', defaultFlag: true },
  { value: 'LUBRICATION', label: '润滑系统', defaultFlag: false },
  { value: 'FASTENING', label: '紧固系统', defaultFlag: false },
  { value: 'ELECTRICAL', label: '电气系统', defaultFlag: false },
  { value: 'SAFETY', label: '安全防护', defaultFlag: false },
  { value: 'OTHER', label: '其它', defaultFlag: false },
]
const itemCategories = ref<ItemCategoryOption[]>(fallbackCategories)
const dialogVisible = ref(false)
const activeEditorSection = ref('basic')
const importVisible = ref(false)
const smartTableQuery = reactive({
  tableFilters: undefined as string | undefined,
  sortBy: undefined as string | undefined,
  sortDirection: undefined as 'asc' | 'desc' | undefined,
})
const editing = ref<ItemRow | null>(null)

const form = reactive({
  itemCode: '',
  itemName: '',
  organizationId: undefined as number | undefined,
  itemCategory: 'TRANSMISSION',
  inspectionPart: '',
  inspectionContent: '',
  inspectionMethod: '',
  inspectionTool: '',
  inspectionStandard: '',
  standardValue: '',
  minimumValue: undefined as number | undefined,
  maximumValue: undefined as number | undefined,
  unit: '',
  resultType: 'NORMAL_ABNORMAL' as ResultType,
  resultOptionsText: '',
  required: true,
  photoRequired: false,
  photoMinCount: 0,
  photoMaxCount: 2,
  photoMaxSizeMb: 5,
  photoAllowedTypes: 'image/jpeg,image/png',
  photoCompressionQuality: 82,
  numericRequired: false,
  skipAllowed: false,
  abnormalSeverity: 'MEDIUM',
  abnormalAdvice: '',
  abnormalDefaultStop: true,
  standardMinutes: 2,
  safetyNotes: '',
  enabled: true,
  description: '',
})

const resultLabels: Record<ResultType, string> = {
  NORMAL_ABNORMAL: '正常/异常',
  PASS_FAIL: '合格/不合格',
  NUMBER: '数值',
  TEXT: '文本',
  SINGLE_CHOICE: '单选',
  MULTIPLE_CHOICE: '多选',
  IMAGE: '图片',
  ATTACHMENT: '附件',
}

const qualitativeOptions = computed(() => {
  if (form.resultType === 'NORMAL_ABNORMAL') {
    return [{ label: '正常', value: 'NORMAL' }, { label: '异常', value: 'ABNORMAL' }]
  }
  if (form.resultType === 'PASS_FAIL') {
    return [{ label: '合格', value: 'PASS' }, { label: '不合格', value: 'FAIL' }]
  }
  return []
})
const isQualitativeResult = computed(() => qualitativeOptions.value.length > 0)
const isNumericResult = computed(() => form.resultType === 'NUMBER')
const categoryOptions = computed(() => {
  if (!form.itemCategory || itemCategories.value.some((row) => row.value === form.itemCategory)) {
    return itemCategories.value
  }
  return [
    ...itemCategories.value,
    { value: form.itemCategory, label: `历史分类（${form.itemCategory}）`, defaultFlag: false },
  ]
})

watch(() => form.resultType, (value) => {
  if (value === 'NUMBER') {
    form.numericRequired = true
    form.standardValue = ''
    form.resultOptionsText = ''
    return
  }
  form.numericRequired = false
  form.minimumValue = undefined
  form.maximumValue = undefined
  form.unit = ''
  if (value === 'NORMAL_ABNORMAL') {
    form.standardValue = ['NORMAL', 'ABNORMAL'].includes(form.standardValue)
      ? form.standardValue : 'NORMAL'
    form.resultOptionsText = '正常\n异常'
  } else if (value === 'PASS_FAIL') {
    form.standardValue = ['PASS', 'FAIL'].includes(form.standardValue)
      ? form.standardValue : 'PASS'
    form.resultOptionsText = '合格\n不合格'
  }
})

watch(() => form.numericRequired, (required) => {
  if (required && form.resultType !== 'NUMBER') form.resultType = 'NUMBER'
})

onMounted(async () => {
  const [organizationResult, categoryResult] = await Promise.allSettled([
    masterDataApi.organizations(),
    inspectionApi.itemCategories(),
  ])
  if (organizationResult.status === 'fulfilled') {
    organizations.value = organizationResult.value.filter((row) => row.status === 1)
  } else ElMessage.error(errorMessage(organizationResult.reason, '部门数据加载失败'))
  if (categoryResult.status === 'fulfilled' && categoryResult.value.length) {
    itemCategories.value = categoryResult.value
  }
  await load()
})

async function load() {
  loading.value = true
  try {
    const result = await inspectionApi.items({
      keyword: keyword.value || undefined,
      organizationId: organizationId.value,
      resultType: resultType.value,
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

function open(row?: ItemRow) {
  activeEditorSection.value = 'basic'
  editing.value = row || null
  const photoRule = row
    ? afterPhotoMinimumChange(Boolean(row.photoRequiredFlag), row.photoMinCount ?? 0)
    : afterPhotoRequiredChange(false, 0)
  Object.assign(form, row
    ? {
        itemCode: row.itemCode,
        itemName: row.itemName,
        organizationId: row.organizationId ?? 0,
        itemCategory: row.itemCategory,
        inspectionPart: row.inspectionPart || '',
        inspectionContent: row.inspectionContent,
        inspectionMethod: row.inspectionMethod || '',
        inspectionTool: row.inspectionTool || '',
        inspectionStandard: row.inspectionStandard,
        standardValue: row.standardValue || '',
        minimumValue: row.minimumValue,
        maximumValue: row.maximumValue,
        unit: row.unit || '',
        resultType: row.resultType,
        resultOptionsText: parseOptions(row.resultOptionsJson).join('\n'),
        required: row.requiredFlag,
        photoRequired: photoRule.photoRequired,
        photoMinCount: photoRule.photoMinCount,
        photoMaxCount: row.photoMaxCount ?? 2,
        photoMaxSizeMb: row.photoMaxSizeMb ?? 5,
        photoAllowedTypes: row.photoAllowedTypes || 'image/jpeg,image/png',
        photoCompressionQuality: row.photoCompressionQuality ?? 82,
        numericRequired: row.numericRequiredFlag ?? row.resultType === 'NUMBER',
        skipAllowed: row.skipAllowedFlag ?? false,
        abnormalSeverity: row.abnormalSeverity || 'MEDIUM',
        abnormalAdvice: row.abnormalAdvice || '',
        abnormalDefaultStop: row.abnormalDefaultStopFlag ?? true,
        standardMinutes: row.standardMinutes ?? 2,
        safetyNotes: row.safetyNotes || '',
        enabled: row.status === 1,
        description: row.description || '',
      }
    : {
        itemCode: '',
        itemName: '',
        organizationId: organizations.value[0]?.id,
        itemCategory: itemCategories.value.find((row) => row.defaultFlag)?.value
          || itemCategories.value[0]?.value
          || 'TRANSMISSION',
        inspectionPart: '',
        inspectionContent: '',
        inspectionMethod: '',
        inspectionTool: '',
        inspectionStandard: '',
        standardValue: '',
        minimumValue: undefined,
        maximumValue: undefined,
        unit: '',
        resultType: 'NORMAL_ABNORMAL',
        resultOptionsText: '',
        required: true,
        photoRequired: false,
        photoMinCount: 0,
        photoMaxCount: 2,
        photoMaxSizeMb: 5,
        photoAllowedTypes: 'image/jpeg,image/png',
        photoCompressionQuality: 82,
        numericRequired: false,
        skipAllowed: false,
        abnormalSeverity: 'MEDIUM',
        abnormalAdvice: '',
        abnormalDefaultStop: true,
        standardMinutes: 2,
        safetyNotes: '',
        enabled: true,
        description: '',
      })
  dialogVisible.value = true
}

function onPhotoRequiredChange(required: string | number | boolean) {
  Object.assign(form, afterPhotoRequiredChange(Boolean(required), form.photoMinCount))
}

function onPhotoMinimumChange(minimum: number | undefined) {
  Object.assign(form, afterPhotoMinimumChange(form.photoRequired, minimum ?? 0))
}

async function save() {
  const missing = [
    !form.itemCode && '项目编码',
    !form.itemName && '项目名称',
    !form.itemCategory && '项目分类',
    !form.inspectionContent && '点检内容',
    !form.inspectionStandard && '点检标准',
  ].filter(Boolean)
  if (missing.length) {
    ElMessage.warning(`请填写必填项：${missing.join('、')}`)
    return
  }
  if (form.photoMinCount > form.photoMaxCount) {
    ElMessage.warning('最少照片数不能大于最多照片数')
    return
  }
  if (form.resultType === 'NUMBER'
      && form.minimumValue == null && form.maximumValue == null) {
    ElMessage.warning('数值结果请至少填写数值下限或数值上限')
    return
  }
  if (form.resultType === 'NUMBER'
      && form.minimumValue != null && form.maximumValue != null
      && form.minimumValue > form.maximumValue) {
    ElMessage.warning('数值下限不能大于数值上限')
    return
  }
  if (['SINGLE_CHOICE', 'MULTIPLE_CHOICE'].includes(form.resultType)
      && !form.resultOptionsText.split('\n').some((value) => value.trim())) {
    ElMessage.warning('选择型结果请至少配置一个选项')
    return
  }
  saving.value = true
  try {
    const payload = {
      ...form,
      organizationId: form.organizationId || undefined,
      resultOptions: form.resultType === 'NORMAL_ABNORMAL'
        ? ['NORMAL', 'ABNORMAL']
        : form.resultType === 'PASS_FAIL'
          ? ['PASS', 'FAIL']
          : form.resultOptionsText.split('\n').map((v) => v.trim()).filter(Boolean),
      version: editing.value?.version,
    }
    if (editing.value) await inspectionApi.updateItem(editing.value.id, payload)
    else await inspectionApi.createItem(payload)
    dialogVisible.value = false
    ElMessage.success('点检项目已保存')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function remove(row: ItemRow) {
  await ElMessageBox.confirm(`确认删除“${row.itemName}”吗？`, '删除点检项目', { type: 'warning' })
  try {
    await inspectionApi.deleteItem(row.id, row.version)
    ElMessage.success('点检项目已删除')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

function parseOptions(value?: string): string[] {
  if (!value) return []
  try { return JSON.parse(value) as string[] } catch { return [] }
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div>
        <h1>点检项目</h1>
        <p>建立可复用的点检标准、结果类型、阈值、拍照和异常规则。</p>
      </div>
      <div class="page-actions">
        <el-button v-if="auth.can('inspection:import')" @click="importVisible = true">批量导入</el-button>
        <el-button v-if="auth.can('inspection:item:manage')" type="primary" @click="open()">新增项目</el-button>
      </div>
    </header>

    <section class="surface-card query-bar">
      <el-input v-model="keyword" clearable placeholder="编码、名称或点检内容" @keyup.enter="page = 1; load()" />
      <el-select v-model="organizationId" clearable filterable placeholder="所属部门">
        <el-option v-for="row in organizations" :key="row.id" :label="row.organizationName" :value="row.id" />
      </el-select>
      <el-select v-model="resultType" clearable placeholder="结果类型">
        <el-option v-for="(label, value) in resultLabels" :key="value" :label="label" :value="value" />
      </el-select>
      <el-button type="primary" @click="page = 1; load()">查询</el-button>
    </section>

    <section class="surface-card table-card" v-loading="loading">
      <div class="table-toolbar"><span class="table-title">标准项目库</span><span>共 {{ total }} 项</span></div>
      <el-table :data="rows" row-key="id" server-query @smart-query-change="applyTableQuery">
        <el-table-column prop="itemCode" label="项目编码" min-width="170"><template #default="{ row }"><span class="mono">{{ row.itemCode }}</span></template></el-table-column>
        <el-table-column prop="itemName" label="项目名称" min-width="150" />
        <el-table-column prop="organizationName" label="所属部门" min-width="150"><template #default="{ row }">{{ row.organizationName || '共享标准' }}</template></el-table-column>
        <el-table-column prop="inspectionPart" label="部位" min-width="120" />
        <el-table-column prop="inspectionStandard" label="点检标准" min-width="240" show-overflow-tooltip />
        <el-table-column prop="resultType" label="结果类型" smart-filter="select" width="130"><template #default="{ row }">{{ resultLabels[row.resultType as ResultType] }}</template></el-table-column>
        <el-table-column prop="minimumValue" label="标准范围" smart-filter="number" min-width="150">
          <template #default="{ row }">
            <span v-if="row.resultType === 'NUMBER'">{{ row.minimumValue ?? '—' }} ～ {{ row.maximumValue ?? '—' }} {{ row.unit }}</span>
            <span v-else>{{ row.standardValue || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="ruleSummary" label="规则" min-width="170">
          <template #default="{ row }">
            <el-tag v-if="row.requiredFlag" size="small">必填</el-tag>
            <el-tag v-if="row.photoRequiredFlag" size="small" type="warning">拍照</el-tag>
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="auth.can('inspection:item:manage')" label="操作" smart-filter="none" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="open(row)">编辑</el-button>
            <el-button v-if="auth.can('inspection:item:delete')" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @change="load" />
    </section>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑点检项目' : '新增点检项目'" width="min(1040px, 96vw)" class="inspection-editor-dialog" align-center>
      <el-form label-position="top" class="inspection-editor-form">
        <el-tabs v-model="activeEditorSection" class="inspection-editor-tabs">
          <el-tab-pane label="基础标准" name="basic">
            <div class="form-grid inspection-editor-pane">
        <el-form-item label="项目编码" required><el-input v-model="form.itemCode" :disabled="Boolean(editing)" placeholder="例如 CNC-LUBRICATION" /></el-form-item>
        <el-form-item label="项目名称" required><el-input v-model="form.itemName" /></el-form-item>
        <el-form-item label="适用范围" required>
          <el-select v-model="form.organizationId" filterable style="width: 100%">
            <el-option label="共享标准（所有组织可用）" :value="0" />
            <el-option v-for="row in organizations" :key="row.id" :label="row.organizationName" :value="row.id" />
          </el-select>
          <div class="field-hint">共享标准可被所有组织的点检方案复用。</div>
        </el-form-item>
        <el-form-item label="项目分类" required>
          <el-select v-model="form.itemCategory" filterable style="width: 100%">
            <el-option v-for="option in categoryOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="点检部位"><el-input v-model="form.inspectionPart" /></el-form-item>
        <el-form-item label="点检内容" class="full" required><el-input v-model="form.inspectionContent" type="textarea" /></el-form-item>
        <el-form-item label="点检方法"><el-input v-model="form.inspectionMethod" /></el-form-item>
        <el-form-item label="工具"><el-input v-model="form.inspectionTool" /></el-form-item>
        <el-form-item label="点检标准" class="full" required><el-input v-model="form.inspectionStandard" type="textarea" /></el-form-item>
            </div>
          </el-tab-pane>
          <el-tab-pane label="判定与异常" name="result">
            <div class="form-grid inspection-editor-pane">
        <el-form-item label="结果类型" required>
          <el-select v-model="form.resultType"><el-option v-for="(label, value) in resultLabels" :key="value" :label="label" :value="value" /></el-select>
        </el-form-item>
        <el-form-item v-if="isQualitativeResult" label="标准结果" required>
          <el-select v-model="form.standardValue">
            <el-option v-for="option in qualitativeOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <div class="field-hint">执行时只能选择这两个业务结果，所选标准结果判定为正常。</div>
        </el-form-item>
        <el-form-item v-else-if="!isNumericResult" label="标准值"><el-input v-model="form.standardValue" /></el-form-item>
        <el-form-item v-if="isNumericResult" label="数值下限"><el-input-number v-model="form.minimumValue" controls-position="right" /><div class="field-hint">数值上下限至少填写一项。</div></el-form-item>
        <el-form-item v-if="isNumericResult" label="数值上限"><el-input-number v-model="form.maximumValue" controls-position="right" /><div class="field-hint">数值上下限至少填写一项。</div></el-form-item>
        <el-form-item v-if="isNumericResult" label="单位"><el-input v-model="form.unit" /></el-form-item>
        <el-form-item label="标准分钟" required><el-input-number v-model="form.standardMinutes" :min="0" /></el-form-item>
        <el-form-item v-if="['SINGLE_CHOICE','MULTIPLE_CHOICE'].includes(form.resultType)" label="选项（每行一个）" class="full"><el-input v-model="form.resultOptionsText" type="textarea" :rows="4" /></el-form-item>
        <el-form-item label="异常等级" required><el-select v-model="form.abnormalSeverity"><el-option label="低" value="LOW" /><el-option label="中" value="MEDIUM" /><el-option label="高" value="HIGH" /><el-option label="紧急" value="CRITICAL" /></el-select></el-form-item>
        <el-form-item label="异常建议"><el-input v-model="form.abnormalAdvice" /></el-form-item>
        <el-form-item label="异常默认停机"><el-switch v-model="form.abnormalDefaultStop" /><small class="block">任务执行时可调整，调整后必须填写原因</small></el-form-item>
            </div>
          </el-tab-pane>
          <el-tab-pane label="拍照与执行" name="execution">
            <div class="form-grid inspection-editor-pane">
        <el-form-item label="最少照片数" required><el-input-number v-model="form.photoMinCount" :min="0" :max="20" @change="onPhotoMinimumChange" /><div class="field-hint">大于 0 即表示必须拍照；设为 0 会自动关闭“必须拍照”。</div></el-form-item>
        <el-form-item label="最多照片数" required><el-input-number v-model="form.photoMaxCount" :min="1" :max="20" /></el-form-item>
        <el-form-item label="单张上限（MB）" required><el-input-number v-model="form.photoMaxSizeMb" :min="1" :max="100" /></el-form-item>
        <el-form-item label="压缩质量" required><el-slider v-model="form.photoCompressionQuality" :min="40" :max="95" show-input /></el-form-item>
        <el-form-item label="允许图片类型" class="full" required><el-input v-model="form.photoAllowedTypes" placeholder="image/jpeg,image/png" /></el-form-item>
        <el-form-item label="安全说明" class="full"><el-input v-model="form.safetyNotes" type="textarea" /></el-form-item>
        <el-form-item label="执行规则" class="full" required>
          <el-checkbox v-model="form.required">必填</el-checkbox>
          <el-checkbox v-model="form.photoRequired" @change="onPhotoRequiredChange">必须拍照</el-checkbox>
          <el-checkbox v-model="form.numericRequired">必须数值</el-checkbox>
          <span class="field-hint">勾选“必须拍照”时最少照片数自动调整为 1；最少照片数设为 0 时自动关闭。勾选“必须数值”会自动切换为数值结果；正常/异常、合格/不合格不使用上下限。</span>
          <el-checkbox v-model="form.skipAllowed">允许跳过</el-checkbox>
          <el-checkbox v-model="form.enabled">启用</el-checkbox>
        </el-form-item>
            </div>
          </el-tab-pane>
        </el-tabs>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
    <InspectionImportDialog v-model="importVisible" @committed="load" />
  </div>
</template>

<style scoped>
.form-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 0 16px; }
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
@media (max-width: 640px) { .form-grid { grid-template-columns: 1fr; } .full { grid-column: auto; } }
</style>
