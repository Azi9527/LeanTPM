<template>
	<view class="launch-page">
		<image class="customer-logo" src="/static/branding/baoshan-mining-logo.png" mode="widthFix" />
		<text class="name">宝山矿业</text>
		<text class="subtitle">精益设备管理 · LeanTPM</text>
		<view class="loading-line"><view class="loading-value"></view></view>
	</view>
</template>

<script setup>
	import { onLoad } from '@dcloudio/uni-app'
	import { ROUTES, reLaunchTo } from '../../constants/routes.js'
	import { getServerBaseUrl } from '../../utils/server.js'
	import { initializeBranding } from '../../stores/branding.js'
	import { restoreSession } from '../../stores/session.js'

	onLoad(async () => {
		await new Promise((resolve) => setTimeout(resolve, 220))
		if (!getServerBaseUrl()) {
			await reLaunchTo(ROUTES.setup)
			return
		}

		await initializeBranding()
		const user = await restoreSession()
		if (!user) {
			await reLaunchTo(ROUTES.login)
			return
		}
		await reLaunchTo(user.mustChangePassword ? '/pages/login/change-password' : ROUTES.workbench)
	})
</script>

<style>
	.launch-page {
		display: flex;
		min-height: 100vh;
		align-items: center;
		justify-content: center;
		flex-direction: column;
		padding: env(safe-area-inset-top) 48rpx env(safe-area-inset-bottom);
		color: #ffffff;
		background: linear-gradient(155deg, #16392d 0%, var(--brand-primary, #1c7d50) 72%, #349869 100%);
	}

	.mark {
		display: flex;
		width: 132rpx;
		height: 132rpx;
		align-items: center;
		justify-content: center;
		border: 2rpx solid rgba(255, 255, 255, 0.7);
		border-radius: 34rpx;
		font-size: 48rpx;
		font-weight: 800;
		letter-spacing: 2rpx;
	}
	.customer-logo { width: 600rpx; padding: 18rpx; border-radius: 24rpx; background: rgba(255,255,255,.96); }

	.name {
		margin-top: 34rpx;
		font-size: 54rpx;
		font-weight: 800;
		letter-spacing: 2rpx;
	}

	.subtitle {
		margin-top: 14rpx;
		color: rgba(255, 255, 255, 0.74);
		font-size: 27rpx;
		letter-spacing: 8rpx;
	}

	.loading-line {
		overflow: hidden;
		width: 210rpx;
		height: 6rpx;
		margin-top: 110rpx;
		border-radius: 6rpx;
		background: rgba(255, 255, 255, 0.2);
	}

	.loading-value {
		width: 45%;
		height: 100%;
		border-radius: 6rpx;
		background: #ffffff;
		animation: loading 1s ease-in-out infinite alternate;
	}

	@keyframes loading {
		from { transform: translateX(0); }
		to { transform: translateX(125%); }
	}
</style>
