# APP 设备范围与版本升级客户反馈修复

## 任务边界

- 目标环境：仅本地工作区源码与本地测试；不连接内网开发服务、云服务或任何数据库。
- 不执行：提交、推送、部署、生产数据库修改、云服务器操作、APK 签名打包。
- 保护范围：保留工作区既有文档、运行时目录和报告变更，不暂存、不格式化、不覆盖无关文件。
- 客户截图核对：截图 APP 为 `1.0.3 (102)`，API 为 `http://8.163.66.164/api/v1`；当前源码 APP 为 `1.0.4 (103)`。旧截图仅作为问题证据，不作为当前 APK 行为结论。

## 需求与验收（Given / When / Then）

1. 点检任务设备编号
   - Given 点检任务接口返回 `equipmentName` 与 `equipmentCode`
   - When APP 展示“我的点检”任务卡片
   - Then 卡片同时清晰展示设备名称和设备编号。
2. 设备状态与扫码点检同范围
   - Given 用户拥有移动扫码权限，且其组织范围按“班组提升到直接上级，再包含该上级全部下级”计算
   - When 用户打开设备状态列表并点击设备
   - Then 列表查询和设备详情都使用同一 `inspectionScanScope`，不再依赖通用后台 `equipment:view` 权限；范围内设备可见可进，兄弟分支外设备不可见不可进。
3. 强制升级
   - Given Web 发布 APP 时可选择“强制所有旧版本升级”，并配置最新版本号与最低支持版本号
   - When 已登录或刚登录 APP 的 `versionCode` 低于最低支持版本号，或已勾选强制升级且低于服务端最新版本号
   - Then APP 立即显示不可取消的升级提示，阻止进入业务页面；未勾选强制升级且仍满足最低支持版本时，只提示存在新版本，不阻断使用。
4. 我的版本信息
   - Given 服务端返回最新 `versionCode`，APP 可读取当前安装版本
   - When 用户进入“我的”
   - Then 同一区域显示当前版本、最新版本和明确状态；当前版本不低于最新版本时显示“当前已是最新版本”，且不显示“下载最新版”按钮。

## 设计与兼容性

- 新增移动端专用设备状态分页接口，权限与扫码详情统一为 `mobile:access + mobile:scan`，服务层复用现有 `inspectionScanScope`。
- Android 版本策略新增 `latestVersionCode` 与 `forceUpgrade`。`forceUpgrade` 存入现有 `system_parameter`，缺失时默认为 `false`，无需 Flyway 迁移。
- 保留既有最低支持版本语义：低于最低版本始终阻断；强制升级选项额外将所有低于最新发布版本的 APP 阻断。
- 老 Backend 未返回新字段时 APP 使用保守兼容默认值，不把缺失字段误判为有可用更新。

## 影响分析与风险

- GitNexus 索引：LeanTPM，提交 `38b30e7`，状态 up-to-date。
- `MobileService`：LOW，2 个直接依赖；`MobileController`：LOW，0；`MobileMapper`/`MobileDtos`：LOW，各 1。
- `AppReleaseService`：LOW，2 个直接依赖；`AppReleaseController`/`AppReleaseDtos`：LOW，0。
- APP `checkAndroidUpgrade`：LOW，1 个直接调用、2 个总影响；登录 `submit`：LOW，1；设备状态 `load`：LOW，2。
- 综合风险：L4。理由：授权范围、登录阻断、跨 Backend/Web/APP API 合同；没有数据库结构或生产环境变更。

## 多角色治理与所有权

- 产品/验收角色（只读阶段）：核对四项客户反馈与上述 GWT，不扩展到发布。
- 架构/安全角色（只读阶段）：核对移动设备列表与扫码详情使用同一范围和权限；核对老版本兼容默认值。
- 实现角色：仅拥有本报告列明的移动设备、APP 发布、版本判定、登录/Profile 与对应测试文件。
- 测试角色（只读阶段）：先建立失败测试，再运行 Backend/Web/APP 定向和全量可行验证。
- 审查角色（只读阶段）：检查权限旁路、阻断条件、误导文案、无关变更和 GitNexus 变化范围。

## 失败测试与验证计划

- APP：版本策略矩阵（最新/可选更新/最低版本阻断/强制最新阻断）；源码合同验证任务设备编号、移动设备接口、登录前置检查、Profile 按状态隐藏下载按钮。
- Backend：移动 Mapper 合同及范围复用；APP 发布强制标志持久化与 DTO；现有扫码组织树集成测试扩展设备列表断言。
- Web：APP 发布表单包含并提交强制升级选项，类型检查、单元测试、生产构建。
- 收尾：Backend 定向测试、APP `verify`、Web test/typecheck/build、`git diff --check`、GitNexus `detect-changes`、权限与版本合同复查。

## 实施结果

- APP 点检任务卡新增“设备编号”，数据直接使用既有任务合同中的 `equipmentCode`。
- 新增 `GET /api/v1/mobile/equipment-status`，要求 `mobile:access + mobile:scan`；列表与扫码详情都复用 `inspectionScanScope`。响应只包含移动列表所需字段和当前有效条码令牌。
- APP 设备状态页不再调用通用 `/equipment` 接口，避免依赖后台 `equipment:view` 权限。
- APP 发布合同新增 `latestVersionCode`、`forceUpgrade`；强制标志使用 `mobile.android-force-upgrade` 系统参数，缺失时为 `false`，无数据库迁移。
- Web APP 发布页新增“强制所有旧版本升级”；启用时所有低于最新发布 `versionCode` 的 APP 被阻断，未启用时仍保留“低于最低支持版本号必须升级”的既有规则。
- APP 登录成功后在业务跳转前检查策略；已登录会话在 App `onShow`、工作台刷新时也检查。阻断弹窗不可取消，确认下载后会再次检查，不能绕过进入业务页。
- “我的”版本卡显示当前安装版本、服务端最新版本（含 `versionCode`）和明确状态；只有确有新版本且配置下载地址时才显示下载按钮。
- 升级下载路径会基于当前 API 地址将服务端相对路径转换为绝对 URL，避免系统浏览器无法打开相对路径。

## 测试证据

- 失败测试（实现前）：APP 新增 5 项均失败；Web 强制升级合同 1 项失败；Backend 合同 2 项失败。另在审查中新增相对下载 URL 测试，修复前失败。
- APP：`npm.cmd run verify`，项目检查通过，49/49 测试通过。
- APP 原生资源：`npm.cmd run build:app`，HBuilderX 5.15 Vue3 编译成功，输出 `LeanTPM-APP/unpackage/dist/build/app`；编译产物包含设备编号、版本状态和移动设备状态接口。
- Web：`npm.cmd test` 18/18；`npm.cmd run typecheck` 通过；`npm.cmd run build` 通过（Vite 2543 modules）。
- Backend 定向：客户反馈合同、扫码范围、APP 发布服务 7/7 通过。
- Backend 全量：213 项，0 失败、0 错误、64 跳过；跳过项均为需要 `LEANTPM_TEST_DB_URL` 的 MySQL 集成测试。本机未配置测试数据库，未连接或修改任何数据库。
- MySQL 范围集成用例已补充：上级组织、自身班组、班组提升直接父级及兄弟分支排除，同时断言设备状态列表、汇总和扫码详情一致；本轮仅完成编译，待获准的隔离测试库执行。
- GitNexus `detect-changes`：整个既有脏工作区被评为 CRITICAL（47 个符号、17 条流程），结果包含任务开始前的发布脚本/文档改动和行号漂移误报。本次核心入口复核：`MobileService.bootstrap` MEDIUM；发布服务、升级判断、登录、设备页和 Web 上传均 LOW。未提交。
- `git diff --check`：通过；仅既有 runtime 发布脚本出现 LF/CRLF 提示，本任务未修改这些既有文件。

## 审查结论

- 权限：新列表没有扩大为通用设备查看权限；服务端强制使用同一扫码组织范围，不能由 APP 参数扩大。
- SQL：状态值使用白名单且通过 MyBatis 参数绑定；分页由控制器校验；租户、启用、删除和有效组织条件均固定。
- 版本：以 Android `versionCode` 为唯一升级顺序；高于服务端的开发/预发布包不会被错误阻断；旧 Backend 缺少最新版本号时不误显示下载按钮。
- 发布安全：未执行提交、暂存、推送、部署、生产数据库、云服务器或 APK 签名操作；既有工作区变更保持原状。
