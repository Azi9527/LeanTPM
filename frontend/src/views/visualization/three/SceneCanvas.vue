<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { accessToken } from '@/utils/http'
import type { SceneDetail, SceneNode } from '@/api/visualization'

const props = withDefaults(defineProps<{ detail?: SceneDetail; compact?: boolean; cinematic?: boolean }>(), {
  compact: false,
  cinematic: false,
})
const emit = defineEmits<{
  select: [node: SceneNode]
  ready: []
  unavailable: [reason: string]
}>()
const host = ref<HTMLDivElement>()
const renderStatus = ref<'INITIALIZING' | 'READY' | 'UNAVAILABLE'>('INITIALIZING')
const failureReason = ref('')
let renderer: THREE.WebGLRenderer | undefined
let scene: THREE.Scene | undefined
let camera: THREE.PerspectiveCamera | undefined
let controls: OrbitControls | undefined
let animationFrame = 0
let resizeObserver: ResizeObserver | undefined
let revision = 0
const clickable: THREE.Object3D[] = []
const pulsing: THREE.Object3D[] = []
const rotating: THREE.Object3D[] = []
const flowParticles: Array<{ mesh: THREE.Mesh; curve: THREE.Curve<THREE.Vector3>; offset: number; speed: number }> = []
const statusRings: Array<{ mesh: THREE.Mesh; phase: number }> = []
const raycaster = new THREE.Raycaster()
const pointer = new THREE.Vector2()
let cruiseEnabled = false

watch(() => props.detail, () => rebuild(), { deep: true })

onMounted(initializeRenderer)

onBeforeUnmount(() => {
  revision += 1
  destroyRenderer()
})

function initializeRenderer() {
  if (!host.value) return
  destroyRenderer()
  renderStatus.value = 'INITIALIZING'
  failureReason.value = ''
  try {
    // 驾驶舱中的紧凑场景优先保证低配终端可用，避免高 DPR、抗锯齿和阴影耗尽显存。
    renderer = new THREE.WebGLRenderer({
      antialias: !props.compact,
      alpha: false,
      depth: true,
      stencil: false,
      powerPreference: 'default',
      failIfMajorPerformanceCaveat: false,
    })
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, props.compact ? 1 : 1.5))
    renderer.outputColorSpace = THREE.SRGBColorSpace
    renderer.shadowMap.enabled = !props.compact
    renderer.domElement.addEventListener('pointerup', selectAt)
    renderer.domElement.addEventListener('webglcontextlost', handleContextLost)
    host.value.appendChild(renderer.domElement)
    resizeObserver = new ResizeObserver(resize)
    resizeObserver.observe(host.value)
    renderStatus.value = 'READY'
    emit('ready')
    void rebuild().catch((error) => markUnavailable(error, '三维场景构建失败'))
    animate()
  } catch (error) {
    markUnavailable(error, '当前浏览器无法创建 WebGL 三维环境')
  }
}

function handleContextLost(event: Event) {
  event.preventDefault()
  markUnavailable(undefined, '显卡的 WebGL 上下文已丢失')
}

function markUnavailable(error: unknown, fallback: string) {
  if (renderStatus.value === 'UNAVAILABLE') return
  const detail = error instanceof Error && error.message ? error.message : ''
  const reason = detail ? `${fallback}：${detail}` : fallback
  renderStatus.value = 'UNAVAILABLE'
  failureReason.value = reason
  destroyRenderer()
  emit('unavailable', reason)
}

function destroyRenderer() {
  cancelAnimationFrame(animationFrame)
  animationFrame = 0
  resizeObserver?.disconnect()
  resizeObserver = undefined
  if (renderer) {
    const canvas = renderer.domElement
    canvas.removeEventListener('pointerup', selectAt)
    canvas.removeEventListener('webglcontextlost', handleContextLost)
    renderer.dispose()
    canvas.remove()
    renderer = undefined
  }
  disposeScene()
}

async function rebuild() {
  if (!renderer || !host.value || !props.detail) return
  const buildRevision = ++revision
  disposeScene()
  clickable.length = 0
  pulsing.length = 0
  rotating.length = 0
  flowParticles.length = 0
  statusRings.length = 0
  const config = props.detail.scene
  scene = new THREE.Scene()
  scene.background = new THREE.Color(config.backgroundColor)
  scene.fog = new THREE.Fog(config.backgroundColor, 35, 90)
  camera = new THREE.PerspectiveCamera(46, 1, 0.1, 300)
  camera.position.set(config.cameraX, config.cameraY, config.cameraZ)
  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  cruiseEnabled = config.autoRotateFlag || props.cinematic
  controls.autoRotate = cruiseEnabled
  controls.autoRotateSpeed = 0.55
  controls.target.set(config.targetX, config.targetY, config.targetZ)
  controls.maxPolarAngle = Math.PI * 0.48
  controls.minDistance = 8
  controls.maxDistance = 80
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
  addFactoryEnvironment(scene)

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
    scene.add(statusBeaconFor(node, object.position))
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
    const zoneMaterial = new THREE.MeshStandardMaterial({
      color,
      emissive: new THREE.Color(color).multiplyScalar(0.08),
      transparent: true,
      opacity: 0.2,
      metalness: 0.25,
      roughness: 0.5,
    })
    const zone = new THREE.Mesh(new THREE.BoxGeometry(2.8, 0.1, 2), zoneMaterial)
    zone.position.y = 0.06
    group.add(zone)
    const outline = new THREE.LineSegments(
      new THREE.EdgesGeometry(zone.geometry),
      new THREE.LineBasicMaterial({ color, transparent: true, opacity: 0.82 }),
    )
    outline.position.copy(zone.position)
    group.add(outline)
    for (const [x, z] of [[-1.28, -0.88], [1.28, -0.88], [-1.28, 0.88], [1.28, 0.88]]) {
      const corner = new THREE.Mesh(new THREE.BoxGeometry(0.05, 0.75, 0.05), new THREE.MeshBasicMaterial({ color }))
      corner.position.set(x, 0.38, z)
      group.add(corner)
    }
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
  if (typeof context.roundRect === 'function') context.roundRect(8, 8, 496, 80, 18)
  else context.rect(8, 8, 496, 80)
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

function addFactoryEnvironment(targetScene: THREE.Scene) {
  const world = new THREE.Group()
  world.name = 'digital-twin-environment'

  const site = new THREE.Mesh(
    new THREE.BoxGeometry(54, 0.18, 36),
    new THREE.MeshStandardMaterial({ color: '#0b2132', roughness: 0.82, metalness: 0.18 }),
  )
  site.position.y = -0.08
  site.receiveShadow = true
  world.add(site)

  const roadMaterial = new THREE.MeshStandardMaterial({ color: '#183247', roughness: 0.88 })
  const roadMain = new THREE.Mesh(new THREE.PlaneGeometry(50, 5.2), roadMaterial)
  roadMain.rotation.x = -Math.PI / 2
  roadMain.position.set(0, 0.025, 9.8)
  world.add(roadMain)
  const roadCross = new THREE.Mesh(new THREE.PlaneGeometry(5.2, 30), roadMaterial)
  roadCross.rotation.x = -Math.PI / 2
  roadCross.position.set(0, 0.03, -4)
  world.add(roadCross)

  const markingMaterial = new THREE.MeshBasicMaterial({ color: '#f5c84c' })
  for (let x = -23; x <= 23; x += 3.2) {
    const marking = new THREE.Mesh(new THREE.PlaneGeometry(1.8, 0.09), markingMaterial)
    marking.rotation.x = -Math.PI / 2
    marking.position.set(x, 0.045, 9.8)
    world.add(marking)
  }
  for (let z = -17; z <= 7; z += 3.2) {
    const marking = new THREE.Mesh(new THREE.PlaneGeometry(0.09, 1.8), markingMaterial)
    marking.rotation.x = -Math.PI / 2
    marking.position.set(0, 0.05, z)
    world.add(marking)
  }

  const zoneMaterial = new THREE.MeshBasicMaterial({ color: '#0ea5e9', transparent: true, opacity: 0.055 })
  ;[
    [-14, -5, 19, 17],
    [13.5, -5, 18, 17],
  ].forEach(([x, z, width, depth]) => {
    const zone = new THREE.Mesh(new THREE.PlaneGeometry(width, depth), zoneMaterial.clone())
    zone.rotation.x = -Math.PI / 2
    zone.position.set(x, 0.035, z)
    world.add(zone)
    const edge = new THREE.LineSegments(
      new THREE.EdgesGeometry(new THREE.BoxGeometry(width, 0.05, depth)),
      new THREE.LineBasicMaterial({ color: '#155e75', transparent: true, opacity: 0.65 }),
    )
    edge.position.set(x, 0.06, z)
    world.add(edge)
  })

  addWorkshop(world, -15.5, -8.5, 15, 8, 4.6, '#0f4c67')
  addWorkshop(world, 13.5, -8.5, 14, 8, 4.2, '#115e59')
  addWarehouse(world, 14.5, 3.8)
  addTankFarm(world, -19.5, 3.4)
  addConveyor(world)
  addPipeline(world)
  addUtilityProps(world)
  addFlowPath(world)
  addDigitalDust(world)
  targetScene.add(world)
}

function addWorkshop(group: THREE.Group, x: number, z: number, width: number, depth: number, height: number, color: string) {
  const shellMaterial = new THREE.MeshStandardMaterial({ color, transparent: true, opacity: 0.28, roughness: 0.5, metalness: 0.45 })
  const shell = new THREE.Mesh(new THREE.BoxGeometry(width, height, depth), shellMaterial)
  shell.position.set(x, height / 2, z)
  shell.castShadow = true
  shell.receiveShadow = true
  group.add(shell)
  const edges = new THREE.LineSegments(
    new THREE.EdgesGeometry(shell.geometry),
    new THREE.LineBasicMaterial({ color: '#38bdf8', transparent: true, opacity: 0.62 }),
  )
  edges.position.copy(shell.position)
  group.add(edges)

  const roofMaterial = new THREE.MeshStandardMaterial({ color: '#17435a', roughness: 0.45, metalness: 0.65 })
  for (const offset of [-depth * 0.23, depth * 0.23]) {
    const roof = new THREE.Mesh(new THREE.BoxGeometry(width + 0.5, 0.16, depth * 0.55), roofMaterial)
    roof.position.set(x, height + 0.55, z + offset)
    roof.rotation.x = offset < 0 ? 0.16 : -0.16
    group.add(roof)
  }
  const windowMaterial = new THREE.MeshBasicMaterial({ color: '#4de3ff', transparent: true, opacity: 0.65 })
  for (let ix = -width / 2 + 1.2; ix < width / 2; ix += 2.15) {
    const window = new THREE.Mesh(new THREE.PlaneGeometry(1.15, 0.48), windowMaterial)
    window.position.set(x + ix, height * 0.62, z + depth / 2 + 0.012)
    group.add(window)
  }
  const machineMaterial = new THREE.MeshStandardMaterial({ color: '#274d62', metalness: 0.65, roughness: 0.32 })
  for (let row = 0; row < 2; row += 1) {
    for (let column = 0; column < 4; column += 1) {
      const machine = new THREE.Group()
      const body = new THREE.Mesh(new THREE.BoxGeometry(1.35, 1.25, 1.05), machineMaterial)
      body.position.y = 0.63
      machine.add(body)
      const screen = new THREE.Mesh(
        new THREE.PlaneGeometry(0.48, 0.32),
        new THREE.MeshBasicMaterial({ color: column === 3 && row === 1 ? '#ff5b6e' : '#35e49a' }),
      )
      screen.position.set(0, 0.8, 0.531)
      machine.add(screen)
      const signal = new THREE.PointLight(column === 3 && row === 1 ? '#ff5b6e' : '#35e49a', 1.8, 2.5)
      signal.position.set(0, 1.45, 0)
      machine.add(signal)
      machine.position.set(x - width / 2 + 2.5 + column * 3, 0, z - 1.75 + row * 3.5)
      group.add(machine)
    }
  }
  const beacon = new THREE.PointLight('#38bdf8', 8, 15)
  beacon.position.set(x, height + 2.1, z)
  group.add(beacon)
}

function addWarehouse(group: THREE.Group, x: number, z: number) {
  const material = new THREE.MeshStandardMaterial({ color: '#153047', metalness: 0.4, roughness: 0.55 })
  const building = new THREE.Mesh(new THREE.BoxGeometry(10, 3, 5.5), material)
  building.position.set(x, 1.5, z)
  group.add(building)
  for (const doorX of [-2.8, 0, 2.8]) {
    const door = new THREE.Mesh(
      new THREE.PlaneGeometry(1.8, 2.1),
      new THREE.MeshBasicMaterial({ color: '#176b87', transparent: true, opacity: 0.8 }),
    )
    door.position.set(x + doorX, 1.18, z + 2.76)
    group.add(door)
  }
}

function addTankFarm(group: THREE.Group, x: number, z: number) {
  const material = new THREE.MeshStandardMaterial({ color: '#8198aa', metalness: 0.72, roughness: 0.28 })
  for (let i = 0; i < 3; i += 1) {
    const tank = new THREE.Group()
    const body = new THREE.Mesh(new THREE.CylinderGeometry(1.15, 1.15, 2.8, 24), material)
    body.position.y = 1.4
    tank.add(body)
    const cap = new THREE.Mesh(new THREE.SphereGeometry(1.15, 20, 10, 0, Math.PI * 2, 0, Math.PI / 2), material)
    cap.position.y = 2.8
    tank.add(cap)
    tank.position.set(x + i * 3, 0, z)
    group.add(tank)
  }
}

function addConveyor(group: THREE.Group) {
  const frameMaterial = new THREE.MeshStandardMaterial({ color: '#536b7d', metalness: 0.72, roughness: 0.3 })
  const belt = new THREE.Mesh(
    new THREE.BoxGeometry(14, 0.18, 1.7),
    new THREE.MeshStandardMaterial({ color: '#17212b', roughness: 0.86 }),
  )
  belt.position.set(5, 1.05, 3.5)
  group.add(belt)
  for (let x = -1.5; x <= 11.5; x += 1.1) {
    const roller = new THREE.Mesh(new THREE.CylinderGeometry(0.11, 0.11, 1.55, 12), frameMaterial)
    roller.rotation.x = Math.PI / 2
    roller.position.set(x, 1.18, 3.5)
    group.add(roller)
  }
  for (const x of [-1, 2, 5, 8, 11]) {
    const leg = new THREE.Mesh(new THREE.BoxGeometry(0.15, 1, 1.5), frameMaterial)
    leg.position.set(x, 0.5, 3.5)
    group.add(leg)
  }
}

function addPipeline(group: THREE.Group) {
  const curve = new THREE.CatmullRomCurve3([
    new THREE.Vector3(-20, 1.1, 6.3),
    new THREE.Vector3(-12, 1.1, 6.3),
    new THREE.Vector3(-8, 2.4, 5.4),
    new THREE.Vector3(-1.5, 2.4, 5.4),
  ])
  const pipe = new THREE.Mesh(
    new THREE.TubeGeometry(curve, 60, 0.13, 10, false),
    new THREE.MeshStandardMaterial({ color: '#22c55e', emissive: '#0b5f34', emissiveIntensity: 0.8, metalness: 0.55 }),
  )
  group.add(pipe)
}

function addUtilityProps(group: THREE.Group) {
  const poleMaterial = new THREE.MeshStandardMaterial({ color: '#60758a', metalness: 0.8 })
  for (const [x, z] of [[-22, 10], [-12, 10], [10, 10], [22, 10], [-1.8, -14]]) {
    const pole = new THREE.Mesh(new THREE.CylinderGeometry(0.08, 0.11, 4.5, 10), poleMaterial)
    pole.position.set(x, 2.25, z)
    group.add(pole)
    const lamp = new THREE.PointLight('#9cecff', 7, 8)
    lamp.position.set(x, 4.5, z)
    group.add(lamp)
  }
  for (let i = 0; i < 6; i += 1) {
    const tree = new THREE.Group()
    const trunk = new THREE.Mesh(new THREE.CylinderGeometry(0.12, 0.16, 1.2, 8), new THREE.MeshStandardMaterial({ color: '#5b4634' }))
    trunk.position.y = 0.6
    tree.add(trunk)
    const crown = new THREE.Mesh(new THREE.ConeGeometry(0.7, 1.8, 10), new THREE.MeshStandardMaterial({ color: '#176b4d' }))
    crown.position.y = 1.8
    tree.add(crown)
    tree.position.set(-25 + i * 10, 0, 16)
    group.add(tree)
  }
  const fan = new THREE.Group()
  for (let i = 0; i < 3; i += 1) {
    const blade = new THREE.Mesh(new THREE.BoxGeometry(0.22, 0.06, 2.4), new THREE.MeshBasicMaterial({ color: '#52d8ff' }))
    blade.rotation.y = i * Math.PI / 3
    fan.add(blade)
  }
  fan.position.set(22, 5.2, -12)
  rotating.push(fan)
  group.add(fan)
}

function addFlowPath(group: THREE.Group) {
  const curve = new THREE.CatmullRomCurve3([
    new THREE.Vector3(-20, 0.45, -1),
    new THREE.Vector3(-9, 0.45, 4),
    new THREE.Vector3(0, 0.45, 4),
    new THREE.Vector3(10, 0.45, 4),
    new THREE.Vector3(20, 0.45, 0),
  ])
  const guide = new THREE.Mesh(
    new THREE.TubeGeometry(curve, 80, 0.035, 8, false),
    new THREE.MeshBasicMaterial({ color: '#22d3ee', transparent: true, opacity: 0.42 }),
  )
  group.add(guide)
  for (let i = 0; i < 18; i += 1) {
    const mesh = new THREE.Mesh(
      new THREE.SphereGeometry(0.11, 10, 8),
      new THREE.MeshBasicMaterial({ color: i % 3 === 0 ? '#facc15' : '#4de3ff' }),
    )
    flowParticles.push({ mesh, curve, offset: i / 18, speed: 0.000018 })
    group.add(mesh)
  }
}

function addDigitalDust(group: THREE.Group) {
  const positions = new Float32Array(240 * 3)
  for (let i = 0; i < 240; i += 1) {
    positions[i * 3] = (Math.random() - 0.5) * 52
    positions[i * 3 + 1] = 0.5 + Math.random() * 11
    positions[i * 3 + 2] = (Math.random() - 0.5) * 34
  }
  const geometry = new THREE.BufferGeometry()
  geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3))
  const points = new THREE.Points(geometry, new THREE.PointsMaterial({ color: '#38bdf8', size: 0.045, transparent: true, opacity: 0.42 }))
  group.add(points)
}

function statusBeaconFor(node: SceneNode, position: THREE.Vector3) {
  const color = node.displayColor || node.fallbackColor || '#38bdf8'
  const beacon = new THREE.Group()
  beacon.position.copy(position)
  const pole = new THREE.Mesh(
    new THREE.CylinderGeometry(0.025, 0.025, 2.6, 8),
    new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.55 }),
  )
  pole.position.y = 1.3
  beacon.add(pole)
  const light = new THREE.Mesh(new THREE.SphereGeometry(0.16, 12, 10), new THREE.MeshBasicMaterial({ color }))
  light.position.y = 2.65
  beacon.add(light)
  const ring = new THREE.Mesh(
    new THREE.RingGeometry(0.2, 0.27, 28),
    new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.45, side: THREE.DoubleSide }),
  )
  ring.rotation.x = -Math.PI / 2
  ring.position.y = 2.64
  beacon.add(ring)
  statusRings.push({ mesh: ring, phase: (node.id % 7) / 7 })
  const glow = new THREE.PointLight(color, node.pulseFlag ? 7 : 3, 7)
  glow.position.y = 2.65
  beacon.add(glow)
  return beacon
}

function animate(time = 0) {
  if (renderStatus.value !== 'READY') return
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
  rotating.forEach((object, index) => {
    object.rotation.y += 0.004 + index * 0.0005
  })
  flowParticles.forEach((particle) => {
    particle.mesh.position.copy(particle.curve.getPoint((time * particle.speed + particle.offset) % 1))
  })
  statusRings.forEach(({ mesh, phase }) => {
    const wave = 1 + ((time * 0.00055 + phase) % 1) * 1.6
    mesh.scale.setScalar(wave)
    ;(mesh.material as THREE.MeshBasicMaterial).opacity = Math.max(0, 0.48 - (wave - 1) * 0.25)
  })
  if (renderer && scene && camera) {
    try {
      renderer.render(scene, camera)
    } catch (error) {
      markUnavailable(error, '三维场景渲染失败')
    }
  }
}

function setView(view: 'OVERVIEW' | 'TOP' | 'LINE' | 'ROAM') {
  if (!camera || !controls || !props.detail) return
  const config = props.detail.scene
  const target = new THREE.Vector3(config.targetX, config.targetY, config.targetZ)
  controls.target.copy(target)
  if (view === 'TOP') camera.position.copy(target).add(new THREE.Vector3(0.01, 42, 0.01))
  else if (view === 'LINE') camera.position.copy(target).add(new THREE.Vector3(0, 10, 30))
  else if (view === 'ROAM') camera.position.copy(target).add(new THREE.Vector3(-28, 16, 24))
  else camera.position.set(config.cameraX, config.cameraY, config.cameraZ)
  controls.autoRotate = view === 'ROAM' || cruiseEnabled
  controls.update()
}

function toggleCruise(force?: boolean) {
  cruiseEnabled = force ?? !cruiseEnabled
  if (controls) controls.autoRotate = cruiseEnabled
  return cruiseEnabled
}

defineExpose({ setView, toggleCruise })

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
  if (object instanceof THREE.Mesh || object instanceof THREE.Line || object instanceof THREE.LineSegments || object instanceof THREE.Points) {
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
  <div ref="host" class="scene-host" :class="{ compact }">
    <div v-if="renderStatus === 'INITIALIZING'" class="scene-state">
      <strong>正在初始化三维场景</strong>
      <span>正在检测浏览器与显卡渲染能力…</span>
    </div>
    <div v-else-if="renderStatus === 'UNAVAILABLE'" class="scene-state unavailable">
      <strong>当前电脑无法显示三维场景</strong>
      <span>{{ failureReason }}</span>
      <button type="button" @click="initializeRenderer">重新检测</button>
    </div>
    <div v-if="renderStatus === 'READY'" class="scene-hint">左键选择 · 拖动旋转 · 滚轮缩放 · 右键平移</div>
  </div>
</template>

<style scoped>
.scene-host { position: relative; width: 100%; height: 100%; min-height: 560px; overflow: hidden; border-radius: 14px; }
.scene-host.compact { min-height: 0; border-radius: 0; }
.scene-hint { position: absolute; z-index: 2; right: 14px; bottom: 12px; padding: 7px 10px; color: #7897ae; font-size: 11px; border: 1px solid rgba(82,178,255,.16); border-radius: 8px; background: rgba(4,16,28,.72); pointer-events: none; }
.scene-state { position: absolute; z-index: 3; inset: 0; display: grid; place-content: center; gap: 8px; padding: 28px; color: #a9c9d8; text-align: center; background: radial-gradient(circle, rgba(18,76,98,.22), rgba(4,16,28,.96)); }
.scene-state strong { color: #e1f7ff; font-size: 16px; }
.scene-state span { max-width: 520px; color: #7394a5; font-size: 12px; line-height: 1.7; }
.scene-state button { justify-self: center; margin-top: 4px; padding: 7px 14px; color: #9ee6ff; border: 1px solid rgba(56,217,255,.35); border-radius: 6px; background: rgba(30,116,145,.22); cursor: pointer; }
.scene-state.unavailable strong { color: #ffc56b; }
</style>
