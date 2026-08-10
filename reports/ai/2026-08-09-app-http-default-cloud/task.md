# LeanTPM APP HTTP 与正式云服务默认项

## 基本信息

- 任务类型：移动端配置修改
- 任务目标：Android APP 允许 HTTP；新增并默认选择“正式版云服务” `http://8.163.66.164/`
- 工作目录：`LeanTPM-APP`
- 可信基线：`2185536ea9da0a323b27f53dcf849b818ea19069`（detached HEAD，保留既有未提交改动）
- 范围：`manifest.json`、`utils/server.js`、配置页说明、对应现有测试
- 非目标：后端、数据库、Caddy、阿里云安全组、APK 签名与部署

## 需求理解

| 类型 | 当前理解 |
|---|---|
| 真实问题 | 当前 APP 未明确允许 Android 明文 HTTP，且正式服务器不是默认选项 |
| 明确需求 | 允许 HTTP；增加正式云 IP 预设并默认选择 |
| 涉及对象 | Android APP 首次配置与打包清单 |
| 涉及模块 | `LeanTPM-APP` |
| 可能歧义 | 用户给出的 URL 带尾部 `/`，内部标准形式会去掉尾斜杠并自动补 `/api/v1` |
| 必须确认 | 无；现有规范化逻辑已定义该行为 |
| 默认假设 | “默认选择”指无已保存地址时显示并选中正式云服务；已有用户地址不覆盖 |
| 假设影响 | 保留既有安装的企业地址，避免升级后静默改址 |

## Given / When / Then

```text
Given 新安装或没有已保存服务地址的 APP
When 打开企业服务配置页
Then 默认显示“正式版云服务”且地址为 http://8.163.66.164
And 保存时规范化为 http://8.163.66.164/api/v1

Given Android 生产构建配置
When HBuilderX 生成 Android 清单
Then manifest 配置明确允许明文 HTTP

Given 已保存手动地址或原测试预设
When 用户升级或再次打开配置页
Then 已保存地址优先，手动输入、测试云和测试内网保持兼容
```

## 影响与风险

- GitNexus：索引与当前提交一致；`DEFAULT_SERVER_URL`、`SERVER_PRESETS` 上游影响均为 LOW、0 个索引执行流
- 直接修改：默认 URL、预设列表、Android 清单配置
- 间接消费者：启动页、配置页、基础合同测试、Android 打包
- 数据/权限：不写后端或数据库；允许明文传输会降低网络机密性与完整性
- 风险等级：L2（移动端网络行为和默认连接状态）
- 回滚：删除正式预设/恢复旧默认值，并将 `usesCleartextTraffic` 恢复为 `false` 或移除

## 多角色与文件所有权

| 角色 | 任务 | 文件/模块所有权 | 只读 | 可修改测试 |
|---|---|---|---|---|
| 主代理/移动端实现 | 最小实现与集成 | `manifest.json`、`utils/server.js`、`pages/setup/index.vue` | 否 | 否 |
| 测试阶段 | 黑盒合同与模块回归 | `tests/foundation.test.js`、`scripts/check-project.mjs` | 否 | 是 |
| 审查阶段 | 检查 diff、风险和回滚边界 | 本任务最终 diff | 是 | 否 |

## 测试矩阵

| 场景 | 层级 | 预期结果 | 命令 |
|---|---|---|---|
| 默认正式云 | 单元 | 默认 URL 与首个预设一致 | `npm test` |
| HTTP 地址规范化 | 单元 | 自动生成 `/api/v1` | `npm test` |
| Android 清单 | 静态合同 | `usesCleartextTraffic=true` | `npm run check` |
| 旧预设/手动输入 | 回归 | 原有地址仍受支持 | `npm test` |
| 模块回归 | 模块 | APP 检查与全部测试通过 | `npm run verify` |

## 授权

- 允许：本地源代码与测试修改
- 不允许：数据库写入、云端部署、提交、Push、PR、外部系统变更

## 最终记录

- 实际修改：Android 明文 HTTP 开关；正式云服务默认项；配置页说明；默认/兼容/清单合同测试
- 验收结果：源代码合同完成；新安装空存储默认正式云，已有保存地址保持优先
- 测试：`npm run verify` PASS（26/26）；`git diff --check` PASS
- 构建：`npm run build:app` 在生成前被既有 toolchain-lock 门禁阻止，原因是 HBuilderX compiler digest 未锁定
- 独立审查：无 P0；确认默认首项与测试真实性；全局 HTTP 明文风险为明确接受的临时 P1
- 清理：未创建测试数据库、服务、进程或外部资源；无本任务构建产物
- 未解决风险：必须在正式域名和公共证书上线后恢复 HTTPS 并关闭全局明文流量；最终 APK 清单尚未生成验证
- Git/发布动作：未暂存、未提交、未 Push、未创建 PR、未部署
