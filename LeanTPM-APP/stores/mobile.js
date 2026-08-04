import { reactive, readonly } from 'vue'
import { mobileApi } from '../api/mobile.js'

const state = reactive({
	bootstrap: null,
	loading: false,
	error: '',
	lastUpdatedAt: ''
})

export const mobileState = readonly(state)

export async function refreshMobileBootstrap() {
	state.loading = true
	state.error = ''
	try {
		state.bootstrap = await mobileApi.bootstrap()
		state.lastUpdatedAt = new Date().toISOString()
		return state.bootstrap
	} catch (error) {
		state.error = error?.message || '移动工作台加载失败'
		throw error
	} finally {
		state.loading = false
	}
}
