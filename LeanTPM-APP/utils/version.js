import { getServerBaseUrl } from './server.js'

export function compareVersionCodes(currentCode, minimumCode) {
	const current = Number.parseInt(String(currentCode || 0), 10)
	const minimum = Number.parseInt(String(minimumCode || 0), 10)
	return { current, minimum, upgradeRequired: Number.isFinite(minimum) && minimum > 0 && current < minimum }
}

export function upgradeDownloadUrl(url, serverBaseUrl = getServerBaseUrl()) {
	const value = String(url || '').trim()
	if (!value || /^https?:\/\//i.test(value)) return value
	return `${String(serverBaseUrl || '').replace(/\/+$/, '')}/${value.replace(/^\/+/, '')}`
}

export function evaluateAndroidVersionPolicy(currentCode, policy = {}) {
	const current = Number.parseInt(String(currentCode || 0), 10)
	const minimum = Number.parseInt(String(policy.minimumVersionCode || 0), 10)
	const latest = Number.parseInt(String(policy.latestVersionCode || 0), 10)
	const updateAvailable = Number.isFinite(latest) && latest > 0 && current < latest
	return {
		current,
		latest,
		minimum,
		updateAvailable,
		upgradeRequired: (
			(Number.isFinite(minimum) && minimum > 0 && current < minimum)
			|| (policy.forceUpgrade === true && updateAvailable)
		),
		isLatest: Number.isFinite(latest) && latest > 0 && current >= latest
	}
}

export function currentAppInfo() {
	let version = '1.0.11'
	let versionCode = 104
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

let upgradeDownloadPromise = null

function downloadAndroidApk(url) {
	return new Promise((resolve, reject) => {
		const task = uni.downloadFile({
			url,
			timeout: 180000,
			success: (response) => {
				if (response.statusCode >= 200 && response.statusCode < 300 && response.tempFilePath) {
					resolve(response.tempFilePath)
					return
				}
				reject(new Error(`服务器返回状态 ${response.statusCode || 0}`))
			},
			fail: (error) => reject(new Error(error?.errMsg || '升级包下载失败'))
		})
		task?.onProgressUpdate?.(({ progress }) => {
			uni.showLoading({ title: `下载 ${Math.max(0, Number(progress) || 0)}%`, mask: true })
		})
	})
}

function installAndroidApk(filePath) {
	return new Promise((resolve, reject) => {
		plus.runtime.install(
			filePath,
			{ force: false },
			resolve,
			(error) => reject(new Error(error?.message || '系统安装程序未能打开'))
		)
	})
}

export async function openUpgradeUrl(url) {
	if (!url) throw new Error('管理员尚未配置下载地址')
	const downloadUrl = upgradeDownloadUrl(url)
	// #ifdef APP-PLUS
	if (upgradeDownloadPromise) return upgradeDownloadPromise
	upgradeDownloadPromise = (async () => {
		uni.showLoading({ title: '正在下载升级包', mask: true })
		try {
			const filePath = await downloadAndroidApk(downloadUrl)
			uni.hideLoading()
			await installAndroidApk(filePath)
			return true
		} finally {
			uni.hideLoading()
			upgradeDownloadPromise = null
		}
	})()
	return upgradeDownloadPromise
	// #endif
	// #ifndef APP-PLUS
	uni.setClipboardData({ data: downloadUrl, success: () => uni.showToast({ title: '下载地址已复制', icon: 'none' }) })
	return true
	// #endif
}

let upgradePromptVisible = false

export function checkAndroidUpgrade(policy) {
	if (!policy) return false
	const info = currentAppInfo()
	const state = evaluateAndroidVersionPolicy(info.versionCode, policy)
	if (!state.upgradeRequired) return false
	if (upgradePromptVisible) return true
	upgradePromptVisible = true
	const reason = state.current < state.minimum
		? `低于最低支持版本（${state.minimum}）`
		: `与强制要求的最新版本 ${policy.latestVersionName || ''}（${state.latest}）不一致`
	uni.showModal({
		title: '必须升级 LeanTPM',
		content: `当前版本 ${info.version}（${state.current}）${reason}，升级前无法继续使用 APP。${policy.releaseNotes || ''}`,
		showCancel: false,
		confirmText: '立即升级',
		success: async ({ confirm }) => {
			upgradePromptVisible = false
			if (!confirm) {
				setTimeout(() => checkAndroidUpgrade(policy), 0)
				return
			}
			try {
				await openUpgradeUrl(policy.downloadUrl)
			} catch (error) {
				await new Promise((resolve) => uni.showModal({
					title: '升级未完成',
					content: `${error?.message || '升级包下载或安装失败'}。请检查网络和“允许安装未知应用”权限后重试。`,
					showCancel: false,
					confirmText: '重新下载',
					complete: resolve
				}))
			} finally {
				setTimeout(() => checkAndroidUpgrade(policy), 0)
			}
		},
		fail: () => {
			upgradePromptVisible = false
			setTimeout(() => checkAndroidUpgrade(policy), 0)
		}
	})
	return true
}
