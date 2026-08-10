# 数据库迁移、备份与恢复生命周期

## 1. 迁移前置条件

生产迁移前必须同时满足：

- release-manifest 验签和逐文件哈希通过；
- 目标 host/database 与批准计划精确匹配；
- `flyway_schema_history` 无失败、无 checksum 漂移，当前版本位于兼容矩阵；
- 有同一恢复点的数据库、附件、配置引用和 release 指针备份；
- 备份集清单与哈希已验证；
- 磁盘、连接、锁等待、维护窗口和旧版本可用性通过；
- migrator 使用短期最小权限凭据，密码不进入 argv、日志或报告。

业务 Backend 不承担生产自动迁移。发布流程使用独立 Flyway CLI/受审查 migrator，先 `validate` 后 `migrate`。`baseline-on-migrate` 在生产关闭。

## 2. 备份集结构

```text
backup-<id>/
├─ backup-manifest.json
├─ backup-manifest.p7s
├─ database/
│  └─ database.sql[.encrypted]
├─ attachments/
├─ config/
│  ├─ effective-config.json             # 严格非敏感/引用值合同
│  └─ secret-references.json
├─ release/
│  └─ release-manifest.json
├─ pointers/
│  ├─ current-release.json
│  └─ previous-release.json
└─ protection/profile.json
```

Secret 引用可以备份，Secret 值和私钥默认不备份。若客户要求 Secret 灾备，应由企业 Vault/KMS 的独立导出/复制流程处理，不由发布脚本明文打包。

`backup-manifest.json` 记录 backupId、环境标记、源版本/schema、时间窗口、DB server UUID、附件一致性策略、配置引用、每个子项状态、哈希、加密和保留策略；生产清单必须有固定证书的 detached CMS 签名。任何子项失败时整套状态为 INVALID，阻止发布。保护 profile 只是主机声明，必须以加密卷和离机复制系统的独立证据复核。

## 3. 附件一致性

推荐在维护窗口暂停业务写流量后：

1. 记录 DB 一致性快照点；
2. 复制附件到唯一不可覆盖目录，不跟随重解析点；
3. 生成逐文件路径、大小和 SHA-256；
4. 记录复制期间变化并在必要时进行第二轮增量；
5. 只有 DB 与附件清单共同验证后才把备份标为 VALID。

对象存储/NAS 环境应使用存储产品快照，而不是把大规模附件压成单个 ZIP。

## 4. 恢复规则

- 默认只允许 `NON_PRODUCTION` 标记的已存在空数据库与空附件目录。
- 数据库名、host、server UUID、路径和 backupId 必须二次确认；拒绝自由 SQL。
- 恢复前验证 manifest、签名/哈希、加密、schema 与目标应用兼容。
- 恢复到新库/新目录，完成 Flyway、核心表计数/关系、管理员认证、附件抽样和业务读取后再切换连接。
- 不在原生产库执行覆盖式恢复。`AllowNonEmpty` 不进入普通控制面；break-glass 需双审批和专项恢复计划。

## 5. 回滚与前滚

- migration 未开始：中止发布，旧版本继续。
- Expand 已成功：保留新 schema，可回切兼容旧应用；优先前滚修复。
- Migrate 失败：暂停 checkpoint job，调查后续跑或运行幂等补偿；不盲目反向 SQL。
- Contract 或数据损坏：停止写入，在新隔离目标恢复升级前备份，验证后切换；记录 RPO/RTO。
- 永不删除或手工修改 Flyway history 来伪造回滚。

## 6. 隔离演练矩阵

- fresh V1→当前 schema，再次 migrate 为 no-op；
- 代表性 V32、V37、V44 快照→当前 schema；
- checksum 篡改、迁移中断、锁等待、磁盘不足；
- DB/附件不可用、备份复制失败、恢复文件损坏；
- 新旧应用与新旧 Web/APP 组合；
- DB+附件+配置引用同恢复点重读；
- RPO/RTO 计时、精确资源清理和生产未触达证明。

任何真实写入演练都需要额外授权，且必须使用唯一命名、可证明为非生产的目标。
