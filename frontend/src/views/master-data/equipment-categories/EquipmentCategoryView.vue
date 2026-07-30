<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  masterDataApi,
  type AttributeDefinitionRow,
  type EquipmentCategoryRow,
} from '@/api/masterData'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/http'

interface CategoryTreeNode extends EquipmentCategoryRow {
  children: CategoryTreeNode[]
}

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const rows = ref<EquipmentCategoryRow[]>([])
const keyword = ref('')
const categoryDialogVisible = ref(false)
const editingCategory = ref<EquipmentCategoryRow | null>(null)
const attributeDrawerVisible = ref(false)
const attributeDialogVisible = ref(false)
const attributesLoading = ref(false)
const includeInherited = ref(true)
const selectedCategory = ref<EquipmentCategoryRow | null>(null)
const attributes = ref<AttributeDefinitionRow[]>([])
const editingAttribute = ref<AttributeDefinitionRow | null>(null)

const categoryForm = reactive({
  parentId: 0,
  categoryCode: '',
  categoryName: '',
  defaultOeeMode: 'STANDARD',
  sortOrder: 0,
  enabled: true,
  description: '',
})

const attributeForm = reactive({
  attributeCode: '',
  attributeName: '',
  dataType: 'STRING' as AttributeDefinitionRow['dataType'],
  unit: '',
  required: false,
  defaultValue: '',
  validationPattern: '',
  minimumValue: undefined as number | undefined,
  maximumValue: undefined as number | undefined,
  enumOptionsText: '',
  sortOrder: 0,
  enabled: true,
  description: '',
})

const dataTypeLabels: Record<AttributeDefinitionRow['dataType'], string> = {
  STRING: '文本',
  INTEGER: '整数',
  DECIMAL: '小数',
  BOOLEAN: '布尔',
  DATE: '日期',
  ENUM: '枚举',
}

const treeRows = computed(() => buildTree(filterWithAncestors(rows.value, keyword.value)))
const parentTree = computed(() => buildTree(
  rows.value.filter((row) => row.id !== editingCategory.value?.id),
))

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    rows.value = await masterDataApi.categories()
  } catch (error) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

function buildTree(source: EquipmentCategoryRow[]): CategoryTreeNode[] {
  const nodes = new Map<number, CategoryTreeNode>()
  source.forEach((row) => nodes.set(row.id, { ...row, children: [] }))
  const roots: CategoryTreeNode[] = []
  nodes.forEach((node) => {
    const parent = nodes.get(node.parentId)
    if (parent) parent.children.push(node)
    else roots.push(node)
  })
  return roots
}

function filterWithAncestors(source: EquipmentCategoryRow[], value: string): EquipmentCategoryRow[] {
  const needle = value.trim().toLowerCase()
  if (!needle) return source
  const byId = new Map(source.map((row) => [row.id, row]))
  const ids = new Set<number>()
  source.forEach((row) => {
    if (`${row.categoryCode} ${row.categoryName}`.toLowerCase().includes(needle)) {
      let cursor: EquipmentCategoryRow | undefined = row
      while (cursor && !ids.has(cursor.id)) {
        ids.add(cursor.id)
        cursor = byId.get(cursor.parentId)
      }
    }
  })
  return source.filter((row) => ids.has(row.id))
}

function openCategoryDialog(row?: EquipmentCategoryRow, asChild = false) {
  editingCategory.value = asChild ? null : row || null
  Object.assign(categoryForm, editingCategory.value
    ? {
        parentId: editingCategory.value.parentId,
        categoryCode: editingCategory.value.categoryCode,
        categoryName: editingCategory.value.categoryName,
        defaultOeeMode: editingCategory.value.defaultOeeMode || 'STANDARD',
        sortOrder: editingCategory.value.sortOrder,
        enabled: editingCategory.value.status === 1,
        description: editingCategory.value.description || '',
      }
    : {
        parentId: row?.id || 0,
        categoryCode: '',
        categoryName: '',
        defaultOeeMode: row?.defaultOeeMode || 'STANDARD',
        sortOrder: 0,
        enabled: true,
        description: '',
      })
  categoryDialogVisible.value = true
}

async function saveCategory() {
  if (!categoryForm.categoryCode.trim() || !categoryForm.categoryName.trim()) {
    ElMessage.warning('请完整填写分类编码和名称')
    return
  }
  saving.value = true
  try {
    const payload = {
      ...categoryForm,
      categoryCode: categoryForm.categoryCode.trim().toUpperCase(),
      categoryName: categoryForm.categoryName.trim(),
      version: editingCategory.value?.version,
    }
    if (editingCategory.value) {
      await masterDataApi.updateCategory(editingCategory.value.id, payload)
    } else {
      await masterDataApi.createCategory(payload)
    }
    categoryDialogVisible.value = false
    ElMessage.success('设备分类已保存')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function removeCategory(row: EquipmentCategoryRow) {
  await ElMessageBox.confirm(`确认删除分类“${row.categoryName}”吗？`, '删除分类', {
    type: 'warning',
  })
  try {
    await masterDataApi.deleteCategory(row.id, row.version)
    ElMessage.success('设备分类已删除')
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function openAttributes(row: EquipmentCategoryRow) {
  selectedCategory.value = row
  attributeDrawerVisible.value = true
  await loadAttributes()
}

async function loadAttributes() {
  if (!selectedCategory.value) return
  attributesLoading.value = true
  try {
    attributes.value = await masterDataApi.attributes(
      selectedCategory.value.id,
      includeInherited.value,
    )
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    attributesLoading.value = false
  }
}

function parseEnumOptions(json?: string): string {
  if (!json) return ''
  try {
    return (JSON.parse(json) as string[]).join('\n')
  } catch {
    return ''
  }
}

function openAttributeDialog(row?: AttributeDefinitionRow) {
  editingAttribute.value = row || null
  Object.assign(attributeForm, row
    ? {
        attributeCode: row.attributeCode,
        attributeName: row.attributeName,
        dataType: row.dataType,
        unit: row.unit || '',
        required: row.requiredFlag,
        defaultValue: row.defaultValue || '',
        validationPattern: row.validationPattern || '',
        minimumValue: row.minimumValue,
        maximumValue: row.maximumValue,
        enumOptionsText: parseEnumOptions(row.enumOptionsJson),
        sortOrder: row.sortOrder,
        enabled: row.status === 1,
        description: row.description || '',
      }
    : {
        attributeCode: '',
        attributeName: '',
        dataType: 'STRING',
        unit: '',
        required: false,
        defaultValue: '',
        validationPattern: '',
        minimumValue: undefined,
        maximumValue: undefined,
        enumOptionsText: '',
        sortOrder: 0,
        enabled: true,
        description: '',
      })
  attributeDialogVisible.value = true
}

async function saveAttribute() {
  if (!selectedCategory.value
    || !attributeForm.attributeCode.trim()
    || !attributeForm.attributeName.trim()) {
    ElMessage.warning('请完整填写属性编码和名称')
    return
  }
  const enumOptions = attributeForm.enumOptionsText
    .split(/[\n,，]/)
    .map((item) => item.trim())
    .filter(Boolean)
  saving.value = true
  try {
    const payload = {
      attributeCode: attributeForm.attributeCode.trim().toUpperCase(),
      attributeName: attributeForm.attributeName.trim(),
      dataType: attributeForm.dataType,
      unit: attributeForm.unit || undefined,
      required: attributeForm.required,
      defaultValue: attributeForm.defaultValue || undefined,
      validationPattern: attributeForm.validationPattern || undefined,
      minimumValue: attributeForm.minimumValue,
      maximumValue: attributeForm.maximumValue,
      enumOptions,
      sortOrder: attributeForm.sortOrder,
      enabled: attributeForm.enabled,
      description: attributeForm.description || undefined,
      version: editingAttribute.value?.version,
    }
    if (editingAttribute.value) {
      await masterDataApi.updateAttribute(editingAttribute.value.id, payload)
    } else {
      await masterDataApi.createAttribute(selectedCategory.value.id, payload)
    }
    attributeDialogVisible.value = false
    ElMessage.success('属性模板已保存')
    await loadAttributes()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function removeAttribute(row: AttributeDefinitionRow) {
  await ElMessageBox.confirm(`确认删除属性“${row.attributeName}”吗？`, '删除属性', {
    type: 'warning',
  })
  try {
    await masterDataApi.deleteAttribute(row.id, row.version)
    ElMessage.success('属性已删除')
    await loadAttributes()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}
</script>

<template>
  <div class="page-shell">
    <header class="page-header">
      <div>
        <h1>设备分类与属性模板</h1>
        <p>多级分类继承技术属性，并预留点检、维保、故障和 OEE 默认配置。</p>
      </div>
      <el-button
        v-if="auth.can('master-data:equipment-category:manage')"
        type="primary"
        @click="openCategoryDialog()"
      >
        新增根分类
      </el-button>
    </header>

    <section class="surface-card query-bar">
      <el-input
        v-model="keyword"
        clearable
        placeholder="分类编码或名称"
        style="width: min(360px, 100%)"
      />
      <el-button type="primary" plain @click="load">刷新</el-button>
    </section>

    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false">
      <template #default><el-button link type="primary" @click="load">重新加载</el-button></template>
    </el-alert>

    <section class="surface-card table-card" v-loading="loading">
      <div class="table-toolbar">
        <span class="table-title">分类树</span>
        <span class="result-count">共 {{ rows.length }} 个分类</span>
      </div>
      <el-table :data="treeRows" row-key="id" default-expand-all :tree-props="{ children: 'children' }">
        <el-table-column prop="categoryName" label="分类名称" min-width="220" />
        <el-table-column prop="categoryCode" label="分类编码" min-width="150">
          <template #default="{ row }"><span class="mono">{{ row.categoryCode }}</span></template>
        </el-table-column>
        <el-table-column prop="treeLevel" label="层级" width="80" />
        <el-table-column prop="defaultOeeMode" label="OEE 方式" width="110">
          <template #default="{ row }">{{ row.defaultOeeMode || '未配置' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="285" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openAttributes(row)">属性模板</el-button>
            <template v-if="auth.can('master-data:equipment-category:manage')">
              <el-button link type="primary" @click="openCategoryDialog(row, true)">新增下级</el-button>
              <el-button link type="primary" @click="openCategoryDialog(row)">编辑</el-button>
            </template>
            <el-button
              v-if="auth.can('master-data:equipment-category:delete')"
              link
              type="danger"
              @click="removeCategory(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无设备分类" /></template>
      </el-table>
    </section>

    <el-dialog
      v-model="categoryDialogVisible"
      :title="editingCategory ? '编辑设备分类' : '新增设备分类'"
      width="min(680px, 94vw)"
    >
      <el-form label-position="top" class="edit-form">
        <el-form-item label="上级分类">
          <el-tree-select
            v-model="categoryForm.parentId"
            :data="parentTree"
            node-key="id"
            check-strictly
            :render-after-expand="false"
            :props="{ label: 'categoryName', children: 'children' }"
            clearable
            @clear="categoryForm.parentId = 0"
          />
        </el-form-item>
        <el-form-item label="分类编码">
          <el-input v-model="categoryForm.categoryCode" :disabled="Boolean(editingCategory)" />
        </el-form-item>
        <el-form-item label="分类名称"><el-input v-model="categoryForm.categoryName" /></el-form-item>
        <el-form-item label="默认 OEE 方式">
          <el-select v-model="categoryForm.defaultOeeMode" clearable>
            <el-option label="标准 OEE" value="STANDARD" />
            <el-option label="仅时间开动率" value="AVAILABILITY_ONLY" />
            <el-option label="不计算" value="DISABLED" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序"><el-input-number v-model="categoryForm.sortOrder" :min="0" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="categoryForm.enabled" /></el-form-item>
        <el-form-item label="说明" class="full-row">
          <el-input v-model="categoryForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="categoryDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveCategory">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="attributeDrawerVisible"
      :title="`${selectedCategory?.categoryName || ''} · 属性模板`"
      size="min(900px, 96vw)"
    >
      <div class="drawer-toolbar">
        <el-checkbox v-model="includeInherited" @change="loadAttributes">显示继承属性</el-checkbox>
        <el-button
          v-if="auth.can('master-data:equipment-attribute:manage')"
          type="primary"
          @click="openAttributeDialog()"
        >
          新增属性
        </el-button>
      </div>
      <el-table :data="attributes" v-loading="attributesLoading" row-key="id">
        <el-table-column prop="attributeName" label="属性名称" min-width="140">
          <template #default="{ row }">
            {{ row.attributeName }}
            <el-tag v-if="row.inherited" size="small" effect="plain">继承自 {{ row.categoryName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="attributeCode" label="属性编码" min-width="150">
          <template #default="{ row }"><span class="mono">{{ row.attributeCode }}</span></template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ dataTypeLabels[row.dataType as AttributeDefinitionRow['dataType']] }}</template>
        </el-table-column>
        <el-table-column prop="unit" label="单位" width="80">
          <template #default="{ row }">{{ row.unit || '—' }}</template>
        </el-table-column>
        <el-table-column label="必填" width="75">
          <template #default="{ row }">{{ row.requiredFlag ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">{{ row.status === 1 ? '启用' : '停用' }}</template>
        </el-table-column>
        <el-table-column
          v-if="auth.can('master-data:equipment-attribute:manage')"
          label="操作"
          width="120"
          fixed="right"
        >
          <template #default="{ row }">
            <template v-if="!row.inherited">
              <el-button link type="primary" @click="openAttributeDialog(row)">编辑</el-button>
              <el-button link type="danger" @click="removeAttribute(row)">删除</el-button>
            </template>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无属性模板" /></template>
      </el-table>
    </el-drawer>

    <el-dialog
      v-model="attributeDialogVisible"
      :title="editingAttribute ? '编辑属性' : '新增属性'"
      width="min(760px, 94vw)"
      append-to-body
    >
      <el-form label-position="top" class="edit-form">
        <el-form-item label="属性编码">
          <el-input v-model="attributeForm.attributeCode" :disabled="Boolean(editingAttribute)" />
        </el-form-item>
        <el-form-item label="属性名称"><el-input v-model="attributeForm.attributeName" /></el-form-item>
        <el-form-item label="数据类型">
          <el-select v-model="attributeForm.dataType">
            <el-option v-for="(label, value) in dataTypeLabels" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="单位"><el-input v-model="attributeForm.unit" placeholder="例如 kW、mm" /></el-form-item>
        <el-form-item label="默认值"><el-input v-model="attributeForm.defaultValue" /></el-form-item>
        <el-form-item label="校验正则"><el-input v-model="attributeForm.validationPattern" /></el-form-item>
        <el-form-item v-if="['INTEGER', 'DECIMAL'].includes(attributeForm.dataType)" label="最小值">
          <el-input-number v-model="attributeForm.minimumValue" controls-position="right" />
        </el-form-item>
        <el-form-item v-if="['INTEGER', 'DECIMAL'].includes(attributeForm.dataType)" label="最大值">
          <el-input-number v-model="attributeForm.maximumValue" controls-position="right" />
        </el-form-item>
        <el-form-item v-if="attributeForm.dataType === 'ENUM'" label="枚举选项" class="full-row">
          <el-input
            v-model="attributeForm.enumOptionsText"
            type="textarea"
            :rows="4"
            placeholder="每行一个选项，也可用逗号分隔"
          />
        </el-form-item>
        <el-form-item label="排序"><el-input-number v-model="attributeForm.sortOrder" :min="0" /></el-form-item>
        <el-form-item label="必填"><el-switch v-model="attributeForm.required" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="attributeForm.enabled" /></el-form-item>
        <el-form-item label="说明" class="full-row">
          <el-input v-model="attributeForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="attributeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveAttribute">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.result-count { color: var(--tpm-text-secondary); font-size: 12px; }
.edit-form { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.full-row { grid-column: 1 / -1; }
.drawer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
@media (max-width: 640px) {
  .edit-form { grid-template-columns: 1fr; }
  .full-row { grid-column: auto; }
}
</style>
