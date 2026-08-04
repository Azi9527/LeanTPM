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
assert.equal(android?.packagename, 'com.leantpm.mobile.uniapp.dev')
assert.equal(android?.minSdkVersion, 29)
assert.equal(android?.targetSdkVersion, 36)

const forbiddenPermissions = [
	'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION', 'READ_PHONE_STATE',
	'GET_ACCOUNTS', 'READ_CONTACTS', 'WRITE_CONTACTS', 'READ_SMS', 'SEND_SMS',
	'READ_LOGS', 'WRITE_SETTINGS', 'READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE'
]
const permissions = JSON.stringify(android.permissions || [])
for (const permission of forbiddenPermissions) {
	assert.ok(!permissions.includes(permission), `Forbidden Android permission: ${permission}`)
}

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
