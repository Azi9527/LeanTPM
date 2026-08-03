import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { masterDataApi, type OrganizationRow } from '@/api/masterData'
import { visualizationApi, type DashboardResult } from '@/api/visualization'
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
    periodType: 'DAY' as 'DAY' | 'WEEK' | 'MONTH',
  })
  const loading = ref(false)
  const dashboard = ref<DashboardResult>()
  const organizations = ref<OrganizationRow[]>([])
  let timer: number | undefined

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
      Math.max(dashboard.value?.refreshSeconds ?? 86400, 60) * 1000,
    )
  }

  function resetRange(periodType: 'DAY' | 'WEEK' | 'MONTH') {
    const end = new Date()
    const start = new Date(end)
    if (periodType === 'DAY') start.setDate(start.getDate() - 6)
    if (periodType === 'WEEK') start.setDate(start.getDate() - 7 * 11)
    if (periodType === 'MONTH') start.setMonth(start.getMonth() - 11, 1)
    filters.startDate = localDate(start)
    filters.endDate = localDate(end)
  }

  watch(() => filters.periodType, (periodType) => {
    resetRange(periodType)
    void load()
  })

  onMounted(async () => {
    try {
      organizations.value = (await masterDataApi.organizations()).filter((item) => item.status === 1)
    } catch (error) {
      ElMessage.error(errorMessage(error))
    }
    await load()
  })

  onBeforeUnmount(() => {
    if (timer) window.clearInterval(timer)
  })

  return { filters, loading, dashboard, organizations, load }
}
