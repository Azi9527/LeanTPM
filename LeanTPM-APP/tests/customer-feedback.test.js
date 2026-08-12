import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const { evaluateAndroidVersionPolicy, upgradeDownloadUrl } = await import('../utils/version.js')
const { equipmentStatusErrorMessage } = await import('../utils/equipment-status.js')
const { ApiError } = await import('../utils/errors.js')

function source(relativePath) {
	return readFileSync(new URL(`../${relativePath}`, import.meta.url), 'utf8')
}

test('evaluates latest, optional, minimum-blocked and force-latest Android versions', () => {
	assert.deepEqual(
		evaluateAndroidVersionPolicy(103, {
			latestVersionCode: 103,
			minimumVersionCode: 101,
			forceUpgrade: false
		}),
		{
			current: 103,
			latest: 103,
			minimum: 101,
			updateAvailable: false,
			upgradeRequired: false,
			isLatest: true
		}
	)
	assert.equal(evaluateAndroidVersionPolicy(102, {
		latestVersionCode: 103,
		minimumVersionCode: 101,
		forceUpgrade: false
	}).upgradeRequired, false)
	assert.equal(evaluateAndroidVersionPolicy(100, {
		latestVersionCode: 103,
		minimumVersionCode: 101,
		forceUpgrade: false
	}).upgradeRequired, true)
	assert.equal(evaluateAndroidVersionPolicy(102, {
		latestVersionCode: 103,
		minimumVersionCode: 101,
		forceUpgrade: true
	}).upgradeRequired, true)
})

test('resolves the backend relative APK path against the configured API address', () => {
	assert.equal(
		upgradeDownloadUrl('/public/app/android/download', 'http://8.163.66.164/api/v1'),
		'http://8.163.66.164/api/v1/public/app/android/download'
	)
	assert.equal(
		upgradeDownloadUrl('https://download.example.com/leantpm.apk', 'http://server/api/v1'),
		'https://download.example.com/leantpm.apk'
	)
})

test('downloads and installs the Android APK instead of silently opening the URL', () => {
	const version = source('utils/version.js')
	const profile = source('pages/profile/index.vue')
	assert.match(version, /uni\.downloadFile\(/)
	assert.match(version, /plus\.runtime\.install\(/)
	assert.match(version, /uni\.showLoading\(\{[\s\S]*mask:\s*true/)
	assert.match(version, /finally\s*\{[\s\S]*checkAndroidUpgrade\(policy\)/)
	assert.match(profile, /async function downloadUpgrade\(\)[\s\S]*await openUpgradeUrl/)
})

test('inspection cards expose the equipment code returned by the task API', () => {
	const inspection = source('pages/inspection/index.vue')
	assert.match(inspection, /task\.equipmentCode/)
})

test('equipment status uses the mobile scan-scope endpoint', () => {
	const status = source('pages/equipment/status.vue')
	const mobileApi = source('api/mobile.js')
	assert.match(status, /mobileApi\.equipmentStatus/)
	assert.doesNotMatch(status, /equipmentApi\.page/)
	assert.match(mobileApi, /equipmentStatus:.*\/mobile\/equipment-status/s)
})

test('equipment status reports endpoint-specific failures instead of declaring the whole service unavailable', () => {
	assert.match(
		equipmentStatusErrorMessage(new ApiError('HTTP_404', '服务器返回状态 404', 404)),
		/Backend 尚未发布设备状态接口/
	)
	assert.match(
		equipmentStatusErrorMessage(new ApiError('FORBIDDEN', '无权执行此操作', 403)),
		/移动端设备扫码查看.*权限/
	)
	const unavailable = equipmentStatusErrorMessage(
		new ApiError('SERVICE_UNAVAILABLE', '企业服务暂时不可用', 503)
	)
	assert.match(unavailable, /设备状态专用接口/)
	assert.match(unavailable, /HTTP 503/)
	assert.match(unavailable, /其他功能正常.*手机网络和登录状态通常正常/)
	assert.doesNotMatch(unavailable, /^企业服务暂时不可用/)
})

test('login checks the Android policy before entering any business page', () => {
	const login = source('pages/login/index.vue')
	const publicCheckIndex = login.indexOf('await checkPublicAndroidUpgrade()')
	const signInIndex = login.indexOf('await signIn')
	const launchIndex = login.indexOf('await reLaunchTo', signInIndex)
	assert.ok(publicCheckIndex > 0)
	assert.ok(signInIndex > publicCheckIndex)
	assert.match(login, /onLoad\([\s\S]*await checkPublicAndroidUpgrade\(\)/)
	assert.match(source('api/mobile.js'), /androidRelease:[\s\S]*\/public\/app\/android\/latest[\s\S]*auth:\s*false/)
	assert.ok(launchIndex > signInIndex)
})

test('app launch checks the public Android policy even without a restored session', () => {
	const app = source('App.vue')
	const publicCheckIndex = app.indexOf('await checkPublicAndroidUpgrade()')
	const noSessionIndex = app.indexOf('if (!sessionState.user) return')
	assert.ok(publicCheckIndex > 0)
	assert.ok(noSessionIndex > publicCheckIndex)
})

test('the package metadata matches the Android release declared by the server', () => {
	const manifest = source('manifest.json')
	assert.match(manifest, /"versionName"\s*:\s*"1\.0\.11"/)
	assert.match(manifest, /"versionCode"\s*:\s*"?104"?/)
	assert.match(manifest, /"packagename"\s*:\s*"uni\.app\.UNICEE59D0"/)
})

test('profile distinguishes installed and latest versions and hides download when current', () => {
	const profile = source('pages/profile/index.vue')
	assert.match(profile, /当前安装版本/)
	assert.match(profile, /当前已是最新版本/)
	assert.match(profile, /v-if="versionState\.updateAvailable && versionPolicy\.downloadUrl"/)
})
