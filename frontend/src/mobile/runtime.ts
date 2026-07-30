import { readonly, ref } from 'vue'
import { App } from '@capacitor/app'
import { Network } from '@capacitor/network'
import { nativeContainer } from './secureVault'

const onlineState = ref(navigator.onLine)
let initialized = false

export const online = readonly(onlineState)

export async function initializeMobileRuntime(): Promise<void> {
  if (initialized) return
  initialized = true
  try {
    onlineState.value = (await Network.getStatus()).connected
    await Network.addListener('networkStatusChange', (status) => {
      onlineState.value = status.connected
    })
  } catch {
    onlineState.value = navigator.onLine
    window.addEventListener('online', () => {
      onlineState.value = true
    })
    window.addEventListener('offline', () => {
      onlineState.value = false
    })
  }

  if (nativeContainer) {
    await App.addListener('appStateChange', async ({ isActive }) => {
      if (!isActive) return
      try {
        onlineState.value = (await Network.getStatus()).connected
      } catch {
        onlineState.value = navigator.onLine
      }
    })
  }
}
