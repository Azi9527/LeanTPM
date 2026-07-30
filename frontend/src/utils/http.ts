import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import type { ApiResponse, TokenPair } from '@/types/api'

const ACCESS_TOKEN_KEY = 'leantpm_access_token'
const REFRESH_TOKEN_KEY = 'leantpm_refresh_token'
const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

export const http = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
})

function token(): string | null {
  return localStorage.getItem(ACCESS_TOKEN_KEY)
}

export function storeTokens(tokens: TokenPair): void {
  localStorage.setItem(ACCESS_TOKEN_KEY, tokens.accessToken)
  localStorage.setItem(REFRESH_TOKEN_KEY, tokens.refreshToken)
}

export function clearTokens(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
}

export function hasToken(): boolean {
  return Boolean(token())
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
    const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY)
    const isRefreshRequest = original?.url?.includes('/auth/refresh')
    if (error.response?.status === 401 && original && !original._retried && refreshToken && !isRefreshRequest) {
      original._retried = true
      try {
        refreshing ??= axios
          .post<ApiResponse<TokenPair>>('/api/v1/auth/refresh', { refreshToken })
          .then((response) => response.data.data)
          .finally(() => {
            refreshing = null
          })
        const tokens = await refreshing
        storeTokens(tokens)
        original.headers.Authorization = `Bearer ${tokens.accessToken}`
        return http(original)
      } catch {
        clearTokens()
        window.location.assign('/login')
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
