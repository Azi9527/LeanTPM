<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { accessToken } from '@/utils/http'
import type { SceneDetail, SceneNode } from '@/api/visualization'

const props = defineProps<{ detail?: SceneDetail }>()
const emit = defineEmits<{ select: [node: SceneNode] }>()
const host = ref<HTMLDivElement>()
let renderer: THREE.WebGLRenderer | undefined
let scene: THREE.Scene | undefined
let camera: THREE.PerspectiveCamera | undefined
let controls: OrbitControls | undefined
let animationFrame = 0
let resizeObserver: ResizeObserver | undefined
let revision = 0
const clickable: THREE.Object3D[] = []
const pulsing: THREE.Object3D[] = []
const raycaster = new THREE.Raycaster()
const pointer = new THREE.Vector2()

watch(() => props.detail, () => rebuild(), { deep: true })

onMounted(() => {
  if (!host.value) return
  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.outputColorSpace = THREE.SRGBColorSpace
  renderer.shadowMap.enabled = true
  host.value.appendChild(renderer.domElement)
  renderer.domElement.addEventListener('pointerup', selectAt)
  resizeObserver = new ResizeObserver(resize)
  resizeObserver.observe(host.value)
  rebuild()
  animate()
})

onBeforeUnmount(() => {
  revision += 1
  cancelAnimationFrame(animationFrame)
  resizeObserver?.disconnect()
  if (renderer) {
    renderer.domElement.removeEventListener('pointerup', selectAt)
    renderer.dispose()
    renderer.domElement.remove()
  }
  disposeScene()
})

async function rebuild() {
  if (!renderer || !host.value || !props.detail) return
  const buildRevision = ++revision
  disposeScene()
  clickable.length = 0
  pulsing.length = 0
  const config = props.detail.scene
  scene = new THREE.Scene()
  scene.background = new THREE.Color(config.backgroundColor)
  scene.fog = new THREE.Fog(config.backgroundColor, 35, 90)
  camera = new THREE.PerspectiveCamera(46, 1, 0.1, 300)
  camera.position.set(config.cameraX, config.cameraY, config.cameraZ)
  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  controls.autoRotate = config.autoRotateFlag
  controls.autoRotateSpeed = 0.7
  controls.target.set(config.targetX, config.targetY, config.targetZ)
  controls.maxPolarAngle = Math.PI * 0.48
  controls.update()

  const ambient = new THREE.HemisphereLight('#bde9ff', '#09213a', 2.2)
  scene.add(ambient)
  const key = new THREE.DirectionalLight('#d9f3ff', 3.2)
  key.position.set(12, 24, 14)
  key.castShadow = true
  scene.add(key)
  const rim = new THREE.PointLight('#22d3ee', 28, 55)
  rim.position.set(-14, 9, -12)
  scene.add(rim)
  const grid = new THREE.GridHelper(64, 32, config.gridColor, config.gridColor)
  const gridMaterial = grid.material as THREE.Material
  gridMaterial.opacity = 0.38
  gridMaterial.transparent = true
  scene.add(grid)
  const floor = new THREE.Mesh(
    new THREE.PlaneGeometry(64, 64),
    new THREE.MeshStandardMaterial({ color: '#081827', roughness: 0.92, metalness: 0.08 }),
  )
  floor.rotation.x = -Math.PI / 2
  floor.position.y = -0.04
  floor.receiveShadow = true
  scene.add(floor)

  for (const node of props.detail.nodes.filter((item) => item.visibleFlag)) {
    const object = await objectFor(node)
    if (buildRevision !== revision || !scene) {
      disposeObject(object)
      return
    }
    applyTransform(object, node)
    object.userData.node = node
    object.traverse((child) => {
      if (child instanceof THREE.Mesh) {
        child.castShadow = true
        child.receiveShadow = true
        child.userData.node = node
        clickable.push(child)
      }
    })
    if (node.pulseFlag) pulsing.push(object)
    scene.add(object)
    if (node.labelVisibleFlag) scene.add(labelFor(node, object.position))
  }
  resize()
}

async function objectFor(node: SceneNode): Promise<THREE.Object3D> {
  if (node.modelFormat && node.modelFormat !== 'PRIMITIVE' && node.modelResourceId) {
    try {
      const response = await fetch(`/api/v1/visualization/models/${node.modelResourceId}/content`, {
        headers: { Authorization: `Bearer ${accessToken() ?? ''}` },
      })
      if (!response.ok) throw new Error('model download failed')
      const buffer = await response.arrayBuffer()
      const gltf = await new Promise<Awaited<ReturnType<GLTFLoader['parseAsync']>>>((resolve, reject) => {
        new GLTFLoader().parse(buffer, '', resolve, reject)
      })
      tint(gltf.scene, node.displayColor || node.fallbackColor)
      return gltf.scene
    } catch {
      return primitive(node)
    }
  }
  return primitive(node)
}

function primitive(node: SceneNode): THREE.Object3D {
  const color = node.displayColor || node.fallbackColor || '#38bdf8'
  const material = new THREE.MeshStandardMaterial({
    color,
    emissive: new THREE.Color(color).multiplyScalar(0.12),
    metalness: 0.45,
    roughness: 0.38,
  })
  const type = node.primitiveType || (node.nodeType === 'EQUIPMENT' ? 'BOX' : 'WORKSHOP')
  const group = new THREE.Group()
  if (type === 'FACTORY' || type === 'WORKSHOP') {
    const building = new THREE.Mesh(new THREE.BoxGeometry(2.8, 1, 2), material)
    building.position.y = 0.5
    group.add(building)
    const roof = new THREE.Mesh(
      new THREE.ConeGeometry(2.1, 0.65, 4),
      material.clone(),
    )
    roof.rotation.y = Math.PI / 4
    roof.position.y = 1.3
    group.add(roof)
  } else if (type === 'LINE') {
    group.add(new THREE.Mesh(new THREE.BoxGeometry(3.2, 0.34, 0.85), material))
    for (const x of [-1.1, 0, 1.1]) {
      const station = new THREE.Mesh(new THREE.BoxGeometry(0.62, 0.75, 0.68), material.clone())
      station.position.set(x, 0.5, 0)
      group.add(station)
    }
  } else if (type === 'ROBOT') {
    const base = new THREE.Mesh(new THREE.CylinderGeometry(0.45, 0.55, 0.36, 16), material)
    base.position.y = 0.18
    group.add(base)
    const arm = new THREE.Mesh(new THREE.BoxGeometry(0.28, 1.15, 0.28), material.clone())
    arm.position.set(0, 0.9, 0)
    arm.rotation.z = -0.35
    group.add(arm)
    const forearm = arm.clone()
    forearm.position.set(0.46, 1.58, 0)
    forearm.rotation.z = 0.75
    group.add(forearm)
  } else if (type === 'PUMP') {
    const body = new THREE.Mesh(new THREE.CylinderGeometry(0.58, 0.58, 1.25, 20), material)
    body.rotation.z = Math.PI / 2
    body.position.y = 0.65
    group.add(body)
  } else {
    const body = new THREE.Mesh(new THREE.BoxGeometry(1.45, 1.25, 1.15), material)
    body.position.y = 0.65
    group.add(body)
    const panel = new THREE.Mesh(
      new THREE.BoxGeometry(0.65, 0.55, 0.05),
      new THREE.MeshStandardMaterial({ color: '#081827', emissive: '#0ea5e9', emissiveIntensity: 0.25 }),
    )
    panel.position.set(0, 0.78, 0.6)
    group.add(panel)
  }
  return group
}

function labelFor(node: SceneNode, position: THREE.Vector3) {
  const canvas = document.createElement('canvas')
  canvas.width = 512
  canvas.height = 96
  const context = canvas.getContext('2d')!
  context.fillStyle = 'rgba(4, 16, 28, .82)'
  context.roundRect(8, 8, 496, 80, 18)
  context.fill()
  context.strokeStyle = node.displayColor || '#38bdf8'
  context.lineWidth = 3
  context.stroke()
  context.font = '600 30px sans-serif'
  context.textAlign = 'center'
  context.textBaseline = 'middle'
  context.fillStyle = '#e5f6ff'
  context.fillText(`${node.displayName} · ${node.statusName}`, 256, 49)
  const texture = new THREE.CanvasTexture(canvas)
  texture.colorSpace = THREE.SRGBColorSpace
  const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: texture, transparent: true }))
  sprite.position.copy(position).add(new THREE.Vector3(0, Math.max(node.scaleY, 1) * 1.2, 0))
  sprite.scale.set(4.2, 0.78, 1)
  sprite.userData.label = true
  return sprite
}

function applyTransform(object: THREE.Object3D, node: SceneNode) {
  object.position.set(node.positionX, node.positionY, node.positionZ)
  object.rotation.set(node.rotationX, node.rotationY, node.rotationZ)
  object.scale.set(node.scaleX, node.scaleY, node.scaleZ)
}

function tint(object: THREE.Object3D, color: string) {
  object.traverse((child) => {
    if (!(child instanceof THREE.Mesh)) return
    const material = child.material as THREE.MeshStandardMaterial
    if (!material?.color) return
    child.material = material.clone()
    ;(child.material as THREE.MeshStandardMaterial).color.lerp(new THREE.Color(color), 0.45)
  })
}

function animate(time = 0) {
  animationFrame = requestAnimationFrame(animate)
  controls?.update()
  const pulse = 1 + Math.sin(time * 0.004) * 0.035
  pulsing.forEach((object) => {
    object.traverse((child) => {
      if (child instanceof THREE.Mesh && child.material instanceof THREE.MeshStandardMaterial) {
        child.material.emissiveIntensity = pulse
      }
    })
  })
  if (renderer && scene && camera) renderer.render(scene, camera)
}

function selectAt(event: PointerEvent) {
  if (!renderer || !camera) return
  const rect = renderer.domElement.getBoundingClientRect()
  pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1
  pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1
  raycaster.setFromCamera(pointer, camera)
  const hit = raycaster.intersectObjects(clickable, false)[0]
  const node = hit?.object.userData.node as SceneNode | undefined
  if (node) emit('select', node)
}

function resize() {
  if (!host.value || !renderer || !camera) return
  const width = Math.max(host.value.clientWidth, 1)
  const height = Math.max(host.value.clientHeight, 1)
  renderer.setSize(width, height, false)
  camera.aspect = width / height
  camera.updateProjectionMatrix()
}

function disposeScene() {
  scene?.traverse((object) => disposeObject(object))
  scene?.clear()
  controls?.dispose()
  controls = undefined
  scene = undefined
  camera = undefined
}

function disposeObject(object: THREE.Object3D) {
  if (object instanceof THREE.Mesh) {
    object.geometry?.dispose()
    const materials = Array.isArray(object.material) ? object.material : [object.material]
    materials.forEach((material) => material.dispose())
  }
  if (object instanceof THREE.Sprite) {
    object.material.map?.dispose()
    object.material.dispose()
  }
}
</script>

<template>
  <div ref="host" class="scene-host">
    <div class="scene-hint">左键选择 · 拖动旋转 · 滚轮缩放 · 右键平移</div>
  </div>
</template>

<style scoped>
.scene-host { position: relative; width: 100%; height: 100%; min-height: 560px; overflow: hidden; border-radius: 14px; }
.scene-hint { position: absolute; z-index: 2; right: 14px; bottom: 12px; padding: 7px 10px; color: #7897ae; font-size: 11px; border: 1px solid rgba(82,178,255,.16); border-radius: 8px; background: rgba(4,16,28,.72); pointer-events: none; }
</style>
