import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { mobileApi, type MobileBootstrap } from '@/api/mobile'
import { inspectionApi } from '@/api/inspection'
import { maintenanceApi } from '@/api/maintenance'
import {
  countMobileDrafts, listMobileDrafts, loadMobileDraft, purgeExpiredMobileDrafts,
  removeMobileDraft, saveMobileDraft,
} from '@/mobile/draftStore'
import { publishNewLocalAlerts } from '@/mobile/localAlerts'
import {
  countQueuedPhotos, listQueuedPhotos, removeQueuedPhoto, restoreQueuedCapture,
} from '@/mobile/photoQueue'
import { uploadPhotoEvidence } from '@/mobile/photoEvidence'
import { online } from '@/mobile/runtime'

export const useMobileStore = defineStore('mobile', () => {
  const bootstrap = ref<MobileBootstrap | null>(null)
  const loading = ref(false)
  const draftCount = ref(0)
  const queuedPhotoCount = ref(0)
  const syncing = ref(false)
  const lastSyncError = ref('')
  let serverEpochMs = 0
  let serverReceivedAtMs = 0

  const messages = computed(() => bootstrap.value?.messages ?? [])
  const maxUploadMb = computed(() => bootstrap.value?.maxUploadMb ?? 10)

  async function refresh(): Promise<void> {
    loading.value = true
    try {
      bootstrap.value = await mobileApi.bootstrap()
      serverEpochMs = Date.parse(bootstrap.value.serverTime)
      serverReceivedAtMs = Date.now()
      await purgeExpiredMobileDrafts(bootstrap.value.draftRetentionDays)
      await refreshDraftCount()
      void publishNewLocalAlerts(bootstrap.value.messages)
      if (online.value) void syncPending()
    } finally {
      loading.value = false
    }
  }

  async function refreshDraftCount(): Promise<void> {
    draftCount.value = await countMobileDrafts()
    queuedPhotoCount.value = await countQueuedPhotos()
  }

  async function syncPending(): Promise<void> {
    if (!online.value || syncing.value) return
    syncing.value = true
    lastSyncError.value = ''
    try {
      for (const queued of await listQueuedPhotos()) {
        const result = await uploadPhotoEvidence(restoreQueuedCapture(queued))
        const draft = await loadMobileDraft<Record<string, unknown>>(queued.workflow, queued.taskId)
        if (draft) {
          const payload = draft.payload as { results?: Array<Record<string, unknown>> }
          const item = payload.results?.find((row) => row.taskItemId === queued.taskItemId)
          if (item) {
            const ids = Array.isArray(item[queued.kind]) ? item[queued.kind] as number[] : []
            item[queued.kind] = [...new Set([...ids, result.attachmentId])]
            await saveMobileDraft({ ...draft, payload, updatedAt: new Date().toISOString() })
          }
        }
        await removeQueuedPhoto(queued.id)
      }

      const remainingPhotos = await listQueuedPhotos()
      for (const draft of await listMobileDrafts()) {
        if (!draft.pendingSubmit || remainingPhotos.some((photo) => (
          photo.workflow === draft.workflow && photo.taskId === draft.taskId
        ))) continue
        if (draft.workflow === 'inspection') {
          await inspectionApi.submitTask(draft.taskId, draft.payload as object, draft.idempotencyKey)
        } else {
          await maintenanceApi.submitTask(draft.taskId, draft.payload as object, draft.idempotencyKey)
        }
        await removeMobileDraft(draft.workflow, draft.taskId)
      }
    } catch (error) {
      lastSyncError.value = error instanceof Error ? error.message : '自动同步失败'
    } finally {
      syncing.value = false
      await refreshDraftCount()
    }
  }

  function estimatedServerTime(): string {
    if (!serverEpochMs || !serverReceivedAtMs) return new Date().toISOString()
    return new Date(serverEpochMs + Date.now() - serverReceivedAtMs).toISOString()
  }

  watch(online, (connected) => {
    if (connected) void syncPending()
  })

  return {
    bootstrap,
    loading,
    draftCount,
    queuedPhotoCount,
    syncing,
    lastSyncError,
    messages,
    maxUploadMb,
    online,
    refresh,
    refreshDraftCount,
    syncPending,
    estimatedServerTime,
  }
})
