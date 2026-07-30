<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { systemApi } from '@/api/system'
import { errorMessage } from '@/utils/http'
import type { MenuItem } from '@/types/api'

interface MenuTree extends MenuItem { children?: MenuTree[] }
const loading = ref(false)
const rows = ref<MenuTree[]>([])

onMounted(async () => {
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
})
</script>

<template>
  <div class="page-shell">
    <header class="page-header"><div><h1>菜单权限</h1><p>查看系统菜单、页面和按钮权限编码。菜单结构由后端统一下发。</p></div></header>
    <el-alert title="第一阶段菜单为受控基础配置；新增业务页面时，应同步通过数据库迁移增加菜单和权限点。" type="info" show-icon :closable="false" />
    <section class="surface-card table-card">
      <div class="table-toolbar"><span class="table-title">权限资源树</span></div>
      <el-table v-loading="loading" :data="rows" row-key="id" default-expand-all>
        <el-table-column prop="menuName" label="名称" min-width="210" />
        <el-table-column label="类型" width="110"><template #default="{ row }"><el-tag size="small" effect="plain">{{ row.menuType }}</el-tag></template></el-table-column>
        <el-table-column prop="routePath" label="路由" min-width="180"><template #default="{ row }"><span class="mono">{{ row.routePath || '—' }}</span></template></el-table-column>
        <el-table-column prop="permissionCode" label="权限编码" min-width="230"><template #default="{ row }"><span class="mono">{{ row.permissionCode || '—' }}</span></template></el-table-column>
        <el-table-column prop="sortOrder" label="排序" width="80" />
      </el-table>
    </section>
  </div>
</template>
