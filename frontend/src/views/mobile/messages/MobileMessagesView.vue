<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useMobileStore } from '@/stores/mobile'

const router = useRouter()
const mobile = useMobileStore()

function severityClass(severity: string): string {
  return ['HIGH', 'CRITICAL'].includes(severity) ? 'danger' : 'warning'
}

function dateTime(value: string): string {
  return value.replace('T', ' ').slice(0, 16)
}
</script>

<template>
  <div v-loading="mobile.loading" class="messages-page">
    <header><div><p>ALERTS</p><h1>现场消息</h1></div><el-button circle @click="mobile.refresh"><el-icon><Refresh /></el-icon></el-button></header>
    <article
      v-for="message in mobile.messages"
      :key="`${message.messageType}-${message.occurredTime}-${message.title}`"
      class="message-card"
      @click="message.routePath && router.push(message.routePath)"
    >
      <span class="severity" :class="severityClass(message.severity)"><el-icon><Warning /></el-icon></span>
      <div><strong>{{ message.title }}</strong><p>{{ message.content }}</p><time>{{ dateTime(message.occurredTime) }}</time></div>
      <el-icon v-if="message.routePath"><ArrowRight /></el-icon>
    </article>
    <el-empty v-if="!mobile.loading && !mobile.messages.length" description="暂无逾期任务或未关闭异常" />
  </div>
</template>

<style scoped>
.messages-page { display: grid; gap: 12px; }
header { display: flex; align-items: center; justify-content: space-between; padding: 4px 4px 10px; }
header p, header h1 { margin: 0; }
header p { color: #178198; font-size: 11px; font-weight: 800; letter-spacing: .14em; }
header h1 { margin-top: 4px; font-size: 26px; }
.message-card { display: grid; grid-template-columns: 42px 1fr auto; align-items: center; gap: 12px; padding: 16px; border-radius: 17px; background: white; box-shadow: 0 6px 20px rgba(23, 58, 69, .06); }
.severity { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 13px; color: #a86a0f; background: #fff3db; }
.severity.danger { color: #ca4242; background: #ffeded; }
.message-card strong, .message-card p { display: block; margin: 0; }
.message-card p { margin: 5px 0; color: #6f828a; line-height: 1.45; font-size: 12px; }
.message-card time { color: #9aa7ac; font-size: 11px; }
</style>
