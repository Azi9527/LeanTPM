import type { MenuItem } from '@/types/api'

const menuTypeLabels: Record<MenuItem['menuType'], string> = {
  DIRECTORY: '目录',
  MENU: '页面菜单',
  BUTTON: '操作权限',
}

const permissionSegmentLabels: Record<string, string> = {
  system: '系统管理',
  equipment: '设备管理',
  inspection: '点检管理',
  maintenance: '维保管理',
  fault: '故障维修',
  oee: 'OEE 管理',
  visualization: '可视化中心',
  mobile: '移动作业',
  notification: '消息中心',
  'master-data': '基础数据',
  user: '用户',
  role: '角色',
  menu: '菜单权限',
  dictionary: '字典',
  attachment: '附件',
  parameter: '系统参数',
  'number-rule': '编号规则',
  'online-user': '在线用户',
  'data-scope': '数据范围',
  organization: '组织',
  location: '位置',
  category: '设备分类',
  ledger: '设备台账',
  barcode: '设备条码',
  status: '状态',
  item: '项目',
  scheme: '方案',
  plan: '计划',
  task: '任务',
  abnormal: '异常',
  statistics: '统计',
  calendar: '日历',
  report: '报表',
  record: '记录',
  target: '目标',
  loss: '损失原因',
  production: '产量',
  downtime: '停机',
  scene: '三维场景',
  model: '三维模型',
  message: '消息',
  rule: '提醒规则',
  delivery: '发送记录',
  app: 'Android APP',
  view: '查看',
  create: '新增',
  update: '编辑',
  delete: '删除',
  manage: '维护',
  import: '导入',
  export: '导出',
  upload: '上传',
  download: '下载',
  generate: '生成',
  assign: '派工',
  execute: '执行',
  review: '审核',
  verify: '验证',
  process: '处理',
  accept: '受理/验收',
  cancel: '取消',
  authorize: '授权',
  scan: '扫描',
  lock: '锁定/解锁',
  recalculate: '重新计算',
  print: '打印',
  copy: '复制',
  transfer: '转移',
  publish: '发布',
  reset: '重置',
  'reset-password': '重置密码',
}

export function menuTypeLabel(menuType: MenuItem['menuType']) {
  return menuTypeLabels[menuType] ?? menuType
}

export function permissionCodeLabel(permissionCode?: string) {
  if (!permissionCode) return '—'
  return permissionCode
    .split(':')
    .filter(Boolean)
    .map((segment) => permissionSegmentLabels[segment] ?? segment)
    .join(' / ')
}
