<script>
	import { initializeNetwork } from './platform/network.js'
	import { initializeOfflineSync } from './services/offline-sync.js'
	import { hasToken } from './api/request.js'
	import { ROUTES, reLaunchTo } from './constants/routes.js'
	import { checkPublicAndroidUpgrade, refreshMobileBootstrap } from './stores/mobile.js'
	import { restoreSession, sessionState } from './stores/session.js'
	import { checkAndroidUpgrade } from './utils/version.js'

	let sessionRecovery = null
	function recoverMissingSession() {
		if (sessionState.user || !hasToken() || sessionRecovery) return sessionRecovery
		sessionRecovery = restoreSession()
			.then((user) => user || reLaunchTo(ROUTES.login))
			.catch(() => reLaunchTo(ROUTES.login).catch(() => null))
			.finally(() => { sessionRecovery = null })
		return sessionRecovery
	}

	let versionCheck = null
	async function enforceAndroidVersion() {
		if (versionCheck) return versionCheck
		versionCheck = (async () => {
			try {
				if (await checkPublicAndroidUpgrade()) return
			} catch { /* public policy refresh must not make an offline device unusable */ }
			await recoverMissingSession()
			if (!sessionState.user) return
			try {
				const bootstrap = await refreshMobileBootstrap()
				checkAndroidUpgrade(bootstrap?.androidVersion)
			} catch { /* keep offline session available when policy cannot be refreshed */ }
		})().finally(() => { versionCheck = null })
		return versionCheck
	}

	export default {
		onLaunch: function() {
			initializeNetwork()
			initializeOfflineSync()
		},
		onShow: function() {
			enforceAndroidVersion()
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
