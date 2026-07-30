import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { hasToken } from '@/utils/http'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { public: true },
  },
  {
    path: '/',
    component: () => import('@/layouts/AppLayout.vue'),
    children: [
      { path: '', redirect: '/dashboard' },
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/DashboardView.vue'),
        meta: { title: '工作台', permission: 'dashboard:view' },
      },
      {
        path: 'system/users',
        name: 'SystemUsers',
        component: () => import('@/views/system/users/UserListView.vue'),
        meta: { title: '用户管理', permission: 'system:user:view' },
      },
      {
        path: 'system/roles',
        name: 'SystemRoles',
        component: () => import('@/views/system/roles/RoleListView.vue'),
        meta: { title: '角色管理', permission: 'system:role:view' },
      },
      {
        path: 'system/menus',
        name: 'SystemMenus',
        component: () => import('@/views/system/menus/MenuListView.vue'),
        meta: { title: '菜单权限', permission: 'system:menu:view' },
      },
      {
        path: 'system/dictionaries',
        name: 'SystemDictionaries',
        component: () => import('@/views/system/dictionaries/DictionaryView.vue'),
        meta: { title: '字典管理', permission: 'system:dictionary:view' },
      },
      {
        path: 'system/attachments',
        name: 'SystemAttachments',
        component: () => import('@/views/system/attachments/AttachmentView.vue'),
        meta: { title: '附件管理', permission: 'system:attachment:view' },
      },
      {
        path: 'system/login-logs',
        name: 'SystemLoginLogs',
        component: () => import('@/views/system/logs/LoginLogView.vue'),
        meta: { title: '登录日志', permission: 'system:login-log:view' },
      },
      {
        path: 'system/operation-logs',
        name: 'SystemOperationLogs',
        component: () => import('@/views/system/logs/OperationLogView.vue'),
        meta: { title: '操作日志', permission: 'system:operation-log:view' },
      },
      {
        path: 'system/parameters',
        name: 'SystemParameters',
        component: () => import('@/views/system/parameters/ParameterView.vue'),
        meta: { title: '系统参数', permission: 'system:parameter:view' },
      },
      {
        path: 'system/number-rules',
        name: 'SystemNumberRules',
        component: () => import('@/views/system/number-rules/NumberRuleView.vue'),
        meta: { title: '编号规则', permission: 'system:number-rule:view' },
      },
      {
        path: 'system/online-users',
        name: 'SystemOnlineUsers',
        component: () => import('@/views/system/online-users/OnlineUserView.vue'),
        meta: { title: '在线用户', permission: 'system:online-user:view' },
      },
      {
        path: 'system/data-scopes',
        name: 'SystemDataScopes',
        component: () => import('@/views/system/data-scopes/DataScopeView.vue'),
        meta: { title: '数据权限', permission: 'system:data-scope:view' },
      },
      {
        path: 'system/change-logs',
        name: 'SystemChangeLogs',
        component: () => import('@/views/system/logs/ChangeLogView.vue'),
        meta: { title: '数据变更日志', permission: 'system:change-log:view' },
      },
      {
        path: 'coming-soon/:module',
        name: 'ComingSoon',
        component: () => import('@/views/common/ComingSoonView.vue'),
        meta: { title: '模块建设中' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    component: () => import('@/views/common/NotFoundView.vue'),
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.public) {
    if (to.name === 'Login' && hasToken()) return '/dashboard'
    return true
  }
  if (!hasToken()) return { name: 'Login', query: { redirect: to.fullPath } }
  if (!auth.initialized) await auth.loadProfile()
  if (!auth.user) return { name: 'Login', query: { redirect: to.fullPath } }
  if (auth.user.mustChangePassword && to.name !== 'Login') {
    return true
  }
  const permission = to.meta.permission as string | undefined
  if (permission && !auth.can(permission)) return '/dashboard'
  return true
})

export default router
