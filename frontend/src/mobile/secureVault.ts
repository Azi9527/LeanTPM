import { Capacitor, WebPlugin, registerPlugin } from '@capacitor/core'

interface VaultValue {
  value: string | null
}

interface SecureVaultPlugin {
  get(options: { key: string }): Promise<VaultValue>
  set(options: { key: string; value: string }): Promise<void>
  remove(options: { key: string }): Promise<void>
}

const WEB_PREFIX = 'leantpm_secure_fallback:'

class SecureVaultWeb extends WebPlugin implements SecureVaultPlugin {
  async get(options: { key: string }): Promise<VaultValue> {
    return { value: localStorage.getItem(`${WEB_PREFIX}${options.key}`) }
  }

  async set(options: { key: string; value: string }): Promise<void> {
    localStorage.setItem(`${WEB_PREFIX}${options.key}`, options.value)
  }

  async remove(options: { key: string }): Promise<void> {
    localStorage.removeItem(`${WEB_PREFIX}${options.key}`)
  }
}

const SecureVault = registerPlugin<SecureVaultPlugin>('SecureVault', {
  web: () => Promise.resolve(new SecureVaultWeb()),
})

export const nativeContainer = Capacitor.isNativePlatform()

export async function vaultGet(key: string): Promise<string | null> {
  return (await SecureVault.get({ key })).value
}

export async function vaultSet(key: string, value: string): Promise<void> {
  await SecureVault.set({ key, value })
}

export async function vaultRemove(key: string): Promise<void> {
  await SecureVault.remove({ key })
}
