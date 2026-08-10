# 独立运维控制面设计

## 当前实现状态（2026-08-09）

`ops-control-plane` 已具备独立 Spring Boot 进程、loopback 监听、Bearer Token 摘要认证、单文件部署授权包上传/固定校验、HostSnapshot 绑定、耐久类型化队列、哈希链状态日志、发布/审计/Agent 状态 API 和静态运维页面。外层 ZIP 固定包含正式发布包、精确生产计划和两名不同签署人的 detached CMS；因此操作页面只需一次最终确认，不降低底层双签名门禁。

独立 Windows Agent 已实现 `VerifyOnly` 和 `ExecuteSignedDeployment`。生产模式再次验证包、计划、双 CMS、HostSnapshot 与完整 PowerShell 工具包锁，只调用固定 `Invoke-LeanTpmDeployment.ps1`，并通过耐久结果和 completed 队列实现崩溃重放保护。控制面只有在全部绑定一致时才把记录推进为 `DEPLOYED`。两个独立 Windows Service 的固定 starter、只读安装就绪检查、安装、绑定、状态和卸载源码已完成；真实服务账户/ACL/SCM 与备份、迁移、切换、补偿仍需隔离 Windows 演练，因此当前状态是“仓库生产候选”，不是“目标服务器已启用”。控制面不读取业务 JWT/数据库密码，也不提供终端、SQL、自由路径、自由 URL 或自由服务名。

`Get-LeanTpmOpsServicesInstallationReadiness.ps1` 是安装前的唯一发现入口：它只读采集固定制品、工具包锁、服务身份、签名和 SCM 占用状态，并把实际摘要传给 `Install-LeanTpmOpsServices.ps1 -PlanOnly`。`PLAN_READY` 只表示安装参数已形成且仍不可执行；`INPUT_REQUIRED` 必须先消除全部 blocker。任何状态都不等于已经安装或已经部署。

`New-LeanTpmDeploymentBundle.ps1` 把正式发布包、生产计划和两份签名封装为固定六文件 ZIP；操作员只上传该 ZIP 并确认一次。生成端与服务器验证端分别重算摘要，上传动作本身不能改变审批或主机绑定。

## 1. 隔离原则

第二阶段控制面必须与 `LeanTPM.Backend` 在以下方面分离：

- 独立进程、Windows Service、端口、服务账户与安装目录；
- 独立 OIDC/AD 应用、token audience/issuer、MFA 与角色；
- 独立审计/发布记录存储，不复用业务数据库或业务操作日志；
- 不读取业务 JWT Secret、app DB 密码、APP 签名私钥；
- 与特权 ReleaseAgent 只通过本机 ACL 命名管道传递版本化、强类型命令。

控制面不可用不得影响业务服务；业务服务不可用时控制面仍应能认证、查询 SCM/日志、验证包和执行固定应用回滚。

## 2. 角色

| 角色 | 权限 |
|---|---|
| Viewer | 版本、健康、发布记录、受限日志只读 |
| Operator | 启停固定 Backend、测试环境发布、创建备份 |
| ReleaseManager | 发起生产发布/回滚，不能批准自己的请求 |
| Approver | 批准/拒绝生产发布、恢复和 break-glass |
| Auditor | 审计导出与验证，无服务控制 |
| SecurityAdmin | 身份、信任根和命令策略；不能发起发布 |

## 3. API 合同草案

```text
GET  /api/v1/releases
GET  /api/v1/releases/{releaseId}
POST /api/v1/releases/import
POST /api/v1/releases/{releaseId}/verify
POST /api/v1/releases/{releaseId}/deployments
POST /api/v1/deployments/{deploymentId}/approve
POST /api/v1/deployments/{deploymentId}/cancel
POST /api/v1/deployments/{deploymentId}/rollback
GET  /api/v1/services/backend
POST /api/v1/services/backend:start|stop|restart
POST /api/v1/backups
POST /api/v1/restores/plans
POST /api/v1/restores/{planId}/execute
GET  /api/v1/logs?cursor=...
GET  /api/v1/audit?cursor=...
GET  /api/v1/agent
GET  /api/v1/health
```

所有写 API 要求幂等键、CSRF/重放防护、actor、理由和审计 correlation id。生产 deploy/restore/uninstall/break-glass 要求不同主体双审批和短有效期。任意 shell、SQL、DSN、服务名、URL 或自由路径不属于合同。

## 4. UI

- 发布总览：当前/上一版本、schema、兼容性、健康与签名状态；
- 发布记录：状态机时间线、每步摘要、actor/approver 与错误；
- 服务：固定 Backend 状态和白名单启停；
- 备份恢复：有效备份集、恢复计划、隔离目标和 RPO/RTO；
- 日志：受限字段、时间范围、游标分页和脱敏；
- 监控告警：基础 JVM/HTTP/DB/持久安全状态表/磁盘/备份指标；
- 审计：追加式事件、摘要验证和导出。

UI 不提供终端、SQL 控制台、文件浏览器或任意脚本输入。

## 5. 实施顺序

1. 只读 Ops 服务：独立认证、发布/健康/审计读取。
2. 本机 ReleaseAgent：VerifyOnly、签名生产执行、工具包锁、严格结果回传、崩溃重放，以及固定 Windows Service 的只读 readiness、安装/绑定/状态源码已完成；生产卸载仍 fail closed，真实隔离演练仍待授权。
3. 开放测试环境 Import/Verify/Stage/Activate。
4. 开放固定 Backend 启停与备份。
5. 在故障、并发、越权和审计验证后开放生产双审批发布/回滚。
6. 最后接入告警渠道；外部系统副作用需单独授权。

第一阶段隔离演练未通过前不得开始此实施。
