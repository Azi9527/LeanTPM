import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { Capacitor } from '@capacitor/core'
import { useAuthStore } from '@/stores/auth'
import { hasToken } from '@/utils/http'

const nativeContainer = Capacitor.isNativePlatform()

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { public: true },
  },
  {
    path: '/m/e/:token',
    name: 'PublicEquipment',
    component: () => import('@/views/mobile/PublicEquipmentView.vue'),
    meta: { public: true, title: '设备扫码信息' },
  },
  {
    path: '/mobile/setup',
    name: 'MobileServerSetup',
    component: () => import('@/views/mobile/profile/MobileServerSetupView.vue'),
    meta: { public: true, title: '配置服务地址' },
  },
  {
    path: '/',
    component: () => import('@/layouts/AppLayout.vue'),
    children: [
      { path: '', redirect: () => nativeContainer ? '/mobile/workbench' : '/dashboard' },
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/DashboardView.vue'),
        meta: { title: '工作台', permission: 'dashboard:view' },
      },
      {
        path: 'notifications/messages',
        name: 'NotificationMessages',
        component: () => import('@/views/notifications/NotificationCenterView.vue'),
        meta: { title: '我的消息', permission: 'notification:message:view' },
      },
      {
        path: 'notifications/rules',
        name: 'NotificationRules',
        component: () => import('@/views/notifications/NotificationRuleView.vue'),
        meta: { title: '提醒规则', permission: 'notification:rule:view' },
      },
      {
        path: 'notifications/deliveries',
        name: 'NotificationDeliveries',
        component: () => import('@/views/notifications/NotificationDeliveryView.vue'),
        meta: { title: '发送记录', permission: 'notification:delivery:view' },
      },
      {
        path: 'faults/reports',
        name: 'FaultReports',
        component: () => import('@/views/faults/FaultReportView.vue'),
        meta: { title: '故障报修', permission: 'fault:report:view' },
      },
      {
        path: 'faults/repairs',
        name: 'RepairOrders',
        component: () => import('@/views/faults/RepairOrderView.vue'),
        meta: { title: '维修工单', permission: 'fault:repair:view' },
      },
      {
        path: 'faults/my-repairs',
        name: 'MyRepairs',
        component: () => import('@/views/faults/RepairOrderView.vue'),
        meta: { title: '我的维修', permission: 'fault:repair:execute' },
      },
      {
        path: 'faults/statistics',
        name: 'FaultStatistics',
        component: () => import('@/views/faults/FaultStatisticsView.vue'),
        meta: { title: '故障统计', permission: 'fault:statistics:view' },
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
        path: 'master-data/organizations',
        name: 'MasterDataOrganizations',
        component: () => import('@/views/master-data/organizations/OrganizationView.vue'),
        meta: { title: '组织管理', permission: 'master-data:organization:view' },
      },
      {
        path: 'master-data/locations',
        name: 'MasterDataLocations',
        component: () => import('@/views/master-data/locations/LocationView.vue'),
        meta: { title: '位置管理', permission: 'master-data:location:view' },
      },
      {
        path: 'master-data/equipment-categories',
        name: 'MasterDataEquipmentCategories',
        component: () => import('@/views/master-data/equipment-categories/EquipmentCategoryView.vue'),
        meta: {
          title: '设备分类',
          permission: 'master-data:equipment-category:view',
        },
      },
      {
        path: 'equipment/ledger',
        name: 'EquipmentLedger',
        component: () => import('@/views/equipment/ledger/EquipmentLedgerView.vue'),
        meta: { title: '设备台账', permission: 'equipment:ledger:view' },
      },
      {
        path: 'equipment/barcodes',
        name: 'EquipmentBarcodes',
        component: () => import('@/views/equipment/barcodes/EquipmentBarcodeView.vue'),
        meta: { title: '设备条码', permission: 'equipment:barcode:view' },
      },
      {
        path: 'equipment/statuses',
        name: 'EquipmentStatuses',
        component: () => import('@/views/equipment/statuses/EquipmentStatusView.vue'),
        meta: { title: '设备状态', permission: 'equipment:status:view' },
      },
      {
        path: 'inspection/items',
        name: 'InspectionItems',
        component: () => import('@/views/inspection/items/InspectionItemView.vue'),
        meta: { title: '点检项目', permission: 'inspection:item:view' },
      },
      {
        path: 'inspection/schemes',
        name: 'InspectionSchemes',
        component: () => import('@/views/inspection/schemes/InspectionSchemeView.vue'),
        meta: { title: '点检方案', permission: 'inspection:scheme:view' },
      },
      {
        path: 'inspection/calendars',
        name: 'InspectionCalendars',
        component: () => import('@/views/inspection/calendars/InspectionCalendarView.vue'),
        meta: { title: '点检日历', permission: 'inspection:calendar:view' },
      },
      {
        path: 'inspection/plans',
        name: 'InspectionPlans',
        component: () => import('@/views/inspection/plans/InspectionPlanView.vue'),
        meta: { title: '点检计划', permission: 'inspection:plan:view' },
      },
      {
        path: 'inspection/tasks',
        name: 'InspectionTasks',
        component: () => import('@/views/inspection/tasks/InspectionTaskView.vue'),
        meta: { title: '点检任务', permission: 'inspection:task:view' },
      },
      {
        path: 'inspection/my-tasks',
        name: 'MyInspectionTasks',
        component: () => import('@/views/inspection/mobile/MyInspectionTaskView.vue'),
        meta: { title: '我的点检', permission: 'inspection:my-task:view' },
      },
      {
        path: 'inspection/abnormal',
        name: 'InspectionAbnormal',
        component: () => import('@/views/inspection/abnormal/InspectionAbnormalView.vue'),
        meta: { title: '点检异常', permission: 'inspection:abnormal:view' },
      },
      {
        path: 'inspection/statistics',
        name: 'InspectionStatistics',
        component: () => import('@/views/inspection/statistics/InspectionStatisticsView.vue'),
        meta: { title: '点检统计', permission: 'inspection:statistics:view' },
      },
      {
        path: 'maintenance/items',
        name: 'MaintenanceItems',
        component: () => import('@/views/maintenance/items/MaintenanceItemView.vue'),
        meta: { title: '维保项目', permission: 'maintenance:item:view' },
      },
      {
        path: 'maintenance/schemes',
        name: 'MaintenanceSchemes',
        component: () => import('@/views/maintenance/schemes/MaintenanceSchemeView.vue'),
        meta: { title: '维保方案', permission: 'maintenance:scheme:view' },
      },
      {
        path: 'maintenance/plans',
        name: 'MaintenancePlans',
        component: () => import('@/views/maintenance/plans/MaintenancePlanView.vue'),
        meta: { title: '维保计划', permission: 'maintenance:plan:view' },
      },
      {
        path: 'maintenance/tasks',
        name: 'MaintenanceTasks',
        component: () => import('@/views/maintenance/tasks/MaintenanceTaskView.vue'),
        meta: { title: '维保任务', permission: 'maintenance:task:view' },
      },
      {
        path: 'maintenance/my-tasks',
        name: 'MyMaintenanceTasks',
        component: () => import('@/views/maintenance/mobile/MyMaintenanceTaskView.vue'),
        meta: { title: '我的维保', permission: 'maintenance:my-task:view' },
      },
      {
        path: 'maintenance/abnormal',
        name: 'MaintenanceAbnormal',
        component: () => import('@/views/maintenance/abnormal/MaintenanceAbnormalView.vue'),
        meta: { title: '维保异常', permission: 'maintenance:abnormal:view' },
      },
      {
        path: 'maintenance/statistics',
        name: 'MaintenanceStatistics',
        component: () => import('@/views/maintenance/statistics/MaintenanceStatisticsView.vue'),
        meta: { title: '维保统计', permission: 'maintenance:statistics:view' },
      },
      {
        path: 'oee/calendar',
        name: 'OeeCalendar',
        component: () => import('@/views/oee/calendar/OeeCalendarView.vue'),
        meta: { title: '班次日历', permission: 'oee:calendar:view' },
      },
      {
        path: 'oee/loss-reasons',
        name: 'OeeLossReasons',
        component: () => import('@/views/oee/loss-reasons/OeeLossReasonView.vue'),
        meta: { title: '损失原因', permission: 'oee:loss-reason:view' },
      },
      {
        path: 'oee/targets',
        name: 'OeeTargets',
        component: () => import('@/views/oee/targets/OeeTargetView.vue'),
        meta: { title: 'OEE目标', permission: 'oee:target:view' },
      },
      {
        path: 'oee/records',
        name: 'OeeRecords',
        component: () => import('@/views/oee/records/OeeRecordView.vue'),
        meta: { title: 'OEE数据维护', permission: 'oee:record:view' },
      },
      {
        path: 'oee/production',
        name: 'OeeProduction',
        component: () => import('@/views/oee/production/OeeProductionView.vue'),
        meta: { title: '产量与停机', permission: 'oee:production:view' },
      },
      {
        path: 'oee/analysis',
        name: 'OeeAnalysis',
        component: () => import('@/views/oee/analysis/OeeAnalysisView.vue'),
        meta: { title: 'OEE分析', permission: 'oee:analysis:view' },
      },
      {
        path: 'visualization/cockpit',
        name: 'VisualizationCockpit',
        component: () => import('@/views/visualization/cockpit/OperationsCockpitView.vue'),
        meta: { title: '设备综合大屏', permission: 'visualization:cockpit:view' },
      },
      {
        path: 'visualization/three',
        name: 'VisualizationThree',
        component: () => import('@/views/visualization/three/ThreeSceneView.vue'),
        meta: { title: '三维运行大屏', permission: 'visualization:3d:view' },
      },
      {
        path: 'visualization/status',
        name: 'VisualizationStatus',
        component: () => import('@/views/visualization/topic/VisualizationTopicView.vue'),
        meta: { title: '设备状态大屏', permission: 'visualization:status:view' },
      },
      {
        path: 'visualization/inspection',
        name: 'VisualizationInspection',
        component: () => import('@/views/visualization/topic/VisualizationTopicView.vue'),
        meta: { title: '点检分析大屏', permission: 'visualization:inspection:view' },
      },
      {
        path: 'visualization/maintenance',
        name: 'VisualizationMaintenance',
        component: () => import('@/views/visualization/topic/VisualizationTopicView.vue'),
        meta: { title: '维保分析大屏', permission: 'visualization:maintenance:view' },
      },
      {
        path: 'visualization/oee',
        name: 'VisualizationOee',
        component: () => import('@/views/visualization/topic/VisualizationTopicView.vue'),
        meta: { title: 'OEE 分析大屏', permission: 'visualization:oee:view' },
      },
      {
        path: 'visualization/scenes',
        name: 'VisualizationScenes',
        component: () => import('@/views/visualization/scenes/SceneManagementView.vue'),
        meta: { title: '三维场景配置', permission: 'visualization:scene:view' },
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
    path: '/mobile',
    component: () => import('@/layouts/MobileLayout.vue'),
    meta: { permission: 'mobile:access' },
    children: [
      { path: '', redirect: '/mobile/workbench' },
      {
        path: 'workbench',
        name: 'MobileWorkbench',
        component: () => import('@/views/mobile/workbench/MobileWorkbenchView.vue'),
        meta: { title: '移动工作台', permission: 'mobile:workbench:view' },
      },
      {
        path: 'scan',
        name: 'MobileScan',
        component: () => import('@/views/mobile/scan/MobileScanView.vue'),
        meta: { title: '设备扫码', permission: 'mobile:scan' },
      },
      {
        path: 'equipment/:token',
        name: 'MobileEquipment',
        component: () => import('@/views/mobile/scan/MobileEquipmentView.vue'),
        meta: { title: '设备现场信息', permission: 'mobile:scan' },
      },
      {
        path: 'equipment-status',
        name: 'MobileEquipmentStatus',
        component: () => import('@/views/mobile/equipment/MobileEquipmentStatusView.vue'),
        meta: { title: '设备状态', permission: 'equipment:view' },
      },
      {
        path: 'tasks',
        name: 'MobileTasks',
        component: () => import('@/views/mobile/tasks/MobileTaskHubView.vue'),
        meta: { title: '现场任务', permission: 'mobile:task:view' },
      },
      {
        path: 'inspection',
        name: 'MobileInspection',
        component: () => import('@/views/inspection/mobile/MyInspectionTaskView.vue'),
        meta: { title: '移动点检', permission: 'inspection:my-task:view' },
      },
      {
        path: 'maintenance',
        name: 'MobileMaintenance',
        component: () => import('@/views/maintenance/mobile/MyMaintenanceTaskView.vue'),
        meta: { title: '移动维保', permission: 'maintenance:my-task:view' },
      },
      {
        path: 'messages',
        name: 'MobileMessages',
        component: () => import('@/views/mobile/messages/MobileMessagesView.vue'),
        meta: { title: '现场消息', permission: 'mobile:message:view' },
      },
      {
        path: 'profile',
        name: 'MobileProfile',
        component: () => import('@/views/mobile/profile/MobileProfileView.vue'),
        meta: { title: '移动设置', permission: 'mobile:profile:view' },
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
    if (to.name === 'Login' && hasToken()) {
      return nativeContainer ? '/mobile/workbench' : '/dashboard'
    }
    return true
  }
  if (!hasToken()) return { name: 'Login', query: { redirect: to.fullPath } }
  if (!auth.initialized) await auth.loadProfile()
  if (!auth.user) return { name: 'Login', query: { redirect: to.fullPath } }
  if (auth.user.mustChangePassword && to.name !== 'Login') {
    return true
  }
  if (to.path.startsWith('/mobile') && !auth.can('mobile:access')) {
    return nativeContainer ? '/login' : '/dashboard'
  }
  const permission = to.meta.permission as string | undefined
  if (permission && !auth.can(permission)) {
    return nativeContainer ? '/login' : '/dashboard'
  }
  return true
})

export default router
