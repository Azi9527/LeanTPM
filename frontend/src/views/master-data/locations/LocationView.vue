<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  masterDataApi,
  type LocationRow,
  type OrganizationRow,
  type ReferenceUser,
} from '@/api/masterData'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

interface LocationTreeNode extends LocationRow {
  children: LocationTreeNode[]
}

interface OrganizationTreeNode extends OrganizationRow {
  children: OrganizationTreeNode[]
}

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const rows = ref<LocationRow[]>([])
const organizations = ref<OrganizationRow[]>([])
const users = ref<ReferenceUser[]>([])
const selectedOrganizationId = ref<number>()
const keyword = ref('')
const dialogVisible = ref(false)
const editing = ref<LocationRow | null>(null)

const form = reactive({
  parentId: 0,
  locationCode: '',
  locationName: '',
  locationType: 'AREA' as LocationRow['locationType'],
  organizationId: undefined as number | undefined,
  managerUserId: undefined as number | undefined,
  sortOrder: 0,
  enabled: true,
  description: '',
})

const typeLabels: Record<LocationRow['locationType'], string> = {
  AREA: '区域',
  BUILDING: '建筑',
  FLOOR: '楼层',
  ZONE: '功能区',
  SPOT: '点位/机位',
}

const nextType: Partial<Record<LocationRow['locationType'], LocationRow['locationType']>> = {
  AREA: 'BUILDING',
  BUILDING: 'FLOOR',
  FLOOR: 'ZONE',
  ZONE: 'SPOT',
}

const organizationTree = computed(() => buildOrganizationTree(organizations.value))
const selectedOrganization = computed(() => organizations.value.find(
  (row) => row.id === selectedOrganizationId.value,
))
const selectedRows = computed(() => rows.value.filter(
  (row) => row.organizationId === selectedOrganizationId.value,
))
const formRows = computed(() => rows.value.filter(
  (row) => row.organizationId === form.organizationId,
))
const treeRows = computed(() => buildLocationTree(
  filterWithAncestors(selectedRows.value, keyword.value),
))
const parentTree = computed(() => buildLocationTree(
  formRows.value.filter((row) => row.id !== editing.value?.id),
))
const parentSelection = computed<number | undefined>({
  get: () => form.parentId || undefined,
  set: (value) => { form.parentId = value || 0 },
})

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [locationRows, organizationRows, userRows] = await Promise.all([
      masterDataApi.locations(),
      masterDataApi.organizations(),
      masterDataApi.referenceUsers(),
    ])
    rows.value = locationRows
    organizations.value = organizationRows
    users.value = userRows
    if (!selectedOrganizationId.value
      || !organizations.value.some((row) => row.id === selectedOrganizationId.value)) {
      selectedOrganizationId.value = locationRows.find((row) => row.status === 1)?.organizationId
        || organizationRows.find((row) => row.status === 1)?.id
    }
  } catch (error) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

function buildLocationTree(source: LocationRow[]): LocationTreeNode[] {
  const nodes = new Map<number, LocationTreeNode>()
  source.forEach((row) => nodes.set(row.id, { ...row, children: [] }))
  const roots: LocationTreeNode[] = []
  nodes.forEach((node) => {
    const parent = nodes.get(node.parentId)
    if (parent) parent.children.push(node)
    else roots.push(node)
  })
  return roots
}

function buildOrganizationTree(source: OrganizationRow[]): OrganizationTreeNode[] {
  const nodes = new Map<number, OrganizationTreeNode>()
  source.forEach((row) => nodes.set(row.id, { ...row, children: [] }))
  const roots: OrganizationTreeNode[] = []
  nodes.forEach((node) => {
    const parent = nodes.get(node.parentId)
    if (parent) parent.children.push(node)
    else roots.push(node)
  })
  return roots
}

function filterWithAncestors(source: LocationRow[], value: string): LocationRow[] {
  const needle = value.trim().toLowerCase()
  if (!needle) return source
  const byId = new Map(source.map((row) => [row.id, row]))
  const ids = new Set<number>()
  source.forEach((row) => {
    if (`${row.locationCode} ${row.locationName}`.toLowerCase().includes(needle)) {
      let cursor: LocationRow | undefined = row
      while (cursor && !ids.has(cursor.id)) {
        ids.add(cursor.id)
        cursor = byId.get(cursor.parentId)
      }
    }
  })
  return source.filter((row) => ids.has(row.id))
}

function selectOrganization(row: OrganizationRow) {
  selectedOrganizationId.value = row.id
  keyword.value = ''
}

function locationCount(organizationId: number) {
  return rows.value.filter((row) => row.organizationId === organizationId).length
}

function handleOrganizationChange(organizationId?: number) {
  if (!organizationId) {
    form.parentId = 0
    return
  }
  const parent = rows.value.find((row) => row.id === form.parentId)
  if (parent && parent.organizationId !== organizationId) form.parentId = 0
}

function openDialog(row?: LocationRow, asChild = false) {
  if (!selectedOrganization.value) {
    ElMessage.warning('请先从左侧选择所属组织')
    return
  }
  editing.value = asChild ? null : row || null
  Object.assign(form, editing.value
    ? {
        parentId: editing.value.parentId,
        locationCode: editing.value.locationCode,
        locationName: editing.value.locationName,
        locationType: editing.value.locationType,
        organizationId: editing.value.organizationId,
        managerUserId: editing.value.managerUserId,
        sortOrder: editing.value.sortOrder,
        enabled: editing.value.status === 1,
        description: editing.value.description || '',
      }
    : {
        parentId: row?.id || 0,
        locationCode: '',
        locationName: '',
        locationType: row ? nextType[row.locationType] || 'SPOT' : 'AREA',
        organizationId: selectedOrganization.value.id,
        managerUserId: undefined,
        sortOrder: 0,
        enabled: true,
        description: '',
      })
  dialogVisible.value = true
}

async function save() {
  if (!form.locationCode.trim() || !form.locationName.trim() || !form.organizationId) {
    ElMessage.warning('请完整填写物理位置编码和名称')
    return
  }
  saving.value = true
  try {
    const payload = {
      ...form,
      locationCode: form.locationCode.trim().toUpperCase(),
      locationName: form.locationName.trim(),
      version: editing.value?.version,
    }
    if (editing.value) await masterDataApi.updateLocation(editing.value.id, payload)
    else await masterDataApi.createLocation(payload)
    selectedOrganizationId.value = form.organizationId
    dialogVisible.value = false
    ElMessage.success('物理位置已保存')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function remove(row: LocationRow) {
  await ElMessageBox.confirm(`确认删除物理位置“${row.locationName}”吗？`, '删除物理位置', {
    type: 'warning',
  })
  try {
    await masterDataApi.deleteLocation(row.id, row.version)
    ElMessage.success('物理位置已删除')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div>
        <h1>位置管理</h1>
        <p>位置挂靠现有组织，只维护区域、建筑、楼层、功能区和机位，不重复定义组织层级。</p>
      </div>
      <el-button
        v-if="auth.can('master-data:location:manage')"
        type="primary"
        :disabled="!selectedOrganization"
        @click="openDialog()"
      >
        新增物理位置
      </el-button>
    </header>

    <el-alert
      title="组织回答‘归谁管理’，物理位置回答‘设备具体在哪里’；设备台账允许暂不指定物理位置。"
      type="info"
      show-icon
      :closable="false"
    />

    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false">
      <template #default><el-button link type="primary" @click="load">重新加载</el-button></template>
    </el-alert>

    <section class="location-workspace" v-loading="loading">
      <aside class="surface-card organization-panel">
        <div class="panel-heading">
          <div>
            <strong>组织关系</strong>
            <small>选择位置所属组织</small>
          </div>
          <el-button link type="primary" @click="load">刷新</el-button>
        </div>
        <el-tree
          :data="organizationTree"
          node-key="id"
          default-expand-all
          highlight-current
          :current-node-key="selectedOrganizationId"
          :expand-on-click-node="false"
          :props="{ label: 'organizationName', children: 'children' }"
          @node-click="selectOrganization"
        >
          <template #default="{ data }">
            <span class="organization-node">
              <span>
                <b>{{ data.organizationName }}</b>
                <small>{{ data.organizationCode }}</small>
              </span>
              <el-tag size="small" effect="plain">{{ locationCount(data.id) }}</el-tag>
            </span>
          </template>
        </el-tree>
      </aside>

      <div class="surface-card location-panel">
        <div class="panel-heading location-heading">
          <div>
            <strong>{{ selectedOrganization?.organizationName || '请选择组织' }}</strong>
            <small>该组织下的物理位置</small>
          </div>
          <el-input
            v-model="keyword"
            clearable
            placeholder="搜索位置编码或名称"
            style="width: min(300px, 100%)"
          />
        </div>

        <el-table
          :data="treeRows"
          row-key="id"
          default-expand-all
          :tree-props="{ children: 'children' }"
        >
          <el-table-column prop="locationName" label="物理位置" min-width="220" />
          <el-table-column prop="locationCode" label="位置编码" min-width="145">
            <template #default="{ row }"><span class="mono">{{ row.locationCode }}</span></template>
          </el-table-column>
          <el-table-column label="类型" width="105">
            <template #default="{ row }">{{ typeLabels[row.locationType as LocationRow['locationType']] }}</template>
          </el-table-column>
          <el-table-column prop="managerName" label="负责人" width="120">
            <template #default="{ row }">{{ row.managerName || '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'info'">
                {{ row.status === 1 ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            v-if="auth.can('master-data:location:manage')"
            label="操作"
            width="205"
            fixed="right"
          >
            <template #default="{ row }">
              <el-button
                v-if="row.locationType !== 'SPOT'"
                link
                type="primary"
                @click="openDialog(row, true)"
              >新增下级</el-button>
              <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
              <el-button
                v-if="auth.can('master-data:location:delete')"
                link
                type="danger"
                @click="remove(row)"
              >删除</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty :description="selectedOrganization ? '该组织暂未维护物理位置' : '请从左侧选择组织'" />
          </template>
        </el-table>
      </div>
    </section>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑物理位置' : '新增物理位置'"
      width="min(720px, 94vw)"
    >
      <el-form label-position="top" class="edit-form">
        <el-form-item label="所属组织">
          <el-tree-select
            v-model="form.organizationId"
            :data="organizationTree"
            node-key="id"
            check-strictly
            :render-after-expand="false"
            :props="{
              label: 'organizationName',
              children: 'children',
              disabled: (data: OrganizationRow) => data.status !== 1,
            }"
            placeholder="请选择所属组织"
            @change="handleOrganizationChange"
          />
        </el-form-item>
        <el-form-item label="上级物理位置">
          <el-tree-select
            v-model="parentSelection"
            :data="parentTree"
            node-key="id"
            check-strictly
            :render-after-expand="false"
            :props="{ label: 'locationName', children: 'children' }"
            clearable
            placeholder="不选择则为该组织的根物理位置"
          />
        </el-form-item>
        <el-form-item label="位置类型">
          <el-select v-model="form.locationType">
            <el-option v-for="(label, value) in typeLabels" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="位置编码">
          <el-input v-model="form.locationCode" :disabled="Boolean(editing)" placeholder="例如 AREA-A01" />
        </el-form-item>
        <el-form-item label="位置名称"><el-input v-model="form.locationName" placeholder="例如 东侧装配区" /></el-form-item>
        <el-form-item label="负责人">
          <el-select v-model="form.managerUserId" clearable filterable>
            <el-option
              v-for="user in users"
              :key="user.id"
              :label="`${user.realName}（${user.username}）`"
              :value="user.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sortOrder" :min="0" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
        <el-form-item label="说明" class="full-row">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.location-workspace {
  display: grid;
  grid-template-columns: minmax(260px, 320px) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}
.organization-panel,
.location-panel { min-height: 520px; }
.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 0 14px;
  border-bottom: 1px solid var(--tpm-border);
  margin-bottom: 12px;
}
.panel-heading > div { display: flex; flex-direction: column; gap: 3px; }
.panel-heading strong { color: var(--tpm-text); font-size: 15px; }
.panel-heading small { color: var(--tpm-text-secondary); }
.location-heading { flex-wrap: wrap; }
.organization-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  padding-right: 8px;
}
.organization-node > span { display: flex; flex-direction: column; min-width: 0; }
.organization-node b { overflow: hidden; text-overflow: ellipsis; }
.organization-node small { color: var(--tpm-text-secondary); font-size: 11px; }
:deep(.el-tree-node__content) { height: 48px; border-radius: 8px; }
:deep(.el-tree-node__content:hover) { background: rgba(var(--tpm-primary-rgb), 0.06); }
:deep(.el-tree--highlight-current .el-tree-node.is-current > .el-tree-node__content) {
  background: var(--tpm-primary-soft);
  color: var(--tpm-primary);
}
.edit-form { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.full-row { grid-column: 1 / -1; }
@media (max-width: 900px) {
  .location-workspace { grid-template-columns: 1fr; }
  .organization-panel { min-height: auto; }
}
@media (max-width: 640px) {
  .edit-form { grid-template-columns: 1fr; }
  .full-row { grid-column: auto; }
}
</style>
