# LeanTPM current handoff

Last updated: 2026-08-13 (Asia/Shanghai)

This is the stable entry point for a new Codex conversation. Read this file first, then read [the user-confirmed simple release memory](LEAN_TPM_SIMPLE_RELEASE_MEMORY.md). Never put passwords, JWT secrets, private keys, or unredacted production data in this file.

## Current production baseline

- Public environment: `http://8.163.66.164/`
- Release: `1.0.4-20260813.1`
- Release source: verified local working-tree Backend/Web build; no commit/push was performed for this release
- Scope actually deployed: Backend and Web only
- APP/APK: excluded; the user packages and uploads Android separately
- Database: MySQL schema V53; the 2026-08-13.1 application release did not modify the database
- Final proof supplied by the operator:
  - `LeanTPM.Backend`, `caddy`, and `MySQL80` are Running
  - `/actuator/health/readiness` returned `UP`
  - server-local readiness returned `UP`
  - `/actuator/info` returned version `1.0.4-20260813.1`, schema `53`
  - deployment ended with `SIMPLE_APPLICATION_SUCCESS`
- Application backup: `D:\LeanTPM\backups\application-1.0.4-20260813.1-20260813-011637`
- Backend JAR: `D:\LeanTPM\App\releases\1.0.4-20260813.1\payload\backend\leantpm-backend.jar`
- Web root: `D:\LeanTPM\App\releases\1.0.4-20260813.1\payload\web`
- APP source now contains direct APK download, progress display, Android installer launch and persistent forced-upgrade gating; APP verification passed 60/60. These APP changes still require the user to rebuild and upload the APK before phones receive them.

The local/internal development server and the public cloud server are different environments. Never infer that updating one updated the other. Identify the target environment before any service, database, or deployment action.

## Functional baseline prepared in 1.0.4

- Inspection schemes reference inspection item IDs; they do not own a second copy of item rules.
- Executable, unsubmitted tasks read the latest inspection-item definition; submission freezes an audit snapshot.
- Changing an inspection item bumps affected editable task versions.
- Item photos are required when `photoRequiredFlag=true` or `photoMinCount>0`.
- Item maintenance keeps the photo-required switch and minimum count consistent.
- Item photos and whole-task photos are counted separately.
- APP source contains photo-required labels/counting and offline/idempotency draft protections, but those changes reach users only after the user rebuilds and installs the APK.
- A published scheme is not executable before its effective date; device applicability, assignment, publication, and date must all be checked when diagnosing “no template”.

## Release problems already learned

1. Replacing only a JAR path is unsafe. Version, schema, Flyway flag, and JAR path must each be generated and verified independently.
2. Windows SCM may return a fully quoted executable path. Accept exact quoted or unquoted paths only; reject extra arguments.
3. Never recursively remove inheritance from extracted release files. Apply inheritable ACLs at the release root and re-hash every artifact.
4. Windows PowerShell 5.1 can corrupt UTF-8 Chinese JSON through console decoding. Health gates should use ASCII-safe fields or byte-safe decoding.
5. A failed target is quarantined, not deleted or reused. Every retry uses a fresh target, backup root, and evidence root.
6. For application-only rollback, verify V52 first, restore and fully verify the previous Backend/JAR next, and only then reopen Caddy/public traffic.
7. Operator packaging must run the Windows PowerShell 5.1 safety regression before producing the final ZIP.

Full evidence and details: [1.0.4 release retrospective and fast lane](2026-08-12-production-1.0.4-release-retrospective-and-fast-lane.md).

## Mandatory next-release route

The user has explicitly chosen the small-system simple release model. The binding details are in [LEAN_TPM_SIMPLE_RELEASE_MEMORY.md](LEAN_TPM_SIMPLE_RELEASE_MEMORY.md), and they supersede the earlier proposal to require an enterprise-style fast-lane platform before every release.

Default operator experience: one verified application ZIP plus one simple PowerShell script, copied to `D:\LeanTPM\temp`, then executed once from Administrator PowerShell. Keep hash checks, fresh target directories, config backup, health checks and automatic application rollback inside that script. Do not require PlanOnly/Plan SHA, multiple operator packages, signed manifests or a control plane unless the user explicitly requests them.

### Boundaries retained from production experience

- Do not reuse the 1.0.3 V50-to-V52 script for a later release.
- Reuse the proven structure, but generate a fresh version directory and bind the exact current/target paths, versions and hashes.
- Do not mix APP/APK into a Backend/Web release unless explicitly authorized.
- Do not deploy untested Backend/Web build outputs.
- Do not touch the database when the release has no schema change.

The enterprise-style generic fast-lane toolkit described in the 2026-08-12 retrospective is optional historical design material, not a prerequisite for routine LeanTPM releases.

## New-conversation starting prompt

Copy the prompt below into a new conversation:

```text
请在 D:\codex\LeanTPM 继续开发 LeanTPM 的简易发布运维功能，并继续处理后续客户反馈问题。

开始前必须完整阅读：
1. AGENTS.md
2. docs/ai/development-workflow.md
3. docs/ai/task-template.md
4. docs/ai/gitnexus-workflow.md
5. reports/ai/LEAN_TPM_CURRENT_HANDOFF.md
6. reports/ai/LEAN_TPM_SIMPLE_RELEASE_MEMORY.md
7. reports/ai/2026-08-12-production-1.0.4-release-retrospective-and-fast-lane.md（仅作为历史故障经验，不得覆盖用户确认的简易发布策略）

当前已知基线：
- 阿里云生产已部署 Backend/Web 1.0.4-20260813.1，数据库为 V53。
- APP/APK 不随 Backend/Web 发布，由我使用 HBuilderX 手工打包并在管理端上传。
- APP 正式包名是 uni.app.UNICEE59D0，当前目标版本是 1.0.11（104）；正式签名必须保持与旧包一致。
- 内网开发服务器与云服务器是两个环境，开始任何服务或数据库操作前必须明确目标环境。
- 不要未经授权部署、推送、提交、修改生产数据库或操作云服务器。
- 保护现有所有未提交内容，不 reset、clean、stash，不覆盖与本任务无关的文件。

用户已经明确确认的发布策略：
- LeanTPM 是小系统，默认发布形态就是 Backend + Web + 必要时一份数据库脚本，APP 单独手工打包。
- 不要把流程做成企业级发布平台，不要默认引入 PlanOnly、Plan SHA、多个 operator 包、签名 manifest、控制平面或审批编排。
- 常规发布只生成一个构建 ZIP 和一个简易 PowerShell 脚本，复制到 D:\LeanTPM\temp，在管理员 PowerShell 执行一个脚本。
- 简易脚本内部保留文件哈希、全新版本目录、配置备份、健康检查和失败自动回滚。
- 没有数据库结构变化就完全不操作数据库；只有 APP 修改就明确告诉我只需重打 APK，不重发 Backend/Web。
- 发布命令要短、可直接复制，不要再次让我临时纠正发布策略。

本次新对话的第一阶段目标：
1. 把已经成功的 1.0.4-20260813.1 简易发布脚本整理成仓库内可重复使用的开发运维功能。
2. 提供一个简单入口，让我选择 Web、Backend、Backend+Web 或 Backend+Web+数据库脚本，并生成对应 ZIP、一个简易部署脚本和一份极短执行说明。
3. 自动完成版本号、产物哈希、PowerShell 5.1 语法、Backend/Web 构建与必要测试检查。
4. 部署脚本继续使用 D:\LeanTPM\App\releases\<版本> 新目录、备份启动脚本/Caddyfile、检查 V53、切换服务并失败自动恢复。
5. 不修改生产环境，除非我在新对话中再次明确授权发布。

后续客户反馈工作方式：
- 我给出具体问题后，只处理该问题，不顺带重构或发布。
- 先核对当前代码是否已有修复；截图涉及 APP 时先核对真实 versionCode、API 地址、包名、签名和接口返回。
- GitNexus-First：陌生代码先 query/context，修改符号前 impact；先写失败测试，再做最小修复。
- 完成 Backend/Web/APP 对应验证，最终用直白语言说明修改内容、测试证据、APP 如何重打包、Backend/Web/数据库是否需要发布。

开始时先执行 git status 和 GitNexus-First 调查，区分已有内容与待开发内容；先输出简短实施计划，再开始开发。
```

## Git and authorization status

- The production deployment described above is complete.
- The release-script hardening and fast-lane documents may still be local uncommitted work; inspect `git status` before editing.
- No future commit, push, deployment, production database write, or service change is implied by this handoff. Each still requires explicit authorization.
