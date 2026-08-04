<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import { useRoute } from 'vue-router'
import {
  inspectionApi,
  type InspectionAttachmentRow,
  type TaskDetail,
  type TaskItemRow,
  type TaskRow,
  type TaskStatus,
} from '@/api/inspection'
import { systemApi } from '@/api/system'
import InspectionAttachmentList from '@/components/InspectionAttachmentList.vue'
import {
  loadMobileDraft,
  newIdempotencyKey,
  removeMobileDraft,
  saveMobileDraft,
} from '@/mobile/draftStore'
import { capturePhotoEvidence } from '@/mobile/device'
import { enqueuePhoto } from '@/mobile/photoQueue'
import { uploadPhotoEvidence } from '@/mobile/photoEvidence'
import { useAuthStore } from '@/stores/auth'
import { useMobileStore } from '@/stores/mobile'
import { useBranding } from '@/branding/branding'
import { errorMessage } from '@/utils/http'

interface ResultDraft {
  resultCode?: string
  numericValue?: number
  textValue?: string
  selectedValue?: string
  selectedValues: string[]
  abnormal: boolean
  abnormalDescription?: string
  equipmentStopRequired: boolean
  stopOverrideReason?: string
  skipped: boolean
  skipReason?: string
  attachmentIds: number[]
  version?: number
}

interface SavePayload {
  taskVersion: number
  executionRemark: string | null
  results: Array<ResultDraft & { taskItemId: number }>
}

const route = useRoute()
const auth = useAuthStore()
const mobile = useMobileStore()
const branding = useBranding()
const loading = ref(false)
const saving = ref(false)
const uploadingItemId = ref<number>()
const rows = ref<TaskRow[]>([])
const activeStatus = ref<TaskStatus | ''>('')
const detail = ref<TaskDetail | null>(null)
const taskAttachments = ref<InspectionAttachmentRow[]>([])
const executionVisible = ref(false)
const executionRemark = ref('')
const drafts = reactive<Record<number, ResultDraft>>({})
const localSavedAt = ref('')
const pendingSubmit = ref(false)
const idempotencyKey = ref('')
const localAttachmentBlobs = new Map<number, Blob>()
let autosaveTimer: ReturnType<typeof setTimeout> | undefined
let restoring = false
let routeTaskOpened = false

const statusLabels: Record<TaskStatus, string> = {
  PENDING: '待执行',
  IN_PROGRESS: '执行中',
  PENDING_REVIEW: '已完成',
  COMPLETED: '已完成',
  OVERDUE: '已逾期',
  CANCELLED: '已取消',
  VOIDED: '已作废',
}
const executable = computed(() => detail.value
  && ['PENDING', 'IN_PROGRESS', 'OVERDUE'].includes(detail.value.task.taskStatus))

onMounted(async () => {
  await load()
  const taskId = Number(route.query.taskId)
  if (Number.isSafeInteger(taskId) && taskId > 0 && !routeTaskOpened) {
    routeTaskOpened = true
    await openTask({ id: taskId })
  }
})

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

async function openTask(row: Pick<TaskRow, 'id'>) {
  try {
    restoring = true
    taskAttachments.value = []
    localAttachmentBlobs.clear()
    detail.value = await inspectionApi.task(row.id)
    try {
      taskAttachments.value = await inspectionApi.taskAttachments(row.id)
    } catch (error) {
      ElMessage.warning(errorMessage(error, '附件列表加载失败'))
    }
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
        equipmentStopRequired: result?.equipmentStopRequired
          ?? item.abnormalDefaultStopFlag,
        stopOverrideReason: result?.stopOverrideReason,
        skipped: Boolean(result?.skippedFlag),
        skipReason: result?.skipReason,
        attachmentIds: result?.attachmentIds || [],
        version: result?.version,
      }
    }
    const local = await loadMobileDraft<SavePayload>('inspection', row.id)
    if (local && local.taskVersion === detail.value.task.version) {
      applyPayload(local.payload)
      pendingSubmit.value = local.pendingSubmit
      idempotencyKey.value = local.idempotencyKey
      localSavedAt.value = local.updatedAt
      ElMessage.info(local.pendingSubmit ? '已恢复待提交的本地草稿' : '已恢复本地加密草稿')
    } else {
      if (local) {
        await removeMobileDraft('inspection', row.id)
        ElMessage.warning('任务版本已变化，旧的本地草稿已安全清除')
      }
      idempotencyKey.value = newIdempotencyKey('inspection', row.id)
      pendingSubmit.value = false
      localSavedAt.value = ''
    }
    executionVisible.value = true
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    restoring = false
  }
}

function buildPayload(): SavePayload | null {
  if (!detail.value) return null
  return {
    taskVersion: detail.value.task.version,
    executionRemark: executionRemark.value || null,
    results: detail.value.items.map((item) => ({
      taskItemId: item.id,
      ...drafts[item.id],
      abnormalDescription: drafts[item.id].abnormalDescription || undefined,
      stopOverrideReason: drafts[item.id].stopOverrideReason || undefined,
      skipReason: drafts[item.id].skipReason || undefined,
    })),
  }
}

function toggleAbnormal(item: TaskItemRow, value: boolean) {
  if (value) {
    drafts[item.id].equipmentStopRequired = item.abnormalDefaultStopFlag
    drafts[item.id].stopOverrideReason = undefined
  } else {
    drafts[item.id].equipmentStopRequired = false
    drafts[item.id].stopOverrideReason = undefined
  }
}

function applyPayload(payload: SavePayload) {
  executionRemark.value = payload.executionRemark || ''
  for (const result of payload.results) {
    if (!drafts[result.taskItemId]) continue
    Object.assign(drafts[result.taskItemId], result)
  }
}

async function persistLocal(submit: boolean): Promise<void> {
  const payload = buildPayload()
  if (!detail.value || !payload || !executable.value) return
  const updatedAt = new Date().toISOString()
  await saveMobileDraft({
    schemaVersion: 1,
    workflow: 'inspection',
    taskId: detail.value.task.id,
    taskVersion: detail.value.task.version,
    updatedAt,
    pendingSubmit: submit,
    idempotencyKey: idempotencyKey.value
      || newIdempotencyKey('inspection', detail.value.task.id),
    payload,
  })
  pendingSubmit.value = submit
  localSavedAt.value = updatedAt
  await mobile.refreshDraftCount()
}

function scheduleLocalSave() {
  if (restoring || !executionVisible.value || !executable.value) return
  if (autosaveTimer) clearTimeout(autosaveTimer)
  autosaveTimer = setTimeout(() => {
    void persistLocal(pendingSubmit.value)
  }, 700)
}

watch(drafts, scheduleLocalSave, { deep: true })
watch(executionRemark, scheduleLocalSave)
watch(executionVisible, (visible) => {
  if (!visible) void persistLocal(pendingSubmit.value)
})

onBeforeUnmount(() => {
  if (autosaveTimer) clearTimeout(autosaveTimer)
  void persistLocal(pendingSubmit.value)
})

async function save(submit: boolean) {
  if (!detail.value) return
  saving.value = true
  try {
    const payload = buildPayload()
    if (!payload) return
    await persistLocal(submit)
    if (!mobile.online) {
      ElMessage.warning(submit
        ? '当前离线，结果已加密排队；恢复网络后将自动提交'
        : '当前离线，草稿已加密保存在本机')
      return
    }
    if (submit) {
      await inspectionApi.submitTask(
        detail.value.task.id,
        payload,
        idempotencyKey.value,
      )
    }
    else await inspectionApi.saveDraft(detail.value.task.id, payload)
    ElMessage.success(submit ? '点检任务已完成' : '草稿已保存')
    await removeMobileDraft('inspection', detail.value.task.id)
    pendingSubmit.value = false
    localSavedAt.value = ''
    await mobile.refreshDraftCount()
    const savedTaskId = detail.value.task.id
    detail.value = await inspectionApi.task(savedTaskId)
    try {
      taskAttachments.value = await inspectionApi.taskAttachments(savedTaskId)
    } catch (error) {
      ElMessage.warning(errorMessage(error, '附件列表刷新失败'))
    }
    localAttachmentBlobs.clear()
    if (submit) executionVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(`${errorMessage(error)}；本地加密草稿仍保留`)
  } finally {
    saving.value = false
  }
}

async function upload(itemId: number, file: File) {
  const item = detail.value?.items.find((candidate) => candidate.id === itemId)
  if (!item) return
  if (!mobile.online) {
    ElMessage.warning('当前离线，附件需恢复网络后上传；文字结果已自动保存')
    return
  }
  if (drafts[itemId].attachmentIds.length >= item.photoMaxCount) {
    ElMessage.warning(`该项目最多上传 ${item.photoMaxCount} 张照片`)
    return
  }
  const maxSizeMb = Math.min(mobile.maxUploadMb, item.photoMaxSizeMb)
  if (file.size > maxSizeMb * 1024 * 1024) {
    ElMessage.warning(`单张照片不能超过 ${maxSizeMb} MB`)
    return
  }
  const allowedTypes = item.photoAllowedTypes.split(',').map((value) => value.trim().toLowerCase())
  if (!file.type || !allowedTypes.includes(file.type.toLowerCase())) {
    ElMessage.warning(`仅支持 ${item.photoAllowedTypes}`)
    return
  }
  uploadingItemId.value = itemId
  try {
    const form = new FormData()
    form.append('file', file)
    const response = await systemApi.uploadAttachment(form)
    const attachment = response.data.data
    drafts[itemId].attachmentIds.push(attachment.id)
    localAttachmentBlobs.set(attachment.id, file)
    taskAttachments.value.push({
      id: attachment.id,
      taskItemId: itemId,
      originalName: attachment.originalName,
      contentType: attachment.contentType,
      extension: attachment.extension,
      fileSize: attachment.fileSize,
      attachmentType: 'RESULT_PHOTO',
      createdTime: attachment.createdTime,
    })
    ElMessage.success('现场附件已上传')
  } catch (error) {
    ElMessage.error(errorMessage(error, '附件上传失败'))
  } finally {
    uploadingItemId.value = undefined
  }
}

async function capture(itemId: number) {
  if (!detail.value || !mobile.bootstrap) return
  try {
    const item = detail.value.items.find((candidate) => candidate.id === itemId)
    if (!item) return
    if (drafts[itemId].attachmentIds.length >= item.photoMaxCount) {
      ElMessage.warning(`该项目最多上传 ${item.photoMaxCount} 张照片`)
      return
    }
    const capture = await capturePhotoEvidence(
      `inspection-${detail.value.task.taskCode}-${itemId}`,
      Math.min(mobile.maxUploadMb, item.photoMaxSizeMb),
      {
        workflowType: 'INSPECTION', taskId: detail.value.task.id, taskItemId: itemId,
        taskCode: detail.value.task.taskCode,
        equipmentCode: detail.value.task.equipmentCode,
        equipmentName: detail.value.task.equipmentName,
        itemName: item.itemName,
        executorName: auth.displayName,
        serverTime: mobile.estimatedServerTime(),
        brandName: branding.shortName,
        faultLocationText: item.inspectionPart || detail.value.task.locationName || item.itemName,
        photoCompressionQuality: item.photoCompressionQuality,
      },
    )
    if (!mobile.online) {
      await enqueuePhoto('inspection', 'attachmentIds', capture)
      await persistLocal(false)
      await mobile.refreshDraftCount()
      ElMessage.success('水印照片已加密存入本地队列，联网后自动上传')
      return
    }
    uploadingItemId.value = itemId
    const uploaded = await uploadPhotoEvidence(capture)
    drafts[itemId].attachmentIds.push(uploaded.attachmentId)
    localAttachmentBlobs.set(uploaded.attachmentId, capture.watermarkedFile)
    taskAttachments.value.push({
      id: uploaded.attachmentId, taskItemId: itemId,
      originalName: capture.watermarkedFile.name, contentType: capture.watermarkedFile.type,
      extension: 'jpeg', fileSize: capture.watermarkedFile.size,
      attachmentType: 'RESULT_PHOTO', createdTime: new Date().toISOString(),
    })
    if (uploaded.evidence.clockSkewWarning) {
      ElMessage.warning('设备时间与服务端偏差较大，照片已标记时钟告警')
    } else ElMessage.success('设备位置水印照片已上传')
  } catch (error) {
    ElMessage.warning(errorMessage(error, '拍照已取消'))
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

function attachmentsForItem(itemId: number) {
  return taskAttachments.value.filter((attachment) => attachment.taskItemId === itemId)
}

async function loadAttachmentContent(attachmentId: number) {
  const local = localAttachmentBlobs.get(attachmentId)
  if (local) return local
  if (!detail.value) throw new Error('点检任务尚未打开')
  return inspectionApi.taskAttachmentContent(detail.value.task.id, attachmentId)
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
        <el-alert v-if="['COMPLETED', 'PENDING_REVIEW'].includes(detail.task.taskStatus)" title="任务已完成，当前为只读结果" type="success" :closable="false" />
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
                <el-checkbox v-model="drafts[item.id].abnormal" @change="(value: boolean) => toggleAbnormal(item, value)">标记异常</el-checkbox>
                <el-checkbox v-if="item.skipAllowedFlag" v-model="drafts[item.id].skipped">跳过本项</el-checkbox>
                <el-upload :show-file-list="false" :auto-upload="true" :accept="item.photoAllowedTypes" :http-request="uploadHandler(item.id)">
                  <el-button :loading="uploadingItemId === item.id" plain>{{ item.photoRequiredFlag ? '拍照/上传（必需）' : '上传附件' }}</el-button>
                </el-upload>
                <el-button :loading="uploadingItemId === item.id" plain @click="capture(item.id)">
                  <el-icon><Camera /></el-icon>现场拍照
                </el-button>
                <span>照片 {{ drafts[item.id].attachmentIds.length }}/{{ item.photoMaxCount }}，至少 {{ item.photoMinCount }} 张</span>
              </div>
              <div v-if="attachmentsForItem(item.id).length" class="item-attachments">
                <div class="attachment-title">现场附件（{{ attachmentsForItem(item.id).length }}）</div>
                <InspectionAttachmentList
                  :attachments="attachmentsForItem(item.id)"
                  :load-content="loadAttachmentContent"
                />
              </div>
              <el-input v-if="drafts[item.id].abnormal" v-model="drafts[item.id].abnormalDescription" :disabled="!executable" type="textarea" placeholder="请描述异常现象" />
              <div v-if="drafts[item.id].abnormal" class="stop-decision">
                <el-switch v-model="drafts[item.id].equipmentStopRequired" :disabled="!executable" active-text="设备需要停机" inactive-text="设备无需停机" />
                <el-tag size="small" type="info">项目默认：{{ item.abnormalDefaultStopFlag ? '停机' : '不停机' }}</el-tag>
                <el-input
                  v-if="drafts[item.id].equipmentStopRequired !== item.abnormalDefaultStopFlag"
                  v-model="drafts[item.id].stopOverrideReason"
                  :disabled="!executable"
                  placeholder="与默认停机规则不同，请填写调整原因"
                />
              </div>
              <el-input v-if="drafts[item.id].skipped" v-model="drafts[item.id].skipReason" :disabled="!executable" placeholder="请填写跳过原因" />
            </template>
          </div>
        </section>
        <el-input v-if="executable" v-model="executionRemark" type="textarea" :rows="3" placeholder="执行备注" />
        <div v-if="executable && localSavedAt" class="local-draft-state">
          <el-icon><Lock /></el-icon>
          {{ pendingSubmit ? '待恢复网络后提交' : '本地草稿已加密保存' }}
          · {{ localSavedAt.replace('T', ' ').slice(0, 19) }}
        </div>
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
.stop-decision { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 12px; border-radius: 8px; background: var(--el-fill-color-light); }
.stop-decision .el-input { flex: 1 1 280px; }
.item-attachments { display: grid; gap: 8px; padding: 12px; border-radius: 8px; background: var(--el-fill-color-extra-light); }
.attachment-title { color: var(--el-text-color-regular); font-size: 13px; font-weight: 600; }
.sticky-actions { position: sticky; bottom: 0; justify-content: flex-end; padding: 16px 0; background: var(--el-bg-color); z-index: 2; }
.local-draft-state { display: flex; align-items: center; gap: 7px; margin-top: 14px; color: #53717c; font-size: 12px; }
@media (max-width: 600px) {
  .mobile-shell { padding: 0; }
  .page-header p { display: none; }
  .task-grid { grid-template-columns: 1fr; }
  .execution-head { align-items: flex-start; }
  .sticky-actions > * { flex: 1; min-height: 48px; }
}
</style>
