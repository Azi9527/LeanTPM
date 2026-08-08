<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { FullScreen, Refresh, VideoPause, VideoPlay } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { useBranding } from '@/branding/branding'
import {
  visualizationApi,
  type EquipmentSnapshot,
  type SceneDetail,
  type SceneNode,
  type SceneSummary,
} from '@/api/visualization'
import CockpitCharts from '@/views/visualization/components/CockpitCharts.vue'
import EquipmentSnapshotDrawer from '@/views/visualization/three/EquipmentSnapshotDrawer.vue'
import SceneCanvas from '@/views/visualization/three/SceneCanvas.vue'
import { useVisualizationDashboard } from '@/views/visualization/useVisualizationDashboard'
import { errorMessage } from '@/utils/http'

type DisplayMode = 'THREE' | 'OPERATIONS'

const router = useRouter()
const branding = useBranding()
const root = ref<HTMLElement>()
const displayMode = ref<DisplayMode>('THREE')
const threeAvailable = ref(true)
const threeFailure = ref('')
const autoPlay = ref(true)
const clock = ref(new Date())
const sceneLoading = ref(false)
const scenes = ref<SceneSummary[]>([])
const sceneDetail = ref<SceneDetail>()
const snapshot = ref<EquipmentSnapshot>()
const snapshotLoading = ref(false)
const drawerVisible = ref(false)
const { filters, loading, dashboard, organizations, load } = useVisualizationDashboard()
let clockTimer: number | undefined
let rotationTimer: number | undefined

const abnormalEquipment = computed(() => {
  const core = dashboard.value?.core
  return core ? core.stopped + core.scrapped : 0
})
const healthyEquipment = computed(() => Math.max((dashboard.value?.core.total ?? 0) - abnormalEquipment.value, 0))
const healthRate = computed(() => ratio(healthyEquipment.value, dashboard.value?.core.total ?? 0))
const workflowDue = computed(() => (dashboard.value?.inspection.due ?? 0) + (dashboard.value?.maintenance.due ?? 0))
const workflowCompleted = computed(() => (dashboard.value?.inspection.completed ?? 0) + (dashboard.value?.maintenance.completed ?? 0))
const workflowRate = computed(() => ratio(workflowCompleted.value, workflowDue.value))
const overdueCount = computed(() => (dashboard.value?.inspection.overdue ?? 0) + (dashboard.value?.maintenance.overdue ?? 0))
const openAbnormalCount = computed(() => (dashboard.value?.inspection.abnormal ?? 0) + (dashboard.value?.maintenance.abnormal ?? 0))
const statusRows = computed(() => (dashboard.value?.statusDistribution ?? []).filter((item) => item.equipmentCount > 0))
const attentionEquipment = computed(() => (dashboard.value?.liveEquipment ?? [])
  .filter((item) => item.statusCode === 'STOPPED' || item.longDuration)
  .sort((a, b) => Number(b.longDuration) - Number(a.longDuration) || b.durationSeconds - a.durationSeconds)
  .slice(0, 6))
const metrics = computed(() => [
  { key: 'TOTAL', label: '设备总数', value: dashboard.value?.core.total ?? 0, hint: `${healthyEquipment.value} 台状态正常`, tone: 'cyan' },
  { key: 'HEALTH', label: '设备健康率', value: percent(healthRate.value), hint: `${abnormalEquipment.value} 台需关注`, tone: healthRate.value >= 0.9 ? 'green' : 'amber' },
  { key: 'TASK', label: '任务完成率', value: percent(workflowRate.value), hint: `${workflowCompleted.value}/${workflowDue.value} 项完成`, tone: 'green' },
  { key: 'OEE', label: '平均 OEE', value: percent(dashboard.value?.oee.summary.oeeRate), hint: `目标 ${percent(dashboard.value?.oee.summary.targetOeeRate)}`, tone: 'cyan' },
  { key: 'ABNORMAL', label: '开放异常', value: openAbnormalCount.value, hint: '点检与维保异常', tone: openAbnormalCount.value ? 'red' : 'green' },
  { key: 'OVERDUE', label: '逾期任务', value: overdueCount.value, hint: '需要立即闭环', tone: overdueCount.value ? 'amber' : 'green' },
])
const healthRingStyle = computed(() => ({
  background: `conic-gradient(#23d98b 0 ${healthRate.value * 360}deg, rgba(66, 104, 131, .24) ${healthRate.value * 360}deg 360deg)`,
}))

onMounted(async () => {
  clockTimer = window.setInterval(() => { clock.value = new Date() }, 1000)
  await loadScenes()
  restartRotation()
})

onBeforeUnmount(() => {
  if (clockTimer) window.clearInterval(clockTimer)
  if (rotationTimer) window.clearInterval(rotationTimer)
})

watch(autoPlay, restartRotation)

function restartRotation() {
  if (rotationTimer) window.clearInterval(rotationTimer)
  rotationTimer = undefined
  if (!autoPlay.value || !threeAvailable.value) return
  rotationTimer = window.setInterval(() => {
    displayMode.value = displayMode.value === 'THREE' ? 'OPERATIONS' : 'THREE'
  }, 30000)
}

function selectDisplayMode(mode: DisplayMode) {
  if (mode === 'THREE' && !threeAvailable.value) {
    ElMessage.warning('当前电脑的三维渲染不可用，已保留运行图谱展示')
    return
  }
  displayMode.value = mode
}

function handleThreeUnavailable(reason: string) {
  threeAvailable.value = false
  threeFailure.value = reason
  displayMode.value = 'OPERATIONS'
  restartRotation()
  ElMessage.warning('当前电脑无法启用三维场景，已自动切换为运行图谱')
}

async function loadScenes() {
  sceneLoading.value = true
  try {
    scenes.value = await visualizationApi.scenes()
    const first = scenes.value.find((item) => item.parentSceneId === 0 && item.status === 1)
      ?? scenes.value.find((item) => item.status === 1)
    if (first) await openScene(first.id)
  } catch (error) {
    ElMessage.error(errorMessage(error, '三维场景加载失败'))
  } finally {
    sceneLoading.value = false
  }
}

async function openScene(id: number) {
  sceneLoading.value = true
  try {
    sceneDetail.value = await visualizationApi.scene(id)
  } catch (error) {
    ElMessage.error(errorMessage(error, '三维场景加载失败'))
  } finally {
    sceneLoading.value = false
  }
}

async function refreshAll() {
  await Promise.allSettled([
    load(),
    sceneDetail.value ? openScene(sceneDetail.value.scene.id) : loadScenes(),
  ])
}

async function selectNode(node: SceneNode) {
  if (node.targetSceneId) {
    await openScene(node.targetSceneId)
    return
  }
  if (!node.equipmentId) return
  drawerVisible.value = true
  snapshotLoading.value = true
  snapshot.value = undefined
  try {
    snapshot.value = await visualizationApi.snapshot(node.equipmentId)
  } catch (error) {
    ElMessage.error(errorMessage(error, '设备快照加载失败'))
  } finally {
    snapshotLoading.value = false
  }
}

function openMetric(key: string) {
  const paths: Record<string, string> = {
    TOTAL: '/equipment/ledger',
    HEALTH: '/equipment/statuses',
    TASK: '/inspection/tasks',
    OEE: '/visualization/oee',
    ABNORMAL: '/inspection/abnormal',
    OVERDUE: '/inspection/tasks',
  }
  void router.push(paths[key] ?? '/dashboard')
}

function openEquipment(id: number) {
  void router.push({ path: '/equipment/ledger', query: { equipmentId: id } })
}

async function fullscreen() {
  if (!document.fullscreenElement) await root.value?.requestFullscreen()
  else await document.exitFullscreen()
}

function ratio(value: number, total: number) {
  return total ? value / total : 0
}

function percent(value?: number) {
  return value == null ? '—' : `${(Number(value) * 100).toFixed(1)}%`
}

function duration(seconds: number) {
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  if (days) return `${days}天 ${hours}小时`
  if (hours) return `${hours}小时 ${minutes}分`
  return `${minutes}分钟`
}

function dateText(value: Date) {
  return value.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', weekday: 'short' })
}

function timeText(value: Date) {
  return value.toLocaleTimeString('zh-CN', { hour12: false })
}
</script>

<template>
  <main ref="root" class="cockpit" v-loading="loading || sceneLoading">
    <header class="command-header">
      <div class="command-brand">
        <i class="live-signal" />
        <div><span>{{ branding.shortName }} · DIGITAL FACTORY</span><strong>设备运营指挥中心</strong></div>
      </div>
      <div class="command-title">
        <span>LEAN TPM VISUAL COMMAND CENTER</span>
        <h1>设备综合运行驾驶舱</h1>
      </div>
      <div class="command-clock">
        <strong>{{ timeText(clock) }}</strong>
        <span>{{ dateText(clock) }}</span>
      </div>
    </header>

    <section class="command-toolbar">
      <div class="toolbar-filters">
        <el-segmented
          v-model="filters.periodType"
          :options="[{ label: '日', value: 'DAY' }, { label: '周', value: 'WEEK' }, { label: '月', value: 'MONTH' }]"
        />
        <el-select v-model="filters.organizationId" clearable filterable placeholder="全部组织" @change="load()">
          <el-option v-for="item in organizations" :key="item.id" :label="item.organizationName" :value="item.id" />
        </el-select>
        <span class="data-range">{{ filters.startDate }} 至 {{ filters.endDate }}</span>
      </div>
      <div class="toolbar-actions">
        <span class="updated">数据更新 {{ dashboard?.generatedAt ? new Date(dashboard.generatedAt).toLocaleTimeString('zh-CN', { hour12: false }) : '—' }}</span>
        <el-button :icon="autoPlay ? VideoPause : VideoPlay" @click="autoPlay = !autoPlay">{{ autoPlay ? '暂停轮播' : '开始轮播' }}</el-button>
        <el-button :icon="Refresh" @click="refreshAll">刷新</el-button>
        <el-button :icon="FullScreen" @click="fullscreen">全屏播放</el-button>
      </div>
    </section>

    <section class="kpi-grid">
      <button v-for="item in metrics" :key="item.key" class="kpi-card" :class="item.tone" @click="openMetric(item.key)">
        <span>{{ item.label }}</span><strong>{{ item.value }}</strong><small>{{ item.hint }}</small>
      </button>
    </section>

    <section class="command-grid">
      <article class="screen-panel health-panel">
        <header><div><span>HEALTH INDEX</span><h2>设备健康态势</h2></div><small>实时状态</small></header>
        <div class="health-ring" :style="healthRingStyle">
          <div><strong>{{ percent(healthRate) }}</strong><span>设备健康率</span></div>
        </div>
        <div class="health-summary"><b>{{ healthyEquipment }} 台正常</b><span>{{ abnormalEquipment }} 台异常</span></div>
        <div class="status-bars">
          <button v-for="item in statusRows" :key="item.statusCode" @click="router.push('/equipment/statuses')">
            <span><i :style="{ backgroundColor: item.displayColor }" />{{ item.statusName }}</span>
            <b>{{ item.equipmentCount }}</b>
            <em><i :style="{ width: `${ratio(item.equipmentCount, dashboard?.core.total ?? 0) * 100}%`, backgroundColor: item.displayColor }" /></em>
          </button>
        </div>
      </article>

      <article class="screen-panel visual-panel">
        <header class="visual-header">
          <div><span>FACTORY DIGITAL TWIN</span><h2>{{ displayMode === 'THREE' ? '三维设备运行场景' : '组织运行态势' }}</h2></div>
          <div class="visual-actions">
            <el-select v-if="displayMode === 'THREE'" :model-value="sceneDetail?.scene.id" placeholder="选择场景" @change="openScene">
              <el-option v-for="item in scenes" :key="item.id" :label="item.sceneName" :value="item.id" />
            </el-select>
            <button :class="{ active: displayMode === 'THREE' }" :disabled="!threeAvailable" @click="selectDisplayMode('THREE')">三维场景</button>
            <button :class="{ active: displayMode === 'OPERATIONS' }" @click="selectDisplayMode('OPERATIONS')">运行图谱</button>
          </div>
        </header>
        <div class="visual-body">
          <SceneCanvas
            v-if="displayMode === 'THREE' && sceneDetail"
            :detail="sceneDetail"
            compact
            @select="selectNode"
            @unavailable="handleThreeUnavailable"
          />
          <CockpitCharts v-else-if="displayMode === 'OPERATIONS'" :dashboard="dashboard" mode="STATUS" compact />
          <el-empty v-else description="暂未配置可用的三维场景" />
          <div v-if="threeFailure && displayMode === 'OPERATIONS'" class="three-compatibility" :title="threeFailure">
            本机三维渲染不可用，已自动展示运行图谱
          </div>
          <div v-if="displayMode === 'THREE' && sceneDetail" class="scene-caption">
            <i />
            <span><b>{{ sceneDetail.scene.sceneName }}</b><small>{{ sceneDetail.scene.sceneLevel }} · {{ sceneDetail.nodes.length }} 个节点 · 点击设备查看实时快照</small></span>
          </div>
        </div>
      </article>

      <article class="screen-panel attention-panel">
        <header><div><span>REAL-TIME ALERT</span><h2>异常与风险关注</h2></div><small>{{ attentionEquipment.length }} 台关注</small></header>
        <div class="risk-summary">
          <button @click="router.push('/equipment/statuses')"><strong>{{ abnormalEquipment }}</strong><span>异常设备</span></button>
          <button @click="router.push('/inspection/tasks')"><strong>{{ overdueCount }}</strong><span>逾期任务</span></button>
          <button @click="router.push('/inspection/abnormal')"><strong>{{ openAbnormalCount }}</strong><span>开放异常</span></button>
        </div>
        <div class="attention-list">
          <button v-for="item in attentionEquipment" :key="item.equipmentId" @click="openEquipment(item.equipmentId)">
            <i :style="{ backgroundColor: item.displayColor }" />
            <span><b>{{ item.equipmentName }}</b><small>{{ item.equipmentCode }} · {{ item.organizationName }}</small></span>
            <em><strong :style="{ color: item.displayColor }">{{ item.statusName }}</strong><small>{{ duration(item.durationSeconds) }}</small></em>
          </button>
          <el-empty v-if="!attentionEquipment.length" description="当前没有设备风险" :image-size="54" />
        </div>
      </article>
    </section>

    <section class="insight-grid">
      <div class="compact-chart"><CockpitCharts :dashboard="dashboard" mode="INSPECTION" compact /></div>
      <div class="compact-chart"><CockpitCharts :dashboard="dashboard" mode="OEE" compact /></div>
    </section>

    <footer class="command-footer">
      <span><i /> 系统在线</span>
      <p>当前视图每 30 秒自动轮播 · 业务数据按系统刷新策略自动更新 · 点击任意指标可进入业务明细</p>
      <b>{{ branding.shortName }} 精益设备管理平台</b>
    </footer>

    <EquipmentSnapshotDrawer
      v-model="drawerVisible"
      :snapshot="snapshot"
      :loading="snapshotLoading"
      @detail="openEquipment"
    />
  </main>
</template>

<style scoped>
.cockpit {
  --screen-cyan: #38d9ff;
  --screen-green: #22d98b;
  --screen-red: #ff4f64;
  --screen-amber: #ffbd42;
  display: grid;
  grid-template-rows: auto auto auto minmax(410px, 1fr) minmax(210px, auto) auto;
  gap: 10px;
  min-height: 100%;
  padding: 12px;
  color: #dff7ff;
  background:
    linear-gradient(rgba(29, 125, 80, .035) 1px, transparent 1px),
    linear-gradient(90deg, rgba(29, 125, 80, .035) 1px, transparent 1px),
    radial-gradient(circle at 50% -20%, rgba(34, 217, 139, .16), transparent 42%),
    #06111d;
  background-size: 36px 36px, 36px 36px, auto, auto;
}
.cockpit:fullscreen { width: 100vw; height: 100vh; overflow: auto; }
.command-header, .command-toolbar, .screen-panel, .compact-chart, .command-footer { border: 1px solid rgba(79, 181, 217, .18); background: rgba(7, 23, 39, .9); box-shadow: inset 0 0 28px rgba(34, 217, 139, .025); }
.command-header { display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; min-height: 76px; padding: 10px 20px; border-radius: 14px 14px 5px 5px; }
.command-brand { display: flex; align-items: center; gap: 11px; }
.command-brand > div { display: grid; gap: 3px; }
.command-brand span, .command-title span, .screen-panel header span { color: #58abc3; font-size: 9px; letter-spacing: .16em; }
.command-brand strong { color: #b9dce7; font-size: 13px; }
.live-signal { width: 9px; height: 9px; border-radius: 50%; background: var(--screen-green); box-shadow: 0 0 0 5px rgba(34,217,139,.1), 0 0 18px var(--screen-green); animation: signal 1.8s infinite; }
.command-title { text-align: center; }
.command-title h1 { margin: 2px 0 0; color: #f2fbff; font-size: clamp(24px, 2vw, 34px); letter-spacing: .08em; }
.command-clock { display: grid; justify-items: end; gap: 1px; }
.command-clock strong { color: var(--screen-cyan); font-family: Consolas, monospace; font-size: 24px; letter-spacing: .08em; }
.command-clock span { color: #7398aa; font-size: 11px; }
.command-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 7px 12px; border-radius: 5px; }
.toolbar-filters, .toolbar-actions { display: flex; align-items: center; gap: 8px; }
.toolbar-filters :deep(.el-select) { width: 170px; }
.data-range, .updated { color: #7192a4; font-size: 11px; }
.kpi-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 10px; }
.kpi-card { position: relative; min-width: 0; padding: 13px 15px 12px; overflow: hidden; color: #b9d9e7; border: 1px solid rgba(56,217,255,.22); border-radius: 8px; background: linear-gradient(140deg, rgba(56,217,255,.09), rgba(7,23,39,.92) 62%); cursor: pointer; text-align: left; }
.kpi-card::after { content: ''; position: absolute; right: -22px; top: -28px; width: 74px; height: 74px; border: 1px solid currentColor; border-radius: 50%; opacity: .08; }
.kpi-card span, .kpi-card small { display: block; overflow: hidden; color: #769aac; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.kpi-card strong { display: block; margin: 5px 0 3px; color: var(--screen-cyan); font: 700 25px/1 Consolas, sans-serif; }
.kpi-card.green strong { color: var(--screen-green); }.kpi-card.amber strong { color: var(--screen-amber); }.kpi-card.red strong { color: var(--screen-red); }
.kpi-card:hover { border-color: currentColor; transform: translateY(-1px); }
.command-grid { display: grid; grid-template-columns: minmax(235px, .72fr) minmax(540px, 2fr) minmax(270px, .85fr); gap: 10px; min-height: 410px; }
.screen-panel { min-width: 0; overflow: hidden; border-radius: 8px; }
.screen-panel > header { display: flex; align-items: center; justify-content: space-between; min-height: 50px; padding: 0 14px; border-bottom: 1px solid rgba(79,181,217,.12); }
.screen-panel h2 { margin: 2px 0 0; color: #e6f7fb; font-size: 15px; }.screen-panel header small { color: #648a9e; font-size: 10px; }
.health-panel { padding-bottom: 10px; }
.health-ring { display: grid; place-items: center; width: 132px; height: 132px; margin: 15px auto 8px; border-radius: 50%; box-shadow: 0 0 32px rgba(34,217,139,.12); }
.health-ring > div { display: grid; place-items: center; width: 104px; height: 104px; border-radius: 50%; background: #091827; }
.health-ring strong { color: var(--screen-green); font: 700 25px Consolas, sans-serif; }.health-ring span { color: #698ea0; font-size: 10px; }
.health-summary { display: flex; justify-content: center; gap: 14px; margin-bottom: 10px; font-size: 11px; }.health-summary b { color: var(--screen-green); }.health-summary span { color: var(--screen-red); }
.status-bars { display: grid; gap: 5px; padding: 0 12px; }
.status-bars button { display: grid; grid-template-columns: 1fr auto; gap: 3px 8px; padding: 3px 0; color: #82a6b6; border: 0; background: transparent; cursor: pointer; text-align: left; }
.status-bars button > span { display: flex; align-items: center; gap: 6px; font-size: 10px; }.status-bars button > span i { width: 6px; height: 6px; border-radius: 50%; }.status-bars b { font: 700 11px Consolas, sans-serif; }
.status-bars em { grid-column: 1 / -1; height: 3px; overflow: hidden; border-radius: 9px; background: rgba(90,125,145,.17); }.status-bars em i { display: block; height: 100%; border-radius: inherit; }
.visual-panel { display: grid; grid-template-rows: auto minmax(0, 1fr); }
.visual-header { position: relative; z-index: 3; }
.visual-actions { display: flex; align-items: center; gap: 5px; }.visual-actions :deep(.el-select) { width: 150px; }
.visual-actions button { padding: 6px 9px; color: #7296a8; border: 1px solid rgba(79,181,217,.14); border-radius: 5px; background: transparent; cursor: pointer; font-size: 10px; }.visual-actions button.active { color: #06131f; border-color: var(--screen-cyan); background: var(--screen-cyan); }
.visual-actions button:disabled { color: #536d7a; border-color: rgba(79,181,217,.08); background: rgba(82,105,119,.08); cursor: not-allowed; }
.visual-body { position: relative; min-height: 0; overflow: hidden; background: radial-gradient(circle at center, rgba(25,93,93,.14), transparent 54%), #06111d; }
.visual-body > :deep(.scene-host), .visual-body > :deep(.charts-grid) { height: 100%; }
.three-compatibility { position: absolute; z-index: 4; right: 12px; bottom: 12px; max-width: 320px; padding: 6px 10px; overflow: hidden; color: #d6a752; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; border: 1px solid rgba(255,189,66,.22); border-radius: 5px; background: rgba(36,27,12,.82); }
.scene-caption { position: absolute; z-index: 3; left: 14px; bottom: 14px; display: flex; align-items: center; gap: 9px; padding: 8px 11px; border: 1px solid rgba(56,217,255,.18); border-radius: 6px; background: rgba(3,14,24,.78); backdrop-filter: blur(8px); }
.scene-caption > i { width: 4px; height: 28px; background: var(--screen-cyan); box-shadow: 0 0 10px var(--screen-cyan); }.scene-caption span { display: grid; gap: 2px; }.scene-caption b { font-size: 11px; }.scene-caption small { color: #6f93a5; font-size: 9px; }
.attention-panel { padding-bottom: 9px; }
.risk-summary { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; padding: 11px; }
.risk-summary button { padding: 8px 3px; border: 1px solid rgba(255,79,100,.16); border-radius: 6px; background: rgba(255,79,100,.04); cursor: pointer; }.risk-summary strong, .risk-summary span { display: block; }.risk-summary strong { color: var(--screen-red); font: 700 19px Consolas, sans-serif; }.risk-summary span { margin-top: 2px; color: #789aaa; font-size: 9px; }
.attention-list { display: grid; gap: 5px; padding: 0 11px; }
.attention-list > button { display: grid; grid-template-columns: 7px 1fr auto; align-items: center; gap: 8px; padding: 8px; color: #a7c8d5; border: 1px solid rgba(79,181,217,.1); border-radius: 6px; background: rgba(12,37,52,.48); cursor: pointer; text-align: left; }
.attention-list > button:hover { border-color: rgba(255,79,100,.4); }.attention-list > button > i { width: 6px; height: 6px; border-radius: 50%; box-shadow: 0 0 10px currentColor; }
.attention-list span, .attention-list em { display: grid; gap: 2px; }.attention-list b { overflow: hidden; max-width: 150px; color: #d9eef4; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }.attention-list small { color: #638597; font-size: 9px; }.attention-list em { font-style: normal; text-align: right; }.attention-list em strong { font-size: 10px; }
.insight-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; min-height: 210px; }
.compact-chart { min-width: 0; overflow: hidden; border-radius: 8px; }
.compact-chart > :deep(.charts-grid), .compact-chart > :deep(.viz-panel) { height: 100%; }
.command-footer { display: grid; grid-template-columns: 1fr 2fr 1fr; align-items: center; min-height: 30px; padding: 0 12px; border-radius: 4px; color: #5f8192; font-size: 9px; }
.command-footer span { color: var(--screen-green); }.command-footer span i { display: inline-block; width: 5px; height: 5px; margin-right: 5px; border-radius: 50%; background: var(--screen-green); box-shadow: 0 0 8px var(--screen-green); }.command-footer p { margin: 0; text-align: center; }.command-footer b { justify-self: end; color: #688c9e; font-weight: 500; }
@keyframes signal { 50% { opacity: .55; box-shadow: 0 0 0 8px rgba(34,217,139,0), 0 0 20px var(--screen-green); } }
@media (max-width: 1450px) { .command-grid { grid-template-columns: 230px minmax(480px, 1.6fr) 260px; }.kpi-card strong { font-size: 22px; } }
@media (max-width: 1100px) { .command-header { grid-template-columns: 1fr 1fr; }.command-title { display: none; }.command-toolbar { align-items: flex-start; flex-direction: column; }.kpi-grid { grid-template-columns: repeat(3, 1fr); }.command-grid { grid-template-columns: 1fr; }.health-panel, .attention-panel { min-height: 360px; }.visual-panel { min-height: 520px; }.insight-grid { grid-template-columns: 1fr; }.command-footer { grid-template-columns: 1fr; }.command-footer p, .command-footer b { display: none; } }
@media (max-width: 680px) { .cockpit { padding: 7px; }.command-clock strong { font-size: 18px; }.toolbar-filters, .toolbar-actions { flex-wrap: wrap; }.updated, .data-range { display: none; }.kpi-grid { grid-template-columns: repeat(2, 1fr); } }
</style>
