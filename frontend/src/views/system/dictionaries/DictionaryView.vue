<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { systemApi, type DictionaryItem, type DictionaryType } from '@/api/system'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const types = ref<DictionaryType[]>([])
const selectedId = ref<number>()
const typeDialog = ref(false)
const itemDialog = ref(false)
const editingType = ref<DictionaryType | null>(null)
const editingItem = ref<DictionaryItem | null>(null)
const typeForm = reactive({ dictCode: '', dictName: '', enabled: true, remark: '' })
const itemForm = reactive({
  itemValue: '',
  itemLabel: '',
  color: '#0b5f7a',
  icon: '',
  enabled: true,
  sortOrder: 0,
  isDefault: false,
})

const selected = computed(() => types.value.find((item) => item.id === selectedId.value))

onMounted(load)

async function load() {
  loading.value = true
  try {
    types.value = await systemApi.dictionaries()
    if (!types.value.some((item) => item.id === selectedId.value)) selectedId.value = types.value[0]?.id
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function openType(row?: DictionaryType) {
  editingType.value = row || null
  Object.assign(typeForm, row
    ? { dictCode: row.dictCode, dictName: row.dictName, enabled: row.status === 1, remark: row.remark || '' }
    : { dictCode: '', dictName: '', enabled: true, remark: '' })
  typeDialog.value = true
}

async function saveType() {
  if (!typeForm.dictCode.trim() || !typeForm.dictName.trim()) {
    ElMessage.warning('请填写字典编码和名称')
    return
  }
  saving.value = true
  try {
    if (editingType.value) {
      await systemApi.updateDictionary(editingType.value.id, {
        ...typeForm,
        version: editingType.value.version,
      })
    } else {
      await systemApi.createDictionary(typeForm)
    }
    typeDialog.value = false
    ElMessage.success('字典类型已保存')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function removeType(row: DictionaryType) {
  await ElMessageBox.confirm(`确认删除字典“${row.dictName}”吗？仅空字典允许删除。`, '删除字典', {
    type: 'warning',
  })
  try {
    await systemApi.deleteDictionary(row.id)
    ElMessage.success('字典已删除')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

function openItem(row?: DictionaryItem) {
  if (!selected.value) return
  editingItem.value = row || null
  Object.assign(itemForm, row
    ? {
        itemValue: row.itemValue,
        itemLabel: row.itemLabel,
        color: row.color || '#0b5f7a',
        icon: row.icon || '',
        enabled: row.status === 1,
        sortOrder: row.sortOrder,
        isDefault: row.isDefault,
      }
    : { itemValue: '', itemLabel: '', color: '#0b5f7a', icon: '', enabled: true, sortOrder: 0, isDefault: false })
  itemDialog.value = true
}

async function saveItem() {
  if (!selected.value || !itemForm.itemValue.trim() || !itemForm.itemLabel.trim()) {
    ElMessage.warning('请填写字典值和显示名称')
    return
  }
  saving.value = true
  try {
    if (editingItem.value) {
      await systemApi.updateDictionaryItem(editingItem.value.id, {
        ...itemForm,
        version: editingItem.value.version,
      })
    } else {
      await systemApi.createDictionaryItem(selected.value.id, itemForm)
    }
    itemDialog.value = false
    ElMessage.success('字典项已保存')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function removeItem(row: DictionaryItem) {
  await ElMessageBox.confirm(`确认删除字典项“${row.itemLabel}”吗？`, '删除字典项', { type: 'warning' })
  try {
    await systemApi.deleteDictionaryItem(row.id)
    ElMessage.success('字典项已删除')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div><h1>字典管理</h1><p>统一维护状态、图标和颜色，业务页面不直接硬编码状态。</p></div>
      <div class="page-actions"><el-button v-if="auth.can('system:dictionary:manage')" type="primary" @click="openType()">新增字典</el-button></div>
    </header>
    <section class="dictionary-layout" v-loading="loading">
      <aside class="surface-card type-panel">
        <div class="panel-heading"><strong>字典类型</strong><span>{{ types.length }}</span></div>
        <button
          v-for="type in types"
          :key="type.id"
          type="button"
          class="type-row"
          :class="{ active: type.id === selectedId }"
          @click="selectedId = type.id"
        >
          <span><strong>{{ type.dictName }}</strong><small>{{ type.dictCode }}</small></span>
          <el-dropdown v-if="auth.can('system:dictionary:manage')" trigger="click" @click.stop>
            <el-button circle text><el-icon><MoreFilled /></el-icon></el-button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item @click="openType(type)">编辑</el-dropdown-item><el-dropdown-item divided @click="removeType(type)">删除</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </button>
        <el-empty v-if="!types.length" description="暂无字典" :image-size="70" />
      </aside>
      <article class="surface-card table-card">
        <div class="table-toolbar">
          <div><span class="table-title">{{ selected?.dictName || '字典项' }}</span><small class="dict-code">{{ selected?.dictCode }}</small></div>
          <el-button v-if="selected && auth.can('system:dictionary:manage')" type="primary" plain @click="openItem()">新增字典项</el-button>
        </div>
        <el-table :data="selected?.items || []" row-key="id">
          <el-table-column prop="itemLabel" label="显示名称" min-width="140" />
          <el-table-column prop="itemValue" label="字典值" min-width="160"><template #default="{ row }"><span class="mono">{{ row.itemValue }}</span></template></el-table-column>
          <el-table-column label="标识" width="100"><template #default="{ row }"><span class="color-chip" :style="{ backgroundColor: row.color || '#909399' }"></span>{{ row.color || '—' }}</template></el-table-column>
          <el-table-column prop="icon" label="图标" min-width="130" />
          <el-table-column prop="sortOrder" label="排序" width="80" />
          <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag></template></el-table-column>
          <el-table-column v-if="auth.can('system:dictionary:manage')" label="操作" width="120" fixed="right">
            <template #default="{ row }"><el-button link type="primary" @click="openItem(row)">编辑</el-button><el-button link type="danger" @click="removeItem(row)">删除</el-button></template>
          </el-table-column>
          <template #empty><el-empty description="请选择字典或新增字典项" /></template>
        </el-table>
      </article>
    </section>

    <el-dialog v-model="typeDialog" :title="editingType ? '编辑字典' : '新增字典'" width="min(480px, 92vw)">
      <el-form label-position="top">
        <el-form-item label="字典编码"><el-input v-model="typeForm.dictCode" :disabled="Boolean(editingType)" placeholder="例如 equipment_status" /></el-form-item>
        <el-form-item label="字典名称"><el-input v-model="typeForm.dictName" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="typeForm.remark" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="typeForm.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="typeDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveType">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="itemDialog" :title="editingItem ? '编辑字典项' : '新增字典项'" width="min(560px, 92vw)">
      <el-form label-position="top" class="item-form">
        <el-form-item label="字典值"><el-input v-model="itemForm.itemValue" :disabled="Boolean(editingItem)" /></el-form-item>
        <el-form-item label="显示名称"><el-input v-model="itemForm.itemLabel" /></el-form-item>
        <el-form-item label="颜色"><el-color-picker v-model="itemForm.color" /></el-form-item>
        <el-form-item label="Element Plus 图标"><el-input v-model="itemForm.icon" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="itemForm.sortOrder" :min="0" /></el-form-item>
        <el-form-item label="选项"><el-checkbox v-model="itemForm.enabled">启用</el-checkbox><el-checkbox v-model="itemForm.isDefault">默认项</el-checkbox></el-form-item>
      </el-form>
      <template #footer><el-button @click="itemDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveItem">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.dictionary-layout { display: grid; grid-template-columns: 280px minmax(0, 1fr); gap: 16px; }
.type-panel { overflow: hidden; padding-bottom: 8px; }
.panel-heading { display: flex; justify-content: space-between; padding: 17px 18px; border-bottom: 1px solid var(--tpm-border); }
.panel-heading span { color: var(--tpm-text-secondary); font-size: 12px; }
.type-row { display: flex; align-items: center; justify-content: space-between; width: calc(100% - 16px); margin: 5px 8px; padding: 11px 10px 11px 13px; border: 0; border-radius: 7px; background: transparent; text-align: left; cursor: pointer; }
.type-row:hover, .type-row.active { color: var(--tpm-primary); background: var(--tpm-primary-soft); }
.type-row > span { display: flex; flex-direction: column; min-width: 0; }
.type-row strong { font-size: 13px; }
.type-row small, .dict-code { margin-top: 3px; color: var(--tpm-text-secondary); font: 10px "SFMono-Regular", Consolas, monospace; }
.table-toolbar > div { display: flex; flex-direction: column; }
.color-chip { display: inline-block; width: 9px; height: 9px; margin-right: 5px; border-radius: 50%; }
.item-form { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
@media (max-width: 760px) { .dictionary-layout { grid-template-columns: 1fr; } .type-panel { max-height: 260px; } .item-form { grid-template-columns: 1fr; } }
</style>
