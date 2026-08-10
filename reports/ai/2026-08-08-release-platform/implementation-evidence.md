# 第一阶段候选实现与验证证据

## 结论

截至 2026-08-09，本工作树已完成第一阶段所需的仓库内设计、合同、脚本、测试与 Windows Server 运行手册候选实现；正式发布流水线候选仍为 **NOT_RELEASEABLE / 未验收**，因为尚未生成正式双 CMS 授权包，也尚未在隔离 Windows 上完成新 Agent 的生产发布、补偿与恢复演练。

第二阶段独立运维控制面已完成仓库 MVP：独立 Spring Boot 服务、上传/校验/确认 API、哈希链审计、持久队列、只读 Agent 状态、单文件双 CMS 部署授权包以及受限 Windows ReleaseAgent 的签名生产桥均已实现并通过离线行为测试；尚未在目标 Windows 主机安装为正式服务。第三阶段未启动。

## 已实现的工程资产

1. `VERSION.json` 作为统一产品版本合同，产品版本为 `1.0.1`、canonical APP 包名为 `com.leantpm.mobile`、Android code 为 `101`、数据库 schema 为 `50`；后端、Web、两条 APP 展示/回退版本已同步。
2. 根 `CHANGELOG.md`、版本兼容矩阵与 schema、数据库迁移逐项分类规则、Expand → Migrate → Contract 生命周期文档。
3. `release-manifest` JSON Schema、示例、严格校验器、逐文件 SHA-256、detached CMS 签名/验签、确定性 ZIP、路径穿越/Windows 设备名/额外条目/ZIP bomb/TOCTOU 防护。
4. clean baseline 证明、迁移 catalog、正式 manifest 签名候选、发布包生成与聚合发布验证脚本。
5. 生产 profile 默认关闭业务进程 Flyway，独立 migrator 按 server UUID、目标 schema 与 checksum 执行 validate/migrate；MySQL 连接使用 `VERIFY_IDENTITY` 与主机信任锚，数据库密码不进入进程参数。
6. Backend 与 Caddy 的 Windows Service 安装/幂等验证资产，以及固定 Backend 启停、升级切换与卸载入口；WinSW/Caddy/Java 摘要同时绑定工具链与 host trust，starter、XML、SCM SDDL、关键文件、secrets/TLS 数据和目录 ACL 均 fail closed。Caddy 先以 Disabled 注册，只有固定绑定门禁通过后才切为 delayed-auto；失败会先禁用再撤销本轮注册。真实执行仍要求外部 pin、gMSA 与 Authenticode 证据。
7. DB、附件、有效配置、实际 `leantpm.env`、Secret 引用、release、current/previous 和保护策略的备份/恢复；运行 env 与 canonical effective config/JDBC 目标逐项闭环；所有可执行恢复都要求签名备份，生产恢复还要求绑定备份/目标/主机的双 CMS 审批与 nonce 防重放。
8. 独立 gMSA 的 Caddy HTTPS 模板/安装器、host trust `publicHost`、后端 loopback 绑定、startup/readiness/liveness/info、生产 Secret 引用与 DPAPI 保护脚本；代理无 Backend secrets 权限，明文 Secret 不进入 env、manifest、包或报告。
9. 人工确认的一键首次安装/发布/回滚/前滚恢复：主机信任配置、生产双 CMS 分权审批、全局互斥锁、锁内主机状态重验、停止写入、一致性备份、验签 staging、不可变 release 子树 DACL 重建和字节复验、迁移、pointer/Web junction 切换、发布身份健康检查、补偿与哈希链审计；不兼容迁移失败持久写入绑定 DB/UUID/config 的 recovery inhibit，只有批准的精确 release/package 可启动。若错误 Backend 无法停止，固定 SCM 进程树被强制终止并验证端口；仍无法证明时停止并验证独立 Proxy，隔离公网入口并写 CRITICAL 审计。
10. 独立运维控制面的进程/账号/认证/存储/Agent 命名管道边界、强类型命令白名单、RBAC、双审批、审计和 STRIDE 设计。
11. 独立运维控制面 Spring Boot MVP、单文件部署授权 ZIP、固定六文件布局、双 CMS 与 HostSnapshot 绑定、持久化 schema v2 命令队列、受限 ReleaseAgent、工具包完整性锁、部署结果回传和 `DEPLOYED` 幂等协调。

## 本地验证证据

所有命令均在新工作树执行；没有连接数据库、注册/启停服务、修改证书库、使用签名私钥或访问外部生产系统。

| 门禁 | 结果 | 备注 |
|---|---:|---|
| 发布平台合同/故障测试 | PASS 71/71 | 在既有发布/恢复/Caddy/ACL/TOCTOU 门禁上，新增签名 ReleaseAgent 执行/重放、完整工具包锁、单文件部署授权包生成和只读验证 |
| PowerShell 语法解析 | PASS 63/63 | `scripts/`、`deploy/windows/` 与 canonical APP HBuilder 构建入口的相关 `.ps1` |
| 后端 Maven | PASS（离线代码门禁） | 144 项测试；84 项实际执行且 0 failure/error，60 项 MySQL 条件测试因当前本地门禁未设置隔离 DB 而明确跳过 |
| 独立运维控制面 Maven | PASS 49/49 | 14 个测试套件，0 failure/error/skip；覆盖上传、确认、审计、队列、Agent 状态与结果协调 |
| Web clean install | PASS | `npm ci`；本任务专用临时 npm cache |
| Web typecheck/build | PASS | Vite 构建有既有大 chunk 警告，不影响退出码 |
| Web 依赖审计 | PASS | high 及以上漏洞 0 |
| LeanTPM-APP | PASS 26/26 | 项目检查通过；未冒充正式 HBuilder APP 构建 |
| 测试发布包 | PASS | synthetic TEST manifest，显式允许 unsigned；包完整性与签名策略路径通过 |
| canonical LeanTPM-APP | NOT_RELEASEABLE | 没有读取/使用企业签名材料，也没有正式 APK 与 signer 证据 |
| MySQL 集成 | NOT_RELEASEABLE | 未获创建/写入/删除隔离数据库授权 |
| 聚合机器门禁 | NOT_RELEASEABLE（符合预期） | 代码级步骤 PASS；dirty 候选、未固定 Java/HBuilder/WinSW/Caddy、MySQL、签名 release package、canonical APP、隔离环境签名证据均明确 NOT_RELEASEABLE；JSON 见 `aggregate-gate.json` |
| `git diff --check` | PASS | 仅换行转换提示，无 whitespace error |
| GitNexus detect-changes | HIGH（聚合） | 索引已重建并与当前提交一致；78 个文件、156 个已索引符号、12 条受影响流程。新增未跟踪发布脚本与独立控制面仍按 L4 源码和行为测试边界审查 |

第一次聚合预发布门禁因外部 `D:\npm-cache` 权限/文件锁报 EPERM；临时测试包已在 `finally` 清理。最终聚合门禁将 npm cache 定向到 OS 临时目录，后端、Web、APP 与发布合同代码级步骤全部通过，并按设计对 dirty 候选、工具链 pin、MySQL、签名 release package、canonical APP 和隔离环境证据返回 `NOT_RELEASEABLE`；机器结论与 TAP 已写入本证据目录。

测试产生的 `.tmp/npm-cache`、`backend/target`、`frontend/node_modules` 与 `frontend/dist` 在确认创建时间和绝对路径均属于本任务新工作树后已精确删除；`LeanTPM-APP/unpackage` 未产生。发布包验证临时目录残留为 0，报告秘密特征扫描为 0。OS 临时目录中存在一个 `leantpm-npm-cache`，因无法仅凭名称证明独占归属而按清理规则保留；其他更早的 LeanTPM 临时目录同样未处理。详情见 `cleanup-report.md`。

19:22 无 Redis 候选复核又生成了专属 `backend/target-codex-final` 与 `frontend/dist`，两者均经绝对路径校验后精确删除；本轮开始前已经存在的 `frontend/node_modules` 因无法重新证明独占归属而保留。当前离线复核的机器摘要见 `../2026-08-08-no-redis-cloud-preparation/validation/offline-validation.json`。

## GitNexus 影响复核

- `SecurityConfig.securityFilterChain`：LOW，0 个上游影响。
- `AppReleaseService.release`：LOW（精确 impact），4 个直接、13 个总上游影响；集中在 `latest`/`qrCode` 两个控制器入口。`detect-changes` 的保守聚合结果列出 6 条相关执行流，因此总体变更报告保持 HIGH 告警。
- `currentAppInfo`：LOW，2 个直接、4 个总上游影响，0 个已识别流程。
- `appliesEveryMigrationAndFoundationTable`：LOW，0 个上游影响。

GitNexus 的局部 LOW 不改变总体风险：发布、数据库、权限、服务控制、Secret、回滚和恢复跨越多个信任域，治理等级保持 L4/CRITICAL。

## 明确未执行

- 任何生产或隔离 MySQL 写入、升级、备份、恢复或清理；
- Windows Service 安装、启动、停止、升级、崩溃恢复或卸载；
- 证书库、防火墙、Caddy/IIS、DPAPI 正式 Secret、企业 Vault 或签名私钥操作；
- HBuilder 正式 APP 构建、真实 APK 签名/验签和真机覆盖升级；
- Java/WinSW/Caddy/HBuilder 正式摘要固定与 Caddy HTTPS 证书申请/续期；
- push、PR、部署、tag、commit、stage 或外部系统调用。

因此该候选可以进入“经授权的隔离环境演练”，不能进入生产，也不能触发第二阶段实施。
