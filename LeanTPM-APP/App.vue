<script>
	import { initializeNetwork } from './platform/network.js'
	import { initializeOfflineSync } from './services/offline-sync.js'
	import { hasToken } from './api/request.js'
	import { ROUTES, reLaunchTo } from './constants/routes.js'
	import { restoreSession, sessionState } from './stores/session.js'

	let sessionRecovery = null
	function recoverMissingSession() {
		if (sessionState.user || !hasToken() || sessionRecovery) return sessionRecovery
		sessionRecovery = restoreSession()
			.then((user) => user || reLaunchTo(ROUTES.login))
			.catch(() => reLaunchTo(ROUTES.login).catch(() => null))
			.finally(() => { sessionRecovery = null })
		return sessionRecovery
	}

	export default {
		onLaunch: function() {
			initializeNetwork()
			initializeOfflineSync()
		},
		onShow: function() {
			recoverMissingSession()
		},
		onHide: function() {
			console.log('App Hide')
		}
	}
</script>

<style>
	page {
		min-height: 100%;
		color: #3e3a39;
		background: #f4f7f5;
		font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC",
			"Microsoft YaHei", sans-serif;
	}

	button::after {
		border: 0;
	}
</style>
