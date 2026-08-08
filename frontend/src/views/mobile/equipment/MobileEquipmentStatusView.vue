<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { equipmentApi, type EquipmentRow } from '@/api/equipment'
import { errorMessage } from '@/utils/http'
import { ElMessage } from 'element-plus'
import { EQUIPMENT_STATUS_OPTIONS, equipmentStatusLabel } from '@/utils/equipment-status'

const route = useRoute()
const rows = ref<EquipmentRow[]>([])
const loading = ref(false)
const status = ref(typeof route.query.status === 'string' ? route.query.status : '')
const statusOptions = [
  { label: '全部状态', value: '' },
  ...EQUIPMENT_STATUS_OPTIONS,
]

async function load() {
  loading.value = true
  try {
    const result = await equipmentApi.page({
      currentStatusCode: status.value || undefined,
      page: 1,
      pageSize: 200,
    })
    rows.value = result.records
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="mobile-page" v-loading="loading">
    <header class="page-heading"><div><h1>设备状态</h1><p>全厂设备只读状态清单</p></div><b>{{ rows.length }}</b></header>
    <el-segmented v-model="status" :options="statusOptions" class="status-filter" @change="load" />
    <section class="equipment-list">
      <article v-for="row in rows" :key="row.id" class="equipment-card">
        <header><div><strong>{{ row.equipmentName }}</strong><span>{{ row.equipmentCode }}</span></div><el-tag>{{ equipmentStatusLabel(row.currentStatusCode) }}</el-tag></header>
        <p>{{ row.organizationName }} · {{ row.locationName }}</p>
        <small>负责人：{{ row.primaryResponsibleName || '未设置' }}</small>
      </article>
      <el-empty v-if="!rows.length" description="暂无设备" />
    </section>
  </div>
</template>

<style scoped>
.mobile-page { display: grid; gap: 14px; }
.page-heading { display: flex; align-items: center; justify-content: space-between; }
.page-heading h1, .page-heading p { margin: 0; }
.page-heading p { margin-top: 4px; color: #839198; font-size: 12px; }
.page-heading b { color: var(--tpm-primary); font-size: 28px; }
.status-filter { overflow-x: auto; }
.equipment-list { display: grid; gap: 10px; }
.equipment-card { padding: 15px; border-radius: 16px; background: white; box-shadow: 0 5px 18px rgba(23, 58, 69, .07); }
.equipment-card header { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.equipment-card header div { display: grid; gap: 3px; }
.equipment-card header span, .equipment-card p, .equipment-card small { color: #7c8c93; font-size: 12px; }
.equipment-card p { margin: 12px 0 5px; }
</style>
