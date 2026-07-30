<script setup lang="ts">
import { FullScreen, Refresh } from '@element-plus/icons-vue'
import type { OrganizationRow } from '@/api/masterData'

defineProps<{
  title: string
  subtitle: string
  generatedAt?: string
  organizations: OrganizationRow[]
  loading: boolean
}>()

const startDate = defineModel<string>('startDate', { required: true })
const endDate = defineModel<string>('endDate', { required: true })
const organizationId = defineModel<number | undefined>('organizationId')
const emit = defineEmits<{ refresh: []; fullscreen: [] }>()
</script>

<template>
  <header class="dashboard-header">
    <div class="brand">
      <span class="eyebrow">LEAN TPM · VISUAL COMMAND CENTER</span>
      <h1>{{ title }}</h1>
      <p>{{ subtitle }}</p>
    </div>
    <div class="dashboard-actions">
      <div class="filters">
        <el-date-picker v-model="startDate" type="date" value-format="YYYY-MM-DD" />
        <span>—</span>
        <el-date-picker v-model="endDate" type="date" value-format="YYYY-MM-DD" />
        <el-select
          v-model="organizationId"
          clearable
          filterable
          placeholder="全部组织"
          style="width: 180px"
        >
          <el-option
            v-for="item in organizations"
            :key="item.id"
            :label="item.organizationName"
            :value="item.id"
          />
        </el-select>
      </div>
      <div class="buttons">
        <span class="updated">更新 {{ generatedAt ? new Date(generatedAt).toLocaleTimeString() : '—' }}</span>
        <el-button :icon="Refresh" :loading="loading" @click="emit('refresh')">刷新</el-button>
        <el-button :icon="FullScreen" @click="emit('fullscreen')">全屏</el-button>
      </div>
    </div>
  </header>
</template>

<style scoped>
.dashboard-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  padding: 18px 22px;
  border: 1px solid rgba(82, 178, 255, 0.22);
  border-radius: 18px;
  background: linear-gradient(120deg, rgba(10, 28, 51, 0.96), rgba(7, 17, 31, 0.94));
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.25);
}
.eyebrow { color: #55d6ff; font-size: 11px; letter-spacing: 0.18em; }
h1 { margin: 5px 0 3px; color: #f4fbff; font-size: clamp(24px, 2.4vw, 38px); }
p { margin: 0; color: #8ba8bf; }
.dashboard-actions { display: grid; justify-items: end; gap: 10px; }
.filters, .buttons { display: flex; align-items: center; gap: 8px; }
.updated { color: #7390a8; font-size: 12px; margin-right: 4px; }
@media (max-width: 1180px) {
  .dashboard-header { align-items: flex-start; flex-direction: column; }
  .dashboard-actions { justify-items: start; width: 100%; }
  .filters { flex-wrap: wrap; }
}
</style>
