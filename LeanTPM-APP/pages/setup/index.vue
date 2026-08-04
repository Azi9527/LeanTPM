<template>
	<view class="page">
		<view class="brand">
			<view class="brand-mark">LT</view>
			<text class="brand-name">LeanTPM</text>
		</view>

		<view class="card">
			<text class="eyebrow">企业服务配置</text>
			<text class="title">连接 LeanTPM 服务</text>
			<text class="description">
				请输入企业部署的后端地址。开发环境可以使用局域网地址，生产环境必须使用 HTTPS。
			</text>

			<view class="field">
				<text class="label">服务地址</text>
				<input
					v-model="serverUrl"
					class="input"
					type="text"
					confirm-type="done"
					placeholder="例如：http://192.168.31.91:18080"
				/>
			</view>

			<button
				class="primary-button"
				:loading="testing"
				:disabled="testing"
				@click="saveAndTest"
			>
				{{ testing ? '正在测试连接' : '保存并测试连接' }}
			</button>

			<text class="hint">系统会自动补全 /api/v1，请不要填写 127.0.0.1。</text>
		</view>
	</view>
</template>

<script setup>
	import { ref } from 'vue'
	import {
		getServerDisplayUrl,
		normalizeServerBaseUrl,
		saveServerBaseUrl,
		testServerConnection
	} from '../../utils/server.js'

	const serverUrl = ref(getServerDisplayUrl())
	const testing = ref(false)

	async function saveAndTest() {
		if (testing.value) return
		testing.value = true

		try {
			const normalized = normalizeServerBaseUrl(serverUrl.value)
			await testServerConnection(normalized)
			saveServerBaseUrl(normalized)
			serverUrl.value = normalized.replace(/\/api\/v1$/, '')

			uni.showToast({
				title: '服务连接成功',
				icon: 'success',
				duration: 1200
			})

			setTimeout(() => {
				uni.reLaunch({
					url: '/pages/login/index'
				})
			}, 700)
		} catch (error) {
			uni.showModal({
				title: '无法连接服务器',
				content: error?.message || '请检查服务地址、手机网络、电脑防火墙和后端服务状态',
				showCancel: false
			})
		} finally {
			testing.value = false
		}
	}
</script>

<style>
	.page {
		box-sizing: border-box;
		min-height: 100vh;
		padding: calc(90rpx + env(safe-area-inset-top)) 40rpx 60rpx;
		background: linear-gradient(160deg, rgba(28, 125, 80, 0.18) 0%, rgba(244, 247, 245, 0) 45%), #f4f7f5;
	}

	.brand {
		display: flex;
		align-items: center;
		margin-bottom: 90rpx;
	}

	.brand-mark {
		display: flex;
		width: 72rpx;
		height: 72rpx;
		align-items: center;
		justify-content: center;
		margin-right: 20rpx;
		border-radius: 16rpx;
		color: #ffffff;
		background: var(--brand-primary, #1c7d50);
		font-size: 28rpx;
		font-weight: 700;
	}

	.brand-name {
		color: #19352a;
		font-size: 42rpx;
		font-weight: 700;
	}

	.card {
		padding: 48rpx 36rpx;
		border: 1rpx solid rgba(28, 125, 80, 0.12);
		border-radius: 32rpx;
		background: #ffffff;
		box-shadow: 0 24rpx 70rpx rgba(28, 70, 51, 0.1);
	}

	.eyebrow,
	.title,
	.description,
	.label,
	.hint {
		display: block;
	}

	.eyebrow {
		margin-bottom: 16rpx;
		color: var(--brand-primary, #1c7d50);
		font-size: 24rpx;
		font-weight: 700;
		letter-spacing: 4rpx;
	}

	.title {
		margin-bottom: 20rpx;
		color: #19352a;
		font-size: 48rpx;
		font-weight: 700;
	}

	.description {
		margin-bottom: 44rpx;
		color: #75837d;
		font-size: 28rpx;
		line-height: 1.7;
	}

	.field {
		margin-bottom: 32rpx;
	}

	.label {
		margin-bottom: 14rpx;
		color: var(--brand-secondary, #3e3a39);
		font-size: 28rpx;
		font-weight: 600;
	}

	.input {
		box-sizing: border-box;
		width: 100%;
		height: 96rpx;
		padding: 0 28rpx;
		border: 2rpx solid #dce5e0;
		border-radius: 18rpx;
		color: #19352a;
		background: #ffffff;
		font-size: 28rpx;
	}

	.primary-button {
		display: flex;
		height: 96rpx;
		align-items: center;
		justify-content: center;
		margin: 12rpx 0 24rpx;
		border: 0;
		border-radius: 18rpx;
		color: #ffffff;
		background: var(--brand-primary, #1c7d50);
		font-size: 30rpx;
		font-weight: 700;
	}

	.primary-button[disabled] {
		color: rgba(255, 255, 255, 0.8);
		background: #70aa8e;
	}

	.hint {
		color: #929d98;
		font-size: 24rpx;
		line-height: 1.6;
		text-align: center;
	}
</style>
