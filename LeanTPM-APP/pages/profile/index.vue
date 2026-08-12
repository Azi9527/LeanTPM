<template>
	<view class="page" :style="$brandTheme()">
		<view v-if="!sessionState.user" class="session-loading">正在验证登录状态…</view>
		<template v-else>
		<view class="profile-card">
			<view class="avatar">{{ displayName().slice(0, 1) }}</view>
			<view><text class="name">{{ displayName() }}</text><text class="account">{{ sessionState.user?.username }}</text></view>
		</view>

		<view class="menu-card">
			<view class="menu-row"><text>所属角色</text><text class="value">{{ rolesText }}</text></view>
			<view class="menu-row"><text>服务地址</text><text class="value server">{{ serverUrl }}</text></view>
			<view class="menu-row"><text>APP 版本</text><text class="value">{{ versionName }}（{{ versionCode }}）</text></view>
			<view class="menu-row"><text>待同步业务</text><text class="value">{{ pendingCount }} 项</text></view>
			<view class="menu-row" @click="syncNow"><text>{{ syncing ? '正在同步…' : '立即同步离线业务' }}</text><text class="arrow">›</text></view>
			<view class="menu-row" @click="openMessages"><text>现场消息</text><text class="arrow">›</text></view>
			<view class="menu-row" @click="openSetup"><text>重新配置服务地址</text><text class="arrow">›</text></view>
			<view class="menu-row" @click="openChangePassword"><text>修改密码</text><text class="arrow">›</text></view>
		</view>
		<view class="menu-card version-card">
			<view class="menu-row"><text>服务端最新版本</text><text class="value">{{ versionPolicy.latestVersionName || '未配置' }}</text></view>
			<text v-if="upgradeRequired" class="upgrade-warning">当前版本低于系统最低要求，请立即升级后继续使用。</text>
			<text v-if="versionPolicy.releaseNotes" class="release-notes">{{ versionPolicy.releaseNotes }}</text>
			<button v-if="versionPolicy.downloadUrl" class="upgrade-button" @click="downloadUpgrade">下载最新版</button>
		</view>

		<button class="logout-button" :loading="loading" @click="logout">退出登录</button>
		<AppBottomNav active="profile" />
		</template>
	</view>
</template>

<script setup>
	import { computed, ref } from 'vue'
	import { onLoad } from '@dcloudio/uni-app'
	import { hasToken } from '../../api/request.js'
	import { ROUTES, navigateTo, reLaunchTo } from '../../constants/routes.js'
	import { syncPendingWork } from '../../services/offline-sync.js'
	import { mobileState, refreshMobileBootstrap } from '../../stores/mobile.js'
	import { pendingWorkCount } from '../../stores/offline.js'
	import { displayName, restoreSession, sessionState, signOut } from '../../stores/session.js'
	import { getServerBaseUrl } from '../../utils/server.js'
	import { compareVersionCodes, currentAppInfo, openUpgradeUrl } from '../../utils/version.js'
	import AppBottomNav from '../../components/AppBottomNav.vue'

	const loading = ref(false)
	const serverUrl = getServerBaseUrl()
	const versionName = ref('1.0.4')
	const versionCode = ref(103)
	const pendingCount = ref(pendingWorkCount())
	const syncing = ref(false)
	const rolesText = computed(() => sessionState.user?.roles?.join('、') || '—')
	const versionPolicy = computed(() => mobileState.bootstrap?.androidVersion || {})
	const upgradeRequired = computed(() => compareVersionCodes(versionCode.value, versionPolicy.value.minimumVersionCode).upgradeRequired)

	onLoad(async () => {
		if (!sessionState.user && hasToken()) await restoreSession()
		if (!sessionState.user) return reLaunchTo(ROUTES.login)
		const info = currentAppInfo()
		versionName.value = info.version
		versionCode.value = info.versionCode
		if (!mobileState.bootstrap) try { await refreshMobileBootstrap() } catch { /* profile remains available offline */ }
	})

	function openSetup() {
		uni.showModal({
			title: '切换企业服务',
			content: '切换服务地址会清除当前登录状态和本地业务缓存，是否继续？',
			success: ({ confirm }) => { if (confirm) reLaunchTo(ROUTES.setup) }
		})
	}

	function openChangePassword() {
		uni.navigateTo({ url: '/pages/login/change-password' })
	}
	function openMessages() { navigateTo(ROUTES.messages) }
	function downloadUpgrade() { openUpgradeUrl(versionPolicy.value.downloadUrl) }
	async function syncNow() {
		if (syncing.value) return
		syncing.value = true
		try {
			const result = await syncPendingWork()
			pendingCount.value = pendingWorkCount()
			uni.showToast({ title: `已同步 ${result.photos + result.drafts} 项`, icon: 'none' })
		} catch { uni.showToast({ title: '同步失败，请检查网络', icon: 'none' }) }
		finally { syncing.value = false }
	}

	async function logout() {
		if (loading.value) return
		loading.value = true
		try { await signOut() } finally {
			loading.value = false
			await reLaunchTo(ROUTES.login)
		}
	}
</script>

<style>
	.page { box-sizing: border-box; min-height: 100vh; padding: 32rpx 28rpx; background: #f4f7f5; }
	.session-loading { padding: 180rpx 20rpx; color: #7f8d86; font-size: 25rpx; text-align: center; }
	.profile-card { display: flex; align-items: center; padding: 40rpx 32rpx; border-radius: 26rpx; color: #fff; background: linear-gradient(145deg, #173c2f, var(--brand-primary, #1c7d50)); }
	.avatar { display: flex; width: 88rpx; height: 88rpx; align-items: center; justify-content: center; margin-right: 24rpx; border: 2rpx solid rgba(255,255,255,.55); border-radius: 28rpx; background: rgba(255,255,255,.13); font-size: 35rpx; font-weight: 800; }
	.name, .account { display: block; }
	.name { font-size: 35rpx; font-weight: 750; }
	.account { margin-top: 7rpx; color: rgba(255,255,255,.7); font-size: 23rpx; }
	.menu-card { overflow: hidden; margin-top: 28rpx; border-radius: 24rpx; background: #fff; box-shadow: 0 12rpx 38rpx rgba(25,53,42,.06); }
	.menu-row { display: flex; min-height: 94rpx; align-items: center; justify-content: space-between; padding: 0 28rpx; border-bottom: 1rpx solid #edf1ef; color: #394740; font-size: 27rpx; }
	.menu-row:last-child { border-bottom: 0; }
	.value { max-width: 68%; color: #87918c; font-size: 24rpx; text-align: right; }
	.value.server { word-break: break-all; }
	.arrow { color: #98a29d; font-size: 42rpx; }
	.logout-button { display: flex; height: 92rpx; align-items: center; justify-content: center; margin-top: 36rpx; border-radius: 18rpx; color: var(--brand-accent, #c4000a); background: #fff; font-size: 28rpx; font-weight: 700; }
	.version-card { padding-bottom: 22rpx; }
	.upgrade-warning, .release-notes { display: block; margin: 20rpx 28rpx 0; font-size: 23rpx; line-height: 1.6; }
	.upgrade-warning { color: #a00008; }
	.release-notes { color: #7d8883; }
	.upgrade-button { margin: 22rpx 28rpx 0; color: #fff; background: var(--brand-primary, #1c7d50); font-size: 25rpx; }
</style>
