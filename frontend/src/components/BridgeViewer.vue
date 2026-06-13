<template>
  <div ref="container" class="relative w-full h-full bg-gradient-to-b from-sky-100 to-stone-200 overflow-hidden">
    <div class="absolute top-4 left-4 z-10 space-y-2">
      <select v-model="selectedBridge" @change="loadBridge"
        class="bg-white border border-stone-300 rounded px-3 py-1.5 text-sm shadow">
        <option v-for="b in bridges" :key="b.id" :value="b.id">{{ b.name }}</option>
      </select>
      <div class="flex gap-2">
        <button @click="toggleHeatmap"
          :class="showHeatmap ? 'bg-red-500 text-white' : 'bg-white text-stone-700'"
          class="px-3 py-1 text-xs rounded shadow border border-stone-300">
          热力图
        </button>
        <button @click="toggleCracks"
          :class="showCracks ? 'bg-red-600 text-white' : 'bg-white text-stone-700'"
          class="px-3 py-1 text-xs rounded shadow border border-stone-300">
          裂缝标记
        </button>
        <select v-model.number="lodLevel" @change="updateLOD"
          class="bg-white border border-stone-300 rounded px-2 py-1 text-xs shadow">
          <option :value="2">高精度</option>
          <option :value="1">中精度</option>
          <option :value="0">低精度</option>
        </select>
      </div>
      <div class="text-xs text-stone-600 bg-white/80 px-2 py-1 rounded">
        LOD: {{ lodNames[lodLevel] }} | 内存: {{ memoryInfo }}
      </div>
    </div>

    <div class="absolute bottom-4 right-4 z-10 w-48 bg-white/90 rounded shadow p-3 text-xs">
      <div class="font-bold mb-2">应力图例 (Pa)</div>
      <div class="h-4 rounded bg-gradient-to-r from-blue-500 via-green-500 to-red-500"></div>
      <div class="flex justify-between mt-1 text-stone-500">
        <span>{{ minStress.toExponential(1) }}</span>
        <span>{{ maxStress.toExponential(1) }}</span>
      </div>
    </div>

    <div v-if="selectedSensor" class="absolute top-4 right-4 z-20 w-80 bg-white rounded-lg shadow-xl border border-stone-200 overflow-hidden">
      <div class="bg-primary text-white px-4 py-2 flex justify-between items-center">
        <span class="font-medium text-sm">{{ selectedSensor.name || '传感器详情' }}</span>
        <button @click="selectedSensor = null" class="text-white/80 hover:text-white text-lg leading-none">&times;</button>
      </div>
      <div class="p-3">
        <div class="text-xs text-stone-500 mb-1">类型：{{ sensorTypes[selectedSensor.type] || selectedSensor.type }}</div>
        <div class="text-2xl font-bold text-stone-800">{{ latestValue }}{{ sensorUnits[selectedSensor.type] || '' }}</div>
        <div ref="trendChart" class="h-40 mt-3"></div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import * as echarts from 'echarts'
import axios from 'axios'
import { createArchGeometry, applyHeatmapColors, createPierGeometry, heatColor } from '@/utils/bridgeGeometry'

const props = defineProps({
  showHeatmap: { type: Boolean, default: true },
  showCracks: { type: Boolean, default: true }
})

const emit = defineEmits(['sensor-click'])

const container = ref(null)
const trendChart = ref(null)
const selectedBridge = ref(1)
const bridges = ref([])
const lodLevel = ref(1)
const showHeatmap = ref(true)
const showCracks = ref(true)
const selectedSensor = ref(null)
const latestValue = ref(0)
const minStress = ref(0)
const maxStress = ref(1e6)
const memoryInfo = ref('')

const lodNames = ['低', '中', '高']
const sensorTypes = { strain: '应变计', displacement: '位移计', crack: '裂缝计', temperature: '温度传感器', vibration: '振动传感器' }
const sensorUnits = { strain: ' με', displacement: ' mm', crack: ' mm', temperature: ' °C', vibration: ' mm/s²' }

let scene, camera, renderer, controls, bridgeGroup, sensorsGroup, cracksGroup
let archLOD = [], pierLOD = []
let animationId
let chartInstance = null
let sensorObjects = []
let bridgeData = null

onMounted(async () => {
  await loadBridgeList()
  initScene()
  await loadBridge()
  window.addEventListener('resize', onResize)
  animate()
})

onUnmounted(() => {
  cancelAnimationFrame(animationId)
  window.removeEventListener('resize', onResize)
  if (chartInstance) chartInstance.dispose()
  if (renderer) {
    renderer.dispose()
  }
})

async function loadBridgeList() {
  try {
    const res = await axios.get('/api/bridges')
    bridges.value = res.data.data || []
    if (bridges.value.length && !selectedBridge.value) {
      selectedBridge.value = bridges.value[0].id
    }
  } catch (e) {
    bridges.value = [
      { id: 1, name: '赵州桥', spanLength: 37, riseSpanRatio: 0.2, pierThickness: 1.5, archCount: 1 },
      { id: 2, name: '卢沟桥', spanLength: 21.3, riseSpanRatio: 0.25, pierThickness: 1.2, archCount: 11 }
    ]
  }
}

function initScene() {
  const w = container.value.clientWidth
  const h = container.value.clientHeight

  scene = new THREE.Scene()
  scene.background = new THREE.Color(0x87ceeb)
  scene.fog = new THREE.Fog(0x87ceeb, 100, 500)

  camera = new THREE.PerspectiveCamera(50, w / h, 0.1, 2000)
  camera.position.set(0, 15, 50)

  renderer = new THREE.WebGLRenderer({ antialias: true })
  renderer.setSize(w, h)
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.shadowMap.enabled = true
  container.value.appendChild(renderer.domElement)

  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  controls.dampingFactor = 0.05

  const ambient = new THREE.AmbientLight(0xffffff, 0.7)
  scene.add(ambient)
  const dir = new THREE.DirectionalLight(0xffffff, 1.0)
  dir.position.set(30, 50, 30)
  dir.castShadow = true
  scene.add(dir)

  const ground = new THREE.Mesh(
    new THREE.PlaneGeometry(500, 500),
    new THREE.MeshLambertMaterial({ color: 0x8b9567 })
  )
  ground.rotation.x = -Math.PI / 2
  ground.receiveShadow = true
  scene.add(ground)

  bridgeGroup = new THREE.Group()
  sensorsGroup = new THREE.Group()
  cracksGroup = new THREE.Group()
  scene.add(bridgeGroup)
  scene.add(sensorsGroup)
  scene.add(cracksGroup)

  const raycaster = new THREE.Raycaster()
  const mouse = new THREE.Vector2()
  renderer.domElement.addEventListener('click', (e) => {
    const rect = renderer.domElement.getBoundingClientRect()
    mouse.x = ((e.clientX - rect.left) / rect.width) * 2 - 1
    mouse.y = -((e.clientY - rect.top) / rect.height) * 2 + 1
    raycaster.setFromCamera(mouse, camera)
    const hits = raycaster.intersectObjects(sensorObjects)
    if (hits.length > 0) {
      const sensor = hits[0].object.userData.sensor
      if (sensor) {
        selectedSensor.value = sensor
        emit('sensor-click', sensor)
        loadTrendData(sensor.id)
      }
    }
  })
}

async function loadBridge() {
  const bridge = bridges.value.find(b => b.id === selectedBridge.value)
  bridgeData = bridge
  buildBridgeModels(bridge || { spanLength: 37, riseSpanRatio: 0.2, pierThickness: 1.5 })
  await loadFemResult()
  await loadSensors()
  await loadCracks()
  updateMemoryInfo()
}

function buildBridgeModels(bridge) {
  while (bridgeGroup.children.length > 0) {
    const c = bridgeGroup.children[0]
    bridgeGroup.remove(c)
    c.geometry?.dispose()
    c.material?.dispose()
  }
  archLOD = []
  pierLOD = []

  const span = bridge.spanLength || 37
  const rise = span * (bridge.riseSpanRatio || 0.2)
  const t = bridge.pierThickness || 1.5
  const w = 9.6

  const lod = new THREE.LOD()
  const details = ['high', 'medium', 'low']
  const distances = [10, 30, 80]

  for (let i = 0; i < 3; i++) {
    const archGeom = createArchGeometry(span, rise, t, w, 60, details[i])
    const mat = new THREE.MeshStandardMaterial({
      color: 0xc0b280,
      roughness: 0.9,
      metalness: 0.05,
      vertexColors: false
    })
    const mesh = new THREE.Mesh(archGeom, mat)
    mesh.castShadow = true
    mesh.receiveShadow = true
    lod.addLevel(mesh, distances[i])
    archLOD.push(mesh)
  }
  lod.position.y = 0
  bridgeGroup.add(lod)

  const pierGeom = createPierGeometry(t, rise * 0.6, t, w)
  const pierMat = new THREE.MeshStandardMaterial({ color: 0xb0a070, roughness: 0.95 })
  const leftPier = new THREE.Mesh(pierGeom, pierMat)
  leftPier.position.set(-span / 2 + t / 2, rise * 0.3, 0)
  const rightPier = new THREE.Mesh(pierGeom.clone(), pierMat)
  rightPier.position.set(span / 2 - t / 2, rise * 0.3, 0)
  bridgeGroup.add(leftPier)
  bridgeGroup.add(rightPier)

  const railingMat = new THREE.MeshStandardMaterial({ color: 0x8b7355 })
  for (let side of [-1, 1]) {
    for (let i = 0; i < 20; i++) {
      const x = -span / 2 + 2 + i * (span - 4) / 19
      const y = 4 * rise * (0.25 - (x * x) / (span * span)) + 0.6
      const post = new THREE.Mesh(new THREE.BoxGeometry(0.12, 0.8, 0.12), railingMat)
      post.position.set(x, y, side * (w / 2 - 0.3))
      bridgeGroup.add(post)
    }
  }
}

async function loadFemResult() {
  try {
    const res = await axios.get(`/api/simulation/fem/${selectedBridge.value}`)
    const fem = res.data.data
    if (fem && fem.nodeData && archLOD.length) {
      const nodes = fem.nodeData
      minStress.value = Math.min(...nodes.map(n => n.stress || 0))
      maxStress.value = Math.max(...nodes.map(n => n.stress || 0))
      if (showHeatmap.value) {
        applyHeatmapToBridge(nodes)
      }
    }
  } catch (e) {
    console.warn('加载FEM结果失败:', e.message)
  }
}

function applyHeatmapToBlocks(nodeData) {
  if (!archLOD.length) return
  const span = bridgeData?.spanLength || 37
  const rise = span * (bridgeData?.riseSpanRatio || 0.2)

  archLOD.forEach(mesh => {
    applyHeatmapColors(mesh.geometry, nodeData, span, rise)
    if (mesh.material) {
      mesh.material.vertexColors = true
      mesh.material.needsUpdate = true
    }
  })
}
const applyHeatmapToBridge = applyHeatmapToBlocks

function toggleHeatmap() {
  showHeatmap.value = !showHeatmap.value
  if (showHeatmap.value) {
    loadFemResult()
  } else {
    archLOD.forEach(mesh => {
      if (mesh.material) {
        mesh.material.vertexColors = false
        mesh.material.color.set(0xc0b280)
        mesh.material.needsUpdate = true
      }
    })
  }
}

function toggleCracks() {
  showCracks.value = !showCracks.value
  cracksGroup.visible = showCracks.value
}

function updateLOD() {
  if (!archLOD[0] || !archLOD[0].parent?.parent) return
  const lod = bridgeGroup.children.find(c => c.isLOD)
  if (lod) {
    lod.currentLevel = lodLevel.value
    updateMemoryInfo()
  }
}

async function loadSensors() {
  try {
    const res = await axios.get(`/api/bridges/${selectedBridge.value}/sensors`)
    const sensors = res.data.data || []
    placeSensors(sensors)
  } catch (e) {
    console.warn('加载传感器失败:', e.message)
    const demoSensors = [
      { id: 1, name: '拱顶应变计', type: 'strain', position: { x: 0, y: 5, z: 0 } },
      { id: 2, name: '左拱脚位移计', type: 'displacement', position: { x: -15, y: 0.5, z: 0 } },
      { id: 3, name: '右拱脚位移计', type: 'displacement', position: { x: 15, y: 0.5, z: 0 } }
    ]
    placeSensors(demoSensors)
  }
}

function placeSensors(sensors) {
  while (sensorsGroup.children.length > 0) {
    const c = sensorsGroup.children[0]
    sensorsGroup.remove(c)
    c.geometry?.dispose()
    c.material?.dispose()
  }
  sensorObjects = []

  const colors = { strain: 0x2563eb, displacement: 0x059669, crack: 0xdc2626, temperature: 0xf59e0b, vibration: 0x7c3aed }
  const span = bridgeData?.spanLength || 37
  const rise = span * (bridgeData?.riseSpanRatio || 0.2)

  sensors.forEach(s => {
    const pos = s.position || {}
    let x = pos.x != null ? pos.x : (Math.random() - 0.5) * span * 0.8
    let y = pos.y != null ? pos.y : 4 * rise * (0.25 - (x * x) / (span * span)) + 0.3
    let z = pos.z != null ? pos.z : (Math.random() - 0.5) * 4

    const geom = new THREE.SphereGeometry(0.25, 16, 16)
    const mat = new THREE.MeshBasicMaterial({
      color: colors[s.type] || 0xffffff,
      transparent: true,
      opacity: 0.9
    })
    const sphere = new THREE.Mesh(geom, mat)
    sphere.position.set(x, y, z)
    sphere.userData.sensor = s
    sensorsGroup.add(sphere)
    sensorObjects.push(sphere)

    const labelCanvas = document.createElement('canvas')
    labelCanvas.width = 128
    labelCanvas.height = 32
    const ctx = labelCanvas.getContext('2d')
    ctx.fillStyle = 'rgba(255,255,255,0.9)'
    ctx.fillRect(0, 0, 128, 32)
    ctx.fillStyle = '#374151'
    ctx.font = 'bold 12px sans-serif'
    ctx.textAlign = 'center'
    ctx.fillText(s.name || `传感器${s.id}`, 64, 20)
    const texture = new THREE.CanvasTexture(labelCanvas)
    const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: texture }))
    sprite.scale.set(2, 0.5, 1)
    sprite.position.set(x, y + 0.8, z)
    sensorsGroup.add(sprite)
  })
}

async function loadCracks() {
  try {
    const res = await axios.get(`/api/bridges/${selectedBridge.value}/sensors`)
    const all = res.data.data || []
    const cracks = all.filter(s => s.type === 'crack')
    renderCracks(cracks)
  } catch (e) {
    renderCracks([
      { id: 1, name: '左拱腹裂缝', position: { x: -8, y: 3.2, z: -2 }, length: 1.5, depth: 0.05 },
      { id: 2, name: '右拱脚裂缝', position: { x: 12, y: 0.8, z: 1.5 }, length: 0.8, depth: 0.03 }
    ])
  }
}

function renderCracks(cracks) {
  while (cracksGroup.children.length > 0) {
    const c = cracksGroup.children[0]
    cracksGroup.remove(c)
    c.geometry?.dispose()
    c.material?.dispose()
  }

  const mat = new THREE.LineBasicMaterial({ color: 0xdc2626, linewidth: 2, transparent: true, opacity: 0.9 })

  cracks.forEach(ck => {
    const pos = ck.position || {}
    const x = pos.x != null ? pos.x : 0
    const y = pos.y != null ? pos.y : 2
    const z = pos.z != null ? pos.z : 0
    const len = ck.length || 1

    const points = []
    const steps = 12
    for (let i = 0; i <= steps; i++) {
      const t = i / steps
      const cx = x + (t - 0.5) * len
      const cy = y + Math.sin(t * Math.PI) * 0.15
      points.push(new THREE.Vector3(cx, cy, z))
    }
    const geom = new THREE.BufferGeometry().setFromPoints(points)
    const line = new THREE.Line(geom, mat)
    line.userData.sensor = ck
    cracksGroup.add(line)

    const glowMat = new THREE.LineBasicMaterial({ color: 0xff4444, linewidth: 4, transparent: true, opacity: 0.3 })
    const glowLine = new THREE.Line(geom, glowMat)
    cracksGroup.add(glowLine)
  })
}

async function loadTrendData(sensorId) {
  try {
    const res = await axios.get(`/api/data/sensors/${sensorId}/trend?days=365`)
    const data = res.data.data || []
    const values = data.map(d => d.value != null ? d.value : d.avgValue)
    latestValue.value = values.length ? values[values.length - 1] : 0
    drawTrendChart(data)
  } catch (e) {
    console.warn('加载趋势数据失败:', e.message)
    const demoData = generateDemoTrend(365)
    latestValue.value = demoData[demoData.length - 1].value
    drawTrendChart(demoData)
  }
}

function generateDemoTrend(days) {
  const data = []
  const now = new Date()
  let v = 50
  for (let i = days * 24; i >= 0; i -= 24) {
    const d = new Date(now.getTime() - i * 3600000)
    v += (Math.random() - 0.48) * 3
    data.push({ timestamp: d.toISOString(), value: v })
  }
  return data
}

function drawTrendChart(data) {
  nextTick(() => {
    if (!trendChart.value) return
    if (!chartInstance) {
      chartInstance = echarts.init(trendChart.value)
    }
    chartInstance.setOption({
      grid: { left: 40, right: 10, top: 10, bottom: 25 },
      xAxis: { type: 'time', axisLabel: { fontSize: 9 } },
      yAxis: { type: 'value', scale: true, axisLabel: { fontSize: 9 } },
      tooltip: { trigger: 'axis' },
      series: [{
        type: 'line',
        smooth: true,
        showSymbol: false,
        lineStyle: { width: 1.5, color: '#2563eb' },
        areaStyle: { color: 'rgba(37,99,235,0.1)' },
        data: data.map(d => [d.timestamp, d.value != null ? d.value : d.avgValue])
      }]
    })
  })
}

function updateMemoryInfo() {
  const mem = renderer?.info?.memory
  if (mem) {
    memoryInfo.value = `${mem.geometries} geom / ${mem.textures} tex`
  } else {
    memoryInfo.value = `LOD级别 ${lodLevel.value}`
  }
}

function onResize() {
  if (!container.value || !camera || !renderer) return
  const w = container.value.clientWidth
  const h = container.value.clientHeight
  camera.aspect = w / h
  camera.updateProjectionMatrix()
  renderer.setSize(w, h)
}

function animate() {
  animationId = requestAnimationFrame(animate)
  controls?.update()
  renderer?.render(scene, camera)
}

watch(lodLevel, updateLOD)
watch(showHeatmap, toggleHeatmap)
watch(showCracks, toggleCracks)
</script>
