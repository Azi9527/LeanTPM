<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  masterDataApi,
  type OrganizationDeleteImpact,
  type OrganizationRow,
  type ReferenceUser,
} from '@/api/masterData'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

interface OrganizationTreeNode extends OrganizationRow {
  children: OrganizationTreeNode[]
}

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const rows = ref<OrganizationRow[]>([])
const users = ref<ReferenceUser[]>([])
const keyword = ref('')
const dialogVisible = ref(false)
const editing = ref<OrganizationRow | null>(null)
const form = reactive({
  parentId: 0,
  organizationCode: '',
  organizationName: '',
  organizationType: 'ENTERPRISE' as OrganizationRow['organizationType'],
  managerUserId: undefined as number | undefined,
  sortOrder: 0,
  enabled: true,
  description: '',
})

const typeLabels: Record<OrganizationRow['organizationType'], string> = {
  ENTERPRISE: '企业',
  FACTORY: '工厂',
  DEPARTMENT: '部门',
  WORKSHOP: '车间',
  LINE: '产线',
  TEAM: '班组',
}

const nextType: Partial<Record<OrganizationRow['organizationType'], OrganizationRow['organizationType']>> = {
  ENTERPRISE: 'FACTORY',
  FACTORY: 'WORKSHOP',
  DEPARTMENT: 'TEAM',
  WORKSHOP: 'LINE',
  LINE: 'TEAM',
}

const treeRows = computed(() => buildTree(filterWithAncestors(rows.value, keyword.value)))
const parentTree = computed(() => buildTree(
  rows.value.filter((row) => row.id !== editing.value?.id),
))

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [organizationRows, userRows] = await Promise.all([
      masterDataApi.organizations(),
      masterDataApi.referenceUsers(),
    ])
    rows.value = organizationRows
    users.value = userRows
  } catch (error) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

function buildTree(source: OrganizationRow[]): OrganizationTreeNode[] {
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

function filterWithAncestors(source: OrganizationRow[], value: string): OrganizationRow[] {
  const needle = value.trim().toLowerCase()
  if (!needle) return source
  const byId = new Map(source.map((row) => [row.id, row]))
  const ids = new Set<number>()
  source.forEach((row) => {
    if (`${row.organizationCode} ${row.organizationName} ${row.managerName || ''}`
      .toLowerCase().includes(needle)) {
      let cursor: OrganizationRow | undefined = row
      while (cursor && !ids.has(cursor.id)) {
        ids.add(cursor.id)
        cursor = byId.get(cursor.parentId)
      }
    }
  })
  return source.filter((row) => ids.has(row.id))
}

function openDialog(row?: OrganizationRow, asChild = false) {
  editing.value = asChild ? null : row || null
  Object.assign(form, editing.value
    ? {
        parentId: editing.value.parentId,
        organizationCode: editing.value.organizationCode,
        organizationName: editing.value.organizationName,
        organizationType: editing.value.organizationType,
        managerUserId: editing.value.managerUserId,
        sortOrder: editing.value.sortOrder,
        enabled: editing.value.status === 1,
        description: editing.value.description || '',
      }
    : {
        parentId: row?.id || 0,
        organizationCode: '',
        organizationName: '',
        organizationType: row ? nextType[row.organizationType] || 'DEPARTMENT' : 'ENTERPRISE',
        managerUserId: undefined,
        sortOrder: 0,
        enabled: true,
        description: '',
      })
  dialogVisible.value = true
}

async function save() {
  if (!form.organizationCode.trim() || !form.organizationName.trim()) {
    ElMessage.warning('请完整填写组织编码和名称')
    return
  }
  saving.value = true
  try {
    const payload = {
      ...form,
      organizationCode: form.organizationCode.trim().toUpperCase(),
      organizationName: form.organizationName.trim(),
      version: editing.value?.version,
    }
    if (editing.value) await masterDataApi.updateOrganization(editing.value.id, payload)
    else await masterDataApi.createOrganization(payload)
    dialogVisible.value = false
    ElMessage.success('组织已保存')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function remove(row: OrganizationRow) {
  let impact: OrganizationDeleteImpact
  try {
    impact = await masterDataApi.organizationDeleteImpact(row.id)
  } catch (error) {
    ElMessage.error(errorMessage(error, '无法检查组织关联关系'))
    return
  }
  if (impact.childOrganizations > 0) {
    await ElMessageBox.alert(
      `组织“${row.organizationName}”还有 ${impact.childOrganizations} 个下级组织，请先删除或调整下级组织。`,
      '暂时不能删除',
      { type: 'warning' },
    )
    return
  }
  const details = [
    impact.users ? `用户 ${impact.users} 个` : '',
    impact.locations ? `位置 ${impact.locations} 个` : '',
    impact.equipment ? `设备 ${impact.equipment} 台` : '',
    impact.teamMemberships ? `班组任职关系 ${impact.teamMemberships} 条` : '',
    impact.dataScopes ? `数据权限关系 ${impact.dataScopes} 条` : '',
    impact.businessRecords ? `业务记录 ${impact.businessRecords} 条` : '',
    impact.visualizationRecords ? `可视化配置 ${impact.visualizationRecords} 条` : '',
  ].filter(Boolean)
  const hasRelations = impact.totalReferences > 0
  const message = hasRelations
    ? `组织“${row.organizationName}”存在以下关联：${details.join('、')}。是否将业务数据转移到上级组织、删除班组任职及数据权限关系，然后删除该组织？`
    : `确认删除组织“${row.organizationName}”吗？`
  try {
    await ElMessageBox.confirm(message, hasRelations ? '删除组织及关联关系' : '删除组织', {
      type: 'warning',
      confirmButtonText: hasRelations ? '删除关联关系并删除组织' : '确认删除',
      cancelButtonText: '取消',
      distinguishCancelAndClose: true,
    })
  } catch {
    return
  }
  try {
    await masterDataApi.deleteOrganization(row.id, row.version, hasRelations)
    ElMessage.success('组织已删除')
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
        <h1>组织管理</h1>
        <p>维护企业管理组织树；组织调整不会改变设备物理位置。</p>
      </div>
      <el-button
        v-if="auth.can('master-data:organization:manage')"
        type="primary"
        @click="openDialog()"
      >
        新增根组织
      </el-button>
    </header>

    <section class="surface-card query-bar">
      <el-input
        v-model="keyword"
        clearable
        placeholder="组织编码、名称或负责人"
        style="width: min(360px, 100%)"
      />
      <el-button type="primary" plain @click="load">刷新</el-button>
    </section>

    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false">
      <template #default><el-button link type="primary" @click="load">重新加载</el-button></template>
    </el-alert>

    <section class="surface-card table-card" v-loading="loading">
      <div class="table-toolbar">
        <span class="table-title">组织树</span>
        <span class="result-count">共 {{ rows.length }} 个组织</span>
      </div>
      <el-table
        :data="treeRows"
        row-key="id"
        default-expand-all
        :tree-props="{ children: 'children' }"
      >
        <el-table-column prop="organizationName" label="组织名称" min-width="220" />
        <el-table-column prop="organizationCode" label="组织编码" min-width="150">
          <template #default="{ row }"><span class="mono">{{ row.organizationCode }}</span></template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ typeLabels[row.organizationType as OrganizationRow['organizationType']] }}</template>
        </el-table-column>
        <el-table-column prop="managerName" label="负责人" width="130">
          <template #default="{ row }">{{ row.managerName || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="auth.can('master-data:organization:manage')" label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDialog(row, true)">新增下级</el-button>
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button
              v-if="row.parentId !== 0 && auth.can('master-data:organization:delete')"
              link
              type="danger"
              @click="remove(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="当前数据范围内暂无组织" /></template>
      </el-table>
    </section>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑组织' : '新增组织'"
      width="min(680px, 94vw)"
    >
      <el-form label-position="top" class="edit-form">
        <el-form-item label="上级组织">
          <el-tree-select
            v-model="form.parentId"
            :data="parentTree"
            node-key="id"
            check-strictly
            :render-after-expand="false"
            :props="{ label: 'organizationName', children: 'children' }"
            placeholder="根组织请选择 0"
            clearable
            @clear="form.parentId = 0"
          />
        </el-form-item>
        <el-form-item label="组织类型">
          <el-select v-model="form.organizationType">
            <el-option v-for="(label, value) in typeLabels" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="组织编码">
          <el-input v-model="form.organizationCode" :disabled="Boolean(editing)" placeholder="例如 FACTORY-A" />
        </el-form-item>
        <el-form-item label="组织名称"><el-input v-model="form.organizationName" /></el-form-item>
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
        <el-form-item label="说明" class="full-row">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
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
