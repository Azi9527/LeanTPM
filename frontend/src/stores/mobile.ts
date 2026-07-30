import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { mobileApi, type MobileBootstrap } from '@/api/mobile'
import { countMobileDrafts, purgeExpiredMobileDrafts } from '@/mobile/draftStore'
import { online } from '@/mobile/runtime'

export const useMobileStore = defineStore('mobile', () => {
  const bootstrap = ref<MobileBootstrap | null>(null)
  const loading = ref(false)
  const draftCount = ref(0)

  const messages = computed(() => bootstrap.value?.messages ?? [])
  const maxUploadMb = computed(() => bootstrap.value?.maxUploadMb ?? 10)

  async function refresh(): Promise<void> {
    loading.value = true
    try {
      bootstrap.value = await mobileApi.bootstrap()
      await purgeExpiredMobileDrafts(bootstrap.value.draftRetentionDays)
      draftCount.value = await countMobileDrafts()
    } finally {
      loading.value = false
    }
  }

  async function refreshDraftCount(): Promise<void> {
    draftCount.value = await countMobileDrafts()
  }

  return {
    bootstrap,
    loading,
    draftCount,
    messages,
    maxUploadMb,
    online,
    refresh,
    refreshDraftCount,
  }
})
