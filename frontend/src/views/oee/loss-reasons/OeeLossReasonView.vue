<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { oeeApi, type LossReasonRow } from '@/api/oee'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const records = ref<LossReasonRow[]>([])
const total = ref(0)
const dialog = ref(false)
const editingId = ref<number>()
const query = reactive({ keyword: '', lossCategory: '', status: undefined as number | undefined, page: 1, pageSize: 100 })
const form = reactive({
  parentId: 0,
  reasonCode: '',
  reasonName: '',
  lossCategory: 'BREAKDOWN',
  affectsMetric: 'AVAILABILITY',
  plannedFlag: false,
  color: '#F56C6C',
  sortOrder: 10,
  status: 1,
  description: '',
  version: undefined as number | undefined,
})
const categories = [
  ['BREAKDOWN', '设备故障'], ['SETUP_ADJUSTMENT', '换型与调整'],
  ['MINOR_STOPPAGE', '空转与短暂停机'], ['REDUCED_SPEED', '速度降低'],
  ['PROCESS_DEFECT', '过程不良'], ['STARTUP_REJECT', '启动不良'],
  ['PLANNED_STOP', '计划停机'], ['OTHER', '其他损失'],
]
const metrics: Record<string, string> = {
  AVAILABILITY: '时间开动率',
  PERFORMANCE: '性能开动率',
  QUALITY: '良品率',
  EXCLUDED: '负荷时间剔除',
}

onMounted(load)

async function load() {
  loading.value = true
  try {
    const result = await oeeApi.lossReasons({ ...query, lossCategory: query.lossCategory || undefined })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function open(row?: LossReasonRow) {
  editingId.value = row?.id
  Object.assign(form, row
    ? { ...row }
    : {
        parentId: 0,
        reasonCode: '',
        reasonName: '',
        lossCategory: 'BREAKDOWN',
        affectsMetric: 'AVAILABILITY',
        plannedFlag: false,
        color: '#F56C6C',
        sortOrder: 10,
        status: 1,
        description: '',
        version: undefined,
      })
  dialog.value = true
}

function syncPlanned() {
  if (form.plannedFlag) {
    form.lossCategory = 'PLANNED_STOP'
    form.affectsMetric = 'EXCLUDED'
  } else if (form.affectsMetric === 'EXCLUDED') {
    form.affectsMetric = 'AVAILABILITY'
  }
}

async function save() {
  if (!form.reasonCode || !form.reasonName) {
    ElMessage.warning('请填写损失原因编码和名称')
    return
  }
  try {
    if (editingId.value) await oeeApi.updateLossReason(editingId.value, form)
    else await oeeApi.createLossReason(form)
    ElMessage.success('损失原因保存成功')
    dialog.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function remove(row: LossReasonRow) {
  await ElMessageBox.confirm(`确认删除“${row.reasonName}”？已引用的数据不会允许删除。`, '删除确认', { type: 'warning' })
  try {
    await oeeApi.deleteLossReason(row.id, row.version)
    ElMessage.success('损失原因已删除')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

function categoryLabel(value: string) {
  return categories.find(([code]) => code === value)?.[1] ?? value
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div><h1>OEE 损失原因</h1><p>按 TPM 六大损失统一归类停机与效率损失，配置其影响指标和是否从负荷时间剔除。</p></div>
      <el-button v-if="auth.can('oee:loss-reason:manage')" type="primary" @click="open()">新增原因</el-button>
    </header>
    <section class="surface-card">
      <div class="toolbar">
        <el-input v-model="query.keyword" clearable placeholder="编码/名称" style="width: 220px" @keyup.enter="load" />
        <el-select v-model="query.lossCategory" clearable placeholder="全部损失分类" style="width: 180px"><el-option v-for="[value, label] in categories" :key="value" :label="label" :value="value" /></el-select>
        <el-select v-model="query.status" clearable placeholder="全部状态" style="width: 130px"><el-option label="启用" :value="1" /><el-option label="停用" :value="0" /></el-select>
        <el-button type="primary" @click="query.page = 1; load()">查询</el-button>
      </div>
      <el-table v-loading="loading" :data="records" stripe>
        <el-table-column prop="reasonCode" label="编码" width="170" />
        <el-table-column prop="reasonName" label="名称" min-width="150"><template #default="{ row }"><span class="reason-dot" :style="{ background: row.color }" />{{ row.reasonName }}</template></el-table-column>
        <el-table-column label="损失分类" width="150"><template #default="{ row }">{{ categoryLabel(row.lossCategory) }}</template></el-table-column>
        <el-table-column label="影响指标" width="150"><template #default="{ row }">{{ metrics[row.affectsMetric] }}</template></el-table-column>
        <el-table-column label="计划属性" width="100"><template #default="{ row }"><el-tag :type="row.plannedFlag ? 'info' : 'warning'">{{ row.plannedFlag ? '计划停机' : '非计划' }}</el-tag></template></el-table-column>
        <el-table-column prop="referenceCount" label="引用数" width="90" />
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.status ? 'success' : 'info'">{{ row.status ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column v-if="auth.can('oee:loss-reason:manage')" label="操作" width="140" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="open(row)">编辑</el-button><el-button link type="danger" @click="remove(row)">删除</el-button></template></el-table-column>
        <template #empty><el-empty description="暂无损失原因" /></template>
      </el-table>
      <el-pagination v-model:current-page="query.page" v-model:page-size="query.pageSize" :total="total" layout="total, prev, pager, next" @current-change="load" />
    </section>

    <el-dialog v-model="dialog" :title="editingId ? '编辑损失原因' : '新增损失原因'" width="640px">
      <el-form label-width="110px">
        <el-form-item label="上级原因"><el-select v-model="form.parentId" clearable style="width: 100%"><el-option label="无上级" :value="0" /><el-option v-for="item in records.filter((item) => item.id !== editingId)" :key="item.id" :label="item.reasonName" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="原因编码"><el-input v-model="form.reasonCode" :disabled="Boolean(editingId)" /></el-form-item>
        <el-form-item label="原因名称"><el-input v-model="form.reasonName" /></el-form-item>
        <el-form-item label="损失分类"><el-select v-model="form.lossCategory" style="width: 100%"><el-option v-for="[value, label] in categories" :key="value" :label="label" :value="value" /></el-select></el-form-item>
        <el-form-item label="影响指标"><el-select v-model="form.affectsMetric" style="width: 100%" :disabled="form.plannedFlag"><el-option v-for="(label, value) in metrics" :key="value" :label="label" :value="value" /></el-select></el-form-item>
        <el-form-item label="计划停机"><el-switch v-model="form.plannedFlag" @change="syncPlanned" /><span class="hint">计划停机从负荷时间中剔除</span></el-form-item>
        <el-form-item label="颜色/排序"><el-color-picker v-model="form.color" /><el-input-number v-model="form.sortOrder" :min="0" /></el-form-item>
        <el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 16px; }
.el-pagination { justify-content: flex-end; margin-top: 16px; }
.reason-dot { display: inline-block; width: 9px; height: 9px; border-radius: 50%; margin-right: 8px; }
.hint { margin-left: 10px; color: var(--el-text-color-secondary); font-size: 12px; }
.el-color-picker { margin-right: 16px; }
</style>
