import { readonly, ref } from 'vue'

const connectedState = ref(true)
const networkTypeState = ref('unknown')
let initialized = false

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
		connectedState.value = isConnected
		networkTypeState.value = type
	})
}
