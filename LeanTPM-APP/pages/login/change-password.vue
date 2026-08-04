<template>
	<view class="page" :style="$brandTheme()">
		<view class="card">
			<text class="title">修改初始密码</text>
			<text class="description">首次登录必须设置新密码。新密码至少 6 位，且不能与当前密码相同。</text>

			<text class="label">当前密码</text>
			<input v-model="currentPassword" class="input" password placeholder="请输入当前密码" />
			<text class="label">新密码</text>
			<input v-model="newPassword" class="input" password placeholder="至少 6 位" />
			<text class="label">确认新密码</text>
			<input v-model="confirmPassword" class="input" password placeholder="再次输入新密码" />

			<button class="primary-button" :loading="loading" :disabled="loading" @click="submit">确认修改</button>
		</view>
	</view>
</template>

<script setup>
	import { ref } from 'vue'
	import { ROUTES, reLaunchTo } from '../../constants/routes.js'
	import { changePassword } from '../../stores/session.js'
	import { errorMessage } from '../../utils/errors.js'

	const currentPassword = ref('')
	const newPassword = ref('')
	const confirmPassword = ref('')
	const loading = ref(false)

	async function submit() {
		if (!currentPassword.value) return uni.showToast({ title: '请输入当前密码', icon: 'none' })
		if (newPassword.value.length < 6) return uni.showToast({ title: '新密码至少 6 位', icon: 'none' })
		if (newPassword.value === currentPassword.value) return uni.showToast({ title: '新密码不能与当前密码相同', icon: 'none' })
		if (newPassword.value !== confirmPassword.value) return uni.showToast({ title: '两次新密码不一致', icon: 'none' })

		loading.value = true
		try {
			await changePassword(currentPassword.value, newPassword.value)
			currentPassword.value = ''
			newPassword.value = ''
			confirmPassword.value = ''
			uni.showToast({ title: '密码修改成功', icon: 'success' })
			setTimeout(() => reLaunchTo(ROUTES.workbench), 600)
		} catch (error) {
			uni.showModal({ title: '修改失败', content: errorMessage(error), showCancel: false })
		} finally {
			loading.value = false
		}
	}
</script>

<style>
	.page { box-sizing: border-box; min-height: 100vh; padding: 44rpx 36rpx; background: #f4f7f5; }
	.card { padding: 42rpx 34rpx; border-radius: 28rpx; background: #fff; box-shadow: 0 18rpx 50rpx rgba(25,53,42,.08); }
	.title, .description, .label { display: block; }
	.title { color: #19352a; font-size: 42rpx; font-weight: 800; }
	.description { margin: 16rpx 0 36rpx; color: #7e8984; font-size: 26rpx; line-height: 1.7; }
	.label { margin: 24rpx 0 12rpx; color: var(--brand-secondary, #3e3a39); font-size: 27rpx; font-weight: 600; }
	.input { box-sizing: border-box; width: 100%; height: 92rpx; padding: 0 24rpx; border: 2rpx solid #dce5e0; border-radius: 17rpx; font-size: 28rpx; }
	.primary-button { display: flex; height: 94rpx; align-items: center; justify-content: center; margin-top: 42rpx; border-radius: 18rpx; color: #fff; background: var(--brand-primary, #1c7d50); font-size: 30rpx; font-weight: 700; }
</style>
