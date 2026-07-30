<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { oeeApi, type CalculationLogRow, type ImportResult, type OeeRecordRow, type ShiftRow } from '@/api/oee'
import { equipmentApi, type EquipmentRow } from '@/api/equipment'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const records = ref<OeeRecordRow[]>([])
const equipment = ref<EquipmentRow[]>([])
const shifts = ref<ShiftRow[]>([])
const total = ref(0)
const dialog = ref(false)
const logsDialog = ref(false)
const importDialog = ref(false)
const editingId = ref<number>()
const logs = ref<CalculationLogRow[]>([])
const importResult = ref<ImportResult>()
const query = reactive({
  equipmentId: undefined as number | undefined,
  dataStatus: '',
  startDate: '',
  endDate: '',
  page: 1,
  pageSize: 20,
})
const form = reactive({
  equipmentId: undefined as number | undefined,
  productionDate: new Date().toISOString().slice(0, 10),
  shiftId: undefined as number | undefined,
  standardCycleSeconds: 60,
  plannedWorkMinutes: 480,
  plannedDowntimeMinutes: 0,
  plannedQuantity: 0,
  actualQuantity: 0,
  goodQuantity: 0,
  defectiveQuantity: 0,
  sourceType: 'MANUAL',
  version: undefined as number | undefined,
})
const statuses: Record<string, { label: string; type: '' | 'warning' | 'success' | 'info' }> = {
  DRAFT: { label: '草稿', type: '' },
  SUBMITTED: { label: '待审核', type: 'warning' },
  APPROVED: { label: '已审核', type: 'success' },
  LOCKED: { label: '已锁定', type: 'info' },
}

onMounted(async () => {
  await Promise.all([load(), loadReferences()])
})

async function loadReferences() {
  try {
    const [equipmentPage, shiftPage] = await Promise.all([
      equipmentApi.page({ status: 1, page: 1, pageSize: 200 }),
      oeeApi.shifts({ status: 1, page: 1, pageSize: 200 }),
    ])
    equipment.value = equipmentPage.records.filter((item) => item.oeeEnabled)
    shifts.value = shiftPage.records
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function load() {
  loading.value = true
  try {
    const result = await oeeApi.records({
      ...query,
      dataStatus: query.dataStatus || undefined,
      startDate: query.startDate || undefined,
      endDate: query.endDate || undefined,
    })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function open(row?: OeeRecordRow) {
  editingId.value = row?.id
  Object.assign(form, row
    ? {
        equipmentId: row.equipmentId,
        productionDate: row.productionDate,
        shiftId: row.shiftId,
        standardCycleSeconds: row.standardCycleSeconds,
        plannedWorkMinutes: row.plannedWorkMinutes,
        plannedDowntimeMinutes: row.plannedDowntimeMinutes,
        plannedQuantity: row.plannedQuantity,
        actualQuantity: row.actualQuantity,
        goodQuantity: row.goodQuantity,
        defectiveQuantity: row.defectiveQuantity,
        sourceType: row.sourceType,
        version: row.version,
      }
    : {
        equipmentId: undefined,
        productionDate: new Date().toISOString().slice(0, 10),
        shiftId: shifts.value[0]?.id,
        standardCycleSeconds: 60,
        plannedWorkMinutes: shifts.value[0]?.standardWorkMinutes ?? 480,
        plannedDowntimeMinutes: 0,
        plannedQuantity: 0,
        actualQuantity: 0,
        goodQuantity: 0,
        defectiveQuantity: 0,
        sourceType: 'MANUAL',
        version: undefined,
      })
  dialog.value = true
}

function applyShift() {
  const shift = shifts.value.find((item) => item.id === form.shiftId)
  if (shift) form.plannedWorkMinutes = shift.standardWorkMinutes
}

async function save() {
  if (!form.equipmentId || !form.productionDate || !form.shiftId) {
    ElMessage.warning('请选择设备、日期和班次')
    return
  }
  if (form.goodQuantity + form.defectiveQuantity > form.actualQuantity) {
    ElMessage.warning('良品与不良品之和不能大于实际产量')
    return
  }
  if (form.plannedDowntimeMinutes > form.plannedWorkMinutes) {
    ElMessage.warning('计划停机不能大于计划工作时间')
    return
  }
  try {
    if (editingId.value) await oeeApi.updateRecord(editingId.value, form)
    else await oeeApi.createRecord(form)
    ElMessage.success('OEE数据已保存并由后端完成计算')
    dialog.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function recalculate(row: OeeRecordRow) {
  try {
    await oeeApi.recalculate(row.id)
    ElMessage.success('重新计算完成，日志已保留')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function workflow(row: OeeRecordRow, action: 'SUBMIT' | 'APPROVE' | 'LOCK' | 'UNLOCK') {
  const label = { SUBMIT: '提交审核', APPROVE: '审核通过', LOCK: '锁定数据', UNLOCK: '解锁数据' }[action]
  const { value } = await ElMessageBox.prompt(`确认${label}？`, label, {
    inputPlaceholder: '备注（可选）',
    confirmButtonText: '确认',
    cancelButtonText: '取消',
  })
  try {
    await oeeApi.workflow(row.id, { action, version: row.version, comment: value })
    ElMessage.success(`${label}成功`)
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function showLogs(row: OeeRecordRow) {
  try {
    logs.value = await oeeApi.calculationLogs(row.id)
    logsDialog.value = true
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function downloadTemplate() {
  try {
    const response = await oeeApi.downloadTemplate()
    const url = URL.createObjectURL(response.data)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = 'LeanTPM-OEE-import-template.xlsx'
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function importFile(file: File) {
  loading.value = true
  try {
    importResult.value = await oeeApi.importRecords(file)
    importDialog.value = true
    ElMessage.success(`导入完成：成功 ${importResult.value.successRows} 行`)
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
  return false
}

function percent(value?: number) {
  return value == null ? '—' : `${(Number(value) * 100).toFixed(2)}%`
}

function prettyJson(value: string) {
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div><h1>OEE 数据维护</h1><p>按设备、日期和班次维护唯一记录。所有指标由后端 OEE_V1 公式统一计算，浏览器仅展示结果。</p></div>
      <div class="header-actions">
        <el-button v-if="auth.can('oee:record:import')" plain @click="downloadTemplate">下载模板</el-button>
        <el-upload v-if="auth.can('oee:record:import')" :show-file-list="false" accept=".xlsx" :before-upload="importFile"><el-button plain>Excel导入</el-button></el-upload>
        <el-button v-if="auth.can('oee:record:manage')" type="primary" @click="open()">新增OEE</el-button>
      </div>
    </header>
    <section class="surface-card">
      <div class="toolbar">
        <el-select v-model="query.equipmentId" clearable filterable placeholder="全部设备" style="width: 240px"><el-option v-for="item in equipment" :key="item.id" :label="`${item.equipmentCode} · ${item.equipmentName}`" :value="item.id" /></el-select>
        <el-select v-model="query.dataStatus" clearable placeholder="全部状态" style="width: 130px"><el-option v-for="(item, value) in statuses" :key="value" :label="item.label" :value="value" /></el-select>
        <el-date-picker v-model="query.startDate" type="date" value-format="YYYY-MM-DD" placeholder="开始日期" />
        <el-date-picker v-model="query.endDate" type="date" value-format="YYYY-MM-DD" placeholder="结束日期" />
        <el-button type="primary" @click="query.page = 1; load()">查询</el-button>
      </div>
      <el-table v-loading="loading" :data="records" stripe>
        <el-table-column prop="productionDate" label="日期" width="115" />
        <el-table-column prop="equipmentCode" label="设备编码" width="145" />
        <el-table-column prop="equipmentName" label="设备名称" min-width="140" />
        <el-table-column prop="shiftName" label="班次" width="85" />
        <el-table-column label="时间开动率" width="115"><template #default="{ row }">{{ percent(row.availabilityRate) }}</template></el-table-column>
        <el-table-column label="性能开动率" width="115"><template #default="{ row }">{{ percent(row.performanceRate) }}</template></el-table-column>
        <el-table-column label="良品率" width="95"><template #default="{ row }">{{ percent(row.qualityRate) }}</template></el-table-column>
        <el-table-column label="OEE" width="100"><template #default="{ row }"><strong :class="{ danger: row.targetOeeRate && row.oeeRate < row.targetOeeRate }">{{ percent(row.oeeRate) }}</strong></template></el-table-column>
        <el-table-column label="目标" width="95"><template #default="{ row }">{{ percent(row.targetOeeRate) }}</template></el-table-column>
        <el-table-column label="校验" min-width="150"><template #default="{ row }"><el-tooltip v-if="row.anomalyFlag" :content="row.anomalyMessage"><el-tag type="danger">数据异常</el-tag></el-tooltip><el-tag v-else type="success">正常</el-tag></template></el-table-column>
        <el-table-column label="状态" width="95"><template #default="{ row }"><el-tag :type="statuses[row.dataStatus]?.type">{{ statuses[row.dataStatus]?.label }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button v-if="auth.can('oee:record:manage') && row.dataStatus !== 'LOCKED' && row.dataStatus !== 'SUBMITTED'" link type="primary" @click="open(row)">编辑</el-button>
            <el-button v-if="auth.can('oee:record:recalculate') && row.dataStatus !== 'LOCKED'" link @click="recalculate(row)">重算</el-button>
            <el-button v-if="row.dataStatus === 'DRAFT' && (auth.can('oee:record:manage') || auth.can('oee:record:approve'))" link type="warning" @click="workflow(row, 'SUBMIT')">提交</el-button>
            <el-button v-if="row.dataStatus === 'SUBMITTED' && auth.can('oee:record:approve')" link type="success" @click="workflow(row, 'APPROVE')">审核</el-button>
            <el-button v-if="row.dataStatus === 'APPROVED' && auth.can('oee:record:lock')" link @click="workflow(row, 'LOCK')">锁定</el-button>
            <el-button v-if="row.dataStatus === 'LOCKED' && auth.can('oee:record:lock')" link @click="workflow(row, 'UNLOCK')">解锁</el-button>
            <el-button link @click="showLogs(row)">日志</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无OEE记录" /></template>
      </el-table>
      <el-pagination v-model:current-page="query.page" v-model:page-size="query.pageSize" :total="total" layout="total, prev, pager, next" @current-change="load" />
    </section>

    <el-dialog v-model="dialog" :title="editingId ? '编辑OEE数据' : '新增OEE数据'" width="760px">
      <el-alert type="info" :closable="false" title="前端只提交原始时间、节拍和产量；可动率、性能率、良品率和 OEE 均由后端计算。" />
      <el-form label-width="125px">
        <el-form-item label="设备"><el-select v-model="form.equipmentId" filterable style="width: 100%"><el-option v-for="item in equipment" :key="item.id" :label="`${item.equipmentCode} · ${item.equipmentName}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="日期/班次"><el-date-picker v-model="form.productionDate" type="date" value-format="YYYY-MM-DD" /><el-select v-model="form.shiftId" @change="applyShift"><el-option v-for="item in shifts" :key="item.id" :label="item.shiftName" :value="item.id" /></el-select></el-form-item>
        <div class="form-grid">
          <el-form-item label="标准节拍(秒)"><el-input-number v-model="form.standardCycleSeconds" :min="0.000001" :precision="6" /></el-form-item>
          <el-form-item label="计划工作(分)"><el-input-number v-model="form.plannedWorkMinutes" :min="0" :precision="3" /></el-form-item>
          <el-form-item label="计划停机(分)"><el-input-number v-model="form.plannedDowntimeMinutes" :min="0" :max="form.plannedWorkMinutes" :precision="3" /></el-form-item>
          <el-form-item label="计划数量"><el-input-number v-model="form.plannedQuantity" :min="0" :precision="3" /></el-form-item>
          <el-form-item label="实际产量"><el-input-number v-model="form.actualQuantity" :min="0" :precision="3" /></el-form-item>
          <el-form-item label="良品数量"><el-input-number v-model="form.goodQuantity" :min="0" :precision="3" /></el-form-item>
          <el-form-item label="不良品数量"><el-input-number v-model="form.defectiveQuantity" :min="0" :precision="3" /></el-form-item>
          <el-form-item label="数据来源"><el-select v-model="form.sourceType"><el-option label="人工录入" value="MANUAL" /><el-option label="MES" value="MES" /><el-option label="IoT" value="IOT" /></el-select></el-form-item>
        </div>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" @click="save">保存并计算</el-button></template>
    </el-dialog>

    <el-dialog v-model="logsDialog" title="OEE计算日志" width="900px">
      <el-timeline>
        <el-timeline-item v-for="item in logs" :key="item.id" :timestamp="item.calculatedTime" placement="top">
          <el-card><h4>V{{ item.calculationVersion }} · {{ item.triggerType }} · {{ item.formulaVersion }}</h4><el-alert v-if="item.validationMessage" type="warning" :closable="false" :title="item.validationMessage" /><el-collapse><el-collapse-item title="输入快照"><pre>{{ prettyJson(item.inputSnapshot) }}</pre></el-collapse-item><el-collapse-item title="输出快照"><pre>{{ prettyJson(item.outputSnapshot) }}</pre></el-collapse-item></el-collapse><small>计算人：{{ item.calculatedByName || '系统' }}</small></el-card>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-if="!logs.length" description="暂无计算日志" />
    </el-dialog>

    <el-dialog v-model="importDialog" title="Excel导入结果" width="680px">
      <el-descriptions v-if="importResult" :column="3" border><el-descriptions-item label="总行数">{{ importResult.totalRows }}</el-descriptions-item><el-descriptions-item label="成功">{{ importResult.successRows }}</el-descriptions-item><el-descriptions-item label="失败">{{ importResult.failureRows }}</el-descriptions-item></el-descriptions>
      <el-alert v-for="item in importResult?.errors" :key="item" type="error" :closable="false" :title="item" />
    </el-dialog>
  </div>
</template>

<style scoped>
.header-actions, .toolbar { display: flex; flex-wrap: wrap; gap: 10px; }
.toolbar { margin-bottom: 16px; }
.el-pagination { justify-content: flex-end; margin-top: 16px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; }
.el-alert { margin-bottom: 18px; }
.danger { color: var(--el-color-danger); }
pre { white-space: pre-wrap; word-break: break-all; max-height: 300px; overflow: auto; }
.el-form-item :deep(.el-date-editor), .el-form-item :deep(.el-select) { margin-right: 10px; }
@media (max-width: 680px) { .form-grid { grid-template-columns: 1fr; } }
</style>
