import { computed, defineComponent, h, inject, ref, useAttrs, watch } from 'vue'
import { Filter } from '@element-plus/icons-vue'
import { ElButton, ElDialog, ElIcon, ElInput, ElOption, ElSelect, ElTableColumn } from 'element-plus'
import {
  filterText,
  inferField,
  readField,
  smartTableContextKey,
  type SmartFilterOperator,
} from './smart-table-context'

const textOperators: Array<{ value: SmartFilterOperator; label: string }> = [
  { value: 'CONTAINS', label: '包含' },
  { value: 'NOT_CONTAINS', label: '不包含' },
  { value: 'EQ', label: '等于' },
  { value: 'NE', label: '不等于' },
  { value: 'STARTS_WITH', label: '开头是' },
  { value: 'ENDS_WITH', label: '结尾是' },
  { value: 'EMPTY', label: '为空' },
  { value: 'NOT_EMPTY', label: '不为空' },
]

const numberOperators: Array<{ value: SmartFilterOperator; label: string }> = [
  { value: 'EQ', label: '等于' },
  { value: 'NE', label: '不等于' },
  { value: 'GT', label: '大于' },
  { value: 'GTE', label: '大于等于' },
  { value: 'LT', label: '小于' },
  { value: 'LTE', label: '小于等于' },
  { value: 'BETWEEN', label: '介于' },
  { value: 'EMPTY', label: '为空' },
  { value: 'NOT_EMPTY', label: '不为空' },
]

export default defineComponent({
  name: 'LeanSmartTableColumn',
  inheritAttrs: false,
  setup(_, { slots }) {
    const attrs = useAttrs()
    const table = inject(smartTableContextKey, null)
    const operator = ref<SmartFilterOperator>('CONTAINS')
    const value = ref('')
    const secondValue = ref('')
    const filterDialogVisible = ref(false)
    const property = computed(() => {
      if (typeof attrs.prop === 'string') return attrs.prop
      return inferField(attrs.label, table?.rows.value[0])
    })
    const filterFormatter = computed(() => {
      const formatter = attrs.filterFormatter ?? attrs['filter-formatter']
      return typeof formatter === 'function'
        ? formatter as (value: unknown, row: Record<string, unknown>) => unknown
        : undefined
    })
    const filters = computed(() => {
      if (!table || !property.value) return []
      const values = new Map<string, string>()
      table.rows.value.forEach((row) => {
        const raw = readField(row, property.value)
        const rawValue = raw === null || raw === undefined ? '' : String(raw)
        values.set(
          rawValue,
          filterText(filterFormatter.value ? filterFormatter.value(raw, row) : raw),
        )
      })
      return [...values.entries()]
        .sort((left, right) => left[1].localeCompare(right[1], 'zh-CN', { numeric: true }))
        .slice(0, 200)
        .map(([value, text]) => ({ text, value }))
    })
    const filterType = computed(() => {
      const configured = attrs.smartFilter ?? attrs['smart-filter']
      if (configured === 'none' || configured === 'number' || configured === 'date' || configured === 'select') return configured
      const firstValue = property.value && table?.rows.value.length
        ? readField(table.rows.value[0], property.value)
        : undefined
      return typeof firstValue === 'number' ? 'number' : 'text'
    })
    const operators = computed(() => (
      filterType.value === 'number' || filterType.value === 'date' ? numberOperators : textOperators
    ))
    const activeFilter = computed(() => table?.query.value.filters.find((item) => item.field === property.value))

    watch(activeFilter, (filter) => {
      operator.value = filter?.operator || (
        filterType.value === 'number' || filterType.value === 'date' ? 'EQ' : 'CONTAINS'
      )
      value.value = filter?.value || filter?.values?.[0] || ''
      secondValue.value = filter?.values?.[1] || ''
    }, { immediate: true })

    function applyFilter() {
      if (!table || !property.value) return
      const noValue = ['EMPTY', 'NOT_EMPTY'].includes(operator.value)
      const values = operator.value === 'BETWEEN'
        ? [value.value.trim(), secondValue.value.trim()]
        : undefined
      if (!noValue && (values ? values.some((item) => !item) : !value.value.trim())) return
      table.updateFilter({
        field: property.value,
        label: String(attrs.label || property.value),
        operator: operator.value,
        ...(values ? { values } : noValue ? {} : { value: value.value.trim() }),
      })
      filterDialogVisible.value = false
    }

    function clearFilter() {
      if (table && property.value) table.removeFilter(property.value)
      value.value = ''
      secondValue.value = ''
      filterDialogVisible.value = false
    }

    function serverHeader() {
      const prop = property.value
      if (!table?.serverMode.value || !prop || filterType.value === 'none') return undefined
      const selectOptions = filters.value
      const valueControl = filterType.value === 'select'
        ? h(ElSelect, {
            modelValue: value.value,
            placeholder: '选择筛选值',
            clearable: true,
            filterable: true,
            'onUpdate:modelValue': (next: string) => { value.value = next || '' },
          }, {
            default: () => selectOptions.map((item) => h(ElOption, {
              key: String(item.value),
              label: item.text,
              value: String(item.value),
            })),
          })
        : h(ElInput, {
            modelValue: value.value,
            type: filterType.value === 'number' ? 'number' : filterType.value === 'date' ? 'date' : 'text',
            clearable: true,
            placeholder: '输入筛选值',
            'onUpdate:modelValue': (next: string) => { value.value = next },
            onKeyup: (event: KeyboardEvent) => { if (event.key === 'Enter') applyFilter() },
          })
      const panel = h('div', { class: 'smart-table-filter-panel' }, [
        h('strong', `${String(attrs.label || prop)}筛选`),
        h(ElSelect, {
          modelValue: operator.value,
          'onUpdate:modelValue': (next: SmartFilterOperator) => { operator.value = next },
        }, {
          default: () => operators.value.map((item) => h(ElOption, {
            key: item.value,
            label: item.label,
            value: item.value,
          })),
        }),
        ...(['EMPTY', 'NOT_EMPTY'].includes(operator.value) ? [] : [valueControl]),
        ...(operator.value === 'BETWEEN' ? [h(ElInput, {
          modelValue: secondValue.value,
          type: filterType.value === 'number' ? 'number' : filterType.value === 'date' ? 'date' : 'text',
          placeholder: '结束值',
          'onUpdate:modelValue': (next: string) => { secondValue.value = next },
          onKeyup: (event: KeyboardEvent) => { if (event.key === 'Enter') applyFilter() },
        })] : []),
        h('div', { class: 'smart-table-filter-panel__actions' }, [
          h(ElButton, { size: 'small', onClick: clearFilter }, { default: () => '清除' }),
          h(ElButton, { size: 'small', type: 'primary', onClick: applyFilter }, { default: () => '应用' }),
        ]),
      ])
      return () => h('div', { class: ['smart-table-header', activeFilter.value ? 'is-filtered' : ''] }, [
        h('span', { class: 'smart-table-header__label' }, String(attrs.label || prop)),
        h(ElButton, {
          link: true,
          type: activeFilter.value ? 'primary' : undefined,
          class: 'smart-table-header__filter',
          title: `${String(attrs.label || prop)}表头筛选`,
          onClick: (event: MouseEvent) => {
            event.stopPropagation()
            filterDialogVisible.value = true
          },
        }, { default: () => h(ElIcon, null, { default: () => h(Filter) }) }),
        h(ElDialog, {
          modelValue: filterDialogVisible.value,
          'onUpdate:modelValue': (next: boolean) => { filterDialogVisible.value = next },
          title: `${String(attrs.label || prop)}复合筛选`,
          width: 'min(420px, 92vw)',
          appendToBody: true,
          closeOnClickModal: true,
        }, { default: () => panel }),
      ])
    }

    return () => {
      const prop = property.value
      const columnAttrs = { ...attrs }
      delete columnAttrs.filterFormatter
      delete columnAttrs['filter-formatter']
      delete columnAttrs.smartFilter
      delete columnAttrs['smart-filter']
      const serverMode = Boolean(table?.serverMode.value)
      const sortable = attrs.sortable === undefined && prop ? (serverMode ? 'custom' : true) : attrs.sortable
      const resolvedFilters = serverMode ? undefined : (attrs.filters === undefined && prop ? filters.value : attrs.filters)
      const filterMethod = serverMode ? undefined : (attrs.filterMethod === undefined && prop
        ? (value: unknown, row: Record<string, unknown>) => (
            filterText(filterFormatter.value
              ? filterFormatter.value(readField(row, prop), row)
              : readField(row, prop)) === String(value)
          )
        : attrs.filterMethod)
      const resolvedSlots = serverMode && prop && filterType.value !== 'none'
        ? { ...slots, header: serverHeader() }
        : slots
      return h(ElTableColumn as any, {
        ...columnAttrs,
        prop: prop || undefined,
        sortable,
        filters: resolvedFilters,
        filterMethod,
        filterMultiple: attrs.filterMultiple ?? true,
        filterPlacement: attrs.filterPlacement ?? 'bottom-start',
      } as any, resolvedSlots)
    }
  },
})
