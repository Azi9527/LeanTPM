<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useMobileStore } from '@/stores/mobile'
import {
  clearTokens,
  errorMessage,
  serverBaseUrl,
  setServerBaseUrl,
} from '@/utils/http'
import { nativeContainer } from '@/mobile/secureVault'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const mobile = useMobileStore()
const changing = ref(false)
const savingServer = ref(false)
const password = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
const serverUrl = ref(serverBaseUrl())
const forceChange = computed(() => auth.user?.mustChangePassword || route.query.changePassword === '1')

async function changePassword() {
  if (!password.currentPassword || password.newPassword.length < 8) {
    ElMessage.warning('请填写当前密码，新密码至少 8 位')
    return
  }
  if (password.newPassword !== password.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  changing.value = true
  try {
    await auth.updatePassword(password.currentPassword, password.newPassword)
    Object.assign(password, { currentPassword: '', newPassword: '', confirmPassword: '' })
    ElMessage.success('密码修改成功')
    await router.replace('/mobile/profile')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    changing.value = false
  }
}

async function saveServer() {
  try {
    await ElMessageBox.confirm(
      '更换服务地址将清除当前登录令牌，需要重新登录。是否继续？',
      '更换服务地址',
      { type: 'warning' },
    )
  } catch {
    return
  }
  savingServer.value = true
  try {
    await clearTokens()
    await setServerBaseUrl(serverUrl.value)
    ElMessage.success('服务地址已保存，请重新登录')
    await router.replace('/login?redirect=/mobile/workbench')
  } catch (error) {
    ElMessage.error(errorMessage(error, '服务地址格式不正确'))
  } finally {
    savingServer.value = false
  }
}

async function signOut() {
  await auth.signOut()
  await router.replace('/login?redirect=/mobile/workbench')
}
</script>

<template>
  <div class="profile-page">
    <section class="identity-card">
      <span class="avatar">{{ auth.displayName.slice(0, 1) }}</span>
      <div><h1>{{ auth.displayName }}</h1><p>{{ auth.user?.username }} · {{ auth.user?.roles?.join(' / ') }}</p></div>
    </section>

    <el-alert
      v-if="forceChange"
      title="首次登录必须修改初始密码"
      description="完成修改后才能安全开展移动作业。"
      type="warning"
      :closable="false"
      show-icon
    />

    <section class="settings-card">
      <h2>安全与草稿</h2>
      <div class="setting-row"><span>本地加密草稿</span><strong>{{ mobile.draftCount }} 份</strong></div>
      <div class="setting-row"><span>当前网络</span><strong>{{ mobile.online ? '在线' : '离线' }}</strong></div>
      <div class="setting-row"><span>运行容器</span><strong>{{ nativeContainer ? 'Android APK' : '手机浏览器' }}</strong></div>
    </section>

    <section class="settings-card">
      <h2>修改密码</h2>
      <el-input v-model="password.currentPassword" type="password" show-password size="large" placeholder="当前密码" />
      <el-input v-model="password.newPassword" type="password" show-password size="large" placeholder="新密码（至少 8 位）" />
      <el-input v-model="password.confirmPassword" type="password" show-password size="large" placeholder="确认新密码" />
      <el-button type="primary" size="large" :loading="changing" @click="changePassword">确认修改</el-button>
    </section>

    <section v-if="nativeContainer" class="settings-card">
      <h2>服务器</h2>
      <p>生产环境请使用 HTTPS。模拟器访问本机后端可使用 http://10.0.2.2:8080。</p>
      <el-input v-model="serverUrl" size="large" placeholder="https://tpm.example.com/api/v1" />
      <el-button plain size="large" :loading="savingServer" @click="saveServer">保存并重新登录</el-button>
    </section>

    <el-button class="logout-button" size="large" @click="signOut">退出登录</el-button>
  </div>
</template>

<style scoped>
.profile-page { display: grid; gap: 16px; }
.identity-card, .settings-card { padding: 20px; border-radius: 20px; background: white; box-shadow: 0 7px 24px rgba(23, 58, 69, .07); }
.identity-card { display: flex; align-items: center; gap: 14px; }
.avatar { display: grid; width: 56px; height: 56px; place-items: center; border-radius: 18px; color: white; background: linear-gradient(135deg, #0a5368, #1691aa); font-size: 24px; font-weight: 800; }
.identity-card h1, .identity-card p { margin: 0; }
.identity-card p { margin-top: 5px; color: #788991; font-size: 12px; }
.settings-card { display: grid; gap: 12px; }
.settings-card h2, .settings-card p { margin: 0; }
.settings-card h2 { font-size: 17px; }
.settings-card p { color: #788991; line-height: 1.6; font-size: 12px; }
.setting-row { display: flex; align-items: center; justify-content: space-between; padding: 12px 0; border-bottom: 1px solid #eef2f3; }
.setting-row span { color: #71838b; font-size: 13px; }
.logout-button { min-height: 50px; border-radius: 14px; }
</style>
