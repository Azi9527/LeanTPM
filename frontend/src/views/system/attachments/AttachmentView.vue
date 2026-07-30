<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { systemApi, type AttachmentRelationRow, type AttachmentRow } from '@/api/system'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const uploading = ref(false)
const relationSaving = ref(false)
const relationDialogVisible = ref(false)
const selected = ref<AttachmentRow | null>(null)
const fileInput = ref<HTMLInputElement>()
const rows = ref<AttachmentRow[]>([])
const total = ref(0)
const query = reactive({ keyword: '', page: 1, pageSize: 20 })
const relationForm = reactive({
  businessType: 'EQUIPMENT',
  businessId: undefined as number | undefined,
  relationType: 'DOCUMENT',
  sortOrder: 0,
  remark: '',
})

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

function openRelation(row: AttachmentRow) {
  selected.value = row
  Object.assign(relationForm, {
    businessType: 'EQUIPMENT',
    businessId: undefined,
    relationType: 'DOCUMENT',
    sortOrder: 0,
    remark: '',
  })
  relationDialogVisible.value = true
}

async function saveRelation() {
  if (!selected.value || !relationForm.businessId) {
    ElMessage.warning('请输入有效的业务 ID')
    return
  }
  relationSaving.value = true
  try {
    await systemApi.addAttachmentRelation(selected.value.id, {
      ...relationForm,
      businessType: relationForm.businessType.trim().toUpperCase(),
    })
    ElMessage.success('附件关系已绑定')
    relationDialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error, '附件关系绑定失败'))
  } finally {
    relationSaving.value = false
  }
}

async function removeRelation(relation: AttachmentRelationRow) {
  await ElMessageBox.confirm(
    `确认解除 ${relation.businessType} #${relation.businessId} 的附件关系吗？`,
    '解除附件关系',
    { type: 'warning', confirmButtonText: '确认解绑', cancelButtonText: '取消' },
  )
  try {
    await systemApi.removeAttachmentRelation(relation.id)
    ElMessage.success('附件关系已解除')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error, '附件关系解除失败'))
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
        <el-table-column label="业务关系" min-width="260">
          <template #default="{ row }">
            <template v-if="row.relations.length">
              <el-tag
                v-for="relation in row.relations"
                :key="relation.id"
                class="relation-tag"
                effect="plain"
                :closable="auth.can('system:attachment:relation')"
                @close="removeRelation(relation)"
              >
                {{ relation.businessType }} #{{ relation.businessId }} · {{ relation.relationType }}
              </el-tag>
            </template>
            <span v-else class="muted">尚未绑定</span>
          </template>
        </el-table-column>
        <el-table-column label="SHA-256" min-width="180" show-overflow-tooltip><template #default="{ row }"><span class="mono">{{ row.sha256 }}</span></template></el-table-column>
        <el-table-column label="上传时间" min-width="170"><template #default="{ row }">{{ row.createdTime.replace('T', ' ') }}</template></el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="download(row)">下载</el-button>
            <el-button
              v-if="auth.can('system:attachment:relation')"
              link
              type="primary"
              @click="openRelation(row)"
            >
              绑定
            </el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无附件" /></template>
      </el-table>
      <div class="table-pagination"><el-pagination v-model:current-page="query.page" v-model:page-size="query.pageSize" :total="total" layout="total, prev, pager, next" @change="load" /></div>
    </section>

    <el-dialog
      v-model="relationDialogVisible"
      :title="`绑定附件关系 · ${selected?.originalName || ''}`"
      width="min(560px, 94vw)"
      destroy-on-close
    >
      <el-form label-position="top">
        <el-form-item label="业务类型" required>
          <el-input
            v-model="relationForm.businessType"
            maxlength="64"
            placeholder="例如 EQUIPMENT、INSPECTION_TASK"
          />
        </el-form-item>
        <el-form-item label="业务 ID" required>
          <el-input-number
            v-model="relationForm.businessId"
            :min="1"
            :precision="0"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="关系类型" required>
          <el-select v-model="relationForm.relationType" style="width: 100%">
            <el-option label="图片" value="IMAGE" />
            <el-option label="文档" value="DOCUMENT" />
            <el-option label="三维模型" value="MODEL" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="relationForm.sortOrder" :min="0" :max="9999" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="relationForm.remark" type="textarea" :rows="3" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="relationDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="relationSaving" @click="saveRelation">确认绑定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.file-input { display: none; }
.file-name { display: flex; align-items: center; gap: 8px; }
.file-name .el-icon { color: var(--tpm-primary); }
.dict-code { color: var(--tpm-text-secondary); font-size: 12px; }
.relation-tag { margin: 2px 4px 2px 0; }
.muted { color: var(--tpm-text-secondary); }
</style>
