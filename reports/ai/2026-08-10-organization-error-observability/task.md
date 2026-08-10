# 组织编码软删除冲突与错误审计修复

## 基本信息

- 任务类型：修复
- 任务目标：软删除组织编码再次创建时返回明确业务错误；操作记录保存安全错误码和可关联的错误编号。
- 工作目录：`C:\Users\Mayn\.codex\worktrees\fca1\LeanTPM`
- 可信基线：`2185536ea9da0a323b27f53dcf849b818ea19069`（detached HEAD）
- 范围：`backend` 的组织编码校验、全局异常上下文、操作日志过滤器及最窄测试。
- 非目标：不恢复或删除生产数据；不修改数据库结构；不连接、部署或启停阿里云服务；不处理其他既有未提交改动。

## 需求理解

| 类型 | 当前理解 |
|---|---|
| 真实问题 | `organization` 唯一键覆盖软删除记录，但应用预检只查询 `deleted=0`，重复编码下沉为未处理的数据库异常；异常被 `@RestControllerAdvice` 消费后，过滤器只记录 HTTP 失败而没有错误。 |
| 明确需求 | 校验与唯一键保持一致；后台操作记录可用安全错误码和错误编号定位后端堆栈。 |
| 涉及对象 | 组织新增、业务异常、未处理异常、操作日志。 |
| 涉及模块 | MasterData、Common Exception、System Operation Log。 |
| 可能歧义 | 是否允许重新使用软删除编码。 |
| 必须确认 | 无；数据库唯一键已经明确禁止复用，本修复按该既有合同 fail closed。 |
| 默认假设 | 历史编码不可直接复用；需恢复旧记录或使用新编码。 |
| 假设影响 | 不改变数据库数据，只把现有约束提前为明确业务错误。 |

## 验收标准

```text
Given 当前租户存在相同编码的软删除组织
When 再次创建该编码
Then 应在 INSERT 前返回 ORGANIZATION_CODE_EXISTS
And 提示该编码已存在或曾被使用，不产生数据库约束异常

Given 业务异常已被全局异常处理器转换成 HTTP 响应
When OperationLogFilter 在请求结束后写操作日志
Then error_message 包含安全业务错误码、消息和错误编号
And 不包含堆栈、SQL、密码、令牌或任意请求正文

Given 未处理异常
When 全局异常处理器返回 INTERNAL_ERROR
Then 响应、服务端日志和操作记录共享同一错误编号
And 前端仍不获得原始异常详情
```

边界：成功请求错误字段保持空；未认证请求不落操作日志；错误文本受 500 字符上限约束。数据库写集成测试本轮不连接生产库，以 Mapper 合同、服务单测和过滤器/异常处理器单测覆盖。

## 授权范围

| 动作 | 是否允许 | 限制或目标 |
|---|---|---|
| 创建或切换分支 | 否 | 当前 detached worktree 保持不变 |
| 数据库写入 | 否 | 不连接任何数据库 |
| 创建提交 | 否 | 未获授权 |
| Push / PR | 否 | 未获授权 |
| 部署 | 否 | 未获授权 |
| 外部副作用 | 否 | 不操作云端、SCM、Caddy、MySQL |

## 影响与风险

- GitNexus：索引与提交一致。`assertOrganizationCodeAvailable` 和 `countOrganizationCode` 为 LOW，1 个直接调用方、1 条 `createOrganization` 流；`handleBusiness`/`handleUnexpected` 为 LOW；`OperationLogFilter` 未索引，风险 UNKNOWN。
- 直接修改：MasterData Mapper 查询合同、组织编码错误消息、请求失败上下文、异常处理器、操作日志过滤器。
- 间接消费者：新增组织 API、前端通用错误展示、系统操作记录页面、服务端错误日志。
- 风险等级：L3。
- 分级理由：持久化前置校验和跨请求错误审计链发生变化，但无表结构与数据迁移。
- 回滚：逐文件撤销本任务增量即可恢复旧行为；数据格式未迁移。

## 多角色与文件所有权

| 角色 | 任务 | 文件/模块所有权 | 是否只读 | 是否允许修改测试 |
|---|---|---|---|---|
| 产品/架构阶段 | 固化不可复用合同、脱敏边界和回滚 | 本任务记录与方案 | 是 | 否 |
| 后端实现阶段 | 最小实现组织校验与错误上下文 | 相关 `backend/src/main` 文件 | 否 | 否 |
| 测试阶段 | 先写失败测试并运行模块回归 | 相关 `backend/src/test` 文件 | 默认只读审查；新增测试除外 | 是 |
| 最终代码审查阶段 | 独立阶段检查泄密、事务、兼容和越界 diff | 本任务全部变更 | 是 | 否 |

## 测试矩阵

| 场景 | 层级 | 预期结果 | 命令 | 证据 |
|---|---|---|---|---|
| 软删除编码 | 单元/Mapper 合同 | INSERT 前明确拒绝，XML 与唯一键一致 | Maven 定向测试 | 最终记录 |
| 业务异常审计 | 单元 | 错误码、消息、错误编号落日志 | Maven 定向测试 | 最终记录 |
| 未处理异常 | 单元 | 响应与日志编号一致，不泄露详情 | Maven 定向测试 | 最终记录 |
| 成功/未认证 | 单元 | 成功错误为空；未认证不落日志 | Maven 定向测试 | 最终记录 |
| 模块回归 | 模块 | backend 测试零失败 | `mvn test` | 最终记录 |
| 结构与影响 | 静态 | 无越界空白/意外流程 | GitNexus detect-changes、`git diff --check` | 最终记录 |

## 最终记录

- 实现结果：
  - `countOrganizationCode` 不再排除软删除记录，查询口径与 `(tenant_id, organization_code)` 唯一键一致。
  - 重复或历史已用编码在 INSERT 前返回 `ORGANIZATION_CODE_EXISTS`，提示“组织编码已存在或曾被使用，请更换编码”。
  - 新增请求失败上下文；写请求响应携带 `X-Correlation-Id`，操作日志记录安全错误码、错误编号与可展示消息。
  - 未处理异常响应只返回通用消息和错误编号；服务日志只记录错误编号及异常类型，不记录原始异常消息、SQL 或凭据。
  - 成功写请求保持 `error_message=null`；未认证请求不创建带操作人的审计记录。
- 测试先行证据：新增测试实现前为 `4 tests / 4 failures`，分别命中旧提示、Mapper 的 `deleted=0`、缺少业务错误编号、缺少未处理异常错误编号。
- 定向回归：`MasterDataOrganizationCodeTest`、`GlobalExceptionOperationLogTest`、`MasterDataServiceOrganizationDeleteTest` 共 `8/8 PASS`。
- backend 全量：`150 tests / 0 failures / 0 errors / 60 skipped`；跳过项均为需独立 MySQL 环境的集成测试，本轮未连接数据库。
- GitNexus：全工作树因用户既有大批未提交改动显示 `HIGH`；本任务复查的 `assertOrganizationCodeAvailable`、`countOrganizationCode`、`handleBusiness`、`handleUnexpected` 均为 `LOW`。本任务没有触碰报告外的既有改动。
- 清洁检查：`git diff --check` 退出码 0；仅报告 6 个既有 PowerShell 文件的 LF→CRLF 提示。
- 未执行：未创建提交、未构建生产包、未部署、未连接或修改云端 MySQL、未启停任何服务。
