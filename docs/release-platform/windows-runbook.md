# Windows Server 发布、回滚与恢复运行手册

> **当前生产发布入口：** 每次发布前必须先阅读[生产发布最佳实践与避坑清单](production-deployment-practices.md)。独立 OpsControl/ReleaseAgent 在完成该文档规定的重复隔离验证和人工准入前，不得承担生产发布；当前使用已经过 1.0.2 验证的直接业务发布路径。

## 1. 使用边界

本手册的命令只适用于经批准的隔离或目标 Windows Server。不得把示例路径、主机名、UUID、证书指纹或账号直接当成客户值。生产部署、数据库、服务、证书库、签名材料和外部系统仍需逐项授权。

第一阶段是本机管理员运行的受限脚本控制面；第二阶段才由独立 Ops/ReleaseAgent 调用同一白名单动作。任何脚本不接受自由 shell、自由 SQL、自由服务名或任意生产根目录。

## 2. 主机准备

1. 只开放公网 443；Backend 强制监听 `127.0.0.1:18080`，并用 Windows 防火墙复核 18080 不可从外部访问。
2. 安装 Java 21、固定版本/摘要的 WinSW 和 Caddy（或经批准等效代理）；验证 Authenticode/厂商摘要。
3. 创建低权限虚拟服务账户或 gMSA。日常 Backend 不使用 LocalSystem/管理员。
4. 生产布局由管理员所有的 `C:\ProgramData\LeanTPM-bootstrap\host-layout.json` 固定：不可变程序根为 `D:\LeanTPM\App`，运行数据根为 `D:\LeanTPM\Runtime`，既有 `D:\LeanTPM\data` 仅保留给 MySQL。安装器按 releases/config/pointers/secrets 只读、uploads/logs 可写、staging/audit/backups/locks 仅管理员/Agent 的粒度设置 ACL；不得用 non-production 开关绕过该布局。
5. 先维护 canonical `config\effective-config.json`，再生成/复核实际 `config\leantpm.env`；发布备份门禁会逐项比较两者的 DB、端口、附件、CORS 与版本值，漂移即中止。用 `Protect-LeanTpmDpapiSecret.ps1` 交互创建 `secrets\*.bin`，并从 `config\secret-references.json` 只保存 `dpapi://` 引用。禁止在 env、命令行或报告中保存 Secret。
6. 将 manifest/审批/备份/starter signer、Backend/Proxy gMSA、`publicHost` 以及 Java/WinSW/Caddy 摘要固定在 `config\release-trust.json`，并与 `release/toolchain-lock.json` 交叉校验；将加密卷、隔离、离机复制和保留策略写入 `config\backup-protection.json`。
7. Caddy 证书私钥 ACL 只授予代理身份；验证证书链、SAN、自动续期、HSTS 和 TLS 轮换告警。

### 2.1 Legacy 目录受控导入边界

发现旧 `D:\LeanTPM\current`、`shared`、`packages` 或其他受管目录非空时，首次安装必须保持阻断。当前仓库只提供以下离线、只读规划步骤：

1. 使用 `Get-LeanTpmLegacyLayoutInventory.ps1` 生成只读 inventory，并核对 `D:\LeanTPM\data` 始终为 `PRESERVE_EXTERNAL`。
2. 先生成完整的数据库、附件、配置和 legacy 源备份；备份必须经过签名、逐文件哈希、加密/离机副本和隔离恢复验证。
3. 使用 `New-LeanTpmLegacyImportPlan.ps1 -PlanOnly` 绑定 inventory 摘要、备份 receipt、逐路径确认和 `Runtime\staging\legacy-import\<planId>` quarantine 目标。
4. 规划报告始终保持 `status=INPUT_REQUIRED`、`trustSource=CALLER_BOUND_PLAN_ONLY` 和 `executable=false`；即使逐路径确认与 caller-bound receipt 齐全，也不表示备份可信、已批准或可执行。当前脚本不具备执行权限，不得执行复制。

未来可执行导入仪式必须另行实现并通过双人 CMS 审批、nonce/expiry/重放门禁、HostBootstrap 与全局锁内复核、源摘要重验、copy-only quarantine、目标摘要与 ACL 复验、canonical App/Runtime 转换、审计和失败回滚。任何执行器都不得删除源目录、覆盖非空目标或读写 `D:\LeanTPM\data`；这些资产与隔离 Windows 演练完成前，legacy 非空主机仍为 `IMPORT_REQUIRED`。

## 3. Windows Service 生命周期

- 安装：只使用已 pin 的 WinSW、已由固定证书 Authenticode 签名的 starter、固定服务名 `LeanTPM.Backend`；管理员安装后以低权限身份运行。
- Start/Stop/Restart/Status/Uninstall：只通过 `Invoke-LeanTpmWindowsService.ps1`，修改动作要求显式确认；生产卸载还必须提供绑定 host/environment、固定 SCM image、nonce/expiry 的双 CMS 审批计划。
- 升级：不重装服务；验证新 bundle 后切换不可变 release 与 current/previous，再重启固定服务。
- 卸载：只移除 SCM 注册，不删除 release、配置、附件、日志、审计或备份。
- SCM 的 Running 不等于发布成功；必须同时通过 readiness 与 `/actuator/info` 的产品/schema 身份校验。
- 安装顺序固定为 Backend → `Test-LeanTpmWindowsServiceBinding.ps1` → Caddy → `Test-LeanTpmCaddyServiceBinding.ps1`。Caddy 使用不同 gMSA，仅能读取版本化 Web 资产和写自己的 TLS/log 目录，不能读取 Backend secrets；它以 Disabled 注册，只有完整 binding 通过后才改 delayed-auto，本轮安装失败先禁用并撤销注册。任一关键文件、目录 ACL、账号、SCM SDDL 或摘要漂移都拒绝启动/幂等安装。

真实演练必须覆盖重复安装、路径含空格、自动启动、机器重启、停止超时、崩溃恢复、端口占用、ACL 越权和旧版本回切。超时不默认 kill；break-glass 另行双审批。

## 4. 发布

发布计划只包含强类型 environment/release/approval/package digest、固定根目录、固定服务、loopback health URI、精确数据库与 server UUID。生产计划还包含两个 detached 审批签名引用。

执行顺序：

```text
全局锁 → 主机/包/双审批预检 → 停止写入 → DB+附件+配置引用+指针备份
→ 备份签名/哈希复验 → 验签解压到 staging → 移入不可变 release
→ 重建受保护 release 子树 DACL并复验全部字节 → 独立 migrator validate/migrate
→ 切 Web junction 与 Backend pointer
→ 启动 → readiness + version/schema 身份校验 → 审计
```

- 发布包在受保护的固定对象上计算摘要、解压和验签，不能在预检后重新读取可替换路径。
- 从系统 Temp/staging 移入的 release 不继承来源 DACL；`Protect-LeanTpmReleaseDirectory.ps1` 为每个目录/文件设置 Administrators/SYSTEM、Backend RX 和 Web-only Proxy RX 的受保护 ACL，拒绝 reparse/额外 reader-writer/DeleteChild，并在任何 DB 写入前重新计算目录摘要和执行 manifest/artifact 验证。
- 备份前必须实际停止业务写入；开关只记录已发生事实，不能代替排空。
- migration 使用独立账号、环境变量传 Secret、精确 server UUID，并以 `VERIFY_IDENTITY` + 主机 Java trust store 验证 MySQL 身份；Backend 的生产 profile 永久关闭自动 Flyway。
- audit JSONL 每次追加前从第一条重算哈希链；Backend 无写权限，并要求离机/WORM 汇聚作为最终不可抵赖锚。
- readiness 失败时先停止新进程，恢复 pointer/Web junction，再启动旧版本；新 schema 保留，只有兼容矩阵允许才自动应用回切。

首次上线不得伪造 previous 指针，必须使用 `Initialize-LeanTpmFirstRelease.ps1` 的独立 `FIRST_INSTALL / UNINITIALIZED` 仪式，并证明主机、附件目录和数据库均为空。迁移或激活期间会持久写入 `recovery-inhibit.json`；标记精确绑定 release/package、数据库 host/port/name、`@@server_uuid` 与 runtime config 摘要。若进入 `RECOVERY_REQUIRED`，只允许经双审批的 `Resolve-LeanTpmRecovery.ps1` 执行 `COMPLETE_FORWARD`，纯预检失败不得改写原标记。失败补偿若不能通过普通 SCM Stop 证明 Backend 与监听端口均消失，`Stop-LeanTpmBackendFailClosed.ps1` 只处理固定服务的 SCM PID/子树；仍不确定时停止并验证 `LeanTPM.Proxy`，以牺牲可用性换取公网 fail closed。

## 5. 回滚

回滚与发布共用全局锁。计划中的 current/previous 必须与磁盘指针一致，当前失败 release 与目标 release 都重新验证 schema、manifest、签名和每个 artifact。

- `APPLICATION_ONLY`：当前 DB schema 与目标应用 schema 完全相同，且不存在会阻止旧应用运行的持久化数据合同变化；清单可以选择更保守的 `RECOVERY_REQUIRED`，但不能低于迁移阶段要求。
- `FORWARD_COMPATIBLE_SCHEMA`：回滚类别来自“当前失败 release”的已验证 manifest；目标应用必须在兼容矩阵中明确 `SUPPORTED` 当前 schema。
- `RECOVERY_REQUIRED`：禁止自动应用回切，进入隔离恢复/前滚处置。

任何回滚启动/健康失败都恢复原指针、junction 和服务，并写 `ROLLBACK_FAILED`。不得通过手工改 Flyway history、覆盖非空库或删除失败证据伪造成功。

## 6. 备份与恢复

生产备份集包括：`backup-manifest.json`、`backup-manifest.p7s`、数据库 dump、附件、脱敏有效配置、Secret 引用、current/previous、release manifest 和保护策略。清单逐文件记录路径、大小、SHA-256、源 server UUID、schema、加密/隔离/离机复制/保留声明。

恢复只允许不同名称的新数据库和已有空目录；所有可执行恢复都要求受信签名备份，生产源还要求绑定 backup manifest、隔离目标、host/environment、nonce/expiry 的双 CMS 审批。所有目标（包括 loopback）必须固定实际 `@@server_uuid`，MySQL 预检、导入与复验均使用 CA + `VERIFY_IDENTITY`。验证后的快照文件持有只读锁直至消费结束，文件恢复只复制 manifest 白名单。数据层成功仅标记 `DATA_RESTORED_PENDING_APPLICATION_E2E`；必须再以 sandbox-only 配置启动应用并完成管理员认证、核心读取与附件读写，才能人工晋级为恢复成功。失败会留下 `RESTORE_INVALID.json`，数据库视为可能部分恢复，必须隔离调查；脚本不会猜测性删除。

备份集只有在加密卷和离机复制任务均真实生效时才满足生产门禁。保护 profile 是声明，不替代存储平台证据。建议目标 RPO≤15m、RTO≤4h，最终以演练测量为准。

## 7. HTTPS 与健康验收

- 外部只能经 HTTPS 代理访问；HTTP→HTTPS、TLS 链/SAN、HSTS、安全头、上传大小和 Web SPA fallback 均验证。
- liveness 只含进程状态/ping，避免依赖故障导致重启风暴。
- readiness 包含 DB、磁盘、实际 Flyway schema 与附件目录非破坏性写删探针；生产数据库强制身份校验 TLS，并在 E2E 验证证书失败会 fail closed。
- health 对外不暴露 details；代理拒绝公网 `/actuator/health*`，本机发布工具只查 loopback。

## 8. 演练、证据和清理

隔离演练至少执行 fresh V1→V50、V32/V37/V44/V48/V49→V50、重复 migrate no-op、checksum 篡改、迁移中断、备份损坏、恢复、服务启停、证书失败、readiness 假阳性、并发锁和断电重入。每次记录唯一资源名、目标 UUID、开始/结束、RPO/RTO、结果、失败资产、精确清理清单和生产未触达证明到 `reports/ai/`，不得包含 Secret 或未脱敏数据。

只有全部证据通过、残余风险获批且 `git diff --check`、依赖审计、GitNexus detect-changes、独立审查与清理门禁完成后，第一阶段才可验收；此前第二阶段不实施。
