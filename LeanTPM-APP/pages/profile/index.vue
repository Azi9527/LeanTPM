<template>
	<view class="page">
		<view class="profile-card">
			<view class="avatar">{{ displayName().slice(0, 1) }}</view>
			<view><text class="name">{{ displayName() }}</text><text class="account">{{ sessionState.user?.username }}</text></view>
		</view>

		<view class="menu-card">
			<view class="menu-row"><text>所属角色</text><text class="value">{{ rolesText }}</text></view>
			<view class="menu-row"><text>服务地址</text><text class="value server">{{ serverUrl }}</text></view>
			<view class="menu-row"><text>APP 版本</text><text class="value">{{ versionName }}</text></view>
			<view class="menu-row" @click="openSetup"><text>重新配置服务地址</text><text class="arrow">›</text></view>
			<view class="menu-row" @click="openChangePassword"><text>修改密码</text><text class="arrow">›</text></view>
		</view>

		<button class="logout-button" :loading="loading" @click="logout">退出登录</button>
	</view>
</template>

<script setup>
	import { computed, ref } from 'vue'
	import { onLoad } from '@dcloudio/uni-app'
	import { ROUTES, reLaunchTo } from '../../constants/routes.js'
	import { displayName, sessionState, signOut } from '../../stores/session.js'
	import { getServerBaseUrl } from '../../utils/server.js'

	const loading = ref(false)
	const serverUrl = getServerBaseUrl()
	const versionName = ref('1.0.0')
	const rolesText = computed(() => sessionState.user?.roles?.join('、') || '员工')

	onLoad(() => {
		try { versionName.value = uni.getAppBaseInfo().appVersion || '1.0.0' } catch { /* H5 fallback */ }
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
	.profile-card { display: flex; align-items: center; padding: 40rpx 32rpx; border-radius: 26rpx; color: #fff; background: linear-gradient(145deg, #173c2f, #1c7d50); }
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
	.logout-button { display: flex; height: 92rpx; align-items: center; justify-content: center; margin-top: 36rpx; border-radius: 18rpx; color: #c4000a; background: #fff; font-size: 28rpx; font-weight: 700; }
</style>
