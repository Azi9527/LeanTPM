const PREFIX = 'leantpm_uni_'

export const STORAGE_KEYS = Object.freeze({
	serverBaseUrl: `${PREFIX}server_base_url`,
	accessToken: `${PREFIX}access_token`,
	refreshToken: `${PREFIX}refresh_token`,
	accessExpiresAt: `${PREFIX}access_expires_at`,
	refreshExpiresAt: `${PREFIX}refresh_expires_at`,
	userProfile: `${PREFIX}user_profile`,
	rememberedUsername: `${PREFIX}remembered_username`,
	branding: `${PREFIX}branding`,
	drafts: `${PREFIX}drafts`,
	photoQueue: `${PREFIX}photo_queue`,
	lastMessageIds: `${PREFIX}last_message_ids`
})

function storage() {
	if (!globalThis.uni) throw new Error('uni storage is unavailable')
	return globalThis.uni
}

export function getStored(key, fallback = null) {
	try {
		const value = storage().getStorageSync(key)
		return value === '' || value === undefined || value === null ? fallback : value
	} catch {
		return fallback
	}
}

export function setStored(key, value) {
	storage().setStorageSync(key, value)
}

export function removeStored(key) {
	try {
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
	removeStored(STORAGE_KEYS.branding)
}
