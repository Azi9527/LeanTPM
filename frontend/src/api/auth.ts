import { http } from '@/utils/http'
import type { ApiResponse, LoginResponse, TokenPair, UserProfile } from '@/types/api'

export async function login(username: string, password: string): Promise<LoginResponse> {
  const response = await http.post<ApiResponse<LoginResponse>>('/auth/login', { username, password })
  return response.data.data
}

export async function currentUser(): Promise<UserProfile> {
  const response = await http.get<ApiResponse<UserProfile>>('/auth/me')
  return response.data.data
}

export async function changePassword(currentPassword: string, newPassword: string): Promise<TokenPair> {
  const response = await http.put<ApiResponse<TokenPair>>('/auth/password', {
    currentPassword,
    newPassword,
  })
  return response.data.data
}

export async function logout(): Promise<void> {
  await http.post('/auth/logout')
}
