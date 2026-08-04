import { readonly, ref } from 'vue'

const connectedState = ref(true)
const networkTypeState = ref('unknown')
let initialized = false
const reconnectHandlers = new Set()

export const connected = readonly(connectedState)
export const networkType = readonly(networkTypeState)

export function initializeNetwork() {
	if (initialized) return
	initialized = true

	uni.getNetworkType({
		success: ({ networkType: type }) => {
			networkTypeState.value = type
			connectedState.value = type !== 'none'
		},
		fail: () => {
			networkTypeState.value = 'unknown'
		}
	})

	uni.onNetworkStatusChange(({ isConnected, networkType: type }) => {
		const wasConnected = connectedState.value
		connectedState.value = isConnected
		networkTypeState.value = type
		if (!wasConnected && isConnected) {
			for (const handler of reconnectHandlers) Promise.resolve().then(handler).catch(() => {})
		}
	})
}

export function onNetworkReconnect(handler) {
	if (typeof handler !== 'function') return () => {}
	reconnectHandlers.add(handler)
	return () => reconnectHandlers.delete(handler)
}
