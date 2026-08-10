# 独立运维后台生产装配子任务

## 需求

- 独立运维后台必须能以完整 Spring Boot 应用启动，而不是只在单元测试中手工拼装对象。
- 默认仅监听 `127.0.0.1:18090`；公网入口后续只能由受控 Caddy 路由提供。
- 上传、状态日志、作业队列、包校验器、主机快照和独立令牌认证必须由受保护的外部配置绑定。
- 仓库不得包含明文令牌、生产证书指纹或可执行任意命令的配置。
- 生产确认数默认是 2；只有显式配置为 1 时才启用“上传一个文件、确认一次”的简化流程。

## Given / When / Then

```text
Given 受保护的运维数据根、固定 PowerShell 与校验脚本摘要、HostLayout 摘要和操作者令牌摘要
When 启动完整 ops-control-plane Spring Boot 应用
Then 上传存储、哈希链仓库、固定校验器、HostSnapshot、类型化文件队列和 ReleaseWorkflowService 全部装配成功
And /actuator/health 可匿名读取，发布 API 未认证时返回 401
And 不生成 Spring 默认用户或默认密码

Given 缺少任何信任锚或操作者令牌摘要
When 启动应用
Then 启动失败且不退化为默认认证、内存仓库或空校验器
```

## 影响、风险与所有权

- 风险：L4。该装配连接认证、上传、签名校验、主机身份、持久状态和部署队列。
- GitNexus：新模块尚未进入索引，`OpsControlPlaneConfiguration` 影响结果为 `UNKNOWN`；不得解释为低风险。
- 主实现所有权：`ops-control-plane/src/main/**` 与本子任务测试。
- 测试审查：完整应用上下文、匿名健康检查、未认证拒绝、默认双审批；只读复核实现边界。
- 架构安全审查：禁止默认凭据、任意 shell/SQL/路径/服务名；仅固定类型化组件。
- 回滚：删除本子任务新增装配类与资源即可回到“核心库可测试、应用不可生产启动”的旧状态；不迁移业务数据。

## 测试顺序

1. 先添加完整应用上下文测试并确认因缺少生产装配类而失败。
2. 添加最小配置属性、Bean 装配与安全默认资源。
3. 运行完整 `ops-control-plane` Maven 测试。
4. 在正式 D 盘源码执行相同测试、`git diff --check` 和最终影响复核。

## 只读审计时间线增量

### 需求与 Given / When / Then

- 运维页面需要展示发布状态写入和幂等绑定的耐久审计时间线，但不得泄露上传包物理路径、令牌或签名材料。
- Given 哈希链日志包含多个连续事件，When 使用游标分页读取，Then 只返回序号、事件类型、发布标识、发布状态、操作名和链摘要，并保持稳定顺序。
- Given 日志被重写、截断或游标/分页参数越界，When 服务启动或读取审计，Then fail closed，不能返回伪造的“正常”审计结果。
- Given 未认证访问审计 API，When 请求 `/api/v1/audit`，Then 返回 401；认证后 UI 可刷新时间线，令牌仍只保存在页面内存。

### 影响与所有权

- 风险仍为 L4；GitNexus 对整块未跟踪的新模块返回 `UNKNOWN`，本增量只修改 `ops-control-plane` 的日志读取、只读 API、静态 UI 与对应测试。
- 不修改生产部署、恢复、数据库、SCM、Caddy 或云端资源；回滚仅需移除本增量新增的审计 DTO/API/UI。

## ReleaseAgent 联机状态增量

- Given 受限 Agent 尚未写入受保护心跳，When 操作员读取状态，Then 返回 `NOT_CONNECTED`、当前待处理作业数和 `productionExecutionEnabled=false`，不能把 `QUEUED` 误报为已执行。
- Given 心跳严格匹配 schema、Agent 标识、版本、模式并且仍在最大时效内，When 读取状态，Then 返回 `ONLINE`；过期心跳返回 `STALE` 且生产执行能力强制为 false。
- Given 心跳文件是 reparse、非普通文件、非法 UTF-8/JSON、未知字段或未来时间，When 读取状态，Then fail closed。
- 状态 API 必须认证；页面认证成功后同时显示控制面和 Agent 状态。

## ReleaseAgent VerifyOnly 工作循环

- 风险等级仍为 L4；本增量新增独立、受限的 Windows Agent 脚本，不修改或调用生产部署/回滚入口。所有权限定为 `deploy/windows/Invoke-LeanTpmReleaseAgent.ps1`、发布平台行为测试与控制面文档。
- Given 受保护队列不存在待处理作业，When Agent 以 `VerifyOnly + RunOnce` 运行，Then 原子写入短时效 `VERIFY_ONLY` 心跳并返回 `IDLE`，不修改发布包、SCM、数据库、Caddy 或生产指针。
- Given 队列包含一个严格类型化、未过期且仍绑定原始包字节的 `DEPLOY_RELEASE` 作业，When Agent 运行一次，Then 再次调用固定路径/固定 SHA-256 的发布包校验器并原子写入脱敏验证结果；待处理作业保留，绝不执行部署。
- Given 作业 JSON 有未知/缺失字段、文件名与 commandId 不一致、过期、包越界/漂移、校验器路径/摘要漂移或结果异常，When Agent 运行，Then 非零退出且不写成功结果。
- 当前心跳只允许声明 `VERIFY_ONLY`；`PRODUCTION_ENABLED` 必须等生产 CMS、全局锁、恢复补偿和隔离 Windows 行为测试完成后另行实现。

### 当前验证结果

- `ops-control-plane` Maven 全套：37/37 PASS，0 failure/error/skip。
- 发布平台 Node 全套：68/68 PASS，包含 VerifyOnly Agent 的空队列、固定校验、结果耐久化、未知字段、releaseId 漂移、校验器漂移，以及校验器/发布包自改写被只读句柄阻断。
- 仓库正式 PowerShell 脚本：Windows PowerShell 5.1 AST 60/60 PASS。
- 当前增量仍保持 `productionExecutionEnabled=false`；未调用数据库、SCM、Caddy、发布或回滚入口。

## ReleaseAgent 结果回传增量

- 风险等级：L4。GitNexus 对尚未纳入索引的 `ops-control-plane` 符号仍返回 `UNKNOWN`；本次所有权限定为 Agent 结果读取器、`ReleaseWorkflowService` 的幂等状态协调、状态枚举/记录复制方法、Spring 装配、对应测试与运维页面文案。
- Given 固定结果目录没有当前 jobId 的文件，When 查询发布记录，Then 保持 `QUEUED`，不得推测 Agent 已完成。
- Given 结果文件是严格、哈希自证的 `VERIFIED_ONLY`，并与 jobId、releaseId、productVersion、schema、package/manifest/plan/host snapshot 全部一致，When 查询发布记录，Then 仅追加一次耐久 `AGENT_VERIFIED` 状态事件；重复查询必须幂等。
- Given 结果 JSON 有未知/缺失字段、非法 UTF-8、错误 resultSha256、生产执行标志、越界 jobId、reparse，或任何发布绑定漂移，When 控制面读取结果，Then fail closed 且发布记录保持 `QUEUED`。
- 本增量不消费 pending 作业、不删除结果、不调用部署/回滚，不把 `AGENT_VERIFIED` 表述为已部署。

### 当前验证结果

- `ops-control-plane` Maven 全套：41/41 PASS，0 failure/error/skip。
- 页面合同明确把 `AGENT_VERIFIED` 显示为“服务器已复核，尚未部署”，且该状态不再显示重复确认表单。
- 结果协调只追加一次状态事件；缺失或任一摘要/身份漂移均保持 `QUEUED` 或 fail closed。

## ReleaseAgent 签名生产执行桥增量

- 风险等级：L4。GitNexus 对新 Agent 与控制面模块仍返回 `UNKNOWN`，所以范围限定为类型化队列、Agent、固定部署入口、结果读取器和对应行为测试。
- Given 外层部署授权包已通过固定验证并物化为精确计划与两份 detached CMS，When 页面执行一次最终确认，Then 控制面写入 schema v2 `DEPLOY_SIGNED_RELEASE` 耐久命令。
- Given Agent 以 `ExecuteSignedDeployment` 处理该命令，When 包、计划、双签名、HostSnapshot、工具包 lock 和全部 PowerShell 脚本摘要一致，Then 只调用固定 `Invoke-LeanTpmDeployment.ps1`，严格验证执行报告，先写 schema v2 `DEPLOYED` 结果，再原子移动作业到 `completed`。
- Given 结果已耐久写入但作业尚未移动，When Agent 重启，Then 重新绑定 command/release/version/schema/package/manifest/plan/host/Agent 身份并完成移动，不重复执行部署。
- Given 任一签名、包、工具包、执行报告或结果绑定漂移，When Agent 处理或控制面协调，Then fail closed，不能进入 `DEPLOYED`。
- 生产桥不开放回滚、终端、SQL、URL、自由服务名或自由目标路径。

### 当前验证结果

- ReleaseAgent VerifyOnly/签名执行/重放/工具包漂移行为测试 PASS。
- 控制面 schema v2 结果读取和 `DEPLOYED` 幂等协调测试 PASS。
- 单文件生产授权 ZIP 生成器的固定六文件布局、源文件零改写、输出防覆盖和包摘要漂移拒绝行为测试 PASS。
- `ops-control-plane` Maven 全套 PASS。
- Windows Service、真实账户/ACL、SCM、备份/迁移/切换/补偿仍需隔离 Windows 演练后才能启用。

## 单文件生产部署授权包增量

### 需求与 Given / When / Then

- 用户未来只上传一个文件；该文件必须是外层部署授权 ZIP，固定包含正式发布包、精确主机部署计划、请求者/审批者两份 detached CMS 和与仓库一致的 schema，不接受任意附加文件。
- Given 授权包、内部正式发布包、部署计划、两份签名、HostSnapshot 与 host-owned trust 全部精确匹配，When 只读验证授权包，Then 返回外层/内层/计划/签名摘要和 `PASS`，且不执行部署、不写数据库、不控制 SCM/Caddy。
- Given ZIP 路径穿越、大小写碰撞、额外条目、压缩炸弹、schema 漂移、任一大小/摘要漂移、非 PRODUCTION 发布包、计划 host/package/manifest 漂移、签名身份或 CMS 校验失败，When 验证，Then fail closed 且临时目录清理完成。
- 部署计划中的签名路径必须精确落在固定 `ApprovalRoot\\<approvalId>`，内部发布包路径必须精确落在固定 `UploadRoot\\releases\\<packageSha256>\\release-package.zip`；不得由上传内容选择任意路径。

### 影响、风险与所有权

- 风险等级：L4。新增只读 bundle schema/verifier 与行为测试；不修改生产部署脚本，不开放 Agent `PRODUCTION_ENABLED`。
- GitNexus 对新增脚本无符号图；测试文件影响为 LOW，但安全结论仍按 L4 源码与行为边界审查。
- 回滚为删除新增 schema/verifier/test；不涉及生产数据或服务器状态。

## 独立运维服务 Windows Service 化（2026-08-10）

### 需求与 Given / When / Then

- 目标：把 `ops-control-plane` 与 `ReleaseAgent` 固定为两个独立 WinSW 服务，使后续正式发布保持“上传一个签名授权包、确认一次”，同时不把发布执行权限授予运维 Web 进程。
- Given 固定的 App/Runtime 根、pin 的 Java/WinSW、签名 starter、控制面 JAR/配置和 Agent 工具包，When 以 `-PlanOnly` 生成安装计划，Then 返回固定服务 ID、账户、路径、摘要与动作序列，且目录、文件、ACL、SCM、进程均零变化。
- Given 两个服务账户，When 校验安装输入，Then 必须都是不同的 gMSA，且不得与 Backend/Proxy 身份相同；`LeanTPM.OpsControl` 只能读应用制品并写控制面数据，`LeanTPM.ReleaseAgent` 只能消费固定队列并调用固定 `ExecuteSignedDeployment` 入口。
- Given 任一 wrapper/JAR/config/starter/toolkit 摘要、服务 ID、账户、SCM image、XML 或启动参数漂移，When 安装、状态查询或卸载前验证，Then fail closed，不覆盖既有服务，不执行自由 shell/SQL/服务名/目标路径。
- Given 生产安装或卸载，When 进入首次 SCM 副作用，Then 必须先通过 HostLayout、全局 deployment lock、管理员身份、固定摘要和显式确认；卸载还必须精确确认两个固定服务 ID。
- Given 服务已正确注册，When 查询状态，Then 分别返回 SCM 状态与绑定摘要；When 卸载，Then 只停止并删除这两个固定服务，不删除数据、上传包、审批、审计或发布结果。

### 影响、风险、所有权与验证

- 风险：L4/HIGH。GitNexus 对既有 PowerShell 文件仅建立文件级图，`Install-LeanTpmWindowsService.ps1` 和 `Invoke-LeanTpmReleaseAgent.ps1` 均显示 0 callers/LOW；这不足以表达 SCM 与生产发布权限，故按人工边界提升为 HIGH。
- 实现所有权：仅新增 Ops/Agent WinSW starter、模板、安装/绑定/控制脚本，以及对应控制面文档；不修改 Backend、数据库、Caddy、APP 或现有云服务。
- 测试所有权：`scripts/tests/release-platform.test.mjs` 先新增失败用例，真实调用 PlanOnly 脚本并对临时树前后快照；生产 SCM 分支只做离线合同/AST 验证，真实账户/ACL/SCM 留到另行授权的隔离 Windows 演练。
- 审查所有权：实现完成后独立执行 PowerShell 5.1 AST、Node 行为测试、GitNexus `detect-changes`、`git diff --check`；不在本批 commit、push、deploy、安装或启停服务。
- 回滚：删除本批新增服务资产并移除对应测试/文档段；不会触碰服务器 SCM 或 Runtime 数据。
