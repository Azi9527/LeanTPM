<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { extractEquipmentToken, scanEquipmentToken } from '@/mobile/device'
import { errorMessage } from '@/utils/http'

const router = useRouter()
const scanning = ref(false)
const manualValue = ref('')

async function scan() {
  scanning.value = true
  try {
    const token = await scanEquipmentToken()
    await router.push(`/mobile/equipment/${token}`)
  } catch (error) {
    ElMessage.warning(errorMessage(error, '扫码已取消或未识别到设备'))
  } finally {
    scanning.value = false
  }
}

async function resolveManual() {
  const token = extractEquipmentToken(manualValue.value)
  if (!token) {
    ElMessage.warning('请输入 64 位设备令牌或完整的 LeanTPM 设备二维码地址')
    return
  }
  await router.push(`/mobile/equipment/${token}`)
}
</script>

<template>
  <div class="scan-page">
    <section class="scanner-card">
      <div class="scan-frame">
        <span></span><span></span><span></span><span></span>
        <el-icon><FullScreen /></el-icon>
      </div>
      <h1>扫描设备二维码</h1>
      <p>二维码只包含不可推断的安全访问令牌，不包含设备敏感信息。</p>
      <el-button class="scan-button" type="primary" size="large" :loading="scanning" @click="scan">
        <el-icon><Camera /></el-icon>{{ scanning ? '正在打开相机' : '打开相机扫码' }}
      </el-button>
    </section>

    <section class="manual-card">
      <h2>手工识别</h2>
      <p>可粘贴二维码地址或设备安全令牌。</p>
      <el-input v-model="manualValue" size="large" clearable placeholder="粘贴二维码内容">
        <template #append><el-button @click="resolveManual">识别</el-button></template>
      </el-input>
    </section>
  </div>
</template>

<style scoped>
.scan-page { display: grid; gap: 16px; }
.scanner-card, .manual-card { padding: 24px 20px; border-radius: 20px; background: white; box-shadow: 0 8px 26px rgba(23, 58, 69, .08); }
.scanner-card { display: grid; justify-items: center; text-align: center; }
.scan-frame {
  position: relative; display: grid; width: min(58vw, 230px); aspect-ratio: 1;
  margin: 10px 0 22px; place-items: center; border-radius: 24px;
  color: var(--tpm-primary); background: linear-gradient(145deg, var(--tpm-primary-soft), #fff);
}
.scan-frame > span { position: absolute; width: 38px; height: 38px; border-color: var(--tpm-primary); }
.scan-frame > span:nth-child(1) { top: 12px; left: 12px; border-top: 4px solid; border-left: 4px solid; border-radius: 8px 0 0; }
.scan-frame > span:nth-child(2) { top: 12px; right: 12px; border-top: 4px solid; border-right: 4px solid; border-radius: 0 8px 0 0; }
.scan-frame > span:nth-child(3) { bottom: 12px; left: 12px; border-bottom: 4px solid; border-left: 4px solid; border-radius: 0 0 0 8px; }
.scan-frame > span:nth-child(4) { right: 12px; bottom: 12px; border-right: 4px solid; border-bottom: 4px solid; border-radius: 0 0 8px; }
.scan-frame .el-icon { font-size: 72px; opacity: .35; }
h1, h2, p { margin: 0; }
.scanner-card p, .manual-card p { margin: 8px 0 18px; color: #75878f; line-height: 1.7; font-size: 13px; }
.scan-button { width: 100%; min-height: 52px; border-radius: 14px; }
</style>
