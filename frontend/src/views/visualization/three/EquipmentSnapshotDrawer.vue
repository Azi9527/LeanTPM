<script setup lang="ts">
import type { EquipmentSnapshot } from '@/api/visualization'

defineProps<{ snapshot?: EquipmentSnapshot; loading: boolean }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ detail: [id: number] }>()

function percent(value?: number) {
  return value == null ? '—' : `${(Number(value) * 100).toFixed(1)}%`
}
function duration(seconds: number) {
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  return hours ? `${hours} 小时 ${minutes} 分` : `${minutes} 分钟`
}
</script>

<template>
  <el-drawer v-model="visible" size="430px" class="snapshot-drawer">
    <template #header>
      <div v-if="snapshot" class="drawer-title">
        <span :style="{ color: snapshot.statusColor }">● {{ snapshot.statusName }}</span>
        <h2>{{ snapshot.equipmentName }}</h2>
        <small>{{ snapshot.equipmentCode }}</small>
      </div>
    </template>
    <div v-loading="loading">
      <template v-if="snapshot">
        <section class="facts">
          <div><span>分类</span><b>{{ snapshot.categoryName }}</b></div>
          <div><span>组织 / 位置</span><b>{{ snapshot.organizationName }} / {{ snapshot.locationName }}</b></div>
          <div><span>状态持续</span><b>{{ duration(snapshot.statusDurationSeconds) }}</b></div>
          <div><span>责任人</span><b>{{ snapshot.responsibleName || '未配置' }}</b></div>
        </section>
        <section class="snapshot-metrics">
          <div><span>今日运行</span><b>{{ Number(snapshot.todayRunMinutes).toFixed(0) }} min</b></div>
          <div><span>今日停机</span><b>{{ Number(snapshot.todayStopMinutes).toFixed(0) }} min</b></div>
          <div><span>今日 OEE</span><b>{{ percent(snapshot.todayOee) }}</b></div>
          <div><span>开放异常</span><b>{{ snapshot.openAbnormalCount }}</b></div>
          <div><span>点检完成</span><b>{{ snapshot.todayInspectionCompleted }}/{{ snapshot.todayInspectionDue }}</b></div>
          <div><span>维保完成</span><b>{{ snapshot.todayMaintenanceCompleted }}/{{ snapshot.todayMaintenanceDue }}</b></div>
        </section>
        <h3>最近活动</h3>
        <el-timeline>
          <el-timeline-item
            v-for="event in snapshot.recentEvents"
            :key="`${event.eventType}-${event.eventCode}-${event.eventTime}`"
            :timestamp="new Date(event.eventTime).toLocaleString()"
          >
            <b>{{ event.eventType }} · {{ event.eventCode }}</b>
            <p>{{ event.eventStatus }} {{ event.description || '' }}</p>
          </el-timeline-item>
        </el-timeline>
        <el-button type="primary" class="detail-button" @click="emit('detail', snapshot.equipmentId)">
          进入设备完整台账
        </el-button>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer-title h2 { margin: 5px 0 2px; } .drawer-title small { color: #7890a4; }
.facts { display: grid; gap: 10px; padding: 13px; border-radius: 12px; background: #f4f7fa; }
.facts div { display: flex; justify-content: space-between; gap: 20px; }
.facts span, .snapshot-metrics span { color: #728296; }
.snapshot-metrics { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin: 14px 0 20px; }
.snapshot-metrics div { display: grid; gap: 5px; padding: 12px; border: 1px solid #e4ebf1; border-radius: 10px; }
.snapshot-metrics b { color: #0f7490; font-size: 18px; }
h3 { margin: 16px 0; }
p { margin: 3px 0; color: #728296; }
.detail-button { width: 100%; }
</style>
