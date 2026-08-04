<template>
	<view class="page">
		<view class="status-mark">✓</view>
		<text class="title">LeanTPM 移动端</text>
		<text class="subtitle">uni-app 基础环境已就绪</text>

		<view class="status-card">
			<view class="status-row">
				<text class="status-label">企业服务</text>
				<text class="status-value">连接成功</text>
			</view>
			<text class="server-url">{{ serverUrl }}</text>
		</view>

		<text class="next-step">下一步将接入 LeanTPM 账号登录与令牌刷新。</text>
		<button class="secondary-button" @click="openSetup">重新配置服务地址</button>
	</view>
</template>

<script setup>
	import { ref } from 'vue'
	import { onShow } from '@dcloudio/uni-app'
	import { getServerBaseUrl } from '../../utils/server'

	const serverUrl = ref('未配置')

	onShow(() => {
		serverUrl.value = getServerBaseUrl() || '未配置'
	})

	function openSetup() {
		uni.reLaunch({
			url: '/pages/setup/index'
		})
	}
</script>

<style>
	.page {
		box-sizing: border-box;
		display: flex;
		min-height: 100vh;
		align-items: center;
		flex-direction: column;
		padding: calc(150rpx + env(safe-area-inset-top)) 44rpx 60rpx;
		background: linear-gradient(160deg, rgba(28, 125, 80, 0.17), rgba(244, 247, 245, 0) 42%), #f4f7f5;
	}

	.status-mark {
		display: flex;
		width: 120rpx;
		height: 120rpx;
		align-items: center;
		justify-content: center;
		border-radius: 36rpx;
		color: #ffffff;
		background: #1c7d50;
		box-shadow: 0 20rpx 50rpx rgba(28, 125, 80, 0.25);
		font-size: 64rpx;
		font-weight: 700;
	}

	.title {
		margin-top: 40rpx;
		color: #19352a;
		font-size: 48rpx;
		font-weight: 700;
	}

	.subtitle {
		margin-top: 14rpx;
		color: #77857f;
		font-size: 28rpx;
	}

	.status-card {
		box-sizing: border-box;
		width: 100%;
		margin-top: 72rpx;
		padding: 34rpx;
		border: 1rpx solid rgba(28, 125, 80, 0.12);
		border-radius: 26rpx;
		background: #ffffff;
		box-shadow: 0 18rpx 56rpx rgba(25, 53, 42, 0.08);
	}

	.status-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
	}

	.status-label {
		color: #3e3a39;
		font-size: 29rpx;
		font-weight: 600;
	}

	.status-value {
		color: #1c7d50;
		font-size: 26rpx;
		font-weight: 700;
	}

	.server-url {
		display: block;
		margin-top: 22rpx;
		padding-top: 22rpx;
		border-top: 1rpx solid #edf1ef;
		color: #718078;
		font-size: 24rpx;
		line-height: 1.6;
		word-break: break-all;
	}

	.next-step {
		margin-top: 44rpx;
		color: #7e8984;
		font-size: 25rpx;
		line-height: 1.7;
		text-align: center;
	}

	.secondary-button {
		width: 100%;
		height: 92rpx;
		margin-top: 36rpx;
		border: 2rpx solid #1c7d50;
		border-radius: 18rpx;
		color: #1c7d50;
		background: transparent;
		font-size: 28rpx;
		font-weight: 700;
	}
</style>
