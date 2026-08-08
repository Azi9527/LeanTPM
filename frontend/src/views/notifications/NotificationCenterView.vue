<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  notificationApi,
  type NotificationBusinessAttachmentDetail,
  type NotificationBusinessDetail,
  type NotificationBusinessItemDetail,
  type NotificationMessage,
} from '@/api/notification'
import { errorMessage } from '@/utils/http'

const loading = ref(false)
const detailLoading = ref(false)
const detailVisible = ref(false)
const detail = ref<NotificationBusinessDetail>()
const attachmentLoading = ref(false)
const attachmentUrls = ref<Record<number, string>>({})
const previewImages = computed(() => Object.values(attachmentUrls.value))
const rows = ref<NotificationMessage[]>([])
const total = ref(0)
const query = reactive({ unreadOnly: false, page: 1, pageSize: 100 })

onMounted(load)
onBeforeUnmount(clearAttachmentUrls)
watch(detailVisible, (visible) => {
  if (!visible) clearAttachmentUrls()
})

async function load() {
  loading.value = true
  try {
    const result = await notificationApi.messages(query)
    rows.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(errorMessage(error, '消息加载失败'))
  } finally {
    loading.value = false
  }
}

async function markRead(row: NotificationMessage) {
  try {
    await notificationApi.read(row.id)
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error, '消息已读操作失败'))
  }
}

async function acknowledge(row: NotificationMessage) {
  try {
    await notificationApi.acknowledge(row.id)
    ElMessage.success('消息已确认')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error, '消息确认失败'))
  }
}

async function openBusiness(row: NotificationMessage) {
  clearAttachmentUrls()
  detailVisible.value = true
  detailLoading.value = true
  detail.value = undefined
  try {
    if (!row.readTime) {
      await notificationApi.read(row.id)
      row.readTime = new Date().toISOString()
    }
    const loaded = await notificationApi.businessDetail(row.id)
    detail.value = loaded
    await loadAttachmentPreviews(row.id, loaded.attachments)
  } catch (error) {
    detailVisible.value = false
    ElMessage.error(errorMessage(error, '任务详情加载失败'))
  } finally {
    detailLoading.value = false
  }
}

function isImageAttachment(attachment: NotificationBusinessAttachmentDetail) {
  return attachment.contentType?.toLowerCase().startsWith('image/')
    || /^(jpg|jpeg|png|gif|webp|bmp)$/i.test(attachment.extension || '')
}

async function loadAttachmentPreviews(
  messageId: number,
  attachments: NotificationBusinessAttachmentDetail[],
) {
  const images = attachments.filter(isImageAttachment)
  if (!images.length) return
  attachmentLoading.value = true
  try {
    const entries = await Promise.all(images.map(async (attachment) => {
      try {
        const blob = await notificationApi.businessAttachmentContent(messageId, attachment.id)
        return [attachment.id, URL.createObjectURL(blob)] as const
      } catch {
        return undefined
      }
    }))
    attachmentUrls.value = Object.fromEntries(entries.filter(Boolean) as Array<readonly [number, string]>)
  } finally {
    attachmentLoading.value = false
  }
}

function clearAttachmentUrls() {
  Object.values(attachmentUrls.value).forEach((url) => URL.revokeObjectURL(url))
  attachmentUrls.value = {}
}

async function downloadAttachment(attachment: NotificationBusinessAttachmentDetail) {
  if (!detail.value) return
  try {
    const blob = await notificationApi.businessAttachmentContent(detail.value.messageId, attachment.id)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = attachment.originalName
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    ElMessage.error(errorMessage(error, '附件下载失败'))
  }
}

function fileSize(value: number) {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

function dateTime(value?: string) {
  return value?.replace('T', ' ').slice(0, 19) || '—'
}

function rowClassName({ row }: { row: NotificationMessage }) {
  return row.readTime ? '' : 'unread-row'
}

function businessLabel(value?: string) {
  return value === 'MAINTENANCE' ? '维保任务' : '点检任务'
}

function statusLabel(value?: string) {
  return ({
    PENDING_ASSIGNMENT: '未派工', PENDING: '待执行', IN_PROGRESS: '执行中',
    PAUSED: '已暂停', PENDING_CONFIRMATION: '待确认', PENDING_REVIEW: '待复核',
    COMPLETED: '已完成', OVERDUE: '已逾期', CANCELLED: '已取消', VOIDED: '已作废',
  } as Record<string, string>)[value || ''] || value || '—'
}

function sourceLabel(value?: string) {
  return ({ PLAN: '计划生成', MANUAL: '手工创建', BACKFILL: '补录' } as Record<string, string>)[value || ''] || value || '—'
}

function resultLabel(item: NotificationBusinessItemDetail) {
  if (item.skippedFlag || item.resultCode === 'SKIPPED') return `已跳过${item.skipReason ? `：${item.skipReason}` : ''}`
  if (item.numericValue !== undefined && item.numericValue !== null) return `${item.numericValue}${item.unit || ''}`
  if (item.selectedValue) return item.selectedValue
  if (item.textValue) return item.textValue
  return ({ NORMAL: '正常', ABNORMAL: '异常', PASS: '合格', FAIL: '不合格' } as Record<string, string>)[item.resultCode || ''] || '未填写'
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div><h1>我的消息</h1><p>集中查看到期提醒、临时强提醒和逾期升级，支持已读与确认留痕。</p></div>
    </header>
    <section class="surface-card query-bar">
      <el-switch v-model="query.unreadOnly" active-text="只看未读" @change="query.page = 1; load()" />
      <el-button type="primary" plain @click="load">刷新</el-button>
    </section>
    <section class="surface-card table-card">
      <el-table v-loading="loading" :data="rows" row-key="id" :row-class-name="rowClassName">
        <el-table-column label="级别" width="90">
          <template #default="{ row }"><el-tag :type="['HIGH', 'CRITICAL'].includes(row.severity) ? 'danger' : 'warning'">{{ row.severity }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="250" show-overflow-tooltip />
        <el-table-column prop="content" label="内容" min-width="330" show-overflow-tooltip />
        <el-table-column label="产生时间" width="170"><template #default="{ row }">{{ dateTime(row.occurredTime) }}</template></el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.acknowledgedTime" type="success">已确认</el-tag>
            <el-tag v-else-if="row.readTime" type="info">已读</el-tag>
            <el-tag v-else>未读</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button v-if="!row.readTime" link type="primary" @click="markRead(row)">标记已读</el-button>
            <el-button v-if="row.acknowledgeRequired && !row.acknowledgedTime" link type="warning" @click="acknowledge(row)">确认</el-button>
            <el-button link @click="openBusiness(row)">查看详情</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无消息" /></template>
      </el-table>
      <div class="table-pagination"><el-pagination v-model:current-page="query.page" v-model:page-size="query.pageSize" :total="total" layout="total, sizes, prev, pager, next" @change="load" /></div>
    </section>

    <el-dialog v-model="detailVisible" width="min(960px, 92vw)" title="提醒任务详情" destroy-on-close>
      <div v-loading="detailLoading" class="detail-body">
        <template v-if="detail">
          <el-alert
            title="此页面仅用于查看提醒对应的任务，不提供执行、派工或处理操作。"
            type="info"
            :closable="false"
            show-icon
          />
          <el-descriptions class="detail-summary" :column="3" border>
            <el-descriptions-item label="业务类型">{{ businessLabel(detail.businessType) }}</el-descriptions-item>
            <el-descriptions-item label="任务编号">{{ detail.taskCode }}</el-descriptions-item>
            <el-descriptions-item label="任务状态">
              <el-tag>{{ statusLabel(detail.taskStatus) }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="方案名称">{{ detail.schemeName || '—' }}</el-descriptions-item>
            <el-descriptions-item label="设备">{{ detail.equipmentName }}（{{ detail.equipmentCode }}）</el-descriptions-item>
            <el-descriptions-item label="来源">{{ sourceLabel(detail.sourceType) }}</el-descriptions-item>
            <el-descriptions-item label="所属组织">{{ detail.organizationName }}</el-descriptions-item>
            <el-descriptions-item label="物理位置">{{ detail.locationName || '未设置' }}</el-descriptions-item>
            <el-descriptions-item label="执行人员">{{ detail.assigneeNames || '未派工' }}</el-descriptions-item>
            <el-descriptions-item label="计划日期">{{ detail.plannedDate }}</el-descriptions-item>
            <el-descriptions-item label="截止时间">{{ dateTime(detail.dueTime) }}</el-descriptions-item>
            <el-descriptions-item label="完成时间">{{ dateTime(detail.completedTime) }}</el-descriptions-item>
          </el-descriptions>

          <div class="detail-section-title">任务项目与结果</div>
          <el-table :data="detail.items" border max-height="430">
            <el-table-column type="index" label="#" width="52" />
            <el-table-column label="项目" min-width="150">
              <template #default="{ row }">
                <div class="item-name">{{ row.itemName }}</div>
                <small>{{ row.itemCode }}</small>
              </template>
            </el-table-column>
            <el-table-column label="部位 / 内容" min-width="190">
              <template #default="{ row }">
                <div>{{ row.itemPart || '—' }}</div>
                <small>{{ row.itemContent || '—' }}</small>
              </template>
            </el-table-column>
            <el-table-column prop="itemStandard" label="标准" min-width="180" show-overflow-tooltip />
            <el-table-column label="结果" min-width="130">
              <template #default="{ row }">
                <el-tag :type="row.abnormalFlag ? 'danger' : 'success'">{{ resultLabel(row) }}</el-tag>
                <div v-if="row.abnormalDescription" class="abnormal-description">{{ row.abnormalDescription }}</div>
              </template>
            </el-table-column>
            <el-table-column label="执行记录" min-width="150">
              <template #default="{ row }">
                <div>{{ row.executedByName || '—' }}</div>
                <small>{{ dateTime(row.executedTime) }}</small>
              </template>
            </el-table-column>
            <template #empty><el-empty description="暂无任务项目" /></template>
          </el-table>

          <template v-if="detail.attachments.length">
            <div class="detail-section-title attachment-title">
              现场图片与附件（{{ detail.attachments.length }}）
            </div>
            <div v-loading="attachmentLoading" class="attachment-grid">
              <article v-for="attachment in detail.attachments" :key="attachment.id" class="attachment-card">
                <el-image
                  v-if="isImageAttachment(attachment) && attachmentUrls[attachment.id]"
                  class="attachment-image"
                  :src="attachmentUrls[attachment.id]"
                  :preview-src-list="previewImages"
                  :initial-index="Math.max(0, previewImages.indexOf(attachmentUrls[attachment.id]))"
                  fit="cover"
                  preview-teleported
                />
                <div v-else class="attachment-file" @click="downloadAttachment(attachment)">
                  <span>{{ isImageAttachment(attachment) ? '图片加载失败' : '文件附件' }}</span>
                </div>
                <div class="attachment-meta">
                  <strong>{{ attachment.itemName || '任务附件' }}</strong>
                  <span :title="attachment.originalName">{{ attachment.originalName }}</span>
                  <small>{{ fileSize(attachment.fileSize) }} · {{ dateTime(attachment.createdTime) }}</small>
                  <el-button link type="primary" @click="downloadAttachment(attachment)">下载原文件</el-button>
                </div>
              </article>
            </div>
          </template>
        </template>
      </div>
      <template #footer><el-button type="primary" @click="detailVisible = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
:deep(.unread-row) { font-weight: 650; background: #f0f9fb; }
.detail-body { min-height: 160px; }
.detail-summary { margin: 16px 0 20px; }
.detail-section-title { margin-bottom: 10px; color: #18373d; font-size: 15px; font-weight: 700; }
.item-name { font-weight: 650; }
.detail-body small { color: #819095; }
.abnormal-description { margin-top: 6px; color: #c03639; font-size: 12px; line-height: 1.45; }
.attachment-title { margin-top: 22px; }
.attachment-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 14px; min-height: 80px; }
.attachment-card { overflow: hidden; border: 1px solid #dce5e4; border-radius: 10px; background: #fff; }
.attachment-image, .attachment-file { display: flex; width: 100%; height: 142px; align-items: center; justify-content: center; background: #edf3f2; cursor: pointer; }
.attachment-file { color: #70817f; font-weight: 650; }
.attachment-meta { display: flex; padding: 10px 12px; flex-direction: column; gap: 4px; }
.attachment-meta span { overflow: hidden; color: #61716f; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.attachment-meta .el-button { align-self: flex-start; margin: 2px 0 0; padding: 0; }
</style>
