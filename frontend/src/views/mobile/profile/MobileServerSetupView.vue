<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { captcha } from '@/api/auth'
import {
  errorMessage,
  serverBaseUrl,
  setServerBaseUrl,
} from '@/utils/http'

const router = useRouter()
const value = ref(serverBaseUrl())
const testing = ref(false)

async function saveAndTest() {
  testing.value = true
  try {
    await setServerBaseUrl(value.value)
    await captcha()
    ElMessage.success('服务连接成功')
    await router.replace('/login?redirect=/mobile/workbench')
  } catch (error) {
    ElMessage.error(errorMessage(error, '无法连接服务器，请检查地址、网络和证书'))
  } finally {
    testing.value = false
  }
}
</script>

<template>
  <main class="setup-page">
    <section class="setup-card">
      <div class="setup-icon"><el-icon><Connection /></el-icon></div>
      <p class="eyebrow">LEANTPM MOBILE</p>
      <h1>配置企业服务地址</h1>
      <p class="description">APK 只封装前端业务，数据始终通过企业部署的 LeanTPM 后端访问。生产环境必须使用 HTTPS。</p>
      <el-input v-model="value" size="large" placeholder="https://tpm.example.com">
        <template #prefix><el-icon><Link /></el-icon></template>
      </el-input>
      <el-button type="primary" size="large" :loading="testing" @click="saveAndTest">保存并测试连接</el-button>
      <el-button text @click="router.replace('/login?redirect=/mobile/workbench')">返回登录</el-button>
    </section>
  </main>
</template>

<style scoped>
.setup-page { display: grid; min-height: 100dvh; padding: 24px; place-items: center; background: linear-gradient(150deg, #063847, #0c7188); }
.setup-card { display: grid; width: min(100%, 430px); gap: 15px; padding: 30px 24px; border-radius: 24px; background: white; box-shadow: 0 24px 60px rgba(0, 22, 30, .3); }
.setup-icon { display: grid; width: 58px; height: 58px; place-items: center; border-radius: 18px; color: #08728a; background: #e7f6f8; font-size: 28px; }
.eyebrow, h1, .description { margin: 0; }
.eyebrow { color: #118097; font-size: 11px; font-weight: 800; letter-spacing: .16em; }
h1 { font-size: 26px; }
.description { color: #71838b; line-height: 1.7; font-size: 13px; }
.el-button { min-height: 48px; border-radius: 13px; }
</style>
