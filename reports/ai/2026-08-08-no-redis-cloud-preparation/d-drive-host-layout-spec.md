# D 盘生产布局与既有 Caddy 复用规格

## 需求理解

| 项目 | 约束 |
|---|---|
| 部署总目录 | 云服务器已有 `D:\LeanTPM`，但不得直接把安装根和数据根设置为同一目录 |
| 安装与数据边界 | 候选生产根为 `D:\LeanTPM\App` 与 `D:\LeanTPM\Runtime`；两者必须不同且互不为祖先；既有 `D:\LeanTPM\data` 专用于 MySQL，不得作为发布控制面根 |
| 信任根 | 生产布局必须由主机管理员所有的 bootstrap 策略固定，不能由发布计划或其指定目录内的 trust 文件自我授权 |
| Backend 选择器 | `Runtime\pointers\current-release.json` 指向 `App\releases\<releaseId>`，Backend 从已验签 release 启动 |
| Web 选择器 | `App\current` junction 指向同一不可变 release，Caddy 只读取 `App\current\payload\web` |
| 既有目录 | `D:\LeanTPM\current\backend|frontend`、`shared\config|uploads` 等只读盘点后受控迁移；不得静默覆盖或删除 |
| 反向代理 | 默认评估复用既有 `caddy` 服务；与 `LeanTPM.Proxy` 二选一，禁止并存 |
| 当前授权边界 | 仅允许仓库内离线规格、验证器、PlanOnly 和测试；不连接或修改云服务器、SCM、数据库、证书或防火墙 |

## Given / When / Then 验收

1. **Given** 主机 bootstrap 固定 `D:\LeanTPM\App` 与 `D:\LeanTPM\Runtime`，**When** 解析生产布局，**Then** 返回确定、机器可读的 releases/current/config/uploads/staging/pointers/backups/logs/audit/state/secrets 路径及布局摘要，并拒绝既有 MySQL 根 `D:\LeanTPM\data`。
2. **Given** 根相同、嵌套、相对、UNC、设备路径或含 `..`，**When** 校验布局，**Then** 在读取发布 trust、停服、备份或迁移前拒绝。
3. **Given** 发布计划声明的根或 trust 与 bootstrap 不一致，**When** 执行任一生产入口，**Then** fail closed，不能使用 `AllowNonProduction*` 绕过。
4. **Given** 代理模式为 `EXTERNAL_EXISTING`，**When** 校验既有 Caddy，**Then** 只能接受 bootstrap 固定的 serviceId、image/hash、账户、SDDL、配置、publicHost、loopback upstream 与 80/443 listener owner；不得安装第二代理。
5. **Given** 代理模式为 `MANAGED_LEANTPM_PROXY`，**When** 发现既有 `caddy` 或 80/443 被其他进程占用，**Then** 拒绝安装或启动。
6. **Given** legacy 目录非空，**When** 运行 PlanOnly/preflight，**Then** 仅报告导入阻断，目录树、服务和进程零变化。
7. **Given** Backend pointer 与 Web junction 不属于同一 release/package digest，**When** 启动、发布或回滚，**Then** 拒绝并进入可审计恢复状态，不能产生跨版本混搭。
8. **Given** 代理停止、reload、junction、ACL 或断电故障，**When** 执行补偿，**Then** 真实公网 listener 未隔离即报告 CRITICAL，且不宣告成功。

## GitNexus 与影响分析

- GitNexus 索引状态：up-to-date，基线提交 `2185536e`。
- query 命中 Deployment、Rollback、FirstInstall、Caddy installer/binding/fail-closed 等文件。
- PowerShell 入口在图谱中仅有文件级符号，`context/impact` 返回 UNKNOWN；不能据此降低风险。
- 源码实际爆炸半径覆盖首次安装、发布、回滚、恢复、服务启停、备份路径、Secret、HTTPS 和公网 fail-closed，按 **L4 / HIGH** 管理。

## 风险分级

| 风险 | 等级 | 控制 |
|---|---:|---|
| plan 指定 DataRoot 后读取其中 trust，形成信任根自替换 | P0 / L4 | 固定 host bootstrap；计划只引用并绑定 layout digest |
| 停止 `LeanTPM.Proxy` 但真实 `caddy` 仍对公网转发 | P0 / L4 | 单一 proxy abstraction；固定 serviceId/PID/listener owner；二选一 |
| InstallRoot=DataRoot 折叠不可变供应链与可写 TLS/log/data 边界 | P1 / L4 | 两个不相交子根；拒绝相同或祖先关系 |
| legacy current/config/uploads 被覆盖 | P1 / L3 | 只读盘点；显式导入计划、备份与逐路径确认 |
| pointer/junction 分步切换造成 Web/Backend 混搭 | P1 / L4 | 全局锁、同版摘要绑定、分别补偿、事后复核 |

## 角色与文件所有权

| 角色 | 所有权 | 当前权限 |
|---|---|---|
| 产品/验收 | 本规格、阶段验收与证据映射 | 只读复核 |
| 架构与安全 | bootstrap 信任边界、根隔离、proxy 模式 | 只读复核 |
| 发布实现 | HostLayout schema/resolver、计划绑定、离线适配器 | 可修改指定发布脚本 |
| 测试 | `scripts/tests/release-platform.test.mjs` 与新行为 harness | 测试先行；默认只读审查 |
| 独立审查 | 变更差异、P0/P1 与清理门禁 | 只读 |

## 测试矩阵

| 层级 | 首批离线测试 | 后续环境测试 |
|---|---|---|
| Schema/单元 | 精确路径映射、摘要稳定、未知字段/错误根拒绝 | bootstrap ACL/owner/卷标识 |
| 合同 | 所有生产入口引用同一 layout digest；禁止 non-production 绕过 | 签名计划与主机 layout 绑定 |
| PlanOnly | D 布局解析、legacy 报告、零目录/环境/进程副作用 | 真实 D 盘只读盘点 |
| Proxy | EXTERNAL/MANAGED 二选一、错误 service/PID/hash/port 拒绝 | 既有 `caddy` SCM/DACL/PID/listener/配置验证 |
| 切换/故障 | pointer+junction 一致性、各阶段失败补偿 | Windows 断电、reload、服务停止失败与公网隔离 |
| 恢复 | D 布局 backup/restore 路径合同 | 隔离 DB+附件+配置恢复及真实应用 E2E |

## 回滚方案

- 本批离线资产未接入生产执行入口前，可通过撤销新增 schema/resolver/test 文件回滚，不改变既有 C 盘 fail-closed 行为。
- 接入阶段必须保留旧 C 盘 profile，并以 bootstrap schemaVersion 分流；未知版本一律拒绝。
- 云端迁移不得覆盖 legacy 目录；先复制到新根、校验 hash/ACL，再原子切换。失败时恢复原配置字节和 selector，并保持 Backend/公网入口处于已验证安全态。
- 任何 DB 写入开始后的失败遵守 manifest `RECOVERY_REQUIRED`，不自动启动旧版应用。

## 当前结论

`OFFLINE-DESIGN-IN-PROGRESS / NOT_RELEASEABLE / CLOUD-NOT-AUTHORIZED`

## Host-owned bootstrap 实施批次

生产信任锚采用固定小型目录 `C:\ProgramData\LeanTPM-bootstrap`，只保存主机布局策略，不保存制品、备份或 Secret。生产调用方不得传入其他 bootstrap 路径；测试临时根只能在 `PlanOnly` 使用。

### 新增验收

1. **Given** 固定 bootstrap 目录及 `host-layout.json`，**When** 生产校验，**Then** owner 必须为 `BUILTIN\Administrators` 或 `NT AUTHORITY\SYSTEM`，ACL 必须关闭继承，且除 Administrators/SYSTEM 外无读写 ACE；目录、文件和父链均不得为 reparse。
2. **Given** 当前 Windows，**When** 校验 `hostId`，**Then** 必须等于规范化 MachineGuid 与 SMBIOS UUID 的 SHA-256；调用方不能覆盖。
3. **Given** `D:\LeanTPM\App` 与 `D:\LeanTPM\Runtime`，**When** 校验卷身份，**Then** 两根必须存在于同一个固定本地卷，布局 `volumeIdentity` 必须等于该卷 DeviceID 的 SHA-256。
4. **Given** 两个根，**When** 取得目录句柄，**Then** final path 必须与批准的规范路径一致；8.3、junction、mount point 或父链 reparse 不得改变最终目标。
5. **Given** bootstrap 校验成功，**When** 生成报告，**Then** 输出 `layoutSha256`、`hostId`、`volumeIdentity`、根与 proxy 选择；后续签名计划必须逐字节绑定这些值。
6. **Given** PlanOnly 临时 bootstrap，**When** 校验，**Then** 只能输出 `executable=false`、`hostFilesystemVerified=false`，前后目录摘要必须一致。

### 风险与回滚

- 该批次为 L4：错误信任锚会把任意目录、代理或主机提升为生产目标。
- bootstrap 校验器接入任何写入口前必须经过独立安全复核；接入前现有 C 盘生产路径继续 fail closed。
- 新校验器可通过移除调用接线回滚，不得自动删除 bootstrap、D 盘目录或修改 ACL。
