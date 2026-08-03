<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { notificationApi } from '@/api/notification'
import { useMobileStore } from '@/stores/mobile'
import { errorMessage } from '@/utils/http'

const router = useRouter()
const mobile = useMobileStore()

function severityClass(severity: string): string {
  return ['HIGH', 'CRITICAL'].includes(severity) ? 'danger' : 'warning'
}

function dateTime(value: string): string {
  return value.replace('T', ' ').slice(0, 16)
}

async function openMessage(message: (typeof mobile.messages)[number]) {
  try {
    if (!message.readTime) await notificationApi.read(message.id)
    if (message.routePath) await router.push(message.routePath)
    await mobile.refresh()
  } catch (error) {
    ElMessage.error(errorMessage(error, '消息操作失败'))
  }
}

async function acknowledge(message: (typeof mobile.messages)[number]) {
  try {
    await notificationApi.acknowledge(message.id)
    ElMessage.success('已确认收到')
    await mobile.refresh()
  } catch (error) {
    ElMessage.error(errorMessage(error, '消息确认失败'))
  }
}
</script>

<template>
  <div v-loading="mobile.loading" class="messages-page">
    <header><div><p>ALERTS</p><h1>现场消息</h1></div><el-button circle @click="mobile.refresh"><el-icon><Refresh /></el-icon></el-button></header>
    <article
      v-for="message in mobile.messages"
      :key="message.id"
      class="message-card"
      :class="{ unread: !message.readTime }"
      @click="openMessage(message)"
    >
      <span class="severity" :class="severityClass(message.severity)"><el-icon><Warning /></el-icon></span>
      <div><strong>{{ message.title }}</strong><p>{{ message.content }}</p><time>{{ dateTime(message.occurredTime) }}</time><el-button v-if="message.acknowledgeRequired && !message.acknowledgedTime" size="small" type="warning" plain @click.stop="acknowledge(message)">确认收到</el-button></div>
      <el-icon v-if="message.routePath"><ArrowRight /></el-icon>
    </article>
    <el-empty v-if="!mobile.loading && !mobile.messages.length" description="暂无逾期任务或未关闭异常" />
  </div>
</template>

<style scoped>
.messages-page { display: grid; gap: 12px; }
header { display: flex; align-items: center; justify-content: space-between; padding: 4px 4px 10px; }
header p, header h1 { margin: 0; }
header p { color: var(--tpm-primary); font-size: 11px; font-weight: 800; letter-spacing: .14em; }
header h1 { margin-top: 4px; font-size: 26px; }
.message-card { display: grid; grid-template-columns: 42px 1fr auto; align-items: center; gap: 12px; padding: 16px; border-radius: 17px; background: white; box-shadow: 0 6px 20px rgba(23, 58, 69, .06); }
.message-card.unread { border-left: 4px solid var(--tpm-primary); }
.severity { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 13px; color: #a86a0f; background: #fff3db; }
.severity.danger { color: #ca4242; background: #ffeded; }
.message-card strong, .message-card p { display: block; margin: 0; }
.message-card p { margin: 5px 0; color: #6f828a; line-height: 1.45; font-size: 12px; }
.message-card time { color: #9aa7ac; font-size: 11px; }
</style>
