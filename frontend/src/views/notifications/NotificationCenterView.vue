<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { notificationApi, type NotificationMessage } from '@/api/notification'
import { errorMessage } from '@/utils/http'

const router = useRouter()
const loading = ref(false)
const rows = ref<NotificationMessage[]>([])
const total = ref(0)
const query = reactive({ unreadOnly: false, page: 1, pageSize: 20 })

onMounted(load)

async function load() {
  loading.value = true
  try {
    const result = await notificationApi.messages(query)
    rows.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(errorMessage(error, '消息加载失败'))
  } finally {
    loading.value = false
  }
}

async function markRead(row: NotificationMessage) {
  try {
    await notificationApi.read(row.id)
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error, '消息已读操作失败'))
  }
}

async function acknowledge(row: NotificationMessage) {
  try {
    await notificationApi.acknowledge(row.id)
    ElMessage.success('消息已确认')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error, '消息确认失败'))
  }
}

async function openBusiness(row: NotificationMessage) {
  if (!row.readTime) await notificationApi.read(row.id)
  const path = row.businessType === 'INSPECTION'
    ? `/inspection/my-tasks?taskId=${row.businessId}`
    : `/maintenance/my-tasks?taskId=${row.businessId}`
  await router.push(path)
}

function dateTime(value?: string) {
  return value?.replace('T', ' ').slice(0, 19) || '—'
}

function rowClassName({ row }: { row: NotificationMessage }) {
  return row.readTime ? '' : 'unread-row'
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div><h1>我的消息</h1><p>集中查看到期提醒、临时强提醒和逾期升级，支持已读与确认留痕。</p></div>
    </header>
    <section class="surface-card query-bar">
      <el-switch v-model="query.unreadOnly" active-text="只看未读" @change="query.page = 1; load()" />
      <el-button type="primary" plain @click="load">刷新</el-button>
    </section>
    <section class="surface-card table-card">
      <el-table v-loading="loading" :data="rows" row-key="id" :row-class-name="rowClassName">
        <el-table-column label="级别" width="90">
          <template #default="{ row }"><el-tag :type="['HIGH', 'CRITICAL'].includes(row.severity) ? 'danger' : 'warning'">{{ row.severity }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="250" show-overflow-tooltip />
        <el-table-column prop="content" label="内容" min-width="330" show-overflow-tooltip />
        <el-table-column label="产生时间" width="170"><template #default="{ row }">{{ dateTime(row.occurredTime) }}</template></el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.acknowledgedTime" type="success">已确认</el-tag>
            <el-tag v-else-if="row.readTime" type="info">已读</el-tag>
            <el-tag v-else>未读</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button v-if="!row.readTime" link type="primary" @click="markRead(row)">标记已读</el-button>
            <el-button v-if="row.acknowledgeRequired && !row.acknowledgedTime" link type="warning" @click="acknowledge(row)">确认</el-button>
            <el-button link @click="openBusiness(row)">查看任务</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无消息" /></template>
      </el-table>
      <div class="table-pagination"><el-pagination v-model:current-page="query.page" v-model:page-size="query.pageSize" :total="total" layout="total, sizes, prev, pager, next" @change="load" /></div>
    </section>
  </div>
</template>

<style scoped>
:deep(.unread-row) { font-weight: 650; background: #f0f9fb; }
</style>
