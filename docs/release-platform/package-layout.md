# 发布包布局、来源证明与签名流程

## 1. 固定 ZIP 布局

```text
LeanTPM-<releaseId>.zip
├─ release-manifest.json
├─ release-manifest.schema.json
├─ release-manifest.p7s                 # STAGING/PRODUCTION 必须
└─ payload/
   ├─ backend/leantpm-backend.jar
   ├─ web/index.html                    # 以及清单列出的静态资源
   ├─ app/LeanTPM.apk
   ├─ database/migrations.json
   ├─ database/migrations/V*.sql        # 仅 schemaFrom→schemaTo 增量
   └─ operations/compatibility-matrix.json
```

ZIP 根目录只允许 manifest、受信 schema、可选的 manifest detached signature 和 `payload/`。payload 中每个文件必须恰好出现在 manifest；路径、大小和 SHA-256 均匹配。额外文件、大小写碰撞、重解析点、`..`、Windows 设备名、超限压缩比或磁盘余量不足均拒绝。

`release/sample-package` 只是无客户资料的合成 TEST fixture，其中 `.jar`/`.apk` 不是可运行制品，不得交付或部署。

## 2. 来源闭环

1. 在 clean checkout 上运行 `Get-LeanTpmReleaseBaseline.ps1`，锁定 commit、Git tree 摘要和 `SOURCE_DATE_EPOCH`。
2. 使用 `release/toolchain-lock.json` 中固定的 Java/Maven/Node/npm/Gradle/HBuilder/WinSW/Caddy 版本与摘要构建。任何 `null`/`*_REQUIRED_BEFORE_RELEASE` 状态均阻止正式发布或 Windows Service 安装。
3. Web 必须 `npm ci`；后端测试与打包使用固定 Maven；canonical APP 是 `LeanTPM-APP` 的正式签名 APK，旧 Capacitor APK 只作兼容回归。
4. `New-MigrationCatalog.ps1` 只消费逐迁移 `APPROVED` 分类证据，生成连续顺序与 SQL 哈希；整体阶段取最严格项。
5. `New-ReleaseManifest.ps1` 只能从上述 baseline 和实际 payload 字节生成清单。TEST 可显式无签名；STAGING/PRODUCTION 只输出 `AWAITING_SIGNATURE` 候选。
6. 在隔离签名区运行 `New-LeanTpmDetachedCmsSignature.ps1`，对 manifest 的精确字节生成 CMS-SHA256 detached signature。私钥不进入仓库、包、服务器或报告。
7. `New-ReleasePackage.ps1` 在打包前后都校验，并拒绝覆盖同名 ZIP；同一输入与时间戳必须字节一致。
8. `verify-release.ps1` 将正式 APP 的包名、versionName、versionCode、签名证书摘要与 bundle 内 APK 字节做闭环比对。跳过 DB、正式包、canonical APP 或依赖审计时只能得到 `NOT_RELEASEABLE`。

## 3. 签名与信任

- manifest、生产双审批和备份 manifest 使用不同用途/身份的证书；信任指纹来自 ACL 保护的主机配置，不来自发布计划。
- CMS 校验包括精确内容、单 signer、固定指纹、有效期、Code Signing EKU（发布与审批）、证书链和在线吊销检查。
- 生产计划由发起人和审批人的两个不同证书对同一计划文件字节分别签名；同一人/证书不能完成双角色。
- 签名时间戳、证书轮换、吊销不可用时的处置由 PKI 运行规程批准。没有可验证时间戳的过期签名默认拒绝，不能通过关闭校验绕过。
- 服务器不持有发布私钥。备份签名使用独立本机/备份身份私钥，备份恢复时仍需主机固定的 signer 指纹。

## 4. 当前明确阻断

- WinSW 版本/摘要仍未由用户批准，正式安装 fail closed。
- HBuilder 编译器版本已识别，但完整目录摘要在本机两次 120 秒门限内未完成，正式 APP 构建 fail closed。
- 正式 APP 历史最高 versionCode、签名证书摘要和真实 APK 尚未确认。
- 兼容矩阵当前为 `CANDIDATE_UNVERIFIED`；只有隔离环境矩阵通过后才能改为 `SUPPORTED`。
