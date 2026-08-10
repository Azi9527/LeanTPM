# 无 Redis 候选版离线验证摘要

结论：**离线代码门禁通过，但候选版本仍为 NOT_RELEASEABLE。**

2026-08-09 本轮在可信基线 `2185536ea9da0a323b27f53dcf849b818ea19069` 的链接工作树中执行：

- 跨仓 Node 合同：82/82 PASS（其中发布平台 71/71、无 Redis 6、认证 E2E 静态合同 1、PC 合同 4）；
- 后端 Maven 离线测试：144 项，84 项实际 PASS，60 项隔离 MySQL 条件测试明确 SKIP；
- LeanTPM-APP：26/26 PASS；
- 独立运维控制面 Maven：49/49 PASS；
- PowerShell AST：63/63 PASS；
- PC Web typecheck/build：PASS（保留既有大 chunk 警告）。

GitNexus 已重建并显示索引与当前提交一致（8,009 nodes / 20,011 edges / 432 clusters / 300 flows）；`detect-changes -s all -r LeanTPM` 报告 78 files / 156 indexed symbols / 12 affected processes / HIGH。新增未跟踪 PowerShell 发布符号与 `ops-control-plane` 符号仍未被完整建模，因此本批继续按 L4 采用源码边界、真实状态机 harness 与独立安全审查治理。

本轮没有设置 `LEANTPM_TEST_DB_URL`，没有连接数据库、网络、Windows SCM 或云服务器。新增的 checksum 篡改、非事务迁移中断/修复前滚、迁移/模块分库与 DROP 失败门禁均已编译或通过离线合同测试，但数据库行为证据必须在获批的隔离 MySQL 上实际运行后才能成立。

完整 82 项 TAP 为 `offline-contracts-current.tap`，SHA-256 为 `a2161e1fd4798457eadecc8eb409c4b42efe292a8834c890ea5c8f6cb4400346`；发布平台 71 项独立 TAP 为 `release-platform-tests-current.tap`，SHA-256 为 `0e07506a3ee745aefcc5ff6b38db58c8ec3c6394d9549a7f1ff365f070cb3049`。旧 TAP 已被本摘要明确 supersede，不得作为当前候选证据。

D 盘 HostLayout、固定 HostBootstrap 与生产根策略已经形成仓库代码候选；离线证据同时覆盖 legacy 只读盘点与非可执行 import-plan 草案。`D:\LeanTPM\data` 仅标记为 `PRESERVE_EXTERNAL`，旧 current/shared/package 内容会在首次安装前及锁内复核时阻断。import plan 始终为 `INPUT_REQUIRED/executable=false`，不能替代可信备份、双审批或执行器。真实主机卷身份、ACL、父链 reparse、既有 Caddy SCM/listener 与防火墙仍未授权验证。

仍需取得的证据：无 Redis 真实启动与 readiness、V48→V50 升级、全部模块 MySQL 回归、认证/注销/锁定/幂等跨重启 E2E、备份恢复及故障注入、Windows Service、正式 HBuilder/签名 APK。上述证据齐备并经用户确认前，禁止初始化云端业务库、注册后端服务或执行云部署。
