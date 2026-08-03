<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  inspectionApi,
  type InspectionImportResult,
} from '@/api/inspection'
import { errorMessage } from '@/utils/http'

const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ committed: [] }>()

const validating = ref(false)
const committing = ref(false)
const result = ref<InspectionImportResult>()
const fileName = ref('')

watch(visible, (value) => {
  if (!value) {
    result.value = undefined
    fileName.value = ''
  }
})

async function validateFile(file: File) {
  if (!file.name.toLowerCase().endsWith('.xlsx')) {
    ElMessage.warning('请选择 .xlsx 格式的点检导入文件')
    return false
  }
  validating.value = true
  result.value = undefined
  fileName.value = file.name
  try {
    result.value = await inspectionApi.validateImport(file)
    if (result.value.status === 'VALIDATED') {
      ElMessage.success('文件校验通过，可以确认导入')
    } else {
      ElMessage.warning(`发现 ${result.value.errors.length} 个问题，请修正后重新上传`)
    }
  } catch (error) {
    ElMessage.error(errorMessage(error, '点检导入文件校验失败'))
  } finally {
    validating.value = false
  }
  return false
}

async function commit() {
  if (!result.value || result.value.status !== 'VALIDATED') return
  committing.value = true
  try {
    result.value = await inspectionApi.commitImport(result.value.batchId)
    ElMessage.success('点检项目和方案已批量导入')
    emit('committed')
    visible.value = false
  } catch (error) {
    ElMessage.error(errorMessage(error, '确认导入失败'))
  } finally {
    committing.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="批量导入点检项目与方案" width="min(980px, 96vw)">
    <el-alert
      title="请使用系统模板；上传只做校验，校验通过后点击“确认导入”才会写入数据。"
      type="info"
      :closable="false"
      show-icon
    />

    <div class="import-actions">
      <el-button @click="inspectionApi.downloadImportTemplate()">下载 Excel 模板</el-button>
      <el-upload
        :show-file-list="false"
        accept=".xlsx"
        :before-upload="validateFile"
        :disabled="validating || committing"
      >
        <el-button type="primary" :loading="validating">选择文件并校验</el-button>
      </el-upload>
      <span v-if="fileName" class="file-name">{{ fileName }}</span>
    </div>

    <template v-if="result">
      <el-result
        :icon="result.status === 'VALIDATED' ? 'success' : result.status === 'COMMITTED' ? 'success' : 'warning'"
        :title="result.status === 'VALIDATED' ? '校验通过' : result.status === 'COMMITTED' ? '导入完成' : '校验未通过'"
        :sub-title="`批次：${result.batchId}`"
      />
      <el-descriptions :column="4" border>
        <el-descriptions-item label="点检项目">{{ result.itemRows }}</el-descriptions-item>
        <el-descriptions-item label="点检方案">{{ result.schemeRows }}</el-descriptions-item>
        <el-descriptions-item label="关系数据">{{ result.relationRows }}</el-descriptions-item>
        <el-descriptions-item label="错误">{{ result.errors.length }}</el-descriptions-item>
        <el-descriptions-item label="新增项目">{{ result.newItems }}</el-descriptions-item>
        <el-descriptions-item label="更新项目">{{ result.updatedItems }}</el-descriptions-item>
        <el-descriptions-item label="新增方案">{{ result.newSchemes }}</el-descriptions-item>
        <el-descriptions-item label="新草稿版本">{{ result.newSchemeVersions }}</el-descriptions-item>
      </el-descriptions>

      <el-table v-if="result.errors.length" :data="result.errors" max-height="320" class="error-table">
        <el-table-column prop="sheet" label="工作表" width="120" />
        <el-table-column prop="rowNumber" label="行" width="80" />
        <el-table-column prop="column" label="列" width="140" />
        <el-table-column prop="message" label="问题" min-width="300" />
      </el-table>
    </template>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button
        type="primary"
        :disabled="result?.status !== 'VALIDATED'"
        :loading="committing"
        @click="commit"
      >
        确认导入
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.import-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 18px 0;
  flex-wrap: wrap;
}
.file-name { color: var(--el-text-color-secondary); }
.error-table { margin-top: 16px; }
</style>
