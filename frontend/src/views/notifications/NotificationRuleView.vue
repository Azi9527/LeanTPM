<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { notificationApi, type NotificationRule } from '@/api/notification'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editing = ref<NotificationRule>()
const rows = ref<NotificationRule[]>([])
const form = reactive({
  ruleCode: '', ruleName: '', businessType: 'INSPECTION', triggerType: 'DUE_SOON',
  advanceMinutes: 60, repeatMinutes: 0, escalationLevel: 0,
  recipientType: 'ASSIGNEE', severity: 'MEDIUM', channels: ['SYSTEM', 'ANDROID'],
  acknowledgeRequired: false, enabled: true, version: 0,
})

const businessLabels: Record<string, string> = { INSPECTION: '点检', MAINTENANCE: '维保' }
const triggerLabels: Record<string, string> = { DUE_SOON: '到期前提醒', MANUAL_CREATED: '临时任务提醒', OVERDUE: '逾期提醒' }
const recipientLabels: Record<string, string> = { ASSIGNEE: '执行人', TEAM_LEADER: '班组长', WORKSHOP_MANAGER: '车间主任' }
const severityLabels: Record<string, string> = { LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '紧急' }
const channelLabels: Record<string, string> = { SYSTEM: '站内消息', ANDROID: 'Android APP', SMS: '短信', WECHAT: '微信', EMAIL: '邮件' }

onMounted(load)

async function load() {
  loading.value = true
  try { rows.value = await notificationApi.rules() }
  catch (error) { ElMessage.error(errorMessage(error, '提醒规则加载失败')) }
  finally { loading.value = false }
}

function openCreate() {
  editing.value = undefined
  Object.assign(form, { ruleCode: '', ruleName: '', businessType: 'INSPECTION', triggerType: 'DUE_SOON', advanceMinutes: 60, repeatMinutes: 0, escalationLevel: 0, recipientType: 'ASSIGNEE', severity: 'MEDIUM', channels: ['SYSTEM', 'ANDROID'], acknowledgeRequired: false, enabled: true, version: 0 })
  dialogVisible.value = true
}

function openEdit(row: NotificationRule) {
  editing.value = row
  Object.assign(form, { ...row, channels: [...row.channels] })
  dialogVisible.value = true
}

async function save() {
  if (!form.ruleCode || !form.ruleName || !form.channels.length) return ElMessage.warning('请填写规则名称、编码和渠道')
  saving.value = true
  try {
    if (editing.value) await notificationApi.updateRule(editing.value.id, form)
    else await notificationApi.createRule(form)
    ElMessage.success('提醒规则已保存')
    dialogVisible.value = false
    await load()
  } catch (error) { ElMessage.error(errorMessage(error, '提醒规则保存失败')) }
  finally { saving.value = false }
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header"><div><h1>提醒规则</h1><p>配置到期、临时任务和逾期逐级升级策略。</p></div><el-button v-if="auth.can('notification:rule:manage')" type="primary" @click="openCreate">新增规则</el-button></header>
    <section class="surface-card table-card">
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="ruleName" label="规则" min-width="180" />
        <el-table-column prop="businessType" label="业务" width="130"><template #default="{ row }">{{ businessLabels[row.businessType] || row.businessType }}</template></el-table-column>
        <el-table-column prop="triggerType" label="触发" width="150"><template #default="{ row }">{{ triggerLabels[row.triggerType] || row.triggerType }}</template></el-table-column>
        <el-table-column prop="advanceMinutes" label="提前/逾期分钟" width="140" />
        <el-table-column prop="recipientType" label="接收人" width="170"><template #default="{ row }">{{ recipientLabels[row.recipientType] || row.recipientType }}</template></el-table-column>
        <el-table-column label="渠道" min-width="160"><template #default="{ row }">{{ row.channels.map((item: string) => channelLabels[item] || item).join('、') }}</template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="90"><template #default="{ row }"><el-button v-if="auth.can('notification:rule:manage')" link type="primary" @click="openEdit(row)">编辑</el-button></template></el-table-column>
      </el-table>
    </section>
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑提醒规则' : '新增提醒规则'" width="min(760px, 96vw)">
      <el-form :model="form" label-position="top" class="rule-form">
        <el-form-item label="规则编码" required><el-input v-model="form.ruleCode" :disabled="Boolean(editing)" placeholder="例如：INSPECTION_DUE_SOON" /><div class="field-hint">以大写字母开头，只能填写大写字母、数字和下划线，共 3～64 位。</div></el-form-item>
        <el-form-item label="规则名称" required><el-input v-model="form.ruleName" /></el-form-item>
        <el-form-item label="业务类型" required><el-select v-model="form.businessType"><el-option label="点检" value="INSPECTION" /><el-option label="维保" value="MAINTENANCE" /></el-select></el-form-item>
        <el-form-item label="触发类型" required><el-select v-model="form.triggerType"><el-option label="到期前" value="DUE_SOON" /><el-option label="临时任务立即" value="MANUAL_CREATED" /><el-option label="逾期" value="OVERDUE" /></el-select></el-form-item>
        <el-form-item label="提前/逾期延迟（分钟）"><el-input-number v-model="form.advanceMinutes" :min="0" /></el-form-item>
        <el-form-item label="重复间隔（分钟，0 表示一次）"><el-input-number v-model="form.repeatMinutes" :min="0" /></el-form-item>
        <el-form-item label="升级级别"><el-input-number v-model="form.escalationLevel" :min="0" :max="9" /></el-form-item>
        <el-form-item label="接收人" required><el-select v-model="form.recipientType"><el-option v-for="(label, value) in recipientLabels" :key="value" :label="label" :value="value" /></el-select></el-form-item>
        <el-form-item label="提醒等级" required><el-select v-model="form.severity"><el-option v-for="(label, value) in severityLabels" :key="value" :label="label" :value="value" /></el-select></el-form-item>
        <el-form-item label="发送渠道" required><el-select v-model="form.channels" multiple><el-option v-for="(label, value) in channelLabels" :key="value" :label="label" :value="value" /></el-select></el-form-item>
        <el-form-item label="需要确认"><el-switch v-model="form.acknowledgeRequired" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.rule-form { display: grid; grid-template-columns: 1fr 1fr; gap: 0 18px; }
.rule-form :deep(.el-select), .rule-form :deep(.el-input-number) { width: 100%; }
.field-hint { margin-top: 5px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.5; }
@media (max-width: 640px) { .rule-form { grid-template-columns: 1fr; } }
</style>
