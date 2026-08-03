import { WebPlugin, registerPlugin } from '@capacitor/core'
import type { MobileMessage } from '@/api/mobile'
import { vaultGet, vaultSet } from './secureVault'

interface LocalAlertsPlugin {
  requestPermission(): Promise<{ granted: boolean }>
  show(options: { id: number, title: string, body: string, route: string }): Promise<void>
  getLaunchRoute(): Promise<{ route?: string }>
}

class LocalAlertsWeb extends WebPlugin implements LocalAlertsPlugin {
  async requestPermission(): Promise<{ granted: boolean }> {
    if (!('Notification' in window)) return { granted: false }
    const permission = Notification.permission === 'default'
      ? await Notification.requestPermission()
      : Notification.permission
    return { granted: permission === 'granted' }
  }

  async show(options: { title: string, body: string }): Promise<void> {
    if ('Notification' in window && Notification.permission === 'granted') {
      new Notification(options.title, { body: options.body })
    }
  }

  async getLaunchRoute(): Promise<{ route?: string }> {
    return {}
  }
}

const LocalAlerts = registerPlugin<LocalAlertsPlugin>('LocalAlerts', {
  web: () => Promise.resolve(new LocalAlertsWeb()),
})

const NOTIFIED_KEY = 'notification:notified-message-ids'

export async function publishNewLocalAlerts(messages: MobileMessage[]): Promise<void> {
  const unread = messages.filter((message) => !message.readTime).slice(0, 10)
  if (!unread.length) return
  const permission = await LocalAlerts.requestPermission()
  if (!permission.granted) return
  const notified = new Set(await notifiedIds())
  for (const message of unread) {
    if (notified.has(message.id)) continue
    await LocalAlerts.show({
      id: normalizedNotificationId(message.id),
      title: message.title,
      body: message.content,
      route: message.routePath || '/mobile/messages',
    })
    notified.add(message.id)
  }
  await vaultSet(NOTIFIED_KEY, JSON.stringify([...notified].slice(-300)))
}

export async function consumeNotificationLaunchRoute(): Promise<string | undefined> {
  return (await LocalAlerts.getLaunchRoute()).route
}

async function notifiedIds(): Promise<number[]> {
  const value = await vaultGet(NOTIFIED_KEY)
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed.filter((id): id is number => Number.isSafeInteger(id)) : []
  } catch { return [] }
}

function normalizedNotificationId(value: number): number {
  return Math.max(1, Math.abs(value % 2_000_000_000))
}
