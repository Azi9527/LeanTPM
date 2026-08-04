function promisify(call) {
	return new Promise((resolve, reject) => call({ success: resolve, fail: reject }))
}

export function chooseCameraPhoto() {
	return promisify((callbacks) => uni.chooseImage({ count: 1, sizeType: ['compressed'], sourceType: ['camera'], ...callbacks }))
		.then((result) => result.tempFilePaths?.[0])
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

export async function renderWatermark({ src, canvasId, page, lines, maxWidth = 1440, maxHeight = 1900 }) {
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
	context.setFillStyle('rgba(0, 0, 0, 0.58)')
	context.fillRect(0, height - panelHeight, width, panelHeight)
	context.setFillStyle('#ffffff')
	context.setFontSize(fontSize)
	safeLines.forEach((line, index) => context.fillText(line, padding, height - panelHeight + padding + lineHeight * (index + 0.78), width - padding * 2))
	await new Promise((resolve) => context.draw(false, () => setTimeout(resolve, 80)))
	return new Promise((resolve, reject) => uni.canvasToTempFilePath({ canvasId, width, height, destWidth: width, destHeight: height, fileType: 'jpg', quality: 0.84, success: (result) => resolve(result.tempFilePath), fail: reject }, page))
}

export function formatBusinessDateTime(value = new Date()) {
	const date = value instanceof Date ? value : new Date(value)
	if (Number.isNaN(date.getTime())) return ''
	const pad = (part) => String(part).padStart(2, '0')
	return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export function watermarkLines({ brandName, equipmentName, equipmentCode, taskCode, itemName, executorName, faultLocationText, capturedAt }) {
	const capturedTime = formatBusinessDateTime(capturedAt || new Date())
	return [
		brandName || 'LeanTPM',
		`${equipmentName || ''} (${equipmentCode || ''})`,
		`${taskCode || ''} · ${itemName || ''}`,
		`位置 ${faultLocationText || '设备现场'}`,
		`${capturedTime} · 执行人 ${executorName || '当前用户'}`
	]
}
