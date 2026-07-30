<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { FullScreen, Refresh, Setting } from '@element-plus/icons-vue'
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

const router = useRouter()
const root = ref<HTMLElement>()
const scenes = ref<SceneSummary[]>([])
const detail = ref<SceneDetail>()
const loading = ref(false)
const snapshotLoading = ref(false)
const snapshot = ref<EquipmentSnapshot>()
const drawerVisible = ref(false)
const streamController = new AbortController()
let timer: number | undefined

onMounted(async () => {
  await loadScenes()
  subscribeVisualization(() => refresh(true), streamController.signal).catch(() => undefined)
  timer = window.setInterval(() => refresh(true), 15000)
})
onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
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
  loading.value = true
  try {
    detail.value = await visualizationApi.scene(id)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function refresh(silent = false) {
  if (!detail.value) return
  if (!silent) loading.value = true
  try {
    detail.value = await visualizationApi.scene(detail.value.scene.id)
  } catch (error) {
    if (!silent) ElMessage.error(errorMessage(error))
  } finally {
    if (!silent) loading.value = false
  }
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

async function fullscreen() {
  if (!document.fullscreenElement) await root.value?.requestFullscreen()
  else await document.exitFullscreen()
}
</script>

<template>
  <main ref="root" class="three-screen" v-loading="loading">
    <header class="scene-header">
      <div>
        <span class="eyebrow">LEAN TPM · DIGITAL FACTORY</span>
        <h1>三维设备运行中心</h1>
      </div>
      <div class="scene-actions">
        <el-select
          :model-value="detail?.scene.id"
          filterable
          placeholder="选择场景"
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
        <el-button :icon="Refresh" @click="refresh()">刷新</el-button>
        <el-button :icon="Setting" @click="router.push('/visualization/scenes')">场景配置</el-button>
        <el-button :icon="FullScreen" @click="fullscreen">全屏</el-button>
      </div>
    </header>

    <nav v-if="detail" class="breadcrumbs">
      <template v-for="(item, index) in detail.breadcrumb" :key="item.id">
        <button @click="openScene(item.id)">{{ item.sceneName }}</button>
        <span v-if="index < detail.breadcrumb.length - 1">/</span>
      </template>
    </nav>

    <section class="scene-stage">
      <SceneCanvas :detail="detail" @select="selectNode" />
      <aside class="legend">
        <h3>实时状态图例</h3>
        <div v-for="item in detail?.statusColors" :key="item.statusCode">
          <i :style="{ backgroundColor: item.displayColor }" :class="{ pulse: item.pulseFlag }" />
          <span>{{ item.statusName }}</span>
        </div>
      </aside>
      <div class="scene-meta">
        <b>{{ detail?.scene.sceneName || '未选择场景' }}</b>
        <span>{{ detail?.scene.sceneLevel }} · {{ detail?.nodes.length ?? 0 }} 个节点</span>
      </div>
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
.three-screen { min-height: 100%; display: grid; grid-template-rows: auto auto minmax(600px, 1fr); gap: 10px; padding: 12px; color: #dff4ff; background: #07111f; }
.three-screen:fullscreen { height: 100vh; }
.scene-header { display: flex; justify-content: space-between; align-items: center; gap: 20px; padding: 14px 18px; border: 1px solid rgba(82,178,255,.2); border-radius: 15px; background: rgba(8,25,43,.95); }
.eyebrow { color: #55d6ff; font-size: 10px; letter-spacing: .18em; }
h1 { margin: 3px 0 0; font-size: 27px; }
.scene-actions { display: flex; gap: 8px; align-items: center; }
.breadcrumbs { display: flex; align-items: center; gap: 7px; padding: 8px 14px; border: 1px solid rgba(82,178,255,.13); border-radius: 11px; background: rgba(8,25,43,.8); }
.breadcrumbs button { color: #7dd3fc; border: 0; background: none; cursor: pointer; }.breadcrumbs span { color: #3c5a72; }
.scene-stage { position: relative; min-height: 600px; overflow: hidden; border: 1px solid rgba(82,178,255,.22); border-radius: 16px; background: #07111f; }
.legend { position: absolute; z-index: 3; top: 14px; right: 14px; width: 145px; padding: 12px; border: 1px solid rgba(82,178,255,.18); border-radius: 12px; background: rgba(4,16,28,.82); backdrop-filter: blur(10px); }
.legend h3 { margin: 0 0 9px; font-size: 12px; }.legend div { display: inline-flex; width: 50%; align-items: center; gap: 6px; margin: 4px 0; color: #8faec3; font-size: 10px; }
.legend i { width: 7px; height: 7px; border-radius: 50%; }.legend i.pulse { animation: pulse 1.4s infinite; }
.scene-meta { position: absolute; z-index: 3; left: 14px; top: 14px; display: grid; gap: 3px; padding: 9px 12px; border-left: 3px solid #22d3ee; background: rgba(4,16,28,.76); }.scene-meta span { color: #6e8ba2; font-size: 11px; }
@keyframes pulse { 50% { box-shadow: 0 0 13px currentColor; transform: scale(1.35); } }
@media (max-width: 900px) { .scene-header { align-items: flex-start; flex-direction: column; }.scene-actions { flex-wrap: wrap; }.legend { display: none; } }
</style>
