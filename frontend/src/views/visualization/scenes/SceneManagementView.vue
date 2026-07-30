<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import { equipmentApi, type EquipmentRow } from '@/api/equipment'
import { masterDataApi, type OrganizationRow } from '@/api/masterData'
import {
  visualizationApi,
  type ModelResource,
  type SaveModelRequest,
  type SaveNodeRequest,
  type SaveSceneRequest,
  type SceneDetail,
  type SceneNode,
  type SceneSummary,
  type StatusColor,
} from '@/api/visualization'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const activeTab = ref('scenes')
const scenes = ref<SceneSummary[]>([])
const models = ref<ModelResource[]>([])
const colors = ref<StatusColor[]>([])
const organizations = ref<OrganizationRow[]>([])
const equipment = ref<EquipmentRow[]>([])
const selectedSceneId = ref<number>()
const selectedDetail = ref<SceneDetail>()
const modelDialog = ref(false)
const sceneDialog = ref(false)
const nodeDialog = ref(false)
const editingModel = ref<ModelResource>()
const editingScene = ref<SceneDetail>()
const editingNode = ref<SceneNode>()
const modelFile = ref<File>()

const modelForm = reactive<SaveModelRequest>({
  resourceCode: '', resourceName: '', resourceLevel: 'EQUIPMENT',
  modelFormat: 'PRIMITIVE', primitiveType: 'BOX', fallbackColor: '#38BDF8', status: 1,
})
const sceneForm = reactive<SaveSceneRequest>({
  parentSceneId: 0, sceneCode: '', sceneName: '', sceneLevel: 'LINE',
  organizationId: 0, backgroundColor: '#07111F', gridColor: '#1E3A5F',
  cameraX: 18, cameraY: 14, cameraZ: 22, targetX: 0, targetY: 0, targetZ: 0,
  autoRotateFlag: false, sortOrder: 10, status: 1,
})
const nodeForm = reactive<SaveNodeRequest>({
  nodeCode: '', displayName: '', nodeType: 'EQUIPMENT',
  positionX: 0, positionY: 0, positionZ: 0,
  rotationX: 0, rotationY: 0, rotationZ: 0,
  scaleX: 1, scaleY: 1, scaleZ: 1,
  labelVisibleFlag: true, visibleFlag: true, sortOrder: 10,
})
const sceneOptions = computed(() => scenes.value.filter((item) => item.status === 1))

onMounted(loadAll)

async function loadAll() {
  loading.value = true
  try {
    const [sceneRows, modelRows, statusRows, orgRows, equipmentPage] = await Promise.all([
      visualizationApi.scenes(),
      visualizationApi.models(),
      visualizationApi.statusColors(),
      masterDataApi.organizations(),
      equipmentApi.page({ page: 1, pageSize: 200, status: 1 }),
    ])
    scenes.value = sceneRows
    models.value = modelRows
    colors.value = statusRows
    organizations.value = orgRows.filter((item) => item.status === 1)
    equipment.value = equipmentPage.records
    if (!selectedSceneId.value && sceneRows.length) selectedSceneId.value = sceneRows[0].id
    if (selectedSceneId.value) await selectScene(selectedSceneId.value)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function selectScene(id: number) {
  selectedSceneId.value = id
  selectedDetail.value = await visualizationApi.scene(id)
}

function selectSceneRow(row: SceneSummary) {
  selectScene(row.id)
}

function openModel(row?: ModelResource) {
  editingModel.value = row
  modelFile.value = undefined
  Object.assign(modelForm, row ? {
    resourceCode: row.resourceCode, resourceName: row.resourceName,
    resourceLevel: row.resourceLevel, attachmentId: row.attachmentId,
    modelFormat: row.modelFormat, primitiveType: row.primitiveType,
    fallbackColor: row.fallbackColor, thumbnailAttachmentId: row.thumbnailAttachmentId,
    description: row.description, status: row.status, version: row.version,
  } : {
    resourceCode: '', resourceName: '', resourceLevel: 'EQUIPMENT',
    attachmentId: undefined, modelFormat: 'PRIMITIVE', primitiveType: 'BOX',
    fallbackColor: '#38BDF8', thumbnailAttachmentId: undefined,
    description: '', status: 1, version: undefined,
  })
  modelDialog.value = true
}

function chooseModelFile(upload: UploadFile) {
  modelFile.value = upload.raw
}

async function saveModel() {
  if (!modelForm.resourceCode.trim() || !modelForm.resourceName.trim()) {
    ElMessage.warning('请填写模型编码和名称')
    return
  }
  saving.value = true
  try {
    if (editingModel.value) {
      await visualizationApi.updateModel(editingModel.value.id, modelForm)
    } else if (modelForm.modelFormat !== 'PRIMITIVE' && modelFile.value) {
      await visualizationApi.uploadModel(modelFile.value, modelForm)
    } else {
      await visualizationApi.createModel(modelForm)
    }
    modelDialog.value = false
    ElMessage.success('模型资源已保存')
    await loadAll()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function removeModel(row: ModelResource) {
  await ElMessageBox.confirm(`确认删除模型“${row.resourceName}”？`, '删除确认', { type: 'warning' })
  try {
    await visualizationApi.deleteModel(row.id, row.version)
    ElMessage.success('模型资源已删除')
    await loadAll()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function openScene(row?: SceneSummary) {
  let detail: SceneDetail | undefined
  if (row) detail = await visualizationApi.scene(row.id)
  editingScene.value = detail
  Object.assign(sceneForm, detail ? {
    ...detail.scene,
  } : {
    parentSceneId: 0, sceneCode: '', sceneName: '', sceneLevel: 'LINE',
    organizationId: 0, modelResourceId: undefined,
    backgroundColor: '#07111F', gridColor: '#1E3A5F',
    cameraX: 18, cameraY: 14, cameraZ: 22,
    targetX: 0, targetY: 0, targetZ: 0,
    autoRotateFlag: false, sortOrder: 10, status: 1, description: '', version: undefined,
  })
  sceneDialog.value = true
}

async function saveScene() {
  if (!sceneForm.sceneCode.trim() || !sceneForm.sceneName.trim() || !sceneForm.organizationId) {
    ElMessage.warning('请完整填写场景编码、名称和组织')
    return
  }
  saving.value = true
  try {
    if (editingScene.value) {
      await visualizationApi.updateScene(editingScene.value.scene.id, sceneForm)
    } else {
      await visualizationApi.createScene(sceneForm)
    }
    sceneDialog.value = false
    ElMessage.success('场景已保存')
    await loadAll()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function removeScene(row: SceneSummary) {
  await ElMessageBox.confirm(`确认删除场景“${row.sceneName}”？`, '删除确认', { type: 'warning' })
  try {
    await visualizationApi.deleteScene(row.id, row.version)
    selectedSceneId.value = undefined
    ElMessage.success('场景已删除')
    await loadAll()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

function openNode(row?: SceneNode) {
  editingNode.value = row
  Object.assign(nodeForm, row ? {
    ...row,
  } : {
    nodeCode: '', displayName: '', nodeType: 'EQUIPMENT',
    organizationId: undefined, equipmentId: undefined, targetSceneId: undefined,
    modelResourceId: undefined, positionX: 0, positionY: 0, positionZ: 0,
    rotationX: 0, rotationY: 0, rotationZ: 0,
    scaleX: 1, scaleY: 1, scaleZ: 1,
    labelVisibleFlag: true, visibleFlag: true, sortOrder: 10,
    description: '', version: undefined,
  })
  nodeDialog.value = true
}

async function saveNode() {
  if (!selectedSceneId.value || !nodeForm.nodeCode.trim() || !nodeForm.displayName.trim()) return
  saving.value = true
  try {
    if (editingNode.value) await visualizationApi.updateNode(editingNode.value.id, nodeForm)
    else await visualizationApi.createNode(selectedSceneId.value, nodeForm)
    nodeDialog.value = false
    ElMessage.success('场景节点已保存')
    await selectScene(selectedSceneId.value)
    scenes.value = await visualizationApi.scenes()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function removeNode(row: SceneNode) {
  await ElMessageBox.confirm(`确认删除节点“${row.displayName}”？`, '删除确认', { type: 'warning' })
  try {
    await visualizationApi.deleteNode(row.id, row.version)
    if (selectedSceneId.value) await selectScene(selectedSceneId.value)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function saveColor(row: StatusColor) {
  try {
    await visualizationApi.updateStatusColor(row.statusCode, {
      statusName: row.statusName,
      displayColor: row.displayColor,
      emissiveColor: row.emissiveColor,
      pulseFlag: row.pulseFlag,
      sortOrder: row.sortOrder,
      status: row.status,
      description: row.description,
      version: row.version,
    })
    ElMessage.success(`${row.statusName}颜色已更新`)
    colors.value = await visualizationApi.statusColors()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}
</script>

<template>
  <div class="page-shell" v-loading="loading">
    <header class="page-header">
      <div>
        <h1>三维场景配置</h1>
        <p>统一维护模型资源、层级场景、业务节点坐标和设备状态颜色。</p>
      </div>
      <el-button type="primary" @click="loadAll">刷新配置</el-button>
    </header>

    <el-tabs v-model="activeTab" class="surface-card config-tabs">
      <el-tab-pane label="场景层级" name="scenes">
        <div class="toolbar">
          <el-button v-if="auth.can('visualization:scene:manage')" type="primary" @click="openScene()">新建场景</el-button>
        </div>
        <el-table :data="scenes" row-key="id" stripe @row-click="selectSceneRow">
          <el-table-column prop="sceneCode" label="场景编码" width="180" />
          <el-table-column prop="sceneName" label="场景名称" min-width="150" />
          <el-table-column prop="sceneLevel" label="层级" width="120" />
          <el-table-column prop="organizationName" label="绑定组织" min-width="150" />
          <el-table-column prop="nodeCount" label="节点数" width="90" />
          <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.status ? 'success' : 'info'">{{ row.status ? '启用' : '停用' }}</el-tag></template></el-table-column>
          <el-table-column v-if="auth.can('visualization:scene:manage')" label="操作" width="150" fixed="right">
            <template #default="{ row }"><el-button link type="primary" @click.stop="openScene(row)">编辑</el-button><el-button link type="danger" @click.stop="removeScene(row)">删除</el-button></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="场景节点" name="nodes">
        <div class="toolbar">
          <el-select v-model="selectedSceneId" placeholder="选择场景" style="width: 260px" @change="selectScene">
            <el-option v-for="item in sceneOptions" :key="item.id" :label="item.sceneName" :value="item.id" />
          </el-select>
          <el-button v-if="auth.can('visualization:scene:manage')" type="primary" :disabled="!selectedSceneId" @click="openNode()">新建节点</el-button>
        </div>
        <el-table :data="selectedDetail?.nodes ?? []" stripe>
          <el-table-column prop="nodeCode" label="节点编码" width="160" />
          <el-table-column prop="displayName" label="显示名称" min-width="150" />
          <el-table-column prop="nodeType" label="类型" width="120" />
          <el-table-column label="坐标" min-width="170"><template #default="{ row }">{{ row.positionX }}, {{ row.positionY }}, {{ row.positionZ }}</template></el-table-column>
          <el-table-column prop="primitiveType" label="模型/形状" width="130" />
          <el-table-column prop="statusName" label="实时状态" width="100" />
          <el-table-column v-if="auth.can('visualization:scene:manage')" label="操作" width="150" fixed="right">
            <template #default="{ row }"><el-button link type="primary" @click="openNode(row)">编辑</el-button><el-button link type="danger" @click="removeNode(row)">删除</el-button></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="模型资源" name="models">
        <div class="toolbar">
          <el-button v-if="auth.can('visualization:model:manage')" type="primary" @click="openModel()">新增 / 上传模型</el-button>
          <span class="hint">支持 GLB、GLTF；加载失败时自动使用程序化模型和后备颜色。</span>
        </div>
        <el-table :data="models" stripe>
          <el-table-column prop="resourceCode" label="资源编码" width="180" />
          <el-table-column prop="resourceName" label="资源名称" min-width="160" />
          <el-table-column prop="resourceLevel" label="层级" width="120" />
          <el-table-column prop="modelFormat" label="格式" width="90" />
          <el-table-column prop="primitiveType" label="后备形状" width="120" />
          <el-table-column label="后备颜色" width="110"><template #default="{ row }"><i class="color-dot" :style="{ backgroundColor: row.fallbackColor }" />{{ row.fallbackColor }}</template></el-table-column>
          <el-table-column v-if="auth.can('visualization:model:manage')" label="操作" width="150" fixed="right">
            <template #default="{ row }"><el-button link type="primary" @click="openModel(row)">编辑</el-button><el-button link type="danger" @click="removeModel(row)">删除</el-button></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="状态颜色" name="colors">
        <el-table :data="colors" stripe>
          <el-table-column prop="statusCode" label="状态编码" width="160" />
          <el-table-column label="状态名称" width="160"><template #default="{ row }"><el-input v-model="row.statusName" /></template></el-table-column>
          <el-table-column label="显示颜色" width="150"><template #default="{ row }"><el-color-picker v-model="row.displayColor" /><span>{{ row.displayColor }}</span></template></el-table-column>
          <el-table-column label="发光颜色" width="150"><template #default="{ row }"><el-color-picker v-model="row.emissiveColor" /><span>{{ row.emissiveColor }}</span></template></el-table-column>
          <el-table-column label="脉冲" width="90"><template #default="{ row }"><el-switch v-model="row.pulseFlag" /></template></el-table-column>
          <el-table-column v-if="auth.can('visualization:status-color:manage')" label="操作" width="100"><template #default="{ row }"><el-button type="primary" link @click="saveColor(row)">保存</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="modelDialog" :title="editingModel ? '编辑模型资源' : '新增 / 上传模型'" width="640px">
      <el-form label-width="110px">
        <el-row :gutter="14">
          <el-col :span="12"><el-form-item label="资源编码"><el-input v-model="modelForm.resourceCode" :disabled="Boolean(editingModel)" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="资源名称"><el-input v-model="modelForm.resourceName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="应用层级"><el-select v-model="modelForm.resourceLevel"><el-option v-for="value in ['FACTORY','PLANT_AREA','WORKSHOP','LINE','EQUIPMENT']" :key="value" :label="value" :value="value" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="模型格式"><el-select v-model="modelForm.modelFormat"><el-option label="程序化模型" value="PRIMITIVE" /><el-option label="GLB" value="GLB" /><el-option label="GLTF" value="GLTF" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="后备形状"><el-select v-model="modelForm.primitiveType"><el-option v-for="value in ['FACTORY','WORKSHOP','LINE','CNC','ROBOT','PRESS','PUMP','BOX']" :key="value" :label="value" :value="value" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="后备颜色"><el-color-picker v-model="modelForm.fallbackColor" /></el-form-item></el-col>
        </el-row>
        <el-form-item v-if="!editingModel && modelForm.modelFormat !== 'PRIMITIVE'" label="模型文件">
          <el-upload :auto-upload="false" :limit="1" accept=".glb,.gltf" :on-change="chooseModelFile"><el-button>选择 GLB / GLTF</el-button></el-upload>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="modelForm.description" type="textarea" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="modelDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveModel">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="sceneDialog" :title="editingScene ? '编辑场景' : '新建场景'" width="760px">
      <el-form label-width="100px">
        <el-row :gutter="14">
          <el-col :span="12"><el-form-item label="场景编码"><el-input v-model="sceneForm.sceneCode" :disabled="Boolean(editingScene)" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="场景名称"><el-input v-model="sceneForm.sceneName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="父场景"><el-select v-model="sceneForm.parentSceneId"><el-option label="无（根场景）" :value="0" /><el-option v-for="item in scenes" :key="item.id" :label="item.sceneName" :value="item.id" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="场景层级"><el-select v-model="sceneForm.sceneLevel"><el-option v-for="value in ['ENTERPRISE','FACTORY','PLANT_AREA','WORKSHOP','LINE']" :key="value" :label="value" :value="value" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="绑定组织"><el-select v-model="sceneForm.organizationId" filterable><el-option v-for="item in organizations" :key="item.id" :label="item.organizationName" :value="item.id" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="场景模型"><el-select v-model="sceneForm.modelResourceId" clearable><el-option v-for="item in models" :key="item.id" :label="item.resourceName" :value="item.id" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="背景 / 网格"><el-color-picker v-model="sceneForm.backgroundColor" /><el-color-picker v-model="sceneForm.gridColor" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="自动旋转"><el-switch v-model="sceneForm.autoRotateFlag" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="相机位置"><el-input-number v-model="sceneForm.cameraX" /><el-input-number v-model="sceneForm.cameraY" /><el-input-number v-model="sceneForm.cameraZ" /></el-form-item>
        <el-form-item label="观察目标"><el-input-number v-model="sceneForm.targetX" /><el-input-number v-model="sceneForm.targetY" /><el-input-number v-model="sceneForm.targetZ" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="sceneDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveScene">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="nodeDialog" :title="editingNode ? '编辑场景节点' : '新建场景节点'" width="780px">
      <el-form label-width="100px">
        <el-row :gutter="14">
          <el-col :span="12"><el-form-item label="节点编码"><el-input v-model="nodeForm.nodeCode" :disabled="Boolean(editingNode)" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="显示名称"><el-input v-model="nodeForm.displayName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="节点类型"><el-select v-model="nodeForm.nodeType"><el-option label="组织" value="ORGANIZATION" /><el-option label="设备" value="EQUIPMENT" /><el-option label="装饰" value="DECORATION" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="模型资源"><el-select v-model="nodeForm.modelResourceId" clearable><el-option v-for="item in models" :key="item.id" :label="item.resourceName" :value="item.id" /></el-select></el-form-item></el-col>
          <el-col v-if="nodeForm.nodeType === 'ORGANIZATION'" :span="12"><el-form-item label="绑定组织"><el-select v-model="nodeForm.organizationId" filterable><el-option v-for="item in organizations" :key="item.id" :label="item.organizationName" :value="item.id" /></el-select></el-form-item></el-col>
          <el-col v-if="nodeForm.nodeType === 'EQUIPMENT'" :span="12"><el-form-item label="绑定设备"><el-select v-model="nodeForm.equipmentId" filterable><el-option v-for="item in equipment" :key="item.id" :label="`${item.equipmentCode} · ${item.equipmentName}`" :value="item.id" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="下钻场景"><el-select v-model="nodeForm.targetSceneId" clearable><el-option v-for="item in scenes" :key="item.id" :label="item.sceneName" :value="item.id" /></el-select></el-form-item></el-col>
        </el-row>
        <el-form-item label="位置 XYZ"><el-input-number v-model="nodeForm.positionX" /><el-input-number v-model="nodeForm.positionY" /><el-input-number v-model="nodeForm.positionZ" /></el-form-item>
        <el-form-item label="旋转 XYZ"><el-input-number v-model="nodeForm.rotationX" :step="0.1" /><el-input-number v-model="nodeForm.rotationY" :step="0.1" /><el-input-number v-model="nodeForm.rotationZ" :step="0.1" /></el-form-item>
        <el-form-item label="缩放 XYZ"><el-input-number v-model="nodeForm.scaleX" :min="0.01" /><el-input-number v-model="nodeForm.scaleY" :min="0.01" /><el-input-number v-model="nodeForm.scaleZ" :min="0.01" /></el-form-item>
        <el-form-item label="显示"><el-checkbox v-model="nodeForm.visibleFlag">节点可见</el-checkbox><el-checkbox v-model="nodeForm.labelVisibleFlag">显示名称</el-checkbox></el-form-item>
      </el-form>
      <template #footer><el-button @click="nodeDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveNode">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.config-tabs { padding: 8px 18px 18px; }
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; }
.color-dot { display: inline-block; width: 13px; height: 13px; margin-right: 7px; border-radius: 50%; vertical-align: -2px; }
:deep(.el-form .el-select) { width: 100%; }
:deep(.el-form-item .el-input-number) { width: 31%; margin-right: 2%; }
</style>
