# LeanTPM 长期开发与简易发布记忆

Last updated: 2026-08-13 (Asia/Shanghai)

这是用户明确确认的长期工作策略。新对话在处理客户反馈、构建发布运维功能或准备云端发布前，必须先完整阅读本文件。除非用户再次明确改变策略，不得自行把发布流程扩展成企业级发布平台。

## 1. 系统定位与用户偏好

- LeanTPM 是一个小型业务系统，生产形态就是 Backend、Web、MySQL 和用户手工打包的 Android APP。
- 用户需要可理解、可重复、几分钟内能执行的简易发布流程，不需要复杂的发布平台、签名 manifest、PlanOnly、审批编排、控制平面或多层 operator ZIP。
- 不得因为追求形式完整而让一次小改动演变成通宵发布。
- 安全措施保留在一个简易 PowerShell 脚本内部，不额外增加操作步骤。
- 对用户的命令必须短、连续、可复制；优先给“复制两个文件，执行一个脚本”。

## 2. 环境边界

- 本地工作区：`D:\codex\LeanTPM`。
- 云生产地址：`http://8.163.66.164/`。
- 云服务器程序根目录：`D:\LeanTPM`。
- 内网开发服务器与阿里云生产服务器是两个环境；任何服务、数据库或部署操作前必须明确目标环境。
- 未经用户明确授权，不提交、不推送、不部署、不修改生产数据库、不操作云服务器。
- 用户允许时，可以进行生产公开接口或数据库的只读核对；写操作必须单独获得授权。

## 3. 当前生产基线

- Backend/Web 发布版本：`1.0.4-20260813.1`。
- Backend JAR：`D:\LeanTPM\App\releases\1.0.4-20260813.1\payload\backend\leantpm-backend.jar`。
- Web 根目录：`D:\LeanTPM\App\releases\1.0.4-20260813.1\payload\web`。
- 数据库：MySQL schema V53。
- 本次成功标志：
  `SIMPLE_APPLICATION_SUCCESS release=1.0.4-20260813.1 schema=53 backend=true web=true databaseModified=false appIncluded=false`
- 对应备份根目录：`D:\LeanTPM\backups\application-1.0.4-20260813.1-20260813-011637`。
- APP 独立于云端 Backend/Web 发布，由用户使用 HBuilderX 手工云打包并在 Web 管理端上传。
- APP 正式包名：`uni.app.UNICEE59D0`。
- APP 当前目标版本：`1.0.11`，versionCode `104`。
- 正式签名证书 SHA-256：
  `f8ba79b2b084034c21f9cc458470a51d31883c2f9d5f8f1a978dc96feb2a37bc`。

## 4. 默认发布分类

每次先按修改内容判断范围，不要扩大：

| 修改内容 | 默认发布范围 |
|---|---|
| 只改 PC 页面 | Web |
| 修改接口、权限或 Backend 业务逻辑 | Backend |
| Backend 与 PC 页面同时修改 | Backend + Web |
| 只改 APP 页面、离线逻辑、扫码或下载行为 | 用户重新打包并上传 APK |
| 新增或修改数据库表/字段 | 数据库备份 + 一份 SQL/Flyway 脚本 + 对应 Backend |

- 没有数据库结构变化时，绝对不要操作数据库。
- APP 不要混入 Backend/Web 发布包。
- 若只需要 APP 重新打包，明确告诉用户 Backend/Web 无需重发。

## 5. 用户确认的简易 Backend/Web 发布流程

本地只生成两个文件：

1. 一个 Backend+Web ZIP（或按范围只包含其中一个）。
2. 一个简易 PowerShell 发布脚本。

用户将两个文件复制到云服务器：

```text
D:\LeanTPM\temp
```

管理员 PowerShell 只执行：

```powershell
Set-ExecutionPolicy -Scope Process Bypass -Force
& 'D:\LeanTPM\temp\Deploy-LeanTPM-<版本>-Simple.ps1'
```

简易脚本内部完成必要安全工作：

1. 检查管理员权限、文件大小和 SHA-256。
2. 检查当前 Backend、Caddy 和 schema 基线。
3. 解压到全新的版本目录，禁止覆盖已有目录。
4. 备份 Backend 启动脚本和 Caddyfile。
5. 保持数据库版本不变，只切换本次授权的 Backend/Web。
6. 启动 Backend 并检查 readiness、版本和 schema。
7. 切换 Caddy Web 根目录并检查实际入口资源。
8. 失败时自动恢复旧 Backend/Web 配置。
9. 最终只输出清晰的 SUCCESS 或 FAILED/ROLLED_BACK 标志。

不要默认增加以下内容：

- 不开发通用企业级发布控制平面。
- 不要求用户运行 PlanOnly、输入 Plan SHA 或操作多个 operator 包。
- 不要求为小改动先建立签名 manifest、审批流或长期部署编排。
- 不因为工作区存在无关未提交文件就阻止用户已明确授权的紧急简易发布；但必须保护无关文件，只打包实际验证过的 Backend/Web 构建产物。

## 6. 数据库变更的简化流程

仅在确有表或字段变化时执行：

1. 明确云生产环境和目标 schema 版本。
2. 停止 Backend 或进入必要的短暂停写窗口。
3. 使用 `mysqldump` 创建新鲜生产备份并输出 SHA-256。
4. 执行一份最小、可重复检查的 SQL/Flyway 迁移。
5. 核对列/表和 `flyway_schema_history`。
6. 发布对应 Backend，检查 readiness 和 schema。

用户明确接受跳过真实迁移演练时，不得继续强行要求企业级恢复演练；需要直白说明剩余风险，然后按用户确认的可空加列等最小方案执行。

## 7. APP 打包与升级固定规则

- 使用 HBuilderX 打开 `D:\codex\LeanTPM\LeanTPM-APP`。
- 每次确认 `versionName`、`versionCode`、正式包名和 API 地址。
- 包名必须是 `uni.app.UNICEE59D0`，签名必须与正式旧包一致，否则无法覆盖安装。
- 后台填写的版本号不能代替 APK 真实版本；上传后应使用 `aapt dump badging` 核对真实包名、versionName、versionCode，并用 `apksigner` 核对证书摘要。
- 强制升级必须同时验证：启动页/登录前阻断、工作台二次检查、下载按钮、APK 下载进度、系统安装唤起、下载/安装失败后门禁仍存在。
- 已安装的旧二进制无法凭空获得新客户端逻辑；必要时明确要求先手工安装一次桥接/修复 APK。

## 8. 客户反馈开发固定流程

1. 完整阅读 AGENTS、开发流程、任务模板、GitNexus 工作流、本文件和当前交接。
2. 执行 `git status`，保护全部已有修改，不 reset、clean、stash，不覆盖无关文件。
3. 先确认截图对应的 APP versionCode、API 地址、任务/数据标识、方案生效时间和接口实际返回，不把旧截图直接视为当前源码行为。
4. GitNexus-First：陌生代码先 query/context，修改符号前 impact；HIGH/CRITICAL 先告警并缩小范围。
5. 先写失败测试，再做最小修复。
6. 按范围验证 Backend/Web/APP；不要顺带重构或顺带发布。
7. 最终用直白语言说明：修改内容、测试证据、APP 如何重打包、Backend/Web/数据库是否需要发布。

## 9. 沟通规则

- 结论先行，少讲发布平台术语。
- 能用一张简表说清楚就不要输出长篇治理说明。
- 发布失败最多针对同一问题修正三次；先根据实际错误做最小修正，不重建一套新体系。
- 用户说“快速发布”且已明确授权时，优先复用已经成功的简易脚本结构。
- 每次都明确：目标环境、发布范围、数据库是否修改、APP 是否包含。
