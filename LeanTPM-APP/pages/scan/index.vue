<template>
	<view class="page" :style="$brandTheme()">
		<view class="scanner-card">
			<view class="scan-frame"><view/><view/><view/><view/><text>▦</text></view>
			<text class="title">扫描设备二维码</text>
			<text class="hint">{{ taskId ? `请扫描“${expectedEquipmentIdentity}”的 LeanTPM 二维码，校验通过后进入点检。` : '将每台设备的 LeanTPM 二维码放入取景框，识别后进入设备现场页。' }}</text>
			<!-- #ifndef H5 -->
			<button class="primary" :loading="scanning" @click="scan">打开相机扫码</button>
			<!-- #endif -->
			<!-- #ifdef H5 -->
			<text class="web-hint">H5 调试环境请使用下方手工输入；Android 与微信小程序支持相机扫码。</text>
			<!-- #endif -->
		</view>
		<view class="manual-card">
			<text class="manual-title">手工输入二维码内容</text>
			<textarea v-model="manualValue" class="textarea" maxlength="500" placeholder="粘贴 64 位设备令牌或完整二维码链接" />
			<button class="secondary" @click="openValue(manualValue)">识别并打开设备</button>
		</view>
		<AppBottomNav active="scan" />
	</view>
</template>

<script setup>
	import { computed, ref } from 'vue'
	import { onLoad } from '@dcloudio/uni-app'
	import { inspectionApi } from '../../api/inspection.js'
	import { mobileApi } from '../../api/mobile.js'
	import { ROUTES, navigateTo, routeWithQuery } from '../../constants/routes.js'
	import { requireEquipmentToken } from '../../utils/equipment-token.js'
	import { equipmentScanErrorMessage, errorMessage } from '../../utils/errors.js'
	import { scannedEquipmentMatchesTask } from '../../utils/inspection-navigation.js'
	import AppBottomNav from '../../components/AppBottomNav.vue'

	const scanning = ref(false)
	const validating = ref(false)
	const manualValue = ref('')
	const taskId = ref(0)
	const expectedEquipmentCode = ref('')
	const expectedEquipmentName = ref('')
	const expectedEquipmentIdentity = computed(() => [expectedEquipmentCode.value, expectedEquipmentName.value].filter(Boolean).join(' · ') || '任务设备')

	onLoad((query) => {
		taskId.value = Number(query?.taskId || 0)
		expectedEquipmentCode.value = String(query?.equipmentCode || '')
		expectedEquipmentName.value = String(query?.equipmentName || '')
	})

	function scan() {
		if (scanning.value) return
		scanning.value = true
		uni.scanCode({
			onlyFromCamera: true,
			scanType: ['qrCode'],
			success: (result) => void openValue(result.result),
			fail: (error) => {
				if (!String(error?.errMsg || '').includes('cancel')) uni.showToast({ title: '扫码失败，请重试', icon: 'none' })
			},
			complete: () => { scanning.value = false }
		})
	}

	async function openValue(value) {
		if (validating.value) return
		validating.value = true
		try {
			const token = requireEquipmentToken(value)
			if (taskId.value > 0) {
				let detail
				try { detail = await inspectionApi.task(taskId.value) }
				catch (cause) {
					return uni.showModal({ title: '无法加载任务', content: errorMessage(cause, '任务加载失败'), showCancel: false })
				}
				const context = await mobileApi.equipment(token)
				const task = detail.task
				if (!scannedEquipmentMatchesTask(task, context?.equipment)) {
					const requiredIdentity = [task.equipmentCode, task.equipmentName || expectedEquipmentName.value].filter(Boolean).join(' · ') || task.equipmentId
					const scannedIdentity = [context?.equipment?.equipmentCode, context?.equipment?.equipmentName].filter(Boolean).join(' · ') || '当前设备'
					return uni.showModal({
						title: '设备不匹配',
						content: `本任务要求扫描“${requiredIdentity}”，当前扫描的是“${scannedIdentity}”。`,
						showCancel: false
					})
				}
				navigateTo(routeWithQuery('/pages/inspection/detail', { id: taskId.value }))
				return
			}
			navigateTo(routeWithQuery(ROUTES.equipmentContext, { token }))
		} catch (error) {
			uni.showModal({ title: '无法识别', content: equipmentScanErrorMessage(error), showCancel: false })
		} finally { validating.value = false }
	}
</script>

<style>
	.page { min-height: 100vh; padding: 34rpx 28rpx 60rpx; background: #f4f7f5; }
	.scanner-card, .manual-card { padding: 38rpx 30rpx; border-radius: 28rpx; background: #fff; box-shadow: 0 14rpx 42rpx rgba(25,53,42,.07); }
	.scanner-card { display: flex; align-items: center; flex-direction: column; text-align: center; }
	.scan-frame { position: relative; display: flex; width: 330rpx; height: 330rpx; align-items: center; justify-content: center; margin: 24rpx 0 34rpx; border-radius: 30rpx; color: var(--brand-primary, #1c7d50); background: #edf7f2; }
	.scan-frame text { font-size: 110rpx; opacity: .48; }
	.scan-frame view { position: absolute; width: 60rpx; height: 60rpx; border-color: var(--brand-primary, #1c7d50); }
	.scan-frame view:nth-child(1) { top: 18rpx; left: 18rpx; border-top: 7rpx solid; border-left: 7rpx solid; }
	.scan-frame view:nth-child(2) { top: 18rpx; right: 18rpx; border-top: 7rpx solid; border-right: 7rpx solid; }
	.scan-frame view:nth-child(3) { bottom: 18rpx; left: 18rpx; border-bottom: 7rpx solid; border-left: 7rpx solid; }
	.scan-frame view:nth-child(4) { right: 18rpx; bottom: 18rpx; border-right: 7rpx solid; border-bottom: 7rpx solid; }
	.title, .hint, .manual-title, .web-hint { display: block; }
	.title { color: #1e3b2f; font-size: 34rpx; font-weight: 800; }
	.hint, .web-hint { margin-top: 14rpx; color: #7b8882; font-size: 24rpx; line-height: 1.65; }
	.primary, .secondary { width: 100%; margin-top: 30rpx; border-radius: 17rpx; font-size: 28rpx; }
	.primary { color: #fff; background: var(--brand-primary, #1c7d50); }
	.manual-card { margin-top: 24rpx; }
	.manual-title { color: #294538; font-size: 28rpx; font-weight: 700; }
	.textarea { box-sizing: border-box; width: 100%; height: 160rpx; margin-top: 22rpx; padding: 20rpx; border: 2rpx solid #dce6e1; border-radius: 17rpx; font-size: 25rpx; }
	.secondary { color: var(--brand-primary, #1c7d50); background: #e9f5ef; }
</style>
