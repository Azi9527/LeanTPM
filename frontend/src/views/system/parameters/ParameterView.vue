<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { systemApi, type ParameterRow } from '@/api/system'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const rows = ref<ParameterRow[]>([])
const keyword = ref('')
const groupCode = ref('')
const dialogVisible = ref(false)
const editing = ref<ParameterRow | null>(null)
const form = reactive({
  parameterKey: '',
  parameterName: '',
  parameterValue: '',
  valueType: 'STRING' as ParameterRow['valueType'],
  groupCode: 'SYSTEM',
  description: '',
  enabled: true,
})

const groups = computed(() => [...new Set(rows.value.map((row) => row.groupCode))].sort())

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    rows.value = await systemApi.parameters({
      keyword: keyword.value || undefined,
      groupCode: groupCode.value || undefined,
    })
  } catch (error) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

function openDialog(row?: ParameterRow) {
  editing.value = row || null
  Object.assign(
    form,
    row
      ? {
          parameterKey: row.parameterKey,
          parameterName: row.parameterName,
          parameterValue: row.parameterValue,
          valueType: row.valueType,
          groupCode: row.groupCode,
          description: row.description || '',
          enabled: row.status === 1,
        }
      : {
          parameterKey: '',
          parameterName: '',
          parameterValue: '',
          valueType: 'STRING',
          groupCode: 'SYSTEM',
          description: '',
          enabled: true,
        },
  )
  dialogVisible.value = true
}

async function save() {
  if (!form.parameterKey.trim() || !form.parameterName.trim() || !form.groupCode.trim()) {
    ElMessage.warning('请完整填写参数键、名称和分组')
    return
  }
  saving.value = true
  try {
    const payload = {
      ...form,
      groupCode: form.groupCode.trim().toUpperCase(),
      version: editing.value?.version,
    }
    if (editing.value) await systemApi.updateParameter(editing.value.id, payload)
    else await systemApi.createParameter(payload)
    dialogVisible.value = false
    ElMessage.success('系统参数已保存')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function remove(row: ParameterRow) {
  await ElMessageBox.confirm(`确认删除参数“${row.parameterName}”吗？`, '删除参数', {
    type: 'warning',
  })
  try {
    await systemApi.deleteParameter(row.id)
    ElMessage.success('系统参数已删除')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div>
        <h1>系统参数</h1>
        <p>集中维护可运行配置；内置参数可调整但不可删除。</p>
      </div>
      <div class="page-actions">
        <el-button v-if="auth.can('system:parameter:manage')" type="primary" @click="openDialog()">
          新增参数
        </el-button>
      </div>
    </header>

    <section class="surface-card query-bar">
      <el-input
        v-model="keyword"
        clearable
        placeholder="参数键或名称"
        style="width: 280px"
        @keyup.enter="load"
        @clear="load"
      />
      <el-select v-model="groupCode" clearable placeholder="全部分组" style="width: 180px" @change="load">
        <el-option v-for="group in groups" :key="group" :label="group" :value="group" />
      </el-select>
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
        <span class="table-title">参数列表</span>
        <span class="result-count">共 {{ rows.length }} 项</span>
      </div>
      <el-table :data="rows" row-key="id">
        <el-table-column prop="parameterName" label="参数名称" min-width="160" />
        <el-table-column prop="parameterKey" label="参数键" min-width="220">
          <template #default="{ row }"><span class="mono">{{ row.parameterKey }}</span></template>
        </el-table-column>
        <el-table-column prop="parameterValue" label="参数值" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag v-if="row.valueType === 'BOOLEAN'" :type="row.parameterValue === 'true' ? 'success' : 'info'">
              {{ row.parameterValue === 'true' ? '开启' : '关闭' }}
            </el-tag>
            <span v-else class="mono">{{ row.parameterValue }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="valueType" label="类型" width="105" />
        <el-table-column prop="groupCode" label="分组" width="120" />
        <el-table-column label="属性" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.builtIn" type="warning" effect="plain">内置</el-tag>
            <el-tag v-else effect="plain">自定义</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          v-if="auth.can('system:parameter:manage')"
          label="操作"
          width="130"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button
              v-if="!row.builtIn && auth.can('system:parameter:delete')"
              link
              type="danger"
              @click="remove(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无系统参数" /></template>
      </el-table>
    </section>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑参数' : '新增参数'" width="min(620px, 94vw)">
      <el-form label-position="top" class="parameter-form">
        <el-form-item label="参数键">
          <el-input v-model="form.parameterKey" :disabled="Boolean(editing)" placeholder="例如 equipment.import.max-rows" />
        </el-form-item>
        <el-form-item label="参数名称"><el-input v-model="form.parameterName" /></el-form-item>
        <el-form-item label="参数类型">
          <el-select v-model="form.valueType">
            <el-option label="文本" value="STRING" />
            <el-option label="布尔值" value="BOOLEAN" />
            <el-option label="整数" value="INTEGER" />
            <el-option label="小数" value="DECIMAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="参数分组"><el-input v-model="form.groupCode" placeholder="SYSTEM" /></el-form-item>
        <el-form-item label="参数值" class="full-row">
          <el-switch
            v-if="form.valueType === 'BOOLEAN'"
            :model-value="form.parameterValue === 'true'"
            active-text="开启"
            inactive-text="关闭"
            @change="form.parameterValue = $event ? 'true' : 'false'"
          />
          <el-input v-else v-model="form.parameterValue" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="说明" class="full-row">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.result-count { color: var(--tpm-text-secondary); font-size: 12px; }
.parameter-form { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.full-row { grid-column: 1 / -1; }
@media (max-width: 640px) { .parameter-form { grid-template-columns: 1fr; } .full-row { grid-column: auto; } }
</style>
