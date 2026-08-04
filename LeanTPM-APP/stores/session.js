import { reactive, readonly } from 'vue'
import { authApi } from '../api/auth.js'
import {
	clearTokens,
	configureAuthFailureHandler,
	hasToken,
	storeTokens
} from '../api/request.js'
import { ROUTES, reLaunchTo } from '../constants/routes.js'
import { STORAGE_KEYS, clearBusinessStorage, getStored, removeStored, setStored } from '../platform/storage.js'
import { isAuthenticationFailure, isServiceUnavailable } from '../utils/errors.js'

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

export function rememberedCredentials() {
	const saved = getStored(STORAGE_KEYS.rememberedCredentials, null)
	if (!saved || typeof saved !== 'object') return null
	const username = String(saved.username || '').trim()
	const password = String(saved.password || '')
	return username && password ? { username, password } : null
}

export function rememberCredentials(username, password, remember) {
	if (remember) {
		setStored(STORAGE_KEYS.rememberedCredentials, { username: username.trim(), password })
		rememberUsername(username, true)
		return
	}
	removeStored(STORAGE_KEYS.rememberedCredentials)
	rememberUsername('', false)
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
		rememberCredentials(credentials.username, credentials.password, remember)
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
	} catch (error) {
		if (isServiceUnavailable(error) && state.user) return state.user
		if (isAuthenticationFailure(error)) clearTokens()
		state.user = null
		return null
	} finally {
		state.initialized = true
	}
}

export async function changePassword(currentPassword, newPassword) {
	const tokens = await authApi.changePassword(currentPassword, newPassword)
	storeTokens(tokens)
	const saved = rememberedCredentials()
	if (saved?.username === state.user?.username) {
		rememberCredentials(saved.username, newPassword, true)
	}
	return restoreSession()
}

export async function signOut() {
	try {
		if (hasToken()) await authApi.logout()
	} finally {
		clearTokens()
		clearBusinessStorage()
		state.user = null
		state.initialized = true
	}
}

configureAuthFailureHandler(async () => {
	state.user = null
	state.initialized = true
	await reLaunchTo(ROUTES.login)
})
