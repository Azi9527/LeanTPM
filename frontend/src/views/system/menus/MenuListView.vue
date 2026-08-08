<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { systemApi } from '@/api/system'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'
import { menuTypeLabel, permissionCodeLabel } from '@/utils/permission-labels'
import type { MenuItem } from '@/types/api'

interface MenuTree extends MenuItem { children?: MenuTree[] }
const loading = ref(false)
const rows = ref<MenuTree[]>([])
const statusLoading = ref(new Set<number>())
const auth = useAuthStore()

async function loadRows() {
  loading.value = true
  try {
    const menus = await systemApi.menus()
    const map = new Map<number, MenuTree>()
    menus.forEach((item) => map.set(item.id, { ...item, children: [] }))
    const roots: MenuTree[] = []
    map.forEach((item) => {
      const parent = map.get(item.parentId)
      if (parent) parent.children?.push(item)
      else roots.push(item)
    })
    rows.value = roots
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function toggleStatus(row: MenuTree, value: string | number | boolean) {
  const enabled = Boolean(value)
  statusLoading.value.add(row.id)
  try {
    const response = await systemApi.updateMenuStatus(row.id, enabled)
    ElMessage.success(`${enabled ? '已启用' : '已停用'}“${row.menuName}”及其 ${Math.max(0, response.data.data.affectedCount - 1)} 个下级菜单`)
    await Promise.all([loadRows(), auth.loadProfile()])
  } catch (error) {
    row.status = enabled ? 0 : 1
    ElMessage.error(errorMessage(error))
  } finally {
    statusLoading.value.delete(row.id)
  }
}

onMounted(loadRows)
</script>

<template>
  <div class="page-shell">
    <header class="page-header"><div><h1>菜单权限</h1><p>以中文查看系统目录、页面菜单和操作权限；技术编码保留用于系统鉴权。</p></div></header>
    <el-alert title="第一阶段菜单为受控基础配置；新增业务页面时，应同步通过数据库迁移增加菜单和权限点。" type="info" show-icon :closable="false" />
    <section class="surface-card table-card">
      <div class="table-toolbar"><span class="table-title">权限资源树</span></div>
      <el-table v-loading="loading" :data="rows" row-key="id" default-expand-all>
        <el-table-column prop="menuName" label="名称" min-width="210" />
        <el-table-column label="类型" width="120"><template #default="{ row }"><el-tag size="small" effect="plain">{{ menuTypeLabel(row.menuType) }}</el-tag></template></el-table-column>
        <el-table-column prop="routePath" label="路由" min-width="180"><template #default="{ row }"><span class="mono">{{ row.routePath || '—' }}</span></template></el-table-column>
        <el-table-column prop="permissionCode" label="权限编码（中文）" min-width="260">
          <template #default="{ row }">
            <span class="permission-code"><strong>{{ permissionCodeLabel(row.permissionCode) }}</strong><small v-if="row.permissionCode">{{ row.permissionCode }}</small></span>
          </template>
        </el-table-column>
        <el-table-column prop="sortOrder" label="排序" width="80" />
        <el-table-column label="启用状态" width="150" fixed="right">
          <template #default="{ row }">
            <el-switch
              v-if="auth.can('system:menu:manage')"
              :model-value="row.status === 1"
              :loading="statusLoading.has(row.id)"
              inline-prompt
              active-text="启用"
              inactive-text="停用"
              @change="toggleStatus(row, $event)"
            />
            <el-tag v-else :type="row.status === 1 ? 'success' : 'info'" effect="plain">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<style scoped>
.permission-code { display: grid; gap: 2px; }
.permission-code strong { color: var(--tpm-text-primary); font-weight: 500; }
.permission-code small { color: var(--tpm-text-secondary); font: 10px "SFMono-Regular", Consolas, monospace; }
</style>
