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

		<view v-if="selectedMessage" class="detail-layer">
			<view class="detail-header">
				<text class="detail-back" @click="closeDetail">‹</text>
				<text class="detail-header-title">提醒任务详情</text>
				<text class="detail-close" @click="closeDetail">×</text>
			</view>
			<scroll-view scroll-y class="detail-scroll">
				<view class="detail-message">
					<text class="detail-message-title">{{ selectedMessage.title }}</text>
					<text class="detail-message-content">{{ selectedMessage.content }}</text>
					<text class="time">{{ dateTime(selectedMessage.occurredTime, 19) }}</text>
				</view>
				<view v-if="detailLoading" class="detail-state">正在加载任务详情…</view>
				<view v-else-if="detailError" class="detail-state warning-text">{{ detailError }}。提醒内容仍可正常查看。</view>
				<template v-if="businessDetail">
					<view class="summary-card">
						<view class="summary-title-row"><text class="summary-title">{{ businessDetail.equipmentName }}</text><text class="status-pill">{{ statusLabel(businessDetail.taskStatus) }}</text></view>
						<text class="summary-code">{{ businessDetail.equipmentCode }} · {{ businessDetail.taskCode }}</text>
						<view class="summary-grid">
							<view><text class="label">方案</text><text class="value">{{ businessDetail.schemeName || '—' }}</text></view>
							<view><text class="label">组织</text><text class="value">{{ businessDetail.organizationName || '—' }}</text></view>
							<view><text class="label">执行人员</text><text class="value">{{ businessDetail.assigneeNames || '未派工' }}</text></view>
							<view><text class="label">截止时间</text><text class="value">{{ dateTime(businessDetail.dueTime, 19) }}</text></view>
						</view>
					</view>

					<view class="section-card">
						<text class="section-title">点检项目与结果（{{ businessDetail.items?.length || 0 }}）</text>
						<view v-for="(item, index) in businessDetail.items" :key="item.id" class="item-row">
							<view class="item-heading"><text class="item-index">{{ index + 1 }}</text><text class="item-name">{{ item.itemName }}</text><text :class="['result-pill', { abnormal: item.abnormalFlag }]">{{ resultLabel(item) }}</text></view>
							<text v-if="item.itemPart || item.itemContent" class="item-line">{{ [item.itemPart, item.itemContent].filter(Boolean).join(' · ') }}</text>
							<text class="item-standard">标准：{{ item.itemStandard || '—' }}</text>
							<text v-if="item.abnormalDescription" class="abnormal-description">异常说明：{{ item.abnormalDescription }}</text>
							<text class="item-executor">{{ item.executedByName || '尚未执行' }} · {{ dateTime(item.executedTime, 19) }}</text>
						</view>
					</view>

					<view v-if="businessDetail.attachments?.length" class="section-card">
						<text class="section-title">现场图片与附件（{{ businessDetail.attachments.length }}）</text>
						<view class="attachment-grid">
							<view v-for="attachment in businessDetail.attachments" :key="attachment.id" class="attachment-card" @click="openAttachment(attachment)">
								<image v-if="attachmentPaths[attachment.id] && isImageAttachment(attachment)" class="attachment-image" :src="attachmentPaths[attachment.id]" mode="aspectFill" />
								<view v-else class="attachment-placeholder">{{ isImageAttachment(attachment) ? '图片加载中' : '文件附件' }}</view>
								<text class="attachment-name">{{ attachment.itemName || attachment.originalName }}</text>
							</view>
						</view>
					</view>
				</template>
				<view class="detail-bottom-space" />
			</scroll-view>
			<view class="detail-actions">
				<button class="secondary-action" @click="closeDetail">关闭</button>
				<button v-if="canEnterOperation" class="primary-action" @click="enterOperation">进入任务处理</button>
			</view>
		</view>
	</view>
</template>

<script setup>
	import { computed, ref } from 'vue'
	import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app'
	import { notificationApi } from '../../api/notification.js'
	import { inspectionApi } from '../../api/inspection.js'
	import { navigateTo } from '../../constants/routes.js'
	import { can, displayName, sessionState } from '../../stores/session.js'
	import { errorMessage } from '../../utils/errors.js'
	import { inspectionTaskTarget } from '../../utils/inspection-navigation.js'

	const rows = ref([])
	const loading = ref(false)
	const error = ref('')
	const selectedMessage = ref(null)
	const businessDetail = ref(null)
	const detailLoading = ref(false)
	const detailError = ref('')
	const attachmentPaths = ref({})
	const unreadCount = computed(() => rows.value.filter((item) => !item.readTime).length)
	const isAdministrator = computed(() => (sessionState.user?.roles || []).some((role) => ['ADMIN', 'SUPER_ADMIN'].includes(String(role).toUpperCase())))
	const canEnterOperation = computed(() => {
		const detail = businessDetail.value
		if (!detail || detail.businessType !== 'INSPECTION') return false
		if (['COMPLETED', 'CANCELLED', 'VOIDED'].includes(detail.taskStatus)) return false
		if (!can('inspection:task:execute') || !can('inspection:my-task:view')) return false
		return isAdministrator.value || String(detail.assigneeNames || '').includes(displayName())
	})
	onLoad(load)
	onPullDownRefresh(async () => { try { await load() } finally { uni.stopPullDownRefresh() } })
	function dateTime(value, length = 16) { return value ? String(value).replace('T', ' ').slice(0, length) : '—' }
	function severityClass(value) { return ['HIGH', 'CRITICAL'].includes(value) ? 'danger' : 'warning' }
	function statusLabel(value) { return ({ PENDING_ASSIGNMENT: '未派工', PENDING: '待执行', IN_PROGRESS: '执行中', PAUSED: '已暂停', PENDING_CONFIRMATION: '待确认', PENDING_REVIEW: '待复核', COMPLETED: '已完成', OVERDUE: '已逾期', CANCELLED: '已取消', VOIDED: '已作废' })[value] || value || '—' }
	function resultLabel(item) {
		if (item.skippedFlag || item.resultCode === 'SKIPPED') return '已跳过'
		if (item.numericValue !== undefined && item.numericValue !== null) return `${item.numericValue}${item.unit || ''}`
		if (item.selectedValue) return item.selectedValue
		if (item.textValue) return item.textValue
		return ({ NORMAL: '正常', ABNORMAL: '异常', PASS: '合格', FAIL: '不合格' })[item.resultCode] || '未填写'
	}
	function isImageAttachment(attachment) { return String(attachment.contentType || '').toLowerCase().startsWith('image/') || /^(jpg|jpeg|png|gif|webp|bmp)$/i.test(attachment.extension || '') }
	async function load() {
		if (loading.value) return
		loading.value = true; error.value = ''
		try { rows.value = (await notificationApi.messages({ page: 1, pageSize: 100 }))?.records || [] }
		catch (cause) { error.value = errorMessage(cause, '消息加载失败') }
		finally { loading.value = false }
	}
	async function openMessage(message) {
		selectedMessage.value = message
		businessDetail.value = null
		detailError.value = ''
		attachmentPaths.value = {}
		detailLoading.value = true
		try {
			if (!message.readTime) await notificationApi.read(message.id)
			businessDetail.value = await notificationApi.businessDetail(message.id)
			await loadAttachmentPreviews(message.id, businessDetail.value.attachments || [])
			await load()
		} catch (cause) { detailError.value = errorMessage(cause, '关联任务详情暂时无法加载') }
		finally { detailLoading.value = false }
	}
	async function loadAttachmentPreviews(messageId, attachments) {
		const images = attachments.filter(isImageAttachment)
		const entries = await Promise.all(images.map(async (attachment) => {
			try { return [attachment.id, await notificationApi.businessAttachmentContent(messageId, attachment.id)] }
			catch { return null }
		}))
		attachmentPaths.value = Object.fromEntries(entries.filter(Boolean))
	}
	function closeDetail() { selectedMessage.value = null; businessDetail.value = null; detailError.value = ''; attachmentPaths.value = {} }
	async function enterOperation() {
		if (!canEnterOperation.value || !businessDetail.value) return
		try {
			const detail = await inspectionApi.task(businessDetail.value.businessId)
			navigateTo(inspectionTaskTarget(detail.task).url)
		} catch (cause) {
			uni.showToast({ title: errorMessage(cause, '任务加载失败'), icon: 'none' })
		}
	}
	async function openAttachment(attachment) {
		let path = attachmentPaths.value[attachment.id]
		if (!path) {
			try { path = await notificationApi.businessAttachmentContent(selectedMessage.value.id, attachment.id); attachmentPaths.value = { ...attachmentPaths.value, [attachment.id]: path } }
			catch (cause) { uni.showToast({ title: errorMessage(cause, '附件加载失败'), icon: 'none' }); return }
		}
		if (isImageAttachment(attachment)) {
			const urls = businessDetail.value.attachments.filter(isImageAttachment).map((item) => attachmentPaths.value[item.id]).filter(Boolean)
			uni.previewImage({ current: path, urls: urls.length ? urls : [path] })
		} else uni.openDocument({ filePath: path, showMenu: true, fail: () => uni.showToast({ title: '暂不支持打开此文件', icon: 'none' }) })
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
	.detail-layer { position: fixed; z-index: 100; inset: 0; display: flex; flex-direction: column; background: #f4f7f5; }
	.detail-header { display: grid; height: 105rpx; padding: env(safe-area-inset-top) 25rpx 0; align-items: center; grid-template-columns: 70rpx 1fr 70rpx; color: #fff; background: var(--brand-primary, #1c7d50); }
	.detail-back, .detail-close { font-size: 58rpx; line-height: 1; text-align: center; }
	.detail-header-title { font-size: 31rpx; font-weight: 750; text-align: center; }
	.detail-scroll { flex: 1; height: 0; }
	.detail-message, .summary-card, .section-card { margin: 22rpx 24rpx 0; padding: 25rpx; border-radius: 22rpx; background: #fff; box-shadow: 0 8rpx 28rpx rgba(25,53,42,.06); }
	.detail-message { color: #fff; background: linear-gradient(135deg, #124431, var(--brand-primary, #1c7d50)); }
	.detail-message-title, .detail-message-content { display: block; }
	.detail-message-title { font-size: 30rpx; font-weight: 800; }
	.detail-message-content { margin-top: 12rpx; font-size: 24rpx; line-height: 1.6; }
	.detail-message .time { color: rgba(255,255,255,.7); }
	.detail-state { margin: 22rpx 24rpx 0; padding: 35rpx 25rpx; border-radius: 18rpx; color: #77847e; background: #fff; text-align: center; }
	.warning-text { color: #95620b; background: #fff7e5; }
	.summary-title-row, .item-heading { display: flex; align-items: center; gap: 12rpx; }
	.summary-title { flex: 1; color: #183c2e; font-size: 33rpx; font-weight: 850; }
	.status-pill, .result-pill { padding: 6rpx 15rpx; border-radius: 18rpx; color: #167348; background: #e8f5ee; font-size: 20rpx; }
	.summary-code { display: block; margin-top: 8rpx; color: #78877f; font-size: 22rpx; }
	.summary-grid { display: grid; margin-top: 22rpx; gap: 20rpx; grid-template-columns: 1fr 1fr; }
	.label, .value { display: block; }
	.label { color: #96a19b; font-size: 20rpx; }
	.value { margin-top: 6rpx; color: #344b40; font-size: 23rpx; line-height: 1.4; }
	.section-title { display: block; color: #193e30; font-size: 28rpx; font-weight: 800; }
	.item-row { padding: 24rpx 0; border-bottom: 1rpx solid #e8eeeb; }
	.item-row:last-child { border-bottom: 0; }
	.item-index { display: flex; width: 40rpx; height: 40rpx; align-items: center; justify-content: center; border-radius: 50%; color: #fff; background: var(--brand-primary, #1c7d50); font-size: 20rpx; }
	.item-name { flex: 1; color: #263f34; font-size: 25rpx; font-weight: 750; }
	.result-pill.abnormal { color: #a00008; background: #ffebec; }
	.item-line, .item-standard, .abnormal-description, .item-executor { display: block; margin-top: 12rpx; font-size: 21rpx; line-height: 1.5; }
	.item-line, .item-executor { color: #86928c; }
	.item-standard { padding: 12rpx 15rpx; border-radius: 10rpx; color: #4d6157; background: #f3f6f4; }
	.abnormal-description { color: #aa151c; }
	.attachment-grid { display: grid; margin-top: 20rpx; gap: 16rpx; grid-template-columns: 1fr 1fr; }
	.attachment-card { overflow: hidden; border: 1rpx solid #e0e8e4; border-radius: 14rpx; }
	.attachment-image, .attachment-placeholder { width: 100%; height: 190rpx; }
	.attachment-placeholder { display: flex; align-items: center; justify-content: center; color: #87938d; background: #edf2ef; font-size: 22rpx; }
	.attachment-name { display: block; overflow: hidden; padding: 13rpx; color: #41574c; font-size: 20rpx; text-overflow: ellipsis; white-space: nowrap; }
	.detail-bottom-space { height: 35rpx; }
	.detail-actions { display: flex; padding: 18rpx 24rpx calc(18rpx + env(safe-area-inset-bottom)); gap: 18rpx; background: #fff; box-shadow: 0 -8rpx 28rpx rgba(25,53,42,.08); }
	.detail-actions button { flex: 1; height: 78rpx; border-radius: 14rpx; font-size: 26rpx; line-height: 78rpx; }
	.secondary-action { color: #52655c; background: #edf2ef; }
	.primary-action { color: #fff; background: var(--brand-primary, #1c7d50); }
</style>
