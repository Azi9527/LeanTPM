<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { equipmentApi, type BarcodeRow, type EquipmentRow } from '@/api/equipment'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const rows = ref<BarcodeRow[]>([])
const equipment = ref<EquipmentRow[]>([])
const equipmentId = ref<number>()
const activeOnly = ref(true)
const dialogVisible = ref(false)
const previewVisible = ref(false)
const selected = ref<BarcodeRow | null>(null)
const selectedPrintIds = ref<number[]>([])
const imageUrls = reactive<Record<number, string>>({})
const generatingAll = ref(false)
const downloading = ref(false)
const label = reactive({ widthMm: 60, heightMm: 80, imagePixels: 600 })
const form = reactive({
  equipmentId: undefined as number | undefined,
  barcodeType: 'QR' as 'QR' | 'CODE128',
  reason: '',
  regenerate: false,
})

onMounted(async () => {
  await Promise.all([loadEquipment(), load()])
})
onBeforeUnmount(revokeImages)

async function loadEquipment() {
  try {
    const result = await equipmentApi.page({ page: 1, pageSize: 200, status: 1 })
    equipment.value = result.records
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function load() {
  loading.value = true
  revokeImages()
  try {
    rows.value = await equipmentApi.barcodes({
      equipmentId: equipmentId.value,
      activeOnly: activeOnly.value,
    })
    selectedPrintIds.value = selectedPrintIds.value.filter((id) =>
      rows.value.some((row) => row.id === id && row.active),
    )
    await Promise.all(rows.value
      .filter((row) => row.active)
      .map(async (row) => {
        const blob = await equipmentApi.barcodeImage(
          row.id,
          row.barcodeType === 'QR' ? label.imagePixels : Math.min(1200, label.imagePixels * 2),
          row.barcodeType === 'QR' ? label.imagePixels : Math.max(120, Math.round(label.imagePixels / 2)),
        )
        imageUrls[row.id] = URL.createObjectURL(blob)
      }))
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function togglePrint(id: number, checked: boolean) {
  selectedPrintIds.value = checked
    ? [...new Set([...selectedPrintIds.value, id])]
    : selectedPrintIds.value.filter((value) => value !== id)
}

function revokeImages() {
  Object.values(imageUrls).forEach((url) => URL.revokeObjectURL(url))
  Object.keys(imageUrls).forEach((key) => delete imageUrls[Number(key)])
}

function openGenerate(row?: BarcodeRow) {
  Object.assign(form, {
    equipmentId: row?.equipmentId,
    barcodeType: row?.barcodeType || 'QR',
    reason: row ? '标签损坏或需要换新' : '',
    regenerate: Boolean(row),
  })
  dialogVisible.value = true
}

async function save() {
  if (!form.equipmentId) {
    ElMessage.warning('请选择设备')
    return
  }
  try {
    const payload = { barcodeType: form.barcodeType, reason: form.reason || null }
    if (form.regenerate) {
      await equipmentApi.regenerateBarcode(form.equipmentId, payload)
    } else {
      await equipmentApi.generateBarcode(form.equipmentId, payload)
    }
    dialogVisible.value = false
    ElMessage.success(form.regenerate ? '条码已重新生成，旧条码立即失效' : '条码已生成')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function generateAll() {
  generatingAll.value = true
  try {
    const result = await equipmentApi.generateAllBarcodes({ barcodeType: 'QR' })
    ElMessage.success(`已生成 ${result.generatedCount} 个二维码，${result.existingCount} 台设备已有标签`)
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    generatingAll.value = false
  }
}

async function downloadPngArchive(selectedOnly: boolean) {
  const ids = selectedOnly ? selectedPrintIds.value : undefined
  if (selectedOnly && !ids?.length) {
    ElMessage.warning('请先选择需要下载的有效二维码')
    return
  }
  downloading.value = true
  try {
    const blob = await equipmentApi.barcodeArchive(ids, label.imagePixels, label.imagePixels)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = 'LeanTPM-equipment-qr-codes.zip'
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    downloading.value = false
  }
}

async function unbind(row: BarcodeRow) {
  const { value } = await ElMessageBox.prompt(
    `解绑后，设备 ${row.equipmentCode} 的当前条码将立即失效。`,
    '解绑设备条码',
    { inputPlaceholder: '请输入解绑原因', inputValidator: (text) => Boolean(text?.trim()) },
  )
  try {
    await equipmentApi.unbindBarcode(row.equipmentId, value)
    ElMessage.success('设备条码已解绑')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

function preview(row: BarcodeRow) {
  selected.value = row
  previewVisible.value = true
}

function printLabel(row: BarcodeRow) {
  const url = imageUrls[row.id]
  if (!url) return
  const popup = window.open('', '_blank', 'width=720,height=620')
  if (!popup) {
    ElMessage.warning('浏览器阻止了打印窗口，请允许弹出窗口')
    return
  }
  popup.document.write(`
    <!doctype html><html><head><title>${escapeHtml(row.equipmentCode)}</title>
    <style>
      body{font-family:Arial,"Microsoft YaHei",sans-serif;display:grid;place-items:center;margin:0;min-height:100vh}
      .label{box-sizing:border-box;width:${label.widthMm}mm;height:${label.heightMm}mm;text-align:center;border:1px solid #d1d5db;padding:6mm;border-radius:3mm}
      img{max-width:560px;max-height:360px}
      h2{margin:12px 0 4px}.code{font:16px monospace;color:#4b5563}
    </style></head><body><div class="label">
    <img src="${url}" /><h2>${escapeHtml(row.equipmentName)}</h2><div class="code">${escapeHtml(row.equipmentCode)}</div>
    </div><script>window.onload=()=>window.print()<\/script></body></html>
  `)
  popup.document.close()
}

function printSelected() {
  const selectedRows = rows.value.filter((row) =>
    selectedPrintIds.value.includes(row.id) && imageUrls[row.id],
  )
  if (!selectedRows.length) {
    ElMessage.warning('请先选择需要打印的有效条码')
    return
  }
  const popup = window.open('', '_blank', 'width=900,height=700')
  if (!popup) {
    ElMessage.warning('浏览器阻止了打印窗口，请允许弹出窗口')
    return
  }
  const labels = selectedRows.map((row) => `
    <article class="label">
      <img src="${imageUrls[row.id]}" />
      <h2>${escapeHtml(row.equipmentName)}</h2>
      <div class="code">${escapeHtml(row.equipmentCode)}</div>
    </article>
  `).join('')
  popup.document.write(`
    <!doctype html><html><head><title>LeanTPM 设备标签</title>
    <style>
      body{font-family:Arial,"Microsoft YaHei",sans-serif;display:grid;grid-template-columns:repeat(2,1fr);gap:16px;margin:20px}
      .label{box-sizing:border-box;width:${label.widthMm}mm;height:${label.heightMm}mm;text-align:center;border:1px solid #d1d5db;padding:4mm;break-inside:avoid;border-radius:2mm}
      img{max-width:100%;height:220px;object-fit:contain}h2{margin:10px 0 4px}.code{font:15px monospace;color:#4b5563}
      @media print{body{margin:0}.label{page-break-inside:avoid}}
    </style></head><body>${labels}<script>window.onload=()=>window.print()<\/script></body></html>
  `)
  popup.document.close()
}

function escapeHtml(value: string) {
  return value.replace(/[&<>"']/g, (character) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#039;',
  }[character] || character))
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div>
        <h1>设备条码</h1>
        <p>二维码仅包含不可推断的随机访问令牌；重新生成或解绑后旧标签立即失效。</p>
      </div>
      <el-button
        v-if="auth.can('equipment:barcode:manage')"
        type="primary"
        @click="openGenerate()"
      >生成条码</el-button>
      <el-button
        v-if="auth.can('equipment:barcode:manage')"
        :loading="generatingAll"
        @click="generateAll"
      >一键生成全部二维码</el-button>
    </header>

    <section class="surface-card query-bar">
      <el-select v-model="equipmentId" clearable filterable placeholder="按设备筛选" style="width: min(320px, 100%)">
        <el-option
          v-for="item in equipment"
          :key="item.id"
          :label="`${item.equipmentCode} · ${item.equipmentName}`"
          :value="item.id"
        />
      </el-select>
      <el-checkbox v-model="activeOnly">仅显示有效条码</el-checkbox>
      <el-input-number v-model="label.widthMm" :min="20" :max="200" controls-position="right" />
      <span>宽 mm</span>
      <el-input-number v-model="label.heightMm" :min="20" :max="200" controls-position="right" />
      <span>高 mm</span>
      <el-select v-model="label.imagePixels" style="width: 125px">
        <el-option label="清晰 600px" :value="600" />
        <el-option label="高清 900px" :value="900" />
        <el-option label="超清 1200px" :value="1200" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button
        v-if="auth.can('equipment:barcode:print')"
        :disabled="!selectedPrintIds.length"
        @click="printSelected"
      >批量打印（{{ selectedPrintIds.length }}）</el-button>
      <el-button
        v-if="auth.can('equipment:barcode:print')"
        :loading="downloading"
        @click="downloadPngArchive(Boolean(selectedPrintIds.length))"
      >{{ selectedPrintIds.length ? '下载选中 PNG' : '下载全部 PNG' }}</el-button>
    </section>

    <section class="surface-card barcode-grid" v-loading="loading">
      <article v-for="row in rows" :key="row.id" class="barcode-card">
        <div class="barcode-head">
          <div>
            <el-checkbox
              v-if="row.active && auth.can('equipment:barcode:print')"
              :model-value="selectedPrintIds.includes(row.id)"
              @change="togglePrint(row.id, Boolean($event))"
            >选择打印</el-checkbox>
            <span class="mono">{{ row.equipmentCode }}</span>
            <h3>{{ row.equipmentName }}</h3>
          </div>
          <el-tag :type="row.active ? 'success' : 'info'">
            {{ row.active ? '有效' : '已失效' }}
          </el-tag>
        </div>
        <button v-if="row.active && imageUrls[row.id]" class="barcode-image" @click="preview(row)">
          <img :src="imageUrls[row.id]" :alt="`${row.equipmentCode} 条码`">
        </button>
        <div v-else class="invalid-placeholder">条码已失效</div>
        <div class="barcode-meta">
          <span>{{ row.barcodeType }}</span>
          <span>生成于 {{ row.generatedTime }}</span>
        </div>
        <p v-if="row.invalidationReason" class="reason">失效原因：{{ row.invalidationReason }}</p>
        <div v-if="row.active" class="card-actions">
          <el-button v-if="auth.can('equipment:barcode:print')" @click="printLabel(row)">打印</el-button>
          <el-button v-if="auth.can('equipment:barcode:manage')" @click="openGenerate(row)">重新生成</el-button>
          <el-button v-if="auth.can('equipment:barcode:manage')" type="danger" plain @click="unbind(row)">解绑</el-button>
        </div>
      </article>
      <el-empty v-if="!loading && !rows.length" description="暂无符合条件的设备条码" />
    </section>

    <el-dialog v-model="dialogVisible" :title="form.regenerate ? '重新生成条码' : '生成设备条码'" width="min(520px, 94vw)">
      <el-form label-position="top">
        <el-form-item label="设备">
          <el-select v-model="form.equipmentId" :disabled="form.regenerate" filterable>
            <el-option
              v-for="item in equipment"
              :key="item.id"
              :label="`${item.equipmentCode} · ${item.equipmentName}`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="条码类型">
          <el-radio-group v-model="form.barcodeType">
            <el-radio-button value="QR">二维码</el-radio-button>
            <el-radio-button value="CODE128">Code 128</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.regenerate" label="重新生成原因">
          <el-input v-model="form.reason" type="textarea" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">确认</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="previewVisible" title="设备标签预览" width="min(620px, 94vw)" align-center>
      <div v-if="selected" class="preview-label">
        <img :src="imageUrls[selected.id]" :alt="selected.equipmentCode">
        <h2>{{ selected.equipmentName }}</h2>
        <span class="mono">{{ selected.equipmentCode }}</span>
      </div>
      <template #footer>
        <el-button v-if="selected" type="primary" @click="printLabel(selected)">打印标签</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.barcode-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(290px, 1fr));
  gap: 18px;
}

.barcode-card {
  padding: 18px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 14px;
}

.barcode-head,
.barcode-meta,
.card-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
}

.barcode-head h3 {
  margin: 6px 0 14px;
}

.barcode-image {
  display: grid;
  place-items: center;
  width: 100%;
  min-height: 220px;
  padding: 10px;
  border: 0;
  border-radius: 10px;
  background: #fff;
  cursor: zoom-in;
}

.barcode-image img {
  max-width: 100%;
  max-height: 250px;
}

.invalid-placeholder {
  display: grid;
  place-items: center;
  min-height: 220px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-radius: 10px;
}

.barcode-meta {
  margin: 12px 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.reason {
  color: var(--el-text-color-secondary);
}

.preview-label {
  text-align: center;
  padding: 20px;
}

.preview-label img {
  max-width: 100%;
  max-height: 380px;
}
</style>
