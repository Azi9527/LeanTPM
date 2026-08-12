export function compareVersionCodes(currentCode, minimumCode) {
	const current = Number.parseInt(String(currentCode || 0), 10)
	const minimum = Number.parseInt(String(minimumCode || 0), 10)
	return { current, minimum, upgradeRequired: Number.isFinite(minimum) && minimum > 0 && current < minimum }
}

export function currentAppInfo() {
	let version = '1.0.4'
	let versionCode = 103
	try {
		const info = uni.getAppBaseInfo?.() || {}
		version = info.appVersion || version
		versionCode = Number(info.appVersionCode || versionCode)
	} catch { /* platform fallback */ }
	// #ifdef APP-PLUS
	try {
		version = plus.runtime.version || version
		versionCode = Number(plus.runtime.versionCode || versionCode)
	} catch { /* development base may not expose package metadata */ }
	// #endif
	return { version, versionCode }
}

export function openUpgradeUrl(url) {
	if (!url) return uni.showToast({ title: '管理员尚未配置下载地址', icon: 'none' })
	// #ifdef APP-PLUS
	plus.runtime.openURL(url)
	// #endif
	// #ifndef APP-PLUS
	uni.setClipboardData({ data: url, success: () => uni.showToast({ title: '下载地址已复制', icon: 'none' }) })
	// #endif
}

export function checkAndroidUpgrade(policy) {
	if (!policy) return false
	const info = currentAppInfo()
	const state = compareVersionCodes(info.versionCode, policy.minimumVersionCode)
	if (!state.upgradeRequired) return false
	uni.showModal({
		title: '必须升级 LeanTPM',
		content: `当前版本 ${info.version}（${state.current}）低于最低版本要求（${state.minimum}）。${policy.releaseNotes || ''}`,
		showCancel: false,
		confirmText: '立即升级',
		success: () => openUpgradeUrl(policy.downloadUrl)
	})
	return true
}
