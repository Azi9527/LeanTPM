<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { systemApi, type LoginLogRow } from '@/api/system'
import { errorMessage } from '@/utils/http'

const loading = ref(false)
const rows = ref<LoginLogRow[]>([])
const total = ref(0)
const query = reactive({ keyword: '', page: 1, pageSize: 20 })
onMounted(load)
async function load() {
  loading.value = true
  try {
    const result = await systemApi.loginLogs(query)
    rows.value = result.records
    total.value = result.total
  } catch (error) { ElMessage.error(errorMessage(error)) }
  finally { loading.value = false }
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header"><div><h1>登录日志</h1><p>追踪账号登录成功、失败原因、客户端和来源地址。</p></div></header>
    <section class="surface-card query-bar"><el-input v-model="query.keyword" clearable placeholder="账号或 IP" style="width: 260px" @keyup.enter="load" /><el-button type="primary" plain @click="query.page = 1; load()">查询</el-button></section>
    <section class="surface-card table-card">
      <div class="table-toolbar"><span class="table-title">登录记录</span></div>
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="username" label="账号" min-width="130" />
        <el-table-column label="结果" width="100"><template #default="{ row }"><el-tag :type="row.success ? 'success' : 'danger'">{{ row.success ? '成功' : '失败' }}</el-tag></template></el-table-column>
        <el-table-column prop="loginIp" label="来源 IP" min-width="140" />
        <el-table-column prop="failureReason" label="失败原因" min-width="200"><template #default="{ row }">{{ row.failureReason || '—' }}</template></el-table-column>
        <el-table-column prop="userAgent" label="客户端" min-width="260" show-overflow-tooltip />
        <el-table-column label="登录时间" min-width="180"><template #default="{ row }">{{ row.loginTime.replace('T', ' ') }}</template></el-table-column>
        <template #empty><el-empty description="暂无登录记录" /></template>
      </el-table>
      <div class="table-pagination"><el-pagination v-model:current-page="query.page" v-model:page-size="query.pageSize" :total="total" layout="total, prev, pager, next" @change="load" /></div>
    </section>
  </div>
</template>
