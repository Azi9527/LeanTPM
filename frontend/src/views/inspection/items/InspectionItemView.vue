<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { inspectionApi, type ItemRow, type ResultType } from '@/api/inspection'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const rows = ref<ItemRow[]>([])
const total = ref(0)
const page = ref(1)
const keyword = ref('')
const resultType = ref<ResultType>()
const dialogVisible = ref(false)
const editing = ref<ItemRow | null>(null)

const form = reactive({
  itemCode: '',
  itemName: '',
  itemCategory: 'OPERATION',
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
  numericRequired: false,
  skipAllowed: false,
  abnormalSeverity: 'MEDIUM',
  abnormalAdvice: '',
  standardMinutes: 5,
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

onMounted(load)

async function load() {
  loading.value = true
  try {
    const result = await inspectionApi.items({
      keyword: keyword.value || undefined,
      resultType: resultType.value,
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

function open(row?: ItemRow) {
  editing.value = row || null
  Object.assign(form, row
    ? {
        itemCode: row.itemCode,
        itemName: row.itemName,
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
        photoRequired: row.photoRequiredFlag,
        numericRequired: row.numericRequiredFlag,
        skipAllowed: row.skipAllowedFlag,
        abnormalSeverity: row.abnormalSeverity,
        abnormalAdvice: row.abnormalAdvice || '',
        standardMinutes: row.standardMinutes,
        safetyNotes: row.safetyNotes || '',
        enabled: row.status === 1,
        description: row.description || '',
      }
    : {
        itemCode: '',
        itemName: '',
        itemCategory: 'OPERATION',
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
        numericRequired: false,
        skipAllowed: false,
        abnormalSeverity: 'MEDIUM',
        abnormalAdvice: '',
        standardMinutes: 5,
        safetyNotes: '',
        enabled: true,
        description: '',
      })
  dialogVisible.value = true
}

async function save() {
  if (!form.itemCode || !form.itemName || !form.inspectionContent || !form.inspectionStandard) {
    ElMessage.warning('请完整填写编码、名称、点检内容和标准')
    return
  }
  saving.value = true
  try {
    const payload = {
      ...form,
      resultOptions: form.resultOptionsText.split('\n').map((v) => v.trim()).filter(Boolean),
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
      <el-button v-if="auth.can('inspection:item:manage')" type="primary" @click="open()">新增项目</el-button>
    </header>

    <section class="surface-card query-bar">
      <el-input v-model="keyword" clearable placeholder="编码、名称或点检内容" @keyup.enter="page = 1; load()" />
      <el-select v-model="resultType" clearable placeholder="结果类型">
        <el-option v-for="(label, value) in resultLabels" :key="value" :label="label" :value="value" />
      </el-select>
      <el-button type="primary" @click="page = 1; load()">查询</el-button>
    </section>

    <section class="surface-card table-card" v-loading="loading">
      <div class="table-toolbar"><span class="table-title">标准项目库</span><span>共 {{ total }} 项</span></div>
      <el-table :data="rows" row-key="id">
        <el-table-column prop="itemCode" label="项目编码" min-width="170"><template #default="{ row }"><span class="mono">{{ row.itemCode }}</span></template></el-table-column>
        <el-table-column prop="itemName" label="项目名称" min-width="150" />
        <el-table-column prop="inspectionPart" label="部位" min-width="120" />
        <el-table-column prop="inspectionStandard" label="点检标准" min-width="240" show-overflow-tooltip />
        <el-table-column label="结果类型" width="130"><template #default="{ row }">{{ resultLabels[row.resultType as ResultType] }}</template></el-table-column>
        <el-table-column label="标准范围" min-width="130"><template #default="{ row }">{{ row.minimumValue ?? '—' }} ~ {{ row.maximumValue ?? '—' }} {{ row.unit }}</template></el-table-column>
        <el-table-column label="规则" min-width="170">
          <template #default="{ row }">
            <el-tag v-if="row.requiredFlag" size="small">必填</el-tag>
            <el-tag v-if="row.photoRequiredFlag" size="small" type="warning">拍照</el-tag>
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="auth.can('inspection:item:manage')" label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="open(row)">编辑</el-button>
            <el-button v-if="auth.can('inspection:item:delete')" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @change="load" />
    </section>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑点检项目' : '新增点检项目'" width="min(900px, 96vw)">
      <el-form label-position="top" class="form-grid">
        <el-form-item label="项目编码"><el-input v-model="form.itemCode" :disabled="Boolean(editing)" placeholder="例如 CNC-LUBRICATION" /></el-form-item>
        <el-form-item label="项目名称"><el-input v-model="form.itemName" /></el-form-item>
        <el-form-item label="项目分类"><el-input v-model="form.itemCategory" /></el-form-item>
        <el-form-item label="点检部位"><el-input v-model="form.inspectionPart" /></el-form-item>
        <el-form-item label="点检内容" class="full"><el-input v-model="form.inspectionContent" type="textarea" /></el-form-item>
        <el-form-item label="点检方法"><el-input v-model="form.inspectionMethod" /></el-form-item>
        <el-form-item label="工具"><el-input v-model="form.inspectionTool" /></el-form-item>
        <el-form-item label="点检标准" class="full"><el-input v-model="form.inspectionStandard" type="textarea" /></el-form-item>
        <el-form-item label="结果类型">
          <el-select v-model="form.resultType"><el-option v-for="(label, value) in resultLabels" :key="value" :label="label" :value="value" /></el-select>
        </el-form-item>
        <el-form-item label="标准值"><el-input v-model="form.standardValue" /></el-form-item>
        <el-form-item label="数值下限"><el-input-number v-model="form.minimumValue" controls-position="right" /></el-form-item>
        <el-form-item label="数值上限"><el-input-number v-model="form.maximumValue" controls-position="right" /></el-form-item>
        <el-form-item label="单位"><el-input v-model="form.unit" /></el-form-item>
        <el-form-item label="标准分钟"><el-input-number v-model="form.standardMinutes" :min="0" /></el-form-item>
        <el-form-item v-if="['SINGLE_CHOICE','MULTIPLE_CHOICE'].includes(form.resultType)" label="选项（每行一个）" class="full"><el-input v-model="form.resultOptionsText" type="textarea" :rows="4" /></el-form-item>
        <el-form-item label="异常等级"><el-select v-model="form.abnormalSeverity"><el-option label="低" value="LOW" /><el-option label="中" value="MEDIUM" /><el-option label="高" value="HIGH" /><el-option label="紧急" value="CRITICAL" /></el-select></el-form-item>
        <el-form-item label="异常建议"><el-input v-model="form.abnormalAdvice" /></el-form-item>
        <el-form-item label="安全说明" class="full"><el-input v-model="form.safetyNotes" type="textarea" /></el-form-item>
        <el-form-item label="执行规则" class="full">
          <el-checkbox v-model="form.required">必填</el-checkbox>
          <el-checkbox v-model="form.photoRequired">必须拍照</el-checkbox>
          <el-checkbox v-model="form.numericRequired">必须数值</el-checkbox>
          <el-checkbox v-model="form.skipAllowed">允许跳过</el-checkbox>
          <el-checkbox v-model="form.enabled">启用</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.form-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 0 16px; }
.full { grid-column: 1 / -1; }
@media (max-width: 640px) { .form-grid { grid-template-columns: 1fr; } .full { grid-column: auto; } }
</style>
