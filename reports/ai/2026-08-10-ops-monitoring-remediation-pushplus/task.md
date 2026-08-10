# 运维监控、受控自动修复与 PushPlus

## 基本信息

- 任务类型：开发
- 任务目标：在独立运维控制台增加服务器、固定 Windows 服务、数据库和日志监控；对固定服务停止故障执行有次数/冷却限制的白名单自动启动；对异常、恢复、修复和发布结果发送阅读友好的 PushPlus 消息，并支持一个或多个接收方。
- 工作目录：`C:\Users\Mayn\.codex\worktrees\fca1\LeanTPM`
- 可信基线：`2185536ea9da0a323b27f53dcf849b818ea19069`（detached worktree）
- 范围：`ops-control-plane`、对应模块测试、生产配置示例、模块 README、Feature flag 登记和本任务报告。
- 非目标：部署到阿里云、启停真实服务、连接真实数据库、发送真实 PushPlus 消息、提交/推送代码、提供任意命令/SQL/URL/服务名入口。

## 需求理解

| 类型 | 当前理解 |
|---|---|
| 真实问题 | 控制台只有发布能力，系统故障仍需人工发现、人工恢复，异常也不能及时送达多个负责人。 |
| 明确需求 | 完成监控、受控自动修复和 PushPlus；PushPlus 支持配置一个或多个接收方，内容优先适合手机阅读。 |
| 涉及对象 | 运维操作员、生产 Backend、Caddy、ReleaseAgent、MySQL、固定日志、发布记录。 |
| 涉及模块 | `ops-control-plane` 的配置、API、调度、监控探针、修复协调、通知和静态 UI。 |
| 可能歧义 | “自动修复”可能被误解为执行任意脚本；本实现严格限定为启动三个固定服务，不开放任意参数。 |
| 必须确认 | 无；真实启用和 PushPlus 令牌由后续受保护生产配置完成，本轮不产生外部副作用。 |
| 默认假设 | 控制面与被监控服务在同一 Windows 主机；数据库仅允许 loopback、固定库和只读探测 SQL。 |
| 假设影响 | 非同机部署需以后增加受签名 Agent observation 合同，不能通过放宽 URL/命令输入解决。 |

## 验收标准

```text
Given 运维身份已认证且监控已启用
When 控制台读取运行状态
Then 返回服务器、Backend/Caddy/ReleaseAgent、MySQL 和固定日志的有界状态与安全摘要
And 不返回数据库密码、PushPlus token、原始日志内容或任意物理命令

Given 自动修复开关关闭
When 固定服务连续异常
Then 只记录和通知异常，不执行任何修复动作

Given 自动修复开关开启且固定服务达到连续失败阈值
When 未超过每小时上限且冷却期已结束
Then 只执行枚举动作 START_BACKEND、START_CADDY 或 START_RELEASE_AGENT
And 持久记录尝试、结果和下一次允许时间；重启控制面后限制仍有效

Given PushPlus 已配置一个或多个接收方
When 发生异常、恢复、自动修复结果或发布终态
Then 每个启用接收方独立收到 markdown 消息
And 消息固定包含影响、自动处理、当前状态、建议操作和时间，单个接收方失败不阻断其他接收方

Given PushPlus 关闭、无接收方或发送依赖失败
When 产生通知事件
Then 不伪报已送达，不泄露 token，并在控制台显示关闭/部分接受/失败摘要

Given 页面刷新、暂时探测失败或重复调度
When 状态未发生真实转换
Then 不重复推送同一事件、不突破修复上限，现有发布功能和认证边界保持不变
```

覆盖：正常、异常、边界、空配置、权限不足、PushPlus 失败、持久状态重载、重复调度、兼容旧发布功能。数据库写入不适用：探针只执行固定 `SELECT`；生产环境验证本轮不适用，因为未授权部署或连接。

## 授权范围

| 动作 | 是否允许 | 限制或目标 |
|---|---|---|
| 创建或切换分支 | 否 | 保持 detached worktree |
| 数据库写入 | 否 | 测试使用假探针；不连接真实 MySQL |
| 创建提交 | 否 | 未授权 |
| Push / PR | 否 | 未授权 |
| 部署 | 否 | 不触碰阿里云和 D 盘生产服务 |
| 调用有副作用外部服务 | 否 | PushPlus 只用假 transport 验证，不真实发送 |

## 环境

- 开发/测试/构建：模块 Maven 3.9.11、Java 21；Node 用于静态 UI 纯函数测试。
- 浏览器：本机 `127.0.0.1:18090` 预览，不连接生产 Agent。
- 数据库安全：固定 loopback 与固定只读 SQL；本轮测试不连接数据库。
- 外部依赖：PushPlus 官方 `/send` JSON API；同步 `code=200` 只记为“API 已接受”，不声称最终送达。

## 影响与风险

- GitNexus 状态：索引与基线一致，但 `ops-control-plane` 为未跟踪新模块，Query 无有效流程，关键 `context/impact` 均为 UNKNOWN；使用源码、API 合同和测试降级调查。
- 直接修改：控制面配置、Bean 接线、受保护 operations API、调度器、静态 UI。
- 间接消费者：运维浏览器、固定 Windows 服务、MySQL loopback、PushPlus 接收方。
- 数据/合同/权限影响：新增 API 和本地耐久运行状态；不扩展匿名权限；token 仅来自受保护配置且不进入响应/日志。
- 风险等级：L4。
- 分级理由：涉及外部通知、服务控制、持久限流状态和跨模块状态展示。
- Feature flag / 回滚：`ops-auto-remediation` 与 `ops-pushplus-notifications` 默认 OFF；关闭后保留只读监控，不执行服务动作/外发消息。回滚可关闭开关并移除新增 API/UI，发布主链不变。

## 多角色与文件所有权

| 角色 | 任务 | 文件/模块所有权 | 是否只读 | 是否允许修改测试 |
|---|---|---|---|---|
| 主代理/产品阶段 | 固定范围、验收、集成 | 本任务记录与最终集成 | 否 | 否 |
| 架构阶段 | 审查固定命令、密钥和状态边界 | 技术方案与配置合同 | 是 | 否 |
| 后端实现阶段 | 监控、修复、通知、API | `src/main/java/**/operations` 与配置接线 | 否 | 否 |
| 前端实现阶段 | 运维总览与通知摘要 | `static/index.html`、`app.js`、`styles.css` | 否 | 否 |
| 测试阶段 | 先写失败测试、模块回归 | `src/test/**` | 是（实现后） | 是（实现前） |
| 审查阶段 | 权限、幂等、脱敏、越界检查 | 本轮 diff | 是 | 否 |

受当前执行约束不启用子代理；上述角色按独立阶段顺序执行，最终审查阶段不再修改实现，发现问题返回实现阶段，最多三轮。

## 测试矩阵

| 场景 | 层级 | 数据/身份 | 预期结果 | 命令或工具 | 证据路径 |
|---|---|---|---|---|---|
| 多接收方通知 | 单元 | 2 个假 token、1 个 transport 失败 | 独立发送、部分接受、无 token 泄露 | Maven 定向测试 | Surefire |
| 阅读友好模板 | 单元 | 异常/恢复/发布事件 | 固定 markdown 五段结构 | Maven 定向测试 | Surefire |
| 修复阈值/冷却/限额 | 单元 | 假时钟、固定服务 | 只执行枚举动作且不重复 | Maven 定向测试 | Surefire |
| 默认 OFF | 单元/上下文 | 无生产开关 | 无服务动作、无外发 | Maven 测试 | Surefire |
| API 权限与脱敏 | MVC | 匿名/有效 token | 匿名 401；认证响应不含秘密 | Maven MVC | Surefire |
| UI | 浏览器 | 本地预览 token | 监控、修复、PushPlus 摘要可读 | in-app Browser | 本任务最终记录 |
| 兼容发布 | 模块回归 | 既有发布测试 | 现有发布 49 项及新增测试全绿 | Maven test | Surefire |
| 格式/影响 | 静态 | 本轮路径 | 无空白错误、无计划外调用链 | GitNexus + git | 命令摘要 |

## 技术边界

- 所有系统动作使用枚举到固定 SCM ID 的映射，API/配置不能传任意服务名或命令。
- 数据库探针只允许 `127.0.0.1`、固定数据库名和固定查询；凭据不进入响应、审计或异常文本。
- 日志探针只读取配置根下的相对普通文件，不跟随 reparse，不返回原文。
- PushPlus 采用 HTTPS POST、`template=markdown`；支持多个独立接收方、topic/channel/option 配置，付费渠道默认拒绝。
- PushPlus 的 HTTP 200 只表示 API 接受；不将异步投递误写为成功送达。

## 最终记录

- 实际修改：完成独立运维状态 API、周期/即时监控、系统资源/固定 Windows 服务/Backend readiness/loopback MySQL/固定日志探针；完成三个固定服务的白名单启动修复、阈值/冷却/每小时上限/原子持久化；完成 PushPlus 多接收方独立发送、阅读友好 markdown、脱敏状态与运维控制台展示。根据本地验收反馈，补充始终可见的服务器资源、固定服务、数据库、日志四类占位卡片，未接入时明确标记“待接入”且不伪造实时数值。
- 验收结果：所有新开关默认 OFF；修复动作仅允许 `START_BACKEND`、`START_CADDY`、`START_RELEASE_AGENT`；PushPlus 支持 0–20 个接收方，单接收方失败不阻断其他接收方，响应/UI 不包含 token；本地预览 `http://127.0.0.1:18090/` 健康为 `UP`，监控和通知均保持关闭。
- 测试与证据：新增静态运维栏目合同测试先失败后通过；`mvn test` 66/66 PASS、0 失败、0 跳过；`node --check` PASS；`git diff --check` PASS（仅已有 LF/CRLF 警告）；本地 HTTP transport 测试验证 PushPlus 官方 JSON 合同，未发送真实消息；in-app Browser 已验证未认证状态下四类运维栏目可见、认证后关闭态、既有发布流程和控制台零错误。
- GitNexus：已在授权下重建索引（8,068 nodes / 20,120 edges / 439 clusters / 300 flows）。新模块仍未跟踪，因此新符号 `context/impact` 为 `UNKNOWN`；全仓 `detect-changes` 因混合用户既有 83 个文件改动判为 `CRITICAL`，本轮未据此清理、暂存或修改无关文件。
- 清理结果：测试临时目录由测试框架清理；没有真实数据库、SCM、阿里云或 PushPlus 副作用；保留 Maven 构建产物和本地预览进程作为用户验收产物。
- 未解决风险：生产启用仍需在服务器上绑定受保护配置、精确 SCM 身份、只读 MySQL 账号、固定日志根和真实 PushPlus token 后分阶段验证；本轮未执行该生产仪式。GitNexus 在文件纳入版本控制前无法提供新模块的符号级影响结论。
- Git/发布动作：未提交、未推送、未部署。

## 追加需求：所在主机实时指标与图形化展示

### 需求与边界

- 真实问题：当前本机预览只显示“待接入”栏目，没有控制台所在主机的真实资源指标，也没有图形化视图。
- 明确需求：控制台运行在本机时读取本机；未来同一构建运行在阿里云 Windows 主机时读取该云主机。至少展示 CPU、系统内存、磁盘、JVM、处理器数、主机与操作系统摘要，并对百分比指标使用可访问的图形条。
- 默认假设：这里的“本服务器”指运行 `ops-control-plane` Java 进程的操作系统主机，不通过浏览器探测用户电脑，也不远程抓取另一台机器。
- 非目标：本轮不部署阿里云、不启用服务/数据库/日志全量监控、不启用自动修复、不发送 PushPlus、不采集进程列表或敏感环境变量。
- 回滚：`leantpm.ops.monitoring.host-resources-enabled=false` 立即恢复纯占位状态；完整监控的 `enabled` 语义保持不变。

### 验收标准

```text
Given 运维控制台在任意受支持 Windows 主机启动且 host-resources-enabled=true
When 已认证操作者读取或刷新系统运行状态
Then 返回该 Java 进程所在主机的 CPU、系统内存、磁盘、JVM 和基础主机摘要
And 不需要 MySQL 凭据、服务控制或日志目录配置

Given 完整监控 enabled=false 但主机资源监控为 true
When 本机预览启动
Then 只采集主机资源，不调用 SCM、MySQL、Backend readiness 或日志探针
And 自动修复和 PushPlus 仍保持 OFF

Given 返回 0-100 的百分比指标
When 页面渲染系统运行状态
Then 用带文本值、ARIA 标签和受限宽度的图形条展示 CPU、系统内存、磁盘和 JVM
And 非百分比指标继续使用可读文本，不把未知值伪装为 0
```

### 影响、风险与角色

- GitNexus Query 未找到新模块执行流；`SystemResourceProbe`、`renderOperations` 的 Context/Impact 均为 `UNKNOWN`，原因是整个 `ops-control-plane` 仍未跟踪。按 L3 保守治理。
- 直接修改：`SystemResourceProbe`、监控属性与 Bean 接线、默认/示例配置、静态 UI/样式和专项测试。
- 间接消费者：`OperationsCoordinator`、`GET/POST /api/v1/operations/*`、运维浏览器。
- 合同兼容：不增加顶层 DTO；仅在现有 `metrics` map 增加键，旧消费者可忽略。

| 角色 | 任务 | 文件/模块所有权 | 是否只读 | 是否允许修改测试 |
|---|---|---|---|---|
| 产品阶段 | 固定“所在主机”语义与空态 | 本节需求/验收 | 是 | 否 |
| 架构阶段 | 拆分 host-only 与 full monitoring 开关 | operations 配置/接线方案 | 是 | 否 |
| 测试阶段 | 先写主机采样与图形合同失败测试 | `src/test/**` | 否（先行） | 是 |
| 后端实现阶段 | 真实主机采样与安全边界 | `operations/**`、配置 | 否 | 否 |
| 前端实现阶段 | 图形条、可读标签、响应式 | `static/**` | 否 | 否 |
| 审查阶段 | 脱敏、边界、回归和浏览器复核 | 本轮 diff | 是 | 否 |

### 测试矩阵

| 场景 | 层级 | 预期结果 | 证据 |
|---|---|---|---|
| host-only 默认路径 | Spring 上下文/API | 无完整监控凭据仍返回服务器资源 | Maven |
| 确定性主机采样 | 单元 | CPU/内存/磁盘/JVM键和值正确且有界 | Maven |
| 图形化合同 | 静态/UI | 百分比有 bar、文本、ARIA，非百分比可读 | Maven + node check |
| 浏览器真实流 | Browser | 登录后显示当前本机指标与图形条，刷新稳定 | 本机 18090 |
| 回归 | 模块 | 发布、通知、修复测试无回归 | Maven full |

## 追加缺陷：点击“使用此身份”无可见反馈

### Given / When / Then

```text
Given 操作者输入有效的本机预览身份
When 点击“使用此身份”
Then 按钮立即禁用并显示“正在验证运维身份…”
And 系统运行状态接口作为首个、权威的身份与数据请求
And 成功后立即展示所在主机图表

Given 发布记录、审计或 Agent 任一辅助接口暂时失败
When 系统运行状态接口已通过认证并返回数据
Then 身份仍视为已接受且主机图表保持显示
And 页面仅提示部分辅助栏目暂时无法读取

Given 浏览器仍缓存旧版脚本
When 重新加载控制台首页
Then HTML 中的 app.js 与 release-tracker.js 使用固定版本查询参数获取新字节
```

### 影响与验证

- 修改范围仅限静态登录交互、脚本版本 URL 和静态合同测试；不修改鉴权 API、令牌格式或生产配置，按既有 L3 边界执行。
- 先增加失败合同测试，证明旧页面缺少缓存版本与 operations-first 流程；实现后定向测试 3/3 PASS。
- 模块全量回归更新为 70/70 PASS，JavaScript 语法检查 PASS。
- 新预览构建 SHA256：`4041F65643A48A209D4A014D533CE73BCCA9F7AB4957484DBC1463504A007735`。
- 本机预览 PID `61672`，仅监听 `127.0.0.1:18090`；首页和 `app.js` 已返回新缓存版本，受保护 operations API 返回主机 `DESKTOP-NTRMANG` 的实时指标。
