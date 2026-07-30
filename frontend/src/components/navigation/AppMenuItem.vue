<script setup lang="ts">
import { computed } from 'vue'
import type { MenuItem } from '@/types/api'

interface TreeMenu extends MenuItem {
  children?: TreeMenu[]
}

const props = defineProps<{ item: TreeMenu }>()
const routeTarget = computed(() => props.item.routePath || `/coming-soon/${props.item.id}`)
</script>

<template>
  <el-sub-menu v-if="item.children?.length" :index="String(item.id)">
    <template #title>
      <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
      <span>{{ item.menuName }}</span>
    </template>
    <app-menu-item v-for="child in item.children" :key="child.id" :item="child" />
  </el-sub-menu>
  <el-menu-item v-else :index="routeTarget">
    <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
    <template #title>{{ item.menuName }}</template>
  </el-menu-item>
</template>
