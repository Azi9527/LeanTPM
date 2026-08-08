<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { systemApi, type OperationLogRow } from '@/api/system'
import { errorMessage } from '@/utils/http'

const loading = ref(false)
const rows = ref<OperationLogRow[]>([])
const total = ref(0)
const query = reactive({ keyword: '', page: 1, pageSize: 100 })
const methodLabels: Record<string, string> = { POST: '新增/执行', PUT: '修改', PATCH: '调整', DELETE: '删除', GET: '查询' }
const pathLabels: Array<[RegExp, string]> = [
  [/\/inspection\/items/, '点检项目'], [/\/inspection\/schemes/, '点检方案'],
  [/\/inspection\/plans/, '点检计划'], [/\/inspection\/tasks/, '点检任务'],
  [/\/equipment/, '设备管理'], [/\/organizations/, '组织管理'], [/\/locations/, '位置管理'],
  [/\/users/, '用户管理'], [/\/roles/, '角色权限'], [/\/notifications/, '消息提醒'],
  [/\/parameters/, '系统参数'], [/\/number-rules/, '编号规则'], [/\/attachments/, '附件管理'],
]
function operationName(row: OperationLogRow) {
  const module = pathLabels.find(([pattern]) => pattern.test(row.requestPath))?.[1] || '系统数据'
  return `${methodLabels[row.requestMethod] || '操作'}${module}`
}
onMounted(load)
async function load() {
  loading.value = true
  try {
    const result = await systemApi.operationLogs(query)
    rows.value = result.records
    total.value = result.total
  } catch (error) { ElMessage.error(errorMessage(error)) }
  finally { loading.value = false }
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header"><div><h1>操作日志</h1><p>记录关键数据操作的人员、内容、结果和处理耗时。</p></div></header>
    <section class="surface-card query-bar"><el-input v-model="query.keyword" clearable placeholder="操作人或操作内容" style="width: 280px" @keyup.enter="load" /><el-button type="primary" plain @click="query.page = 1; load()">查询</el-button></section>
    <section class="surface-card table-card">
      <div class="table-toolbar"><span class="table-title">操作记录</span></div>
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="username" label="操作人" min-width="120" />
        <el-table-column label="操作类型" width="110"><template #default="{ row }"><el-tag size="small" effect="plain">{{ methodLabels[row.requestMethod] || '操作' }}</el-tag></template></el-table-column>
        <el-table-column label="操作内容" min-width="220"><template #default="{ row }">{{ operationName(row) }}</template></el-table-column>
        <el-table-column label="结果" width="90"><template #default="{ row }"><el-tag :type="row.success ? 'success' : 'danger'">{{ row.success ? '成功' : '失败' }}</el-tag></template></el-table-column>
        <el-table-column label="耗时" width="100"><template #default="{ row }">{{ row.durationMs }} ms</template></el-table-column>
        <el-table-column prop="requestIp" label="来源 IP" min-width="130" />
        <el-table-column prop="errorMessage" label="错误" min-width="160" show-overflow-tooltip><template #default="{ row }">{{ row.errorMessage || '—' }}</template></el-table-column>
        <el-table-column label="操作时间" min-width="180"><template #default="{ row }">{{ row.operationTime.replace('T', ' ') }}</template></el-table-column>
        <template #empty><el-empty description="暂无操作记录" /></template>
      </el-table>
      <div class="table-pagination"><el-pagination v-model:current-page="query.page" v-model:page-size="query.pageSize" :total="total" layout="total, prev, pager, next" @change="load" /></div>
    </section>
  </div>
</template>
