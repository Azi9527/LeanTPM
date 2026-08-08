export interface ApiResponse<T> {
  code: string
  message: string
  data: T
  timestamp: string
}

export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  pageSize: number
}

export interface TokenPair {
  accessToken: string
  refreshToken: string
  accessExpiresAt: string
  refreshExpiresAt: string
}

export interface MenuItem {
  id: number
  parentId: number
  menuType: 'DIRECTORY' | 'MENU' | 'BUTTON'
  menuName: string
  routeName?: string
  routePath?: string
  componentPath?: string
  permissionCode?: string
  icon?: string
  visible?: number
  status?: number
  sortOrder: number
}

export interface UserProfile {
  id: number
  tenantId: number
  username: string
  realName: string
  mustChangePassword: boolean
  roles: string[]
  permissions: string[]
  menus: MenuItem[]
}

export interface LoginResponse {
  tokens: TokenPair
  user: UserProfile
}
