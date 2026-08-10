<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import { systemApi, type ParameterRow, type PhotoWatermarkSettings } from '@/api/system'
import {
  applyBranding,
  DEFAULT_BRANDING,
  type BrandingSettings,
} from '@/branding/branding'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const rows = ref<ParameterRow[]>([])
const configurationRows = ref<ParameterRow[]>([])
const keyword = ref('')
const groupCode = ref('')
const dialogVisible = ref(false)
const editing = ref<ParameterRow | null>(null)
const brandingSaving = ref(false)
const barcodeLogoSaving = ref(false)
const watermarkSaving = ref(false)
const barcodeCenterLogoKey = 'equipment.barcode.center-logo-url'
const barcodeCenterLogoUrl = ref('DEFAULT')
const brandForm = reactive<BrandingSettings>({ ...DEFAULT_BRANDING })
const watermarkForm = reactive<PhotoWatermarkSettings>({
  watermarkEnabled: true,
  saveOriginal: true,
  saveWatermarked: true,
  template: '{brand}\n{equipmentName} ({equipmentCode})\n{taskCode} · {itemName}\n位置/部位 {location}\n{capturedAt} · 执行人 {executor}',
  position: 'BOTTOM',
  backgroundOpacity: 74,
  fontColor: '#ffffff',
  backgroundColor: '#031922',
})
const form = reactive({
  parameterKey: '',
  parameterName: '',
  parameterValue: '',
  valueType: 'STRING' as ParameterRow['valueType'],
  groupCode: 'SYSTEM',
  description: '',
  enabled: true,
})

const groups = computed(() => [...new Set(rows.value.map((row) => row.groupCode))].sort())

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [filteredRows, allRows, watermarkSettings] = await Promise.all([
      systemApi.parameters({
        keyword: keyword.value || undefined,
        groupCode: groupCode.value || undefined,
      }),
      systemApi.parameters(),
      systemApi.photoWatermarkSettings(),
    ])
    rows.value = filteredRows
    configurationRows.value = allRows
    Object.assign(watermarkForm, watermarkSettings)
    hydrateBrandingForm()
    hydrateBarcodeLogoForm()
  } catch (error) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

function handleWatermarkEnabled(enabled: boolean) {
  if (!enabled) {
    watermarkForm.saveWatermarked = false
    watermarkForm.saveOriginal = true
  }
}

async function saveWatermarkSettings() {
  if (!watermarkForm.saveOriginal && !watermarkForm.saveWatermarked) {
    ElMessage.warning('原图和水印图至少需要保留一种')
    return
  }
  if (watermarkForm.watermarkEnabled && !watermarkForm.template.trim()) {
    ElMessage.warning('启用水印后必须填写水印模板')
    return
  }
  watermarkSaving.value = true
  try {
    await systemApi.updatePhotoWatermarkSettings({
      ...watermarkForm,
      template: watermarkForm.template.trim(),
      fontColor: watermarkForm.fontColor.toLowerCase(),
      backgroundColor: watermarkForm.backgroundColor.toLowerCase(),
    })
    ElMessage.success('现场照片与水印规则已保存，APP 下次同步后生效')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error, '水印规则保存失败'))
  } finally {
    watermarkSaving.value = false
  }
}

function hydrateBrandingForm() {
  const value = (key: string, fallback: string) =>
    configurationRows.value.find((row) => row.parameterKey === key)?.parameterValue || fallback
  Object.assign(brandForm, {
    systemName: value('system.name', DEFAULT_BRANDING.systemName),
    shortName: value('branding.short-name', DEFAULT_BRANDING.shortName),
    subtitle: value('branding.subtitle', DEFAULT_BRANDING.subtitle),
    logoUrl: value('branding.logo-url', DEFAULT_BRANDING.logoUrl),
    primaryColor: value('branding.primary-color', DEFAULT_BRANDING.primaryColor),
    secondaryColor: value('branding.secondary-color', DEFAULT_BRANDING.secondaryColor),
    neutralColor: value('branding.neutral-color', DEFAULT_BRANDING.neutralColor),
  })
}

function hydrateBarcodeLogoForm() {
  const configured = configurationRows.value.find(
    (row) => row.parameterKey === barcodeCenterLogoKey,
  )
  barcodeCenterLogoUrl.value = configured?.status === 1
    ? configured.parameterValue
    : 'DEFAULT'
}

async function handleLogoChange(file: UploadFile) {
  const raw = file.raw
  if (!raw) return
  if (!['image/png', 'image/jpeg', 'image/webp'].includes(raw.type)) {
    ElMessage.warning('Logo 仅支持 PNG、JPEG 或 WebP 图片')
    return
  }
  if (raw.size > 512 * 1024) {
    ElMessage.warning('Logo 图片不能超过 512KB')
    return
  }
  brandForm.logoUrl = await readDataUrl(raw)
}

async function handleBarcodeLogoChange(file: UploadFile) {
  const raw = file.raw
  if (!raw) return
  if (!['image/png', 'image/jpeg'].includes(raw.type)) {
    ElMessage.warning('二维码中心图标仅支持 PNG 或 JPEG 图片')
    return
  }
  if (raw.size > 512 * 1024) {
    ElMessage.warning('二维码中心图标不能超过 512KB')
    return
  }
  barcodeCenterLogoUrl.value = await readDataUrl(raw)
}

function readDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(file)
  })
}

function resetBranding() {
  Object.assign(brandForm, DEFAULT_BRANDING)
}

function resetBarcodeLogo() {
  barcodeCenterLogoUrl.value = 'DEFAULT'
}

async function saveBarcodeLogo() {
  barcodeLogoSaving.value = true
  try {
    const existing = configurationRows.value.find(
      (row) => row.parameterKey === barcodeCenterLogoKey,
    )
    const payload = {
      parameterKey: barcodeCenterLogoKey,
      parameterName: '设备二维码中心图标',
      parameterValue: barcodeCenterLogoUrl.value,
      valueType: 'STRING',
      groupCode: 'EQUIPMENT',
      description: '设备二维码标签中央图标；DEFAULT 使用内置 LT 盾牌',
      enabled: true,
      version: existing?.version,
    }
    if (existing) await systemApi.updateParameter(existing.id, payload)
    else await systemApi.createParameter(payload)
    ElMessage.success('设备二维码标签配置已保存，新生成的预览和打印立即生效')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error, '设备二维码标签配置保存失败'))
  } finally {
    barcodeLogoSaving.value = false
  }
}

async function saveBranding() {
  const required = [brandForm.systemName, brandForm.shortName, brandForm.subtitle, brandForm.logoUrl]
  if (required.some((value) => !value.trim())) {
    ElMessage.warning('请完整填写品牌名称、简称、副标题和 Logo')
    return
  }
  if (![brandForm.primaryColor, brandForm.secondaryColor, brandForm.neutralColor]
    .every((value) => /^#[0-9a-f]{6}$/i.test(value))) {
    ElMessage.warning('主题色必须使用 #RRGGBB 格式')
    return
  }

  brandingSaving.value = true
  try {
    const normalized = {
      systemName: brandForm.systemName.trim(),
      shortName: brandForm.shortName.trim(),
      subtitle: brandForm.subtitle.trim(),
      logoUrl: brandForm.logoUrl.trim(),
      primaryColor: brandForm.primaryColor.trim().toLowerCase(),
      secondaryColor: brandForm.secondaryColor.trim().toLowerCase(),
      neutralColor: brandForm.neutralColor.trim().toLowerCase(),
    }
    await systemApi.updateBranding(normalized)
    applyBranding(normalized)
    ElMessage.success('品牌 Logo 与主题色已保存并立即生效')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error, '品牌配置保存失败'))
  } finally {
    brandingSaving.value = false
  }
}

function openDialog(row?: ParameterRow) {
  editing.value = row || null
  Object.assign(
    form,
    row
      ? {
          parameterKey: row.parameterKey,
          parameterName: row.parameterName,
          parameterValue: row.parameterValue,
          valueType: row.valueType,
          groupCode: row.groupCode,
          description: row.description || '',
          enabled: row.status === 1,
        }
      : {
          parameterKey: '',
          parameterName: '',
          parameterValue: '',
          valueType: 'STRING',
          groupCode: 'SYSTEM',
          description: '',
          enabled: true,
        },
  )
  dialogVisible.value = true
}

async function save() {
  if (!form.parameterKey.trim() || !form.parameterName.trim() || !form.groupCode.trim()) {
    ElMessage.warning('请完整填写参数键、名称和分组')
    return
  }
  saving.value = true
  try {
    const payload = {
      ...form,
      groupCode: form.groupCode.trim().toUpperCase(),
      version: editing.value?.version,
    }
    if (editing.value) await systemApi.updateParameter(editing.value.id, payload)
    else await systemApi.createParameter(payload)
    dialogVisible.value = false
    ElMessage.success('系统参数已保存')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function remove(row: ParameterRow) {
  await ElMessageBox.confirm(`确认删除参数“${row.parameterName}”吗？`, '删除参数', {
    type: 'warning',
  })
  try {
    await systemApi.deleteParameter(row.id)
    ElMessage.success('系统参数已删除')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div>
        <h1>系统参数</h1>
        <p>集中维护可运行配置；内置参数可调整但不可删除。</p>
      </div>
      <div class="page-actions">
        <el-button v-if="auth.can('system:parameter:manage')" type="primary" @click="openDialog()">
          新增参数
        </el-button>
      </div>
    </header>

    <section class="surface-card branding-card">
      <div class="branding-heading">
        <div>
          <h2>品牌与主题</h2>
          <p>统一调整登录页、后台、移动作业端和安卓 App 的 Logo、名称与主题色。</p>
        </div>
        <el-tag type="success" effect="plain">即时预览</el-tag>
      </div>
      <div class="branding-grid">
        <div class="branding-preview" :style="{
          '--preview-primary': brandForm.primaryColor,
          '--preview-secondary': brandForm.secondaryColor,
          '--preview-neutral': brandForm.neutralColor,
        }">
          <div class="preview-logo"><img :src="brandForm.logoUrl" alt="品牌 Logo 预览" /></div>
          <strong>{{ brandForm.systemName }}</strong>
          <span>{{ brandForm.subtitle }}</span>
          <button type="button">主题按钮</button>
          <div class="preview-colors">
            <i :style="{ background: brandForm.primaryColor }"></i>
            <i :style="{ background: brandForm.secondaryColor }"></i>
            <i :style="{ background: brandForm.neutralColor }"></i>
          </div>
        </div>
        <el-form label-position="top" class="branding-form">
          <el-form-item label="系统名称"><el-input v-model="brandForm.systemName" maxlength="60" /></el-form-item>
          <el-form-item label="品牌简称"><el-input v-model="brandForm.shortName" maxlength="30" /></el-form-item>
          <el-form-item label="品牌副标题"><el-input v-model="brandForm.subtitle" maxlength="40" /></el-form-item>
          <el-form-item label="品牌 Logo" class="logo-field">
            <div class="logo-actions">
              <el-upload
                accept="image/png,image/jpeg,image/webp"
                :auto-upload="false"
                :show-file-list="false"
                :on-change="handleLogoChange"
              >
                <el-button>选择图片</el-button>
              </el-upload>
              <span>PNG/JPEG/WebP，最大 512KB，建议透明或白底横版 Logo</span>
            </div>
          </el-form-item>
          <el-form-item label="主品牌色">
            <div class="color-field"><el-color-picker v-model="brandForm.primaryColor" /><el-input v-model="brandForm.primaryColor" maxlength="7" /></div>
          </el-form-item>
          <el-form-item label="辅助品牌色">
            <div class="color-field"><el-color-picker v-model="brandForm.secondaryColor" /><el-input v-model="brandForm.secondaryColor" maxlength="7" /></div>
          </el-form-item>
          <el-form-item label="强调品牌色">
            <div class="color-field"><el-color-picker v-model="brandForm.neutralColor" /><el-input v-model="brandForm.neutralColor" maxlength="7" /></div>
          </el-form-item>
          <div v-if="auth.can('system:parameter:manage')" class="branding-actions">
            <el-button @click="resetBranding">恢复宝山默认值</el-button>
            <el-button type="primary" :loading="brandingSaving" @click="saveBranding">保存品牌配置</el-button>
          </div>
        </el-form>
      </div>
    </section>

    <section class="surface-card barcode-label-card">
      <div class="branding-heading">
        <div>
          <h2>设备二维码标签</h2>
          <p>客户确认的蓝色科技风标签。二维码与设备动态绑定，这里只配置二维码中央品牌图标。</p>
        </div>
        <el-tag type="success" effect="plain">打印即时生效</el-tag>
      </div>
      <div class="barcode-label-grid">
        <div class="qr-label-preview">
          <strong>大宝山设备管理系统</strong>
          <div class="qr-wave-lines" />
          <div class="qr-code-card">
            <div class="qr-dot-field">
              <i class="finder finder-top-left" />
              <i class="finder finder-top-right" />
              <i class="finder finder-bottom-left" />
              <div class="qr-center-logo">
                <img
                  v-if="barcodeCenterLogoUrl !== 'DEFAULT'"
                  :src="barcodeCenterLogoUrl"
                  alt="二维码中心图标预览"
                />
                <span v-else>LT</span>
              </div>
            </div>
          </div>
          <div class="qr-device-row"><span>▣</span><b>设备名称：循环泵站一号</b></div>
          <div class="qr-device-row"><span>⚙</span><b>设备编号：VIZ-PUMP-01</b></div>
          <div class="qr-scan-action"><span>⌗</span><b>扫码查看设备档案</b></div>
        </div>
        <div class="barcode-label-settings">
          <div class="settings-callout">
            <strong>关于二维码图片</strong>
            <p>二维码本体由系统按设备实时生成，不能上传一张固定二维码替换，否则所有设备会指向同一地址。</p>
          </div>
          <el-form label-position="top">
            <el-form-item label="二维码中心图标">
              <div class="barcode-logo-actions">
                <div class="barcode-logo-thumb">
                  <img
                    v-if="barcodeCenterLogoUrl !== 'DEFAULT'"
                    :src="barcodeCenterLogoUrl"
                    alt="自定义二维码中心图标"
                  />
                  <span v-else>LT</span>
                </div>
                <div>
                  <el-upload
                    accept="image/png,image/jpeg"
                    :auto-upload="false"
                    :show-file-list="false"
                    :on-change="handleBarcodeLogoChange"
                  >
                    <el-button>选择中心图标</el-button>
                  </el-upload>
                  <p>PNG/JPEG，最大 512KB；建议方形透明图，主体居中并保留留白。</p>
                </div>
              </div>
            </el-form-item>
            <div v-if="auth.can('system:parameter:manage')" class="barcode-label-actions">
              <el-button @click="resetBarcodeLogo">恢复内置 LT 图标</el-button>
              <el-button
                type="primary"
                :loading="barcodeLogoSaving"
                @click="saveBarcodeLogo"
              >
                保存二维码标签配置
              </el-button>
            </div>
          </el-form>
        </div>
      </div>
    </section>

    <section class="surface-card watermark-card">
      <div class="branding-heading">
        <div>
          <h2>现场照片与水印</h2>
          <p>统一控制 APP 是否补充水印、是否保存原图和水印图，以及水印的内容与样式。</p>
        </div>
        <el-tag type="success" effect="plain">移动端同步生效</el-tag>
      </div>
      <div class="watermark-grid">
        <div class="watermark-preview">
          <div
            class="watermark-preview-layer"
            :class="watermarkForm.position === 'TOP' ? 'is-top' : 'is-bottom'"
            :style="{
              color: watermarkForm.fontColor,
              background: `color-mix(in srgb, ${watermarkForm.backgroundColor} ${watermarkForm.backgroundOpacity}%, transparent)`,
            }"
          >
            <div>大宝山矿业</div>
            <div>循环泵站一号 (VIZ-PUMP-01)</div>
            <div>DJ-20260805-00001 · 润滑油液位</div>
            <div>位置/部位 主轴润滑油箱</div>
            <div>2026-08-05 14:30:25 · 执行人 操作工01</div>
          </div>
          <span v-if="!watermarkForm.watermarkEnabled" class="watermark-disabled">当前不生成水印</span>
        </div>
        <el-form label-position="top" class="watermark-form">
          <el-form-item label="是否补充水印">
            <el-switch
              v-model="watermarkForm.watermarkEnabled"
              active-text="生成水印"
              inactive-text="不生成水印"
              @change="handleWatermarkEnabled"
            />
          </el-form-item>
          <el-form-item label="图片保存策略">
            <div class="retention-switches">
              <el-checkbox v-model="watermarkForm.saveOriginal">保存原图</el-checkbox>
              <el-checkbox v-model="watermarkForm.saveWatermarked" :disabled="!watermarkForm.watermarkEnabled">
                保存水印图
              </el-checkbox>
            </div>
          </el-form-item>
          <el-form-item label="水印生成模板" class="full-row">
            <el-input
              v-model="watermarkForm.template"
              type="textarea"
              :rows="6"
              maxlength="2000"
              show-word-limit
              :disabled="!watermarkForm.watermarkEnabled"
            />
            <div class="template-help">
              支持：{brand} 品牌、{equipmentName} 设备名称、{equipmentCode} 设备编号、{taskCode} 任务号、
              {itemName} 点检项、{capturedAt} 拍摄时间、{executor} 执行人、{location} 位置/部位。
            </div>
          </el-form-item>
          <el-form-item label="水印位置">
            <el-select v-model="watermarkForm.position" :disabled="!watermarkForm.watermarkEnabled">
              <el-option label="图片底部" value="BOTTOM" />
              <el-option label="图片顶部" value="TOP" />
            </el-select>
          </el-form-item>
          <el-form-item label="背景不透明度">
            <el-slider
              v-model="watermarkForm.backgroundOpacity"
              :min="0"
              :max="100"
              show-input
              :disabled="!watermarkForm.watermarkEnabled"
            />
          </el-form-item>
          <el-form-item label="文字颜色">
            <div class="color-field"><el-color-picker v-model="watermarkForm.fontColor" /><el-input v-model="watermarkForm.fontColor" maxlength="7" /></div>
          </el-form-item>
          <el-form-item label="背景颜色">
            <div class="color-field"><el-color-picker v-model="watermarkForm.backgroundColor" /><el-input v-model="watermarkForm.backgroundColor" maxlength="7" /></div>
          </el-form-item>
          <div v-if="auth.can('system:parameter:manage')" class="watermark-actions">
            <el-button type="primary" :loading="watermarkSaving" @click="saveWatermarkSettings">
              保存照片与水印规则
            </el-button>
          </div>
        </el-form>
      </div>
    </section>

    <section class="surface-card query-bar">
      <el-input
        v-model="keyword"
        clearable
        placeholder="参数键或名称"
        style="width: 280px"
        @keyup.enter="load"
        @clear="load"
      />
      <el-select v-model="groupCode" clearable placeholder="全部分组" style="width: 180px" @change="load">
        <el-option v-for="group in groups" :key="group" :label="group" :value="group" />
      </el-select>
      <el-button type="primary" plain @click="load">查询</el-button>
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

    <section class="surface-card table-card" v-loading="loading">
      <div class="table-toolbar">
        <span class="table-title">参数列表</span>
        <span class="result-count">共 {{ rows.length }} 项</span>
      </div>
      <el-table :data="rows" row-key="id">
        <el-table-column prop="parameterName" label="参数名称" min-width="160" />
        <el-table-column prop="parameterKey" label="参数键" min-width="220">
          <template #default="{ row }"><span class="mono">{{ row.parameterKey }}</span></template>
        </el-table-column>
        <el-table-column prop="parameterValue" label="参数值" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag v-if="row.valueType === 'BOOLEAN'" :type="row.parameterValue === 'true' ? 'success' : 'info'">
              {{ row.parameterValue === 'true' ? '开启' : '关闭' }}
            </el-tag>
            <span
              v-else-if="['branding.logo-url', barcodeCenterLogoKey].includes(row.parameterKey)"
              class="mono"
            >
              {{ row.parameterValue.startsWith('data:') ? '已上传自定义图片' : row.parameterValue }}
            </span>
            <span v-else class="mono">{{ row.parameterValue }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="valueType" label="类型" width="105" />
        <el-table-column prop="groupCode" label="分组" width="120" />
        <el-table-column label="属性" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.builtIn" type="warning" effect="plain">内置</el-tag>
            <el-tag v-else effect="plain">自定义</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          v-if="auth.can('system:parameter:manage')"
          label="操作"
          width="130"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button
              v-if="!row.builtIn && auth.can('system:parameter:delete')"
              link
              type="danger"
              @click="remove(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无系统参数" /></template>
      </el-table>
    </section>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑参数' : '新增参数'" width="min(620px, 94vw)">
      <el-form label-position="top" class="parameter-form">
        <el-form-item label="参数键">
          <el-input v-model="form.parameterKey" :disabled="Boolean(editing)" placeholder="例如 equipment.import.max-rows" />
        </el-form-item>
        <el-form-item label="参数名称"><el-input v-model="form.parameterName" /></el-form-item>
        <el-form-item label="参数类型">
          <el-select v-model="form.valueType">
            <el-option label="文本" value="STRING" />
            <el-option label="布尔值" value="BOOLEAN" />
            <el-option label="整数" value="INTEGER" />
            <el-option label="小数" value="DECIMAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="参数分组"><el-input v-model="form.groupCode" placeholder="SYSTEM" /></el-form-item>
        <el-form-item label="参数值" class="full-row">
          <el-switch
            v-if="form.valueType === 'BOOLEAN'"
            :model-value="form.parameterValue === 'true'"
            active-text="开启"
            inactive-text="关闭"
            @change="form.parameterValue = $event ? 'true' : 'false'"
          />
          <el-input v-else v-model="form.parameterValue" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="说明" class="full-row">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.result-count { color: var(--tpm-text-secondary); font-size: 12px; }
.branding-card { padding: 20px; }
.branding-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.branding-heading h2 { margin: 0 0 5px; font-size: 18px; }
.branding-heading p { margin: 0; color: var(--tpm-text-secondary); font-size: 13px; }
.branding-grid { display: grid; grid-template-columns: minmax(240px, .72fr) minmax(480px, 1.6fr); gap: 24px; }
.branding-preview {
  display: flex; overflow: hidden; align-items: flex-start; flex-direction: column; min-height: 280px;
  padding: 24px; border-radius: 16px; color: #fff;
  background: linear-gradient(145deg, var(--preview-secondary), color-mix(in srgb, var(--preview-primary) 62%, var(--preview-secondary)));
}
.preview-logo { display: flex; align-items: center; width: 100%; min-height: 76px; padding: 9px; border-radius: 10px; background: #fff; }
.preview-logo img { display: block; width: 100%; max-height: 66px; object-fit: contain; }
.branding-preview > strong { margin-top: 22px; font-size: 22px; }
.branding-preview > span { margin-top: 4px; opacity: .72; }
.branding-preview button { margin-top: auto; padding: 9px 18px; border: 0; border-radius: 7px; color: #fff; background: var(--preview-primary); }
.preview-colors { display: flex; gap: 7px; margin-top: 14px; }
.preview-colors i { width: 22px; height: 6px; border-radius: 99px; }
.branding-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.logo-field, .branding-actions { grid-column: 1 / -1; }
.logo-actions { display: flex; align-items: center; gap: 12px; }
.logo-actions span { color: var(--tpm-text-secondary); font-size: 12px; }
.color-field { display: grid; grid-template-columns: 40px 1fr; gap: 8px; width: 100%; }
.branding-actions { display: flex; justify-content: flex-end; gap: 8px; }
.barcode-label-card { padding: 20px; }
.barcode-label-grid { display: grid; grid-template-columns: minmax(280px, .72fr) minmax(460px, 1.45fr); gap: 28px; }
.qr-label-preview {
  position: relative; overflow: hidden; aspect-ratio: 3 / 4; padding: 7.5% 7% 4.5%; border-radius: 18px;
  color: #fff; background: linear-gradient(145deg, #0879e6 19%, #0758ba 54%, #00327e 100%);
  box-shadow: 0 18px 46px rgba(0, 67, 155, .24);
}
.qr-label-preview::before {
  position: absolute; top: -3%; right: -20%; left: -20%; height: 30%; border-radius: 0 0 50% 48%;
  background: #fff; content: ''; transform: rotate(-1.5deg);
}
.qr-label-preview::after {
  position: absolute; right: 0; bottom: 0; left: 0; height: 22%; opacity: .34;
  background:
    linear-gradient(45deg, transparent 46%, #42a1ef 47% 49%, transparent 50%) 0 0 / 34px 34px,
    linear-gradient(-45deg, transparent 46%, #42a1ef 47% 49%, transparent 50%) 0 0 / 34px 34px;
  content: '';
}
.qr-label-preview > strong {
  position: relative; z-index: 2; display: block; overflow: hidden; margin: 0 -2% 18%; color: #074ca7;
  font-size: clamp(18px, 2.1vw, 36px); font-weight: 900; letter-spacing: .04em; text-align: center; white-space: nowrap;
}
.qr-wave-lines {
  position: absolute; top: 19%; right: -8%; left: -8%; z-index: 1; height: 15%; opacity: .72;
  border-top: 3px solid #7fc5ff; border-radius: 50%; transform: rotate(-3deg);
  box-shadow: 0 -7px 0 rgba(151, 207, 255, .55), 0 -14px 0 rgba(189, 225, 255, .45);
}
.qr-code-card {
  position: relative; z-index: 2; width: 72%; margin: 0 auto 5%; padding: 4.2%; border: 2px solid #5fc4ff;
  border-radius: 10%; background: rgba(255, 255, 255, .98); box-shadow: 0 10px 28px rgba(0, 32, 101, .24);
}
.qr-dot-field {
  position: relative; aspect-ratio: 1; border-radius: 4%;
  background-color: #fff;
  background-image: radial-gradient(circle, #064b9e 0 30%, transparent 34%);
  background-size: 8.6% 8.6%;
}
.finder { position: absolute; z-index: 2; width: 23%; height: 23%; border: clamp(4px, .8vw, 9px) solid #0757b9; border-radius: 22%; background: #fff; }
.finder::after { position: absolute; inset: 22%; border-radius: 22%; background: #0757b9; content: ''; }
.finder-top-left { top: 0; left: 0; }
.finder-top-right { top: 0; right: 0; }
.finder-bottom-left { bottom: 0; left: 0; }
.qr-center-logo, .barcode-logo-thumb {
  display: grid; overflow: hidden; place-items: center; color: #fff;
  background: linear-gradient(145deg, #17bea7, #007c74); font-weight: 900;
}
.qr-center-logo {
  position: absolute; top: 50%; left: 50%; z-index: 3; width: 23%; aspect-ratio: 1; border: 5px solid #fff;
  clip-path: polygon(50% 0, 88% 18%, 84% 68%, 70% 85%, 50% 100%, 30% 85%, 16% 68%, 12% 18%);
  font-size: clamp(13px, 1.7vw, 26px); transform: translate(-50%, -50%);
}
.qr-center-logo img, .barcode-logo-thumb img { width: 100%; height: 100%; object-fit: contain; background: #fff; }
.qr-device-row, .qr-scan-action { position: relative; z-index: 2; display: grid; align-items: center; border: 2px solid rgba(255, 255, 255, .88); }
.qr-device-row {
  grid-template-columns: 22% 1fr; min-height: 7%; margin-top: 2.4%; border-radius: 12px;
  background: rgba(0, 59, 145, .56); font-size: clamp(11px, 1.25vw, 20px);
}
.qr-device-row span { display: grid; height: 100%; place-items: center; border-right: 1px solid rgba(255, 255, 255, .8); font-size: 1.35em; }
.qr-device-row b { padding: 2.5% 5%; white-space: nowrap; }
.qr-scan-action {
  grid-template-columns: 22% 1fr; min-height: 8.5%; margin-top: 3.3%; border-radius: 14px; color: #074ca7;
  background: #fff; font-size: clamp(12px, 1.45vw, 22px); letter-spacing: .08em;
}
.qr-scan-action span { display: grid; height: 100%; place-items: center; border-radius: 11px 0 0 11px; color: #fff; background: #0aa99b; font-size: 1.5em; }
.qr-scan-action b { padding: 2.5% 5%; text-align: center; white-space: nowrap; }
.barcode-label-settings { align-self: start; }
.settings-callout { margin-bottom: 20px; padding: 16px 18px; border: 1px solid #cfe4fb; border-radius: 12px; background: #f5faff; }
.settings-callout strong { color: #0757b9; }
.settings-callout p { margin: 7px 0 0; color: var(--tpm-text-secondary); font-size: 13px; line-height: 1.7; }
.barcode-logo-actions { display: flex; align-items: center; gap: 16px; }
.barcode-logo-thumb { width: 88px; height: 88px; flex: 0 0 auto; border: 8px solid #fff; border-radius: 22px; box-shadow: 0 0 0 1px #dbe8f5; font-size: 24px; }
.barcode-logo-actions p { margin: 9px 0 0; color: var(--tpm-text-secondary); font-size: 12px; }
.barcode-label-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 22px; }
.watermark-card { padding: 20px; }
.watermark-grid { display: grid; grid-template-columns: minmax(280px, .8fr) minmax(520px, 1.5fr); gap: 24px; }
.watermark-preview {
  position: relative; overflow: hidden; min-height: 330px; border-radius: 16px;
  background: linear-gradient(145deg, #0a4934 0%, #247d58 48%, #a8c5b5 100%);
}
.watermark-preview::before {
  position: absolute; inset: 52px 60px 74px; border: 10px solid rgba(255, 255, 255, .3);
  border-radius: 6px; content: ''; transform: perspective(400px) rotateX(8deg) rotateY(-12deg);
}
.watermark-preview-layer { position: absolute; right: 0; left: 0; z-index: 2; padding: 14px 18px; font-size: 13px; line-height: 1.55; }
.watermark-preview-layer.is-top { top: 0; }
.watermark-preview-layer.is-bottom { bottom: 0; }
.watermark-disabled { position: absolute; top: 50%; left: 50%; z-index: 3; padding: 9px 14px; border-radius: 99px; color: #fff; background: rgba(0, 0, 0, .66); transform: translate(-50%, -50%); }
.watermark-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.retention-switches { display: flex; align-items: center; min-height: 32px; gap: 20px; }
.template-help { margin-top: 7px; color: var(--tpm-text-secondary); font-size: 12px; line-height: 1.6; }
.watermark-actions { display: flex; justify-content: flex-end; grid-column: 1 / -1; }
.parameter-form { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.full-row { grid-column: 1 / -1; }
@media (max-width: 900px) { .branding-grid, .barcode-label-grid, .watermark-grid { grid-template-columns: 1fr; } }
@media (max-width: 640px) {
  .branding-form, .watermark-form, .parameter-form { grid-template-columns: 1fr; }
  .logo-field, .branding-actions, .watermark-actions, .full-row { grid-column: auto; }
  .logo-actions { align-items: flex-start; flex-direction: column; }
  .branding-actions, .barcode-label-actions { display: grid; grid-template-columns: 1fr 1fr; }
  .barcode-logo-actions { align-items: flex-start; flex-direction: column; }
}
</style>
