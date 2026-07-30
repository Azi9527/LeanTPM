<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { equipmentApi, type EquipmentRow, type StatusHistoryRow } from '@/api/equipment'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const rows = ref<EquipmentRow[]>([])
const total = ref(0)
const keyword = ref('')
const statusCode = ref<string>()
const page = ref(1)
const dialogVisible = ref(false)
const historyVisible = ref(false)
const selected = ref<EquipmentRow | null>(null)
const history = ref<StatusHistoryRow[]>([])
const form = reactive({
  statusCode: '',
  reason: '',
  sourceType: 'MANUAL',
})

const statusMeta: Record<string, { label: string; type: '' | 'success' | 'warning' | 'danger' | 'info' }> = {
  NOT_ENABLED: { label: '未启用', type: 'info' },
  IDLE: { label: '空闲', type: 'info' },
  RUNNING: { label: '运行', type: 'success' },
  COMMISSIONING: { label: '调试', type: '' },
  CHANGEOVER: { label: '换型', type: 'warning' },
  MAINTENANCE: { label: '保养', type: 'warning' },
  INSPECTION: { label: '点检', type: '' },
  FAULT: { label: '故障', type: 'danger' },
  REPAIR: { label: '维修', type: 'danger' },
  STOPPED: { label: '停机', type: 'warning' },
  SCRAPPED: { label: '报废', type: 'info' },
  OFFLINE: { label: '离线', type: 'info' },
}

const transitions: Record<string, string[]> = {
  NOT_ENABLED: ['IDLE', 'COMMISSIONING', 'OFFLINE'],
  IDLE: ['RUNNING', 'MAINTENANCE', 'INSPECTION', 'FAULT', 'STOPPED', 'OFFLINE', 'SCRAPPED'],
  RUNNING: ['IDLE', 'CHANGEOVER', 'FAULT', 'STOPPED', 'OFFLINE'],
  COMMISSIONING: ['IDLE', 'FAULT', 'OFFLINE'],
  CHANGEOVER: ['RUNNING', 'IDLE', 'FAULT'],
  MAINTENANCE: ['IDLE', 'FAULT', 'OFFLINE'],
  INSPECTION: ['IDLE', 'FAULT', 'OFFLINE'],
  FAULT: ['REPAIR', 'STOPPED', 'OFFLINE'],
  REPAIR: ['IDLE', 'FAULT', 'OFFLINE'],
  STOPPED: ['IDLE', 'MAINTENANCE', 'REPAIR', 'SCRAPPED', 'OFFLINE'],
  OFFLINE: ['IDLE', 'COMMISSIONING'],
  SCRAPPED: [],
}

onMounted(load)

async function load() {
  loading.value = true
  try {
    const result = await equipmentApi.page({
      keyword: keyword.value || undefined,
      currentStatusCode: statusCode.value,
      page: page.value,
      pageSize: 50,
    })
    rows.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function openChange(row: EquipmentRow) {
  selected.value = row
  Object.assign(form, { statusCode: '', reason: '', sourceType: 'MANUAL' })
  dialogVisible.value = true
}

async function saveStatus() {
  if (!selected.value || !form.statusCode) {
    ElMessage.warning('请选择目标状态')
    return
  }
  try {
    await equipmentApi.changeStatus(selected.value.id, {
      ...form,
      reason: form.reason || null,
      version: selected.value.currentStatusVersion,
    })
    dialogVisible.value = false
    ElMessage.success('设备状态已更新')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function showHistory(row: EquipmentRow) {
  selected.value = row
  historyVisible.value = true
  history.value = []
  try {
    history.value = await equipmentApi.statusHistory(row.id)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

function duration(seconds?: number) {
  if (seconds == null) return '—'
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  return `${days ? `${days}天 ` : ''}${hours}小时 ${minutes}分钟`
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div>
        <h1>设备状态</h1>
        <p>当前状态与历史履历分离存储，所有人工切换均受状态机约束并保留持续时长。</p>
      </div>
    </header>

    <section class="status-summary">
      <article
        v-for="(meta, code) in statusMeta"
        :key="code"
        class="summary-card"
        :class="{ active: statusCode === code }"
        @click="statusCode = statusCode === code ? undefined : code; page = 1; load()"
      >
        <span>{{ meta.label }}</span>
        <strong>{{ rows.filter((row) => row.currentStatusCode === code).length }}</strong>
      </article>
    </section>

    <section class="surface-card query-bar">
      <el-input
        v-model="keyword"
        clearable
        placeholder="设备编码、名称或型号"
        style="width: min(360px, 100%)"
        @keyup.enter="page = 1; load()"
      />
      <el-select v-model="statusCode" clearable placeholder="当前状态">
        <el-option v-for="(meta, code) in statusMeta" :key="code" :label="meta.label" :value="code" />
      </el-select>
      <el-button type="primary" @click="page = 1; load()">查询</el-button>
    </section>

    <section class="surface-card table-card" v-loading="loading">
      <div class="table-toolbar">
        <span class="table-title">实时状态列表</span>
        <span class="result-count">共 {{ total }} 台</span>
      </div>
      <el-table :data="rows" row-key="id">
        <el-table-column prop="equipmentCode" label="设备编码" min-width="160">
          <template #default="{ row }"><span class="mono">{{ row.equipmentCode }}</span></template>
        </el-table-column>
        <el-table-column prop="equipmentName" label="设备名称" min-width="180" />
        <el-table-column prop="locationName" label="位置" min-width="140" />
        <el-table-column label="当前状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusMeta[row.currentStatusCode]?.type">
              {{ statusMeta[row.currentStatusCode]?.label || row.currentStatusCode }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="statusSince" label="状态开始时间" min-width="180" />
        <el-table-column label="持续时间" min-width="150">
          <template #default="{ row }">{{ duration(row.statusDurationSeconds) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="showHistory(row)">履历</el-button>
            <el-button
              v-if="auth.can('equipment:status:update') && transitions[row.currentStatusCode]?.length"
              link
              type="primary"
              @click="openChange(row)"
            >切换状态</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="page"
        :page-size="50"
        :total="total"
        layout="total, prev, pager, next"
        @change="load"
      />
    </section>

    <el-dialog v-model="dialogVisible" title="切换设备状态" width="min(560px, 94vw)">
      <el-alert
        v-if="selected"
        :title="`${selected.equipmentCode} · ${selected.equipmentName}：${statusMeta[selected.currentStatusCode]?.label}`"
        type="info"
        :closable="false"
      />
      <el-form label-position="top">
        <el-form-item label="目标状态">
          <el-select v-model="form.statusCode">
            <el-option
              v-for="code in transitions[selected?.currentStatusCode || ''] || []"
              :key="code"
              :label="statusMeta[code]?.label || code"
              :value="code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="切换原因">
          <el-input v-model="form.reason" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveStatus">确认切换</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="historyVisible" :title="`${selected?.equipmentName || ''} · 状态履历`" size="min(720px, 96vw)">
      <el-timeline>
        <el-timeline-item
          v-for="item in history"
          :key="item.id"
          :timestamp="item.startedTime"
          :type="statusMeta[item.toStatusCode]?.type || 'primary'"
        >
          <div class="history-card">
            <strong>
              {{ item.fromStatusCode ? statusMeta[item.fromStatusCode]?.label : '初始状态' }}
              → {{ statusMeta[item.toStatusCode]?.label || item.toStatusCode }}
            </strong>
            <p>{{ item.reason || '无补充原因' }}</p>
            <small>
              来源：{{ item.sourceType }} · 操作人：{{ item.changedByName || '系统' }}
              <template v-if="item.durationSeconds != null"> · 持续 {{ duration(item.durationSeconds) }}</template>
            </small>
          </div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-if="!history.length" description="暂无状态履历" />
    </el-drawer>
  </div>
</template>

<style scoped>
.status-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
  gap: 12px;
}

.summary-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  background: var(--el-bg-color);
  cursor: pointer;
}

.summary-card.active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.summary-card strong {
  font-size: 22px;
}

.history-card p {
  margin: 8px 0;
}

.history-card small {
  color: var(--el-text-color-secondary);
}
</style>
