# 点检录入弹窗与 APP 强制升级修复

## 基本信息

**任务类型：** 修复

**任务目标：** 优化点检项目/点检方案录入交互；使强制升级同时兼容已安装旧 APK，并保证新 APK 登录前阻断。

**工作目录：** `D:\codex\LeanTPM`

**可信基线：** 当前工作区；保护既有未提交客户反馈修改。

**范围：** Web 点检项目/方案弹窗，Backend 移动端版本策略 DTO，APP 启动/登录版本门禁与打包版本元数据。

**非目标：** 不提交、不推送、不部署、不修改生产数据库或云服务器。

## 需求理解

| 类型 | 当前理解 |
|---|---|
| 真实问题 | 长表单使整个弹窗滚动且底部操作不可见；云端声明 104/强制升级，但 APK 实际为 103，旧客户端又只读取最低支持版本 100。 |
| 明确需求 | 弹窗无需滚动整页维护；强制版本时旧版本进入及登录均被阻断。 |
| 涉及对象 | PC 管理员、Android APP 用户。 |
| 涉及模块 | frontend、backend mobile bootstrap、LeanTPM-APP。 |
| 默认假设 | 小屏允许表单内容区内部滚动，但底部操作区必须固定可见。 |
| 假设影响 | 不改变点检业务字段、保存 API 或数据库结构。 |

## 验收标准

```text
Given 1366x768 或更高的 PC 视口
When 打开点检项目或点检方案新增/编辑弹窗
Then 字段按三个业务分区切换录入，取消/保存按钮始终位于视口内
And 页面本身不随长表单滚动，小屏仅允许表单内容区内部滚动

Given 强制升级配置为 latest=104、minimum=100、force=true
When 旧 APP 103 读取其既有的 mobile/bootstrap 版本策略
Then 返回的有效最低版本为 104，旧客户端无需认识 forceUpgrade 也会阻断

Given 新 APP 位于登录页或无登录会话
When APP 可访问公开版本接口且当前版本低于强制版本
Then 登录前立即显示不可取消的升级提示，不允许进入业务页面

Given 当前重新打包源码
When 生成 APK
Then APK 的 versionName/versionCode 与后台发布记录 1.0.11/104 一致
```

## 授权范围

| 动作 | 是否允许 | 限制或目标 |
|---|---|---|
| 创建或切换分支 | 否 | 本任务未授权 |
| 数据库写入 | 否 | 不需要 |
| 创建提交 | 否 | 本任务未授权 |
| Push / PR | 否 | 本任务未授权 |
| 部署 | 否 | 本任务未授权 |
| 外部服务副作用 | 否 | 仅做已完成的公开接口/APK只读核对 |

## 影响与风险

- GitNexus：两个 Web 页面、MobileDtos、APP 登录/启动入口均为 LOW 代码扩散。
- 数据/合同：`mobile/bootstrap.androidVersion.minimumVersionCode` 在强制升级时返回有效最低版本；管理端原始配置不变。
- 风险等级：L3。
- 分级理由：跨 Backend/Web/APP，涉及登录门禁和旧 APK 兼容，但无数据库变更。
- 回滚方案：分别回退 UI 分区、有效最低版本构造逻辑和公开版本预检；不影响业务数据。

## 多角色与文件所有权

| 角色 | 任务 | 文件/模块所有权 | 只读 |
|---|---|---|---|
| 主实现 | 测试、最小修复、验证 | 本记录列出的范围 | 否 |
| UI 审查 | 弹窗布局审查 | frontend 点检页面 | 是 |
| 升级诊断 | 云端策略与 APK 行为诊断 | Backend/APP 版本链路 | 是 |
| 回归审查 | 测试盲区与兼容矩阵 | tests | 是 |

## 测试矩阵

| 场景 | 层级 | 预期结果 |
|---|---|---|
| 强制升级有效最低版本 | Backend 单元 | 100/104/force=true 返回 104；非强制仍 100；更严格 105 不降低 |
| APP 新旧版本矩阵 | Node 单元 | 可选、最低版本、强制最新版、已最新均正确 |
| 登录前公开检查 | APP 源合同 | 匿名接口 auth=false，启动和登录入口均先检查 |
| 两个录入弹窗 | Web 源合同 + build | 三分区、固定 footer、响应式内部滚动 |
| APK 元数据 | aapt | versionName=1.0.11、versionCode=104（打包后由用户复核） |

## 最终记录

- 实际修改：点检项目和方案编辑器拆分为三个页签，弹窗采用视口内 flex 布局和固定 footer；Backend 强制升级时将 mobile/bootstrap 的有效最低版本提升到 latest；APP 新增匿名公开版本检查并在启动、登录页显示、登录提交前执行；打包元数据改为 1.0.11（104），包名纠正为生产已安装包一致的 `uni.app.UNICEE59D0`。
- 验收结果：1366×767 浏览器实测两个弹窗均无 document 滚动，所有页签 footer 均在视口内；线上公开接口配置为 latest=104/minimum=100/force=true/enabled=true；线上下载 APK 经 aapt 实测却为 1.0.4（103），已纠正本地打包清单。
- 测试与证据：Backend 66 suites / 228 tests / 0 failures / 65 条条件化 MySQL 集成测试跳过；Web 27/27、生产构建成功；APP 工程检查成功、59/59；`git diff --check` 通过；GitNexus detect-changes 风险 LOW、无受影响流程。
- 构建限制：`npm.cmd run build:app` 被仓库既有 toolchain lock 阻断（HBuilderX compiler version/digest 未固定）；本次仍需按项目约定在 HBuilderX 中手工云打包并使用旧正式签名。线上 APK 与历史 HBuilder 包的签名证书 SHA-256 均为 `f8ba79b2b084034c21f9cc458470a51d31883c2f9d5f8f1a978dc96feb2a37bc`，新包必须保持一致才能覆盖升级。
- 未解决风险：已安装的旧二进制无法获得“登录前公开检查”新代码，只能依赖另行发布 Backend 后，通过其原有登录后 bootstrap/workbench 检查点阻断；新打包 104 才能在登录前立即提示。
- Git/发布动作：无。
