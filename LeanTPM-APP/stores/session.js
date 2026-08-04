import { reactive, readonly } from 'vue'
import { authApi } from '../api/auth.js'
import {
	clearTokens,
	configureAuthFailureHandler,
	hasToken,
	storeTokens
} from '../api/request.js'
import { ROUTES, reLaunchTo } from '../constants/routes.js'
import { STORAGE_KEYS, getStored, setStored } from '../platform/storage.js'

const state = reactive({
	user: null,
	initialized: false,
	loading: false
})

export const sessionState = readonly(state)

export function displayName() {
	return state.user?.realName || state.user?.username || '用户'
}

export function can(permission) {
	return Boolean(state.user?.permissions?.includes(permission))
}

export function rememberedUsername() {
	return getStored(STORAGE_KEYS.rememberedUsername, '')
}

export function rememberUsername(username, remember) {
	if (remember) setStored(STORAGE_KEYS.rememberedUsername, username)
	else setStored(STORAGE_KEYS.rememberedUsername, '')
}

export async function signIn(credentials, remember = true) {
	state.loading = true
	try {
		const result = await authApi.login(
			credentials.username,
			credentials.password,
			credentials.captchaId,
			credentials.captchaCode
		)
		storeTokens(result.tokens)
		state.user = result.user
		state.initialized = true
		setStored(STORAGE_KEYS.userProfile, result.user)
		rememberUsername(credentials.username, remember)
		return result.user
	} finally {
		state.loading = false
	}
}

export async function restoreSession() {
	state.initialized = false
	if (!hasToken()) {
		state.user = null
		state.initialized = true
		return null
	}
	state.user = getStored(STORAGE_KEYS.userProfile, null)
	try {
		state.user = await authApi.currentUser()
		setStored(STORAGE_KEYS.userProfile, state.user)
		return state.user
	} catch {
		clearTokens()
		state.user = null
		return null
	} finally {
		state.initialized = true
	}
}

export async function changePassword(currentPassword, newPassword) {
	const tokens = await authApi.changePassword(currentPassword, newPassword)
	storeTokens(tokens)
	return restoreSession()
}

export async function signOut() {
	try {
		if (hasToken()) await authApi.logout()
	} finally {
		clearTokens()
		state.user = null
		state.initialized = true
	}
}

configureAuthFailureHandler(async () => {
	state.user = null
	state.initialized = true
	await reLaunchTo(ROUTES.login)
})
