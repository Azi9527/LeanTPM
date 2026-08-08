<template>
	<view class="page" :style="$brandTheme()">
		<view class="filter-scroll">
			<scroll-view scroll-x class="filters">
				<view
					v-for="item in statusOptions"
					:key="item.value"
					:class="['filter', { active: status === item.value }]"
					@click="selectStatus(item.value)"
				>{{ item.label }}</view>
			</scroll-view>
		</view>

		<view class="summary"><text>设备清单</text><text>{{ total }} 台</text></view>
		<view v-if="error" class="error" @click="load(true)">{{ error }} · 点击重试</view>
		<view v-for="row in rows" :key="row.id" class="card" @click="openEquipment(row)">
			<view class="card-head">
				<view><text class="name">{{ row.equipmentName }}</text><text class="code">{{ row.equipmentCode }}</text></view>
				<text :class="['status', statusClass(row.currentStatusCode)]">{{ statusName(row.currentStatusCode) }}</text>
			</view>
			<text class="location">{{ row.organizationName || '未设置组织' }} · {{ row.locationName || '未设置位置' }}</text>
			<text class="owner">负责人：{{ row.primaryResponsibleName || '未设置' }}</text>
		</view>
		<view v-if="!loading && !rows.length" class="empty">当前筛选条件下暂无设备</view>
		<button v-if="rows.length < total" class="load-more" :loading="loading" @click="load(false)">加载更多</button>
	</view>
</template>

<script setup>
	import { ref } from 'vue'
	import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app'
	import { equipmentApi } from '../../api/equipment.js'
	import { ROUTES, navigateTo, routeWithQuery } from '../../constants/routes.js'
	import { errorMessage } from '../../utils/errors.js'

	const statusOptions = [
		{ label: '全部', value: '' }, { label: '空闲', value: 'IDLE' },
		{ label: '运行', value: 'RUNNING' }, { label: '停机', value: 'STOPPED' },
		{ label: '报废', value: 'SCRAPPED' }
	]
	const labels = Object.fromEntries(statusOptions.map((item) => [item.value, item.label]))
	const status = ref('')
	const rows = ref([])
	const total = ref(0)
	const page = ref(1)
	const loading = ref(false)
	const error = ref('')

	onLoad((query) => { status.value = query?.status || ''; load(true) })
	onPullDownRefresh(async () => { try { await load(true) } finally { uni.stopPullDownRefresh() } })

	function statusName(value) { return labels[value] || value || '未知' }
	function statusClass(value) { return String(value || '').toLowerCase() }
	async function selectStatus(value) { status.value = value; await load(true) }

	async function load(reset) {
		if (loading.value) return
		loading.value = true
		error.value = ''
		if (reset) { page.value = 1; rows.value = [] }
		try {
			const result = await equipmentApi.page({
				currentStatusCode: status.value || undefined,
				page: page.value,
				pageSize: 30
			})
			const records = result?.records || []
			rows.value = reset ? records : rows.value.concat(records)
			total.value = Number(result?.total || rows.value.length)
			page.value += 1
		} catch (cause) {
			error.value = errorMessage(cause, '设备清单加载失败')
		} finally { loading.value = false }
	}

	async function openEquipment(row) {
		if (!row.activeBarcodeToken) {
			uni.showToast({ title: '该设备尚未生成有效二维码', icon: 'none' })
			return
		}
		navigateTo(routeWithQuery(ROUTES.equipmentContext, { token: row.activeBarcodeToken }))
	}
</script>

<style>
	.page { min-height: 100vh; padding: 24rpx 26rpx 60rpx; background: #f4f7f5; }
	.filter-scroll { margin: 0 -26rpx 22rpx; }
	.filters { width: 100%; white-space: nowrap; }
	.filter { display: inline-flex; height: 64rpx; align-items: center; margin-left: 18rpx; padding: 0 29rpx; border-radius: 32rpx; color: #69766f; background: #fff; font-size: 25rpx; }
	.filter.active { color: #fff; background: var(--brand-primary, #1c7d50); }
	.summary { display: flex; justify-content: space-between; margin: 12rpx 4rpx 18rpx; color: #234235; font-size: 29rpx; font-weight: 700; }
	.summary text:last-child { color: var(--brand-primary, #1c7d50); }
	.card { margin-bottom: 18rpx; padding: 28rpx; border-radius: 22rpx; background: #fff; box-shadow: 0 10rpx 32rpx rgba(25,53,42,.06); }
	.card-head { display: flex; justify-content: space-between; gap: 20rpx; }
	.name, .code, .location, .owner { display: block; }
	.name { color: #203b30; font-size: 29rpx; font-weight: 750; }
	.code { margin-top: 6rpx; color: #8a958f; font-family: monospace; font-size: 22rpx; }
	.status { height: 48rpx; padding: 0 17rpx; border-radius: 24rpx; color: #5e6863; background: #eef1f0; font-size: 22rpx; line-height: 48rpx; }
	.status.running { color: #157448; background: #e5f6ed; }
	.status.stopped { color: #a26907; background: #fff4df; }
	.status.fault, .status.repair { color: #b10008; background: #ffeded; }
	.location { margin-top: 23rpx; color: #56645d; font-size: 24rpx; }
	.owner { margin-top: 9rpx; color: #89938e; font-size: 22rpx; }
	.error, .empty { padding: 48rpx 24rpx; color: #8d9792; text-align: center; font-size: 25rpx; }
	.error { margin-bottom: 18rpx; border-radius: 18rpx; color: #a00008; background: #fff0f0; }
	.load-more { margin-top: 24rpx; color: var(--brand-primary, #1c7d50); background: #e8f5ee; font-size: 25rpx; }
</style>
