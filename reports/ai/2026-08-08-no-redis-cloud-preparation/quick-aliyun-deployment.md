# LeanTPM 阿里云快速部署路径

状态：`LOCAL_ARTIFACT_READY / CLOUD_AUTHORIZATION_REQUIRED / NOT_PRODUCTION_SIGNED`

当前部署工具包：

- 文件：`.tmp/LeanTPM-1.0.1-deployment-kit-final.zip`
- SHA-256：`5518e6f578a16983f3c35103917d0520bf1474cabb23cef9a7c4fecdf8830c9d`
- 清单文件：311；数据库迁移：V1–V50；私钥文件：0。
- 后端 JAR SHA-256：`a5e9ef010040bd7ebe2d8883c82b034e10242be7ad52077f6db5a6701721368f`。

## 必需输入

- 用户明确确认无 Redis 版本可以进入云端部署阶段。
- 已认证的 RDP 或 WinRM 会话；密码、Token、Cookie、私钥不得写入聊天或报告。
- 正式域名；没有域名时只允许保留现有 HTTP 健康检查，不宣称 HTTPS 完成。
- 目标 MySQL `@@server_uuid`、CA 文件和 `VERIFY_IDENTITY` 连接证据。
- 目标 Java 与既有 Caddy 可执行文件的实际路径和 SHA-256。

## 只读盘点合同（L4）

Given 已认证的目标 Windows 主机或明确的 PlanOnly fixture，When 执行阿里云部署盘点，Then 只输出脱敏 JSON：Java、`caddy`、`MySQL80`、D 盘容量、固定关键目录，以及 80/443/18080/3306/15173 的监听地址/PID。盘点不得创建目录、写注册表、启停服务、连接数据库或更改防火墙；任何必需查询失败都返回非零并阻断后续写入。

## 最短执行顺序

1. 只读盘点 `D:\LeanTPM`、MySQL80、caddy、80/443/18080/3306/15173 监听、ACL、磁盘和既有配置；不修改任何状态。
2. 将部署工具包上传到 `D:\LeanTPM\packages`，先比对工具包 SHA-256，再解压到新的临时目录。
3. 备份现有 MySQL、附件、配置和 Caddyfile 到 `D:\LeanTPM\backups`；失败立即停止。
4. 在隔离的新数据库执行 V1→V50、重复迁移、checksum 和无 Redis 启动/认证测试；不触碰现有业务库。
5. 将真实 JAR、Web 和迁移文件复制到新的不可变 release 目录；校验逐文件 SHA-256。
6. 生成非秘密 `leantpm.env`；数据库/JWT 等秘密只使用受 ACL 保护的 DPAPI 引用。
7. 迁移最终生产数据库后再切换 Backend pointer 与 Web current；18080 继续只绑定 loopback。
8. 使用已 pin 的 WinSW 2.12.0 注册 `LeanTPM.Backend`，确认重启后 readiness、版本和 schema=50。
9. 原子更新既有 `caddy` 配置：Web root、`/api` loopback 代理、上传文件、日志轮转和安全头；验证后再 reload。
10. 验证 PC、APP、登录/注销/重登、令牌撤销、锁定、幂等、附件和代表性业务流；确认 18080/3306/15173 未公网监听。

任何备份、迁移、服务、Caddy 或健康门禁失败时，保持流量隔离并停止继续，不猜测性覆盖或删除数据。
