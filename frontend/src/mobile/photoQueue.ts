import type { CapturedPhotoEvidence, PhotoEvidenceMetadata } from './device'
import { vaultGet, vaultRemove, vaultSet } from './secureVault'

export type QueuedAttachmentKind = 'attachmentIds' | 'beforeAttachmentIds' | 'afterAttachmentIds'

interface SerializedFile {
  name: string
  type: string
  dataUrl: string
}

export interface QueuedPhoto {
  id: string
  workflow: 'inspection' | 'maintenance'
  taskId: number
  taskItemId: number
  kind: QueuedAttachmentKind
  createdAt: string
  original: SerializedFile
  watermarked: SerializedFile
  metadata: PhotoEvidenceMetadata
}

const INDEX_KEY = 'photo-queue:index'

export async function enqueuePhoto(
  workflow: QueuedPhoto['workflow'],
  kind: QueuedAttachmentKind,
  capture: CapturedPhotoEvidence,
): Promise<string> {
  const id = typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`
  const queued: QueuedPhoto = {
    id,
    workflow,
    taskId: capture.metadata.taskId,
    taskItemId: capture.metadata.taskItemId,
    kind,
    createdAt: new Date().toISOString(),
    original: await serialize(capture.originalFile),
    watermarked: await serialize(capture.watermarkedFile),
    metadata: capture.metadata,
  }
  await vaultSet(key(id), JSON.stringify(queued))
  await saveIndex([...await index(), id])
  return id
}

export async function listQueuedPhotos(): Promise<QueuedPhoto[]> {
  const rows: QueuedPhoto[] = []
  for (const id of await index()) {
    const value = await vaultGet(key(id))
    if (!value) continue
    try { rows.push(JSON.parse(value) as QueuedPhoto) } catch { await removeQueuedPhoto(id) }
  }
  return rows.sort((left, right) => left.createdAt.localeCompare(right.createdAt))
}

export async function removeQueuedPhoto(id: string): Promise<void> {
  await vaultRemove(key(id))
  await saveIndex((await index()).filter((item) => item !== id))
}

export async function countQueuedPhotos(): Promise<number> {
  return (await index()).length
}

export function restoreQueuedCapture(row: QueuedPhoto): CapturedPhotoEvidence {
  return {
    originalFile: deserialize(row.original),
    watermarkedFile: deserialize(row.watermarked),
    metadata: row.metadata,
  }
}

async function serialize(file: File): Promise<SerializedFile> {
  return { name: file.name, type: file.type, dataUrl: await dataUrl(file) }
}

function deserialize(file: SerializedFile): File {
  const [header, encoded] = file.dataUrl.split(',', 2)
  const binary = atob(encoded || '')
  const bytes = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index)
  const type = file.type || header.match(/^data:([^;]+)/)?.[1] || 'image/jpeg'
  return new File([bytes], file.name, { type })
}

function dataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(reader.error || new Error('照片队列编码失败'))
    reader.readAsDataURL(file)
  })
}

async function index(): Promise<string[]> {
  const value = await vaultGet(INDEX_KEY)
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === 'string') : []
  } catch { return [] }
}

async function saveIndex(ids: string[]): Promise<void> {
  await vaultSet(INDEX_KEY, JSON.stringify([...new Set(ids)]))
}

function key(id: string): string {
  return `photo-queue:${id}`
}
