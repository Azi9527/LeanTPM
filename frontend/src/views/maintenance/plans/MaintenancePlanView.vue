<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { maintenanceApi, type PlanRow } from '@/api/maintenance'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const generating = ref(false)
const rows = ref<PlanRow[]>([])
const total = ref(0)
const page = ref(1)
const keyword = ref('')
const status = ref<string>()

const statusMeta: Record<string, { label: string; type: 'success' | 'warning' | 'info' }> = {
  ACTIVE: { label: '执行中', type: 'success' },
  PAUSED: { label: '已暂停', type: 'warning' },
  CANCELLED: { label: '已取消', type: 'info' },
}
const cycleLabels: Record<string, string> = {
  DAILY: '每日',
  WEEKLY: '每周',
  MONTHLY: '每月',
  QUARTERLY: '每季度',
  HALF_YEARLY: '每半年',
  YEARLY: '每年',
  RUNNING_HOURS: '运行小时',
  PRODUCTION_QUANTITY: '生产数量',
  MANUAL: '手工触发',
}

async function updateMeter(row: PlanRow) {
  const value = await ElMessageBox.prompt(
    `当前累计值 ${row.currentMeterValue}，下次触发 ${row.nextTriggerValue ?? '—'}`,
    row.cycleType === 'RUNNING_HOURS' ? '维护累计运行小时' : '维护累计生产数量',
    { inputPattern: /^\d+(\.\d{1,3})?$/, inputErrorMessage: '请输入非负数，最多三位小数' },
  )
  try {
    await maintenanceApi.updatePlanMeter(row.id, {
      currentValue: Number(value.value),
      version: row.version,
    })
    ElMessage.success('设备累计值已更新')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

onMounted(load)

async function load() {
  loading.value = true
  try {
    const result = await maintenanceApi.plans({
      keyword: keyword.value || undefined,
      planStatus: status.value,
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

async function generate() {
  generating.value = true
  try {
    const result = await maintenanceApi.generateTasks()
    ElMessage.success(`已生成 ${result.generatedTasks} 个任务，跳过 ${result.skippedOccurrences} 个已存在时点`)
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    generating.value = false
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
    await maintenanceApi.updatePlanStatus(row.id, { planStatus: target, reason, version: row.version })
    ElMessage.success('计划状态已更新')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div><h1>维保计划</h1><p>方案发布后按适用设备自动形成计划，生成器保证同一计划时点只产生一个任务。</p></div>
      <el-button v-if="auth.can('maintenance:plan:generate')" type="primary" :loading="generating" @click="generate">立即生成任务</el-button>
    </header>
    <section class="surface-card query-bar">
      <el-input v-model="keyword" clearable placeholder="方案或设备编码、名称" @keyup.enter="page = 1; load()" />
      <el-select v-model="status" clearable placeholder="计划状态"><el-option v-for="(meta, value) in statusMeta" :key="value" :label="meta.label" :value="value" /></el-select>
      <el-button type="primary" @click="page = 1; load()">查询</el-button>
    </section>
    <section class="surface-card table-card" v-loading="loading">
      <div class="table-toolbar"><span class="table-title">设备计划</span><span>共 {{ total }} 条</span></div>
      <el-table :data="rows" row-key="id">
        <el-table-column label="方案" min-width="220"><template #default="{ row }"><strong>{{ row.schemeName }}</strong><div class="muted mono">{{ row.schemeCode }} · V{{ row.schemeVersionNumber }}</div></template></el-table-column>
        <el-table-column label="设备" min-width="200"><template #default="{ row }"><strong>{{ row.equipmentName }}</strong><div class="muted mono">{{ row.equipmentCode }}</div></template></el-table-column>
        <el-table-column label="位置" min-width="170"><template #default="{ row }">{{ row.organizationName }} / {{ row.locationName }}</template></el-table-column>
        <el-table-column label="周期" width="130"><template #default="{ row }">{{ cycleLabels[row.cycleType] || row.cycleType }} × {{ row.cycleInterval }}</template></el-table-column>
        <el-table-column prop="assigneeName" label="执行人" width="120"><template #default="{ row }">{{ row.assigneeName || '待派工' }}</template></el-table-column>
        <el-table-column label="下次触发" min-width="150"><template #default="{ row }"><template v-if="['RUNNING_HOURS','PRODUCTION_QUANTITY'].includes(row.cycleType)">{{ row.currentMeterValue }} / {{ row.nextTriggerValue }}</template><template v-else>{{ row.nextGenerationDate }}</template></template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="statusMeta[row.planStatus].type">{{ statusMeta[row.planStatus].label }}</el-tag></template></el-table-column>
        <el-table-column v-if="auth.can('maintenance:plan:manage')" label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.planStatus === 'ACTIVE'" link type="warning" @click="changeStatus(row, 'PAUSED')">暂停</el-button>
            <el-button v-if="row.planStatus === 'PAUSED'" link type="success" @click="changeStatus(row, 'ACTIVE')">恢复</el-button>
            <el-button v-if="auth.can('maintenance:plan:meter') && ['RUNNING_HOURS','PRODUCTION_QUANTITY'].includes(row.cycleType)" link type="primary" @click="updateMeter(row)">累计值</el-button>
            <el-button v-if="row.planStatus !== 'CANCELLED'" link type="danger" @click="changeStatus(row, 'CANCELLED')">取消</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @change="load" />
    </section>
  </div>
</template>

<style scoped>
.muted { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 4px; }
</style>
