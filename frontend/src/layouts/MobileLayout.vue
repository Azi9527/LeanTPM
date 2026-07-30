<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useMobileStore } from '@/stores/mobile'
import { errorMessage } from '@/utils/http'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const mobile = useMobileStore()

const items = computed(() => [
  { path: '/mobile/workbench', label: '工作台', icon: 'House', show: auth.can('mobile:workbench:view') },
  { path: '/mobile/scan', label: '扫码', icon: 'FullScreen', show: auth.can('mobile:scan') },
  { path: '/mobile/tasks', label: '任务', icon: 'Finished', show: auth.can('mobile:task:view') },
  { path: '/mobile/messages', label: '消息', icon: 'Bell', show: auth.can('mobile:message:view') },
  { path: '/mobile/profile', label: '我的', icon: 'User', show: auth.can('mobile:profile:view') },
].filter((item) => item.show))

function active(path: string): boolean {
  if (path === '/mobile/tasks') {
    return route.path === path
      || route.path === '/mobile/inspection'
      || route.path === '/mobile/maintenance'
  }
  return route.path.startsWith(path)
}

onMounted(async () => {
  try {
    await mobile.refresh()
    if (auth.user?.mustChangePassword) {
      await router.replace({ path: '/mobile/profile', query: { changePassword: '1' } })
    }
  } catch (error) {
    ElMessage.error(errorMessage(error, '移动工作台加载失败'))
  }
})
</script>

<template>
  <div class="mobile-app">
    <header class="mobile-topbar">
      <div class="mobile-brand">
        <span>LT</span>
        <div><strong>LeanTPM</strong><small>现场作业</small></div>
      </div>
      <div class="network-pill" :class="{ offline: !mobile.online }">
        <i></i>{{ mobile.online ? '在线' : '离线' }}
      </div>
    </header>

    <el-alert
      v-if="!mobile.online"
      class="offline-alert"
      title="网络已断开，填写内容将保存为本地加密草稿"
      type="warning"
      :closable="false"
      show-icon
    />

    <main class="mobile-content">
      <router-view />
    </main>

    <nav class="mobile-bottom-nav" aria-label="移动端主导航">
      <button
        v-for="item in items"
        :key="item.path"
        type="button"
        :class="{ active: active(item.path) }"
        @click="router.push(item.path)"
      >
        <el-badge
          :value="item.path === '/mobile/messages' ? mobile.messages.length : 0"
          :hidden="item.path !== '/mobile/messages' || !mobile.messages.length"
          :max="99"
        >
          <el-icon><component :is="item.icon" /></el-icon>
        </el-badge>
        <span>{{ item.label }}</span>
      </button>
    </nav>
  </div>
</template>

<style scoped>
.mobile-app {
  min-height: 100dvh;
  color: #172b36;
  background: #f2f6f7;
}
.mobile-topbar {
  position: sticky;
  z-index: 20;
  top: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 64px;
  padding: max(10px, env(safe-area-inset-top)) 18px 10px;
  color: white;
  background: linear-gradient(135deg, #073848, #0b657d);
  box-shadow: 0 8px 24px rgba(5, 49, 63, .18);
}
.mobile-brand { display: flex; align-items: center; gap: 10px; }
.mobile-brand > span {
  display: grid; width: 38px; height: 38px; place-items: center;
  border-radius: 12px; font-weight: 800;
  background: rgba(255, 255, 255, .16);
}
.mobile-brand div { display: grid; }
.mobile-brand small { opacity: .7; font-size: 11px; }
.network-pill {
  display: flex; align-items: center; gap: 6px; padding: 6px 10px;
  border-radius: 999px; font-size: 12px;
  background: rgba(255, 255, 255, .12);
}
.network-pill i { width: 7px; height: 7px; border-radius: 50%; background: #4ade80; }
.network-pill.offline i { background: #fbbf24; }
.offline-alert { position: sticky; z-index: 15; top: 64px; border-radius: 0; }
.mobile-content { padding: 16px 14px calc(88px + env(safe-area-inset-bottom)); }
.mobile-bottom-nav {
  position: fixed; z-index: 30; right: 0; bottom: 0; left: 0;
  display: grid; grid-template-columns: repeat(5, 1fr);
  padding: 8px 6px max(8px, env(safe-area-inset-bottom));
  border-top: 1px solid rgba(15, 55, 67, .09);
  background: rgba(255, 255, 255, .96);
  backdrop-filter: blur(18px);
}
.mobile-bottom-nav button {
  display: grid; min-width: 0; min-height: 48px; place-items: center;
  gap: 3px; border: 0; color: #71828a; background: transparent;
  font: inherit; font-size: 11px;
}
.mobile-bottom-nav .el-icon { font-size: 22px; }
.mobile-bottom-nav button.active { color: #08708a; font-weight: 700; }
@media (min-width: 760px) {
  .mobile-app { max-width: 760px; margin: 0 auto; box-shadow: 0 0 40px rgba(3, 42, 54, .12); }
  .mobile-bottom-nav { right: auto; left: 50%; width: 760px; transform: translateX(-50%); }
}
</style>
