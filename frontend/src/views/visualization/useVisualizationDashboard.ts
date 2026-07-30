import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { masterDataApi, type OrganizationRow } from '@/api/masterData'
import {
  subscribeVisualization,
  visualizationApi,
  type DashboardResult,
} from '@/api/visualization'
import { errorMessage } from '@/utils/http'

function localDate(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function useVisualizationDashboard() {
  const now = new Date()
  const start = new Date(now)
  start.setDate(start.getDate() - 6)
  const filters = reactive({
    startDate: localDate(start),
    endDate: localDate(now),
    organizationId: undefined as number | undefined,
  })
  const loading = ref(false)
  const dashboard = ref<DashboardResult>()
  const organizations = ref<OrganizationRow[]>([])
  let timer: number | undefined
  const streamController = new AbortController()

  async function load(silent = false) {
    if (!silent) loading.value = true
    try {
      dashboard.value = await visualizationApi.dashboard(filters)
      restartTimer()
    } catch (error) {
      if (!silent) ElMessage.error(errorMessage(error))
    } finally {
      if (!silent) loading.value = false
    }
  }

  function restartTimer() {
    if (timer) window.clearInterval(timer)
    timer = window.setInterval(
      () => load(true),
      Math.max(dashboard.value?.refreshSeconds ?? 15, 5) * 1000,
    )
  }

  onMounted(async () => {
    try {
      organizations.value = (await masterDataApi.organizations()).filter((item) => item.status === 1)
    } catch (error) {
      ElMessage.error(errorMessage(error))
    }
    await load()
    subscribeVisualization(() => load(true), streamController.signal).catch(() => undefined)
  })

  onBeforeUnmount(() => {
    if (timer) window.clearInterval(timer)
    streamController.abort()
  })

  return { filters, loading, dashboard, organizations, load }
}
