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
const query = reactive({ status: undefined as string | undefined, page: 1, pageSize: 20 })
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
</script>

<template>
  <div class="page-shell">
    <header class="page-header"><div><h1>发送记录</h1><p>查看站内、Android 和预留渠道的发送结果与失败原因。</p></div><el-button v-if="auth.can('notification:scan')" type="primary" :loading="scanning" @click="scan">立即执行提醒扫描</el-button></header>
    <section class="surface-card query-bar"><el-select v-model="query.status" clearable placeholder="发送状态" style="width: 180px"><el-option v-for="item in ['READY','SENT','FAILED','SKIPPED']" :key="item" :label="item" :value="item" /></el-select><el-button type="primary" plain @click="query.page = 1; load()">查询</el-button></section>
    <section class="surface-card table-card">
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="recipientName" label="接收人" width="130" /><el-table-column prop="title" label="消息" min-width="260" show-overflow-tooltip /><el-table-column prop="channelCode" label="渠道" width="110" /><el-table-column prop="deliveryStatus" label="状态" width="110" /><el-table-column prop="failureReason" label="失败/跳过原因" min-width="220" show-overflow-tooltip /><el-table-column label="发送时间" width="170"><template #default="{ row }">{{ dateTime(row.sentTime || row.createdTime) }}</template></el-table-column>
      </el-table>
      <div class="table-pagination"><el-pagination v-model:current-page="query.page" v-model:page-size="query.pageSize" :total="total" layout="total, sizes, prev, pager, next" @change="load" /></div>
    </section>
  </div>
</template>
