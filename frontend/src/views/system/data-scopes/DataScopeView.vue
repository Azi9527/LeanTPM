<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  systemApi,
  type DataScopeDefinition,
  type OrganizationNode,
  type RoleRow,
} from '@/api/system'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const dialogVisible = ref(false)
const roles = ref<RoleRow[]>([])
const definitions = ref<DataScopeDefinition[]>([])
const organizations = ref<OrganizationNode[]>([])
const editing = ref<RoleRow | null>(null)
const form = reactive({
  dataScope: 'ALL',
  customOrganizationIds: [] as number[],
})

const organizationNames = computed(
  () => new Map(organizations.value.map((item) => [item.id, item.organizationName])),
)

const organizationTree = computed(() => {
  type TreeNode = OrganizationNode & { children: TreeNode[] }
  const nodes = new Map<number, TreeNode>()
  organizations.value.forEach((item) => nodes.set(item.id, { ...item, children: [] }))
  const roots: TreeNode[] = []
  nodes.forEach((item) => {
    const parent = nodes.get(item.parentId)
    if (parent) parent.children.push(item)
    else roots.push(item)
  })
  return roots
})

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [roleRows, scopeRows, organizationRows] = await Promise.all([
      systemApi.roles(),
      systemApi.dataScopes(),
      systemApi.organizations(),
    ])
    roles.value = roleRows
    definitions.value = scopeRows
    organizations.value = organizationRows
  } catch (error) {
    loadError.value = errorMessage(error, '数据权限加载失败')
  } finally {
    loading.value = false
  }
}

function scopeName(code: string) {
  return definitions.value.find((item) => item.scopeCode === code)?.scopeName || code
}

function openEditor(role: RoleRow) {
  editing.value = role
  form.dataScope = role.dataScope
  form.customOrganizationIds = [...role.customOrganizationIds]
  dialogVisible.value = true
}

async function save() {
  if (!editing.value) return
  if (form.dataScope === 'CUSTOM' && form.customOrganizationIds.length === 0) {
    ElMessage.warning('自定义数据范围至少选择一个组织')
    return
  }
  saving.value = true
  try {
    await systemApi.updateRoleDataScope(editing.value.id, {
      dataScope: form.dataScope,
      customOrganizationIds:
        form.dataScope === 'CUSTOM' ? form.customOrganizationIds : [],
      version: editing.value.version,
    })
    ElMessage.success('数据范围已更新，新查询立即按最新范围执行')
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div>
        <h1>数据权限</h1>
        <p>集中配置角色可访问的人员、设备与任务组织范围。</p>
      </div>
    </header>

    <el-alert
      title="多个角色的数据范围按并集计算；任一角色拥有“全部数据”时不再附加组织过滤。"
      type="info"
      show-icon
      :closable="false"
    />

    <el-alert
      v-if="loadError"
      class="state-alert"
      :title="loadError"
      type="error"
      show-icon
      :closable="false"
    >
      <template #default><el-button link type="primary" @click="load">重新加载</el-button></template>
    </el-alert>

    <section v-else class="surface-card table-card">
      <div class="table-toolbar">
        <span class="table-title">角色数据范围</span>
        <el-tag effect="plain">{{ roles.length }} 个角色</el-tag>
      </div>
      <el-table v-loading="loading" :data="roles" row-key="id">
        <el-table-column prop="roleName" label="角色" min-width="150" />
        <el-table-column prop="roleCode" label="角色编码" min-width="180">
          <template #default="{ row }"><span class="mono">{{ row.roleCode }}</span></template>
        </el-table-column>
        <el-table-column label="范围策略" min-width="160">
          <template #default="{ row }">
            <el-tag :type="row.dataScope === 'ALL' ? 'success' : 'primary'" effect="light">
              {{ scopeName(row.dataScope) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="自定义组织" min-width="240">
          <template #default="{ row }">
            <template v-if="row.customOrganizationIds.length">
              <el-tag
                v-for="organizationId in row.customOrganizationIds"
                :key="organizationId"
                class="organization-tag"
                effect="plain"
              >
                {{ organizationNames.get(organizationId) || organizationId }}
              </el-tag>
            </template>
            <span v-else class="muted">由策略自动解析</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="auth.can('system:data-scope:manage')"
              link
              type="primary"
              @click="openEditor(row)"
            >
              配置范围
            </el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无角色数据" /></template>
      </el-table>
    </section>

    <el-dialog
      v-model="dialogVisible"
      :title="`配置数据范围 · ${editing?.roleName || ''}`"
      width="min(620px, 94vw)"
      destroy-on-close
    >
      <el-form label-position="top">
        <el-form-item label="范围策略" required>
          <el-select v-model="form.dataScope" style="width: 100%">
            <el-option
              v-for="item in definitions"
              :key="item.scopeCode"
              :label="item.scopeName"
              :value="item.scopeCode"
            >
              <div class="scope-option">
                <strong>{{ item.scopeName }}</strong>
                <small>{{ item.description }}</small>
              </div>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.dataScope === 'CUSTOM'" label="允许访问的组织" required>
          <el-tree-select
            v-model="form.customOrganizationIds"
            :data="organizationTree"
            node-key="id"
            multiple
            show-checkbox
            check-strictly
            :props="{ label: 'organizationName', children: 'children' }"
            placeholder="选择一个或多个组织"
            style="width: 100%"
          />
          <span class="field-hint">仅包含显式选择的组织，不自动包含其下级。</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存范围</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.state-alert { margin-top: 16px; }
.organization-tag { margin: 2px 4px 2px 0; }
.muted { color: var(--tpm-text-secondary); }
.scope-option {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}
.scope-option small {
  color: var(--tpm-text-secondary);
  font-size: 11px;
}
.field-hint {
  margin-top: 5px;
  color: var(--tpm-text-secondary);
  font-size: 11px;
}
</style>
