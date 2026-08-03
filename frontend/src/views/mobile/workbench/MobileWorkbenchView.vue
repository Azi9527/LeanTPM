<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useMobileStore } from '@/stores/mobile'

const router = useRouter()
const auth = useAuthStore()
const mobile = useMobileStore()

const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 11) return '早上好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

const cards = computed(() => [
  {
    type: '点检',
    icon: 'CircleCheck',
    color: 'var(--tpm-primary)',
    background: 'var(--tpm-primary-soft)',
    data: mobile.bootstrap?.inspection,
    path: '/mobile/inspection',
    show: auth.can('inspection:my-task:view'),
  },
  {
    type: '维保',
    icon: 'Tools',
    color: 'var(--tpm-secondary)',
    background: 'var(--tpm-secondary-soft)',
    data: mobile.bootstrap?.maintenance,
    path: '/mobile/maintenance',
    show: auth.can('maintenance:my-task:view'),
  },
].filter((item) => item.show))

const today = computed(() => cards.value.reduce((summary, card) => ({
  due: summary.due + (card.data?.dueToday ?? 0),
  completed: summary.completed + (card.data?.completedToday ?? 0),
  overdue: summary.overdue + (card.data?.overdue ?? 0),
}), { due: 0, completed: 0, overdue: 0 }))
const completionRate = computed(() => today.value.due
  ? Math.min(100, Math.round(today.value.completed * 100 / today.value.due))
  : 100)
</script>

<template>
  <div v-loading="mobile.loading" class="mobile-page">
    <section class="welcome-card">
      <p>{{ greeting }}</p>
      <h1>{{ auth.displayName }}</h1>
      <span>今天的现场任务已为你汇总</span>
    </section>

    <section class="today-board">
      <div><span>今日完成度</span><strong>{{ completionRate }}%</strong></div>
      <el-progress :percentage="completionRate" :stroke-width="10" :show-text="false" />
      <div class="board-metrics"><span>今日 {{ today.due }}</span><span>已完成 {{ today.completed }}</span><span class="danger">逾期 {{ today.overdue }}</span></div>
    </section>

    <section class="quick-grid">
      <button type="button" @click="router.push('/mobile/scan')">
        <el-icon><FullScreen /></el-icon><strong>扫码作业</strong><span>识别设备二维码</span>
      </button>
      <button type="button" @click="router.push('/mobile/tasks')">
        <el-icon><Finished /></el-icon><strong>现场任务</strong><span>点检与维保</span>
      </button>
      <button type="button" @click="router.push('/mobile/messages')">
        <el-icon><Bell /></el-icon><strong>异常消息</strong><span>{{ mobile.messages.length }} 条待关注</span>
      </button>
      <button type="button" @click="router.push('/mobile/profile')">
        <el-icon><Document /></el-icon><strong>本地队列</strong><span>{{ mobile.draftCount }} 份草稿 · {{ mobile.queuedPhotoCount }} 张照片</span>
      </button>
    </section>

    <section class="section-title"><div><h2>我的任务</h2><p>聚焦今日、逾期与待完成事项</p></div></section>
    <article
      v-for="card in cards"
      :key="card.type"
      class="work-card"
      @click="router.push(card.path)"
    >
      <div class="work-icon" :style="{ background: card.background, color: card.color }">
        <el-icon><component :is="card.icon" /></el-icon>
      </div>
      <div class="work-copy"><strong>{{ card.type }}任务</strong><span>今日 {{ card.data?.dueToday ?? 0 }} 项</span></div>
      <div class="work-numbers">
        <div><b>{{ card.data?.pending ?? 0 }}</b><span>待完成</span></div>
        <div class="danger"><b>{{ card.data?.overdue ?? 0 }}</b><span>已逾期</span></div>
        <div><b>{{ card.data?.completedToday ?? 0 }}</b><span>今日完成</span></div>
      </div>
      <el-icon class="arrow"><ArrowRight /></el-icon>
    </article>
  </div>
</template>

<style scoped>
.mobile-page { display: grid; gap: 16px; }
.welcome-card {
  padding: 22px; border-radius: 20px; color: white;
  background: linear-gradient(135deg, var(--tpm-sidebar), var(--tpm-primary));
  box-shadow: 0 14px 34px rgba(var(--tpm-primary-rgb), .22);
}
.welcome-card p, .welcome-card h1 { margin: 0; }
.welcome-card h1 { margin: 4px 0 10px; font-size: 26px; }
.welcome-card span { opacity: .78; font-size: 13px; }
.quick-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
.today-board { display: grid; gap: 10px; padding: 18px; border-radius: 18px; background: white; box-shadow: 0 6px 20px rgba(23, 58, 69, .07); }
.today-board > div:first-child { display: flex; align-items: center; justify-content: space-between; }
.today-board strong { color: var(--tpm-primary); font-size: 24px; }
.board-metrics { display: flex; justify-content: space-between; color: #71838b; font-size: 12px; }
.board-metrics .danger { color: var(--tpm-danger); }
.quick-grid button {
  display: grid; min-height: 112px; padding: 16px; text-align: left;
  border: 0; border-radius: 18px; color: #213b47; background: white;
  box-shadow: 0 6px 20px rgba(23, 58, 69, .07);
}
.quick-grid .el-icon { margin-bottom: 10px; color: var(--tpm-primary); font-size: 26px; }
.quick-grid span { margin-top: 3px; color: #7c8e95; font-size: 12px; }
.section-title h2, .section-title p { margin: 0; }
.section-title p { margin-top: 3px; color: #7b8b92; font-size: 12px; }
.work-card {
  position: relative; display: grid; grid-template-columns: 48px 1fr auto;
  align-items: center; gap: 12px; padding: 16px;
  border-radius: 18px; background: white;
  box-shadow: 0 6px 20px rgba(23, 58, 69, .07);
}
.work-icon { display: grid; width: 48px; height: 48px; place-items: center; border-radius: 15px; font-size: 24px; }
.work-copy { display: grid; gap: 4px; }
.work-copy span { color: #819097; font-size: 12px; }
.work-numbers { grid-column: 1 / -1; display: grid; grid-template-columns: repeat(3, 1fr); padding-top: 12px; border-top: 1px solid #edf1f2; }
.work-numbers div { display: grid; text-align: center; }
.work-numbers b { font-size: 20px; }
.work-numbers span { color: #87969c; font-size: 11px; }
.work-numbers .danger b { color: var(--tpm-danger); }
.arrow { color: #9aa8ad; }
</style>
