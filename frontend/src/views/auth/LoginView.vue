<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { captcha as fetchCaptcha, type CaptchaChallenge } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'
import { nativeContainer } from '@/mobile/secureVault'
import {
  clearRememberedCredentials,
  loadRememberedCredentials,
  saveRememberedCredentials,
} from '@/utils/rememberedCredentials'
import BrandLogo from '@/components/branding/BrandLogo.vue'
import { useBranding } from '@/branding/branding'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const branding = useBranding()
const formRef = ref<FormInstance>()
const loginBoxRef = ref<HTMLElement>()
const loading = ref(false)
const captchaLoading = ref(false)
const remember = ref(true)
const challenge = ref<CaptchaChallenge>({ enabled: true })
const form = reactive({ username: '', password: '', captchaCode: '' })
const rules: FormRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  captchaCode: [{
    validator: (_rule, value, callback) => {
      if (challenge.value.enabled && !String(value || '').trim()) {
        callback(new Error('请输入验证码'))
        return
      }
      callback()
    },
    trigger: 'blur',
  }],
}

async function loadCaptcha() {
  captchaLoading.value = true
  try {
    challenge.value = await fetchCaptcha()
    form.captchaCode = ''
  } catch (error) {
    challenge.value = { enabled: true }
    ElMessage.error(errorMessage(error, '验证码加载失败'))
  } finally {
    captchaLoading.value = false
  }
}

async function submit() {
  if (loading.value) return
  await nextTick()
  syncCredentialInputs()
  const valid = await formRef.value?.validate().then(() => true).catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await auth.signIn(
      form.username.trim(),
      form.password,
      challenge.value.captchaId,
      form.captchaCode.trim(),
    )
    if (remember.value) {
      await saveRememberedCredentials(form.username, form.password)
    } else {
      await clearRememberedCredentials()
    }
    const redirect = typeof route.query.redirect === 'string'
      ? route.query.redirect
      : nativeContainer ? '/mobile/workbench' : '/dashboard'
    await router.replace(redirect)
  } catch (error) {
    ElMessage.error(errorMessage(error, '登录失败'))
    if (challenge.value.enabled) {
      await loadCaptcha()
    }
  } finally {
    loading.value = false
  }
}

function syncCredentialInputs() {
  const username = loginBoxRef.value
    ?.querySelector<HTMLInputElement>('input[name="username"]')
    ?.value ?? ''
  const password = loginBoxRef.value
    ?.querySelector<HTMLInputElement>('input[name="password"]')
    ?.value ?? ''
  if (username !== form.username) form.username = username
  if (password !== form.password) form.password = password
}

function syncCredentialInputEvent(event: Event) {
  const input = event.target
  if (!(input instanceof HTMLInputElement)) return
  if (input.name === 'username') form.username = input.value
  if (input.name === 'password') form.password = input.value
}

onMounted(async () => {
  const saved = await loadRememberedCredentials()
  if (saved) {
    form.username = saved.username
    form.password = saved.password
    remember.value = true
  }
  await loadCaptcha()
})
</script>

<template>
  <main class="login-page">
    <section class="login-story">
      <div class="story-grid" aria-hidden="true"></div>
      <div class="story-content">
        <div class="story-brand"><BrandLogo :height="64" light /></div>
        <p class="eyebrow">LEAN EQUIPMENT OPERATIONS</p>
        <h1>让每一台设备<br />都有清晰的运行脉络</h1>
        <p class="story-lead">从设备档案、点检维保到 OEE 分析，构建可追踪、可执行、可持续改善的设备管理闭环。</p>
        <div class="process-line">
          <span class="active">设备主数据</span><i></i><span>点检</span><i></i><span>维保</span><i></i><span>OEE</span>
        </div>
      </div>
      <div class="status-panel">
        <span class="pulse"></span>
        <div><strong>基础平台已就绪</strong><small>安全 · 权限 · 字典 · 审计</small></div>
      </div>
    </section>

    <section class="login-panel">
      <div class="mobile-brand"><BrandLogo :height="54" /></div>
      <div ref="loginBoxRef" class="login-box" @input.capture="syncCredentialInputEvent">
        <p class="login-eyebrow">欢迎回来</p>
        <h2>登录 {{ branding.shortName }}</h2>
        <p class="login-description">使用企业分配的账号进入设备管理平台</p>
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
          <el-form-item label="账号" prop="username">
            <el-input v-model="form.username" name="username" size="large" placeholder="请输入账号" autocomplete="username">
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              name="password"
              size="large"
              type="password"
              placeholder="请输入密码"
              autocomplete="current-password"
              show-password
              @keyup.enter="submit"
            >
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item v-if="challenge.enabled" label="验证码" prop="captchaCode">
            <div class="captcha-row">
              <el-input
                v-model="form.captchaCode"
                size="large"
                maxlength="4"
                placeholder="请输入验证码"
                autocomplete="off"
                @keyup.enter="submit"
              >
                <template #prefix><el-icon><Key /></el-icon></template>
              </el-input>
              <button
                class="captcha-image"
                type="button"
                :disabled="captchaLoading"
                title="点击刷新验证码"
                @click="loadCaptcha"
              >
                <img v-if="challenge.imageDataUrl" :src="challenge.imageDataUrl" alt="验证码，点击刷新" />
                <span v-else>刷新</span>
              </button>
            </div>
          </el-form-item>
          <div class="login-options">
            <el-checkbox v-model="remember">记住账号和密码</el-checkbox>
            <span>连续失败 5 次将临时锁定</span>
          </div>
          <el-button
            class="login-button"
            type="primary"
            size="large"
            :loading="loading"
            :disabled="challenge.enabled && !challenge.captchaId"
            @click="submit"
          >
            进入系统
          </el-button>
        </el-form>
        <p class="login-help">首次登录后需修改初始密码。如无法登录，请联系系统管理员。</p>
        <el-button v-if="nativeContainer" text @click="router.push('/mobile/setup')">
          <el-icon><Connection /></el-icon>配置企业服务地址
        </el-button>
      </div>
      <footer>© 2026 {{ branding.shortName }} · {{ branding.subtitle }}</footer>
    </section>
  </main>
</template>

<style scoped lang="scss">
.login-page {
  display: grid;
  grid-template-columns: minmax(520px, 1.12fr) minmax(420px, 0.88fr);
  min-height: 100vh;
}

.login-story {
  position: relative;
  display: flex;
  overflow: hidden;
  align-items: center;
  padding: 8vw;
  color: #fff;
  background:
    radial-gradient(circle at 75% 30%, rgba(var(--tpm-secondary-rgb), 0.42), transparent 26%),
    linear-gradient(145deg, var(--tpm-sidebar) 0%, var(--tpm-primary-strong) 65%, var(--tpm-primary) 100%);
}

.story-grid {
  position: absolute;
  inset: 0;
  opacity: 0.2;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.08) 1px, transparent 1px);
  background-size: 48px 48px;
  transform: perspective(500px) rotateX(55deg) scale(1.5) translateY(22%);
  transform-origin: bottom;
}

.story-grid::after {
  position: absolute;
  inset: 30% 10% 10% 38%;
  border: 1px solid rgba(255, 255, 255, 0.3);
  border-radius: 50%;
  content: "";
  box-shadow:
    0 0 0 48px rgba(255, 255, 255, 0.025),
    0 0 0 96px rgba(255, 255, 255, 0.02);
}

.story-content {
  position: relative;
  z-index: 2;
  max-width: 640px;
}

.story-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: clamp(70px, 14vh, 150px);
  width: min(420px, 80%);
}

.eyebrow,
.login-eyebrow {
  color: var(--tpm-secondary-soft);
  font-size: 11px;
  font-weight: 750;
  letter-spacing: 0.2em;
}

h1 {
  margin: 16px 0 24px;
  font-size: clamp(42px, 4.6vw, 70px);
  line-height: 1.14;
  letter-spacing: -0.045em;
}

.story-lead {
  max-width: 540px;
  color: rgba(255, 255, 255, .76);
  font-size: 17px;
  line-height: 1.9;
}

.process-line {
  display: flex;
  align-items: center;
  margin-top: 54px;
  color: rgba(255, 255, 255, .6);
  font-size: 12px;

  span.active {
    color: #fff;
  }

  i {
    width: 34px;
    height: 1px;
    margin: 0 9px;
    background: rgba(255, 255, 255, 0.22);
  }
}

.status-panel {
  position: absolute;
  right: 34px;
  bottom: 34px;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 18px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 10px;
    background: rgba(var(--tpm-neutral-rgb), 0.48);
  backdrop-filter: blur(10px);

  div {
    display: flex;
    flex-direction: column;
  }

  strong {
    font-size: 12px;
  }

  small {
    margin-top: 3px;
    color: rgba(255, 255, 255, .66);
    font-size: 10px;
  }
}

.pulse {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--tpm-secondary);
  box-shadow: 0 0 0 5px rgba(var(--tpm-secondary-rgb), 0.18);
}

.login-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  padding: 52px clamp(30px, 7vw, 100px);
  background: #fff;
}

.login-box {
  width: min(100%, 420px);
  margin: auto 0;
}

.login-eyebrow {
  margin: 0 0 9px;
  color: var(--tpm-primary);
}

h2 {
  margin: 0;
  color: #102936;
  font-size: 32px;
  letter-spacing: -0.03em;
}

.login-description {
  margin: 11px 0 38px;
  color: var(--tpm-text-secondary);
  font-size: 14px;
}

.login-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: -2px 0 24px;
  color: var(--tpm-text-secondary);
  font-size: 11px;
}

.captcha-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 160px;
  gap: 10px;
  width: 100%;
}

.captcha-image {
  overflow: hidden;
  height: 40px;
  padding: 0;
  border: 1px solid #d8e1e6;
  border-radius: 7px;
  color: var(--tpm-primary);
  background: #eef5f7;
  cursor: pointer;

  &:disabled {
    cursor: wait;
    opacity: 0.65;
  }

  img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.login-button {
  width: 100%;
  min-height: 48px;
  border-radius: 7px;
  font-weight: 650;
  letter-spacing: 0.08em;
}

.login-help {
  margin-top: 24px;
  color: #87939e;
  font-size: 11px;
  line-height: 1.7;
  text-align: center;
}

.mobile-brand {
  display: none;
}

footer {
  color: #a0a9b1;
  font-size: 11px;
}

:deep(.el-form-item__label) {
  color: #344b58;
  font-weight: 600;
}

:deep(.el-input__wrapper) {
  border-radius: 7px;
}

@media (max-width: 900px) {
  .login-page {
    display: block;
    min-height: 100vh;
    background: #fff;
  }

  .login-story {
    display: none;
  }

  .login-panel {
    justify-content: flex-start;
    min-height: 100vh;
    padding: 28px 22px;
  }

  .mobile-brand {
    display: flex;
    align-items: center;
    align-self: flex-start;
    width: min(280px, 74vw);
  }

  .login-box {
    margin: 16vh 0 auto;
  }

  h2 {
    font-size: 28px;
  }

  .captcha-row {
    grid-template-columns: minmax(0, 1fr) 132px;
  }
}
</style>
