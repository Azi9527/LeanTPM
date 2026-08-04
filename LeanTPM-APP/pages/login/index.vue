<template>
	<view class="page">
		<view class="brand">
			<view class="mark">LT</view>
			<view>
				<text class="brand-name">LeanTPM</text>
				<text class="brand-subtitle">{{ brandingState.subtitle }}</text>
			</view>
		</view>

		<view class="login-card">
			<text class="eyebrow">欢迎回来</text>
			<text class="title">登录 {{ brandingState.shortName }}</text>
			<text class="description">使用企业分配的账号进入设备管理平台</text>

			<view class="field">
				<text class="label">账号</text>
				<input
					class="input"
					:value="form.username"
					type="text"
					placeholder="请输入账号"
					confirm-type="next"
					@input="updateUsername"
				/>
			</view>

			<view class="field">
				<text class="label">密码</text>
				<view class="password-field">
					<input
						class="password-input"
						:value="form.password"
						type="text"
						:password="!showPassword"
						placeholder="请输入密码"
						confirm-type="done"
						@input="updatePassword"
						@confirm="submit"
					/>
					<text class="password-toggle" @click="showPassword = !showPassword">
						{{ showPassword ? '隐藏' : '显示' }}
					</text>
				</view>
			</view>

			<view v-if="challenge.enabled" class="field">
				<text class="label">验证码</text>
				<view class="captcha-row">
					<input
						class="captcha-input"
						:value="form.captchaCode"
						type="text"
						maxlength="16"
						placeholder="请输入验证码"
						@input="updateCaptcha"
					/>
					<view class="captcha-image" @click="loadCaptcha">
						<image v-if="challenge.imageDataUrl" :src="challenge.imageDataUrl" mode="aspectFit" />
						<text v-else>{{ captchaLoading ? '加载中' : '点击刷新' }}</text>
					</view>
				</view>
			</view>

			<view class="login-options">
				<view class="remember" @click="remember = !remember">
					<view :class="['checkbox', { checked: remember }]">{{ remember ? '✓' : '' }}</view>
					<text>记住账号</text>
				</view>
				<text class="lock-tip">连续失败 5 次将临时锁定</text>
			</view>

			<button class="primary-button" :loading="loading" :disabled="loading" @click="submit">
				进入系统
			</button>

			<text class="login-help">首次登录后需修改初始密码。如无法登录，请联系系统管理员。</text>
			<button class="text-button" @click="openSetup">配置企业服务地址</button>
		</view>
	</view>
</template>

<script setup>
	import { reactive, ref } from 'vue'
	import { onLoad } from '@dcloudio/uni-app'
	import { authApi } from '../../api/auth.js'
	import { ROUTES, reLaunchTo } from '../../constants/routes.js'
	import { brandingState, initializeBranding } from '../../stores/branding.js'
	import { rememberedUsername, signIn } from '../../stores/session.js'
	import { errorMessage } from '../../utils/errors.js'

	const form = reactive({ username: '', password: '', captchaCode: '' })
	const challenge = ref({ enabled: false })
	const loading = ref(false)
	const captchaLoading = ref(false)
	const remember = ref(true)
	const showPassword = ref(false)

	onLoad(async () => {
		form.username = rememberedUsername()
		await initializeBranding()
		await loadCaptcha()
	})

	function eventValue(event) {
		return String(event?.detail?.value ?? '')
	}

	function updateUsername(event) { form.username = eventValue(event) }
	function updatePassword(event) { form.password = eventValue(event) }
	function updateCaptcha(event) { form.captchaCode = eventValue(event) }

	async function loadCaptcha() {
		captchaLoading.value = true
		try {
			challenge.value = await authApi.captcha()
			form.captchaCode = ''
		} catch (error) {
			challenge.value = { enabled: false }
			uni.showToast({ title: errorMessage(error, '验证码加载失败'), icon: 'none' })
		} finally {
			captchaLoading.value = false
		}
	}

	async function submit() {
		if (loading.value) return
		const username = form.username.trim()
		if (!username) return uni.showToast({ title: '请输入账号', icon: 'none' })
		if (!form.password) return uni.showToast({ title: '请输入密码', icon: 'none' })
		if (challenge.value.enabled && !form.captchaCode.trim()) {
			return uni.showToast({ title: '请输入验证码', icon: 'none' })
		}

		loading.value = true
		try {
			const user = await signIn({
				username,
				password: form.password,
				captchaId: challenge.value.captchaId,
				captchaCode: form.captchaCode.trim()
			}, remember.value)
			form.password = ''
			await reLaunchTo(user.mustChangePassword ? '/pages/login/change-password' : ROUTES.workbench)
		} catch (error) {
			form.password = ''
			uni.showModal({ title: '登录失败', content: errorMessage(error, '账号或密码错误'), showCancel: false })
			if (challenge.value.enabled) await loadCaptcha()
		} finally {
			loading.value = false
		}
	}

	function openSetup() {
		reLaunchTo(ROUTES.setup)
	}
</script>

<style>
	.page {
		box-sizing: border-box;
		min-height: 100vh;
		padding: calc(70rpx + env(safe-area-inset-top)) 38rpx 50rpx;
		background: linear-gradient(160deg, rgba(28, 125, 80, 0.19), rgba(244, 247, 245, 0) 39%), #f4f7f5;
	}

	.brand { display: flex; align-items: center; margin-bottom: 72rpx; }
	.mark { display: flex; width: 76rpx; height: 76rpx; align-items: center; justify-content: center; margin-right: 20rpx; border-radius: 18rpx; color: #fff; background: #1c7d50; font-size: 28rpx; font-weight: 800; }
	.brand-name, .brand-subtitle, .eyebrow, .title, .description, .label, .login-help { display: block; }
	.brand-name { color: #19352a; font-size: 40rpx; font-weight: 800; }
	.brand-subtitle { margin-top: 3rpx; color: #78847f; font-size: 22rpx; }
	.login-card { padding: 46rpx 34rpx 34rpx; border: 1rpx solid rgba(28, 125, 80, .12); border-radius: 32rpx; background: #fff; box-shadow: 0 24rpx 70rpx rgba(28, 70, 51, .1); }
	.eyebrow { color: #1c7d50; font-size: 23rpx; font-weight: 700; letter-spacing: 4rpx; }
	.title { margin-top: 12rpx; color: #19352a; font-size: 46rpx; font-weight: 800; }
	.description { margin: 14rpx 0 40rpx; color: #7a8580; font-size: 26rpx; line-height: 1.6; }
	.field { margin-bottom: 28rpx; }
	.label { margin-bottom: 13rpx; color: #3e3a39; font-size: 27rpx; font-weight: 600; }
	.input, .password-field, .captcha-input { box-sizing: border-box; height: 92rpx; border: 2rpx solid #dce5e0; border-radius: 17rpx; background: #fff; font-size: 28rpx; }
	.input, .captcha-input { width: 100%; padding: 0 25rpx; }
	.password-field { display: flex; align-items: center; padding: 0 23rpx; }
	.password-input { flex: 1; height: 88rpx; font-size: 28rpx; }
	.password-toggle { padding: 20rpx 0 20rpx 20rpx; color: #1c7d50; font-size: 25rpx; }
	.captcha-row { display: flex; }
	.captcha-input { flex: 1; }
	.captcha-image { display: flex; overflow: hidden; width: 210rpx; height: 92rpx; align-items: center; justify-content: center; margin-left: 16rpx; border-radius: 17rpx; color: #1c7d50; background: #eef7f2; font-size: 24rpx; }
	.captcha-image image { width: 100%; height: 100%; }
	.login-options { display: flex; align-items: center; justify-content: space-between; margin: 4rpx 0 30rpx; }
	.remember { display: flex; align-items: center; color: #47544e; font-size: 25rpx; }
	.checkbox { display: flex; width: 34rpx; height: 34rpx; align-items: center; justify-content: center; margin-right: 12rpx; border: 2rpx solid #aeb9b3; border-radius: 8rpx; color: #fff; font-size: 23rpx; }
	.checkbox.checked { border-color: #1c7d50; background: #1c7d50; }
	.lock-tip { color: #929b97; font-size: 22rpx; }
	.primary-button { display: flex; height: 94rpx; align-items: center; justify-content: center; border-radius: 18rpx; color: #fff; background: #1c7d50; font-size: 30rpx; font-weight: 700; }
	.primary-button[disabled] { color: rgba(255,255,255,.78); background: #74aa90; }
	.login-help { margin-top: 26rpx; color: #909a95; font-size: 23rpx; line-height: 1.6; text-align: center; }
	.text-button { margin-top: 12rpx; color: #1c7d50; background: transparent; font-size: 25rpx; }
</style>
