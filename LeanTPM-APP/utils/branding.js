import { DEFAULT_BRANDING } from '../constants/theme.js'

const HEX_COLOR = /^#[0-9a-f]{6}$/i

function cleanText(value, fallback) {
	return String(value || '').trim() || fallback
}

function cleanColor(value, fallback) {
	const color = String(value || '').trim().toLowerCase()
	return HEX_COLOR.test(color) ? color : fallback
}

export function normalizeBranding(settings = {}) {
	return {
		systemName: cleanText(settings.systemName, DEFAULT_BRANDING.systemName),
		shortName: cleanText(settings.shortName, DEFAULT_BRANDING.shortName),
		subtitle: cleanText(settings.subtitle, DEFAULT_BRANDING.subtitle),
		logoUrl: cleanText(settings.logoUrl, DEFAULT_BRANDING.logoUrl),
		primaryColor: cleanColor(settings.primaryColor, DEFAULT_BRANDING.primaryColor),
		secondaryColor: cleanColor(settings.secondaryColor, DEFAULT_BRANDING.secondaryColor),
		neutralColor: cleanColor(settings.neutralColor, DEFAULT_BRANDING.neutralColor)
	}
}

export function brandingLogoSource(value) {
	const source = String(value || '').trim()
	if (!source || source === DEFAULT_BRANDING.logoUrl || source.includes('baoshan-mining-logo')) return '/static/branding/baoshan-mining-logo.png'
	if (/^(data:image\/|https?:\/\/)/i.test(source)) return source
	return source
}
