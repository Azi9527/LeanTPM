<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppMenuItem from '@/components/navigation/AppMenuItem.vue'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'
import type { MenuItem } from '@/types/api'

interface TreeMenu extends MenuItem {
  children?: TreeMenu[]
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const collapsed = ref(false)
const mobileMenuOpen = ref(false)
const changingPassword = ref(false)
const passwordDialogVisible = ref(false)
const passwordForm = ref({ currentPassword: '', newPassword: '', confirmPassword: '' })

const title = computed(() => (route.meta.title as string) || 'LeanTPM')
const treeMenus = computed<TreeMenu[]>(() => {
  const items = (auth.user?.menus || []).filter((item) => item.menuType !== 'BUTTON')
  const byId = new Map<number, TreeMenu>()
  items.forEach((item) => byId.set(item.id, { ...item, children: [] }))
  const roots: TreeMenu[] = []
  byId.forEach((item) => {
    const parent = byId.get(item.parentId)
    if (parent) parent.children?.push(item)
    else roots.push(item)
  })
  return roots
})

const activeMenu = computed(() => route.path)

onMounted(() => {
  passwordDialogVisible.value = Boolean(auth.user?.mustChangePassword)
})

async function handleSignOut() {
  await auth.signOut()
  await router.replace('/login')
}

async function submitPassword() {
  if (passwordForm.value.newPassword !== passwordForm.value.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  changingPassword.value = true
  try {
    await auth.updatePassword(passwordForm.value.currentPassword, passwordForm.value.newPassword)
    passwordDialogVisible.value = false
    passwordForm.value = { currentPassword: '', newPassword: '', confirmPassword: '' }
    ElMessage.success('密码修改成功')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    changingPassword.value = false
  }
}

function navigate(index: string) {
  mobileMenuOpen.value = false
  router.push(index)
}
</script>

<template>
  <div class="app-layout">
    <aside class="sidebar" :class="{ collapsed }">
      <div class="brand">
        <span class="brand-mark">LT</span>
        <span v-if="!collapsed" class="brand-copy">
          <strong>LeanTPM</strong>
          <small>精益设备管理</small>
        </span>
      </div>
      <el-scrollbar class="menu-scroll">
        <el-menu
          :default-active="activeMenu"
          :collapse="collapsed"
          background-color="transparent"
          text-color="#b8cad1"
          active-text-color="#ffffff"
          unique-opened
          router
        >
          <app-menu-item v-for="item in treeMenus" :key="item.id" :item="item" />
        </el-menu>
      </el-scrollbar>
      <button class="collapse-button" type="button" @click="collapsed = !collapsed">
        <el-icon><Expand v-if="collapsed" /><Fold v-else /></el-icon>
        <span v-if="!collapsed">收起导航</span>
      </button>
    </aside>

    <div class="main-column">
      <header class="topbar">
        <button class="mobile-menu-button" type="button" aria-label="打开导航" @click="mobileMenuOpen = true">
          <el-icon><Menu /></el-icon>
        </button>
        <div>
          <span class="topbar-eyebrow">LeanTPM /</span>
          <strong>{{ title }}</strong>
        </div>
        <div class="topbar-actions">
          <el-tooltip content="消息中心">
            <el-badge is-dot>
              <el-button circle text aria-label="消息中心" @click="router.push('/notifications/messages')"><Bell /></el-button>
            </el-badge>
          </el-tooltip>
          <el-dropdown>
            <button class="user-menu" type="button">
              <span class="avatar">{{ auth.displayName.slice(0, 1) }}</span>
              <span class="user-copy">
                <strong>{{ auth.displayName }}</strong>
                <small>{{ auth.user?.roles?.[0] || '用户' }}</small>
              </span>
              <el-icon><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="passwordDialogVisible = true">修改密码</el-dropdown-item>
                <el-dropdown-item divided @click="handleSignOut">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content">
        <router-view />
      </main>

      <nav class="mobile-bottom-nav" aria-label="移动端快捷导航">
        <button type="button" @click="router.push('/dashboard')"><el-icon><House /></el-icon><span>工作台</span></button>
        <button type="button" @click="router.push('/coming-soon/scan')"><el-icon><FullScreen /></el-icon><span>扫码</span></button>
        <button type="button" @click="router.push('/coming-soon/tasks')"><el-icon><List /></el-icon><span>任务</span></button>
        <button type="button" @click="router.push('/notifications/messages')"><el-icon><Bell /></el-icon><span>消息</span></button>
        <button type="button" @click="passwordDialogVisible = true"><el-icon><User /></el-icon><span>我的</span></button>
      </nav>
    </div>

    <el-drawer v-model="mobileMenuOpen" direction="ltr" size="82%" :with-header="false" class="mobile-drawer">
      <div class="brand drawer-brand">
        <span class="brand-mark">LT</span>
        <span class="brand-copy"><strong>LeanTPM</strong><small>精益设备管理</small></span>
      </div>
      <el-menu :default-active="activeMenu" @select="navigate">
        <app-menu-item v-for="item in treeMenus" :key="item.id" :item="item" />
      </el-menu>
    </el-drawer>

    <el-dialog
      v-model="passwordDialogVisible"
      title="修改登录密码"
      width="min(460px, 92vw)"
      :close-on-click-modal="!auth.user?.mustChangePassword"
      :close-on-press-escape="!auth.user?.mustChangePassword"
      :show-close="!auth.user?.mustChangePassword"
    >
      <el-alert
        v-if="auth.user?.mustChangePassword"
        title="首次登录必须修改密码后才能使用系统。"
        type="warning"
        :closable="false"
        show-icon
        class="password-alert"
      />
      <el-form label-position="top" @submit.prevent="submitPassword">
        <el-form-item label="当前密码">
          <el-input v-model="passwordForm.currentPassword" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="passwordForm.newPassword" type="password" show-password autocomplete="new-password" />
          <span class="form-hint">至少 6 位</span>
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="passwordForm.confirmPassword" type="password" show-password autocomplete="new-password" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button v-if="!auth.user?.mustChangePassword" @click="passwordDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="changingPassword" @click="submitPassword">确认修改</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.app-layout {
  display: flex;
  min-height: 100vh;
}

.sidebar {
  position: fixed;
  inset: 0 auto 0 0;
  z-index: 20;
  display: flex;
  flex-direction: column;
  width: 246px;
  color: #fff;
  background:
    radial-gradient(circle at 15% 0%, rgba(30, 151, 178, 0.32), transparent 35%),
    var(--tpm-sidebar);
  transition: width 0.2s ease;

  &.collapsed {
    width: 72px;
  }
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 68px;
  padding: 0 17px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.brand-mark {
  display: grid;
  flex: 0 0 38px;
  place-items: center;
  width: 38px;
  height: 38px;
  border: 1px solid rgba(255, 255, 255, 0.22);
  border-radius: 8px;
  color: #fff;
  background: linear-gradient(145deg, #1688a6, #0a607d);
  box-shadow: inset 0 1px rgba(255, 255, 255, 0.18);
  font-size: 14px;
  font-weight: 800;
  letter-spacing: 0.06em;
}

.brand-copy {
  display: flex;
  flex-direction: column;
  white-space: nowrap;

  strong {
    font-size: 17px;
    letter-spacing: 0.02em;
  }

  small {
    margin-top: 2px;
    color: #92b2be;
    font-size: 10px;
    letter-spacing: 0.12em;
  }
}

.menu-scroll {
  flex: 1;
  padding: 10px 8px;

  :deep(.el-menu) {
    border: none;
  }

  :deep(.el-menu-item),
  :deep(.el-sub-menu__title) {
    height: 46px;
    margin: 3px 0;
    border-radius: 7px;
  }

  :deep(.el-menu-item.is-active) {
    background: linear-gradient(90deg, rgba(22, 136, 166, 0.95), rgba(22, 136, 166, 0.55));
    box-shadow: inset 3px 0 #f3a712;
  }
}

.collapse-button {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 52px;
  border: 0;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  color: #92b2be;
  background: transparent;
  cursor: pointer;
}

.main-column {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 100vh;
  margin-left: 246px;
  transition: margin-left 0.2s ease;
}

.sidebar.collapsed + .main-column {
  margin-left: 72px;
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 15;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 68px;
  padding: 0 24px;
  border-bottom: 1px solid var(--tpm-border);
  background: rgba(255, 255, 255, 0.94);
  backdrop-filter: blur(10px);

  > div:first-of-type {
    display: flex;
    align-items: baseline;
    gap: 6px;
  }
}

.topbar-eyebrow {
  color: var(--tpm-text-secondary);
  font-size: 13px;
}

.topbar-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}

.user-menu {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 0;
  border: 0;
  color: var(--tpm-text);
  background: transparent;
  cursor: pointer;
}

.avatar {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 9px;
  color: #fff;
  background: var(--tpm-primary);
  font-weight: 700;
}

.user-copy {
  display: flex;
  flex-direction: column;
  align-items: flex-start;

  strong {
    font-size: 13px;
  }

  small {
    color: var(--tpm-text-secondary);
    font-size: 10px;
  }
}

.content {
  width: 100%;
  max-width: 1680px;
  margin: 0 auto;
  padding: 22px 24px 36px;
}

.mobile-menu-button,
.mobile-bottom-nav {
  display: none;
}

.password-alert {
  margin-bottom: 18px;
}

.form-hint {
  margin-top: 5px;
  color: var(--tpm-text-secondary);
  font-size: 12px;
}

@media (max-width: 900px) {
  .sidebar {
    display: none;
  }

  .main-column,
  .sidebar.collapsed + .main-column {
    margin-left: 0;
  }

  .topbar {
    height: 58px;
    padding: 0 14px;
  }

  .mobile-menu-button {
    display: grid;
    place-items: center;
    width: 38px;
    height: 38px;
    border: 0;
    border-radius: 8px;
    background: var(--tpm-primary-soft);
    color: var(--tpm-primary);
  }

  .topbar-eyebrow,
  .user-copy,
  .topbar-actions > :first-child {
    display: none;
  }

  .content {
    padding: 16px 12px 84px;
  }

  .mobile-bottom-nav {
    position: fixed;
    inset: auto 0 0;
    z-index: 25;
    display: grid;
    grid-template-columns: repeat(5, 1fr);
    min-height: 66px;
    border-top: 1px solid var(--tpm-border);
    background: rgba(255, 255, 255, 0.98);

    button {
      display: flex;
      align-items: center;
      justify-content: center;
      flex-direction: column;
      gap: 3px;
      border: 0;
      color: var(--tpm-text-secondary);
      background: transparent;
      font-size: 11px;
    }

    button:nth-child(2) .el-icon {
      display: grid;
      place-items: center;
      width: 42px;
      height: 42px;
      margin-top: -18px;
      border: 4px solid var(--tpm-bg);
      border-radius: 50%;
      color: #fff;
      background: var(--tpm-primary);
      font-size: 20px;
    }
  }

  .drawer-brand {
    margin: -20px -20px 12px;
    color: #fff;
    background: var(--tpm-sidebar);
  }
}
</style>
