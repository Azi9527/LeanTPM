<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { oeeApi, type DowntimeRow, type LossReasonRow, type OutputRow, type ShiftRow } from '@/api/oee'
import { equipmentApi, type EquipmentRow } from '@/api/equipment'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const tab = ref('output')
const loading = ref(false)
const outputs = ref<OutputRow[]>([])
const downtimes = ref<DowntimeRow[]>([])
const equipment = ref<EquipmentRow[]>([])
const shifts = ref<ShiftRow[]>([])
const reasons = ref<LossReasonRow[]>([])
const outputTotal = ref(0)
const downtimeTotal = ref(0)
const outputDialog = ref(false)
const downtimeDialog = ref(false)
const editingOutputId = ref<number>()
const editingDowntimeId = ref<number>()
const query = reactive({
  equipmentId: undefined as number | undefined,
  startDate: '',
  endDate: '',
  page: 1,
  pageSize: 100,
})
const outputForm = reactive({
  equipmentId: undefined as number | undefined,
  productionDate: new Date().toISOString().slice(0, 10),
  shiftId: undefined as number | undefined,
  plannedQuantity: 0,
  actualQuantity: 0,
  goodQuantity: 0,
  defectiveQuantity: 0,
  sourceType: 'MANUAL',
  sourceReference: '',
  remark: '',
  version: undefined as number | undefined,
})
const downtimeForm = reactive({
  equipmentId: undefined as number | undefined,
  productionDate: new Date().toISOString().slice(0, 10),
  shiftId: undefined as number | undefined,
  lossReasonId: undefined as number | undefined,
  startedTime: '',
  endedTime: '',
  durationMinutes: 1,
  plannedFlag: false,
  sourceType: 'MANUAL',
  sourceReference: '',
  description: '',
  version: undefined as number | undefined,
})

onMounted(async () => {
  await Promise.all([loadReferences(), loadOutputs(), loadDowntimes()])
})

async function loadReferences() {
  try {
    const [equipmentPage, shiftPage, reasonPage] = await Promise.all([
      equipmentApi.page({ status: 1, page: 1, pageSize: 200 }),
      oeeApi.shifts({ status: 1, page: 1, pageSize: 200 }),
      oeeApi.lossReasons({ status: 1, page: 1, pageSize: 200 }),
    ])
    equipment.value = equipmentPage.records.filter((item) => item.oeeEnabled)
    shifts.value = shiftPage.records
    reasons.value = reasonPage.records
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

function queryParams() {
  return {
    ...query,
    startDate: query.startDate || undefined,
    endDate: query.endDate || undefined,
  }
}

async function loadOutputs() {
  loading.value = true
  try {
    const result = await oeeApi.outputs(queryParams())
    outputs.value = result.records
    outputTotal.value = result.total
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function loadDowntimes() {
  loading.value = true
  try {
    const result = await oeeApi.downtimes(queryParams())
    downtimes.value = result.records
    downtimeTotal.value = result.total
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function openOutput(row?: OutputRow) {
  editingOutputId.value = row?.id
  Object.assign(outputForm, row
    ? { ...row }
    : {
        equipmentId: undefined,
        productionDate: new Date().toISOString().slice(0, 10),
        shiftId: shifts.value[0]?.id,
        plannedQuantity: 0,
        actualQuantity: 0,
        goodQuantity: 0,
        defectiveQuantity: 0,
        sourceType: 'MANUAL',
        sourceReference: '',
        remark: '',
        version: undefined,
      })
  outputDialog.value = true
}

function openDowntime(row?: DowntimeRow) {
  editingDowntimeId.value = row?.id
  Object.assign(downtimeForm, row
    ? { ...row, startedTime: row.startedTime ?? '', endedTime: row.endedTime ?? '' }
    : {
        equipmentId: undefined,
        productionDate: new Date().toISOString().slice(0, 10),
        shiftId: shifts.value[0]?.id,
        lossReasonId: reasons.value.find((item) => !item.plannedFlag)?.id,
        startedTime: '',
        endedTime: '',
        durationMinutes: 1,
        plannedFlag: false,
        sourceType: 'MANUAL',
        sourceReference: '',
        description: '',
        version: undefined,
      })
  syncReason()
  downtimeDialog.value = true
}

function syncReason() {
  const reason = reasons.value.find((item) => item.id === downtimeForm.lossReasonId)
  if (reason) downtimeForm.plannedFlag = reason.plannedFlag
}

function durationFromTime() {
  if (!downtimeForm.startedTime || !downtimeForm.endedTime) return
  const duration = (new Date(downtimeForm.endedTime).getTime() - new Date(downtimeForm.startedTime).getTime()) / 60000
  if (duration > 0) downtimeForm.durationMinutes = Number(duration.toFixed(3))
}

async function saveOutput() {
  if (!outputForm.equipmentId || !outputForm.productionDate || !outputForm.shiftId) {
    ElMessage.warning('请选择设备、日期和班次')
    return
  }
  if (outputForm.goodQuantity + outputForm.defectiveQuantity > outputForm.actualQuantity) {
    ElMessage.warning('良品数与不良品数之和不能大于实际产量')
    return
  }
  try {
    if (editingOutputId.value) await oeeApi.updateOutput(editingOutputId.value, outputForm)
    else await oeeApi.createOutput(outputForm)
    ElMessage.success('产量保存成功，关联OEE已自动重算')
    outputDialog.value = false
    await Promise.all([loadOutputs(), loadDowntimes()])
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function saveDowntime() {
  if (!downtimeForm.equipmentId || !downtimeForm.productionDate || !downtimeForm.shiftId || !downtimeForm.lossReasonId) {
    ElMessage.warning('请选择设备、日期、班次和损失原因')
    return
  }
  durationFromTime()
  try {
    const payload = {
      ...downtimeForm,
      startedTime: downtimeForm.startedTime || undefined,
      endedTime: downtimeForm.endedTime || undefined,
    }
    if (editingDowntimeId.value) await oeeApi.updateDowntime(editingDowntimeId.value, payload)
    else await oeeApi.createDowntime(payload)
    ElMessage.success('停机保存成功，关联OEE已自动重算')
    downtimeDialog.value = false
    await loadDowntimes()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function removeOutput(row: OutputRow) {
  await ElMessageBox.confirm('删除产量后关联OEE将自动重算，是否继续？', '删除确认', { type: 'warning' })
  try {
    await oeeApi.deleteOutput(row.id, row.version)
    ElMessage.success('产量已删除')
    await loadOutputs()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function removeDowntime(row: DowntimeRow) {
  await ElMessageBox.confirm('删除停机后关联OEE将自动重算，是否继续？', '删除确认', { type: 'warning' })
  try {
    await oeeApi.deleteDowntime(row.id, row.version)
    ElMessage.success('停机已删除')
    await loadDowntimes()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div><h1>产量与停机维护</h1><p>维护 OEE 原始事实数据。源数据发生修订时，后端自动重算并保留计算日志；锁定数据不可修改。</p></div>
    </header>
    <section class="surface-card">
      <div class="toolbar">
        <el-select v-model="query.equipmentId" clearable filterable placeholder="全部设备" style="width: 240px"><el-option v-for="item in equipment" :key="item.id" :label="`${item.equipmentCode} · ${item.equipmentName}`" :value="item.id" /></el-select>
        <el-date-picker v-model="query.startDate" type="date" value-format="YYYY-MM-DD" placeholder="开始日期" />
        <el-date-picker v-model="query.endDate" type="date" value-format="YYYY-MM-DD" placeholder="结束日期" />
        <el-button type="primary" @click="query.page = 1; loadOutputs(); loadDowntimes()">查询</el-button>
      </div>
      <el-tabs v-model="tab">
        <el-tab-pane label="产量记录" name="output">
          <div class="tab-action"><el-button v-if="auth.can('oee:output:manage')" type="success" @click="openOutput()">新增产量</el-button></div>
          <el-table v-loading="loading" :data="outputs" stripe>
            <el-table-column prop="productionDate" label="日期" width="115" />
            <el-table-column prop="equipmentCode" label="设备编码" width="150" />
            <el-table-column prop="equipmentName" label="设备名称" min-width="150" />
            <el-table-column prop="shiftName" label="班次" width="90" />
            <el-table-column prop="plannedQuantity" label="计划数量" width="110" />
            <el-table-column prop="actualQuantity" label="实际产量" width="110" />
            <el-table-column prop="goodQuantity" label="良品" width="100" />
            <el-table-column prop="defectiveQuantity" label="不良品" width="100" />
            <el-table-column prop="sourceType" label="来源" width="90" />
            <el-table-column v-if="auth.can('oee:output:manage')" label="操作" width="140" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openOutput(row)">编辑</el-button><el-button link type="danger" @click="removeOutput(row)">删除</el-button></template></el-table-column>
            <template #empty><el-empty description="暂无产量数据" /></template>
          </el-table>
          <el-pagination v-model:current-page="query.page" v-model:page-size="query.pageSize" :total="outputTotal" layout="total, prev, pager, next" @current-change="loadOutputs" />
        </el-tab-pane>
        <el-tab-pane label="停机与损失" name="downtime">
          <div class="tab-action"><el-button v-if="auth.can('oee:downtime:manage')" type="success" @click="openDowntime()">新增停机</el-button></div>
          <el-table v-loading="loading" :data="downtimes" stripe>
            <el-table-column prop="productionDate" label="日期" width="115" />
            <el-table-column prop="equipmentCode" label="设备编码" width="150" />
            <el-table-column prop="equipmentName" label="设备名称" min-width="140" />
            <el-table-column prop="shiftName" label="班次" width="90" />
            <el-table-column prop="reasonName" label="损失原因" min-width="140" />
            <el-table-column prop="durationMinutes" label="时长(分)" width="100" />
            <el-table-column label="属性" width="95"><template #default="{ row }"><el-tag :type="row.plannedFlag ? 'info' : 'warning'">{{ row.plannedFlag ? '计划' : '非计划' }}</el-tag></template></el-table-column>
            <el-table-column prop="sourceType" label="来源" width="90" />
            <el-table-column v-if="auth.can('oee:downtime:manage')" label="操作" width="140" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openDowntime(row)">编辑</el-button><el-button link type="danger" @click="removeDowntime(row)">删除</el-button></template></el-table-column>
            <template #empty><el-empty description="暂无停机与损失数据" /></template>
          </el-table>
          <el-pagination v-model:current-page="query.page" v-model:page-size="query.pageSize" :total="downtimeTotal" layout="total, prev, pager, next" @current-change="loadDowntimes" />
        </el-tab-pane>
      </el-tabs>
    </section>

    <el-dialog v-model="outputDialog" :title="editingOutputId ? '编辑产量' : '新增产量'" width="650px">
      <el-form label-width="110px">
        <el-form-item label="设备"><el-select v-model="outputForm.equipmentId" filterable style="width: 100%"><el-option v-for="item in equipment" :key="item.id" :label="`${item.equipmentCode} · ${item.equipmentName}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="日期/班次"><el-date-picker v-model="outputForm.productionDate" type="date" value-format="YYYY-MM-DD" /><el-select v-model="outputForm.shiftId"><el-option v-for="item in shifts" :key="item.id" :label="item.shiftName" :value="item.id" /></el-select></el-form-item>
        <div class="number-grid">
          <el-form-item label="计划数量"><el-input-number v-model="outputForm.plannedQuantity" :min="0" :precision="3" /></el-form-item>
          <el-form-item label="实际产量"><el-input-number v-model="outputForm.actualQuantity" :min="0" :precision="3" /></el-form-item>
          <el-form-item label="良品数量"><el-input-number v-model="outputForm.goodQuantity" :min="0" :precision="3" /></el-form-item>
          <el-form-item label="不良品数量"><el-input-number v-model="outputForm.defectiveQuantity" :min="0" :precision="3" /></el-form-item>
        </div>
        <el-form-item label="来源"><el-select v-model="outputForm.sourceType"><el-option label="人工录入" value="MANUAL" /><el-option label="MES" value="MES" /><el-option label="IoT" value="IOT" /></el-select></el-form-item>
        <el-form-item label="来源引用"><el-input v-model="outputForm.sourceReference" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="outputForm.remark" type="textarea" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="outputDialog = false">取消</el-button><el-button type="primary" @click="saveOutput">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="downtimeDialog" :title="editingDowntimeId ? '编辑停机' : '新增停机'" width="680px">
      <el-form label-width="110px">
        <el-form-item label="设备"><el-select v-model="downtimeForm.equipmentId" filterable style="width: 100%"><el-option v-for="item in equipment" :key="item.id" :label="`${item.equipmentCode} · ${item.equipmentName}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="日期/班次"><el-date-picker v-model="downtimeForm.productionDate" type="date" value-format="YYYY-MM-DD" /><el-select v-model="downtimeForm.shiftId"><el-option v-for="item in shifts" :key="item.id" :label="item.shiftName" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="损失原因"><el-select v-model="downtimeForm.lossReasonId" filterable style="width: 100%" @change="syncReason"><el-option v-for="item in reasons" :key="item.id" :label="`${item.reasonCode} · ${item.reasonName}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="计划属性"><el-tag :type="downtimeForm.plannedFlag ? 'info' : 'warning'">{{ downtimeForm.plannedFlag ? '计划停机' : '非计划损失' }}</el-tag></el-form-item>
        <el-form-item label="开始时间"><el-date-picker v-model="downtimeForm.startedTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" clearable @change="durationFromTime" /></el-form-item>
        <el-form-item label="结束时间"><el-date-picker v-model="downtimeForm.endedTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" clearable @change="durationFromTime" /></el-form-item>
        <el-form-item label="时长(分钟)"><el-input-number v-model="downtimeForm.durationMinutes" :min="0.001" :precision="3" :disabled="Boolean(downtimeForm.startedTime && downtimeForm.endedTime)" /></el-form-item>
        <el-form-item label="来源"><el-select v-model="downtimeForm.sourceType"><el-option label="人工录入" value="MANUAL" /><el-option label="MES" value="MES" /><el-option label="IoT" value="IOT" /><el-option label="设备状态" value="STATUS" /></el-select></el-form-item>
        <el-form-item label="说明"><el-input v-model="downtimeForm.description" type="textarea" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="downtimeDialog = false">取消</el-button><el-button type="primary" @click="saveDowntime">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar, .tab-action { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 14px; }
.tab-action { justify-content: flex-end; }
.el-pagination { justify-content: flex-end; margin-top: 16px; }
.number-grid { display: grid; grid-template-columns: 1fr 1fr; }
.el-form-item :deep(.el-date-editor), .el-form-item :deep(.el-select) { margin-right: 10px; }
@media (max-width: 640px) { .number-grid { grid-template-columns: 1fr; } }
</style>
