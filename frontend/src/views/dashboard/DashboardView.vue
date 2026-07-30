<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { systemApi } from '@/api/system'

const auth = useAuthStore()
const userCount = ref<number | null>(null)
const roleCount = ref<number | null>(null)
const loading = ref(true)
const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 11) return '早上好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

onMounted(async () => {
  try {
    const tasks: Promise<unknown>[] = []
    if (auth.can('system:user:view')) {
      tasks.push(systemApi.users({ page: 1, pageSize: 1 }).then((page) => (userCount.value = page.total)))
    }
    if (auth.can('system:role:view')) {
      tasks.push(systemApi.roles().then((roles) => (roleCount.value = roles.length)))
    }
    await Promise.all(tasks)
  } finally {
    loading.value = false
  }
})

const stages = [
  { name: '基础平台', detail: '认证、权限、字典、日志、附件', status: '已建设', progress: 100 },
  { name: '设备基础', detail: '组织、位置、分类、台账、状态', status: '下一阶段', progress: 0 },
  { name: '点检维保', detail: '方案、计划、任务、异常闭环', status: '待建设', progress: 0 },
  { name: 'OEE 与大屏', detail: '效率损失与运行可视化', status: '待建设', progress: 0 },
]
</script>

<template>
  <div class="page-shell">
    <section class="welcome-panel">
      <div>
        <p>{{ greeting }}，{{ auth.displayName }}</p>
        <h1>设备管理，从可靠的基础开始</h1>
        <span>当前第一阶段聚焦系统安全、权限与主数据治理能力。</span>
      </div>
      <div class="date-block">
        <strong>{{ new Date().getDate() }}</strong>
        <span>{{ new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long' }) }}</span>
      </div>
    </section>

    <section class="metric-grid" v-loading="loading">
      <article class="metric-card">
        <div class="metric-icon teal"><el-icon><User /></el-icon></div>
        <div><span>系统用户</span><strong>{{ userCount ?? '—' }}</strong><small>真实数据库账户</small></div>
      </article>
      <article class="metric-card">
        <div class="metric-icon amber"><el-icon><Avatar /></el-icon></div>
        <div><span>业务角色</span><strong>{{ roleCount ?? '—' }}</strong><small>RBAC 授权角色</small></div>
      </article>
      <article class="metric-card muted">
        <div class="metric-icon slate"><el-icon><Monitor /></el-icon></div>
        <div><span>设备台账</span><strong>待接入</strong><small>第二阶段建设</small></div>
      </article>
      <article class="metric-card muted">
        <div class="metric-icon slate"><el-icon><TrendCharts /></el-icon></div>
        <div><span>当前 OEE</span><strong>待接入</strong><small>第五阶段建设</small></div>
      </article>
    </section>

    <section class="dashboard-grid">
      <article class="surface-card stage-card">
        <div class="section-heading">
          <div><span>建设路线</span><h2>V1 阶段进展</h2></div>
          <el-tag effect="plain">阶段 1 / 7</el-tag>
        </div>
        <div class="stage-list">
          <div v-for="(stage, index) in stages" :key="stage.name" class="stage-row" :class="{ done: stage.progress === 100 }">
            <span class="stage-index">{{ String(index + 1).padStart(2, '0') }}</span>
            <div class="stage-copy"><strong>{{ stage.name }}</strong><small>{{ stage.detail }}</small></div>
            <span class="stage-status">{{ stage.status }}</span>
          </div>
        </div>
      </article>

      <article class="surface-card capability-card">
        <div class="section-heading">
          <div><span>系统能力</span><h2>第一阶段基线</h2></div>
        </div>
        <div class="capability-list">
          <div><el-icon><CircleCheckFilled /></el-icon><span><strong>身份安全</strong><small>JWT 双令牌与 BCrypt 密码</small></span></div>
          <div><el-icon><CircleCheckFilled /></el-icon><span><strong>访问控制</strong><small>菜单、按钮、接口三级校验</small></span></div>
          <div><el-icon><CircleCheckFilled /></el-icon><span><strong>数据治理</strong><small>字典、审计字段与乐观锁</small></span></div>
          <div><el-icon><CircleCheckFilled /></el-icon><span><strong>过程留痕</strong><small>登录与关键操作日志</small></span></div>
        </div>
        <div class="foundation-note">
          <span>FOUNDATION READY</span>
          <p>设备主数据将在下一阶段沿当前租户、权限和审计基线扩展。</p>
        </div>
      </article>
    </section>
  </div>
</template>

<style scoped lang="scss">
.welcome-panel {
  position: relative;
  display: flex;
  overflow: hidden;
  align-items: center;
  justify-content: space-between;
  min-height: 168px;
  padding: 30px 36px;
  border-radius: 14px;
  color: #fff;
  background:
    radial-gradient(circle at 85% 10%, rgba(55, 206, 224, 0.36), transparent 30%),
    linear-gradient(110deg, #083f56, #0b6d88);

  &::after {
    position: absolute;
    right: 11%;
    width: 230px;
    height: 230px;
    border: 1px solid rgba(255, 255, 255, 0.1);
    border-radius: 50%;
    content: "";
    box-shadow: 0 0 0 46px rgba(255, 255, 255, 0.035);
  }

  p {
    margin: 0 0 8px;
    color: #9ed8e5;
    font-size: 13px;
  }

  h1 {
    margin: 0 0 10px;
    font-size: 28px;
    letter-spacing: -0.025em;
  }

  span {
    color: #c1dde4;
    font-size: 13px;
  }
}

.date-block {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  flex-direction: column;
  min-width: 104px;

  strong {
    font-size: 48px;
    font-weight: 300;
    line-height: 1;
  }
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 15px;
}

.metric-card {
  display: flex;
  align-items: center;
  gap: 15px;
  min-height: 112px;
  padding: 20px;
  border: 1px solid var(--tpm-border);
  border-radius: 12px;
  background: #fff;

  > div:last-child {
    display: grid;
    grid-template-columns: 1fr auto;
    flex: 1;
    gap: 4px 12px;
  }

  span {
    color: var(--tpm-text-secondary);
    font-size: 12px;
  }

  strong {
    grid-row: 1 / span 2;
    grid-column: 2;
    align-self: center;
    font-size: 27px;
  }

  small {
    color: #9aa6ae;
    font-size: 10px;
  }

  &.muted strong {
    color: #84919a;
    font-size: 16px;
  }
}

.metric-icon {
  display: grid;
  flex: 0 0 48px;
  place-items: center;
  width: 48px;
  height: 48px;
  border-radius: 10px;
  font-size: 22px;

  &.teal { color: #0b7894; background: #e3f3f6; }
  &.amber { color: #b87800; background: #fff3d9; }
  &.slate { color: #71808a; background: #edf1f3; }
}

.dashboard-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(320px, 0.8fr);
  gap: 16px;
}

.stage-card,
.capability-card {
  padding: 22px;
}

.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 20px;

  span {
    color: var(--tpm-primary);
    font-size: 10px;
    font-weight: 750;
    letter-spacing: 0.14em;
  }

  h2 {
    margin: 4px 0 0;
    font-size: 18px;
  }
}

.stage-list {
  display: grid;
  gap: 7px;
}

.stage-row {
  display: grid;
  grid-template-columns: 40px 1fr auto;
  align-items: center;
  gap: 10px;
  padding: 13px 15px;
  border: 1px solid var(--tpm-border);
  border-radius: 8px;

  &.done {
    border-color: #addcd0;
    background: #f2fbf8;
  }
}

.stage-index {
  color: #9aa6ae;
  font: 12px "SFMono-Regular", Consolas, monospace;
}

.stage-copy {
  display: flex;
  flex-direction: column;

  strong { font-size: 13px; }
  small { margin-top: 3px; color: var(--tpm-text-secondary); font-size: 11px; }
}

.stage-status {
  color: var(--tpm-text-secondary);
  font-size: 11px;
}

.stage-row.done .stage-status {
  color: var(--tpm-success);
  font-weight: 700;
}

.capability-list {
  display: grid;
  gap: 18px;

  > div {
    display: flex;
    gap: 12px;
  }

  .el-icon {
    margin-top: 2px;
    color: var(--tpm-success);
  }

  span {
    display: flex;
    flex-direction: column;
  }

  strong { font-size: 13px; }
  small { margin-top: 4px; color: var(--tpm-text-secondary); font-size: 11px; }
}

.foundation-note {
  margin-top: 24px;
  padding: 16px;
  border-left: 3px solid var(--tpm-accent);
  background: #f6f8f9;

  span {
    color: var(--tpm-primary);
    font: 700 10px "SFMono-Regular", Consolas, monospace;
    letter-spacing: 0.1em;
  }

  p {
    margin: 6px 0 0;
    color: var(--tpm-text-secondary);
    font-size: 11px;
    line-height: 1.6;
  }
}

@media (max-width: 1100px) {
  .metric-grid { grid-template-columns: repeat(2, 1fr); }
  .dashboard-grid { grid-template-columns: 1fr; }
}

@media (max-width: 600px) {
  .welcome-panel {
    min-height: 150px;
    padding: 24px 20px;

    h1 { font-size: 22px; }
  }
  .date-block { display: none; }
  .metric-grid { grid-template-columns: 1fr 1fr; gap: 9px; }
  .metric-card {
    align-items: flex-start;
    flex-direction: column;
    min-height: 138px;
    padding: 15px;

    > div:last-child { width: 100%; }
    strong { font-size: 22px; }
  }
  .metric-icon { width: 38px; height: 38px; flex-basis: 38px; }
  .stage-card, .capability-card { padding: 16px; }
  .stage-row { grid-template-columns: 30px 1fr; }
  .stage-status { grid-column: 2; }
}
</style>
