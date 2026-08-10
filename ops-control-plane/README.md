# LeanTPM 独立运维控制台

该模块是与业务 Backend 分离的发布控制面。它默认只监听 `127.0.0.1:18090`，使用独立 Bearer Token 摘要认证，并把上传包、发布状态、幂等绑定、队列命令和审计日志放在独立受保护目录中。

## 当前已实现

- 上传 ZIP 到固定数据根并重新计算 SHA-256；
- 调用固定路径、固定脚本摘要和固定证书指纹的 `Test-ReleasePackage.ps1`；
- 绑定 HostLayout/当前版本快照，生成 15 分钟有效的精确计划摘要；
- 普通发布默认要求两个不同操作员确认；已经在外层授权包内完成双 CMS 签名的生产发布，只需要页面最终确认一次；
- 只写入固定 `DEPLOY_RELEASE` 或 `DEPLOY_SIGNED_RELEASE` 类型的耐久文件队列，不接受 shell、SQL、服务名、URL 或任意目标路径；
- 追加式哈希链状态日志、幂等重放保护、只读发布记录与审计时间线；
- 只读 Agent 状态接口，区分未连接、在线和心跳过期，并展示耐久队列待处理数量；
- 独立 Windows ReleaseAgent 的 `VerifyOnly` 与 `ExecuteSignedDeployment` 工作循环：写入短时效心跳、严格读取类型化任务、重新验证包与双 CMS，并用完整 PowerShell 工具包锁调用固定部署入口；
- 生产 Agent 在整个执行期间锁定工具包清单与所有 PowerShell 脚本，校验严格部署报告，先耐久写入结果，再把作业原子移入 `completed`；崩溃重放不会重复部署；
- 控制面严格读取并校验 Agent 结果：`VERIFIED_ONLY` 协调为 `AGENT_VERIFIED`，完整匹配的生产执行结果协调为 `DEPLOYED`；
- 静态运维页面，令牌只保存在当前页面内存；
- 控制台所在主机的 CPU、系统内存、磁盘/JVM 图形化只读监控，以及三个固定 Windows Service、Backend readiness、MySQL V50 和固定日志尾部的可选完整监控；
- 自动修复只允许启动 `LeanTPM.Backend`、`caddy`、`LeanTPM.ReleaseAgent` 三个固定服务，并持久化连续失败、冷却和每小时次数上限；
- PushPlus 支持一个或多个接收方，逐接收方隔离失败，以 Markdown 展示影响、自动处理、当前状态、建议动作和时间；API 状态不返回 token；
- 匿名只开放首页静态资源与健康检查，全部发布/审计 API 都要求独立运维身份。

## 当前生产边界

`QUEUED` 仅表示耐久作业已经形成，`AGENT_VERIFIED` 仅表示服务器 Agent 已复核发布绑定；只有严格的 schema v2 `DEPLOYED` 结果才表示固定部署入口返回 `SUCCEEDED` 或 `ALREADY_SUCCEEDED`。Agent 不提供回滚、终端、SQL、自由路径或任意服务控制。

简化流程不是把双人审批降为单人审批：上传的一个 ZIP 已经固定包含部署计划和两名不同签署人的 detached CMS；网页上的一次确认只是把这份已签授权加入生产队列。正式开放前仍需在隔离 Windows 完成服务账户、ACL、SCM、备份、迁移、切换和补偿演练。

## 构建与测试

```powershell
mvn.cmd test
mvn.cmd package
```

测试必须为零失败；生成的 JAR 位于 `target\leantpm-ops-control-plane-1.0.1.jar`。正式部署不得使用仓库示例中的占位摘要或证书指纹。

正式发布人员完成发布包、生产部署计划和两份 detached CMS 后，使用 `scripts\New-LeanTpmDeploymentBundle.ps1` 生成页面唯一需要上传的 ZIP。生成器锁定全部源文件、重算 package/plan/signature 摘要、嵌入仓库 schema、拒绝覆盖现有输出，并生成固定六文件布局；服务器仍会独立重新验证整个 ZIP。

## 生产配置

复制 `config\application-production.example.yml` 到管理员/SYSTEM 保护的主机配置目录并替换全部占位值。配置只保存操作员令牌的 SHA-256，不保存明文令牌；JAR、配置、验证脚本、HostLayout 和当前版本指针都必须使用固定路径与摘要。

主机资源只读监控默认开启，采集的始终是运行本控制台 Java 进程的主机：本机运行显示本机，部署到阿里云 Windows 后显示该云服务器。可用 `leantpm.ops.monitoring.host-resources-enabled=false` 单独关闭。Windows Service、Backend、MySQL 和日志组成的完整监控、自动修复及 PushPlus 默认关闭。PushPlus 的每个接收方 token 通过环境变量注入；配置可以增加多个 `recipients`，某个接收方失败不会阻止其他接收方。`wechat` 等普通渠道可直接使用，`sms`/`voice` 必须同时设置 `allow-paid-channels: true`，避免意外产生费用。PushPlus 返回 code=200 只表示 API 接受请求，控制台不会把它表述为终端已经送达。

建议启用顺序：先只开启 `monitoring.enabled` 并核对页面采集结果，再开启 PushPlus 并用受控故障验证通知，最后才开启 `remediation.enabled`。自动修复状态写入 `state\operations\operations-runtime-state.json`，应用重启不会清空冷却和每小时次数上限。

建议的独立运行布局：

```text
D:\LeanTPM\App\ops-control-plane\
├─ app\leantpm-ops-control-plane.jar
├─ scripts\Test-ReleasePackage.ps1
├─ scripts\Test-LeanTpmDeploymentBundle.ps1
├─ scripts\Test-LeanTpmReleaseApproval.ps1
└─ scripts\Invoke-LeanTpmReleaseAgent.ps1

D:\LeanTPM\App\release-agent-toolkit\
├─ scripts\
├─ deploy\windows\
└─ release\release-agent-toolkit-lock.json

D:\LeanTPM\Runtime\ops-control-plane\
├─ uploads\
├─ approvals\
├─ state\release-events.jsonl
└─ queue\
   ├─ pending\
   ├─ completed\
   ├─ results\
   └─ agent-heartbeat.json
```

`agent-heartbeat.json` 是受限 Agent 写入的严格、短时效状态文件。控制面只读取它；缺失显示 `NOT_CONNECTED`，超过 `agent-heartbeat-maximum-age` 显示 `STALE`。生产 Agent 声明 `PRODUCTION_ENABLED` 时仍必须逐作业完成全部签名、包、主机和工具包复核。

管理员可先执行一次只读校验循环。生产服务使用同一固定参数集合，只把模式改为 `ExecuteSignedDeployment`：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File 'D:\LeanTPM\App\ops-control-plane\scripts\Invoke-LeanTpmReleaseAgent.ps1' `
  -Mode VerifyOnly `
  -RunOnce `
  -QueueRoot 'D:\LeanTPM\Runtime\ops-control-plane\queue' `
  -UploadRoot 'D:\LeanTPM\Runtime\ops-control-plane\uploads' `
  -ApprovalRoot 'D:\LeanTPM\Runtime\ops-control-plane\approvals' `
  -PackageVerifierPath 'D:\LeanTPM\App\ops-control-plane\scripts\Test-ReleasePackage.ps1' `
  -PackageVerifierSha256 'REPLACE_WITH_64_HEX_SHA256' `
  -ApprovalVerifierPath 'D:\LeanTPM\App\ops-control-plane\scripts\Test-LeanTpmReleaseApproval.ps1' `
  -ApprovalVerifierSha256 'REPLACE_WITH_64_HEX_SHA256' `
  -ReleaseTrustConfigPath 'D:\LeanTPM\Runtime\config\release-trust.json' `
  -DeploymentToolkitRoot 'D:\LeanTPM\App\release-agent-toolkit' `
  -DeploymentToolkitLockPath 'D:\LeanTPM\App\release-agent-toolkit\release\release-agent-toolkit-lock.json' `
  -DeploymentToolkitLockSha256 'REPLACE_WITH_64_HEX_SHA256' `
  -TrustedCertificateThumbprint 'REPLACE_WITH_40_HEX_THUMBPRINT' `
  -AgentId 'leantpm-release-agent-01' `
  -AgentVersion '1.0.1' `
  -OutputFormat Json
```

示例占位值不得用于生产。Agent 必须以独立受限身份运行，且队列、上传根、审批根、结果目录、脚本和信任配置需使用精确 ACL。生产执行桥及两个独立 Windows Service 的安装/绑定/控制源码已实现为仓库候选；真实 gMSA、ACL、SCM 与失败补偿仍待隔离 Windows 验证。

## 两个独立 Windows Service

- `LeanTPM.OpsControl`：固定 Spring Boot JAR/配置并强制监听 `127.0.0.1:18090`；只负责上传、审批、审计和状态，不直接调用部署脚本。
- `LeanTPM.ReleaseAgent`：固定 starter 只循环调用 `Invoke-LeanTpmReleaseAgent.ps1 -Mode ExecuteSignedDeployment -RunOnce`；不接收 shell、SQL、URL、服务名或目标根路径。

安装前先执行只读入口 `deploy/windows/Get-LeanTpmOpsServicesInstallationReadiness.ps1`。它重算 Java、WinSW、JAR、配置、starter 和完整 Agent toolkit lock 的摘要，检查四个服务身份互不相同，并在生产根验证 starter 签名、新增的两个 gMSA 与固定 SCM ID 尚未注册。只有全部输入可验证时才调用既有安装器生成 `PlanOnly` 计划并返回 `PLAN_READY`；否则返回带稳定 blocker code 的 `INPUT_REQUIRED`。该入口不创建目录、不修改 ACL、不注册或启停服务。

审阅 `PLAN_READY` 中的实际路径、账号与摘要后，才可在另行授权的隔离 Windows 仪式中执行 `deploy/windows/Install-LeanTpmOpsServices.ps1`。生产安装要求 HostLayout、全局 deployment lock 与 `-ConfirmInstallation`；两个服务先以 Manual 注册并通过 `Test-LeanTpmOpsServicesBinding.ps1`，之后才切换为 delayed-auto，安装器本身不会启动服务。

固定状态/动作入口为 `Invoke-LeanTpmOpsServices.ps1`。非生产卸载只移除两个固定 SCM 注册并保留 Runtime 数据；生产卸载当前 fail closed，必须等专用双人签名、nonce/replay 账本合同完成后再开放。上述资产不代表目标服务器已安装，真实安装仍需另行授权的隔离 Windows 仪式。

正式 Windows Service、受限 ReleaseAgent 和 Caddy 运维入口完成并通过隔离 Windows 验证之前，仅允许管理员通过 RDP 在服务器本机访问 `http://127.0.0.1:18090/`。
