# 第一阶段环境门禁与授权清单

## 当前门禁

第一阶段只有在仓库门禁与以下环境证据全部通过后才能标记完成。当前无 Redis 代码离线门禁已通过，但 D 盘生产布局/既有 Caddy 适配仍在开发；所有外部写入演练均未获授权，状态为 **REPOSITORY_WORK_IN_PROGRESS / BLOCKED_BY_ENVIRONMENT / NOT_RUN**，不是测试通过。

| 门禁 | 所需隔离资源或输入 | 必须产出的证据 | 失败时的安全动作 |
|---|---|---|---|
| 数据库迁移 | 专用非生产 MySQL、低权限 app/migrator/backup/restore 账号、固定 `@@server_uuid`、唯一临时库前缀、MySQL CA PEM 与 Java trust store | fresh V1→V50、代表性 V48→V50、二次 no-op、checksum 篡改、迁移中断/前滚恢复、TLS 主机名/CA 失败、N/N-1 兼容 | 不切 pointer；保留 Flyway/备份证据；只清理本轮唯一资源 |
| 完整备份恢复 | 空隔离目标、加密备份卷、离机复制测试目标、附件/配置脱敏 fixture、测试备份签名证书与双审批证书 | 签名清单、DB+附件一致性窗口、篡改拒绝、恢复重验、sandbox-only 配置、真实启动/登录/核心读取、RPO/RTO | 标记 `RESTORE_INVALID` 或 `DATA_RESTORED_PENDING_APPLICATION_E2E`，隔离部分恢复目标，不猜测性覆盖/删除 |
| Windows Service 与既有 Caddy | 隔离 Windows Server、管理员安装窗口、Backend 与 Caddy 独立服务身份、固定 Java/WinSW/Caddy 版本+SHA、受保护 D 盘 HostLayout | `D:\LeanTPM\App`/`Data` ACL、Backend install/start/stop/restart/upgrade/uninstall；既有 `caddy` 的 SCM/image/account/SDDL/config/listener 绑定；重启、路径空格、端口占用、越权和超时恢复 | 保留旧 release；失败停止 Backend；真实公网 listener 未隔离则 CRITICAL；禁止并存 `LeanTPM.Proxy` |
| 发布/回滚 E2E | 已签 bundle、双审批测试证书、固定主机 trust、隔离 MySQL/附件、HTTPS 入口；不提供 Redis | 审批→备份→迁移→切换→readiness/info→恢复/前滚→审计，含并发/断电/坏包故障注入 | fail closed；兼容类失败才允许应用回切；V50/非兼容 schema 进入 `RECOVERY_REQUIRED` |
| HTTPS/Secret | 隔离域名和证书、Windows 防火墙、DPAPI 或批准的 Vault、轮换测试 | TLS 链/SAN/HSTS/安全头、18080 外部不可达、Secret 不出现在 argv/env/report、证书过期失败 | 不启动或 readiness 失败；绝不回退 HTTP/明文 Secret |
| canonical APP | 已确认最高正式 versionCode、固定 HBuilder 编译器目录摘要、企业签名流程、测试设备 | 两次 clean build、APK signer/包名/versionName/code、包内字节一致、覆盖升级/强更/最低版本 | 不生成正式 manifest，不发布假 APK |

## 进入演练前必须由用户确认

1. 分别确认隔离 Windows Server 与隔离 MySQL 的精确主机/实例标识；数据库授权必须包含 `@@server_uuid`、脚本生成的唯一数据库前缀和最终清理责任人。
2. 当前线上/客户已发布最高 APP `versionCode`，避免候选 `101` 违反单调递增。
3. Java 21、WinSW 与 Caddy 的批准版本、来源与 SHA-256；当前 lock 中三者状态均为 `PIN_REQUIRED`，不会执行真实 Backend/Proxy 安装。
4. HBuilderX 批准安装目录及可信目录摘要；当前编译器 digest 为 `DIGEST_REQUIRED`，正式 APP 构建 fail closed。
5. manifest、审批、备份、starter 与 APP 各自的证书用途、信任指纹、轮换/吊销/时间戳策略；服务器不得持有发布私钥。
6. Backend 与既有 Caddy 的独立服务身份、`D:\LeanTPM\App`/`D:\LeanTPM\Runtime`/备份目录、host bootstrap 与 layout digest、`publicHost`、域名/证书、Secret provider、MySQL CA/Java trust store 和备份介质。`D:\LeanTPM\data` 已承载 MySQL，禁止作为发布控制面 DataRoot。无 Redis 版本不得要求或恢复 Redis TLS 配置。
7. 测试 fixture 的来源必须为合成或已脱敏数据，禁止纳入客户二进制和旧时效性证据。

## 建议的单独授权范围

建议将授权拆开。下一次最小授权只覆盖一个明确的非生产 MySQL 实例和本机隔离后端进程；Windows SCM/Caddy/证书与云服务器继续保持未授权。数据库授权限定：

- 仅可创建/删除脚本生成并校验的 `leantpm_it_*_migration` 与 `leantpm_it_*_suite` 数据库；
- 仅可写明确的隔离 uploads/backup/evidence 目录并启动/停止本机测试后端；
- 不得安装、启停或修改任何 Windows Service、Caddy、证书信任库或防火墙；
- 禁止任何生产地址、生产账号、客户数据、正式私钥和外部通知系统；
- 每个写动作前再次核对目标、备份、恢复方法和清理清单。

完成仓库内 D 盘/既有 Caddy 适配并取得独立 Windows 授权后，才可只读盘点云端 SCM、PID/listener、ACL、Caddy 配置与卷标识；任何修改仍需再次授权。第一阶段完成前，第二阶段不实施。
