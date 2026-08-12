<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { equipmentApi, type EquipmentRow, type EquipmentStatusSummary, type StatusHistoryRow } from '@/api/equipment'
import { masterDataApi, type OrganizationRow } from '@/api/masterData'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'
import { applySmartTableQuery, type SmartTableServerQuery } from '@/components/table/smart-table-context'
import {
  EQUIPMENT_STATUS_META,
  EQUIPMENT_STATUS_TRANSITIONS,
  equipmentStatusLabel,
  equipmentStatusType,
  normalizeEquipmentStatus,
} from '@/utils/equipment-status'

const auth = useAuthStore()
const loading = ref(false)
const rows = ref<EquipmentRow[]>([])
const total = ref(0)
const statusSummary = ref<EquipmentStatusSummary>({})
const keyword = ref('')
const statusCode = ref<string>()
const organizationId = ref<number>()
const organizations = ref<OrganizationRow[]>([])
const page = ref(1)
const smartTableQuery = reactive({
  tableFilters: undefined as string | undefined,
  sortBy: undefined as string | undefined,
  sortDirection: undefined as 'asc' | 'desc' | undefined,
})
const dialogVisible = ref(false)
const historyVisible = ref(false)
const selected = ref<EquipmentRow | null>(null)
const history = ref<StatusHistoryRow[]>([])
const form = reactive({
  statusCode: '',
  reason: '',
  sourceType: 'MANUAL',
})

const statusMeta = EQUIPMENT_STATUS_META
const transitions = EQUIPMENT_STATUS_TRANSITIONS
const activeOrganizations = computed(() => organizations.value.filter((row) => row.status === 1))

onMounted(async () => {
  await Promise.all([loadOrganizations(), load()])
})

async function loadOrganizations() {
  try {
    organizations.value = await masterDataApi.organizations()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function load() {
  loading.value = true
  try {
    const commonQuery = {
      keyword: keyword.value || undefined,
      organizationId: organizationId.value,
      tableFilters: smartTableQuery.tableFilters,
    }
    const [result, summary] = await Promise.all([
      equipmentApi.page({
        ...commonQuery,
        currentStatusCode: statusCode.value,
        sortBy: smartTableQuery.sortBy,
        sortDirection: smartTableQuery.sortDirection,
        page: page.value,
        pageSize: 50,
      }),
      equipmentApi.statusSummary(commonQuery),
    ])
    rows.value = result.records
    total.value = result.total
    statusSummary.value = summary
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function applyTableQuery(query: SmartTableServerQuery) {
  applySmartTableQuery(smartTableQuery, query)
  page.value = 1
  load()
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
        <strong>{{ statusSummary[code] || 0 }}</strong>
      </article>
    </section>

    <section class="surface-card query-bar">
      <el-select
        v-model="organizationId"
        clearable
        filterable
        placeholder="全部部门"
        style="width: min(280px, 100%)"
      >
        <el-option
          v-for="item in activeOrganizations"
          :key="item.id"
          :label="`${item.organizationName}（${item.organizationCode}）`"
          :value="item.id"
        />
      </el-select>
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
      <el-table :data="rows" row-key="id" server-query @smart-query-change="applyTableQuery">
        <el-table-column prop="equipmentCode" label="设备编码" min-width="160">
          <template #default="{ row }"><span class="mono">{{ row.equipmentCode }}</span></template>
        </el-table-column>
        <el-table-column prop="equipmentName" label="设备名称" min-width="180" />
        <el-table-column prop="organizationName" label="所属部门" min-width="160" />
        <el-table-column prop="locationName" label="位置" min-width="140" />
        <el-table-column prop="currentStatusCode" label="当前状态" smart-filter="select" width="120">
          <template #default="{ row }">
            <el-tag :type="equipmentStatusType(row.currentStatusCode)">
              {{ equipmentStatusLabel(row.currentStatusCode) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="statusSince" label="状态开始时间" min-width="180" />
        <el-table-column prop="statusDurationSeconds" label="持续时间" smart-filter="number" min-width="150">
          <template #default="{ row }">{{ duration(row.statusDurationSeconds) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="showHistory(row)">履历</el-button>
            <el-button
              v-if="auth.can('equipment:status:update') && transitions[normalizeEquipmentStatus(row.currentStatusCode)]?.length"
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
        :title="`${selected.equipmentCode} · ${selected.equipmentName}：${equipmentStatusLabel(selected.currentStatusCode)}`"
        type="info"
        :closable="false"
      />
      <el-form label-position="top">
        <el-form-item label="目标状态">
          <el-select v-model="form.statusCode">
            <el-option
              v-for="code in selected ? transitions[normalizeEquipmentStatus(selected.currentStatusCode)] : []"
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

    <el-drawer v-model="historyVisible" :title="`${selected?.equipmentCode || ''} · ${selected?.equipmentName || ''} · 状态履历`" size="min(720px, 96vw)">
      <el-timeline>
        <el-timeline-item
          v-for="item in history"
          :key="item.id"
          :timestamp="item.startedTime"
          :type="equipmentStatusType(item.toStatusCode)"
        >
          <div class="history-card">
            <strong>
              {{ item.fromStatusCode ? equipmentStatusLabel(item.fromStatusCode) : '初始状态' }}
              → {{ equipmentStatusLabel(item.toStatusCode) }}
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
