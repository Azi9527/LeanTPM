<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { systemApi, type OrganizationNode, type RoleRow, type UserRow } from '@/api/system'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editing = ref<UserRow | null>(null)
const formRef = ref<FormInstance>()
const rows = ref<UserRow[]>([])
const roles = ref<RoleRow[]>([])
const organizations = ref<OrganizationNode[]>([])
const total = ref(0)
const query = reactive({ keyword: '', status: undefined as number | undefined, page: 1, pageSize: 20 })
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
        else if (value && value.length < 10) callback(new Error('密码至少 10 位'))
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
  await Promise.all([load(), loadRoles(), loadOrganizations()])
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
  if (!auth.can('system:role:view')) return
  roles.value = await systemApi.roles()
}

async function loadOrganizations() {
  try {
    organizations.value = await systemApi.organizations()
  } catch (error) {
    ElMessage.error(errorMessage(error, '组织数据加载失败'))
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
    await load()
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
        inputPlaceholder: '至少 10 位，包含字母、数字和特殊字符',
        inputValidator: (value) =>
          /^(?=.*[A-Za-z])(?=.*\d)(?=.*[^A-Za-z\d]).{10,}$/.test(value) || '密码强度不符合要求',
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

const pageSummary = computed(() => `共 ${total.value} 个用户`)
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div><h1>用户管理</h1><p>维护人员账号、使用范围和业务角色。</p></div>
      <div class="page-actions">
        <el-button v-if="auth.can('system:user:create')" type="primary" :icon="Plus" @click="openCreate">新增用户</el-button>
      </div>
    </header>

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
          <span class="field-hint">至少 10 位，包含字母、数字和特殊字符</span>
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
.role-tag { margin: 2px 4px 2px 0; }
.user-form {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 18px;
}
.full-width { grid-column: 1 / -1; }
.field-hint { margin-top: 4px; color: var(--tpm-text-secondary); font-size: 11px; }
@media (max-width: 600px) {
  .user-form { grid-template-columns: 1fr; }
  .full-width { grid-column: auto; }
}
</style>
