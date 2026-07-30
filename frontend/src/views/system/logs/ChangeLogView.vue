<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { systemApi, type ChangeLogRow } from '@/api/system'
import { errorMessage } from '@/utils/http'

const loading = ref(false)
const loadError = ref('')
const rows = ref<ChangeLogRow[]>([])
const total = ref(0)
const selected = ref<ChangeLogRow | null>(null)
const detailVisible = ref(false)
const dateRange = ref<[string, string] | []>([])
const query = reactive({
  resourceType: '',
  keyword: '',
  page: 1,
  pageSize: 20,
})

const resourceOptions = [
  { value: '', label: '全部资源' },
  { value: 'SYSTEM_PARAMETER', label: '系统参数' },
  { value: 'NUMBER_RULE', label: '编号规则' },
  { value: 'ATTACHMENT_RELATION', label: '附件关系' },
]

const operationLabels: Record<string, string> = {
  CREATE: '新增',
  UPDATE: '修改',
  DELETE: '删除',
  BIND: '绑定',
  UNBIND: '解绑',
}

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const result = await systemApi.changeLogs({
      ...query,
      resourceType: query.resourceType || undefined,
      startDate: dateRange.value[0],
      endDate: dateRange.value[1],
    })
    rows.value = result.records
    total.value = result.total
  } catch (error) {
    loadError.value = errorMessage(error, '数据变更日志加载失败')
  } finally {
    loading.value = false
  }
}

function reset() {
  query.resourceType = ''
  query.keyword = ''
  query.page = 1
  dateRange.value = []
  load()
}

function openDetail(row: ChangeLogRow) {
  selected.value = row
  detailVisible.value = true
}

function parseChangedFields(value: string): string[] {
  try {
    const parsed = JSON.parse(value || '[]')
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function prettyJson(value?: string) {
  if (!value) return '无'
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

const detailTitle = computed(
  () => selected.value
    ? `${selected.value.resourceType} #${selected.value.resourceId}`
    : '变更详情',
)
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div>
        <h1>数据变更日志</h1>
        <p>追踪关键业务字段在变更前后的完整快照。</p>
      </div>
    </header>

    <section class="surface-card query-bar">
      <el-select v-model="query.resourceType" style="width: 170px">
        <el-option
          v-for="item in resourceOptions"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        />
      </el-select>
      <el-input
        v-model="query.keyword"
        clearable
        placeholder="资源 ID、操作人或操作类型"
        style="width: 260px"
        @keyup.enter="query.page = 1; load()"
      />
      <el-date-picker
        v-model="dateRange"
        type="daterange"
        value-format="YYYY-MM-DD"
        range-separator="至"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
      />
      <el-button type="primary" plain @click="query.page = 1; load()">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </section>

    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      show-icon
      :closable="false"
    >
      <template #default><el-button link type="primary" @click="load">重新加载</el-button></template>
    </el-alert>

    <section v-else class="surface-card table-card">
      <div class="table-toolbar">
        <span class="table-title">变更记录</span>
        <el-tag effect="plain">共 {{ total }} 条</el-tag>
      </div>
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="resourceType" label="资源类型" min-width="170">
          <template #default="{ row }"><span class="mono">{{ row.resourceType }}</span></template>
        </el-table-column>
        <el-table-column prop="resourceId" label="资源 ID" min-width="110" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-tag effect="light">{{ operationLabels[row.operationType] || row.operationType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="变更字段" min-width="260">
          <template #default="{ row }">
            <el-tag
              v-for="field in parseChangedFields(row.changedFields).slice(0, 5)"
              :key="field"
              class="field-tag"
              size="small"
              effect="plain"
            >
              {{ field }}
            </el-tag>
            <span v-if="parseChangedFields(row.changedFields).length === 0" class="muted">无字段</span>
          </template>
        </el-table-column>
        <el-table-column prop="operatorName" label="操作人" min-width="120" />
        <el-table-column prop="requestId" label="请求 ID" min-width="180" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.requestId || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="变更时间" min-width="180">
          <template #default="{ row }">{{ row.changeTime.replace('T', ' ') }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">查看</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无数据变更记录" /></template>
      </el-table>
      <div class="table-pagination">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @change="load"
        />
      </div>
    </section>

    <el-drawer v-model="detailVisible" :title="detailTitle" size="min(760px, 94vw)">
      <template v-if="selected">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="操作">{{ operationLabels[selected.operationType] }}</el-descriptions-item>
          <el-descriptions-item label="操作人">{{ selected.operatorName }}</el-descriptions-item>
          <el-descriptions-item label="请求 ID" :span="2">{{ selected.requestId || '—' }}</el-descriptions-item>
        </el-descriptions>
        <div class="snapshot-grid">
          <section>
            <h3>变更前</h3>
            <pre>{{ prettyJson(selected.beforeData) }}</pre>
          </section>
          <section>
            <h3>变更后</h3>
            <pre>{{ prettyJson(selected.afterData) }}</pre>
          </section>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.field-tag { margin: 2px 4px 2px 0; }
.muted { color: var(--tpm-text-secondary); }
.snapshot-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  margin-top: 18px;
}
.snapshot-grid h3 { margin: 0 0 8px; font-size: 13px; }
.snapshot-grid pre {
  min-height: 280px;
  margin: 0;
  padding: 14px;
  overflow: auto;
  border: 1px solid var(--tpm-border);
  border-radius: 8px;
  background: #f7f9fa;
  color: #25323a;
  font: 11px/1.55 "SFMono-Regular", Consolas, monospace;
  white-space: pre-wrap;
  word-break: break-all;
}
@media (max-width: 720px) {
  .snapshot-grid { grid-template-columns: 1fr; }
}
</style>
