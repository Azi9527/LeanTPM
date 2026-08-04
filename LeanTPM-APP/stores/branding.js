import { reactive, readonly } from 'vue'
import { publicApi } from '../api/public.js'
import { DEFAULT_BRANDING } from '../constants/theme.js'
import { STORAGE_KEYS, getStored, setStored } from '../platform/storage.js'
import { normalizeBranding } from '../utils/branding.js'
const state = reactive({ ...DEFAULT_BRANDING })

export const brandingState = readonly(state)

export function applyBranding(settings, persist = true) {
	Object.assign(state, normalizeBranding(settings))
	if (persist) setStored(STORAGE_KEYS.branding, { ...state })
}

export async function initializeBranding() {
	applyBranding(getStored(STORAGE_KEYS.branding, DEFAULT_BRANDING), false)
	try {
		applyBranding(await publicApi.branding())
	} catch {
		// Cached defaults keep the app usable when the enterprise server is temporarily offline.
	}
	return state
}
