<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { notificationApi, type NotificationDelivery } from '@/api/notification'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const scanning = ref(false)
const rows = ref<NotificationDelivery[]>([])
const total = ref(0)
const query = reactive({ status: undefined as string | undefined, page: 1, pageSize: 100 })
const channelLabels: Record<string, string> = { SYSTEM: '站内消息', ANDROID: 'Android APP', SMS: '短信', WECHAT: '微信', EMAIL: '邮件' }
const statusLabels: Record<string, string> = { READY: '等待 APP 同步', SENT: '已发送', FAILED: '发送失败', SKIPPED: '已跳过' }
onMounted(load)
async function load() {
  loading.value = true
  try { const result = await notificationApi.deliveries(query); rows.value = result.records; total.value = result.total }
  catch (error) { ElMessage.error(errorMessage(error, '发送记录加载失败')) }
  finally { loading.value = false }
}
async function scan() {
  scanning.value = true
  try { const result = await notificationApi.scan(); ElMessage.success(`扫描完成：新增 ${result.createdMessages} 条，去重 ${result.duplicateMessages} 条`); await load() }
  catch (error) { ElMessage.error(errorMessage(error, '提醒扫描失败')) }
  finally { scanning.value = false }
}
const dateTime = (value?: string) => value?.replace('T', ' ').slice(0, 19) || '—'
const failureText = (value?: string) => {
  if (!value) return '—'
  const labels: Record<string, string> = {
    CHANNEL_DISABLED: '该发送渠道未启用', RECIPIENT_NOT_FOUND: '未找到接收人',
    DUPLICATE: '相同提醒已发送', ACKNOWLEDGED: '事项已经确认，无需继续提醒',
  }
  return labels[value] || value
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header"><div><h1>发送记录</h1><p>查看提醒发送给谁、通过什么方式发送，以及当前处理结果。</p></div><el-button v-if="auth.can('notification:scan')" type="primary" :loading="scanning" @click="scan">立即执行提醒扫描</el-button></header>
    <section class="surface-card query-bar"><el-select v-model="query.status" clearable placeholder="发送状态" style="width: 180px"><el-option v-for="(label, value) in statusLabels" :key="value" :label="label" :value="value" /></el-select><el-button type="primary" plain @click="query.page = 1; load()">查询</el-button></section>
    <section class="surface-card table-card">
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="recipientName" label="接收人" width="130" /><el-table-column prop="title" label="提醒内容" min-width="260" show-overflow-tooltip /><el-table-column prop="channelCode" label="发送方式" width="120"><template #default="{ row }">{{ channelLabels[row.channelCode] || row.channelCode }}</template></el-table-column><el-table-column prop="deliveryStatus" label="处理状态" width="130"><template #default="{ row }">{{ statusLabels[row.deliveryStatus] || row.deliveryStatus }}</template></el-table-column><el-table-column prop="failureReason" label="未发送原因" min-width="220" show-overflow-tooltip><template #default="{ row }">{{ failureText(row.failureReason) }}</template></el-table-column><el-table-column label="处理时间" width="170"><template #default="{ row }">{{ dateTime(row.sentTime || row.createdTime) }}</template></el-table-column>
      </el-table>
      <div class="table-pagination"><el-pagination v-model:current-page="query.page" v-model:page-size="query.pageSize" :total="total" layout="total, sizes, prev, pager, next" @change="load" /></div>
    </section>
  </div>
</template>
