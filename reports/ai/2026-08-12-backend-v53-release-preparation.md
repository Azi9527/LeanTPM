# Backend 1.0.4 / V53 独立发布准备

## 任务与授权

- 目标环境：云生产 `8.163.66.164`。
- 授权范围：仅发布 Backend，并将数据库从 V52 升级到 V53。
- 明确不在范围：Web、APP、内网开发服务器、生产推送及任何未列明的外部系统变更。
- 用户已授权创建 `codex/backend-v53-release` 独立分支/工作树并提交本次 Backend/V53 文件；未授权 push。
- 用户已授权在本机创建、删除唯一命名的隔离 MySQL 测试库，用于 V52→V53 迁移及恢复演练。
- 在生产门禁全部通过、生产预检和新鲜备份完成前，不触达云生产。

## 验收标准（Given / When / Then）

1. Given 云生产仍运行 Backend/Web `1.0.4-20260812.1` 且数据库为 V52，When 准备 Backend/V53 候选，Then 候选只包含 Backend、V53 迁移和必要发布元数据，不包含 Web/APP。
2. Given V52 的 `inspection_abnormal.final_result` 已有历史数据，When 执行 V53，Then 新增 `cause_analysis`、`permanent_countermeasure` 两个可空字段，旧历史结果不丢失。
3. Given 生产 Web 仍为 1.0.4，When 它继续调用旧异常处理合同，Then Backend 仍接受 `finalResult`、`targetStatus` 并保持旧状态流转；新 APP 可使用仅登记措施的新合同。
4. Given 唯一命名的隔离 MySQL 测试库，When 从 V52 升级到 V53并再次执行 Flyway，Then 首次迁移成功、历史结果可回读、第二次为 no-op，并清理隔离测试库。
5. Given V53 属于数据库变更，When 生产发布，Then 必须先取得停写后的新鲜备份、哈希/组件校验和隔离恢复证据；失败时按 `RECOVERY_REQUIRED` 恢复数据库，而不是把仅切回旧 JAR 误称为完整回退。
6. Given 现有通用发布器会切换 Web，When 实施本次发布，Then 不使用该路径；必须先有经评审与演练的 Backend-only/V53 操作路径和绑定到精确字节的 PlanOnly 证据。

## 风险与治理

- 风险等级：L4（生产发布 + 数据库迁移）。
- 实施/测试/发布所有者：主代理；仅在用户已授权范围内工作。
- 独立审查：`release_review`，只读。其结论为当前 NO-GO，必须补齐真实 MySQL、Backend-only 操作器、新鲜备份、生产预检、签名候选包和回退/恢复证据。
- GitNexus 影响分析：`InspectionTaskService.handleAbnormal` LOW（2 个直接上游）；`InspectionController.handleAbnormal` LOW；`InspectionDtos` LOW（3 个直接、1 个间接）；MyBatis XML 动态符号无法可靠建图，使用合同测试与 MySQL 集成测试补偿。

## 最小迁移设计

V53 只执行一次加列：

```sql
ALTER TABLE inspection_abnormal
    ADD COLUMN cause_analysis VARCHAR(2000) NULL AFTER due_time,
    ADD COLUMN permanent_countermeasure VARCHAR(2000) NULL AFTER temporary_action;
```

不做全表回填。读取时使用 `COALESCE(permanent_countermeasure, final_result)` 兼容 V52 历史数据；新写入同时镜像新旧结果列，以保持现有 PC Web 合同。

## 当前证据

- 独立工作树：`D:\codex\LeanTPM-backend-v53-release`，分支 `codex/backend-v53-release`。
- 文件边界：Backend、V53、发布元数据/测试/报告，以及新增的版本锁定 Backend-only/V53 操作器；无 Web、APP 修改。
- `git diff --check`：PASS。
- Backend 全量：222 项，0 失败；66 项 MySQL/恢复演练测试因合格隔离数据库尚未提供而跳过，不能算作真实数据库通过。
- 发布平台合同：80/80 PASS。
- 版本一致性：PASS，schema 53，迁移总数 53。
- V50→V53 迁移目录：PASS，3 个连续脚本，阶段 MIGRATE。
- 示例清单字节完整性：PASS，8 个工件（仅测试清单，未签名，不可用于生产）。
- Backend-only/V53 操作器合同：Windows PowerShell 5.1 PASS；确认 Plan SHA 前不写入，只停止/启动 Backend，不切 Web/APP/Caddy，失败回退级别为 `RECOVERY_REQUIRED`。
- 发布包兼容门禁：PASS；兼容矩阵仍为 `CANDIDATE_UNVERIFIED` 时，构建器会在编译、创建运行目录和产包前拒绝执行。
- 操作器 JAR 检查：PASS；JAR 内 V53 SQL 与独立审核 SQL 逐字节一致。
- 隔离恢复演练合同：Windows PowerShell 5.1 PASS；使用两个唯一命名库，覆盖 V52 备份、V53 升级、二次 no-op、恢复回 V52、恢复后再次升级和 finally 清理。
- GitNexus `detect-changes(compare main)`：索引内 29 个文件、62 个符号，风险 LOW，未命中执行流；新增未跟踪的 SQL、PowerShell、测试和报告不在该统计内，已另按文件边界和合同测试复核。
- 候选源码冻结在独立分支中；本报告随授权提交保存，候选标识以该提交的 `git rev-parse HEAD` 为准。
- 本机环境阻塞：MySQL80 自动生成的服务器证书没有 `127.0.0.1/localhost` SAN，CN 也不是本机名；不能把降级到 `VERIFY_CA` 当作 `VERIFY_IDENTITY` 通过。

## 尚未完成的生产门禁

- 提供本机隔离 MySQL 测试账户，运行全部 MySQL 集成测试和 V52→V53/二次 no-op/恢复演练。
- 演练完成后才把兼容矩阵从 `CANDIDATE_UNVERIFIED` 提升为 `SUPPORTED`。
- 在证书主机名匹配的非生产 MySQL，或另行获批的临时隔离 MySQL 实例上，实际执行 Backend-only/V53 成功/恢复演练。
- 生成生产 Backend JAR、迁移目录、签名 manifest、PlanOnly/Plan SHA。
- 云生产只读预检、停写、新鲜备份、校验及隔离恢复。
- 迁移后验证 schema 53 readiness、设备状态 API、异常措施保存/回读和旧 PC 异常流程。
