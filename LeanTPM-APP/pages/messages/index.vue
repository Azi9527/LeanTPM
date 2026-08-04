<template>
	<view class="page" :style="$brandTheme()">
		<view class="heading"><view><text class="eyebrow">ALERTS</text><text class="title">现场消息</text></view><text class="count">{{ unreadCount }} 未读</text></view>
		<view v-if="error" class="error" @click="load">{{ error }} · 点击重试</view>
		<view v-for="message in rows" :key="message.id" :class="['card', { unread: !message.readTime }]" @click="openMessage(message)">
			<view :class="['icon', severityClass(message.severity)]">!</view>
			<view class="content"><text class="message-title">{{ message.title }}</text><text class="body">{{ message.content }}</text><text class="time">{{ dateTime(message.occurredTime) }}</text><button v-if="message.acknowledgeRequired && !message.acknowledgedTime" class="ack" @click.stop="acknowledge(message)">确认收到</button></view>
			<text class="arrow">›</text>
		</view>
		<view v-if="!loading && !rows.length" class="empty">暂无现场消息</view>
	</view>
</template>

<script setup>
	import { computed, ref } from 'vue'
	import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app'
	import { notificationApi } from '../../api/notification.js'
	import { ROUTES, navigateTo, routeWithQuery } from '../../constants/routes.js'
	import { errorMessage } from '../../utils/errors.js'

	const rows = ref([])
	const loading = ref(false)
	const error = ref('')
	const unreadCount = computed(() => rows.value.filter((item) => !item.readTime).length)
	onLoad(load)
	onPullDownRefresh(async () => { try { await load() } finally { uni.stopPullDownRefresh() } })
	function dateTime(value) { return value ? String(value).replace('T', ' ').slice(0, 16) : '—' }
	function severityClass(value) { return ['HIGH', 'CRITICAL'].includes(value) ? 'danger' : 'warning' }
	async function load() {
		if (loading.value) return
		loading.value = true; error.value = ''
		try { rows.value = (await notificationApi.messages({ page: 1, pageSize: 100 }))?.records || [] }
		catch (cause) { error.value = errorMessage(cause, '消息加载失败') }
		finally { loading.value = false }
	}
	async function openMessage(message) {
		try {
			if (!message.readTime) await notificationApi.read(message.id)
			const routeTaskId = String(message.routePath || '').match(/[?&]taskId=(\d+)/)?.[1]
			if (routeTaskId || message.businessType === 'INSPECTION') navigateTo(routeWithQuery('/pages/inspection/detail', { id: routeTaskId || message.businessId }))
			else if (String(message.businessType || '').includes('ABNORMAL')) navigateTo(ROUTES.abnormalities)
			await load()
		} catch (cause) { uni.showToast({ title: errorMessage(cause, '消息操作失败'), icon: 'none' }) }
	}
	async function acknowledge(message) {
		try { await notificationApi.acknowledge(message.id); uni.showToast({ title: '已确认收到', icon: 'success' }); await load() }
		catch (cause) { uni.showToast({ title: errorMessage(cause, '确认失败'), icon: 'none' }) }
	}
</script>

<style>
	.page { min-height: 100vh; padding: 26rpx 25rpx 60rpx; background: #f4f7f5; }
	.heading { display: flex; align-items: flex-end; justify-content: space-between; margin: 5rpx 4rpx 28rpx; }
	.eyebrow, .title { display: block; }
	.eyebrow { color: var(--brand-primary, #1c7d50); font-size: 20rpx; font-weight: 800; letter-spacing: 4rpx; }
	.title { margin-top: 6rpx; color: #233f33; font-size: 38rpx; font-weight: 850; }
	.count { color: #8b9690; font-size: 22rpx; }
	.card { display: grid; grid-template-columns: 76rpx 1fr auto; align-items: center; gap: 18rpx; margin-bottom: 17rpx; padding: 25rpx; border-radius: 22rpx; background: #fff; box-shadow: 0 10rpx 32rpx rgba(25,53,42,.06); }
	.card.unread { border-left: 7rpx solid var(--brand-primary, #1c7d50); }
	.icon { display: flex; width: 72rpx; height: 72rpx; align-items: center; justify-content: center; border-radius: 21rpx; color: #95620b; background: #fff2d9; font-size: 31rpx; font-weight: 900; }
	.icon.danger { color: #aa0008; background: #ffeded; }
	.message-title, .body, .time { display: block; }
	.message-title { color: #2d453a; font-size: 27rpx; font-weight: 750; }
	.body { margin-top: 7rpx; color: #68766f; font-size: 23rpx; line-height: 1.45; }
	.time { margin-top: 9rpx; color: #98a19c; font-size: 19rpx; }
	.ack { display: inline-flex; height: 54rpx; align-items: center; margin: 14rpx 0 0; padding: 0 20rpx; border: 1rpx solid #d99a26; border-radius: 27rpx; color: #9c680b; background: #fff8e9; font-size: 21rpx; line-height: 54rpx; }
	.arrow { color: #9ba49f; font-size: 40rpx; }
	.error, .empty { padding: 60rpx 20rpx; color: #8b9590; text-align: center; font-size: 24rpx; }
	.error { color: #a00008; }
</style>
