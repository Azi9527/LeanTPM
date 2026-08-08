function promisify(call) {
	return new Promise((resolve, reject) => call({ success: resolve, fail: reject }))
}

export function choosePhotos(sourceType = ['camera', 'album'], count = 1) {
	const sources = Array.isArray(sourceType) && sourceType.length ? sourceType : ['camera', 'album']
	const selectionCount = Math.min(9, Math.max(1, Number(count) || 1))
	return promisify((callbacks) => uni.chooseImage({ count: selectionCount, sizeType: ['compressed'], sourceType: sources, ...callbacks }))
		.then((result) => Array.isArray(result.tempFilePaths) ? result.tempFilePaths.slice(0, selectionCount) : [])
}

export function choosePhoto(sourceType = ['camera', 'album']) {
	return choosePhotos(sourceType, 1).then((paths) => paths[0])
}

export function chooseCameraPhoto() {
	return choosePhoto(['camera'])
}

export function chooseAlbumPhoto() {
	return choosePhoto(['album'])
}

export function imageInfo(src) {
	return promisify((callbacks) => uni.getImageInfo({ src, ...callbacks }))
}

export function persistTempFile(tempFilePath) {
	return new Promise((resolve) => {
		uni.saveFile({ filePath: tempFilePath, success: (result) => resolve(result.savedFilePath), fail: () => resolve(tempFilePath) })
	})
}

export function removeSavedFile(filePath) {
	if (!filePath) return
	try { uni.removeSavedFile({ filePath, fail: () => {} }) } catch { /* no-op on H5 */ }
}

export async function renderWatermark({
	src, canvasId, page, lines, maxWidth = 1440, maxHeight = 1900,
	position = 'BOTTOM', backgroundOpacity = 74, fontColor = '#ffffff', backgroundColor = '#031922'
}) {
	const info = await imageInfo(src)
	const scale = Math.min(1, maxWidth / Number(info.width || maxWidth), maxHeight / Number(info.height || maxHeight))
	const width = Math.max(320, Math.round(Number(info.width || maxWidth) * scale))
	const height = Math.max(320, Math.round(Number(info.height || maxWidth) * scale))
	const context = uni.createCanvasContext(canvasId, page)
	context.drawImage(src, 0, 0, width, height)
	const fontSize = Math.max(18, Math.round(width / 42))
	const padding = Math.round(fontSize * 0.8)
	const lineHeight = Math.round(fontSize * 1.45)
	const safeLines = lines.map((line) => String(line || '').slice(0, 80)).filter(Boolean).slice(0, 5)
	const panelHeight = safeLines.length * lineHeight + padding * 2
	const panelTop = String(position).toUpperCase() === 'TOP' ? 0 : height - panelHeight
	context.setFillStyle(colorWithOpacity(backgroundColor, backgroundOpacity))
	context.fillRect(0, panelTop, width, panelHeight)
	context.setFillStyle(validHexColor(fontColor, '#ffffff'))
	context.setFontSize(fontSize)
	safeLines.forEach((line, index) => context.fillText(line, padding, panelTop + padding + lineHeight * (index + 0.78), width - padding * 2))
	await new Promise((resolve) => context.draw(false, () => setTimeout(resolve, 80)))
	return new Promise((resolve, reject) => uni.canvasToTempFilePath({ canvasId, width, height, destWidth: width, destHeight: height, fileType: 'jpg', quality: 0.84, success: (result) => resolve(result.tempFilePath), fail: reject }, page))
}

function validHexColor(value, fallback) {
	return /^#[0-9a-f]{6}$/i.test(String(value || '')) ? String(value) : fallback
}

function colorWithOpacity(value, opacity) {
	const hex = validHexColor(value, '#031922').slice(1)
	const red = parseInt(hex.slice(0, 2), 16)
	const green = parseInt(hex.slice(2, 4), 16)
	const blue = parseInt(hex.slice(4, 6), 16)
	const alpha = Math.min(100, Math.max(0, Number(opacity) || 0)) / 100
	return `rgba(${red}, ${green}, ${blue}, ${alpha})`
}

export function formatBusinessDateTime(value = new Date()) {
	const date = value instanceof Date ? value : new Date(value)
	if (Number.isNaN(date.getTime())) return ''
	const pad = (part) => String(part).padStart(2, '0')
	return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export const DEFAULT_PHOTO_POLICY = Object.freeze({
	watermarkEnabled: true,
	saveOriginal: true,
	saveWatermarked: true,
	template: '{brand}\n{equipmentName} ({equipmentCode})\n{taskCode} · {itemName}\n位置/部位 {location}\n{capturedAt} · 执行人 {executor}',
	position: 'BOTTOM',
	backgroundOpacity: 74,
	fontColor: '#ffffff',
	backgroundColor: '#031922'
})

export function normalizePhotoPolicy(policy = {}) {
	const normalized = { ...DEFAULT_PHOTO_POLICY, ...(policy || {}) }
	normalized.watermarkEnabled = normalized.watermarkEnabled !== false
	normalized.saveOriginal = normalized.saveOriginal !== false
	normalized.saveWatermarked = normalized.watermarkEnabled && normalized.saveWatermarked !== false
	if (!normalized.saveOriginal && !normalized.saveWatermarked) normalized.saveOriginal = true
	normalized.position = String(normalized.position).toUpperCase() === 'TOP' ? 'TOP' : 'BOTTOM'
	normalized.backgroundOpacity = Math.min(100, Math.max(0, Number(normalized.backgroundOpacity) || 0))
	normalized.fontColor = validHexColor(normalized.fontColor, DEFAULT_PHOTO_POLICY.fontColor)
	normalized.backgroundColor = validHexColor(normalized.backgroundColor, DEFAULT_PHOTO_POLICY.backgroundColor)
	normalized.template = String(normalized.template || DEFAULT_PHOTO_POLICY.template)
	return normalized
}

export function watermarkLines({ brandName, equipmentName, equipmentCode, taskCode, itemName, executorName, faultLocationText, capturedAt }, policy = {}) {
	const capturedTime = formatBusinessDateTime(capturedAt || new Date())
	const values = {
		brand: brandName || 'LeanTPM',
		equipmentName: equipmentName || '',
		equipmentCode: equipmentCode || '',
		taskCode: taskCode || '',
		itemName: itemName || '',
		capturedAt: capturedTime,
		executor: executorName || '当前用户',
		location: faultLocationText || '设备现场'
	}
	return normalizePhotoPolicy(policy).template
		.split(/\r?\n/)
		.map((line) => line.replace(/\{(brand|equipmentName|equipmentCode|taskCode|itemName|capturedAt|executor|location)\}/g, (_, key) => values[key]))
		.map((line) => line.trim())
		.filter(Boolean)
		.slice(0, 5)
}
