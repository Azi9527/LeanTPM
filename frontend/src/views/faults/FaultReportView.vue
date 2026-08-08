<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { equipmentApi, type EquipmentRow } from '@/api/equipment'
import { faultApi, type FaultReport } from '@/api/fault'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const rows = ref<FaultReport[]>([])
const equipment = ref<EquipmentRow[]>([])
const total = ref(0)
const query = reactive({ keyword: '', status: undefined as string | undefined, page: 1, pageSize: 100 })
const form = reactive({ equipmentId: undefined as number | undefined, faultTime: '', faultTitle: '', faultDescription: '', severity: 'MEDIUM' })
const statuses: Record<string, string> = { REPORTED: '已报修', ACCEPTED: '已受理', REJECTED: '已驳回', CONVERTED: '已转工单', CLOSED: '已关闭', CANCELLED: '已取消' }

onMounted(async () => { await Promise.all([load(), loadEquipment()]) })
async function load() { loading.value = true; try { const result = await faultApi.reports(query); rows.value = result.records; total.value = result.total } catch (error) { ElMessage.error(errorMessage(error, '报修单加载失败')) } finally { loading.value = false } }
async function loadEquipment() { try { equipment.value = (await equipmentApi.page({ status: 1, page: 1, pageSize: 100 })).records } catch { equipment.value = [] } }
function openCreate() { Object.assign(form, { equipmentId: undefined, faultTime: new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 19), faultTitle: '', faultDescription: '', severity: 'MEDIUM' }); dialogVisible.value = true }
async function save() { if (!form.equipmentId || !form.faultTitle || !form.faultDescription) return ElMessage.warning('请完整填写设备、故障标题和现象'); saving.value = true; try { await faultApi.createReport({ ...form, attachmentIds: [] }); ElMessage.success('故障报修已创建'); dialogVisible.value = false; await load() } catch (error) { ElMessage.error(errorMessage(error, '故障报修创建失败')) } finally { saving.value = false } }
async function accept(row: FaultReport) { try { await faultApi.acceptReport(row.id, row.version); ElMessage.success('报修单已受理'); await load() } catch (error) { ElMessage.error(errorMessage(error)) } }
async function reject(row: FaultReport) { try { const { value } = await ElMessageBox.prompt('请输入驳回原因', '驳回报修', { inputValidator: value => Boolean(value?.trim()) || '请输入原因' }); await faultApi.rejectReport(row.id, { reason: value, version: row.version }); await load() } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error)) } }
async function createRepair(row: FaultReport) { try { await faultApi.createRepair(row.id, { primaryRepairerUserId: null, collaboratorUserIds: [], restoreStatusCode: 'IDLE', reportVersion: row.version }); ElMessage.success('维修工单已创建，可前往维修工单派工'); await load() } catch (error) { ElMessage.error(errorMessage(error, '维修工单创建失败')) } }
const dateTime = (value?: string) => value?.replace('T', ' ').slice(0, 19) || '—'
</script>

<template>
  <div class="page-shell">
    <header class="page-header"><div><h1>故障报修</h1><p>独立记录设备故障，支持受理、驳回及转维修工单。</p></div><el-button v-if="auth.can('fault:report:create')" type="primary" @click="openCreate">新建报修</el-button></header>
    <section class="surface-card query-bar"><el-input v-model="query.keyword" clearable placeholder="报修号、设备或故障" style="width: 280px" @keyup.enter="load" /><el-select v-model="query.status" clearable placeholder="状态" style="width: 160px"><el-option v-for="(label, value) in statuses" :key="value" :label="label" :value="value" /></el-select><el-button type="primary" plain @click="query.page = 1; load()">查询</el-button></section>
    <section class="surface-card table-card"><el-table v-loading="loading" :data="rows" row-key="id"><el-table-column prop="reportCode" label="报修单" width="170" /><el-table-column label="设备" min-width="190"><template #default="{ row }"><b>{{ row.equipmentName }}</b><small class="block">{{ row.equipmentCode }}</small></template></el-table-column><el-table-column prop="faultTitle" label="故障" min-width="220" show-overflow-tooltip /><el-table-column prop="severity" label="等级" width="100" /><el-table-column prop="sourceType" label="来源" width="110" /><el-table-column label="故障时间" width="170"><template #default="{ row }">{{ dateTime(row.faultTime) }}</template></el-table-column><el-table-column label="状态" width="110"><template #default="{ row }"><el-tag>{{ statuses[row.reportStatus] || row.reportStatus }}</el-tag></template></el-table-column><el-table-column label="操作" width="230" fixed="right"><template #default="{ row }"><el-button v-if="row.reportStatus === 'REPORTED' && auth.can('fault:report:accept')" link type="primary" @click="accept(row)">受理</el-button><el-button v-if="row.reportStatus === 'REPORTED' && auth.can('fault:report:accept')" link type="danger" @click="reject(row)">驳回</el-button><el-button v-if="['REPORTED','ACCEPTED'].includes(row.reportStatus) && auth.can('fault:repair:create')" link type="warning" @click="createRepair(row)">转维修</el-button><span v-if="row.repairCode">{{ row.repairCode }}</span></template></el-table-column></el-table><div class="table-pagination"><el-pagination v-model:current-page="query.page" v-model:page-size="query.pageSize" :total="total" layout="total, sizes, prev, pager, next" @change="load" /></div></section>
    <el-dialog v-model="dialogVisible" title="新建故障报修" width="min(680px, 96vw)"><el-form :model="form" label-position="top"><el-form-item label="设备"><el-select v-model="form.equipmentId" filterable style="width:100%"><el-option v-for="item in equipment" :key="item.id" :label="`${item.equipmentName}（${item.equipmentCode}）`" :value="item.id" /></el-select></el-form-item><el-form-item label="故障时间"><el-date-picker v-model="form.faultTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" /></el-form-item><el-form-item label="故障标题"><el-input v-model="form.faultTitle" /></el-form-item><el-form-item label="严重度"><el-select v-model="form.severity"><el-option v-for="item in ['LOW','MEDIUM','HIGH','CRITICAL']" :key="item" :value="item" /></el-select></el-form-item><el-form-item label="故障现象"><el-input v-model="form.faultDescription" type="textarea" :rows="4" /></el-form-item></el-form><template #footer><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">提交报修</el-button></template></el-dialog>
  </div>
</template>

<style scoped>.block { display:block; color:var(--tpm-text-secondary); margin-top:3px; }</style>
