<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { systemApi, type OnlineSessionRow } from '@/api/system'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const kicking = ref('')
const loadError = ref('')
const keyword = ref('')
const rows = ref<OnlineSessionRow[]>([])

const filteredRows = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  if (!value) return rows.value
  return rows.value.filter((row) =>
    [row.username, row.realName, row.loginIp, row.userAgent]
      .some((field) => field?.toLowerCase().includes(value)),
  )
})

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    rows.value = await systemApi.onlineUsers()
  } catch (error) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

async function kickout(row: OnlineSessionRow) {
  await ElMessageBox.confirm(
    `确认强制下线“${row.realName || row.username}”的这个会话吗？`,
    '强制下线',
    { type: 'warning' },
  )
  kicking.value = row.sessionId
  try {
    await systemApi.kickoutOnlineUser(row.sessionId)
    ElMessage.success('会话已强制下线')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    kicking.value = ''
  }
}

function formatTime(value: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}

function device(value: string) {
  if (!value) return '未知终端'
  if (/Android/i.test(value)) return 'Android'
  if (/iPhone|iPad/i.test(value)) return 'iOS'
  if (/Edg\//i.test(value)) return 'Edge'
  if (/Chrome\//i.test(value)) return 'Chrome'
  if (/Firefox\//i.test(value)) return 'Firefox'
  return value.slice(0, 36)
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div>
        <h1>在线用户</h1>
        <p>会话状态来自 Redis；退出、改密或强制下线后，原访问令牌立即失效。</p>
      </div>
      <div class="page-actions">
        <el-button :loading="loading" @click="load">刷新会话</el-button>
      </div>
    </header>

    <section class="surface-card session-summary">
      <div>
        <strong>{{ rows.length }}</strong>
        <span>在线会话</span>
      </div>
      <div>
        <strong>{{ new Set(rows.map((row) => row.userId)).size }}</strong>
        <span>在线用户</span>
      </div>
      <el-input
        v-model="keyword"
        clearable
        placeholder="搜索姓名、账号、IP 或终端"
        style="max-width: 320px"
      />
    </section>

    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      show-icon
      :closable="false"
    >
      <template #default><el-button link type="primary" @click="load">重新加载</el-button></template>
    </el-alert>

    <section class="surface-card table-card" v-loading="loading">
      <div class="table-toolbar">
        <span class="table-title">当前会话</span>
        <span class="session-tip">最近活动时间会在访问受保护接口时更新</span>
      </div>
      <el-table :data="filteredRows" row-key="sessionId">
        <el-table-column label="用户" min-width="150">
          <template #default="{ row }">
            <div class="user-cell">
              <span class="session-avatar">{{ (row.realName || row.username).slice(0, 1) }}</span>
              <span>
                <strong>{{ row.realName || row.username }}</strong>
                <small>{{ row.username }}</small>
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="loginIp" label="登录 IP" width="145">
          <template #default="{ row }"><span class="mono">{{ row.loginIp || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="终端" min-width="130">
          <template #default="{ row }">
            <el-tooltip :content="row.userAgent || '未知终端'">
              <span>{{ device(row.userAgent) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="登录时间" min-width="175">
          <template #default="{ row }">{{ formatTime(row.loginTime) }}</template>
        </el-table-column>
        <el-table-column label="最近活动" min-width="175">
          <template #default="{ row }">{{ formatTime(row.lastActiveTime) }}</template>
        </el-table-column>
        <el-table-column label="会话" width="105">
          <template #default="{ row }">
            <el-tag v-if="row.currentSession" type="success">当前会话</el-tag>
            <el-tag v-else effect="plain">在线</el-tag>
          </template>
        </el-table-column>
        <el-table-column
          v-if="auth.can('system:online-user:kickout')"
          label="操作"
          width="110"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button
              link
              type="danger"
              :disabled="row.currentSession"
              :loading="kicking === row.sessionId"
              @click="kickout(row)"
            >
              强制下线
            </el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无在线会话" /></template>
      </el-table>
    </section>
  </div>
</template>

<style scoped lang="scss">
.session-summary {
  display: flex;
  align-items: center;
  gap: 28px;
  padding: 16px 18px;
}

.session-summary > div {
  display: flex;
  align-items: baseline;
  gap: 7px;

  strong { color: var(--tpm-primary); font-size: 25px; }
  span { color: var(--tpm-text-secondary); font-size: 13px; }
}

.session-summary .el-input { margin-left: auto; }
.session-tip { color: var(--tpm-text-secondary); font-size: 12px; }
.user-cell { display: flex; align-items: center; gap: 9px; }
.user-cell > span:last-child { display: flex; flex-direction: column; }
.user-cell small { color: var(--tpm-text-secondary); font-size: 11px; }
.session-avatar {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 9px;
  color: #fff;
  background: var(--tpm-primary);
  font-weight: 700;
}

@media (max-width: 700px) {
  .session-summary { align-items: stretch; flex-wrap: wrap; gap: 12px 24px; }
  .session-summary .el-input { flex-basis: 100%; max-width: none !important; margin-left: 0; }
}
</style>
