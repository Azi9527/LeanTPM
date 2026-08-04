<script>
	import { initializeNetwork } from './platform/network.js'
	import { initializeOfflineSync } from './services/offline-sync.js'

	export default {
		onLaunch: function() {
			initializeNetwork()
			initializeOfflineSync()
		},
		onShow: function() {
			console.log('App Show')
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
