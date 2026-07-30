import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as authApi from '@/api/auth'
import { clearTokens, hasToken, storeTokens } from '@/utils/http'
import type { UserProfile } from '@/types/api'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserProfile | null>(null)
  const initialized = ref(false)

  const displayName = computed(() => user.value?.realName || user.value?.username || '用户')
  const permissions = computed(() => new Set(user.value?.permissions || []))

  async function signIn(
    username: string,
    password: string,
    captchaId?: string,
    captchaCode?: string,
  ): Promise<UserProfile> {
    const result = await authApi.login(username, password, captchaId, captchaCode)
    await storeTokens(result.tokens)
    user.value = result.user
    initialized.value = true
    return result.user
  }

  async function loadProfile(): Promise<UserProfile | null> {
    if (!hasToken()) {
      initialized.value = true
      return null
    }
    try {
      user.value = await authApi.currentUser()
      return user.value
    } catch {
      await clearTokens()
      user.value = null
      return null
    } finally {
      initialized.value = true
    }
  }

  async function updatePassword(currentPassword: string, newPassword: string): Promise<void> {
    const tokens = await authApi.changePassword(currentPassword, newPassword)
    await storeTokens(tokens)
    await loadProfile()
  }

  async function signOut(): Promise<void> {
    try {
      await authApi.logout()
    } finally {
      await clearTokens()
      user.value = null
      initialized.value = true
    }
  }

  function can(permission: string): boolean {
    return permissions.value.has(permission)
  }

  return {
    user,
    initialized,
    displayName,
    permissions,
    signIn,
    loadProfile,
    updatePassword,
    signOut,
    can,
  }
})
