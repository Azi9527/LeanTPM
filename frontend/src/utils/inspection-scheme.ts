export interface SchemeItemSource {
  inspectionItemId: number
  sortOrder: number
  requiredFlag: boolean
  photoRequiredFlag: boolean
  skipAllowedFlag: boolean
  abnormalStopFlag?: boolean
}

export interface SchemeItemConfig {
  sortOrder: number
}

export function schemeItemConfigMap(items: SchemeItemSource[]): Record<number, SchemeItemConfig> {
  return Object.fromEntries(items.map((item) => [item.inspectionItemId, {
    sortOrder: item.sortOrder,
  }]))
}

export function schemeItemPayload(
  itemIds: number[],
  configs: Record<number, SchemeItemConfig>,
) {
  return itemIds.map((inspectionItemId, index) => {
    const config = configs[inspectionItemId]
    return {
      inspectionItemId,
      sortOrder: config?.sortOrder ?? (index + 1) * 10,
    }
  })
}
