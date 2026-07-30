<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { systemApi, type NumberRuleRow } from '@/api/system'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const rows = ref<NumberRuleRow[]>([])
const keyword = ref('')
const dialogVisible = ref(false)
const editing = ref<NumberRuleRow | null>(null)
const form = reactive({
  ruleCode: '',
  ruleName: '',
  prefix: '',
  datePattern: 'yyyyMMdd',
  separatorValue: '-',
  sequenceLength: 4,
  resetPeriod: 'DAILY' as NumberRuleRow['resetPeriod'],
  enabled: true,
  description: '',
})

const resetLabels: Record<NumberRuleRow['resetPeriod'], string> = {
  DAILY: '每天重置',
  MONTHLY: '每月重置',
  YEARLY: '每年重置',
  NEVER: '永不重置',
}

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    rows.value = await systemApi.numberRules({ keyword: keyword.value || undefined })
  } catch (error) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

function openDialog(row?: NumberRuleRow) {
  editing.value = row || null
  Object.assign(
    form,
    row
      ? {
          ruleCode: row.ruleCode,
          ruleName: row.ruleName,
          prefix: row.prefix,
          datePattern: row.datePattern,
          separatorValue: row.separatorValue,
          sequenceLength: row.sequenceLength,
          resetPeriod: row.resetPeriod,
          enabled: row.status === 1,
          description: row.description || '',
        }
      : {
          ruleCode: '',
          ruleName: '',
          prefix: '',
          datePattern: 'yyyyMMdd',
          separatorValue: '-',
          sequenceLength: 4,
          resetPeriod: 'DAILY',
          enabled: true,
          description: '',
        },
  )
  dialogVisible.value = true
}

async function save() {
  if (!form.ruleCode.trim() || !form.ruleName.trim()) {
    ElMessage.warning('请填写规则编码和名称')
    return
  }
  saving.value = true
  try {
    const payload = {
      ...form,
      ruleCode: form.ruleCode.trim().toUpperCase(),
      prefix: form.prefix.trim().toUpperCase(),
      version: editing.value?.version,
    }
    if (editing.value) await systemApi.updateNumberRule(editing.value.id, payload)
    else await systemApi.createNumberRule(payload)
    dialogVisible.value = false
    ElMessage.success('编号规则已保存')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function generate(row: NumberRuleRow) {
  await ElMessageBox.confirm(
    `将按“${row.ruleName}”占用下一个正式流水号。确认继续吗？`,
    '生成正式编号',
    { type: 'warning' },
  )
  try {
    const response = await systemApi.generateNumber(row.ruleCode)
    ElMessage.success(`已生成：${response.data.data.businessNumber}`)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div>
        <h1>编号规则</h1>
        <p>统一生成设备、点检和维保业务编号，流水号由数据库原子递增。</p>
      </div>
      <div class="page-actions">
        <el-button v-if="auth.can('system:number-rule:manage')" type="primary" @click="openDialog()">
          新增规则
        </el-button>
      </div>
    </header>

    <section class="surface-card query-bar">
      <el-input
        v-model="keyword"
        clearable
        placeholder="规则编码或名称"
        style="width: 300px"
        @keyup.enter="load"
        @clear="load"
      />
      <el-button type="primary" plain @click="load">查询</el-button>
    </section>

    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      show-icon
      :closable="false"
    >
      <template #default><el-button link type="primary" @click="load">重新加载</el-button></template>
    </el-alert>

    <section class="surface-card table-card" v-loading="loading">
      <div class="table-toolbar">
        <span class="table-title">编号规则</span>
        <span class="rule-note">预览不会占用流水号；生成按钮会占用正式编号</span>
      </div>
      <el-table :data="rows" row-key="id">
        <el-table-column prop="ruleName" label="规则名称" min-width="150" />
        <el-table-column prop="ruleCode" label="规则编码" min-width="170">
          <template #default="{ row }"><span class="mono">{{ row.ruleCode }}</span></template>
        </el-table-column>
        <el-table-column prop="preview" label="格式预览" min-width="210">
          <template #default="{ row }"><strong class="number-preview">{{ row.preview }}</strong></template>
        </el-table-column>
        <el-table-column label="重置周期" width="120">
          <template #default="{ row }">{{ resetLabels[row.resetPeriod as NumberRuleRow['resetPeriod']] }}</template>
        </el-table-column>
        <el-table-column prop="sequenceLength" label="流水位数" width="95" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="auth.can('system:number-rule:manage')"
              link
              type="primary"
              @click="openDialog(row)"
            >
              编辑
            </el-button>
            <el-button
              v-if="auth.can('system:number-rule:generate')"
              link
              type="success"
              :disabled="row.status !== 1"
              @click="generate(row)"
            >
              生成编号
            </el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无编号规则" /></template>
      </el-table>
    </section>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑编号规则' : '新增编号规则'" width="min(680px, 94vw)">
      <el-form label-position="top" class="rule-form">
        <el-form-item label="规则编码">
          <el-input v-model="form.ruleCode" :disabled="Boolean(editing)" placeholder="例如 WORK_ORDER" />
        </el-form-item>
        <el-form-item label="规则名称"><el-input v-model="form.ruleName" /></el-form-item>
        <el-form-item label="固定前缀"><el-input v-model="form.prefix" placeholder="例如 EQP" /></el-form-item>
        <el-form-item label="日期格式">
          <el-select v-model="form.datePattern">
            <el-option label="年月日（yyyyMMdd）" value="yyyyMMdd" />
            <el-option label="年月（yyyyMM）" value="yyyyMM" />
            <el-option label="年份（yyyy）" value="yyyy" />
            <el-option label="两位年份年月日（yyMMdd）" value="yyMMdd" />
            <el-option label="不包含日期" value="" />
          </el-select>
        </el-form-item>
        <el-form-item label="分隔符">
          <el-input v-model="form.separatorValue" maxlength="5" placeholder="例如 -" />
        </el-form-item>
        <el-form-item label="流水位数">
          <el-input-number v-model="form.sequenceLength" :min="2" :max="12" />
        </el-form-item>
        <el-form-item label="重置周期">
          <el-select v-model="form.resetPeriod">
            <el-option v-for="(label, value) in resetLabels" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
        <el-form-item label="说明" class="full-row">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.rule-note { color: var(--tpm-text-secondary); font-size: 12px; }
.number-preview { color: var(--tpm-primary); font-family: "SFMono-Regular", Consolas, monospace; }
.rule-form { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.full-row { grid-column: 1 / -1; }
@media (max-width: 640px) { .rule-form { grid-template-columns: 1fr; } .full-row { grid-column: auto; } }
</style>
