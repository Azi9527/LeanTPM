import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import type { ApiResponse, TokenPair } from '@/types/api'
import { nativeContainer, vaultGet, vaultRemove, vaultSet } from '@/mobile/secureVault'

const ACCESS_TOKEN_KEY = 'leantpm_access_token'
const REFRESH_TOKEN_KEY = 'leantpm_refresh_token'
const API_BASE_URL_KEY = 'leantpm_api_base_url'
const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])
const WEB_API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api/v1'
const NATIVE_API_BASE_URL = import.meta.env.VITE_MOBILE_API_BASE_URL
  || 'http://10.0.2.2:8080/api/v1'

let accessTokenCache: string | null = null
let refreshTokenCache: string | null = null
let apiBaseUrlCache = nativeContainer ? NATIVE_API_BASE_URL : WEB_API_BASE_URL

export const http = axios.create({
  baseURL: apiBaseUrlCache,
  timeout: 15000,
})

function token(): string | null {
  return accessTokenCache
}

export function accessToken(): string | null {
  return token()
}

export async function initializeHttpStorage(): Promise<void> {
  accessTokenCache = await vaultGet(ACCESS_TOKEN_KEY)
  refreshTokenCache = await vaultGet(REFRESH_TOKEN_KEY)
  // The configurable server address belongs to the installed native app only.
  // Reusing it in the browser makes the PC client silently send requests to an
  // old LAN/tunnel address instead of the Vite/Nginx same-origin `/api/v1`.
  if (!nativeContainer) {
    apiBaseUrlCache = WEB_API_BASE_URL
    http.defaults.baseURL = WEB_API_BASE_URL
    return
  }
  const configuredBaseUrl = await vaultGet(API_BASE_URL_KEY)
  if (configuredBaseUrl) apiBaseUrlCache = normalizeApiBaseUrl(configuredBaseUrl)
  http.defaults.baseURL = apiBaseUrlCache
}

export async function storeTokens(tokens: TokenPair): Promise<void> {
  accessTokenCache = tokens.accessToken
  refreshTokenCache = tokens.refreshToken
  await Promise.all([
    vaultSet(ACCESS_TOKEN_KEY, tokens.accessToken),
    vaultSet(REFRESH_TOKEN_KEY, tokens.refreshToken),
  ])
}

export async function clearTokens(): Promise<void> {
  accessTokenCache = null
  refreshTokenCache = null
  await Promise.all([
    vaultRemove(ACCESS_TOKEN_KEY),
    vaultRemove(REFRESH_TOKEN_KEY),
  ])
}

export function hasToken(): boolean {
  return Boolean(token())
}

export function isAuthenticationFailure(error: unknown): boolean {
  if (!axios.isAxiosError<ApiResponse<unknown>>(error)) return false
  const status = error.response?.status
  return status === 401 || status === 403
}

export function serverBaseUrl(): string {
  return apiBaseUrlCache
}

export async function setServerBaseUrl(value: string): Promise<string> {
  const normalized = normalizeApiBaseUrl(value)
  apiBaseUrlCache = normalized
  http.defaults.baseURL = normalized
  await vaultSet(API_BASE_URL_KEY, normalized)
  return normalized
}

function normalizeApiBaseUrl(value: string): string {
  const trimmed = value.trim().replace(/\/+$/, '')
  if (trimmed.startsWith('/')) return trimmed.endsWith('/api/v1')
    ? trimmed
    : `${trimmed}/api/v1`
  const parsed = new URL(trimmed)
  if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password) {
    throw new Error('服务地址必须是有效的 HTTP(S) 地址，且不能包含账号密码')
  }
  parsed.hash = ''
  parsed.search = ''
  const clean = parsed.toString().replace(/\/+$/, '')
  return clean.endsWith('/api/v1') ? clean : `${clean}/api/v1`
}

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const accessToken = token()
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`
  }
  const method = config.method?.toUpperCase()
  if (
    method
    && MUTATING_METHODS.has(method)
    && !config.url?.startsWith('/auth/')
    && !config.headers.get('Idempotency-Key')
  ) {
    const randomPart = typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(16).slice(2)}`
    config.headers.set('Idempotency-Key', `web-${randomPart}`)
  }
  return config
})

let refreshing: Promise<TokenPair> | null = null

http.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<ApiResponse<unknown>>) => {
    const original = error.config as (InternalAxiosRequestConfig & { _retried?: boolean }) | undefined
    const refreshToken = refreshTokenCache
    const isRefreshRequest = original?.url?.includes('/auth/refresh')
    if (error.response?.status === 401 && original && !original._retried && refreshToken && !isRefreshRequest) {
      original._retried = true
      try {
        refreshing ??= axios
          .post<ApiResponse<TokenPair>>(
            `${apiBaseUrlCache}/auth/refresh`,
            { refreshToken },
          )
          .then(async (response) => {
            const tokens = response.data.data
            await storeTokens(tokens)
            return tokens
          })
          .finally(() => {
            refreshing = null
          })
        const tokens = await refreshing
        original.headers.Authorization = `Bearer ${tokens.accessToken}`
        return http(original)
      } catch (refreshError) {
        const refreshAuthenticationFailed = axios.isAxiosError(refreshError)
          && refreshError.response?.status === 401
        if (refreshAuthenticationFailed) {
          await clearTokens()
          window.location.assign('/login')
        }
        throw refreshError
      }
    }
    return Promise.reject(error)
  },
)

export function errorMessage(error: unknown, fallback = '操作失败，请稍后重试'): string {
  if (axios.isAxiosError<ApiResponse<unknown>>(error)) {
    return error.response?.data?.message || fallback
  }
  return error instanceof Error ? error.message : fallback
}
