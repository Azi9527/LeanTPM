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
const keyword = ref('')
const dialogVisible = ref(false)
const editing = ref<LocationRow | null>(null)
const form = reactive({
  parentId: 0,
  locationCode: '',
  locationName: '',
  locationType: 'ENTERPRISE' as LocationRow['locationType'],
  organizationId: undefined as number | undefined,
  managerUserId: undefined as number | undefined,
  sortOrder: 0,
  enabled: true,
  description: '',
})

const typeLabels: Record<LocationRow['locationType'], string> = {
  ENTERPRISE: '企业园区',
  FACTORY: '工厂',
  PLANT_AREA: '厂区',
  WORKSHOP: '车间',
  LINE: '生产线',
  WORKSTATION: '工位',
}

const nextType: Partial<Record<LocationRow['locationType'], LocationRow['locationType']>> = {
  ENTERPRISE: 'FACTORY',
  FACTORY: 'PLANT_AREA',
  PLANT_AREA: 'WORKSHOP',
  WORKSHOP: 'LINE',
  LINE: 'WORKSTATION',
}

const treeRows = computed(() => buildLocationTree(filterWithAncestors(rows.value, keyword.value)))
const parentTree = computed(() => buildLocationTree(
  rows.value.filter((row) => row.id !== editing.value?.id),
))
const organizationTree = computed(() => buildOrganizationTree(organizations.value))

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
    if (`${row.locationCode} ${row.locationName} ${row.organizationName}`
      .toLowerCase().includes(needle)) {
      let cursor: LocationRow | undefined = row
      while (cursor && !ids.has(cursor.id)) {
        ids.add(cursor.id)
        cursor = byId.get(cursor.parentId)
      }
    }
  })
  return source.filter((row) => ids.has(row.id))
}

function openDialog(row?: LocationRow, asChild = false) {
  editing.value = asChild ? null : row || null
  const firstOrganization = organizations.value.find((item) => item.status === 1)
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
        locationType: row ? nextType[row.locationType] || 'WORKSTATION' : 'ENTERPRISE',
        organizationId: row?.organizationId || firstOrganization?.id,
        managerUserId: undefined,
        sortOrder: 0,
        enabled: true,
        description: '',
      })
  dialogVisible.value = true
}

async function save() {
  if (!form.locationCode.trim() || !form.locationName.trim() || !form.organizationId) {
    ElMessage.warning('请完整填写位置编码、名称和所属组织')
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
    dialogVisible.value = false
    ElMessage.success('位置已保存')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function remove(row: LocationRow) {
  await ElMessageBox.confirm(`确认删除位置“${row.locationName}”吗？`, '删除位置', {
    type: 'warning',
  })
  try {
    await masterDataApi.deleteLocation(row.id, row.version)
    ElMessage.success('位置已删除')
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
        <p>独立维护企业、工厂、厂区、车间、生产线和工位物理树。</p>
      </div>
      <el-button
        v-if="auth.can('master-data:location:manage')"
        type="primary"
        @click="openDialog()"
      >
        新增根位置
      </el-button>
    </header>

    <section class="surface-card query-bar">
      <el-input
        v-model="keyword"
        clearable
        placeholder="位置编码、名称或所属组织"
        style="width: min(360px, 100%)"
      />
      <el-button type="primary" plain @click="load">刷新</el-button>
    </section>

    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false">
      <template #default><el-button link type="primary" @click="load">重新加载</el-button></template>
    </el-alert>

    <section class="surface-card table-card" v-loading="loading">
      <div class="table-toolbar">
        <span class="table-title">物理位置树</span>
        <span class="result-count">共 {{ rows.length }} 个位置</span>
      </div>
      <el-table :data="treeRows" row-key="id" default-expand-all :tree-props="{ children: 'children' }">
        <el-table-column prop="locationName" label="位置名称" min-width="220" />
        <el-table-column prop="locationCode" label="位置编码" min-width="150">
          <template #default="{ row }"><span class="mono">{{ row.locationCode }}</span></template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ typeLabels[row.locationType as LocationRow['locationType']] }}</template>
        </el-table-column>
        <el-table-column prop="organizationName" label="所属组织" min-width="150" />
        <el-table-column prop="managerName" label="负责人" width="120">
          <template #default="{ row }">{{ row.managerName || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="auth.can('master-data:location:manage')" label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.locationType !== 'WORKSTATION'"
              link
              type="primary"
              @click="openDialog(row, true)"
            >
              新增下级
            </el-button>
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button
              v-if="row.parentId !== 0 && auth.can('master-data:location:delete')"
              link
              type="danger"
              @click="remove(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="当前数据范围内暂无位置" /></template>
      </el-table>
    </section>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑位置' : '新增位置'" width="min(720px, 94vw)">
      <el-form label-position="top" class="edit-form">
        <el-form-item label="上级位置">
          <el-tree-select
            v-model="form.parentId"
            :data="parentTree"
            node-key="id"
            check-strictly
            :render-after-expand="false"
            :props="{ label: 'locationName', children: 'children' }"
            clearable
            @clear="form.parentId = 0"
          />
        </el-form-item>
        <el-form-item label="位置类型">
          <el-select v-model="form.locationType">
            <el-option v-for="(label, value) in typeLabels" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="位置编码">
          <el-input v-model="form.locationCode" :disabled="Boolean(editing)" placeholder="例如 LINE-A-SITE" />
        </el-form-item>
        <el-form-item label="位置名称"><el-input v-model="form.locationName" /></el-form-item>
        <el-form-item label="所属组织">
          <el-tree-select
            v-model="form.organizationId"
            :data="organizationTree"
            node-key="id"
            check-strictly
            :render-after-expand="false"
            :props="{ label: 'organizationName', children: 'children', disabled: (data: OrganizationRow) => data.status !== 1 }"
          />
        </el-form-item>
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
.result-count { color: var(--tpm-text-secondary); font-size: 12px; }
.edit-form { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.full-row { grid-column: 1 / -1; }
@media (max-width: 640px) {
  .edit-form { grid-template-columns: 1fr; }
  .full-row { grid-column: auto; }
}
</style>
