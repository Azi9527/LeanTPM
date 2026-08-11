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
  required: boolean
  photoRequired: boolean
  skipAllowed: boolean
  abnormalStop: boolean | null
}

export function schemeItemConfigMap(items: SchemeItemSource[]): Record<number, SchemeItemConfig> {
  return Object.fromEntries(items.map((item) => [item.inspectionItemId, {
    sortOrder: item.sortOrder,
    required: item.requiredFlag,
    photoRequired: item.photoRequiredFlag,
    skipAllowed: item.skipAllowedFlag,
    abnormalStop: item.abnormalStopFlag ?? null,
  }]))
}

export function schemeItemPayload(
  itemIds: number[],
  configs: Record<number, SchemeItemConfig>,
  stopOverrides: Record<number, boolean | null>,
) {
  return itemIds.map((inspectionItemId, index) => {
    const config = configs[inspectionItemId]
    return {
      inspectionItemId,
      sortOrder: config?.sortOrder ?? (index + 1) * 10,
      required: config?.required ?? null,
      photoRequired: config?.photoRequired ?? null,
      skipAllowed: config?.skipAllowed ?? null,
      abnormalStop: stopOverrides[inspectionItemId] ?? config?.abnormalStop ?? null,
    }
  })
}
