<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  appReleaseApi,
  appReleaseAssetUrl,
  appReleaseQrCodeUrl,
  type AndroidAppRelease,
} from '@/api/appRelease'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const uploading = ref(false)
const toggling = ref(false)
const release = ref<AndroidAppRelease>({ available: false, enabled: false })
const selectedFile = ref<File>()
const fileInput = ref<HTMLInputElement>()
const form = reactive({
  versionName: '1.0.1',
  versionCode: 101,
  minimumVersionCode: 100,
  releaseNotes: '',
  enabled: true,
  forceUpgrade: false,
})

function formatBytes(value?: number) {
  if (!value) return '—'
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

function formatDate(value?: string) {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}

function chooseFile() {
  fileInput.value?.click()
}

function handleFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.apk')) {
    ElMessage.error('请选择 APK 文件')
    input.value = ''
    return
  }
  selectedFile.value = file
}

async function load() {
  loading.value = true
  try {
    release.value = await appReleaseApi.current()
    if (release.value.available) {
      form.versionName = release.value.versionName || form.versionName
      form.versionCode = release.value.versionCode || form.versionCode
      form.minimumVersionCode = release.value.minimumVersionCode || form.minimumVersionCode
      form.releaseNotes = release.value.releaseNotes || ''
      form.enabled = release.value.enabled
      form.forceUpgrade = Boolean(release.value.forceUpgrade)
    }
  } catch (error) {
    ElMessage.error(errorMessage(error, 'APP 发布信息加载失败'))
  } finally {
    loading.value = false
  }
}

async function upload() {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择 APK 文件')
    return
  }
  if (!form.versionName.trim()) {
    ElMessage.warning('请输入版本名称')
    return
  }
  if (form.minimumVersionCode > form.versionCode) {
    ElMessage.warning('最低版本号不能高于当前版本号')
    return
  }
  uploading.value = true
  try {
    const data = new FormData()
    data.append('file', selectedFile.value)
    data.append('versionName', form.versionName.trim())
    data.append('versionCode', String(form.versionCode))
    data.append('minimumVersionCode', String(form.minimumVersionCode))
    data.append('releaseNotes', form.releaseNotes.trim())
    data.append('enabled', String(form.enabled))
    data.append('forceUpgrade', String(form.forceUpgrade))
    release.value = await appReleaseApi.upload(data)
    selectedFile.value = undefined
    if (fileInput.value) fileInput.value.value = ''
    ElMessage.success('Android APP 已上传并发布')
  } catch (error) {
    ElMessage.error(errorMessage(error, 'APK 上传失败'))
  } finally {
    uploading.value = false
  }
}

async function toggleEnabled(value: boolean) {
  toggling.value = true
  try {
    release.value = await appReleaseApi.updateEnabled(value)
    form.enabled = release.value.enabled
    ElMessage.success(value ? '登录页下载入口已启用' : '登录页下载入口已隐藏')
  } catch (error) {
    form.enabled = !value
    ElMessage.error(errorMessage(error, '发布状态更新失败'))
  } finally {
    toggling.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page-shell" v-loading="loading">
    <header class="page-header">
      <div>
        <h1>APP 发布管理</h1>
        <p>上传企业 Android APK，登录页将自动生成下载二维码和下载入口。</p>
      </div>
    </header>

    <section class="surface-card release-overview">
      <div class="release-heading">
        <div>
          <span class="eyebrow">CURRENT ANDROID RELEASE</span>
          <h2>{{ release.available ? `Android ${release.versionName}` : '尚未发布 Android APP' }}</h2>
          <p v-if="release.available">
            版本号 {{ release.versionCode }} · 最低支持 {{ release.minimumVersionCode }} ·
            {{ release.forceUpgrade ? '强制旧版本升级' : '允许旧版本继续使用' }} · {{ formatBytes(release.fileSize) }}
          </p>
          <p v-else>上传首个 APK 后，登录页才会出现“下载 Android APP”入口。</p>
        </div>
        <el-tag v-if="release.available" :type="release.enabled ? 'success' : 'info'" size="large">
          {{ release.enabled ? '登录页已展示' : '登录页已隐藏' }}
        </el-tag>
      </div>

      <div v-if="release.available" class="release-detail">
        <dl>
          <div><dt>文件名称</dt><dd>{{ release.fileName }}</dd></div>
          <div><dt>发布时间</dt><dd>{{ formatDate(release.publishedTime) }}</dd></div>
          <div><dt>SHA-256</dt><dd class="mono">{{ release.sha256 }}</dd></div>
          <div><dt>发布说明</dt><dd>{{ release.releaseNotes || '无' }}</dd></div>
        </dl>
        <div class="qr-preview">
          <img v-if="release.enabled" :src="appReleaseQrCodeUrl(release.qrCodeUrl)" alt="Android APP 下载二维码" />
          <div v-else class="qr-placeholder">下载入口已隐藏</div>
          <el-button
            v-if="release.enabled"
            tag="a"
            :href="appReleaseAssetUrl(release.downloadUrl)"
            target="_blank"
          >测试下载</el-button>
        </div>
      </div>

      <div v-if="release.available && auth.can('system:app-release:manage')" class="publish-switch">
        <span><strong>登录页下载入口</strong><small>关闭后 APK 仍保留，但匿名用户不可下载。</small></span>
        <el-switch
          v-model="form.enabled"
          :loading="toggling"
          inline-prompt
          active-text="显示"
          inactive-text="隐藏"
          @change="toggleEnabled"
        />
      </div>
    </section>

    <section v-if="auth.can('system:app-release:manage')" class="surface-card upload-card">
      <div class="section-title">
        <div><h2>上传新版本</h2><p>新文件上传成功后将替换登录页当前下载版本。</p></div>
      </div>

      <div class="upload-grid">
        <div class="file-picker" @click="chooseFile">
          <input ref="fileInput" type="file" accept=".apk,application/vnd.android.package-archive" @change="handleFile" />
          <el-icon><UploadFilled /></el-icon>
          <strong>{{ selectedFile?.name || '选择 Android APK 文件' }}</strong>
          <small>{{ selectedFile ? formatBytes(selectedFile.size) : '最大支持 200 MB，仅允许 .apk' }}</small>
        </div>

        <el-form label-position="top" class="release-form">
          <div class="version-row">
            <el-form-item label="版本名称">
              <el-input v-model="form.versionName" maxlength="32" placeholder="例如 1.2.0" />
            </el-form-item>
            <el-form-item label="版本号">
              <el-input-number v-model="form.versionCode" :min="1" :max="2147483647" controls-position="right" />
            </el-form-item>
            <el-form-item label="最低支持版本号">
              <el-input-number v-model="form.minimumVersionCode" :min="1" :max="form.versionCode" controls-position="right" />
            </el-form-item>
          </div>
          <el-form-item label="发布说明">
            <el-input v-model="form.releaseNotes" type="textarea" :rows="4" maxlength="1000" show-word-limit />
          </el-form-item>
          <el-checkbox v-model="form.enabled">上传后立即在登录页展示下载入口</el-checkbox>
          <el-checkbox v-model="form.forceUpgrade">强制所有旧版本升级</el-checkbox>
          <p class="force-upgrade-tip">
            勾选后，版本号低于本次发布版本号的 APP 登录后将被立即阻断并要求升级；未勾选时，仅低于“最低支持版本号”的 APP 会被阻断。
          </p>
          <div class="actions">
            <el-button type="primary" :loading="uploading" @click="upload">上传并发布</el-button>
          </div>
        </el-form>
      </div>
    </section>
  </div>
</template>

<style scoped lang="scss">
.release-overview,
.upload-card { padding: 22px; }
.release-heading,
.section-title,
.publish-switch { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.release-heading h2,
.section-title h2 { margin: 5px 0; font-size: 20px; }
.release-heading p,
.section-title p { margin: 0; color: var(--tpm-text-secondary); font-size: 13px; }
.eyebrow { color: var(--tpm-primary); font-size: 10px; font-weight: 800; letter-spacing: .16em; }
.release-detail { display: grid; grid-template-columns: minmax(0, 1fr) 180px; gap: 24px; margin-top: 22px; padding-top: 20px; border-top: 1px solid var(--tpm-border); }
dl { display: grid; gap: 0; margin: 0; }
dl > div { display: grid; grid-template-columns: 90px minmax(0, 1fr); gap: 14px; padding: 10px 0; border-bottom: 1px dashed var(--tpm-border); }
dt { color: var(--tpm-text-secondary); }
dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }
.mono { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 12px; }
.force-upgrade-tip { margin: 4px 0 0; color: var(--tpm-text-secondary); font-size: 12px; line-height: 1.6; }
.qr-preview { display: flex; align-items: stretch; flex-direction: column; gap: 10px; }
.qr-preview img,
.qr-placeholder { box-sizing: border-box; width: 170px; height: 170px; border: 1px solid var(--tpm-border); border-radius: 10px; background: #fff; }
.qr-preview img { padding: 7px; object-fit: contain; }
.qr-placeholder { display: grid; place-items: center; color: var(--tpm-text-secondary); background: var(--tpm-bg); }
.publish-switch { margin-top: 20px; padding: 14px 16px; border-radius: 9px; background: var(--tpm-primary-soft); }
.publish-switch span { display: flex; flex-direction: column; gap: 3px; }
.publish-switch small { color: var(--tpm-text-secondary); }
.upload-card { margin-top: 16px; }
.upload-grid { display: grid; grid-template-columns: minmax(260px, .7fr) minmax(480px, 1.3fr); gap: 26px; margin-top: 20px; }
.file-picker { display: flex; align-items: center; justify-content: center; flex-direction: column; gap: 9px; min-height: 230px; border: 1px dashed var(--tpm-primary); border-radius: 12px; color: var(--tpm-primary); background: var(--tpm-primary-soft); cursor: pointer; text-align: center; }
.file-picker input { display: none; }
.file-picker .el-icon { font-size: 38px; }
.file-picker small { max-width: 90%; color: var(--tpm-text-secondary); }
.version-row { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 14px; }
.release-form :deep(.el-input-number) { width: 100%; }
.actions { display: flex; justify-content: flex-end; margin-top: 18px; }
@media (max-width: 900px) {
  .release-detail,
  .upload-grid { grid-template-columns: 1fr; }
  .version-row { grid-template-columns: 1fr; gap: 0; }
  .qr-preview { align-items: flex-start; }
}
</style>
