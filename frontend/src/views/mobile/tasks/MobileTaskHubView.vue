<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useMobileStore } from '@/stores/mobile'

const router = useRouter()
const auth = useAuthStore()
const mobile = useMobileStore()

const workflows = computed(() => [
  {
    name: '设备点检',
    description: '逐项检查、数值记录、拍照和异常上报',
    icon: 'CircleCheck',
    path: '/mobile/inspection',
    data: mobile.bootstrap?.inspection,
    show: auth.can('inspection:my-task:view'),
  },
  {
    name: '设备维保',
    description: '开始、暂停、逐项保养、备件和前后照片',
    icon: 'Tools',
    path: '/mobile/maintenance',
    data: mobile.bootstrap?.maintenance,
    show: auth.can('maintenance:my-task:view'),
  },
].filter((item) => item.show))
</script>

<template>
  <div class="task-hub">
    <header><p>FIELD TASKS</p><h1>现场任务</h1><span>大按钮、少层级，适合单手完成作业。</span></header>
    <button
      v-for="workflow in workflows"
      :key="workflow.path"
      type="button"
      class="workflow-card"
      @click="router.push(workflow.path)"
    >
      <div class="workflow-icon"><el-icon><component :is="workflow.icon" /></el-icon></div>
      <div class="workflow-copy"><h2>{{ workflow.name }}</h2><p>{{ workflow.description }}</p></div>
      <div class="workflow-metrics">
        <div><b>{{ workflow.data?.dueToday ?? 0 }}</b><span>今日</span></div>
        <div><b>{{ workflow.data?.pending ?? 0 }}</b><span>待完成</span></div>
        <div class="danger"><b>{{ workflow.data?.overdue ?? 0 }}</b><span>逾期</span></div>
      </div>
      <el-icon class="arrow"><ArrowRight /></el-icon>
    </button>
    <el-empty v-if="!workflows.length" description="当前账号没有现场任务权限" />
  </div>
</template>

<style scoped>
.task-hub { display: grid; gap: 16px; }
header { padding: 4px 4px 8px; }
header p, header h1, header span { margin: 0; }
header p { color: #16839a; font-size: 11px; font-weight: 800; letter-spacing: .14em; }
header h1 { margin: 5px 0; font-size: 26px; }
header span { color: #74868e; font-size: 13px; }
.workflow-card {
  position: relative; display: grid; grid-template-columns: 54px 1fr auto;
  align-items: start; gap: 14px; padding: 20px; border: 0; border-radius: 20px;
  text-align: left; color: inherit; background: white;
  box-shadow: 0 8px 26px rgba(23, 58, 69, .08);
}
.workflow-icon { display: grid; width: 54px; height: 54px; place-items: center; border-radius: 17px; color: #08718a; background: #e8f6f8; font-size: 26px; }
.workflow-copy h2, .workflow-copy p { margin: 0; }
.workflow-copy p { margin-top: 6px; color: #7b8d94; line-height: 1.55; font-size: 12px; }
.workflow-metrics { grid-column: 1 / -1; display: grid; grid-template-columns: repeat(3, 1fr); padding-top: 16px; border-top: 1px solid #edf1f2; }
.workflow-metrics div { display: grid; text-align: center; }
.workflow-metrics b { font-size: 22px; }
.workflow-metrics span { color: #86969c; font-size: 11px; }
.workflow-metrics .danger b { color: #d94d4d; }
.arrow { align-self: center; color: #95a4aa; }
</style>
