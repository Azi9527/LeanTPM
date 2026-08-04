import { vaultGet, vaultRemove, vaultSet } from '@/mobile/secureVault'

const CREDENTIALS_KEY = 'login.credentials.v1'

export interface RememberedCredentials {
  username: string
  password: string
}

export async function loadRememberedCredentials(): Promise<RememberedCredentials | null> {
  try {
    const raw = await vaultGet(CREDENTIALS_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<RememberedCredentials>
    const username = String(parsed.username || '').trim()
    const password = String(parsed.password || '')
    return username && password ? { username, password } : null
  } catch {
    return null
  }
}

export async function saveRememberedCredentials(username: string, password: string): Promise<void> {
  try {
    await vaultSet(CREDENTIALS_KEY, JSON.stringify({ username: username.trim(), password }))
  } catch {
    // Credential persistence must never turn a successful authentication into a failed login.
  }
}

export async function clearRememberedCredentials(): Promise<void> {
  try {
    await vaultRemove(CREDENTIALS_KEY)
  } catch {
    // Private browser modes can reject persistent storage; login can still continue.
  }
}

export async function updateRememberedPassword(username: string, password: string): Promise<void> {
  const saved = await loadRememberedCredentials()
  if (saved?.username === username) await saveRememberedCredentials(username, password)
}
