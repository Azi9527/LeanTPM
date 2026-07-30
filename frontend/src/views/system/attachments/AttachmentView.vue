<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { systemApi, type AttachmentRow } from '@/api/system'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const uploading = ref(false)
const fileInput = ref<HTMLInputElement>()
const rows = ref<AttachmentRow[]>([])
const total = ref(0)
const query = reactive({ keyword: '', page: 1, pageSize: 20 })

onMounted(load)

async function load() {
  loading.value = true
  try {
    const result = await systemApi.attachments(query)
    rows.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function upload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (file.size > 20 * 1024 * 1024) {
    ElMessage.warning('文件不能超过 20MB')
    input.value = ''
    return
  }
  const formData = new FormData()
  formData.append('file', file)
  uploading.value = true
  try {
    await systemApi.uploadAttachment(formData)
    ElMessage.success('附件上传成功')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error, '附件上传失败'))
  } finally {
    uploading.value = false
    input.value = ''
  }
}

async function download(row: AttachmentRow) {
  try {
    const response = await systemApi.downloadAttachment(row.id)
    const url = URL.createObjectURL(response.data)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = row.originalName
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    ElMessage.error(errorMessage(error, '附件下载失败'))
  }
}

function sizeLabel(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div><h1>附件管理</h1><p>统一管理设备图片、说明书、表单附件和三维模型文件。</p></div>
      <div class="page-actions">
        <input ref="fileInput" class="file-input" type="file" @change="upload" />
        <el-button v-if="auth.can('system:attachment:upload')" type="primary" :loading="uploading" @click="fileInput?.click()">上传附件</el-button>
      </div>
    </header>
    <el-alert title="当前使用本地安全文件目录；附件元数据和 SHA-256 摘要已写入数据库，可平滑切换对象存储。" type="info" show-icon :closable="false" />
    <section class="surface-card query-bar">
      <el-input v-model="query.keyword" clearable placeholder="搜索文件名" style="width: 260px" @keyup.enter="load"><template #prefix><el-icon><Search /></el-icon></template></el-input>
      <el-button type="primary" plain @click="query.page = 1; load()">查询</el-button>
    </section>
    <section class="surface-card table-card">
      <div class="table-toolbar"><span class="table-title">附件列表</span><span class="dict-code">共 {{ total }} 个文件</span></div>
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="originalName" label="文件名" min-width="240"><template #default="{ row }"><div class="file-name"><el-icon><Document /></el-icon><span>{{ row.originalName }}</span></div></template></el-table-column>
        <el-table-column prop="extension" label="类型" width="90"><template #default="{ row }"><el-tag effect="plain">{{ row.extension.toUpperCase() }}</el-tag></template></el-table-column>
        <el-table-column label="大小" width="110"><template #default="{ row }">{{ sizeLabel(row.fileSize) }}</template></el-table-column>
        <el-table-column prop="businessType" label="业务类型" min-width="130"><template #default="{ row }">{{ row.businessType || '通用附件' }}</template></el-table-column>
        <el-table-column label="SHA-256" min-width="180" show-overflow-tooltip><template #default="{ row }"><span class="mono">{{ row.sha256 }}</span></template></el-table-column>
        <el-table-column label="上传时间" min-width="170"><template #default="{ row }">{{ row.createdTime.replace('T', ' ') }}</template></el-table-column>
        <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="download(row)">下载</el-button></template></el-table-column>
        <template #empty><el-empty description="暂无附件" /></template>
      </el-table>
      <div class="table-pagination"><el-pagination v-model:current-page="query.page" v-model:page-size="query.pageSize" :total="total" layout="total, prev, pager, next" @change="load" /></div>
    </section>
  </div>
</template>

<style scoped lang="scss">
.file-input { display: none; }
.file-name { display: flex; align-items: center; gap: 8px; }
.file-name .el-icon { color: var(--tpm-primary); }
.dict-code { color: var(--tpm-text-secondary); font-size: 12px; }
</style>
