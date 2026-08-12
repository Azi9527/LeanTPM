# LeanTPM 生产发布最佳实践与避坑清单

## 1. 文档定位

本文是 LeanTPM 每次生产发布前必须阅读和逐项核对的长期运行手册。每次发布结束后，无论成功、失败或回滚，都必须在本文的“版本发布记录”中增加一条记录，并把较长证据保存在 `reports/ai/`。

本文记录的是已经在真实 Windows 生产主机上得到证据的做法。设计完成、单元测试通过或 `PlanOnly` 通过，都不能替代真实的隔离演练和生产验收。

## 2. 当前发布策略

### 2.1 临时生产路径

在独立运维平台满足第 2.2 节的准入条件前，生产业务版本只允许使用“经过验证的直接业务发布”路径：

1. 从 `D:\codex\LeanTPM` 的干净 `main` 构建可追溯产物。
2. 完成只读发现、数据库身份与 V50 校验、备份和 `PlanOnly`。
3. 向操作人展示版本、提交、包摘要、计划摘要、备份和回滚目标。
4. 获得针对精确 `PlanSha256` 的最终确认。
5. 使用同一份已验证计划执行，不在执行时重新生成计划。
6. 失败时先恢复旧 Backend 和 Caddy；只有服务、监听、readiness 和公网 HTTP 全部恢复，才可声称回滚完成。
7. 已完成前置步骤但在切换阶段失败时，优先使用绑定原计划、原备份和现场证据的恢复执行器；不得重新做一套不相关的发布。

当前已验证基线：

- 当前生产版本：`1.0.2-20260811.1`。
- 回滚版本：`1.0.1-20260809.1`。
- 数据库仍为 V50，本次不包含迁移。
- Backend 固定监听 `127.0.0.1:18080`。
- Caddy 继续同时提供公网 HTTP 和内部 CA 的 IP HTTPS；在公共域名和公共证书就绪前不强制 HTTP 跳转 HTTPS。

### 2.2 独立运维平台禁止进入生产发布的条件

`LeanTPM.OpsControl` 和 `LeanTPM.ReleaseAgent` 在下列条件全部满足前，不得承担生产发布，不得用“继续试一次”代替验证：

- 在与生产同版本的隔离 Windows WORKGROUP 主机上，至少连续完成 3 次全新 bootstrap，且每次从干净状态开始。
- 至少连续完成 3 次 `1.0.1 -> 1.0.2` 模拟发布、3 次无变更重复发布拒绝或 no-op 验证。
- 至少完成 3 次故障注入回滚，覆盖 Backend 启动失败、Caddy 切换失败和 Agent 中途退出。
- 通过 PowerShell 5.1 实机验证；不能只在 PowerShell 7 或 AST 解析器中通过。
- 安装包清单、toolkit lock、WinSW 模板、Java 路径和摘要全部来自服务器实际环境并通过复核。
- 服务安装、ACL、SCM、队列重放、双签名、失败补偿、重启后恢复和审计证据全部通过。
- 运维平台自身版本升级已有独立、可重复、可回滚的 bootstrap 方案。
- 由人工完成一次发布演练评审，并明确批准从直接发布切换到运维平台。

任何一项未满足，发布结论必须写为 `OPS_PLATFORM_NOT_PRODUCTION_READY`，继续使用第 2.1 节路径。不要在客户等待业务版本时现场调试运维平台。

## 3. 发布前强制门禁

### 3.1 源码与产物

- 唯一开发和构建目录为 `D:\codex\LeanTPM`。
- 当前分支必须为 `main`，`HEAD` 必须等于 `origin/main`，工作树必须干净。
- 后端 JAR 必须由固定 Java 21.0.1、Maven 3.9.11 从该提交完整构建。
- 禁止使用服务器上通过 `jar uf` 修改过 class 的 JAR，禁止用手工热修复产物作为新版本基线。
- 所有 ZIP、JAR、manifest、计划和执行器必须先检查长度，再检查 SHA-256；任一不匹配立即停止整个脚本块。
- 校验失败后不得继续执行后续复制命令。PowerShell 应把整段操作放在 `$ErrorActionPreference = 'Stop'` 的单一脚本块中。
- 发布包不得包含密码、Token、DPAPI 密文、生产配置、数据库 dump、日志、PID、`target`、`dist`、`node_modules` 或临时文件。

### 3.2 数据库

- 精确验证 MySQL `@@server_uuid`、数据库名、应用账号身份和最高成功 Flyway 版本。
- 当前生产固定为 V50；不存在 V51 时，运行阶段 Flyway 保持关闭。
- 验证 `leantpm_app@127.0.0.1` 可以登录，不能用 root 作为长期应用账号。
- loopback MySQL 使用 `caching_sha2_password` 时，JDBC URL 必须与生产批准配置一致；目前包含 `sslMode=DISABLED&allowPublicKeyRetrieval=true`。这只适用于同机 loopback，不可照搬到远程数据库。
- 发布前创建数据库备份并记录大小和 SHA-256；备份存在不等于可恢复，至少要做 SQL 结构和组件覆盖检查。
- 没有迁移的应用回滚不得修改 Flyway history，不得删除 V49/V50 表或数据。

### 3.3 Windows 服务、秘密与 ACL

- Backend 服务账号保持 `NT AUTHORITY\NetworkService`，Caddy 当前为 `LocalSystem`；不得在发布中顺手修改账号。
- `db-password.bin` 和 `jwt-secret.bin` 使用 LocalMachine DPAPI，并必须允许 Backend 服务身份读取；禁止输出明文或重新生成。
- 新 release 的 Backend JAR 必须允许 Backend 服务身份读取；Web 目录必须允许 Caddy 实际服务身份读取。
- 不能只检查目录 ACL；必须以服务实际身份或实际服务进程完成 JAR/`index.html` 读取和启动验证。
- WinSW 的 SCM 状态 `Running` 只代表 wrapper 运行；还要检查 Java 子进程、唯一 loopback 监听和 readiness。
- 服务 wrapper PID 与 Java 监听 PID 不同是正常现象。停止或恢复时必须确认孤儿 Java 进程和端口占用。

### 3.4 Caddy 与公网访问

- 候选 Caddyfile 必须先 `fmt`、再 `validate`，验证通过后才替换正式配置。
- SPA 必须配置正确的版本化 Web root、`try_files {path} /index.html` 和 `file_server`。
- 先验证物理 `index.html` 可读，再验证本机 HTTP 首页、品牌 API 和公网 HTTP。
- Caddy 404 通常表示路由/root 不匹配；403 通常先检查文件和父目录 ACL，而不是反复改路由。
- PowerShell 5.1 的变量名不区分大小写，禁止使用 `$home`、`$host` 等与内置只读变量冲突的名称。Caddy 首页响应变量统一使用 `$homeResponse`、`$homeStatus`。
- 回滚代码必须和正向切换代码执行相同的 PowerShell 5.1 检查，不能只测试成功路径。
- 当前普通用户使用 HTTP；内部 CA 的 IP HTTPS 只对安装了内部根证书的客户端可信，不能要求每个普通用户安装内部证书。

## 4. 已确认的坑与永久修正规则

| 已发生问题 | 根因 | 永久规则 |
|---|---|---|
| 0 字节 hotfix JAR/ZIP 被复制 | 文件传输失败后，后续命令仍被人工继续执行 | 长度和 SHA-256 任一失败立即终止整个脚本块；不得逐段继续粘贴 |
| Mapper bean 缺失 | `@MapperScan` 合同不正确 | 从源码完整构建并限定实际 mapper 包；发布前用隔离启动验证全部 Bean |
| `DatabaseIdempotencyStore` 无默认构造器 | 构造器注入合同在生产 JAR 中未被 Spring 正确识别 | 源码保留明确构造器注入并用完整 package + 隔离启动验证，禁止生产 class 热补丁 |
| 验证码废弃路径返回 401/409 而不是 404 | Security/幂等过滤器先于不存在路由处理 | 健康验收使用受支持 API；废弃路径只作为安全合同专项测试，不作为发布成功门禁 |
| `Public Key Retrieval is not allowed` | MySQL 8 `caching_sha2_password` 与 loopback JDBC 参数不匹配 | 使用受控 loopback JDBC 参数，并独立验证应用账号；不通过时不启动 Flyway |
| `leantpm_app` 密码多次不同步 | MySQL host identity、应用配置和 DPAPI 文件未作为同一合同验证 | 同时验证 `user@host`、真实登录、DPAPI 写入结果和服务身份读取；不输出密码 |
| Backend 服务反复停止 | NetworkService 无权读取秘密或 release JAR | 发布前检查秘密/JAR ACL，并以服务启动 + readiness 证明，不只看 `icacls` 文本 |
| Caddy 首页 404/403 | Web root 指向错误版本或 Caddy 对父目录/文件无读取权 | 候选配置绑定精确 release；逐级 ACL + 物理文件 + HTTP 三层验证 |
| 品牌 JSON 在 PowerShell 中乱码并解析失败 | 响应编码被错误转换 | 使用 `curl.exe` 保存原始字节，并以严格 UTF-8 读取后再 `ConvertFrom-Json` |
| 新窗口找不到历史脚本或变量 | `Get-History` 和会话变量不跨 PowerShell 窗口 | 生产执行器必须保存为带摘要的文件，不依赖 History、剪贴板或临时会话变量 |
| bootstrap manifest 文件集不一致 | 运行生成文件混入受签名输入目录 | 输入 kit 保持只读；计划、日志和运行文件写到独立 evidence 目录 |
| PowerShell 5.1 报“参数类型不匹配” | `@($serviceSddlSnapshots)` 触发 5.1 dynamic binder 问题 | 所有部署脚本必须在 Windows PowerShell 5.1 实机执行正向和补偿路径测试 |
| pinned Java missing/changed | 构建机 Java 路径/摘要被带到服务器 | 计划使用服务器实际固定 Java 路径和摘要；构建工具链与服务器运行工具链分别锁定 |
| bootstrap 缺 WinSW XML 模板 | toolkit manifest 未覆盖安装器运行时依赖 | 对 kit 做“从空目录解压后 PlanOnly + Execute”闭环测试，不能只校验文件摘要 |
| toolkit lock unsafe/duplicate | 路径规范化和重复项规则未在真实 kit 上验证 | 生成 lock 后用与服务器相同的 PowerShell 5.1 消费一次；任何重复或不安全项在上传前消除 |
| 直接发布在 Caddy 切换时报 `$HOME` 只读 | 使用 `$home`，PowerShell 变量不区分大小写 | 禁用内置变量近似名；执行器和回滚器都运行 PSScriptAnalyzer/5.1 实机冒烟 |
| 失败回滚也因 `$HOME` 中断 | 回滚分支没有与主流程共同测试 | 故障注入必须真实进入每一个补偿分支；没有回滚证据不得批准生产执行 |
| 剪贴板 Base64 不完整 | 大脚本依赖跨机器剪贴板传输 | 执行器使用文件/ZIP传输并校验 SHA-256；Base64 仅作小文件应急且必须校验长度与摘要 |

## 5. 推荐执行顺序

```text
干净 main 与工具链复核
→ 全量构建和测试
→ 包长度/摘要/secret scan
→ 服务器只读发现
→ MySQL UUID/V50/账号校验
→ DB、附件、配置、指针、Caddy 和服务证据备份
→ 上传 staging 后重新计算摘要
→ 生成候选 Backend starter 和 Caddyfile
→ PlanOnly（绑定 PlanSha256）
→ 人工最终确认精确 PlanSha256
→ 切换 Backend
→ readiness + branding
→ 切换 Caddy
→ 本机和公网 HTTP + branding
→ 业务冒烟
→ 保存证据与更新本文
```

发布成功必须同时满足：

- Backend 服务 Running；
- Java 只监听 `127.0.0.1:18080`；
- readiness 为 `UP`；
- Backend branding 为 `OK`；
- Caddy Running；
- 公网 HTTP 首页为 200；
- 公网 branding 为 `OK`；
- 数据库版本和 UUID 未漂移；
- 备份、计划、产物和证据摘要可追溯。

## 6. 失败、恢复与重试纪律

- 同一失败最多尝试三轮；第三轮仍失败就停止，不能在生产主机继续探索式修补。
- 失败现场、备份和日志不得覆盖。新一轮使用新的 evidence 子目录。
- 恢复后必须证明旧 starter、旧 Caddy root、旧 Java 命令行、readiness、公网 HTTP 和 branding 全部恢复。
- 如果正向切换尚未修改数据库，恢复时保持数据库原版本，不做逆向迁移。
- 恢复完成后，只有根因已在源码中修复、通过相同版本 PowerShell 的故障路径测试，并生成新的 `PlanSha256`，才允许再次发布。
- 特例：已验证的恢复/续跑执行器可以继续使用原 `PlanSha256`，但必须绑定原备份、原 evidence 和已完成步骤，且先证明系统已经安全回滚。

## 7. 每次发布必须追加的记录模板

```markdown
### YYYY-MM-DD / <releaseId>

- Git main commit：
- 当前版本 / 目标版本：
- 数据库版本 / 是否含迁移：
- Plan SHA-256：
- Backend SHA-256：
- 备份路径与 SHA-256：
- 执行方式：直接业务发布 / 运维平台（仅在准入后）
- 中断时间：
- 成功门禁结果：
- 失败阶段与根因（无则写“无”）：
- 是否触发回滚/续跑：
- 新增永久规则：
- EvidenceRoot：
- 最终状态：
```

## 8. 版本发布记录

### 2026-08-11 / 1.0.2-20260811.1

- Git main commit：`22a2496987e093783cbc700fe838fb58ee297566`（1.0.2 业务包冻结提交）；不得用服务器手工热修复 JAR 作为来源。
- 当前版本 / 目标版本：`1.0.1-20260809.1` → `1.0.2-20260811.1`。
- 数据库版本 / 是否含迁移：V50 / 无迁移。
- Plan SHA-256：`b213e6c3414d34240b595b3e711cec92d1346095adaaca0e787b0bdac8782c10`。
- Backend SHA-256：`a748f269bcab625ca6a134bc17ab5efe8738694b98735638de7af3b96a2d3bf3`。
- 数据库备份 SHA-256：`eb3759b9712e68535f32ff18f905bac8fd2d83fc224d93a12bf046d68cd676da`。
- 执行方式：经过验证的直接业务发布；运维平台 bootstrap 未通过，未用于业务切换。
- 首次失败阶段：Caddy Web 切换。
- 根因：执行器使用 `$home`，与 PowerShell 只读 `$HOME` 变量冲突；原回滚分支存在同一缺陷。
- 恢复结果：Backend 与 Caddy 恢复到 1.0.1，readiness、首页和品牌 API 均正常。
- 续跑结果：绑定原计划、原备份和原 evidence 的恢复执行器完成 Backend/Caddy 切换。
- 最终门禁：Backend Running、readiness `UP`、Backend branding `OK`、Caddy Running、公网 HTTP 200、public branding `OK`、数据库 V50。
- EvidenceRoot：`D:\LeanTPM\Runtime\logs\direct-deployment-20260811-101213\resume-20260811-102008`。
- 最终状态：`DIRECT_BUSINESS_DEPLOYMENT_PASS`。
