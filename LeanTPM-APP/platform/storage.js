import { secureGet, secureRemove, secureSet } from './secure-storage.js'

const PREFIX = 'leantpm_uni_'

export const STORAGE_KEYS = Object.freeze({
	serverBaseUrl: `${PREFIX}server_base_url`,
	accessToken: `${PREFIX}access_token`,
	refreshToken: `${PREFIX}refresh_token`,
	accessExpiresAt: `${PREFIX}access_expires_at`,
	refreshExpiresAt: `${PREFIX}refresh_expires_at`,
	userProfile: `${PREFIX}user_profile`,
	rememberedUsername: `${PREFIX}remembered_username`,
	rememberedCredentials: `${PREFIX}remembered_credentials`,
	branding: `${PREFIX}branding`,
	drafts: `${PREFIX}drafts`,
	photoQueue: `${PREFIX}photo_queue`,
	lastMessageIds: `${PREFIX}last_message_ids`
})

const SECURE_KEYS = new Set([
	STORAGE_KEYS.accessToken, STORAGE_KEYS.refreshToken,
	STORAGE_KEYS.accessExpiresAt, STORAGE_KEYS.refreshExpiresAt,
	STORAGE_KEYS.userProfile, STORAGE_KEYS.rememberedCredentials,
	STORAGE_KEYS.drafts, STORAGE_KEYS.photoQueue
])

function storage() {
	// In WeChat Mini Program builds `uni` is injected as a module-level runtime
	// variable and is not guaranteed to be exposed on `globalThis`.
	if (typeof uni !== 'undefined' && uni) return uni
	// Keep a native WeChat fallback so storage remains available even when a
	// compiler/runtime version does not export the uni facade from vendor.js.
	if (typeof wx !== 'undefined' && wx?.setStorageSync) return wx
	if (globalThis?.uni) return globalThis.uni
	if (globalThis?.wx?.setStorageSync) return globalThis.wx
	throw new Error('存储运行时不可用（build 20260804.2）')
}

export function getStored(key, fallback = null) {
	try {
		if (SECURE_KEYS.has(key)) return secureGet(key, fallback)
		const value = storage().getStorageSync(key)
		return value === '' || value === undefined || value === null ? fallback : value
	} catch {
		return fallback
	}
}

export function setStored(key, value) {
	if (SECURE_KEYS.has(key)) return secureSet(key, value)
	storage().setStorageSync(key, value)
}

export function removeStored(key) {
	try {
		if (SECURE_KEYS.has(key)) { secureRemove(key); return }
		storage().removeStorageSync(key)
	} catch {
		// A partially initialized runtime may reject storage cleanup; session state is still reset in memory.
	}
}

export function clearSessionStorage() {
	for (const key of [
		STORAGE_KEYS.accessToken,
		STORAGE_KEYS.refreshToken,
		STORAGE_KEYS.accessExpiresAt,
		STORAGE_KEYS.refreshExpiresAt,
		STORAGE_KEYS.userProfile
	]) removeStored(key)
}

export function clearBusinessStorage() {
	for (const key of [
		STORAGE_KEYS.drafts,
		STORAGE_KEYS.photoQueue,
		STORAGE_KEYS.lastMessageIds
	]) removeStored(key)
}

export function clearEnterpriseStorage() {
	clearSessionStorage()
	clearBusinessStorage()
	removeStored(STORAGE_KEYS.rememberedCredentials)
	removeStored(STORAGE_KEYS.rememberedUsername)
	removeStored(STORAGE_KEYS.branding)
}
