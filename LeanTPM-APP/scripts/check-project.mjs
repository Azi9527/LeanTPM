import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const pages = JSON.parse(fs.readFileSync(path.join(root, 'pages.json'), 'utf8'))
assert.ok(Array.isArray(pages.pages) && pages.pages.length > 0, 'pages.json must register pages')
for (const page of pages.pages) {
	assert.ok(fs.existsSync(path.join(root, `${page.path}.vue`)), `Missing page: ${page.path}.vue`)
}

const manifestSource = fs.readFileSync(path.join(root, 'manifest.json'), 'utf8')
const withoutComments = manifestSource.replace(/\/\*[\s\S]*?\*\//g, '')
const manifest = JSON.parse(withoutComments)
const android = manifest['app-plus']?.distribute?.android
assert.equal(
	manifest['mp-weixin']?.lazyCodeLoading,
	'requiredComponents',
	'WeChat mini program must enable component lazy loading'
)
assert.equal(android?.packagename, 'uni.app.UNICEE59D0')
assert.equal(android?.minSdkVersion, 29)
assert.equal(android?.targetSdkVersion, 36)
assert.equal(android?.usesCleartextTraffic, true, 'Android APP must allow the approved HTTP deployment endpoint')
assert.ok(manifest['app-plus']?.distribute?.icons?.android?.xxxhdpi, 'Android icon configuration is required')

const setupPage = fs.readFileSync(path.join(root, 'pages/setup/index.vue'), 'utf8')
assert.match(setupPage, /当前正式云服务可使用 HTTP/, 'Server setup copy must match the approved HTTP deployment mode')

const forbiddenPermissions = [
	'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION', 'READ_PHONE_STATE',
	'GET_ACCOUNTS', 'READ_CONTACTS', 'WRITE_CONTACTS', 'READ_SMS', 'SEND_SMS',
	'READ_LOGS', 'WRITE_SETTINGS', 'READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE'
]
const permissions = JSON.stringify(android.permissions || [])
for (const permission of forbiddenPermissions) {
	assert.ok(!permissions.includes(permission), `Forbidden Android permission: ${permission}`)
}

const mediaExtensions = new Set(['.png', '.jpg', '.jpeg', '.gif', '.webp', '.svg', '.mp3', '.wav', '.aac', '.m4a'])
const staticRoot = path.join(root, 'static')
const oversizedMedia = []
function collectOversizedMedia(directory) {
	for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
		const fullPath = path.join(directory, entry.name)
		if (entry.isDirectory()) collectOversizedMedia(fullPath)
		else if (mediaExtensions.has(path.extname(entry.name).toLowerCase()) && fs.statSync(fullPath).size > 200 * 1024) {
			oversizedMedia.push(path.relative(root, fullPath))
		}
	}
}
collectOversizedMedia(staticRoot)
assert.deepEqual(oversizedMedia, [], `Static media exceeds WeChat's 200 KB recommendation: ${oversizedMedia.join(', ')}`)

const sourceFiles = []
function collect(directory) {
	for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
		const fullPath = path.join(directory, entry.name)
		if (entry.isDirectory()) collect(fullPath)
		else if (/\.(js|vue)$/.test(entry.name)) sourceFiles.push(fullPath)
	}
}
for (const directory of ['api', 'components', 'constants', 'pages', 'platform', 'services', 'stores', 'utils']) {
	const target = path.join(root, directory)
	if (fs.existsSync(target)) collect(target)
}
const source = sourceFiles.map((file) => fs.readFileSync(file, 'utf8')).join('\n')
for (const forbidden of ['@capacitor/', 'element-plus', 'vue-router', 'navigator.geolocation']) {
	assert.ok(!source.includes(forbidden), `Forbidden mobile dependency: ${forbidden}`)
}

console.log(`LeanTPM-APP check passed: ${pages.pages.length} pages, ${sourceFiles.length} source files`)
