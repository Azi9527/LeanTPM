<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { FullScreen, Refresh, Setting, VideoPause, VideoPlay } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  subscribeVisualization,
  visualizationApi,
  type EquipmentSnapshot,
  type SceneDetail,
  type SceneNode,
  type SceneSummary,
} from '@/api/visualization'
import { errorMessage } from '@/utils/http'
import SceneCanvas from './SceneCanvas.vue'
import EquipmentSnapshotDrawer from './EquipmentSnapshotDrawer.vue'

type CameraView = 'OVERVIEW' | 'TOP' | 'LINE' | 'ROAM'

const router = useRouter()
const root = ref<HTMLElement>()
const canvas = ref<InstanceType<typeof SceneCanvas>>()
const scenes = ref<SceneSummary[]>([])
const detail = ref<SceneDetail>()
const monitoredNodes = ref<SceneNode[]>([])
const loading = ref(false)
const snapshotLoading = ref(false)
const snapshot = ref<EquipmentSnapshot>()
const drawerVisible = ref(false)
const currentView = ref<CameraView>('OVERVIEW')
const cruiseEnabled = ref(true)
const now = ref(new Date())
const streamController = new AbortController()
let refreshTimer: number | undefined
let clockTimer: number | undefined
let sceneLoadRevision = 0

const visibleNodes = computed(() => detail.value?.nodes.filter((item) => item.visibleFlag) ?? [])
const equipmentNodes = computed(() => monitoredNodes.value.filter((item) => item.nodeType === 'EQUIPMENT'))
const operatingNodes = computed(() => equipmentNodes.value.filter((item) => item.statusCode === 'RUNNING'))
const abnormalNodes = computed(() => equipmentNodes.value.filter((item) => item.statusCode === 'STOPPED'))
const attentionNodes = computed(() => {
  const nodes = equipmentNodes.value.filter((item) => item.pulseFlag || item.statusCode === 'STOPPED')
  return nodes.slice(0, 5)
})
const healthRate = computed(() => {
  if (!equipmentNodes.value.length) return 100
  return Math.round(((equipmentNodes.value.length - abnormalNodes.value.length) / equipmentNodes.value.length) * 1000) / 10
})
const formattedTime = computed(() => now.value.toLocaleTimeString('zh-CN', { hour12: false }))
const formattedDate = computed(() => now.value.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', weekday: 'short' }))

onMounted(async () => {
  await loadScenes()
  subscribeVisualization(() => refresh(true), streamController.signal).catch(() => undefined)
  refreshTimer = window.setInterval(() => refresh(true), 15000)
  clockTimer = window.setInterval(() => { now.value = new Date() }, 1000)
})

onBeforeUnmount(() => {
  if (refreshTimer) window.clearInterval(refreshTimer)
  if (clockTimer) window.clearInterval(clockTimer)
  streamController.abort()
})

async function loadScenes() {
  loading.value = true
  try {
    scenes.value = await visualizationApi.scenes()
    const first = scenes.value.find((item) => item.parentSceneId === 0 && item.status === 1)
      ?? scenes.value.find((item) => item.status === 1)
    if (first) await openScene(first.id)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function openScene(id: number) {
  const loadRevision = ++sceneLoadRevision
  loading.value = true
  try {
    const sceneDetail = await visualizationApi.scene(id)
    const descendantIds = descendantSceneIds(id)
    const descendants = await Promise.allSettled(descendantIds.map((sceneId) => visualizationApi.scene(sceneId)))
    if (loadRevision !== sceneLoadRevision) return
    detail.value = sceneDetail
    monitoredNodes.value = uniqueVisibleNodes([
      ...sceneDetail.nodes,
      ...descendants.flatMap((result) => result.status === 'fulfilled' ? result.value.nodes : []),
    ])
    currentView.value = 'OVERVIEW'
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function refresh(silent = false) {
  if (!detail.value) return
  const loadRevision = ++sceneLoadRevision
  if (!silent) loading.value = true
  try {
    const sceneId = detail.value.scene.id
    const sceneDetail = await visualizationApi.scene(sceneId)
    const descendants = await Promise.allSettled(
      descendantSceneIds(sceneId).map((descendantId) => visualizationApi.scene(descendantId)),
    )
    if (loadRevision !== sceneLoadRevision) return
    detail.value = sceneDetail
    monitoredNodes.value = uniqueVisibleNodes([
      ...sceneDetail.nodes,
      ...descendants.flatMap((result) => result.status === 'fulfilled' ? result.value.nodes : []),
    ])
    if (!silent) ElMessage.success('数字孪生数据已刷新')
  } catch (error) {
    if (!silent) ElMessage.error(errorMessage(error))
  } finally {
    if (!silent) loading.value = false
  }
}

function descendantSceneIds(rootId: number) {
  const result: number[] = []
  const queue = [rootId]
  while (queue.length) {
    const parentId = queue.shift()!
    const children = scenes.value.filter((item) => item.parentSceneId === parentId && item.status === 1)
    for (const child of children) {
      if (result.includes(child.id)) continue
      result.push(child.id)
      queue.push(child.id)
    }
  }
  return result
}

function uniqueVisibleNodes(nodes: SceneNode[]) {
  const unique = new Map<string, SceneNode>()
  nodes.filter((item) => item.visibleFlag).forEach((item) => {
    const key = item.equipmentId ? `equipment:${item.equipmentId}` : `scene:${item.sceneId}:node:${item.id}`
    unique.set(key, item)
  })
  return [...unique.values()]
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
    ElMessage.error(errorMessage(error))
  } finally {
    snapshotLoading.value = false
  }
}

function setCamera(view: CameraView) {
  currentView.value = view
  if (view === 'ROAM') cruiseEnabled.value = true
  canvas.value?.setView(view)
}

function toggleCruise() {
  cruiseEnabled.value = canvas.value?.toggleCruise() ?? !cruiseEnabled.value
}

async function fullscreen() {
  if (!document.fullscreenElement) await root.value?.requestFullscreen()
  else await document.exitFullscreen()
}
</script>

<template>
  <main ref="root" class="three-screen" v-loading="loading">
    <header class="scene-header">
      <div class="brand-title">
        <span class="live-dot" />
        <div>
          <span class="eyebrow">LEAN TPM · DIGITAL FACTORY TWIN</span>
          <h1>工厂数字孪生运行中心</h1>
        </div>
      </div>
      <div class="scene-actions">
        <el-select
          :model-value="detail?.scene.id"
          filterable
          placeholder="选择数字孪生场景"
          style="width: 220px"
          @change="openScene"
        >
          <el-option
            v-for="item in scenes"
            :key="item.id"
            :label="`${item.sceneLevel} · ${item.sceneName}`"
            :value="item.id"
          />
        </el-select>
        <el-button :icon="Refresh" @click="refresh()">刷新数据</el-button>
        <el-button :icon="Setting" @click="router.push('/visualization/scenes')">场景配置</el-button>
        <el-button :icon="FullScreen" @click="fullscreen">全屏参观</el-button>
      </div>
      <div class="clock">
        <strong>{{ formattedTime }}</strong>
        <span>{{ formattedDate }}</span>
      </div>
    </header>

    <nav v-if="detail" class="breadcrumbs">
      <span class="signal">数字孪生在线</span>
      <template v-for="(item, index) in detail.breadcrumb" :key="item.id">
        <button @click="openScene(item.id)">{{ item.sceneName }}</button>
        <span v-if="index < detail.breadcrumb.length - 1">/</span>
      </template>
      <span class="sync-time">15 秒实时同步 · {{ equipmentNodes.length }} 台设备 / {{ visibleNodes.length }} 个场景节点已接入</span>
    </nav>

    <section class="scene-stage">
      <SceneCanvas ref="canvas" :detail="detail" cinematic @select="selectNode" />
      <div class="scan-line" />

      <aside class="hud-panel overview-panel">
        <div class="panel-heading">
          <span>DIGITAL TWIN OVERVIEW</span>
          <h2>数字孪生总览</h2>
        </div>
        <div class="health-ring" :style="{ '--rate': `${healthRate * 3.6}deg` }">
          <div><strong>{{ healthRate }}%</strong><span>设备健康度</span></div>
        </div>
        <div class="mini-metrics">
          <div><b>{{ equipmentNodes.length }}</b><span>接入设备</span></div>
          <div><b class="green">{{ operatingNodes.length }}</b><span>运行/点检</span></div>
          <div><b class="red">{{ abnormalNodes.length }}</b><span>异常设备</span></div>
          <div><b>{{ detail?.nodes.length ?? 0 }}</b><span>场景节点</span></div>
        </div>
        <div class="energy-flow">
          <span>现场数据接入</span>
          <div class="flow-track"><i /></div>
          <small>设备 → 边缘采集 → LeanTPM</small>
        </div>
      </aside>

      <aside class="hud-panel status-panel">
        <div class="panel-heading compact-heading">
          <span>REAL-TIME OPERATION</span>
          <h2>实时运行态势</h2>
        </div>
        <div class="status-list">
          <div v-for="item in detail?.statusColors" :key="item.statusCode">
            <i :style="{ backgroundColor: item.displayColor, boxShadow: `0 0 10px ${item.displayColor}` }" :class="{ pulse: item.pulseFlag }" />
            <span>{{ item.statusName }}</span>
            <b>{{ equipmentNodes.filter((node) => node.statusCode === item.statusCode).length }}</b>
          </div>
        </div>
        <div class="alert-title"><span>重点关注</span><b>{{ attentionNodes.length }}</b></div>
        <div class="alert-list">
          <button v-for="node in attentionNodes" :key="node.id" @click="selectNode(node)">
            <i :style="{ backgroundColor: node.displayColor }" />
            <span><b>{{ node.displayName }}</b><small>{{ node.statusName }} · 点击查看设备快照</small></span>
          </button>
          <div v-if="!attentionNodes.length" class="empty-alert">当前生产现场运行平稳</div>
        </div>
      </aside>

      <div class="scene-meta">
        <span>FACTORY DIGITAL TWIN</span>
        <b>{{ detail?.scene.sceneName || '未选择场景' }}</b>
        <small>{{ detail?.scene.organizationName }} · {{ detail?.scene.sceneLevel }}</small>
      </div>

      <div class="camera-toolbar">
        <span>视角控制</span>
        <button :class="{ active: currentView === 'OVERVIEW' }" @click="setCamera('OVERVIEW')">全景</button>
        <button :class="{ active: currentView === 'TOP' }" @click="setCamera('TOP')">俯视</button>
        <button :class="{ active: currentView === 'LINE' }" @click="setCamera('LINE')">产线</button>
        <button :class="{ active: currentView === 'ROAM' }" @click="setCamera('ROAM')">巡航</button>
        <button class="cruise-button" @click="toggleCruise">
          <el-icon><VideoPause v-if="cruiseEnabled" /><VideoPlay v-else /></el-icon>
          {{ cruiseEnabled ? '暂停漫游' : '开始漫游' }}
        </button>
      </div>

      <div class="operation-hint">左键选择设备 · 拖动旋转 · 滚轮缩放 · 右键平移</div>
    </section>

    <EquipmentSnapshotDrawer
      v-model="drawerVisible"
      :snapshot="snapshot"
      :loading="snapshotLoading"
      @detail="(id) => router.push({ path: '/equipment/ledger', query: { equipmentId: id } })"
    />
  </main>
</template>

<style scoped>
.three-screen { --cyan: #31d6ff; --green: #28e08a; --red: #ff4d62; height: calc(100vh - 58px); min-height: 720px; display: grid; grid-template-rows: auto auto minmax(0, 1fr); gap: 8px; overflow: hidden; padding: 10px; color: #dff4ff; background: radial-gradient(circle at 50% 0, #102f4c 0, #071625 36%, #050e19 100%); }
.three-screen:fullscreen { height: 100vh; grid-template-rows: auto auto minmax(0, 1fr); overflow: hidden; }
.scene-header { position: relative; display: flex; justify-content: space-between; align-items: center; gap: 20px; padding: 13px 190px 13px 18px; border: 1px solid rgba(82,178,255,.26); border-radius: 14px; background: linear-gradient(100deg, rgba(8,35,58,.98), rgba(7,23,40,.95)); box-shadow: inset 0 0 32px rgba(20,137,190,.08); }
.brand-title { display: flex; align-items: center; gap: 11px; }.live-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--green); box-shadow: 0 0 14px var(--green); animation: live 1.6s infinite; }
.eyebrow { color: #55d6ff; font-size: 9px; letter-spacing: .2em; } h1 { margin: 2px 0 0; font-size: 25px; letter-spacing: .05em; }
.scene-actions { display: flex; gap: 7px; align-items: center; }.clock { position: absolute; right: 18px; display: grid; text-align: right; }.clock strong { color: var(--cyan); font-family: Consolas, monospace; font-size: 21px; letter-spacing: .1em; }.clock span { color: #6e91a9; font-size: 10px; }
.breadcrumbs { display: flex; align-items: center; gap: 7px; padding: 7px 13px; border: 1px solid rgba(82,178,255,.14); border-radius: 9px; background: rgba(8,25,43,.82); }.breadcrumbs button { color: #7dd3fc; border: 0; background: none; cursor: pointer; }.breadcrumbs > span { color: #3c5a72; }.breadcrumbs .signal { margin-right: 8px; padding: 3px 8px; color: #35dc93; border: 1px solid rgba(53,220,147,.3); border-radius: 4px; background: rgba(53,220,147,.08); }.breadcrumbs .sync-time { margin-left: auto; color: #63849b; font-size: 10px; }
.scene-stage { position: relative; min-height: 0; overflow: hidden; border: 1px solid rgba(82,178,255,.28); border-radius: 14px; background: #07111f; box-shadow: 0 0 35px rgba(16,126,180,.1); }
.scene-stage::before { position: absolute; z-index: 1; inset: 0; content: ''; border: 1px solid rgba(49,214,255,.15); pointer-events: none; background: linear-gradient(90deg, rgba(49,214,255,.025) 1px, transparent 1px), linear-gradient(rgba(49,214,255,.025) 1px, transparent 1px); background-size: 40px 40px; mask-image: linear-gradient(to bottom, black, transparent 48%); }
.scan-line { position: absolute; z-index: 2; left: 230px; right: 250px; top: 18%; height: 1px; background: linear-gradient(90deg, transparent, rgba(49,214,255,.65), transparent); box-shadow: 0 0 10px rgba(49,214,255,.5); animation: scan 7s linear infinite; pointer-events: none; }
.hud-panel { position: absolute; z-index: 4; top: 64px; bottom: 70px; width: 205px; padding: 14px; border: 1px solid rgba(82,178,255,.2); border-radius: 11px; background: linear-gradient(180deg, rgba(5,23,39,.9), rgba(4,16,28,.78)); box-shadow: 0 12px 40px rgba(0,0,0,.22), inset 0 0 22px rgba(27,142,191,.05); backdrop-filter: blur(9px); }
.overview-panel { left: 14px; }.status-panel { right: 14px; }.panel-heading span { color: #4bbde5; font-size: 8px; letter-spacing: .14em; }.panel-heading h2 { margin: 2px 0 12px; font-size: 16px; }.compact-heading h2 { margin-bottom: 8px; }
.health-ring { width: 124px; height: 124px; margin: 8px auto 16px; padding: 11px; border-radius: 50%; background: conic-gradient(var(--green) var(--rate), rgba(46,89,109,.28) 0); box-shadow: 0 0 24px rgba(40,224,138,.12); }.health-ring > div { display: grid; width: 100%; height: 100%; place-content: center; text-align: center; border-radius: 50%; background: #071827; }.health-ring strong { color: var(--green); font: 700 22px Consolas, monospace; }.health-ring span { color: #7999ad; font-size: 9px; }
.mini-metrics { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; }.mini-metrics div { display: grid; padding: 8px; border: 1px solid rgba(82,178,255,.12); border-radius: 7px; background: rgba(15,47,68,.45); }.mini-metrics b { color: #47cfff; font: 700 17px Consolas, monospace; }.mini-metrics b.green { color: var(--green); }.mini-metrics b.red { color: var(--red); }.mini-metrics span { color: #7897aa; font-size: 9px; }
.energy-flow { margin-top: 14px; padding-top: 12px; border-top: 1px solid rgba(82,178,255,.12); }.energy-flow > span { color: #a9c4d5; font-size: 10px; }.flow-track { position: relative; height: 4px; margin: 9px 0; overflow: hidden; border-radius: 4px; background: rgba(42,94,117,.4); }.flow-track i { position: absolute; width: 34%; height: 100%; background: linear-gradient(90deg, transparent, var(--cyan), transparent); animation: flow 2s linear infinite; }.energy-flow small { color: #53758b; font-size: 8px; }
.status-list { display: grid; grid-template-columns: 1fr 1fr; gap: 4px 7px; }.status-list div { display: grid; grid-template-columns: 8px 1fr auto; align-items: center; gap: 5px; padding: 4px 0; color: #86a4b8; font-size: 9px; }.status-list i { width: 6px; height: 6px; border-radius: 50%; }.status-list i.pulse { animation: live 1.5s infinite; }.status-list b { color: #cbe8f6; font: 600 10px Consolas, monospace; }
.alert-title { display: flex; justify-content: space-between; align-items: center; margin: 10px 0 6px; padding-top: 9px; color: #8fb2c6; font-size: 10px; border-top: 1px solid rgba(82,178,255,.12); }.alert-title b { color: #ff7a8b; }.alert-list { display: grid; gap: 5px; }.alert-list button { display: grid; grid-template-columns: 6px 1fr; gap: 7px; align-items: center; padding: 7px; text-align: left; color: inherit; border: 1px solid rgba(82,178,255,.1); border-radius: 6px; background: rgba(12,39,57,.58); cursor: pointer; }.alert-list button:hover { border-color: rgba(49,214,255,.45); transform: translateX(-2px); }.alert-list button > i { width: 5px; height: 5px; border-radius: 50%; box-shadow: 0 0 9px currentColor; }.alert-list span { display: grid; min-width: 0; }.alert-list b { overflow: hidden; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }.alert-list small { margin-top: 2px; overflow: hidden; color: #66879a; font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }.empty-alert { padding: 20px 4px; color: #5c8297; font-size: 10px; text-align: center; }
.scene-meta { position: absolute; z-index: 4; left: 50%; top: 13px; display: grid; min-width: 250px; padding: 7px 18px; text-align: center; border: 1px solid rgba(82,178,255,.18); border-radius: 8px; background: rgba(4,16,28,.72); transform: translateX(-50%); backdrop-filter: blur(8px); }.scene-meta > span { color: #42c7f2; font-size: 8px; letter-spacing: .18em; }.scene-meta b { margin: 1px 0; font-size: 13px; }.scene-meta small { color: #66879e; font-size: 8px; }
.camera-toolbar { position: absolute; z-index: 5; left: 50%; bottom: 14px; display: flex; align-items: center; gap: 4px; padding: 6px; border: 1px solid rgba(82,178,255,.2); border-radius: 9px; background: rgba(4,16,28,.86); transform: translateX(-50%); backdrop-filter: blur(10px); }.camera-toolbar > span { padding: 0 9px; color: #62869b; font-size: 9px; }.camera-toolbar button { display: inline-flex; align-items: center; gap: 4px; padding: 6px 10px; color: #8eafc1; font-size: 10px; border: 1px solid transparent; border-radius: 5px; background: rgba(25,56,76,.5); cursor: pointer; }.camera-toolbar button:hover,.camera-toolbar button.active { color: #dff8ff; border-color: rgba(49,214,255,.42); background: rgba(18,125,163,.34); }.camera-toolbar .cruise-button { margin-left: 5px; color: #4de6a0; border-color: rgba(77,230,160,.18); }
.operation-hint { position: absolute; z-index: 4; right: 14px; bottom: 15px; color: #57798d; font-size: 9px; pointer-events: none; }
@keyframes live { 50% { opacity: .55; transform: scale(1.3); } } @keyframes scan { from { top: 12%; } to { top: 88%; } } @keyframes flow { from { left: -34%; } to { left: 100%; } }
@media (max-width: 1200px) { .scene-header { padding-right: 18px; }.clock { display: none; }.hud-panel { width: 185px; }.scan-line { left: 210px; right: 230px; } }
@media (max-width: 900px) { .three-screen { height: auto; min-height: 720px; grid-template-rows: auto auto minmax(600px,1fr); overflow: visible; }.scene-header { align-items: flex-start; flex-direction: column; }.scene-actions { flex-wrap: wrap; }.hud-panel { display: none; }.camera-toolbar { width: max-content; max-width: calc(100% - 20px); }.operation-hint { display: none; } }
</style>
