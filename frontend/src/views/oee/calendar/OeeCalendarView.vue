<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { oeeApi, type CalendarRow, type ShiftRow } from '@/api/oee'
import { masterDataApi, type OrganizationRow } from '@/api/masterData'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const activeTab = ref('calendar')
const loading = ref(false)
const shifts = ref<ShiftRow[]>([])
const calendars = ref<CalendarRow[]>([])
const organizations = ref<OrganizationRow[]>([])
const shiftTotal = ref(0)
const calendarTotal = ref(0)
const shiftDialog = ref(false)
const calendarDialog = ref(false)
const editingShiftId = ref<number>()
const editingCalendarId = ref<number>()
const shiftQuery = reactive({ keyword: '', status: undefined as number | undefined, page: 1, pageSize: 20 })
const calendarQuery = reactive({
  organizationId: undefined as number | undefined,
  startDate: '',
  endDate: '',
  page: 1,
  pageSize: 20,
})
const shiftForm = reactive({
  shiftCode: '',
  shiftName: '',
  startTime: '08:00:00',
  endTime: '20:00:00',
  crossDayFlag: false,
  breakMinutes: 60,
  standardWorkMinutes: 660,
  sortOrder: 10,
  status: 1,
  description: '',
  version: undefined as number | undefined,
})
const calendarForm = reactive({
  organizationId: undefined as number | undefined,
  workDate: '',
  shiftId: undefined as number | undefined,
  dayType: 'WORKDAY',
  plannedWorkMinutes: 660,
  plannedDowntimeMinutes: 0,
  calendarStatus: 'ENABLED',
  remark: '',
  version: undefined as number | undefined,
})

onMounted(async () => {
  await Promise.all([loadShifts(), loadCalendars(), loadOrganizations()])
})

async function loadOrganizations() {
  try {
    organizations.value = await masterDataApi.organizations()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function loadShifts() {
  loading.value = true
  try {
    const result = await oeeApi.shifts(shiftQuery)
    shifts.value = result.records
    shiftTotal.value = result.total
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function loadCalendars() {
  loading.value = true
  try {
    const result = await oeeApi.calendars({
      ...calendarQuery,
      startDate: calendarQuery.startDate || undefined,
      endDate: calendarQuery.endDate || undefined,
    })
    calendars.value = result.records
    calendarTotal.value = result.total
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function openShift(row?: ShiftRow) {
  editingShiftId.value = row?.id
  Object.assign(shiftForm, row
    ? { ...row }
    : {
        shiftCode: '',
        shiftName: '',
        startTime: '08:00:00',
        endTime: '20:00:00',
        crossDayFlag: false,
        breakMinutes: 60,
        standardWorkMinutes: 660,
        sortOrder: 10,
        status: 1,
        description: '',
        version: undefined,
      })
  shiftDialog.value = true
}

function openCalendar(row?: CalendarRow) {
  editingCalendarId.value = row?.id
  Object.assign(calendarForm, row
    ? { ...row }
    : {
        organizationId: undefined,
        workDate: new Date().toISOString().slice(0, 10),
        shiftId: shifts.value.find((item) => item.status === 1)?.id,
        dayType: 'WORKDAY',
        plannedWorkMinutes: shifts.value.find((item) => item.status === 1)?.standardWorkMinutes ?? 660,
        plannedDowntimeMinutes: 0,
        calendarStatus: 'ENABLED',
        remark: '',
        version: undefined,
      })
  calendarDialog.value = true
}

function recalculateShiftMinutes() {
  const minutes = (value: string) => {
    const [hour = 0, minute = 0] = value.split(':').map(Number)
    return hour * 60 + minute
  }
  let elapsed = minutes(shiftForm.endTime) - minutes(shiftForm.startTime)
  if (shiftForm.crossDayFlag) elapsed += 1440
  shiftForm.standardWorkMinutes = Math.max(1, elapsed - shiftForm.breakMinutes)
}

function applyShiftToCalendar() {
  const selected = shifts.value.find((item) => item.id === calendarForm.shiftId)
  if (selected && calendarForm.dayType !== 'HOLIDAY') {
    calendarForm.plannedWorkMinutes = selected.standardWorkMinutes
  }
}

function handleDayType() {
  if (calendarForm.dayType === 'HOLIDAY') {
    calendarForm.plannedWorkMinutes = 0
    calendarForm.plannedDowntimeMinutes = 0
  } else {
    applyShiftToCalendar()
  }
}

async function saveShift() {
  if (!shiftForm.shiftCode || !shiftForm.shiftName) {
    ElMessage.warning('请填写班次编码和名称')
    return
  }
  recalculateShiftMinutes()
  try {
    if (editingShiftId.value) {
      await oeeApi.updateShift(editingShiftId.value, shiftForm)
    } else {
      await oeeApi.createShift(shiftForm)
    }
    ElMessage.success('班次保存成功')
    shiftDialog.value = false
    await loadShifts()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function saveCalendar() {
  if (!calendarForm.organizationId || !calendarForm.workDate || !calendarForm.shiftId) {
    ElMessage.warning('请选择组织、日期和班次')
    return
  }
  try {
    if (editingCalendarId.value) {
      await oeeApi.updateCalendar(editingCalendarId.value, calendarForm)
    } else {
      await oeeApi.createCalendar(calendarForm)
    }
    ElMessage.success('生产日历保存成功')
    calendarDialog.value = false
    await loadCalendars()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function removeShift(row: ShiftRow) {
  await ElMessageBox.confirm(`确认删除班次“${row.shiftName}”？`, '删除确认', { type: 'warning' })
  try {
    await oeeApi.deleteShift(row.id, row.version)
    ElMessage.success('班次已删除')
    await loadShifts()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function removeCalendar(row: CalendarRow) {
  await ElMessageBox.confirm('确认删除这条生产日历？', '删除确认', { type: 'warning' })
  try {
    await oeeApi.deleteCalendar(row.id, row.version)
    ElMessage.success('生产日历已删除')
    await loadCalendars()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

const dayLabel: Record<string, string> = { WORKDAY: '工作日', HOLIDAY: '休息日', OVERTIME: '加班日' }
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div><h1>班次与生产日历</h1><p>统一维护跨日班次、休息时间、组织生产日和计划停机，为 OEE 负荷时间提供基准。</p></div>
    </header>
    <el-tabs v-model="activeTab" class="surface-card">
      <el-tab-pane label="生产日历" name="calendar">
        <div class="toolbar">
          <el-select v-model="calendarQuery.organizationId" clearable filterable placeholder="全部组织" style="width: 220px">
            <el-option v-for="item in organizations" :key="item.id" :label="item.organizationName" :value="item.id" />
          </el-select>
          <el-date-picker v-model="calendarQuery.startDate" type="date" value-format="YYYY-MM-DD" placeholder="开始日期" />
          <el-date-picker v-model="calendarQuery.endDate" type="date" value-format="YYYY-MM-DD" placeholder="结束日期" />
          <el-button type="primary" @click="calendarQuery.page = 1; loadCalendars()">查询</el-button>
          <el-button v-if="auth.can('oee:calendar:manage')" type="success" @click="openCalendar()">新增日历</el-button>
        </div>
        <el-table v-loading="loading" :data="calendars" stripe>
          <el-table-column prop="workDate" label="生产日期" width="120" />
          <el-table-column prop="organizationName" label="组织" min-width="150" />
          <el-table-column prop="shiftName" label="班次" width="110" />
          <el-table-column label="日期类型" width="100"><template #default="{ row }">{{ dayLabel[row.dayType] }}</template></el-table-column>
          <el-table-column prop="plannedWorkMinutes" label="计划工作(分)" width="125" />
          <el-table-column prop="plannedDowntimeMinutes" label="计划停机(分)" width="125" />
          <el-table-column prop="calendarStatus" label="状态" width="100" />
          <el-table-column v-if="auth.can('oee:calendar:manage')" label="操作" width="140" fixed="right">
            <template #default="{ row }"><el-button link type="primary" @click="openCalendar(row)">编辑</el-button><el-button link type="danger" @click="removeCalendar(row)">删除</el-button></template>
          </el-table-column>
          <template #empty><el-empty description="当前筛选条件下暂无生产日历" /></template>
        </el-table>
        <el-pagination v-model:current-page="calendarQuery.page" v-model:page-size="calendarQuery.pageSize" :total="calendarTotal" layout="total, prev, pager, next" @current-change="loadCalendars" />
      </el-tab-pane>
      <el-tab-pane label="生产班次" name="shift">
        <div class="toolbar">
          <el-input v-model="shiftQuery.keyword" clearable placeholder="编码/名称" style="width: 220px" @keyup.enter="loadShifts" />
          <el-select v-model="shiftQuery.status" clearable placeholder="全部状态" style="width: 130px"><el-option label="启用" :value="1" /><el-option label="停用" :value="0" /></el-select>
          <el-button type="primary" @click="shiftQuery.page = 1; loadShifts()">查询</el-button>
          <el-button v-if="auth.can('oee:shift:manage')" type="success" @click="openShift()">新增班次</el-button>
        </div>
        <el-table v-loading="loading" :data="shifts" stripe>
          <el-table-column prop="shiftCode" label="编码" width="130" />
          <el-table-column prop="shiftName" label="名称" min-width="130" />
          <el-table-column label="时段" width="210"><template #default="{ row }">{{ row.startTime }}－{{ row.endTime }}<el-tag v-if="row.crossDayFlag" size="small">跨日</el-tag></template></el-table-column>
          <el-table-column prop="breakMinutes" label="休息(分)" width="100" />
          <el-table-column prop="standardWorkMinutes" label="标准工作(分)" width="125" />
          <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.status ? 'success' : 'info'">{{ row.status ? '启用' : '停用' }}</el-tag></template></el-table-column>
          <el-table-column v-if="auth.can('oee:shift:manage')" label="操作" width="140" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openShift(row)">编辑</el-button><el-button link type="danger" @click="removeShift(row)">删除</el-button></template></el-table-column>
          <template #empty><el-empty description="暂无班次" /></template>
        </el-table>
        <el-pagination v-model:current-page="shiftQuery.page" v-model:page-size="shiftQuery.pageSize" :total="shiftTotal" layout="total, prev, pager, next" @current-change="loadShifts" />
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="shiftDialog" :title="editingShiftId ? '编辑班次' : '新增班次'" width="620px">
      <el-form label-width="115px">
        <el-form-item label="班次编码"><el-input v-model="shiftForm.shiftCode" :disabled="Boolean(editingShiftId)" /></el-form-item>
        <el-form-item label="班次名称"><el-input v-model="shiftForm.shiftName" /></el-form-item>
        <el-form-item label="开始/结束"><el-time-picker v-model="shiftForm.startTime" value-format="HH:mm:ss" placeholder="开始" @change="recalculateShiftMinutes" /><el-time-picker v-model="shiftForm.endTime" value-format="HH:mm:ss" placeholder="结束" @change="recalculateShiftMinutes" /></el-form-item>
        <el-form-item label="跨日"><el-switch v-model="shiftForm.crossDayFlag" @change="recalculateShiftMinutes" /></el-form-item>
        <el-form-item label="休息分钟"><el-input-number v-model="shiftForm.breakMinutes" :min="0" :max="1440" @change="recalculateShiftMinutes" /></el-form-item>
        <el-form-item label="标准工作分钟"><el-input-number v-model="shiftForm.standardWorkMinutes" :min="1" :max="1440" disabled /></el-form-item>
        <el-form-item label="排序/状态"><el-input-number v-model="shiftForm.sortOrder" :min="0" /><el-switch v-model="shiftForm.status" :active-value="1" :inactive-value="0" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="shiftForm.description" type="textarea" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="shiftDialog = false">取消</el-button><el-button type="primary" @click="saveShift">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="calendarDialog" :title="editingCalendarId ? '编辑生产日历' : '新增生产日历'" width="620px">
      <el-form label-width="120px">
        <el-form-item label="组织"><el-select v-model="calendarForm.organizationId" filterable style="width: 100%"><el-option v-for="item in organizations" :key="item.id" :label="item.organizationName" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="生产日期"><el-date-picker v-model="calendarForm.workDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="班次"><el-select v-model="calendarForm.shiftId" @change="applyShiftToCalendar"><el-option v-for="item in shifts.filter((s) => s.status === 1)" :key="item.id" :label="item.shiftName" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="日期类型"><el-radio-group v-model="calendarForm.dayType" @change="handleDayType"><el-radio-button value="WORKDAY">工作日</el-radio-button><el-radio-button value="HOLIDAY">休息日</el-radio-button><el-radio-button value="OVERTIME">加班日</el-radio-button></el-radio-group></el-form-item>
        <el-form-item label="计划工作分钟"><el-input-number v-model="calendarForm.plannedWorkMinutes" :min="0" :max="1440" /></el-form-item>
        <el-form-item label="计划停机分钟"><el-input-number v-model="calendarForm.plannedDowntimeMinutes" :min="0" :max="calendarForm.plannedWorkMinutes" /></el-form-item>
        <el-form-item label="状态"><el-switch v-model="calendarForm.calendarStatus" active-value="ENABLED" inactive-value="DISABLED" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="calendarForm.remark" type="textarea" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="calendarDialog = false">取消</el-button><el-button type="primary" @click="saveCalendar">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 16px; }
.el-pagination { justify-content: flex-end; margin-top: 16px; }
.el-form-item :deep(.el-time-picker) { width: 180px; margin-right: 10px; }
</style>
