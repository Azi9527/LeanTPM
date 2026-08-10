# LeanTPM 统一版本与兼容规则

## 1. 单一版本源

仓库以根目录 `VERSION.json` 作为产品版本合同。后端 Maven、Web package/lock、Capacitor Android、新 uni-app package/manifest/运行时回退和发布示例必须与它一致。直接修改派生版本但不修改 `VERSION.json` 会被发布门禁拒绝。

`VERSION.json` 字段：

- `productVersion`：SemVer 产品版本；后端、Web、APP `versionName` 和发布包名称共用。
- `appPackageName`：canonical Android 包名；正式 APK 必须精确匹配。
- `appVersionCode`：Android 严格单调递增正整数；不通过字符串比较推导。
- `minimumSupportedAppVersionCode`：服务端允许的最低 APP 内部版本。
- `databaseSchemaVersion`：当前仓库 Flyway 最大整数版本。
- `releaseManifestSchemaVersion`：release-manifest 合同版本。

候选 `1.0.1/code 101` 只是仓库统一起点。正式发布前必须核对客户实际最高 versionCode，必要时只提升 code，不降低或复用。

## 2. SemVer 规则

格式为 `MAJOR.MINOR.PATCH[-prerelease][+build]`：

- MAJOR：破坏现有外部合同或要求不可兼容迁移；
- MINOR：向后兼容功能；
- PATCH：向后兼容修复和运维强化；
- prerelease：`alpha`、`beta`、`rc.N` 等不可视为正式生产；
- build metadata：同一源版本的构建标识，不改变版本优先级。

相同 `productVersion` 的制品不可静默替换。重建必须产生新的 releaseId/build metadata，并保留旧 manifest 与摘要。

## 3. 数据库版本

数据库版本沿用不可变 Flyway `V<number>__description.sql`。禁止：

- 修改已应用迁移；
- 删除或重排历史版本；
- 用 `baseline-on-migrate` 自动收编未知生产库；
- 把 Contract 收紧与 Expand 放在同一发布。

release-manifest 必须记录 `schemaFrom`、`schemaTo`、迁移顺序/哈希、阶段、是否需要停机/备份、旧应用兼容性和 rollback class。

## 4. Expand → Migrate → Contract

1. Expand：只添加 nullable 列、新表、兼容索引；旧/新应用都能工作。
2. Migrate：独立 checkpoint 批处理，稳定业务键幂等，支持暂停/续跑；校验计数、哈希、重复和孤儿。
3. Cutover：双写/双读或受审查 flag 切换读取，至少观察一个发布窗口。
4. Contract：后续独立批准发布中删除旧结构或收紧约束；先备份，不承诺普通应用回滚可撤销。

生产业务服务默认关闭自动 Flyway，由短期 migrator 身份执行 validate/migrate。历史库 baseline 只能是一次性、明确批准和留证的接管动作。

## 5. 兼容矩阵语义

机器可读矩阵位于 `release/compatibility-matrix.json`。组合结果只有：

- `SUPPORTED`：完整支持；
- `TRANSITIONAL`：仅发布窗口内支持，并说明退出条件；
- `FORCE_UPGRADE`：APP 必须升级；
- `BLOCKED`：禁止组合；
- `UNKNOWN`：未验证，按禁止处理。

manifest 不得把 `UNKNOWN` 当成支持。至少描述 N/N-1 后端、Web、APP、schema 的组合和旧 APP 最低 code。

## 6. CHANGELOG 规则

根 `CHANGELOG.md` 使用 `Unreleased` 和版本章节，分类为 Added、Changed、Deprecated、Removed、Fixed、Security、Database、Operations、Rollback。每个版本必须记录：

- 兼容性与强制升级；
- schema from/to 和迁移阶段；
- Secret/配置新增、默认值和轮换要求；
- 运维动作、停机、监控和告警变化；
- 明确回滚方法与不能自动回滚的部分。

历史交付报告不能替代 CHANGELOG 或当前 release-manifest。

## 7. 工具链与可重复构建

- Web 使用 lockfile 与 `npm ci`；门禁拒绝只执行 `npm install` 的生产候选。
- Maven/Java/Node/npm/Gradle/HBuilder/WinSW/Caddy 版本与摘要记录在 `release/toolchain-lock.json` 和 manifest/主机信任合同。
- Gradle wrapper必须补充 distribution SHA-256；HBuilderX 编译器目录必须有批准版本/摘要，不能只依赖硬编码本机路径。
- 正式 APP 签名私钥/keystore 永不进入仓库、构建日志或发布包；包只记录证书指纹和验签结果。
- 构建时间采用 UTC；相同源、锁和 `SOURCE_DATE_EPOCH` 的重复包应一致。无法一致的制品必须记录非确定性来源并阻止“可重复”声明。
