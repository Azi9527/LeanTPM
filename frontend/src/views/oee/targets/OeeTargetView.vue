<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { oeeApi, type TargetRow } from '@/api/oee'
import { equipmentApi, type EquipmentRow } from '@/api/equipment'
import { masterDataApi, type OrganizationRow } from '@/api/masterData'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const records = ref<TargetRow[]>([])
const organizations = ref<OrganizationRow[]>([])
const equipment = ref<EquipmentRow[]>([])
const total = ref(0)
const dialog = ref(false)
const editingId = ref<number>()
const query = reactive({ keyword: '', targetLevel: '', status: undefined as number | undefined, page: 1, pageSize: 100 })
const form = reactive({
  targetName: '',
  targetLevel: 'EQUIPMENT',
  organizationId: undefined as number | undefined,
  equipmentId: undefined as number | undefined,
  availabilityTarget: 0.9,
  performanceTarget: 0.95,
  qualityTarget: 0.995,
  effectiveStartDate: new Date().toISOString().slice(0, 10),
  effectiveEndDate: '',
  status: 1,
  description: '',
  version: undefined as number | undefined,
})
const levels = [
  ['ENTERPRISE', '企业'], ['FACTORY', '工厂'], ['WORKSHOP', '车间'],
  ['LINE', '产线'], ['EQUIPMENT', '设备'],
]

onMounted(async () => {
  await Promise.all([load(), loadReferences()])
})

async function loadReferences() {
  try {
    const [orgs, equipmentPage] = await Promise.all([
      masterDataApi.organizations(),
      equipmentApi.page({ status: 1, page: 1, pageSize: 200 }),
    ])
    organizations.value = orgs
    equipment.value = equipmentPage.records.filter((item) => item.oeeEnabled)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function load() {
  loading.value = true
  try {
    const result = await oeeApi.targets({ ...query, targetLevel: query.targetLevel || undefined })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function open(row?: TargetRow) {
  editingId.value = row?.id
  Object.assign(form, row
    ? { ...row, effectiveEndDate: row.effectiveEndDate ?? '' }
    : {
        targetName: '',
        targetLevel: 'EQUIPMENT',
        organizationId: undefined,
        equipmentId: undefined,
        availabilityTarget: 0.9,
        performanceTarget: 0.95,
        qualityTarget: 0.995,
        effectiveStartDate: new Date().toISOString().slice(0, 10),
        effectiveEndDate: '',
        status: 1,
        description: '',
        version: undefined,
      })
  dialog.value = true
}

function changeLevel() {
  if (form.targetLevel === 'EQUIPMENT') form.organizationId = undefined
  else form.equipmentId = undefined
}

function matchingOrganizations() {
  return organizations.value.filter((item) => item.organizationType === form.targetLevel)
}

function percent(value?: number) {
  return value == null ? '—' : `${(Number(value) * 100).toFixed(2)}%`
}

function scopeName(row: TargetRow) {
  return row.targetLevel === 'EQUIPMENT'
    ? `${row.equipmentCode} · ${row.equipmentName}`
    : row.organizationName
}

async function save() {
  if (!form.targetName || !form.effectiveStartDate) {
    ElMessage.warning('请填写目标名称和生效日期')
    return
  }
  if (form.targetLevel === 'EQUIPMENT' ? !form.equipmentId : !form.organizationId) {
    ElMessage.warning('请选择目标作用范围')
    return
  }
  const payload = { ...form, effectiveEndDate: form.effectiveEndDate || undefined }
  try {
    if (editingId.value) await oeeApi.updateTarget(editingId.value, payload)
    else await oeeApi.createTarget(payload)
    ElMessage.success('OEE目标保存成功，综合目标已由后端精确计算')
    dialog.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function remove(row: TargetRow) {
  await ElMessageBox.confirm(`确认删除目标“${row.targetName}”？`, '删除确认', { type: 'warning' })
  try {
    await oeeApi.deleteTarget(row.id, row.version)
    ElMessage.success('OEE目标已删除')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div><h1>OEE 目标</h1><p>按企业、工厂、车间、产线或设备配置生效期目标；综合目标由后端统一精确计算。</p></div>
      <el-button v-if="auth.can('oee:target:manage')" type="primary" @click="open()">新增目标</el-button>
    </header>
    <section class="surface-card">
      <div class="toolbar">
        <el-input v-model="query.keyword" clearable placeholder="目标/组织/设备" style="width: 230px" @keyup.enter="load" />
        <el-select v-model="query.targetLevel" clearable placeholder="全部层级" style="width: 150px"><el-option v-for="[value, label] in levels" :key="value" :label="label" :value="value" /></el-select>
        <el-select v-model="query.status" clearable placeholder="全部状态" style="width: 130px"><el-option label="启用" :value="1" /><el-option label="停用" :value="0" /></el-select>
        <el-button type="primary" @click="query.page = 1; load()">查询</el-button>
      </div>
      <el-table v-loading="loading" :data="records" stripe>
        <el-table-column prop="targetName" label="目标名称" min-width="160" />
        <el-table-column label="层级" width="90"><template #default="{ row }">{{ levels.find(([value]) => value === row.targetLevel)?.[1] }}</template></el-table-column>
        <el-table-column label="作用范围" min-width="190"><template #default="{ row }">{{ scopeName(row) }}</template></el-table-column>
        <el-table-column label="时间开动率" width="110"><template #default="{ row }">{{ percent(row.availabilityTarget) }}</template></el-table-column>
        <el-table-column label="性能开动率" width="110"><template #default="{ row }">{{ percent(row.performanceTarget) }}</template></el-table-column>
        <el-table-column label="良品率" width="100"><template #default="{ row }">{{ percent(row.qualityTarget) }}</template></el-table-column>
        <el-table-column label="综合OEE" width="105"><template #default="{ row }"><strong>{{ percent(row.oeeTarget) }}</strong></template></el-table-column>
        <el-table-column label="生效期" width="210"><template #default="{ row }">{{ row.effectiveStartDate }} ～ {{ row.effectiveEndDate || '长期' }}</template></el-table-column>
        <el-table-column label="状态" width="80"><template #default="{ row }"><el-tag :type="row.status ? 'success' : 'info'">{{ row.status ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column v-if="auth.can('oee:target:manage')" label="操作" width="140" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="open(row)">编辑</el-button><el-button link type="danger" @click="remove(row)">删除</el-button></template></el-table-column>
        <template #empty><el-empty description="暂无OEE目标" /></template>
      </el-table>
      <el-pagination v-model:current-page="query.page" v-model:page-size="query.pageSize" :total="total" layout="total, prev, pager, next" @current-change="load" />
    </section>

    <el-dialog v-model="dialog" :title="editingId ? '编辑OEE目标' : '新增OEE目标'" width="680px">
      <el-form label-width="120px">
        <el-form-item label="目标名称"><el-input v-model="form.targetName" /></el-form-item>
        <el-form-item label="目标层级"><el-select v-model="form.targetLevel" style="width: 100%" @change="changeLevel"><el-option v-for="[value, label] in levels" :key="value" :label="label" :value="value" /></el-select></el-form-item>
        <el-form-item v-if="form.targetLevel === 'EQUIPMENT'" label="设备"><el-select v-model="form.equipmentId" filterable style="width: 100%"><el-option v-for="item in equipment" :key="item.id" :label="`${item.equipmentCode} · ${item.equipmentName}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item v-else label="组织"><el-select v-model="form.organizationId" filterable style="width: 100%"><el-option v-for="item in matchingOrganizations()" :key="item.id" :label="`${item.organizationCode} · ${item.organizationName}`" :value="item.id" /></el-select></el-form-item>
        <div class="rate-form">
          <el-form-item label="时间开动率"><el-input-number v-model="form.availabilityTarget" :min="0" :max="1" :step="0.01" :precision="6" /></el-form-item>
          <el-form-item label="性能开动率"><el-input-number v-model="form.performanceTarget" :min="0" :max="1" :step="0.01" :precision="6" /></el-form-item>
          <el-form-item label="良品率"><el-input-number v-model="form.qualityTarget" :min="0" :max="1" :step="0.001" :precision="6" /></el-form-item>
        </div>
        <el-alert type="info" :closable="false" title="综合 OEE 目标不在浏览器计算，保存后由后端统一精确计算并返回。" />
        <el-form-item label="开始日期"><el-date-picker v-model="form.effectiveStartDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="结束日期"><el-date-picker v-model="form.effectiveEndDate" type="date" value-format="YYYY-MM-DD" clearable /></el-form-item>
        <el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 16px; }
.el-pagination { justify-content: flex-end; margin-top: 16px; }
.rate-form { display: grid; grid-template-columns: repeat(3, 1fr); }
.el-alert { margin: -4px 0 18px; }
@media (max-width: 720px) { .rate-form { grid-template-columns: 1fr; } }
</style>
