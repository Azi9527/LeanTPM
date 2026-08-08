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
const watermarkSaving = ref(false)
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
            <span v-else-if="row.parameterKey === 'branding.logo-url'" class="mono">
              {{ row.parameterValue.startsWith('data:') ? '已上传自定义 Logo' : row.parameterValue }}
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
@media (max-width: 900px) { .branding-grid, .watermark-grid { grid-template-columns: 1fr; } }
@media (max-width: 640px) {
  .branding-form, .watermark-form, .parameter-form { grid-template-columns: 1fr; }
  .logo-field, .branding-actions, .watermark-actions, .full-row { grid-column: auto; }
  .logo-actions { align-items: flex-start; flex-direction: column; }
  .branding-actions { display: grid; grid-template-columns: 1fr 1fr; }
}
</style>
