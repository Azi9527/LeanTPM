<template>
	<view class="nav-shell" :style="$brandTheme()">
		<view class="nav-spacer" />
		<view class="bottom-nav">
			<view
				v-for="item in items"
				:key="item.key"
				:class="['nav-item', { active: active === item.key }]"
				@click="open(item)"
			>
				<text>{{ item.icon }}</text><text>{{ item.label }}</text>
			</view>
		</view>
	</view>
</template>

<script setup>
	import { ROUTES } from '../constants/routes.js'

	const props = defineProps({ active: { type: String, required: true } })
	const items = [
		{ key: 'workbench', label: '工作台', icon: '▣', route: ROUTES.workbench },
		{ key: 'scan', label: '扫码', icon: '⌁', route: ROUTES.scan },
		{ key: 'inspection', label: '点检', icon: '✓', route: ROUTES.inspectionTasks },
		{ key: 'report', label: '报表', icon: '▥', route: ROUTES.report },
		{ key: 'profile', label: '我的', icon: '●', route: ROUTES.profile }
	]

	function open(item) {
		if (props.active === item.key) return
		uni.reLaunch({ url: item.route })
	}
</script>

<style scoped>
	.nav-spacer { height: calc(130rpx + env(safe-area-inset-bottom)); }
	.bottom-nav { position: fixed; z-index: 50; right: 0; bottom: 0; left: 0; display: grid; grid-template-columns: repeat(5, 1fr); padding: 15rpx 8rpx calc(15rpx + env(safe-area-inset-bottom)); border-top: 1rpx solid #e5ebe8; background: rgba(255,255,255,.98); }
	.nav-item { display: flex; align-items: center; flex-direction: column; color: #89948e; font-size: 21rpx; }
	.nav-item text:first-child { margin-bottom: 4rpx; font-size: 30rpx; }
	.nav-item.active { color: var(--brand-primary, #1c7d50); font-weight: 700; }
</style>
