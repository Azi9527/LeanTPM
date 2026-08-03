import { readonly, reactive } from 'vue'
import type { ApiResponse } from '@/types/api'
import { http } from '@/utils/http'

export interface BrandingSettings {
  systemName: string
  shortName: string
  subtitle: string
  logoUrl: string
  primaryColor: string
  secondaryColor: string
  neutralColor: string
}

export const DEFAULT_BRANDING: BrandingSettings = {
  systemName: '宝山矿业设备管理系统',
  shortName: '宝山矿业',
  subtitle: '精益设备管理',
  logoUrl: '/branding/baoshan-mining-logo.png',
  primaryColor: '#1c7d50',
  secondaryColor: '#3e3a39',
  neutralColor: '#c4000a',
}

const CACHE_KEY = 'leantpm_branding_cache'
const HEX_COLOR = /^#[0-9a-f]{6}$/i
const brandingState = reactive<BrandingSettings>({ ...DEFAULT_BRANDING })

export function useBranding() {
  return readonly(brandingState)
}

export function applyBranding(settings: Partial<BrandingSettings>, persist = true): void {
  const normalized = normalizeBranding(settings)
  Object.assign(brandingState, normalized)

  const root = document.documentElement
  const primary = normalized.primaryColor
  const secondary = normalized.secondaryColor
  const accent = normalized.neutralColor
  const variables: Record<string, string> = {
    '--tpm-primary': primary,
    '--tpm-primary-strong': mix(primary, '#000000', 0.24),
    '--tpm-primary-soft': mix(primary, '#ffffff', 0.9),
    '--tpm-primary-rgb': rgbChannels(primary),
    '--tpm-secondary': secondary,
    '--tpm-secondary-strong': mix(secondary, '#000000', 0.22),
    '--tpm-secondary-soft': mix(secondary, '#ffffff', 0.88),
    '--tpm-secondary-rgb': rgbChannels(secondary),
    '--tpm-accent': accent,
    '--tpm-success': primary,
    '--tpm-danger': accent,
    '--tpm-text': secondary,
    '--tpm-sidebar': mix(secondary, '#000000', 0.25),
    '--tpm-neutral-rgb': rgbChannels(secondary),
    '--el-color-primary': primary,
    '--el-color-primary-dark-2': mix(primary, '#000000', 0.2),
    '--el-color-success': primary,
    '--el-color-danger': accent,
  }

  for (const level of [3, 5, 7, 8, 9]) {
    variables[`--el-color-primary-light-${level}`] = mix(primary, '#ffffff', level / 10)
    variables[`--el-color-success-light-${level}`] = mix(primary, '#ffffff', level / 10)
    variables[`--el-color-danger-light-${level}`] = mix(accent, '#ffffff', level / 10)
  }
  Object.entries(variables).forEach(([key, value]) => root.style.setProperty(key, value))
  document.title = normalized.systemName

  if (persist) {
    try {
      localStorage.setItem(CACHE_KEY, JSON.stringify(normalized))
    } catch {
      // Native private mode can reject localStorage; current-session branding still works.
    }
  }
}

export async function initializeBranding(): Promise<void> {
  try {
    const cached = localStorage.getItem(CACHE_KEY)
    if (cached) applyBranding(JSON.parse(cached) as Partial<BrandingSettings>, false)
    else applyBranding(DEFAULT_BRANDING, false)
  } catch {
    applyBranding(DEFAULT_BRANDING, false)
  }

  try {
    const response = await http.get<ApiResponse<BrandingSettings>>('/public/branding', {
      timeout: 5000,
    })
    applyBranding(response.data.data)
  } catch {
    // Keep the bundled/cached branding when the enterprise server is offline.
  }
}

function normalizeBranding(settings: Partial<BrandingSettings>): BrandingSettings {
  return {
    systemName: cleanText(settings.systemName, DEFAULT_BRANDING.systemName),
    shortName: cleanText(settings.shortName, DEFAULT_BRANDING.shortName),
    subtitle: cleanText(settings.subtitle, DEFAULT_BRANDING.subtitle),
    logoUrl: cleanText(settings.logoUrl, DEFAULT_BRANDING.logoUrl),
    primaryColor: cleanColor(settings.primaryColor, DEFAULT_BRANDING.primaryColor),
    secondaryColor: cleanColor(settings.secondaryColor, DEFAULT_BRANDING.secondaryColor),
    neutralColor: cleanColor(settings.neutralColor, DEFAULT_BRANDING.neutralColor),
  }
}

function cleanText(value: string | undefined, fallback: string): string {
  return value?.trim() || fallback
}

function cleanColor(value: string | undefined, fallback: string): string {
  const color = value?.trim().toLowerCase()
  return color && HEX_COLOR.test(color) ? color : fallback
}

function rgbChannels(color: string): string {
  const [red, green, blue] = hexToRgb(color)
  return `${red}, ${green}, ${blue}`
}

function mix(first: string, second: string, secondWeight: number): string {
  const left = hexToRgb(first)
  const right = hexToRgb(second)
  const channel = (index: number) => Math.round(left[index] * (1 - secondWeight) + right[index] * secondWeight)
  return `#${[channel(0), channel(1), channel(2)]
    .map((value) => value.toString(16).padStart(2, '0'))
    .join('')}`
}

function hexToRgb(color: string): [number, number, number] {
  return [
    Number.parseInt(color.slice(1, 3), 16),
    Number.parseInt(color.slice(3, 5), 16),
    Number.parseInt(color.slice(5, 7), 16),
  ]
}
