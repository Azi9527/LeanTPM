const TOKEN_PATTERN = /^[a-fA-F0-9]{64}$/

export function extractEquipmentToken(value) {
	const normalized = String(value || '').trim()
	if (TOKEN_PATTERN.test(normalized)) return normalized.toLowerCase()
	const match = normalized.match(/\/m\/e\/([a-fA-F0-9]{64})(?:[/?#]|$)/)
	return match?.[1]?.toLowerCase() || null
}

export function requireEquipmentToken(value) {
	const token = extractEquipmentToken(value)
	if (!token) throw new Error('未识别到有效的 LeanTPM 设备二维码')
	return token
}
