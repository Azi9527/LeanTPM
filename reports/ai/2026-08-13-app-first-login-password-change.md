# APP 首次登录修改密码

## 需求

新建用户使用初始密码在 APP 登录时，不应被要求先去 PC 修改密码。APP 应在认证成功后直接进入现有的“修改初始密码”页面；修改完成前仍不得访问工作台等业务功能。

## 验收标准（Given / When / Then）

1. Given 用户 `mustChangePassword=true` 且初始密码正确，When 在 APP 登录，Then 保存后端签发的受限会话并直接进入 `/pages/login/change-password`，不请求 `/mobile/bootstrap`。
2. Given 用户 `mustChangePassword=false`，When 在 APP 登录，Then 保持现有流程：读取移动端 bootstrap、检查版本策略并进入工作台。
3. Given 首次登录用户尚未修改密码，When 请求其他业务接口，Then 后端现有 `PasswordChangeEnforcementFilter` 继续拒绝请求，仅放行个人信息、改密与退出接口。
4. Given 用户在 APP 成功修改密码，When 后端返回新令牌，Then APP 更新令牌和用户资料并进入工作台。

## 影响分析

- GitNexus `submit`（`LeanTPM-APP/pages/login/index.vue`）上游影响：LOW，仅当前登录页文件 1 个直接调用者，无已识别跨模块流程。
- GitNexus `signIn` 上游影响：LOW；本次不修改该符号。
- `/api/v1/mobile/bootstrap` API impact：LOW；本次不修改接口或响应合同。
- 后端已经在登录响应中返回 `mustChangePassword`，并通过受限令牌与强制改密过滤器保护业务接口，因此无需修改 Backend 或数据库。

## 风险等级与治理

- 流程风险：L4（涉及登录后的鉴权分流），实现爆炸半径为 LOW。
- 实现所有者：仅修改 APP 登录分流与对应测试。
- 测试阶段：先增加失败回归测试，再运行 APP 单测与构建验证。
- 复核阶段：独立检查普通登录、首次改密、接口权限边界和工作区范围；不进行提交、推送或发布。

## 测试策略

- 源码合同测试明确约束：首次改密分支必须位于 `refreshMobileBootstrap()` 之前并立即返回。
- 保留现有公开版本检查、普通用户 bootstrap 与工作台跳转断言。
- APP 构建验证模板与脚本语法。

## 实施与证据

- 失败测试：新增“首次登录用户必须在业务 bootstrap 前进入改密页”合同测试；修复前按预期失败于缺少提前分流。
- 最小修复：`LeanTPM-APP/pages/login/index.vue` 在 `signIn()` 返回 `mustChangePassword=true` 后立即 `reLaunch` 到改密页并返回；普通用户路径不变。
- `npm.cmd run verify`：通过，项目检查通过，APP 测试 61/61 通过。
- `git diff --check`：通过。
- GitNexus `detect_changes`：仅识别到 APP 登录页 `submit` 1 个变更符号，风险 LOW，无受影响执行流程。
- `npm.cmd run build:app`：未执行到编译阶段。本机 HBuilderX 编译器实际版本为 `5.23.2026080315.2520`，仓库锁定版本为 `5.15.2026070717.2941`，构建脚本按发布规则拒绝使用未锁定工具链。本次未擅自修改工具链锁。
