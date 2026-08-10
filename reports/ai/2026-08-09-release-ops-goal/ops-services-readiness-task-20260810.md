# Ops 服务安装 Readiness / PlanOnly 门禁

## 基本信息

- 类型：开发（只读基础设施门禁）
- 工作目录：`C:\Users\Mayn\.codex\worktrees\fca1\LeanTPM`
- 基线：`2185536ea9da0a323b27f53dcf849b818ea19069`，用户工作树存在大量既有未提交内容，全部保护。
- 范围：新增 Ops 服务安装 readiness 脚本、发布平台行为测试、最小文档和工具包锁更新。
- 非目标：不安装/启停服务，不改 ACL/SCM/注册表/数据库/Caddy/云端，不提交、推送或部署。

## 需求与 Given / When / Then

- Given 固定 App/Runtime 根、WinSW、Java、Ops JAR/config、两份 starter、完整 toolkit lock、四个不同服务账户和 Agent 身份，When 运行 readiness，Then 只读计算摘要、校验工具包、检查身份/签名/现有服务，并调用既有 installer 的 `-PlanOnly`，返回可直接用于下一 ceremony 的类型化参数和计划。
- Given 任何路径缺失/reparse、账户重复、toolkit 文件漂移、生产 starter 签名或 gMSA 不可证明、现有固定 service ID 已注册，When 运行 readiness，Then 返回 `INPUT_REQUIRED` 与稳定 blocker code，不执行安装或产生文件变化。
- Given 非生产临时根与显式测试开关，When 行为测试运行，Then 可跳过生产 gMSA/Authenticode 证明，但仍验证账户形状、四账户互异、文件摘要和 toolkit 全量摘要；整个临时树前后逐字节一致。
- Given 生产根，When readiness 运行，Then 测试开关不可使用；必须由 HostBootstrap/root policy、正式签名和本机 gMSA 证明共同放行。

## 影响与风险

- GitNexus query 对 PowerShell 文件级流程召回有限；`release-platform.test.mjs` impact 为 exact LOW、0 direct caller、0 process。新脚本没有既有调用者。
- 人工风险：L4/HIGH。原因是输出将成为后续 Windows SCM ceremony 的输入，涉及 gMSA、代码签名和供应链摘要。
- 最小化：不修改 `Install-LeanTpmOpsServices.ps1`；readiness 只计算并把实际摘要传给其 `-PlanOnly`，生产执行仍保持原门禁。
- 回滚：删除新增 readiness 脚本/测试/文档并重建 toolkit lock；不涉及外部状态。

## 多角色与所有权

| 角色 | 任务 | 文件/模块所有权 | 只读 | 可修改测试 |
|---|---|---|---|---|
| 主实现 | readiness 脚本与最小文档 | 新增脚本、本文档、toolkit lock | 否 | 否 |
| 测试 | 行为用例、零副作用和漂移负例 | `scripts/tests/release-platform.test.mjs` | 否 | 是 |
| 安全审查 | 路径、摘要、签名、gMSA、SCM 查询和 bypass 边界 | 全部本批 diff | 是 | 否 |
| 最终复核 | C/D 双仓测试、AST、GitNexus、diff | 全部本批证据 | 是 | 否 |

## 测试矩阵

| 场景 | 层级 | 预期 |
|---|---|---|
| 完整非生产 fixture | PowerShell 行为 | `PLAN_READY`，包含固定两个服务和实际 SHA-256，树零变化 |
| toolkit 文件漂移 | PowerShell 行为 | `INPUT_REQUIRED / TOOLKIT_FILE_HASH_MISMATCH`，树零变化 |
| 重复服务账户 | PowerShell 行为 | `INPUT_REQUIRED / SERVICE_ACCOUNTS_NOT_DISTINCT` |
| 生产测试 bypass | 静态+参数行为 | 明确拒绝 |
| 回归 | Node/PS AST | 发布平台全套与 canonical PS5.1 AST 通过 |

## C 工作树实现与验证

- 新增 `deploy/windows/Get-LeanTpmOpsServicesInstallationReadiness.ps1`；输出仅为 `PLAN_READY` 或 `INPUT_REQUIRED`，报告始终声明 `readOnly=true`、`executable=false`。
- production 分支不允许 `AllowUnverifiedTestHostState`；该开关只有与 `AllowNonProductionRoots` 同时使用时才可构造无 SCM/AD 的行为 fixture。
- readiness 逐文件重算 toolkit lock，检查固定必需入口，并把实际 WinSW/Java/JAR/config/lock 摘要传入既有 `Install-LeanTpmOpsServices.ps1 -PlanOnly`。
- 新锁绑定 68 个 PowerShell 文件；C 锁 SHA-256：`cc410662ab601593a3299ca86e19ac6acf20544b0f0fe994ff197fcb42b8108d`。
- 关键窄测：3/3 PASS（toolkit lock、双服务 PlanOnly、readiness 正/负行为）。
- 完整 `release-platform.test.mjs`：73/73 PASS，0 skip/todo，86.5 秒。
- canonical Windows PowerShell 5.1 AST：69/69 PASS，0 parse error。
- GitNexus FTS 增量索引损坏后按用户授权仅重建当前工作树索引；结果为 8,023 nodes、20,037 edges、433 clusters、300 flows。新增 readiness 文件 impact 为 exact LOW，0 caller/process/module。
- 全工作树 `detect-changes` 仍为 HIGH（156 changed symbols / 78 files / 12 flows），来源是用户既有大范围未提交工作；本批未修改这些业务符号。

## D 源码镜像与最终验证

- 仅同步本批 readiness、测试、两份文档和本报告；覆盖前备份位于 `D:\codex\LeanTPM\reports\ai\2026-08-09-release-ops-goal\pre-readiness-sync-20260810-081529`。
- toolkit lock 在 D 实际源码字节上重新生成并绑定 68 个 PowerShell 文件；D 锁 SHA-256：`a0f51f55c507335401cd7cc299fd4ecce5d13fb0e3032bf92e426492b6b9e9b8`。
- D 关键窄测：3/3 PASS；完整 `release-platform.test.mjs`：73/73 PASS，0 skip/todo，83.4 秒。
- D canonical Windows PowerShell 5.1 AST：69/69 PASS，0 parse error。
- D GitNexus 增量索引成功：8,023 nodes、20,037 edges、433 clusters、300 flows；新增 readiness 文件 impact 为 exact LOW，0 caller/process/module。
- D 全工作树 `detect-changes` 与 C 一致为 HIGH，仍由既有 78 个已改文件/12 个流程构成；本批范围没有进入这些业务流程。
- C/D 本批 5 个镜像源码文件 SHA-256 逐一一致；两侧各自的 68 文件 toolkit lock 都精确绑定 readiness 脚本；两侧 `git diff --check` 均为 exit 0（仅既有 LF→CRLF warning）。
- 既有 D 用户文件 `docs/LeanTPM_客户需求理解与澄清清单_20260803.xlsx` 与 `reports/ai/inspection-regression-20260808.md` 均保留。
- 未安装或启停任何 Windows Service，未修改 ACL/SCM/注册表/数据库/Caddy，也未访问云端网络。
