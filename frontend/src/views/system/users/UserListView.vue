<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  ElMessage,
  ElMessageBox,
  type FormInstance,
  type FormRules,
  type UploadFile,
} from 'element-plus'
import { Download, Plus, UploadFilled } from '@element-plus/icons-vue'
import {
  systemApi,
  type OrganizationNode,
  type PersonnelOrganizationRow,
  type PersonnelOrganizationSnapshot,
  type PersonnelUserRow,
  type RoleRow,
  type UserImportResult,
  type UserRow,
} from '@/api/system'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const importDialogVisible = ref(false)
const importFile = ref<File>()
const importResult = ref<UserImportResult>()
const validatingImport = ref(false)
const committingImport = ref(false)
const editing = ref<UserRow | null>(null)
const formRef = ref<FormInstance>()
const rows = ref<UserRow[]>([])
const roles = ref<RoleRow[]>([])
const organizations = ref<OrganizationNode[]>([])
const activeTab = ref('relationships')
const relationshipLoading = ref(false)
const relationshipSavingId = ref<number>()
const relationship = ref<PersonnelOrganizationSnapshot>({ organizations: [], users: [] })
const managerDraft = reactive<Record<number, number | undefined>>({})
const memberDraft = reactive<Record<number, number[]>>({})
const relationshipKeyword = ref('')
const selectedOrganizationId = ref<number>()
const total = ref(0)
const query = reactive({ keyword: '', status: undefined as number | undefined, page: 1, pageSize: 100 })
const form = reactive({
  username: '',
  realName: '',
  employeeNo: '',
  mobile: '',
  email: '',
  organizationId: undefined as number | undefined,
  mobileEnabled: true,
  roleIds: [] as number[],
  initialPassword: '',
})
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  organizationId: [{ required: true, message: '请选择所属组织', trigger: 'change' }],
  roleIds: [{ required: true, type: 'array', min: 1, message: '至少选择一个角色', trigger: 'change' }],
  initialPassword: [
    {
      validator: (_rule, value, callback) => {
        if (!editing.value && !value) callback(new Error('请输入初始密码'))
        else if (value && value.length < 6) callback(new Error('密码至少 6 位'))
        else callback()
      },
      trigger: 'blur',
    },
  ],
}

const statusOptions = [
  { label: '全部状态', value: undefined },
  { label: '启用', value: 1 },
  { label: '停用', value: 0 },
]

onMounted(async () => {
  await Promise.all([load(), loadRoles(), loadOrganizations(), loadRelationships()])
})

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
const organizationTreeProps = {
  label: 'organizationName',
  children: 'children',
  disabled: (data: OrganizationNode) => data.status !== 1,
}

const managerOptions = computed(() =>
  relationship.value.users.filter((user) => user.status === 1),
)
const employeeOptions = computed(() =>
  relationship.value.users.filter((user) => user.status === 1 && user.roleCodes.includes('OPERATOR')),
)

type RelationNode = PersonnelOrganizationRow & { children: RelationNode[] }

const relationshipTree = computed<RelationNode[]>(() => {
  const nodes = new Map<number, RelationNode>()
  relationship.value.organizations.forEach((item) => nodes.set(item.id, { ...item, children: [] }))
  const roots: RelationNode[] = []
  nodes.forEach((item) => {
    const parent = nodes.get(item.parentId)
    if (parent) parent.children.push(item)
    else roots.push(item)
  })
  return roots
})

const filteredRelationshipTree = computed(() => {
  const keyword = relationshipKeyword.value.trim().toLowerCase()
  if (!keyword) return relationshipTree.value
  const filterNodes = (nodes: RelationNode[]): RelationNode[] => nodes.flatMap((node) => {
    const children = filterNodes(node.children || [])
    const matched = `${node.organizationName} ${node.organizationCode} ${organizationTypeLabel(node.organizationType)}`
      .toLowerCase()
      .includes(keyword)
    return matched || children.length ? [{ ...node, children }] : []
  })
  return filterNodes(relationshipTree.value)
})

const selectedOrganization = computed(() =>
  relationship.value.organizations.find((item) => item.id === selectedOrganizationId.value),
)

const selectedOrganizationPath = computed(() => {
  if (!selectedOrganization.value) return []
  const nodes = new Map(relationship.value.organizations.map((item) => [item.id, item]))
  const path: PersonnelOrganizationRow[] = []
  let current: PersonnelOrganizationRow | undefined = selectedOrganization.value
  while (current) {
    path.unshift(current)
    current = nodes.get(current.parentId)
  }
  return path
})

const selectedDirectChildren = computed(() =>
  relationship.value.organizations.filter((item) => item.parentId === selectedOrganizationId.value),
)

const selectedMemberUsers = computed(() => {
  const ids = memberDraft[selectedOrganizationId.value || 0] || []
  return relationship.value.users.filter((user) => ids.includes(user.id))
})

function organizationTypeLabel(type: string) {
  return ({ ENTERPRISE: '企业', FACTORY: '工厂', WORKSHOP: '车间', LINE: '产线', SECTION: '工段', TEAM: '班组' } as Record<string, string>)[type] || type
}

function managerLabel(organization: PersonnelOrganizationRow) {
  if (organization.organizationType === 'WORKSHOP') return '车间负责人'
  if (organization.organizationType === 'LINE') return '产线负责人'
  if (organization.organizationType === 'SECTION') return '工段长'
  if (organization.organizationType === 'TEAM') return '班组长'
  return '负责人'
}

function selectOrganization(node: PersonnelOrganizationRow) {
  selectedOrganizationId.value = node.id
}

function userLabel(user: PersonnelUserRow) {
  return `${user.realName}（${user.username}${user.employeeNo ? ` / ${user.employeeNo}` : ''}）${user.status === 1 ? '' : ' · 已停用'}`
}

async function load() {
  loading.value = true
  try {
    const result = await systemApi.users(query)
    rows.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(errorMessage(error, '用户列表加载失败'))
  } finally {
    loading.value = false
  }
}

async function loadRoles() {
  if (!auth.can('system:user:view') && !auth.can('system:role:view')) return
  roles.value = await systemApi.roles()
}

async function loadOrganizations() {
  try {
    organizations.value = await systemApi.organizations()
  } catch (error) {
    ElMessage.error(errorMessage(error, '组织数据加载失败'))
  }
}

async function loadRelationships() {
  relationshipLoading.value = true
  try {
    relationship.value = await systemApi.personnelOrganization()
    relationship.value.organizations.forEach((organization) => {
      managerDraft[organization.id] = organization.managerUserId
        || organization.managerUserIds?.[0]
      memberDraft[organization.id] = [...(organization.memberUserIds || [])]
    })
    if (!relationship.value.organizations.some((item) => item.id === selectedOrganizationId.value)) {
      selectedOrganizationId.value = relationship.value.organizations.find((item) => item.organizationType === 'WORKSHOP')?.id
        || relationship.value.organizations[0]?.id
    }
  } catch (error) {
    ElMessage.error(errorMessage(error, '人员组织关系加载失败'))
  } finally {
    relationshipLoading.value = false
  }
}

async function saveOrganizationManager(organization: PersonnelOrganizationRow) {
  relationshipSavingId.value = organization.id
  try {
    await systemApi.updateOrganizationManager(organization.id, {
      managerUserIds: managerDraft[organization.id] ? [managerDraft[organization.id]!] : [],
      version: organization.version,
    })
    ElMessage.success(`${managerLabel(organization)}已更新`)
    await loadRelationships()
  } catch (error) {
    ElMessage.error(errorMessage(error, '负责人保存失败'))
  } finally {
    relationshipSavingId.value = undefined
  }
}

async function saveTeamRelationships(organization: PersonnelOrganizationRow) {
  relationshipSavingId.value = organization.id
  try {
    await systemApi.updateTeamRelationships(organization.id, {
      managerUserIds: managerDraft[organization.id] ? [managerDraft[organization.id]!] : [],
      userIds: memberDraft[organization.id] || [],
      version: organization.version,
    })
    ElMessage.success('班组长和班组成员已统一保存')
    await loadRelationships()
  } catch (error) {
    ElMessage.error(errorMessage(error, '班组关系保存失败'))
  } finally {
    relationshipSavingId.value = undefined
  }
}

function resetQuery() {
  query.keyword = ''
  query.status = undefined
  query.page = 1
  load()
}

function openCreate() {
  editing.value = null
  Object.assign(form, {
    username: '',
    realName: '',
    employeeNo: '',
    mobile: '',
    email: '',
    organizationId: organizations.value.find((item) => item.status === 1)?.id,
    mobileEnabled: true,
    roleIds: [],
    initialPassword: '',
  })
  dialogVisible.value = true
}

function openEdit(row: UserRow) {
  editing.value = row
  Object.assign(form, {
    username: row.username,
    realName: row.realName,
    employeeNo: row.employeeNo || '',
    mobile: row.mobile || '',
    email: row.email || '',
    organizationId: row.organizationId,
    mobileEnabled: row.mobileEnabled,
    roleIds: [...row.roleIds],
    initialPassword: '',
  })
  dialogVisible.value = true
}

async function save() {
  await formRef.value?.validate()
  saving.value = true
  try {
    if (editing.value) {
      await systemApi.updateUser(editing.value.id, {
        realName: form.realName,
        employeeNo: form.employeeNo,
        mobile: form.mobile,
        email: form.email,
        organizationId: form.organizationId,
        mobileEnabled: form.mobileEnabled,
        roleIds: form.roleIds,
        version: editing.value.version,
      })
    } else {
      await systemApi.createUser({ ...form })
    }
    ElMessage.success(editing.value ? '用户已更新' : '用户已创建')
    dialogVisible.value = false
    await Promise.all([load(), loadRelationships()])
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row: UserRow) {
  const enabled = row.status !== 1
  await ElMessageBox.confirm(
    `确认${enabled ? '启用' : '停用'}用户“${row.realName}”吗？`,
    `${enabled ? '启用' : '停用'}用户`,
    { type: enabled ? 'info' : 'warning', confirmButtonText: '确认', cancelButtonText: '取消' },
  )
  try {
    await systemApi.updateUserStatus(row.id, { enabled, version: row.version })
    ElMessage.success('用户状态已更新')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function resetPassword(row: UserRow) {
  try {
    const { value } = await ElMessageBox.prompt(
      `为“${row.realName}”设置临时密码，用户下次登录必须修改。`,
      '重置密码',
      {
        inputType: 'password',
        inputPlaceholder: '至少 6 位',
        inputValidator: (value) =>
          value.length >= 6 || '密码至少 6 位',
        confirmButtonText: '确认重置',
        cancelButtonText: '取消',
      },
    )
    await systemApi.resetUserPassword(row.id, value)
    ElMessage.success('密码已重置')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error))
  }
}

function openImport() {
  importFile.value = undefined
  importResult.value = undefined
  importDialogVisible.value = true
}

function chooseImportFile(uploadFile: UploadFile) {
  importFile.value = uploadFile.raw
  importResult.value = undefined
}

async function downloadImportTemplate() {
  try {
    const response = await systemApi.downloadUserImportTemplate()
    const url = URL.createObjectURL(response.data)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = 'LeanTPM用户导入模板.xlsx'
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    ElMessage.error(errorMessage(error, '用户导入模板下载失败'))
  }
}

async function validateImport() {
  if (!importFile.value) {
    ElMessage.warning('请先选择 Excel 文件')
    return
  }
  validatingImport.value = true
  try {
    const body = new FormData()
    body.append('file', importFile.value)
    const response = await systemApi.validateUserImport(body)
    importResult.value = response.data.data
    if (importResult.value.validRows > 0) {
      ElMessage.success(`校验完成，可导入 ${importResult.value.validRows} 行`)
    } else {
      ElMessage.warning('没有可导入的数据，请按错误回执修改文件')
    }
  } catch (error) {
    ElMessage.error(errorMessage(error, '用户导入文件校验失败'))
  } finally {
    validatingImport.value = false
  }
}

async function commitImport() {
  if (!importResult.value?.batchId || importResult.value.validRows < 1) return
  committingImport.value = true
  try {
    const response = await systemApi.commitUserImport(importResult.value.batchId)
    importResult.value = response.data.data
    ElMessage.success(
      `导入完成：新增 ${importResult.value.newUsers}，更新 ${importResult.value.updatedUsers}，跳过 ${importResult.value.skippedUsers}`,
    )
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error, '用户批量导入提交失败'))
  } finally {
    committingImport.value = false
  }
}

const pageSummary = computed(() => `共 ${total.value} 个用户`)
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div><h1>人员与组织关系</h1><p>每个组织统一维护一名负责人；员工仍可在多个班组任职。</p></div>
      <div v-if="activeTab === 'users'" class="page-actions">
        <el-button v-if="auth.can('system:user:import')" :icon="UploadFilled" @click="openImport">批量导入</el-button>
        <el-button v-if="auth.can('system:user:create')" type="primary" :icon="Plus" @click="openCreate">新增用户</el-button>
      </div>
    </header>

    <el-tabs v-model="activeTab" class="personnel-tabs">
      <el-tab-pane label="组织人员关系" name="relationships">
        <section class="surface-card relationship-card">
          <el-alert
            title="层级规则：车间、产线/工段、班组各设置一名负责人；没有工段的车间可直接管理班组；员工可以同时加入多个班组。"
            type="info"
            :closable="false"
            show-icon
          />
          <div v-loading="relationshipLoading" class="organization-workspace">
            <aside class="organization-navigator">
              <div class="navigator-heading">
                <div>
                  <strong>组织架构</strong>
                  <span>{{ relationship.organizations.length }} 个组织节点</span>
                </div>
              </div>
              <el-input v-model="relationshipKeyword" clearable placeholder="搜索组织名称、编码或类型" class="organization-search" />
              <el-tree
                v-if="filteredRelationshipTree.length"
                :data="filteredRelationshipTree"
                node-key="id"
                default-expand-all
                highlight-current
                :current-node-key="selectedOrganizationId"
                :expand-on-click-node="false"
                @node-click="selectOrganization"
              >
                <template #default="{ data }">
                  <div class="organization-tree-node">
                    <span class="type-icon" :class="`type-${data.organizationType.toLowerCase()}`">
                      {{ organizationTypeLabel(data.organizationType).slice(0, 1) }}
                    </span>
                    <span class="tree-node-copy">
                      <strong>{{ data.organizationName }}</strong>
                      <small>{{ organizationTypeLabel(data.organizationType) }} · {{ data.organizationCode }}</small>
                    </span>
                    <span v-if="data.organizationType === 'TEAM'" class="member-count">
                      {{ memberDraft[data.id]?.length || 0 }} 人
                    </span>
                  </div>
                </template>
              </el-tree>
              <el-empty v-else :image-size="64" description="未找到匹配的组织" />
            </aside>

            <main v-if="selectedOrganization" class="organization-detail">
              <div class="organization-breadcrumb">
                <template v-for="(item, index) in selectedOrganizationPath" :key="item.id">
                  <span>{{ item.organizationName }}</span><i v-if="index < selectedOrganizationPath.length - 1">/</i>
                </template>
              </div>
              <header class="detail-header">
                <div class="detail-title-block">
                  <span class="detail-type-icon" :class="`type-${selectedOrganization.organizationType.toLowerCase()}`">
                    {{ organizationTypeLabel(selectedOrganization.organizationType).slice(0, 1) }}
                  </span>
                  <div>
                    <div class="detail-title-line">
                      <h2>{{ selectedOrganization.organizationName }}</h2>
                      <el-tag effect="plain">{{ organizationTypeLabel(selectedOrganization.organizationType) }}</el-tag>
                    </div>
                    <p>组织编码 {{ selectedOrganization.organizationCode }}</p>
                  </div>
                </div>
                <div class="detail-stats">
                  <span><strong>{{ selectedDirectChildren.length }}</strong>直属下级</span>
                  <span><strong>{{ selectedOrganization.organizationType === 'TEAM' ? selectedMemberUsers.length : '—' }}</strong>班组成员</span>
                </div>
              </header>

              <section v-if="['WORKSHOP', 'LINE', 'SECTION'].includes(selectedOrganization.organizationType)" class="maintenance-panel">
                <div class="panel-heading">
                  <div><h3>{{ managerLabel(selectedOrganization) }}</h3><p>当前组织只设置一名负责人，称谓随组织类型显示。</p></div>
                  <el-tag type="success">管理关系</el-tag>
                </div>
                <el-form label-position="top">
                  <el-form-item :label="managerLabel(selectedOrganization)">
                    <el-select v-model="managerDraft[selectedOrganization.id]" clearable filterable :placeholder="`请选择${managerLabel(selectedOrganization)}`" style="width: 100%">
                      <el-option v-for="user in managerOptions" :key="user.id" :label="userLabel(user)" :value="user.id" />
                    </el-select>
                  </el-form-item>
                </el-form>
                <div class="panel-actions">
                  <el-button type="primary" :loading="relationshipSavingId === selectedOrganization.id" @click="saveOrganizationManager(selectedOrganization)">保存{{ managerLabel(selectedOrganization) }}</el-button>
                </div>
              </section>

              <template v-else-if="selectedOrganization.organizationType === 'TEAM'">
                <section class="maintenance-panel">
                  <div class="panel-heading">
                    <div><h3>班组管理关系</h3><p>班组长是本班组员工的直接上级，也是点检异常的首要接收人。</p></div>
                    <el-tag type="warning">直接上级</el-tag>
                  </div>
                  <el-form label-position="top">
                    <el-form-item label="班组长">
                      <el-select v-model="managerDraft[selectedOrganization.id]" clearable filterable placeholder="请选择一名班组长" style="width: 100%">
                        <el-option v-for="user in managerOptions" :key="user.id" :label="userLabel(user)" :value="user.id" />
                      </el-select>
                    </el-form-item>
                    <el-form-item label="班组员工（支持多选，员工可加入多个班组）">
                      <el-select v-model="memberDraft[selectedOrganization.id]" multiple collapse-tags collapse-tags-tooltip filterable placeholder="选择本班组员工" style="width: 100%">
                        <el-option v-for="user in employeeOptions" :key="user.id" :label="userLabel(user)" :value="user.id" />
                      </el-select>
                    </el-form-item>
                  </el-form>
                  <div v-if="selectedMemberUsers.length" class="member-preview">
                    <span v-for="user in selectedMemberUsers" :key="user.id">{{ user.realName }}<small>{{ user.employeeNo || user.username }}</small></span>
                  </div>
                  <div class="panel-actions">
                    <el-button type="primary" :loading="relationshipSavingId === selectedOrganization.id" @click="saveTeamRelationships(selectedOrganization)">保存班组关系</el-button>
                  </div>
                </section>
              </template>

              <section v-else class="maintenance-panel overview-panel">
                <div class="panel-heading">
                  <div><h3>层级概览</h3><p>当前层级用于组织归属和数据权限汇总，负责人由下级车间或班组分别维护。</p></div>
                  <el-tag type="info">汇总节点</el-tag>
                </div>
                <div class="child-grid">
                  <button v-for="child in selectedDirectChildren" :key="child.id" type="button" @click="selectOrganization(child)">
                    <span class="type-icon" :class="`type-${child.organizationType.toLowerCase()}`">{{ organizationTypeLabel(child.organizationType).slice(0, 1) }}</span>
                    <span><strong>{{ child.organizationName }}</strong><small>{{ organizationTypeLabel(child.organizationType) }} · {{ child.organizationCode }}</small></span>
                  </button>
                  <el-empty v-if="!selectedDirectChildren.length" :image-size="72" description="暂无直属下级组织" />
                </div>
              </section>
            </main>
            <el-empty v-else class="organization-detail empty-detail" description="请从左侧选择一个组织节点" />
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane label="用户账号" name="users">
    <section class="surface-card query-bar">
      <el-input v-model="query.keyword" clearable placeholder="姓名、账号或工号" style="width: 260px" @keyup.enter="load">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-select v-model="query.status" placeholder="状态" style="width: 130px">
        <el-option v-for="item in statusOptions" :key="String(item.value)" :label="item.label" :value="item.value" />
      </el-select>
      <el-button type="primary" plain @click="query.page = 1; load()">查询</el-button>
      <el-button @click="resetQuery">重置</el-button>
    </section>

    <section class="surface-card table-card">
      <div class="table-toolbar"><span class="table-title">{{ pageSummary }}</span></div>
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="username" label="账号" min-width="130" />
        <el-table-column prop="realName" label="姓名" min-width="120" />
        <el-table-column prop="employeeNo" label="工号" min-width="110" show-overflow-tooltip />
        <el-table-column label="角色" min-width="180">
          <template #default="{ row }">
            <el-tag v-for="roleId in row.roleIds" :key="roleId" size="small" effect="plain" class="role-tag">
              {{ roles.find((role) => role.id === roleId)?.roleName || roleId }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="organizationName" label="所属组织" min-width="150" show-overflow-tooltip />
        <el-table-column prop="mobile" label="手机" min-width="130" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" effect="light">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近登录" min-width="170">
          <template #default="{ row }">{{ row.lastLoginTime?.replace('T', ' ') || '尚未登录' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="245" fixed="right">
          <template #default="{ row }">
            <el-button v-if="auth.can('system:user:update')" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="auth.can('system:user:status')" link :type="row.status === 1 ? 'danger' : 'success'" @click="toggleStatus(row)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button v-if="auth.can('system:user:reset-password')" link @click="resetPassword(row)">重置密码</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无用户数据" /></template>
      </el-table>
      <div class="table-pagination">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @change="load"
        />
      </div>
    </section>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑用户' : '新增用户'" width="min(680px, 94vw)" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="user-form">
        <el-form-item label="登录账号" prop="username">
          <el-input v-model="form.username" :disabled="Boolean(editing)" placeholder="例如 zhangsan" />
        </el-form-item>
        <el-form-item label="姓名" prop="realName"><el-input v-model="form.realName" /></el-form-item>
        <el-form-item label="工号"><el-input v-model="form.employeeNo" /></el-form-item>
        <el-form-item label="手机"><el-input v-model="form.mobile" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
        <el-form-item label="所属组织" prop="organizationId">
          <el-tree-select
            v-model="form.organizationId"
            :data="organizationTree"
            node-key="id"
            check-strictly
            :props="organizationTreeProps"
            placeholder="选择所属组织"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="允许移动端">
          <el-switch v-model="form.mobileEnabled" />
        </el-form-item>
        <el-form-item label="业务角色" prop="roleIds" class="full-width">
          <el-select v-model="form.roleIds" multiple placeholder="选择角色" style="width: 100%">
            <el-option v-for="role in roles" :key="role.id" :label="role.roleName" :value="role.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="!editing" label="初始密码" prop="initialPassword" class="full-width">
          <el-input v-model="form.initialPassword" type="password" show-password />
          <span class="field-hint">至少 6 位</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importDialogVisible" title="批量导入用户" width="min(820px, 96vw)" destroy-on-close>
      <el-alert
        title="账号、角色和组织关系可在同一份 Excel 中导入"
        description="可指定主要归属组织、多个任职班组、主班组，以及一个负责组织。负责人统一写入组织的负责人字段，模板的“组织关系说明”页已列出全部可用组织编码。"
        type="info"
        :closable="false"
        show-icon
      />
      <div class="import-actions">
        <el-button :icon="Download" @click="downloadImportTemplate">下载导入模板</el-button>
        <el-upload
          accept=".xlsx"
          :auto-upload="false"
          :limit="1"
          :show-file-list="true"
          :on-change="chooseImportFile"
          :on-remove="() => { importFile = undefined; importResult = undefined }"
        >
          <el-button :icon="UploadFilled">选择 Excel 文件</el-button>
        </el-upload>
        <el-button type="primary" :disabled="!importFile" :loading="validatingImport" @click="validateImport">
          校验文件
        </el-button>
      </div>

      <template v-if="importResult">
        <el-descriptions :column="4" border class="import-summary">
          <el-descriptions-item label="总行数">{{ importResult.totalRows }}</el-descriptions-item>
          <el-descriptions-item label="有效行">{{ importResult.validRows }}</el-descriptions-item>
          <el-descriptions-item label="错误数">{{ importResult.errors.length }}</el-descriptions-item>
          <el-descriptions-item label="处理策略">{{ importResult.strategy }}</el-descriptions-item>
          <el-descriptions-item label="预计新增">{{ importResult.newUsers }}</el-descriptions-item>
          <el-descriptions-item label="预计更新">{{ importResult.updatedUsers }}</el-descriptions-item>
          <el-descriptions-item label="预计跳过">{{ importResult.skippedUsers }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ importResult.status }}</el-descriptions-item>
        </el-descriptions>
        <el-table v-if="importResult.errors.length" :data="importResult.errors" max-height="260" size="small">
          <el-table-column prop="rowNumber" label="Excel 行" width="90" />
          <el-table-column prop="column" label="字段" width="150" />
          <el-table-column prop="message" label="错误说明" min-width="300" />
        </el-table>
      </template>

      <template #footer>
        <el-button @click="importDialogVisible = false">关闭</el-button>
        <el-button
          type="primary"
          :disabled="!importResult || importResult.validRows < 1 || importResult.status === 'COMMITTED'"
          :loading="committingImport"
          @click="commitImport"
        >
          提交有效数据
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.role-tag { margin: 2px 4px 2px 0; }
.personnel-tabs { margin-top: -6px; }
.relationship-card { padding: 16px; }
.organization-workspace {
  display: grid;
  grid-template-columns: minmax(300px, 32%) 1fr;
  min-height: 560px;
  margin-top: 16px;
  overflow: hidden;
  border: 1px solid var(--tpm-border);
  border-radius: 14px;
  background: var(--tpm-card);
}
.organization-navigator {
  min-width: 0;
  padding: 18px 14px;
  border-right: 1px solid var(--tpm-border);
  background: linear-gradient(180deg, rgb(28 125 80 / 6%), transparent 180px);
}
.navigator-heading { display: flex; align-items: center; justify-content: space-between; margin: 0 4px 14px; }
.navigator-heading > div { display: flex; flex-direction: column; gap: 3px; }
.navigator-heading strong { color: var(--tpm-text); font-size: 16px; }
.navigator-heading span { color: var(--tpm-text-secondary); font-size: 12px; }
.organization-search { margin-bottom: 14px; }
.organization-navigator :deep(.el-tree) { background: transparent; }
.organization-navigator :deep(.el-tree-node__content) {
  height: auto;
  min-height: 54px;
  margin: 3px 0;
  padding-right: 8px;
  border-radius: 10px;
}
.organization-navigator :deep(.el-tree-node__content:hover) { background: rgb(28 125 80 / 8%); }
.organization-navigator :deep(.el-tree-node.is-current > .el-tree-node__content) {
  color: var(--tpm-primary);
  background: rgb(28 125 80 / 13%);
  box-shadow: inset 3px 0 var(--tpm-primary);
}
.organization-tree-node { display: flex; min-width: 0; flex: 1; align-items: center; gap: 10px; padding: 7px 0; }
.type-icon,
.detail-type-icon {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 9px;
  color: #fff;
  background: #64748b;
  font-size: 13px;
  font-weight: 700;
}
.type-enterprise { background: #163d2f; }
.type-factory { background: #1c7d50; }
.type-workshop { background: #2f9665; }
.type-line { background: #287e8b; }
.type-section { background: #2563a6; }
.type-team { background: #b7791f; }
.tree-node-copy { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 3px; }
.tree-node-copy strong { overflow: hidden; color: inherit; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.tree-node-copy small { overflow: hidden; color: var(--tpm-text-secondary); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.member-count { flex: 0 0 auto; color: var(--tpm-text-secondary); font-size: 11px; }
.organization-detail { min-width: 0; padding: 24px; background: linear-gradient(135deg, #fff 0%, #fbfdfc 100%); }
.organization-breadcrumb { display: flex; flex-wrap: wrap; gap: 7px; color: var(--tpm-text-secondary); font-size: 12px; }
.organization-breadcrumb i { color: var(--tpm-border); font-style: normal; }
.detail-header { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 18px 0 22px; border-bottom: 1px solid var(--tpm-border); }
.detail-title-block { display: flex; min-width: 0; align-items: center; gap: 14px; }
.detail-type-icon { width: 48px; height: 48px; border-radius: 13px; font-size: 18px; }
.detail-title-line { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.detail-title-line h2 { margin: 0; color: var(--tpm-text); font-size: 22px; }
.detail-title-block p { margin: 5px 0 0; color: var(--tpm-text-secondary); font: 12px Consolas, monospace; }
.detail-stats { display: flex; flex: 0 0 auto; gap: 10px; }
.detail-stats span { display: flex; min-width: 82px; flex-direction: column; gap: 3px; padding: 10px 14px; border-radius: 10px; color: var(--tpm-text-secondary); background: #f3f7f5; font-size: 11px; text-align: center; }
.detail-stats strong { color: var(--tpm-primary); font-size: 20px; }
.maintenance-panel { margin-top: 22px; padding: 20px; border: 1px solid var(--tpm-border); border-radius: 12px; background: #fff; }
.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.panel-heading h3 { margin: 0 0 5px; color: var(--tpm-text); font-size: 17px; }
.panel-heading p { margin: 0; color: var(--tpm-text-secondary); font-size: 12px; line-height: 1.6; }
.panel-actions { display: flex; justify-content: flex-end; padding-top: 4px; }
.member-preview { display: flex; flex-wrap: wrap; gap: 8px; margin: -2px 0 18px; }
.member-preview > span { display: inline-flex; align-items: center; gap: 7px; padding: 7px 10px; border: 1px solid rgb(28 125 80 / 16%); border-radius: 999px; color: var(--tpm-text); background: rgb(28 125 80 / 5%); font-size: 12px; }
.member-preview small { color: var(--tpm-text-secondary); }
.child-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.child-grid button { display: flex; min-width: 0; align-items: center; gap: 11px; padding: 13px; border: 1px solid var(--tpm-border); border-radius: 10px; color: inherit; background: #fff; text-align: left; cursor: pointer; transition: 0.2s ease; }
.child-grid button:hover { border-color: rgb(28 125 80 / 45%); box-shadow: 0 6px 18px rgb(16 58 42 / 8%); transform: translateY(-1px); }
.child-grid button > span:last-child { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
.child-grid strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.child-grid small { color: var(--tpm-text-secondary); }
.empty-detail { min-height: 420px; }
.muted-text { color: var(--tpm-text-secondary); font-size: 12px; }
.user-form {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 18px;
}
.full-width { grid-column: 1 / -1; }
.field-hint { margin-top: 4px; color: var(--tpm-text-secondary); font-size: 11px; }
.import-actions { display: flex; flex-wrap: wrap; align-items: flex-start; gap: 12px; margin: 18px 0; }
.import-summary { margin-bottom: 16px; }
@media (max-width: 600px) {
  .user-form { grid-template-columns: 1fr; }
  .full-width { grid-column: auto; }
}
@media (max-width: 980px) {
  .organization-workspace { grid-template-columns: 1fr; }
  .organization-navigator { border-right: 0; border-bottom: 1px solid var(--tpm-border); }
  .organization-navigator :deep(.el-tree) { max-height: 340px; overflow: auto; }
  .detail-header { align-items: flex-start; flex-direction: column; }
}
@media (max-width: 600px) {
  .organization-detail { padding: 18px 14px; }
  .detail-stats { width: 100%; }
  .detail-stats span { flex: 1; }
  .child-grid { grid-template-columns: 1fr; }
}
</style>
