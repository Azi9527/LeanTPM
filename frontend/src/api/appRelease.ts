import { http, serverBaseUrl } from '@/utils/http'
import type { ApiResponse } from '@/types/api'

export interface AndroidAppRelease {
  available: boolean
  enabled: boolean
  versionName?: string
  versionCode?: number
  minimumVersionCode?: number
  fileName?: string
  fileSize?: number
  sha256?: string
  releaseNotes?: string
  publishedTime?: string
  downloadUrl?: string
  qrCodeUrl?: string
}

async function getData<T>(url: string): Promise<T> {
  const response = await http.get<ApiResponse<T>>(url)
  return response.data.data
}

export function appReleaseAssetUrl(path?: string): string {
  if (!path) return ''
  if (/^https?:\/\//i.test(path)) return path
  return `${serverBaseUrl().replace(/\/+$/, '')}/${path.replace(/^\/+/, '')}`
}

export function appReleaseQrCodeUrl(path?: string): string {
  const assetUrl = appReleaseAssetUrl(path)
  if (!assetUrl || typeof window === 'undefined' || !window.location.origin) return assetUrl
  const separator = assetUrl.includes('?') ? '&' : '?'
  return `${assetUrl}${separator}origin=${encodeURIComponent(window.location.origin)}`
}

export const appReleaseApi = {
  publicCurrent: () => getData<AndroidAppRelease>('/public/app/android/latest'),
  current: () => getData<AndroidAppRelease>('/system/app-releases/android'),
  upload: async (formData: FormData): Promise<AndroidAppRelease> => {
    const response = await http.post<ApiResponse<AndroidAppRelease>>(
      '/system/app-releases/android',
      formData,
      { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 180_000 },
    )
    return response.data.data
  },
  updateEnabled: async (enabled: boolean): Promise<AndroidAppRelease> => {
    const response = await http.patch<ApiResponse<AndroidAppRelease>>(
      '/system/app-releases/android/enabled',
      undefined,
      { params: { enabled } },
    )
    return response.data.data
  },
}
