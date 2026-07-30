<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { systemApi, type RoleRow } from '@/api/system'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'
import type { MenuItem } from '@/types/api'

interface MenuTree extends MenuItem {
  children?: MenuTree[]
}

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editing = ref<RoleRow | null>(null)
const rows = ref<RoleRow[]>([])
const menus = ref<MenuItem[]>([])
const menuTree = ref<MenuTree[]>([])
const treeRef = ref()
const formRef = ref<FormInstance>()
const form = reactive({
  roleCode: '',
  roleName: '',
  dataScope: 'ALL',
  enabled: true,
  sortOrder: 0,
  remark: '',
})

const scopeOptions = [
  { value: 'ALL', label: '全部数据' },
  { value: 'FACTORY', label: '本工厂数据' },
  { value: 'WORKSHOP', label: '本车间数据' },
  { value: 'TEAM', label: '本班组数据' },
  { value: 'RESPONSIBLE_EQUIPMENT', label: '本人负责设备' },
  { value: 'SELF_TASK', label: '本人任务' },
]

onMounted(async () => {
  await load()
})

async function load() {
  loading.value = true
  try {
    const [roleRows, menuRows] = await Promise.all([systemApi.roles(), systemApi.menus()])
    rows.value = roleRows
    menus.value = menuRows
    menuTree.value = buildTree(menuRows)
  } catch (error) {
    ElMessage.error(errorMessage(error, '角色数据加载失败'))
  } finally {
    loading.value = false
  }
}

function buildTree(items: MenuItem[]): MenuTree[] {
  const map = new Map<number, MenuTree>()
  items.forEach((item) => map.set(item.id, { ...item, children: [] }))
  const roots: MenuTree[] = []
  map.forEach((item) => {
    const parent = map.get(item.parentId)
    if (parent) parent.children?.push(item)
    else roots.push(item)
  })
  return roots
}

function openDialog(row?: RoleRow) {
  editing.value = row || null
  Object.assign(form, row
    ? {
        roleCode: row.roleCode,
        roleName: row.roleName,
        dataScope: row.dataScope,
        enabled: row.status === 1,
        sortOrder: row.sortOrder,
        remark: row.remark || '',
      }
    : { roleCode: '', roleName: '', dataScope: 'ALL', enabled: true, sortOrder: 0, remark: '' })
  dialogVisible.value = true
  requestAnimationFrame(() => treeRef.value?.setCheckedKeys(row?.menuIds || []))
}

async function save() {
  await formRef.value?.validate()
  saving.value = true
  const data = {
    ...form,
    menuIds: treeRef.value?.getCheckedKeys(false) || [],
    version: editing.value?.version,
  }
  try {
    if (editing.value) await systemApi.updateRole(editing.value.id, data)
    else await systemApi.createRole(data)
    ElMessage.success(editing.value ? '角色已更新' : '角色已创建')
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

function scopeLabel(value: string) {
  return scopeOptions.find((item) => item.value === value)?.label || value
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div><h1>角色管理</h1><p>按岗位职责配置菜单、按钮与数据范围。</p></div>
      <div class="page-actions">
        <el-button v-if="auth.can('system:role:create')" type="primary" :icon="Plus" @click="openDialog()">新增角色</el-button>
      </div>
    </header>
    <section class="surface-card table-card">
      <div class="table-toolbar"><span class="table-title">角色列表</span><el-tag effect="plain">{{ rows.length }} 个角色</el-tag></div>
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="roleCode" label="角色编码" min-width="170"><template #default="{ row }"><span class="mono">{{ row.roleCode }}</span></template></el-table-column>
        <el-table-column prop="roleName" label="角色名称" min-width="150" />
        <el-table-column label="数据范围" min-width="150"><template #default="{ row }">{{ scopeLabel(row.dataScope) }}</template></el-table-column>
        <el-table-column label="权限点" width="110" align="center"><template #default="{ row }">{{ row.menuIds.length }}</template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column prop="remark" label="说明" min-width="200" show-overflow-tooltip />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }"><el-button v-if="auth.can('system:role:update')" link type="primary" @click="openDialog(row)">编辑授权</el-button></template>
        </el-table-column>
        <template #empty><el-empty description="暂无角色数据" /></template>
      </el-table>
    </section>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑角色与授权' : '新增角色'" width="min(800px, 94vw)">
      <div class="role-editor">
        <el-form ref="formRef" :model="form" label-position="top">
          <el-form-item label="角色编码" prop="roleCode" :rules="[{ required: true, message: '请输入角色编码' }]">
            <el-input v-model="form.roleCode" :disabled="Boolean(editing)" placeholder="例如 WORKSHOP_MANAGER" />
          </el-form-item>
          <el-form-item label="角色名称" prop="roleName" :rules="[{ required: true, message: '请输入角色名称' }]">
            <el-input v-model="form.roleName" />
          </el-form-item>
          <el-form-item label="数据范围"><el-select v-model="form.dataScope" style="width: 100%"><el-option v-for="item in scopeOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
          <el-form-item label="排序"><el-input-number v-model="form.sortOrder" :min="0" :max="999" /></el-form-item>
          <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
          <el-form-item label="说明"><el-input v-model="form.remark" type="textarea" :rows="3" /></el-form-item>
        </el-form>
        <div class="permission-panel">
          <div class="permission-title"><strong>菜单与操作权限</strong><span>勾选后端实际校验的权限点</span></div>
          <el-scrollbar height="390px">
            <el-tree ref="treeRef" :data="menuTree" node-key="id" show-checkbox default-expand-all :props="{ label: 'menuName', children: 'children' }">
              <template #default="{ data }">
                <span class="tree-node"><span>{{ data.menuName }}</span><small v-if="data.permissionCode">{{ data.permissionCode }}</small></span>
              </template>
            </el-tree>
          </el-scrollbar>
        </div>
      </div>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存授权</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.role-editor { display: grid; grid-template-columns: 1fr 1.18fr; gap: 24px; }
.permission-panel { padding: 16px; border: 1px solid var(--tpm-border); border-radius: 9px; }
.permission-title { display: flex; flex-direction: column; margin-bottom: 12px; }
.permission-title span { margin-top: 3px; color: var(--tpm-text-secondary); font-size: 11px; }
.tree-node { display: flex; align-items: center; gap: 10px; }
.tree-node small { color: #89959d; font: 10px "SFMono-Regular", Consolas, monospace; }
@media (max-width: 700px) { .role-editor { grid-template-columns: 1fr; } }
</style>
