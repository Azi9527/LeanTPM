<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { maintenanceApi, type AbnormalRow } from '@/api/maintenance'
import { masterDataApi, type ReferenceUser } from '@/api/masterData'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const rows = ref<AbnormalRow[]>([])
const total = ref(0)
const page = ref(1)
const keyword = ref('')
const status = ref<string>()
const users = ref<ReferenceUser[]>([])
const dialogVisible = ref(false)
const selected = ref<AbnormalRow | null>(null)
const form = reactive({
  responsibleUserId: undefined as number | undefined,
  dueTime: '',
  temporaryAction: '',
  finalResult: '',
  requestedEquipmentStatus: '',
  targetStatus: 'PROCESSING' as 'PROCESSING' | 'PENDING_VERIFY',
})

const statusMeta: Record<string, { label: string; type: 'danger' | 'warning' | 'success' | 'info' }> = {
  OPEN: { label: '待处理', type: 'danger' },
  PROCESSING: { label: '处理中', type: 'warning' },
  PENDING_VERIFY: { label: '待验证', type: 'info' },
  CLOSED: { label: '已关闭', type: 'success' },
}
const severityMeta: Record<string, { label: string; type: 'info' | 'warning' | 'danger' }> = {
  LOW: { label: '低', type: 'info' },
  MEDIUM: { label: '中', type: 'warning' },
  HIGH: { label: '高', type: 'danger' },
  CRITICAL: { label: '紧急', type: 'danger' },
}

onMounted(async () => {
  await Promise.all([load(), loadUsers()])
})

async function load() {
  loading.value = true
  try {
    const result = await maintenanceApi.abnormalities({
      keyword: keyword.value || undefined,
      abnormalStatus: status.value,
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

async function loadUsers() {
  try { users.value = await masterDataApi.referenceUsers() } catch { users.value = [] }
}

function openHandle(row: AbnormalRow) {
  selected.value = row
  Object.assign(form, {
    responsibleUserId: row.responsibleUserId,
    dueTime: row.dueTime || '',
    temporaryAction: row.temporaryAction || '',
    finalResult: row.finalResult || '',
    requestedEquipmentStatus: row.requestedEquipmentStatus || '',
    targetStatus: row.abnormalStatus === 'OPEN' ? 'PROCESSING' : 'PENDING_VERIFY',
  })
  dialogVisible.value = true
}

async function saveHandle() {
  if (!selected.value) return
  try {
    await maintenanceApi.handleAbnormal(selected.value.id, {
      ...form,
      responsibleUserId: form.responsibleUserId || null,
      dueTime: form.dueTime || null,
      requestedEquipmentStatus: form.requestedEquipmentStatus || null,
      version: selected.value.version,
    })
    dialogVisible.value = false
    ElMessage.success(form.targetStatus === 'PENDING_VERIFY' ? '异常已提交验证' : '异常处理信息已保存')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function verify(row: AbnormalRow, passed: boolean) {
  const prompt = await ElMessageBox.prompt(
    passed ? '请输入验证意见' : '请输入退回原因',
    passed ? '异常验证通过' : '异常验证退回',
    { inputPattern: /\S+/, inputErrorMessage: '验证意见不能为空' },
  )
  try {
    await maintenanceApi.verifyAbnormal(row.id, {
      passed,
      comment: prompt.value,
      version: row.version,
    })
    ElMessage.success(passed ? '异常已闭环' : '异常已退回处理')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function toRepair(row: AbnormalRow) {
  try {
    await maintenanceApi.abnormalToRepair(row.id)
    ElMessage.success('已创建独立维修工单')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error, '异常转维修工单失败'))
  }
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header"><div><h1>维保异常</h1><p>维保结果自动生成异常记录，落实责任、措施、结果和独立验证。</p></div></header>
    <section class="surface-card query-bar">
      <el-input v-model="keyword" clearable placeholder="异常、任务或设备" @keyup.enter="page = 1; load()" />
      <el-select v-model="status" clearable placeholder="异常状态"><el-option v-for="(meta, value) in statusMeta" :key="value" :label="meta.label" :value="value" /></el-select>
      <el-button type="primary" @click="page = 1; load()">查询</el-button>
    </section>
    <section class="surface-card table-card" v-loading="loading">
      <div class="table-toolbar"><span class="table-title">异常闭环台账</span><span>共 {{ total }} 条</span></div>
      <el-table :data="rows" row-key="id">
        <el-table-column label="异常" min-width="230"><template #default="{ row }"><strong>{{ row.abnormalTitle }}</strong><div class="muted mono">{{ row.abnormalCode }} · {{ row.taskCode }}</div></template></el-table-column>
        <el-table-column label="设备/项目" min-width="190"><template #default="{ row }">{{ row.equipmentName }}<div class="muted">{{ row.itemName }}</div></template></el-table-column>
        <el-table-column prop="abnormalDescription" label="异常现象" min-width="220" show-overflow-tooltip />
        <el-table-column label="等级" width="85"><template #default="{ row }"><el-tag :type="severityMeta[row.severity].type">{{ severityMeta[row.severity].label }}</el-tag></template></el-table-column>
        <el-table-column prop="responsibleUserName" label="责任人" width="110"><template #default="{ row }">{{ row.responsibleUserName || '待分派' }}</template></el-table-column>
        <el-table-column prop="dueTime" label="期限" min-width="170" />
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="statusMeta[row.abnormalStatus].type">{{ statusMeta[row.abnormalStatus].label }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button v-if="auth.can('maintenance:abnormal:handle') && ['OPEN','PROCESSING'].includes(row.abnormalStatus)" link type="primary" @click="openHandle(row)">处理</el-button>
            <el-button v-if="auth.can('fault:repair:create') && !row.repairOrderId && row.abnormalStatus !== 'CLOSED'" link type="warning" @click="toRepair(row)">转维修</el-button>
            <el-tag v-else-if="row.repairOrderId" size="small" type="success">已转维修</el-tag>
            <template v-if="auth.can('maintenance:abnormal:verify') && row.abnormalStatus === 'PENDING_VERIFY'">
              <el-button link type="success" @click="verify(row, true)">通过</el-button>
              <el-button link type="warning" @click="verify(row, false)">退回</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @change="load" />
    </section>

    <el-dialog v-model="dialogVisible" :title="`处理异常 · ${selected?.abnormalCode || ''}`" width="min(720px, 96vw)">
      <el-alert v-if="selected" :title="`${selected.equipmentName} · ${selected.abnormalTitle}`" :description="selected.abnormalDescription" type="error" :closable="false" />
      <el-form label-position="top" class="form-grid">
        <el-form-item label="责任人"><el-select v-model="form.responsibleUserId" clearable filterable><el-option v-for="user in users" :key="user.id" :label="user.realName" :value="user.id" /></el-select></el-form-item>
        <el-form-item label="完成期限"><el-date-picker v-model="form.dueTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item>
        <el-form-item label="临时措施" class="full"><el-input v-model="form.temporaryAction" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="最终处理结果" class="full"><el-input v-model="form.finalResult" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="建议设备状态"><el-select v-model="form.requestedEquipmentStatus" clearable><el-option label="空闲" value="IDLE" /><el-option label="运行" value="RUNNING" /><el-option label="保养" value="MAINTENANCE" /><el-option label="维保" value="INSPECTION" /><el-option label="故障" value="FAULT" /><el-option label="维修" value="REPAIR" /><el-option label="停机" value="STOPPED" /><el-option label="离线" value="OFFLINE" /></el-select></el-form-item>
        <el-form-item label="处理状态"><el-radio-group v-model="form.targetStatus"><el-radio-button value="PROCESSING">处理中</el-radio-button><el-radio-button value="PENDING_VERIFY">提交验证</el-radio-button></el-radio-group></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" @click="saveHandle">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.muted { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 4px; }
.form-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px; margin-top: 18px; }
.full { grid-column: 1 / -1; }
@media (max-width: 640px) { .form-grid { grid-template-columns: 1fr; } .full { grid-column: auto; } }
</style>
