import {
  Fragment,
  cloneVNode,
  computed,
  defineComponent,
  h,
  provide,
  ref,
  useAttrs,
  type VNode,
} from 'vue'
import { ArrowDown, ArrowUp, Refresh, Setting } from '@element-plus/icons-vue'
import { ElButton, ElCheckbox, ElIcon, ElPopover, ElSelect, ElOption, ElTable, ElTag } from 'element-plus'
import SmartElTableColumn from './SmartElTableColumn'
import {
  smartTableContextKey,
  type SmartTableFilter,
  type SmartTableServerQuery,
} from './smart-table-context'
import { useAuthStore } from '@/stores/auth'

interface ColumnDescriptor {
  id: string
  label: string
  vnode: VNode
  locked: boolean
}

interface ColumnPreference {
  order: string[]
  hidden: string[]
}

function flattenColumns(nodes: VNode[]): VNode[] {
  return nodes.flatMap((node) => {
    if (node.type === Fragment && Array.isArray(node.children)) {
      return flattenColumns(node.children as VNode[])
    }
    return [node]
  })
}

function hash(value: string): string {
  let result = 2166136261
  for (let index = 0; index < value.length; index += 1) {
    result ^= value.charCodeAt(index)
    result = Math.imul(result, 16777619)
  }
  return (result >>> 0).toString(36)
}

function readPreference(key: string): ColumnPreference {
  try {
    const saved = JSON.parse(localStorage.getItem(key) || '{}') as Partial<ColumnPreference>
    return {
      order: Array.isArray(saved.order) ? saved.order : [],
      hidden: Array.isArray(saved.hidden) ? saved.hidden : [],
    }
  } catch {
    return { order: [], hidden: [] }
  }
}

export default defineComponent({
  name: 'LeanSmartTable',
  inheritAttrs: false,
  props: {
    serverQuery: { type: Boolean, default: false },
  },
  emits: ['smart-query-change', 'sort-change'],
  setup(props, { attrs: setupAttrs, slots, expose, emit }) {
    const attrs = useAttrs()
    const auth = useAuthStore()
    const tableRef = ref<any>()
    const preference = ref<ColumnPreference>({ order: [], hidden: [] })
    const preferenceKey = ref('')
    const serverFilters = ref<SmartTableFilter[]>([])
    const filterLogic = ref<'AND' | 'OR'>('AND')
    const sortBy = ref<string>()
    const sortDirection = ref<'asc' | 'desc'>()
    const rows = computed<Record<string, unknown>[]>(() => (
      Array.isArray(attrs.data) ? attrs.data as Record<string, unknown>[] : []
    ))
    const serverMode = computed(() => props.serverQuery)
    const serverQueryState = computed<SmartTableServerQuery>(() => ({
      logic: filterLogic.value,
      filters: serverFilters.value,
      sortBy: sortBy.value,
      sortDirection: sortDirection.value,
    }))
    function notifyServerQuery() {
      emit('smart-query-change', serverQueryState.value)
    }
    function updateFilter(filter: SmartTableFilter) {
      serverFilters.value = [
        ...serverFilters.value.filter((item) => item.field !== filter.field),
        filter,
      ]
      notifyServerQuery()
    }
    function removeFilter(field: string) {
      serverFilters.value = serverFilters.value.filter((item) => item.field !== field)
      notifyServerQuery()
    }
    provide(smartTableContextKey, {
      rows,
      serverMode,
      query: serverQueryState,
      updateFilter,
      removeFilter,
    })

    const callTable = (method: string, ...args: unknown[]) => {
      const target = tableRef.value as unknown as Record<string, (...values: unknown[]) => unknown>
      return target?.[method]?.(...args)
    }
    expose({
      clearSelection: (...args: unknown[]) => callTable('clearSelection', ...args),
      toggleRowSelection: (...args: unknown[]) => callTable('toggleRowSelection', ...args),
      toggleAllSelection: (...args: unknown[]) => callTable('toggleAllSelection', ...args),
      toggleRowExpansion: (...args: unknown[]) => callTable('toggleRowExpansion', ...args),
      setCurrentRow: (...args: unknown[]) => callTable('setCurrentRow', ...args),
      clearSort: (...args: unknown[]) => callTable('clearSort', ...args),
      clearFilter: (...args: unknown[]) => callTable('clearFilter', ...args),
      doLayout: (...args: unknown[]) => callTable('doLayout', ...args),
      sort: (...args: unknown[]) => callTable('sort', ...args),
      scrollTo: (...args: unknown[]) => callTable('scrollTo', ...args),
      setScrollTop: (...args: unknown[]) => callTable('setScrollTop', ...args),
      setScrollLeft: (...args: unknown[]) => callTable('setScrollLeft', ...args),
    })

    function describe(nodes: VNode[]): ColumnDescriptor[] {
      const occurrences = new Map<string, number>()
      return flattenColumns(nodes).flatMap((vnode) => {
        if (vnode.type !== SmartElTableColumn) return []
        const props = vnode.props || {}
        const base = String(props.prop || props.label || props.type || 'column')
        const occurrence = (occurrences.get(base) || 0) + 1
        occurrences.set(base, occurrence)
        return [{
          id: `${base}:${occurrence}`,
          label: String(props.label || (props.type === 'selection' ? '选择' : base)),
          vnode,
          locked: props.type === 'selection' || props.type === 'index' || props.type === 'expand',
        }]
      })
    }

    function syncPreference(columns: ColumnDescriptor[]) {
      const signature = columns.map((column) => column.id).join('|')
      const userKey = auth.user?.id || auth.user?.username || 'anonymous'
      const key = `leantpm:table:${userKey}:${window.location.pathname}:${hash(signature)}`
      if (key === preferenceKey.value) return
      preferenceKey.value = key
      preference.value = readPreference(key)
    }

    function persist(next: ColumnPreference) {
      preference.value = next
      if (preferenceKey.value) localStorage.setItem(preferenceKey.value, JSON.stringify(next))
    }

    function ordered(columns: ColumnDescriptor[]) {
      const rank = new Map(preference.value.order.map((id, index) => [id, index]))
      return [...columns].sort((left, right) => {
        const leftRank = rank.get(left.id)
        const rightRank = rank.get(right.id)
        if (leftRank === undefined && rightRank === undefined) return 0
        if (leftRank === undefined) return 1
        if (rightRank === undefined) return -1
        return leftRank - rightRank
      })
    }

    function toggle(column: ColumnDescriptor, checked: boolean) {
      if (column.locked) return
      const hidden = new Set(preference.value.hidden)
      if (checked) hidden.delete(column.id)
      else hidden.add(column.id)
      persist({ ...preference.value, hidden: [...hidden] })
    }

    function move(columns: ColumnDescriptor[], index: number, offset: number) {
      const target = index + offset
      if (target < 0 || target >= columns.length) return
      const ids = columns.map((column) => column.id)
      ;[ids[index], ids[target]] = [ids[target], ids[index]]
      persist({ ...preference.value, order: ids })
    }

    function reset() {
      persist({ order: [], hidden: [] })
    }

    function resetServerQuery() {
      serverFilters.value = []
      sortBy.value = undefined
      sortDirection.value = undefined
      callTable('clearSort')
      notifyServerQuery()
    }

    function handleSortChange(event: { prop?: string; order?: 'ascending' | 'descending' | null }) {
      sortBy.value = event.prop || undefined
      sortDirection.value = event.order === 'ascending'
        ? 'asc'
        : event.order === 'descending' ? 'desc' : undefined
      emit('sort-change', event)
      notifyServerQuery()
    }

    return () => {
      const rawNodes = slots.default?.() || []
      const columns = describe(rawNodes)
      syncPreference(columns)
      const orderedColumns = ordered(columns)
      const visible = orderedColumns.filter((column) => (
        column.locked || !preference.value.hidden.includes(column.id)
      ))
      const nonColumns = flattenColumns(rawNodes).filter((node) => node.type !== SmartElTableColumn)
      const popover = h(ElPopover, {
        placement: 'bottom-end',
        width: 330,
        trigger: 'click',
        popperClass: 'smart-table-column-popper',
      }, {
        reference: () => h(ElButton, { size: 'small', plain: true }, {
          default: () => [h(ElIcon, null, { default: () => h(Setting) }), ' 字段设置'],
        }),
        default: () => h('div', { class: 'smart-table-settings' }, [
          h('div', { class: 'smart-table-settings__heading' }, [
            h('strong', '显示字段与顺序'),
            h(ElButton, { link: true, type: 'primary', onClick: reset }, {
              default: () => [h(ElIcon, null, { default: () => h(Refresh) }), ' 恢复默认'],
            }),
          ]),
          ...orderedColumns.map((column, index) => h('div', {
            class: 'smart-table-settings__row',
            key: column.id,
          }, [
            h(ElCheckbox, {
              modelValue: column.locked || !preference.value.hidden.includes(column.id),
              disabled: column.locked,
              'onUpdate:modelValue': (value: boolean | string | number) => toggle(column, Boolean(value)),
            }, { default: () => column.label }),
            h('span', { class: 'smart-table-settings__moves' }, [
              h(ElButton, {
                link: true,
                disabled: index === 0,
                title: '前移',
                onClick: () => move(orderedColumns, index, -1),
              }, { default: () => h(ElIcon, null, { default: () => h(ArrowUp) }) }),
              h(ElButton, {
                link: true,
                disabled: index === orderedColumns.length - 1,
                title: '后移',
                onClick: () => move(orderedColumns, index, 1),
              }, { default: () => h(ElIcon, null, { default: () => h(ArrowDown) }) }),
            ]),
          ])),
        ]),
      })

      return h('div', { class: 'smart-table-shell' }, [
        columns.length > 1
          ? h('div', { class: 'smart-table-toolbar' }, [
              h('span', { class: 'smart-table-toolbar__hint' }, props.serverQuery
                ? '数据库复合查询 · 点击列名排序 · 点击漏斗筛选'
                : '点击表头排序 · 当前页筛选'),
              ...(props.serverQuery ? [
                h(ElSelect, {
                  modelValue: filterLogic.value,
                  size: 'small',
                  class: 'smart-table-logic',
                  title: '多条件组合方式',
                  'onUpdate:modelValue': (next: 'AND' | 'OR') => {
                    filterLogic.value = next
                    if (serverFilters.value.length) notifyServerQuery()
                  },
                }, { default: () => [
                  h(ElOption, { label: '全部满足（AND）', value: 'AND' }),
                  h(ElOption, { label: '任一满足（OR）', value: 'OR' }),
                ] }),
                ...serverFilters.value.map((filter) => h(ElTag, {
                  key: filter.field,
                  closable: true,
                  type: 'success',
                  effect: 'plain',
                  onClose: () => removeFilter(filter.field),
                }, { default: () => `${filter.label || filter.field} ${filter.operator} ${filter.values?.join(' ~ ') || filter.value || ''}` })),
                h(ElButton, {
                  size: 'small',
                  disabled: !serverFilters.value.length && !sortBy.value,
                  onClick: resetServerQuery,
                }, { default: () => '清空查询' }),
              ] : []),
              popover,
            ])
          : null,
        h(ElTable, {
          ...setupAttrs,
          ref: tableRef,
          onSortChange: handleSortChange,
        }, {
          ...slots,
          default: () => [
            ...visible.map((column) => cloneVNode(column.vnode, { key: column.id })),
            ...nonColumns,
          ],
        }),
      ])
    }
  },
})
