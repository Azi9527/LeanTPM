<script setup lang="ts">
import { onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { InspectionAttachmentRow } from '@/api/inspection'
import { errorMessage } from '@/utils/http'

const props = withDefaults(defineProps<{
  attachments: InspectionAttachmentRow[]
  loadContent: (attachmentId: number) => Promise<Blob>
  emptyText?: string
}>(), {
  emptyText: '暂无附件',
})

const imageUrls = reactive<Record<number, string>>({})
const loadingIds = reactive<Record<number, boolean>>({})
const previewVisible = ref(false)
const previewUrl = ref('')
const previewName = ref('')

watch(
  () => props.attachments,
  async (attachments) => {
    clearImageUrls()
    await Promise.all(attachments.filter(isImage).map(loadImage))
  },
  { immediate: true },
)

onBeforeUnmount(clearImageUrls)

function isImage(attachment: InspectionAttachmentRow) {
  return attachment.contentType?.startsWith('image/')
    || ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp'].includes(
      attachment.extension.toLowerCase(),
    )
}

async function loadImage(attachment: InspectionAttachmentRow) {
  if (imageUrls[attachment.id] || loadingIds[attachment.id]) return
  loadingIds[attachment.id] = true
  try {
    const blob = await props.loadContent(attachment.id)
    imageUrls[attachment.id] = URL.createObjectURL(blob)
  } catch {
    // Keep the file card available for an explicit retry.
  } finally {
    loadingIds[attachment.id] = false
  }
}

async function preview(attachment: InspectionAttachmentRow) {
  try {
    if (!imageUrls[attachment.id]) await loadImage(attachment)
    if (!imageUrls[attachment.id]) throw new Error('图片加载失败')
    previewName.value = attachment.originalName
    previewUrl.value = imageUrls[attachment.id]
    previewVisible.value = true
  } catch (error) {
    ElMessage.error(errorMessage(error, '图片预览失败'))
  }
}

async function download(attachment: InspectionAttachmentRow) {
  loadingIds[attachment.id] = true
  try {
    const blob = await props.loadContent(attachment.id)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = attachment.originalName
    anchor.click()
    setTimeout(() => URL.revokeObjectURL(url), 0)
  } catch (error) {
    ElMessage.error(errorMessage(error, '附件下载失败'))
  } finally {
    loadingIds[attachment.id] = false
  }
}

function clearImageUrls() {
  Object.values(imageUrls).forEach(URL.revokeObjectURL)
  Object.keys(imageUrls).forEach((key) => delete imageUrls[Number(key)])
}

function sizeLabel(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
</script>

<template>
  <div v-if="attachments.length" class="attachment-list">
    <article v-for="attachment in attachments" :key="attachment.id" class="attachment-card">
      <button
        v-if="isImage(attachment)"
        class="image-preview"
        type="button"
        :aria-label="`预览 ${attachment.originalName}`"
        @click="preview(attachment)"
      >
        <img v-if="imageUrls[attachment.id]" :src="imageUrls[attachment.id]" :alt="attachment.originalName">
        <el-icon v-else class="file-icon"><Picture /></el-icon>
      </button>
      <div v-else class="document-preview"><el-icon class="file-icon"><Document /></el-icon></div>
      <div class="attachment-info">
        <strong :title="attachment.originalName">{{ attachment.originalName }}</strong>
        <span>{{ sizeLabel(attachment.fileSize) }} · {{ attachment.extension.toUpperCase() }}</span>
      </div>
      <el-button
        circle
        plain
        :loading="loadingIds[attachment.id]"
        :aria-label="`下载 ${attachment.originalName}`"
        @click="download(attachment)"
      >
        <el-icon><Download /></el-icon>
      </el-button>
    </article>
  </div>
  <el-empty v-else :description="emptyText" :image-size="54" />

  <el-dialog v-model="previewVisible" :title="previewName" width="min(920px, 96vw)" append-to-body>
    <img v-if="previewUrl" class="preview-image" :src="previewUrl" :alt="previewName">
  </el-dialog>
</template>

<style scoped>
.attachment-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 10px; }
.attachment-card { display: grid; grid-template-columns: 52px minmax(0, 1fr) 34px; gap: 10px; align-items: center; padding: 9px; border: 1px solid var(--el-border-color-lighter); border-radius: 9px; background: var(--el-bg-color); }
.image-preview, .document-preview { width: 52px; height: 52px; display: grid; place-items: center; padding: 0; overflow: hidden; border: 0; border-radius: 7px; background: var(--el-fill-color-light); color: var(--el-color-primary); }
.image-preview { cursor: zoom-in; }
.image-preview img { width: 100%; height: 100%; object-fit: cover; }
.file-icon { font-size: 24px; }
.attachment-info { min-width: 0; display: grid; gap: 5px; }
.attachment-info strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }
.attachment-info span { color: var(--el-text-color-secondary); font-size: 12px; }
.preview-image { display: block; max-width: 100%; max-height: 72vh; margin: 0 auto; object-fit: contain; }
:deep(.el-empty) { padding: 8px 0; }
</style>
