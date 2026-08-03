<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  inspectionApi,
  type InspectionCalendarDetail,
  type InspectionCalendarExceptionRow,
  type InspectionCalendarRow,
} from '@/api/inspection'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const rows = ref<InspectionCalendarRow[]>([])
const keyword = ref('')
const calendarDialog = ref(false)
const exceptionDialog = ref(false)
const detailVisible = ref(false)
const editingCalendar = ref<InspectionCalendarRow>()
const editingException = ref<InspectionCalendarExceptionRow>()
const detail = ref<InspectionCalendarDetail>()

const calendarForm = reactive({
  calendarName: '',
  workDays: [1, 2, 3, 4, 5] as number[],
  defaultFlag: false,
  status: 1,
  description: '',
  version: undefined as number | undefined,
})

const exceptionForm = reactive({
  exceptionName: '',
  dateRange: [] as string[],
  dayType: 'RESTDAY' as 'WORKDAY' | 'RESTDAY',
  priorityValue: 100,
  status: 1,
  description: '',
  version: undefined as number | undefined,
})

const weekLabels = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

onMounted(load)

async function load() {
  loading.value = true
  try {
    rows.value = await inspectionApi.calendars({ keyword: keyword.value || undefined })
  } catch (error) {
    ElMessage.error(errorMessage(error, '加载点检日历失败'))
  } finally {
    loading.value = false
  }
}

function openCalendar(row?: InspectionCalendarRow) {
  editingCalendar.value = row
  Object.assign(calendarForm, row ? {
    calendarName: row.calendarName,
    workDays: row.workDays.split(',').map(Number),
    defaultFlag: row.defaultFlag,
    status: row.status,
    description: row.description || '',
    version: row.version,
  } : {
    calendarName: '',
    workDays: [1, 2, 3, 4, 5],
    defaultFlag: false,
    status: 1,
    description: '',
    version: undefined,
  })
  calendarDialog.value = true
}

async function saveCalendar() {
  if (!calendarForm.calendarName.trim() || !calendarForm.workDays.length) {
    ElMessage.warning('请填写日历名称并至少选择一个工作日')
    return
  }
  saving.value = true
  try {
    const payload = {
      ...calendarForm,
      calendarName: calendarForm.calendarName.trim(),
      workDays: [...calendarForm.workDays].sort().join(','),
    }
    if (editingCalendar.value) {
      await inspectionApi.updateCalendar(editingCalendar.value.id, payload)
    } else {
      await inspectionApi.createCalendar(payload)
    }
    calendarDialog.value = false
    ElMessage.success('点检日历已保存')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error, '保存点检日历失败'))
  } finally {
    saving.value = false
  }
}

async function removeCalendar(row: InspectionCalendarRow) {
  await ElMessageBox.confirm(`确认删除点检日历“${row.calendarName}”？`, '删除确认', { type: 'warning' })
  try {
    await inspectionApi.deleteCalendar(row.id, row.version)
    ElMessage.success('点检日历已删除')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error, '删除点检日历失败'))
  }
}

async function showDetail(row: InspectionCalendarRow) {
  try {
    detail.value = await inspectionApi.calendar(row.id)
    detailVisible.value = true
  } catch (error) {
    ElMessage.error(errorMessage(error, '加载自由日历失败'))
  }
}

function openException(row?: InspectionCalendarExceptionRow) {
  editingException.value = row
  Object.assign(exceptionForm, row ? {
    exceptionName: row.exceptionName,
    dateRange: [row.startDate, row.endDate],
    dayType: row.dayType,
    priorityValue: row.priorityValue,
    status: row.status,
    description: row.description || '',
    version: row.version,
  } : {
    exceptionName: '',
    dateRange: [],
    dayType: 'RESTDAY',
    priorityValue: 100,
    status: 1,
    description: '',
    version: undefined,
  })
  exceptionDialog.value = true
}

async function saveException() {
  if (!detail.value || !exceptionForm.exceptionName.trim() || exceptionForm.dateRange.length !== 2) {
    ElMessage.warning('请填写名称并选择日期范围')
    return
  }
  saving.value = true
  try {
    const payload = {
      exceptionName: exceptionForm.exceptionName.trim(),
      startDate: exceptionForm.dateRange[0],
      endDate: exceptionForm.dateRange[1],
      dayType: exceptionForm.dayType,
      priorityValue: exceptionForm.priorityValue,
      status: exceptionForm.status,
      description: exceptionForm.description || null,
      version: exceptionForm.version,
    }
    if (editingException.value) {
      await inspectionApi.updateCalendarException(
        detail.value.calendar.id,
        editingException.value.id,
        payload,
      )
    } else {
      await inspectionApi.createCalendarException(detail.value.calendar.id, payload)
    }
    exceptionDialog.value = false
    ElMessage.success('自由日历已保存')
    await showDetail(detail.value.calendar)
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error, '保存自由日历失败'))
  } finally {
    saving.value = false
  }
}

async function removeException(row: InspectionCalendarExceptionRow) {
  if (!detail.value) return
  await ElMessageBox.confirm(`确认删除“${row.exceptionName}”？`, '删除确认', { type: 'warning' })
  try {
    await inspectionApi.deleteCalendarException(detail.value.calendar.id, row.id, row.version)
    ElMessage.success('自由日历已删除')
    await showDetail(detail.value.calendar)
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error, '删除自由日历失败'))
  }
}

function workDayLabel(value: string) {
  return value.split(',').map(Number).map((day) => weekLabels[day - 1]).join('、')
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div><h1>点检日历</h1><p>工作周定义常规工作日，自由日历覆盖节假日、厂庆和临时生产安排。</p></div>
      <el-button v-if="auth.can('inspection:calendar:manage')" type="primary" @click="openCalendar()">新增日历</el-button>
    </header>
    <section class="surface-card query-bar">
      <el-input v-model="keyword" clearable placeholder="日历名称" @keyup.enter="load" />
      <el-button type="primary" @click="load">查询</el-button>
    </section>
    <section class="surface-card table-card" v-loading="loading">
      <div class="table-toolbar"><span class="table-title">工作日历</span><span>共 {{ rows.length }} 条</span></div>
      <el-table :data="rows" row-key="id">
        <el-table-column prop="calendarName" label="日历名称" min-width="180" />
        <el-table-column label="工作周" min-width="260"><template #default="{ row }">{{ workDayLabel(row.workDays) }}</template></el-table-column>
        <el-table-column prop="exceptionCount" label="自由日历" width="100" />
        <el-table-column label="默认" width="80"><template #default="{ row }"><el-tag v-if="row.defaultFlag" type="success">默认</el-tag><span v-else>—</span></template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column prop="description" label="说明" min-width="220" show-overflow-tooltip />
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="showDetail(row)">自由日历</el-button>
            <template v-if="auth.can('inspection:calendar:manage')">
              <el-button link type="primary" @click="openCalendar(row)">编辑</el-button>
              <el-button link type="danger" :disabled="row.defaultFlag" @click="removeCalendar(row)">删除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="calendarDialog" :title="editingCalendar ? '编辑点检日历' : '新增点检日历'" width="min(620px, 96vw)">
      <el-form label-position="top">
        <el-form-item label="日历名称"><el-input v-model="calendarForm.calendarName" maxlength="150" /></el-form-item>
        <el-form-item label="工作日"><el-checkbox-group v-model="calendarForm.workDays"><el-checkbox v-for="(label, index) in weekLabels" :key="index" :value="index + 1">{{ label }}</el-checkbox></el-checkbox-group></el-form-item>
        <el-form-item label="配置"><el-checkbox v-model="calendarForm.defaultFlag">设为默认日历</el-checkbox><el-switch v-model="calendarForm.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="calendarForm.description" type="textarea" :rows="3" maxlength="500" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="calendarDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveCalendar">保存</el-button></template>
    </el-dialog>

    <el-drawer v-model="detailVisible" :title="`${detail?.calendar.calendarName || ''} · 自由日历`" size="min(900px, 96vw)">
      <template v-if="detail">
        <div class="drawer-actions"><span>优先级更高、更新时间更晚的有效规则优先。</span><el-button v-if="auth.can('inspection:calendar:manage')" type="primary" @click="openException()">新增日期规则</el-button></div>
        <el-table :data="detail.exceptions" row-key="id">
          <el-table-column prop="exceptionName" label="名称" min-width="150" />
          <el-table-column label="日期范围" min-width="210"><template #default="{ row }">{{ row.startDate }} ~ {{ row.endDate }}</template></el-table-column>
          <el-table-column label="类型" width="90"><template #default="{ row }"><el-tag :type="row.dayType === 'WORKDAY' ? 'success' : 'warning'">{{ row.dayType === 'WORKDAY' ? '上班' : '休息' }}</el-tag></template></el-table-column>
          <el-table-column prop="priorityValue" label="优先级" width="80" />
          <el-table-column label="状态" width="80"><template #default="{ row }">{{ row.status === 1 ? '启用' : '停用' }}</template></el-table-column>
          <el-table-column v-if="auth.can('inspection:calendar:manage')" label="操作" width="120"><template #default="{ row }"><el-button link type="primary" @click="openException(row)">编辑</el-button><el-button link type="danger" @click="removeException(row)">删除</el-button></template></el-table-column>
        </el-table>
      </template>
    </el-drawer>

    <el-dialog v-model="exceptionDialog" :title="editingException ? '编辑自由日历' : '新增自由日历'" width="min(620px, 96vw)">
      <el-form label-position="top">
        <el-form-item label="名称"><el-input v-model="exceptionForm.exceptionName" placeholder="例如：国庆节、厂庆、临时生产" /></el-form-item>
        <el-form-item label="日期范围"><el-date-picker v-model="exceptionForm.dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" /></el-form-item>
        <el-form-item label="日期类型"><el-radio-group v-model="exceptionForm.dayType"><el-radio-button value="RESTDAY">休息日</el-radio-button><el-radio-button value="WORKDAY">上班日</el-radio-button></el-radio-group></el-form-item>
        <el-form-item label="优先级"><el-input-number v-model="exceptionForm.priorityValue" :min="0" :max="10000" /><span class="hint">数值越大优先级越高</span></el-form-item>
        <el-form-item label="状态"><el-switch v-model="exceptionForm.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="exceptionForm.description" type="textarea" :rows="3" maxlength="500" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="exceptionDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveException">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.drawer-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; color: var(--el-text-color-secondary); }
.hint { margin-left: 12px; color: var(--el-text-color-secondary); font-size: 12px; }
@media (max-width: 700px) { .drawer-actions { align-items: flex-start; flex-direction: column; } }
</style>
