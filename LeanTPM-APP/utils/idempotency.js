export function createIdempotencyKey(scope = 'app') {
	const normalizedScope = String(scope || 'app').replace(/[^a-z0-9_-]/gi, '-').toLowerCase()
	const randomPart = globalThis.crypto?.randomUUID
		? globalThis.crypto.randomUUID()
		: `${Date.now().toString(36)}-${Math.random().toString(16).slice(2)}`
	return `${normalizedScope}-${randomPart}`
}
