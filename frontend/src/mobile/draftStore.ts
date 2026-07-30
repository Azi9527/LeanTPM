import { vaultGet, vaultRemove, vaultSet } from './secureVault'

export type MobileWorkflow = 'inspection' | 'maintenance'

export interface MobileDraftEnvelope<T> {
  schemaVersion: 1
  workflow: MobileWorkflow
  taskId: number
  taskVersion: number
  updatedAt: string
  pendingSubmit: boolean
  idempotencyKey: string
  payload: T
}

const INDEX_KEY = 'draft:index'

function draftKey(workflow: MobileWorkflow, taskId: number): string {
  return `draft:${workflow}:${taskId}`
}

async function index(): Promise<string[]> {
  const value = await vaultGet(INDEX_KEY)
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed)
      ? parsed.filter((item): item is string => typeof item === 'string')
      : []
  } catch {
    return []
  }
}

async function saveIndex(keys: string[]): Promise<void> {
  await vaultSet(INDEX_KEY, JSON.stringify([...new Set(keys)]))
}

export async function saveMobileDraft<T>(
  envelope: MobileDraftEnvelope<T>,
): Promise<void> {
  const key = draftKey(envelope.workflow, envelope.taskId)
  await vaultSet(key, JSON.stringify(envelope))
  const keys = await index()
  if (!keys.includes(key)) await saveIndex([...keys, key])
}

export async function loadMobileDraft<T>(
  workflow: MobileWorkflow,
  taskId: number,
): Promise<MobileDraftEnvelope<T> | null> {
  const value = await vaultGet(draftKey(workflow, taskId))
  if (!value) return null
  try {
    const parsed = JSON.parse(value) as MobileDraftEnvelope<T>
    return parsed.schemaVersion === 1
      && parsed.workflow === workflow
      && parsed.taskId === taskId
      ? parsed
      : null
  } catch {
    return null
  }
}

export async function removeMobileDraft(
  workflow: MobileWorkflow,
  taskId: number,
): Promise<void> {
  const key = draftKey(workflow, taskId)
  await vaultRemove(key)
  await saveIndex((await index()).filter((item) => item !== key))
}

export async function countMobileDrafts(): Promise<number> {
  return (await index()).length
}

export async function purgeExpiredMobileDrafts(retentionDays: number): Promise<number> {
  const keys = await index()
  const cutoff = Date.now() - Math.max(1, retentionDays) * 86_400_000
  const retained: string[] = []
  let removed = 0
  for (const key of keys) {
    const value = await vaultGet(key)
    if (!value) continue
    try {
      const draft = JSON.parse(value) as MobileDraftEnvelope<unknown>
      if (Date.parse(draft.updatedAt) < cutoff) {
        await vaultRemove(key)
        removed += 1
      } else {
        retained.push(key)
      }
    } catch {
      await vaultRemove(key)
      removed += 1
    }
  }
  await saveIndex(retained)
  return removed
}

export function newIdempotencyKey(workflow: MobileWorkflow, taskId: number): string {
  const random = typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`
  return `mobile-${workflow}-${taskId}-${random}`
}
