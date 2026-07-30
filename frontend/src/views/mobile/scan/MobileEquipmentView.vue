<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { mobileApi, type MobileEquipmentContext } from '@/api/mobile'
import { errorMessage } from '@/utils/http'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const context = ref<MobileEquipmentContext | null>(null)

onMounted(async () => {
  loading.value = true
  try {
    context.value = await mobileApi.equipment(String(route.params.token))
  } catch (error) {
    ElMessage.error(errorMessage(error, '设备二维码无效或无权访问'))
  } finally {
    loading.value = false
  }
})

function dateTime(value?: string): string {
  return value ? value.replace('T', ' ').slice(0, 16) : '—'
}
</script>

<template>
  <div v-loading="loading" class="equipment-page">
    <template v-if="context">
      <section class="equipment-hero">
        <div class="status-dot" :style="{ background: context.equipment.statusColor }"></div>
        <p class="mono">{{ context.equipment.equipmentCode }}</p>
        <h1>{{ context.equipment.equipmentName }}</h1>
        <el-tag :color="context.equipment.statusColor" effect="dark">
          {{ context.equipment.statusName }}
        </el-tag>
      </section>

      <section class="detail-card">
        <div><span>设备分类</span><strong>{{ context.equipment.categoryName }}</strong></div>
        <div><span>所属组织</span><strong>{{ context.equipment.organizationName }}</strong></div>
        <div><span>安装位置</span><strong>{{ context.equipment.locationName }}</strong></div>
        <div><span>设备负责人</span><strong>{{ context.equipment.responsibleName || '未设置' }}</strong></div>
        <div><span>状态开始</span><strong>{{ dateTime(context.equipment.statusSince) }}</strong></div>
      </section>

      <section class="task-section">
        <div class="section-head"><div><h2>可执行任务</h2><p>仅展示分派给你的未关闭任务</p></div><b>{{ context.activeTasks.length }}</b></div>
        <button
          v-for="task in context.activeTasks"
          :key="`${task.workflowType}-${task.taskId}`"
          type="button"
          class="task-link"
          @click="router.push(task.routePath)"
        >
          <span class="task-type" :class="task.workflowType.toLowerCase()">
            {{ task.workflowType === 'INSPECTION' ? '点检' : '维保' }}
          </span>
          <div><strong>{{ task.taskCode }}</strong><p>{{ task.schemeName }}</p><small>截止 {{ dateTime(task.dueTime) }}</small></div>
          <el-icon><ArrowRight /></el-icon>
        </button>
        <el-empty v-if="!context.activeTasks.length" description="该设备当前没有分派给你的任务" />
      </section>
    </template>
    <el-result v-else-if="!loading" icon="warning" title="无法查看设备" sub-title="二维码可能失效，或设备不在你的数据权限范围内">
      <template #extra><el-button type="primary" @click="router.replace('/mobile/scan')">重新扫码</el-button></template>
    </el-result>
  </div>
</template>

<style scoped>
.equipment-page { display: grid; gap: 16px; }
.equipment-hero, .detail-card, .task-section { padding: 20px; border-radius: 20px; background: white; box-shadow: 0 8px 24px rgba(23, 58, 69, .07); }
.equipment-hero { position: relative; overflow: hidden; }
.equipment-hero::after { position: absolute; right: -30px; bottom: -44px; width: 140px; height: 140px; border-radius: 50%; background: #e7f5f7; content: ""; }
.status-dot { width: 10px; height: 10px; margin-bottom: 16px; border-radius: 50%; box-shadow: 0 0 0 6px rgba(15, 115, 137, .08); }
.equipment-hero p, .equipment-hero h1 { margin: 0; }
.equipment-hero h1 { margin: 5px 0 14px; font-size: 24px; }
.detail-card { display: grid; gap: 15px; }
.detail-card div { display: flex; justify-content: space-between; gap: 18px; }
.detail-card span { color: #7b8c93; font-size: 13px; }
.detail-card strong { text-align: right; font-size: 14px; }
.section-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.section-head h2, .section-head p { margin: 0; }
.section-head p { margin-top: 4px; color: #85949a; font-size: 12px; }
.section-head b { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 50%; color: #08718a; background: #e9f6f8; }
.task-link { display: grid; width: 100%; grid-template-columns: auto 1fr auto; align-items: center; gap: 12px; padding: 14px 0; border: 0; border-top: 1px solid #edf1f2; text-align: left; background: transparent; }
.task-type { padding: 7px; border-radius: 10px; color: #0b7189; background: #e9f7f9; font-size: 12px; }
.task-type.maintenance { color: #a25b10; background: #fff3e5; }
.task-link p, .task-link small { margin: 3px 0 0; color: #7d8d94; font-size: 12px; }
</style>
