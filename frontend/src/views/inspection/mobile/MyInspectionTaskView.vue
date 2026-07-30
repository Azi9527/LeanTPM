<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import { inspectionApi, type TaskDetail, type TaskItemRow, type TaskRow, type TaskStatus } from '@/api/inspection'
import { systemApi } from '@/api/system'
import { errorMessage } from '@/utils/http'

interface ResultDraft {
  resultCode?: string
  numericValue?: number
  textValue?: string
  selectedValue?: string
  selectedValues: string[]
  abnormal: boolean
  abnormalDescription?: string
  skipped: boolean
  skipReason?: string
  attachmentIds: number[]
  version?: number
}

const loading = ref(false)
const saving = ref(false)
const uploadingItemId = ref<number>()
const rows = ref<TaskRow[]>([])
const activeStatus = ref<TaskStatus | ''>('')
const detail = ref<TaskDetail | null>(null)
const executionVisible = ref(false)
const executionRemark = ref('')
const drafts = reactive<Record<number, ResultDraft>>({})

const statusLabels: Record<TaskStatus, string> = {
  PENDING: '待执行',
  IN_PROGRESS: '执行中',
  PENDING_REVIEW: '待复核',
  COMPLETED: '已完成',
  OVERDUE: '已逾期',
  CANCELLED: '已取消',
  VOIDED: '已作废',
}
const executable = computed(() => detail.value
  && ['PENDING', 'IN_PROGRESS', 'OVERDUE'].includes(detail.value.task.taskStatus))

onMounted(load)

async function load() {
  loading.value = true
  try {
    const result = await inspectionApi.tasks({
      taskStatus: activeStatus.value || undefined,
      mineOnly: true,
      page: 1,
      pageSize: 100,
    })
    rows.value = result.records
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function openTask(row: TaskRow) {
  try {
    detail.value = await inspectionApi.task(row.id)
    executionRemark.value = detail.value.task.executionRemark || ''
    for (const item of detail.value.items) {
      const result = item.result
      drafts[item.id] = {
        resultCode: result?.resultCode,
        numericValue: result?.numericValue,
        textValue: result?.textValue,
        selectedValue: result?.selectedValue,
        selectedValues: parseSelected(result?.selectedValuesJson),
        abnormal: Boolean(result?.abnormalFlag),
        abnormalDescription: result?.abnormalDescription,
        skipped: Boolean(result?.skippedFlag),
        skipReason: result?.skipReason,
        attachmentIds: result?.attachmentIds || [],
        version: result?.version,
      }
    }
    executionVisible.value = true
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function save(submit: boolean) {
  if (!detail.value) return
  saving.value = true
  try {
    const payload = {
      taskVersion: detail.value.task.version,
      executionRemark: executionRemark.value || null,
      results: detail.value.items.map((item) => ({
        taskItemId: item.id,
        ...drafts[item.id],
        abnormalDescription: drafts[item.id].abnormalDescription || null,
        skipReason: drafts[item.id].skipReason || null,
      })),
    }
    if (submit) await inspectionApi.submitTask(detail.value.task.id, payload)
    else await inspectionApi.saveDraft(detail.value.task.id, payload)
    ElMessage.success(submit ? '点检结果已提交' : '草稿已保存')
    detail.value = await inspectionApi.task(detail.value.task.id)
    if (submit) executionVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function upload(itemId: number, file: File) {
  uploadingItemId.value = itemId
  try {
    const form = new FormData()
    form.append('file', file)
    const response = await systemApi.uploadAttachment(form)
    drafts[itemId].attachmentIds.push(response.data.data.id)
    ElMessage.success('现场附件已上传')
  } catch (error) {
    ElMessage.error(errorMessage(error, '附件上传失败'))
  } finally {
    uploadingItemId.value = undefined
  }
}

function uploadHandler(itemId: number) {
  return (options: UploadRequestOptions) => upload(itemId, options.file)
}

function resultOptions(item: TaskItemRow): string[] {
  if (!item.resultOptionsJson) return []
  try { return JSON.parse(item.resultOptionsJson) as string[] } catch { return [] }
}

function parseSelected(value?: string): string[] {
  if (!value) return []
  try { return JSON.parse(value) as string[] } catch { return [] }
}

function dueClass(row: TaskRow) {
  return row.taskStatus === 'OVERDUE' ? 'danger' : row.taskStatus === 'COMPLETED' ? 'success' : ''
}
</script>

<template>
  <div class="page-shell mobile-shell">
    <header class="page-header">
      <div><h1>我的点检</h1><p>面向现场手机与平板的任务执行入口，支持草稿、拍照、异常上报和断点续填。</p></div>
    </header>
    <section class="surface-card status-tabs">
      <el-radio-group v-model="activeStatus" @change="load">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="PENDING">待执行</el-radio-button>
        <el-radio-button value="IN_PROGRESS">执行中</el-radio-button>
        <el-radio-button value="OVERDUE">已逾期</el-radio-button>
        <el-radio-button value="PENDING_REVIEW">待复核</el-radio-button>
      </el-radio-group>
    </section>

    <section v-loading="loading" class="task-grid">
      <article v-for="row in rows" :key="row.id" class="surface-card task-card" @click="openTask(row)">
        <div class="task-card-head"><span class="mono">{{ row.taskCode }}</span><el-tag :type="dueClass(row)">{{ statusLabels[row.taskStatus] }}</el-tag></div>
        <h3>{{ row.equipmentName }}</h3>
        <p>{{ row.schemeNameSnapshot }}</p>
        <div class="task-meta"><span>{{ row.locationName }}</span><span>截止 {{ row.dueTime.replace('T', ' ') }}</span></div>
        <el-progress :percentage="row.itemCount ? Math.round(row.completedItemCount * 100 / row.itemCount) : 0" :status="row.taskStatus === 'COMPLETED' ? 'success' : undefined" />
      </article>
      <el-empty v-if="!loading && !rows.length" description="当前没有点检任务" />
    </section>

    <el-drawer v-model="executionVisible" direction="rtl" size="min(720px, 100vw)" :with-header="false">
      <template v-if="detail">
        <div class="execution-head">
          <div><span class="mono">{{ detail.task.taskCode }}</span><h2>{{ detail.task.equipmentName }}</h2><p>{{ detail.task.schemeNameSnapshot }} · 截止 {{ detail.task.dueTime }}</p></div>
          <el-button circle @click="executionVisible = false">×</el-button>
        </div>
        <el-alert v-if="detail.task.taskStatus === 'PENDING_REVIEW'" title="结果已提交，等待复核" type="warning" :closable="false" />
        <section v-for="(item, index) in detail.items" :key="item.id" class="inspection-card">
          <div class="inspection-index">{{ index + 1 }}</div>
          <div class="inspection-content">
            <h3>{{ item.itemName }} <el-tag v-if="item.requiredFlag" size="small">必填</el-tag></h3>
            <p>{{ item.inspectionContent }}</p>
            <div class="standard"><strong>标准：</strong>{{ item.inspectionStandard }}<template v-if="item.minimumValue != null || item.maximumValue != null">（{{ item.minimumValue ?? '—' }} ~ {{ item.maximumValue ?? '—' }} {{ item.unit }}）</template></div>
            <el-alert v-if="item.safetyNotes" :title="item.safetyNotes" type="warning" show-icon :closable="false" />
            <template v-if="drafts[item.id]">
              <el-radio-group v-if="item.resultType === 'NORMAL_ABNORMAL'" v-model="drafts[item.id].resultCode" :disabled="!executable"><el-radio-button value="NORMAL">正常</el-radio-button><el-radio-button value="ABNORMAL">异常</el-radio-button></el-radio-group>
              <el-radio-group v-else-if="item.resultType === 'PASS_FAIL'" v-model="drafts[item.id].resultCode" :disabled="!executable"><el-radio-button value="PASS">合格</el-radio-button><el-radio-button value="FAIL">不合格</el-radio-button></el-radio-group>
              <el-input-number v-else-if="item.resultType === 'NUMBER'" v-model="drafts[item.id].numericValue" :disabled="!executable" controls-position="right" /><span v-if="item.resultType === 'NUMBER'"> {{ item.unit }}</span>
              <el-input v-else-if="item.resultType === 'TEXT'" v-model="drafts[item.id].textValue" :disabled="!executable" type="textarea" />
              <el-select v-else-if="item.resultType === 'SINGLE_CHOICE'" v-model="drafts[item.id].selectedValue" :disabled="!executable"><el-option v-for="option in resultOptions(item)" :key="option" :label="option" :value="option" /></el-select>
              <el-select v-else-if="item.resultType === 'MULTIPLE_CHOICE'" v-model="drafts[item.id].selectedValues" multiple :disabled="!executable"><el-option v-for="option in resultOptions(item)" :key="option" :label="option" :value="option" /></el-select>
              <div v-if="executable" class="result-controls">
                <el-checkbox v-model="drafts[item.id].abnormal">标记异常</el-checkbox>
                <el-checkbox v-if="item.skipAllowedFlag" v-model="drafts[item.id].skipped">跳过本项</el-checkbox>
                <el-upload :show-file-list="false" :auto-upload="true" :http-request="uploadHandler(item.id)">
                  <el-button :loading="uploadingItemId === item.id" plain>{{ item.photoRequiredFlag ? '拍照/上传（必需）' : '上传附件' }}</el-button>
                </el-upload>
                <span v-if="drafts[item.id].attachmentIds.length">已上传 {{ drafts[item.id].attachmentIds.length }} 个</span>
              </div>
              <el-input v-if="drafts[item.id].abnormal" v-model="drafts[item.id].abnormalDescription" :disabled="!executable" type="textarea" placeholder="请描述异常现象" />
              <el-input v-if="drafts[item.id].skipped" v-model="drafts[item.id].skipReason" :disabled="!executable" placeholder="请填写跳过原因" />
            </template>
          </div>
        </section>
        <el-input v-if="executable" v-model="executionRemark" type="textarea" :rows="3" placeholder="执行备注" />
        <div v-if="executable" class="sticky-actions"><el-button :loading="saving" @click="save(false)">保存草稿</el-button><el-button type="primary" :loading="saving" @click="save(true)">提交点检</el-button></div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.mobile-shell { max-width: 1120px; margin: 0 auto; }
.status-tabs { overflow-x: auto; }
.task-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 14px; }
.task-card { cursor: pointer; transition: transform .15s ease, box-shadow .15s ease; }
.task-card:hover { transform: translateY(-2px); box-shadow: var(--el-box-shadow-light); }
.task-card-head, .task-meta, .execution-head, .result-controls, .sticky-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.task-card h3 { margin: 16px 0 6px; }
.task-card p, .task-meta, .execution-head p { color: var(--el-text-color-secondary); font-size: 13px; }
.task-meta { margin: 16px 0; flex-wrap: wrap; }
.inspection-card { position: relative; display: flex; gap: 14px; padding: 20px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.inspection-index { flex: 0 0 30px; height: 30px; display: grid; place-items: center; border-radius: 50%; color: white; background: var(--el-color-primary); }
.inspection-content { flex: 1; min-width: 0; display: grid; gap: 12px; }
.inspection-content h3, .inspection-content p { margin: 0; }
.standard { padding: 12px; border-radius: 8px; background: var(--el-fill-color-light); }
.result-controls { justify-content: flex-start; flex-wrap: wrap; }
.sticky-actions { position: sticky; bottom: 0; justify-content: flex-end; padding: 16px 0; background: var(--el-bg-color); z-index: 2; }
@media (max-width: 600px) { .task-grid { grid-template-columns: 1fr; } .execution-head { align-items: flex-start; } }
</style>
