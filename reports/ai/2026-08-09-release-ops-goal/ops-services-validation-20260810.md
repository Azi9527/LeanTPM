# OpsControl / ReleaseAgent Windows Service 源码验证

日期：2026-08-10（Asia/Shanghai）

## 范围与风险

- 范围仅限独立 `LeanTPM.OpsControl` 与 `LeanTPM.ReleaseAgent` 的 WinSW starter、固定模板、安装/绑定/控制脚本、HostBootstrap 身份合同、文档和离线测试。
- 风险按 L4/HIGH 处理。GitNexus 对新增 PowerShell 文件给出文件级 `LOW / 0 callers`，但 SCM、gMSA、ACL 和生产发布执行权属于人工提升后的高风险边界。
- 本轮没有安装或启停 Windows 服务，没有连接数据库、Caddy 或云端，也没有提交、暂存、推送或发布。

## Given / When / Then 结果

- Given 固定 App/Runtime 根、wrapper/Java/JAR/config/starter/toolkit 摘要，When 运行 `Install-LeanTpmOpsServices.ps1 -PlanOnly`，Then 返回两个固定服务、不同账户、loopback `127.0.0.1:18090` 与 `ExecuteSignedDeployment`，临时树前后完全一致。
- Given 两个服务账户重复或 JAR 摘要漂移，When 运行 PlanOnly，Then 非零退出且临时树保持不变。
- Given 实际安装分支，Then 源码顺序门禁要求先保护 service root ACL、再复制，复制后重算所有绑定摘要并复核 starter 签名，之后才允许 WinSW 注册。
- Given 新注册服务，Then 必须先按 Manual 状态通过完整 binding，再改为 delayed automatic 并按 Automatic 状态复核；安装脚本不启动服务。
- Given 生产卸载请求，Then 当前版本主动 fail closed，直至独立的双 CMS、nonce 和重放门禁完成；非生产只允许精确两个固定 service ID。

## 验证证据

- `node --test --test-name-pattern="toolkit lock|plans isolated OpsControl" scripts/tests/release-platform.test.mjs`：2/2 PASS。
- `node --test scripts/tests/release-platform.test.mjs`：72/72 PASS，0 fail，0 skip，0 todo，82.5 秒。
- Windows PowerShell 5.1 AST：受控正式脚本集合 68/68 PASS，0 parse error。
- `git diff --check`：exit 0；仅报告 6 个既有 LF→CRLF 提示。
- GitNexus 增量索引：8 changed、9 added；全工作树 `detect-changes` 为 HIGH（78 files / 156 symbols / 12 flows），其中包含用户既有未提交变化。四个本批核心新文件逐一 impact 为 exact `LOW / 0 callers / 0 flows`，因此仍按人工 L4/HIGH 边界审查。
- 当前环境没有可用 Maven 可执行文件；本批未修改 `ops-control-plane` Java 源码，Java 测试未伪造为本轮已执行。

## 仍需单独授权的环境步骤

- 在隔离 Windows 主机准备四个不同 gMSA、正式 Authenticode starter、正式 Java/WinSW/JAR/config/toolkit pins。
- 先运行 PlanOnly，之后才可另行审批真实 ACL/SCM 安装演练。
- 演练通过前，不得在当前阿里云生产机安装或启动这两个服务。

## D 盘源码同步与复验

- 已把 16 个明确归属本批的源码/测试/文档文件精确同步到 `D:\codex\LeanTPM`；覆盖前备份了 9 个旧版本，备份位于 `reports/ai/2026-08-09-release-ops-goal/pre-ops-services-sync-20260810-063741`。
- 16 个 C→D 文件逐字节 SHA-256 比较：16/16 一致。
- D 盘工具包锁按 D 盘实际脚本字节重新生成：67 files，0 mismatch，lock SHA-256 `eff86a9db6251268ba5a5be413fe8a51ee07849837c01fa6309e67df97aec7b4`。
- D 盘发布平台完整测试：72/72 PASS，0 fail/skip/todo，81.8 秒。
- D 盘 Windows PowerShell 5.1 AST：68/68 PASS；`git diff --check`：exit 0。
- 用户文件 `docs/LeanTPM_客户需求理解与澄清清单_20260803.xlsx` 与 `reports/ai/inspection-regression-20260808.md` 均确认保留。
- D 盘 GitNexus 旧 FTS 索引发生内部不一致；仅删除并重建 `.gitnexus` 后恢复为 up-to-date：8,022 nodes、20,036 edges、433 clusters、300 flows。
