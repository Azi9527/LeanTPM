export function createIdempotencyKey(scope = 'app') {
	const normalizedScope = String(scope || 'app').replace(/[^a-z0-9_-]/gi, '-').toLowerCase()
	const randomPart = globalThis.crypto?.randomUUID
		? globalThis.crypto.randomUUID()
		: `${Date.now().toString(36)}-${Math.random().toString(16).slice(2)}`
	return `${normalizedScope}-${randomPart}`
}

function normalizePayload(value) {
	if (Array.isArray(value)) return value.map((item) => normalizePayload(item))
	if (value && typeof value === 'object') {
		return Object.fromEntries(
			Object.keys(value)
				.filter((key) => value[key] !== undefined)
				.sort()
				.map((key) => [key, normalizePayload(value[key])])
		)
	}
	if (typeof value === 'number' && !Number.isFinite(value)) return null
	return value
}

export function submissionPayloadSignature(payload) {
	return JSON.stringify(normalizePayload(payload))
}

export function bindIdempotencyKeyToPayload({
	idempotencyKey,
	payloadSignature = '',
	payload,
	legacyPendingSubmit = false,
	scope = 'app',
	createKey = createIdempotencyKey
}) {
	const nextSignature = submissionPayloadSignature(payload)
	const mustRotate = Boolean(idempotencyKey) && (
		Boolean(legacyPendingSubmit && !payloadSignature)
		|| Boolean(payloadSignature && payloadSignature !== nextSignature)
	)
	return {
		idempotencyKey: !idempotencyKey || mustRotate ? createKey(scope) : idempotencyKey,
		payloadSignature: nextSignature
	}
}
