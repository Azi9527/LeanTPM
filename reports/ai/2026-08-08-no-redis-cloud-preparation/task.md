# LeanTPM 无 Redis 版本与云部署准备任务规格

## 1. 任务状态与授权边界

- 调查日期：2026-08-08（Asia/Shanghai）
- 可信基线：`2185536ea9da0a323b27f53dcf849b818ea19069`
- 工作树：`C:\Users\Mayn\.codex\worktrees\fca1\LeanTPM`
- 风险等级：L4（身份认证、令牌撤销、登录锁定、跨 161 个写接口的幂等、数据库迁移、跨端 API 合同、生产部署准备）
- 当前阶段：只读调查与设计门禁；尚未开始本需求的业务代码修改。
- 明确禁止：连接或写入云端 MySQL、注册/启停 Windows Service、修改 Caddy、开放端口、部署、push、PR、写入外部系统。
- 后续生产动作前置条件：无 Redis 版本完成全部代码和隔离环境验证，并由用户明确确认。

当前链接工作树包含此前发布平台任务的未提交改动。本任务必须在其上做精确增量，禁止 reset、clean、stash、覆盖或纳入原工作区用户文件。

## 2. 启动与 GitNexus 证据

已执行并核对：

- `git status --short --branch`
- `git worktree list --porcelain`
- `git rev-parse HEAD`
- `npx.cmd gitnexus status`
- 完整阅读 `AGENTS.md`、`docs/ai/development-workflow.md`、`docs/ai/task-template.md`、`docs/ai/gitnexus-workflow.md`

GitNexus 状态：

- 链接工作树路径报告 `Repository not indexed`，未使用 `--force` 重建。
- 原项目 `D:\codex\LeanTPM` 的索引为 up-to-date，精确对应同一提交 `2185536`。
- 已通过该可信索引执行 `query → context → impact`，源码搜索仅用于补足 AOP、配置和脚本等图谱盲区。

影响分析：

| 目标 | GitNexus 结果 | 实际判定 | 说明 |
|---|---:|---:|---|
| `RedisAuthSessionService` | HIGH，18 个上游符号、12 个直接依赖、3 个模块 | HIGH / L4 | 登录、JWT 过滤、刷新、注销、在线用户、踢下线、停用用户和重置密码均依赖 |
| `AuthService.login` | LOW，2 个直接上游 | HIGH / L4 | 认证入口、事务和失败锁状态都在此汇合，图级局部值不能降低安全风险 |
| `CaptchaService` | LOW，4 个直接上游、5 个总影响 | L3 | 后端局部影响不大，但 PC/APP 合同和连接探针构成 breaking change |
| `IdempotencyAspect.execute` | LOW，图上 0 上游 | HIGH / L4 | AOP pointcut 未被调用图建模；源码确认 17 个 Controller、161 个 `@Idempotent` 写接口 |
| `JwtAuthenticationFilter.doFilterInternal` | LOW，图上 0 上游 | HIGH / L4 | 每个携带 Bearer Token 的受保护请求都经过该过滤器 |

结论：不得一次性删除 Redis 类。实施必须先稳定抽象合同，再用数据库实现替换；验证码、会话和幂等分别作为可回滚批次处理。

## 3. 需求理解表

| 范围 | 当前理解 | 明确完成条件 | 不在当前阶段执行 |
|---|---|---|---|
| 无 Redis 启动 | 删除运行依赖、客户端、配置、Secret、健康检查和启动门禁 | 后端 classpath、配置、健康组、部署模板均无 Redis；无 Redis 服务仍启动并 readiness 通过 | 连接云服务器验证 |
| 会话与 JWT | 保留服务端会话状态、即时注销、刷新令牌轮换和重放检测 | 状态持久化于 MySQL，重启后仍生效；访问令牌不能在注销后继续使用 | 降级为纯无状态 JWT |
| 登录锁定 | 失败计数和锁定窗口不能因进程重启丢失 | 数据库原子更新，失败认证事务回滚时计数仍提交 | 内存 Map、仅查询可能回滚的登录日志 |
| 幂等 | 现有 161 个写接口的请求占位、冲突检测和结果重放不能退化 | MySQL 唯一键、租约、指纹和完成结果持久化；并发与重启行为可证 | 删除 AOP、绕过保护执行请求 |
| 验证码 | 完整删除创建、核销、DTO、接口、活跃配置、PC/APP UI 和测试 | `/auth/captcha` 不再存在；登录合同不再带验证码字段；活跃代码/配置/测试零残留，历史迁移与历史证据仅精确 allowlist | 用数据库继续实现验证码 |
| 连接探针 | 当前 PC/APP 以验证码接口探测，存在“探针成功但登录 503”的假阳性 | 改用现有匿名 `/api/v1/public/branding` 并统一跨端响应校验 | 使用管理型 Actuator 详情作客户端探针 |
| 数据库 | 采用 Expand → Migrate → Contract，迁移可重复、可验证、可恢复 | 新表、索引、清理策略和兼容门禁经隔离 MySQL 验证 | 未授权的云端数据库初始化 |
| 云部署 | 无 Redis 版本通过后再产包、上传、备份、初始化、服务化和 Caddy 正式配置 | 必须由用户确认后另行执行 | 当前阶段的任何远程变更 |

## 4. 现状责任盘点

### 4.1 运行时 Redis 的三个直接实现

1. `RedisAuthSessionService`
   - 会话注册与访问校验；
   - access token 每次请求的服务端会话确认；
   - refresh token JTI 原子轮换与复用检测；
   - 单会话注销、用户全部会话撤销、在线列表和踢下线；
   - 登录失败次数、窗口和临时锁定；
   - 会话、撤销、租户集合、用户集合等 TTL。
2. `CaptchaService`
   - 生成验证码和 SVG；
   - Redis 保存摘要与 5 分钟过期；
   - Lua 一次性核销；
   - `security.captcha.enabled` 配置开关。
3. `IdempotencyAspect`
   - 为 `Idempotency-Key` 建立 Redis 原子占位；
   - 请求指纹冲突检测；
   - PROCESSING 和 COMPLETED TTL；
   - 完成响应序列化与重复请求重放；
   - 异常时按 token 原子释放占位。

未发现其他已实现的 Redis 业务缓存。设计文档中曾规划的大屏缓存当前仍为数据库直接聚合，不能把历史规划误认作现有运行依赖。

### 4.2 关键事务问题

- `AuthService.login` 标记为 `@Transactional`。
- 密码失败时先写 `system_login_log`、记录 Redis 失败次数，随后抛出继承自 `RuntimeException` 的 `BusinessException`。
- 若把失败次数直接替换为同一事务内的数据库写，该状态会随异常一起回滚。
- `AuthMapper.countRecentFailures` 虽已存在，但同事务的失败日志也可能回滚，不能直接作为可靠锁定依据。
- `AuthService.refresh` 当前标记为 `@Transactional(readOnly = true)`；数据库刷新令牌轮换需要写事务和条件更新。

### 4.3 跨端验证码与探针合同

后端涉及：Controller、DTO、LoginRequest、AuthService、CaptchaService、SecurityConfig、OpenAPI、V2 种子和测试。

PC Web 涉及：auth API、auth store、登录页、移动服务器配置页。

LeanTPM APP 涉及：auth API、session store、登录页、服务器连接工具和测试。

当前验证码关闭时 `/auth/captcha` 可返回成功，但登录仍无条件调用 Redis 会话服务。因此 PC/APP 的“连接成功”可能是假阳性。

### 4.4 配置、健康与运维残留

Redis 还存在于：

- Maven 依赖；
- `application.yml`、`application-prod.yml`；
- readiness 组和 Redis health；
- OpenAPI 的 `REDIS_UNAVAILABLE` 说明；
- Windows starter 环境变量白名单、Secret 引用、有效配置合同；
- 备份、恢复、运行配置校验和发布平台合同测试；
- 生产环境示例和运维文档；
- 旧验证码 E2E 脚本。

上述资产必须同步更新；只删 Java 依赖会导致启动、配置验证、备份或发布门禁漂移。

“零残留”口径：活跃代码、运行配置、OpenAPI、构建/发布包、当前规范与运维文档、数据库有效参数、自动化测试中不得再有 Redis/验证码合同；已经执行且不可变的 V2 迁移不得改写，历史交付证据只允许保留并明确标注 superseded。自动扫描必须使用精确白名单区分两者，不能为通过扫描而篡改历史。

## 5. 目标架构

### 5.1 稳定服务合同

先引入与存储无关的 `AuthSessionService`，保持现有消费者的业务语义：

- `register`
- `validateAccess`
- `rotate`
- `revoke`
- `revokeAllUserSessions`
- `list`
- `kickout`
- `assertLoginAllowed`
- `recordLoginFailure`
- `clearLoginFailures`

最终运行实现仅为数据库实现，不保留 Redis fallback 或双运行开关。抽象步骤用于缩小改动面和测试语义，不代表最终继续依赖 Redis。

### 5.2 数据表建议

#### `auth_session`

- 主键：不可预测的 `session_id`；
- 归属：`tenant_id`、`user_id`；
- 展示快照：用户名、姓名、登录 IP、User-Agent、登录时间、最近活动时间；
- 生命周期：`expires_at`、`status`、`revoked_at`、`revocation_reason`；
- 刷新安全：只保存当前 refresh JTI 的 SHA-256 摘要，不保存明文 token/JTI；
- 并发：`version` 或条件更新；
- 索引：租户+状态+到期时间、租户+用户+状态、到期清理索引。

语义：

- access token 每次受保护请求必须查 ACTIVE 且未过期的会话；
- logout、停用账户、改密均可即时撤销；
- refresh 在同一事务中使用 `SELECT ... FOR UPDATE`（或等效存储过程）读取并锁定权威会话行，再比较状态、到期时间和 JTI 摘要；匹配时轮换，不匹配时先写入 `REVOKED/REFRESH_TOKEN_REUSED`；
- 事务方法返回结果枚举，调用者只在事务提交后抛 401。禁止在写入撤销后直接抛运行时异常，也禁止条件更新返回 0 后再用另一事务撤销；
- 撤销记录至少保留到 access/refresh token 的最大有效期之后；
- `last_active_at` 可按时间阈值节流更新，但不能跳过状态校验。

#### `auth_login_security_state`

- 已存在用户优先以 `tenant_id + user_id` 标识；未知用户名使用与数据库排序规则一致的规范化后 HMAC 摘要，避免直接保存可枚举用户名；
- 字段：`failure_count`、`window_started_at`、`locked_until`、`last_failure_at`、`version`；
- 原子 upsert/条件更新；
- 成功登录清零；
- 失败记录使用独立 `REQUIRES_NEW` 事务，在外层认证失败回滚时仍持久化；
- 锁定响应对“用户不存在、已停用、密码错误”保持一致，避免账号枚举。

`system_login_log` 继续作为审计记录，但不承担唯一安全状态源。失败审计同样需要独立事务或明确的事务事件机制。

`REQUIRES_NEW` 必须位于独立 Spring Bean 或使用显式 `TransactionTemplate`，禁止在 `AuthService` 内部自调用导致事务代理失效。失败审计与 throttle UPSERT 在同一短事务提交，返回后才抛 401/429；同时缩小或移除 `login()` 的外层长事务，避免并发失败登录耗尽嵌套连接。

当前 Redis 只做 `trim().toLowerCase()`，而 MySQL 用户名使用大小写/重音不敏感排序规则，存在通过等价拼写分散计数的风险。新实现必须先按数据库规则解析用户，再绑定稳定 user ID；未知用户仍返回相同外部响应，避免枚举。

#### `request_idempotency`

- 主键：`tenant_id + key_hash`；
- 字段：请求指纹、状态、租约 token、fencing/version、租约到期、完整 HTTP 状态码、内容类型、有大小上限的响应 payload、完成时间、记录到期和错误分类；
- 状态：`PROCESSING`、`COMPLETED`、`UNKNOWN`；
- 唯一键抢占保证并发只有一个执行者；
- 相同 key 不同指纹返回冲突；
- COMPLETED 返回已保存响应；
- acquire 必须在业务方法执行前以独立短事务提交；AOP 与事务 advisor 的顺序必须显式声明并测试，不能依赖 Spring 默认顺序；
- completion 只在事务管理器确认业务事务提交后，以 owner/fencing 条件更新；只有确认业务回滚且不存在文件/外部系统等非事务副作用时才释放，其余状态转为 UNKNOWN；
- COMPLETED 必须能重放相同状态码、内容类型和响应 payload；仅保存二进制摘要不能满足现有重复请求响应合同；
- 进程在业务提交后、写入 COMPLETED 前崩溃时，过期 PROCESSING 转为 UNKNOWN 并 fail closed，要求查询业务结果，不能盲目重放写操作；
- 清理由有界批量任务完成，并保留必要审计窗口。

### 5.3 服务器连接合同

优先复用现有匿名只读 `GET /api/v1/public/branding`，而不是为本次删除新增另一条公共 API：

- 该接口已经是 PC/APP 的公开品牌合同并访问数据库配置，可同时证明 API 路由和数据库基本可用；
- PC 与 APP 校验统一的 `ApiResponse` 和必要品牌字段，不能只看 HTTP 200；
- 连接测试必须使用本次实时网络响应；客户端缓存或离线 branding fallback 不得被算作连接成功；
- 不返回主机名、IP、路径、数据库、Secret 或详细健康信息；
- 客户端随后仍须以实际登录结果作为认证可用性结论。

不继续复用 `/auth/captcha`，也不把 Actuator 详细健康信息暴露给客户端。当前 OpenAPI 对 public branding 的匿名属性与 `SecurityConfig` 存在漂移，实施时必须修复并用合同测试证明 OpenAPI Bearer 要求与运行授权一致。若后续确需独立服务身份握手，应作为单独 API 合同变更评审，不夹带在本次删除中。

## 6. Given / When / Then 验收标准

1. **无 Redis 启动**
   - Given 主机未安装/未启动 Redis，且环境中没有任何 Redis 变量
   - When 使用生产配置启动后端
   - Then 应用启动成功，liveness/readiness 不包含 Redis 且无 Redis 连接日志或告警
2. **登录、退出、重新登录**
   - Given 有效用户与干净会话状态
   - When 登录、访问受保护接口、退出、再用原 token 访问并重新登录
   - Then 首次访问成功，退出后原 token 立即 401，新登录获得可用的新会话
3. **APP 与 PC 登录**
   - Given PC 和 APP 指向同一无 Redis 后端
   - When 分别执行连接测试和账号密码登录
   - Then 两端通过 public branding 合同确认服务，并使用一致登录合同成功认证，不发送验证码字段
4. **验证码完全移除**
   - Given 构建后的后端、PC、APP 和发布配置
   - When 检查路由、OpenAPI、UI、DTO、配置、种子、脚本和产物
   - Then `/auth/captcha` 的 Handler/OpenAPI 不存在，匿名和认证请求均不返回 200/验证码（正常路由判定为 404）；页面无验证码，登录 DTO 无相关字段，运行和发布资产无验证码配置；不可变 V2 和 superseded 历史证据仅按精确白名单保留
5. **登录失败锁定**
   - Given 某租户用户在锁定窗口内连续失败
   - When 达到阈值并继续尝试正确或错误密码
   - Then 返回统一锁定响应；窗口到期后恢复；不存在用户不能用于枚举；锁定状态不可读时在校验密码前 fail closed
6. **JWT/会话过期**
   - Given 已签发 access/refresh token 和持久会话
   - When access/refresh 到期、有效 refresh 轮换、旧 refresh 重放或并发刷新
   - Then 过期 token 被拒绝，有效 refresh 原子轮换且并发仅一次成功，旧 refresh 重放在同一事务撤销会话，状态不会因重启复活
7. **注销令牌安全**
   - Given 用户已登录且 token 未自然过期
   - When 注销、改密、停用用户或管理员踢下线
   - Then 相关会话立即撤销，旧 access/refresh token 均不可再使用；会话状态数据库异常时返回 503，JWT 过滤器不得把请求降为匿名后继续
8. **重启后安全状态不丢失**
   - Given 存在活动会话、已撤销会话、登录锁定和幂等完成记录
   - When 重启后端
   - Then 四类状态与重启前一致，不能恢复已撤销 token 或丢失锁定/幂等结果
9. **幂等不退化**
   - Given 相同/不同指纹的并发重复写请求和进程中断场景
   - When 使用同一 Idempotency-Key 调用任一受保护写接口
   - Then 单次执行、相同响应重放、指纹冲突拒绝、未知提交状态 fail closed；acquire 不可用时不得进入业务方法，completion 不确定时转 UNKNOWN 而非释放后重试
10. **无 Redis 残留**
    - Given 源码、依赖树、配置、健康、部署、备份、Secret、文档和发布包
    - When 执行自动残留扫描与启动测试
    - Then 无 Redis 客户端、配置项、健康项、环境变量、Secret 引用、错误码或运行要求
11. **迁移可重复与可恢复**
    - Given fresh 数据库以及代表性 V48 数据库快照
    - When 执行升级、重复执行、校验和故障注入
    - Then 首次升级成功、第二次 no-op、校验通过；失败不产生可误用的半状态，并有前滚/隔离恢复步骤
12. **旧数据兼容**
    - Given V48 的用户、会话外业务数据、登录日志、设备、点检、附件等样例
    - When 升级到新 schema 并启动无 Redis 版本
    - Then 原业务数据保持可读写；历史登录日志不被破坏；新安全表从空状态安全启动
13. **跨端合同一致**
    - Given 后端 OpenAPI、PC 类型和 APP 请求实现
    - When 校验登录、刷新、注销和 public branding 合同
    - Then 路径、字段、错误码和可选性一致，无验证码或 `REDIS_UNAVAILABLE`
14. **核心模块不回归**
    - Given 设备、点检、附件、权限、组织和报表的代表性读写流程
    - When 在无 Redis 版本上执行模块回归
    - Then 权限、租户隔离、幂等、数据和附件行为与基线一致
15. **构建与关键业务流通过**
    - Given 干净依赖环境和隔离 MySQL
    - When 执行后端单元/合同/集成、PC typecheck/build、APP verify/build 和关键 E2E
    - Then 所有必需门禁 PASS；跳过项有明确理由且不能被误报为 RELEASEABLE

## 7. 风险分级

| 风险 | 等级 | 控制措施 |
|---|---:|---|
| 注销后 token 重新可用、刷新重放未发现 | L4 / CRITICAL | DB 会话状态、JTI 摘要条件更新、重放撤销、重启测试 |
| 登录失败状态随外层事务回滚 | L4 / CRITICAL | `REQUIRES_NEW`、原子 upsert、真实 MySQL 并发测试 |
| 幂等在进程崩溃窗口重复执行业务 | L4 / CRITICAL | PROCESSING/COMPLETED/UNKNOWN 状态机、业务结果查询、故障注入 |
| AOP 影响图假阴性导致漏测 161 个接口 | L4 | 静态枚举 `@Idempotent`、分模块合同回归、代表性业务 E2E |
| 验证码删除造成 PC/APP breaking change | L3 | 同批修改三端合同，复用 public branding 探针，合同测试 |
| PC refresh 遇到任意错误即清 token，而 APP 对 503 保留会话 | L3 | 统一瞬时依赖故障与认证失效语义，PC/APP 都只在确定的 401/撤销错误时清会话 |
| PC/APP “记住密码”存在 localStorage/可逆 fallback | L4（既存发布风险） | PC 交给浏览器密码管理器；APP 仅允许已验证 Keystore，失败时不保存密码；正式发布前真机证明 |
| V48 → 新 schema 升级或回滚不安全 | L4 | Expand→Migrate→Contract、隔离快照、前滚优先、备份恢复 |
| 当前 D 盘布局与发布平台默认 C 盘信任根不一致 | L4（后续部署） | 无 Redis 验收后单独设计 host-owned D 盘 root pair，禁止把生产根伪装为 NON_PRODUCTION |

## 8. 角色与文件所有权

| 角色 | 默认权限 | 所有权/关注范围 |
|---|---|---|
| 产品/验收 | 只读 | 本任务规格、15 条 GWT、PC/APP/后端合同和兼容边界 |
| 架构与安全 | 只读 | 会话、锁定、幂等状态机、事务、威胁、迁移与回滚审查 |
| 后端实现 | 可写（实施阶段） | `backend/pom.xml`、认证/安全/幂等 Java、Mapper、Flyway、后端测试 |
| 前端实现 | 可写（实施阶段） | `frontend/src` 中 auth、store、登录和连接设置及合同测试 |
| APP 实现 | 可写（实施阶段） | `LeanTPM-APP` 中 auth、session、登录、server 工具和 Node 测试 |
| 发布/配置实现 | 可写（后端通过后） | application 配置、deploy/windows、备份/恢复/运行配置合同和文档 |
| 测试 | 只读优先 | 测试矩阵、故障注入、独立证据，不修改实现 |
| 独立代码审查 | 只读 | 安全、事务、跨端合同、残留、回滚和差异审查 |

同一文件同时涉及多个角色时，由实现角色修改，安全/测试角色只提交审查意见，避免并发覆盖。

## 9. 实施阶段与失败测试顺序

### 批次 A：合同与数据库 Expand

1. 失败测试：数据库会话合同、登录锁状态、JTI 并发轮换、幂等状态机和 V48→新版本迁移。
2. 新增存储无关接口及数据库表/Mapper/事务服务。
3. 保持消费者行为不变，先让新实现满足原安全语义。
4. 隔离 MySQL 验证 fresh、V48 升级、重复 migrate 和并发。

### 批次 B：切换会话与登录锁

1. 失败测试：无 Redis context 启动、注销即时失效、改密/停用/踢下线、刷新复用、重启保持、锁定事务。
2. 消费者依赖改为 `AuthSessionService`。
3. 移除 `RedisAuthSessionService`，调整 refresh 写事务。
4. 后端窄测和认证回归。

### 批次 C：切换数据库幂等

1. 失败测试：同 key 同指纹、不同指纹、并发、业务异常、提交后中断、租约过期、重启重放。
2. 替换 AOP 存储实现，不改变 `@Idempotent` API 使用方式。
3. 按 17 个 Controller 分组回归，优先设备、点检、附件、权限、组织和报表。

### 批次 D：删除验证码并统一跨端合同

1. 先写后端 404/DTO 合同、PC/APP 无验证码字段和 public branding 探针的失败测试。
2. 删除验证码服务、DTO、路由、登录字段和配置参数。
3. PC 与 APP 同批删除 UI/请求字段，连接探针改为现有 public branding。
4. 删除旧验证码 E2E，新增无 Redis 登录/注销/重启 E2E。

### 批次 E：删除运行与发布残留

1. 移除 Maven Redis starter、YAML、health/readiness、OpenAPI 503 说明。
2. 更新 Windows starter、Secret、effective-config、备份/恢复、发布合同测试和文档。
3. 自动扫描源代码和构建产物，确认无 Redis/验证码残留。

### 批次 F：隔离验证与独立审查

1. 仅在用户明确授权的非云、非生产隔离 MySQL 执行写测试；必须同时提供 `ConfirmIsolatedDatabase`、精确 `ExpectedServerUuid`、CLI CA、Java truststore、唯一动态库名和最小权限临时账户。
2. 任何写演练前确认目标、备份和恢复方案；finally 只清理精确动态库名，凭据不得出现在 argv、日志或报告。缺少任一条件即跳过并明确标为 NOT VERIFIED。
3. 测试、产品和安全独立复核；最多三轮修复。
4. GitNexus analyze/detect-changes、测试、清理门禁、`git diff --check`。

## 10. 测试矩阵

| 层级 | 必测内容 | 当前可否执行 |
|---|---|---|
| 单元 | 会话状态、JTI 摘要轮换、登录锁窗口、幂等状态转换、public branding 探针、验证码合同删除 | 可离线，实施后执行 |
| 合同 | OpenAPI/PC/APP 登录 DTO、错误码、public branding、无 Redis/验证码残留 | 可离线 |
| 事务集成 | 失败登录独立提交、refresh 条件更新、踢下线/改密撤销 | 需隔离 MySQL 授权 |
| 并发 | 同会话 refresh 竞争、同 idempotency key 并发、锁计数并发 | 需隔离 MySQL 授权 |
| 迁移 | fresh→latest、V48→latest、第二次 no-op、checksum、迁移中断 | 需隔离 MySQL、备份和恢复计划 |
| 重启 | 活动/撤销会话、锁定和幂等完成/未知状态跨重启 | 需隔离后端进程和 MySQL |
| PC | typecheck/build、登录 UI、连接设置、注销、错误处理 | 可离线构建；E2E 需隔离后端 |
| APP | `npm run verify`、登录、连接设置、刷新/注销合同 | 可离线；平台构建仍受 HBuilder 工具链锁阻断 |
| 模块回归 | 设备、点检、附件、权限、组织、报表代表性读写和幂等 | 需隔离 MySQL |
| 故障注入 | DB 短断、死锁/超时、提交后中断、进程重启、重复请求 | 需隔离环境 |
| 发布残留 | dependency tree、配置/Secret/health/文档扫描、发布包内容 | 可离线 |
| Windows/云端 | Windows Service、Caddy、D 盘 ACL、正式 DB、端到端 | 用户确认无 Redis 版本后另行授权 |

当前候选实现的最新离线证据：跨仓 Node 合同 82/82（发布平台 71、无 Redis 6、认证 E2E 静态合同 1、PC 合同 4），APP verify 26/26，63 个相关 PowerShell 脚本 AST 解析 0 错；后端 Maven 离线测试 144 项、0 failure/error，其中 84 项实际通过、60 项 MySQL 条件测试因本地门禁未配置隔离数据库而明确跳过；独立运维控制面 Maven 49/49，PC typecheck/build 均通过。上述结果只证明离线代码门禁，不替代正式双 CMS 包、目标 Windows Agent、生产补偿或恢复演练。

### 10.1 十五条验收的计划证据映射

以下文件均为实施后计划生成的当前轮证据，统一写入 `reports/ai/2026-08-08-no-redis-cloud-preparation/validation/`，不得引用旧 `target-*` 或历史发布报告代替。

| # | 自动测试/脚本 | 证据文件 | PASS 判定 |
|---:|---|---|---|
| 1 | `verify-auth-e2e.ps1` 的隔离后端启动 + live/ready/info 门禁 | `01-no-redis-startup.json`、脱敏启动日志 | 进程启动、live/ready UP、无 Redis 连接尝试 |
| 2 | `verify-auth-e2e.ps1` 登录/注销/重登流程 | `02-login-logout-relogin.xml` | 登录/访问/注销/旧 token 拒绝/重登全通过 |
| 3 | PC auth 组件/E2E + APP `auth-contract.test.js` | `03-cross-client-login.json` | 两端只发 username/password，成功与错误合同一致 |
| 4 | `no-redis-contract.test.mjs` + `verify-auth-e2e.ps1` 的 `/auth/captcha` 运行断言 | `04-captcha-removal.json` | Handler/OpenAPI/UI/活跃配置/制品零残留，匿名请求非 200，白名单仅 V2/历史证据 |
| 5 | `AuthSecurityMySqlIntegrationTest` + `verify-auth-e2e.ps1` 锁定/重启流程 | `05-login-throttle.xml` | 原子计数、阈值、到期、重启和 fail-closed 全通过 |
| 6 | `AuthSecurityMySqlIntegrationTest` 的 50 路 refresh + `verify-auth-e2e.ps1` | `06-token-expiry-refresh.xml` | 仅一次轮换成功，重放撤销，过期与并发语义通过 |
| 7 | `verify-auth-e2e.ps1` + `JwtAuthenticationFilterTest` + session 单元测试 | `07-revocation.xml` | logout/改密/停用/踢下线后 access+refresh 均永久失效 |
| 8 | `verify-auth-e2e.ps1` 的多次受控后端重启流程 | `08-restart-state.json` | session/revocation/lock/idempotency 重启前后状态一致 |
| 9 | `AuthSecurityMySqlIntegrationTest` + `DatabaseIdempotencyStoreTest`/`IdempotencyAspectTest` + E2E | `09-idempotency.xml`、`09-faults.json` | 并发一次执行、结果重放、冲突、UNKNOWN、DB 故障 fail closed |
| 10 | `no-redis-contract.test.mjs` + Maven dependency tree + health/最终包扫描 | `10-zero-residual.json` | 活跃依赖/代码/config/Secret/health/docs/package 零 Redis |
| 11 | `MySqlMigrationIntegrationTest` + `run-mysql-integration.ps1` | `11-migration-repeat-recovery.json` | fresh/V48 升级、第二次 no-op、checksum、失败恢复均通过 |
| 12 | `MySqlMigrationIntegrationTest` + 模块 MySQL suite + 认证 E2E | `12-legacy-data.json` | V48 代表数据、核心模块读写/FK/权限及附件文件恢复结果升级前后一致；当前仅有测试资产，须在隔离环境执行后才可 PASS |
| 13 | OpenAPI 快照 + PC/APP request snapshot + compatibility matrix validator | `13-contract-compatibility.json` | 三端字段/路径/错误一致，旧客户端组合 BLOCKED |
| 14 | Equipment/Inspection/Attachment/DataPermission/Organization/OEE/Visualization 集成套件 | `14-core-regression.json` | 代表性读写、租户权限、附件和重复提交全部通过 |
| 15 | `verify-release.ps1 -OutputFormat Json` 聚合门禁 | `15-aggregate.json` + JUnit/TAP/产物 SHA256 | 测试数、skip、commit、toolchain、日志和哈希齐全且结论 PASS |

## 11. 数据库迁移与回滚

### Expand

- 新增三类安全表、索引和最小必要约束；不修改或删除现有业务列；
- 数据库实现可以从空安全状态开始，历史 V48 业务数据无需回填 Redis 会话；已有 token 在切换窗口统一失效并要求重新登录；
- 上线前应明确这一兼容行为并在维护窗口执行。

根据用户当前说明，云端最终业务数据库尚未初始化，也不存在已批准投产的 Redis 会话状态，因此本轮不设计生产 Redis→MySQL 双写迁移。若后续发现已有真实用户或需保留的 Redis 状态，必须暂停并重新评审 cohort/store-epoch 方案；严禁以“DB 未找到就回退 Redis”，否则会复活已注销会话。

### Migrate / Cutover

- 先切换会话和登录锁，再切换幂等；每批都有独立验证和恢复决策点，恢复决策不等于允许旧应用回滚；
- 验证新数据库状态后才移除 Redis 依赖；
- 旧 Redis 中的临时会话、验证码和幂等键不迁入生产数据库，避免导入不可验证状态；
- 切换时撤销旧 token，以新 DB 会话为唯一信任源。

### Contract

- 删除验证码 API、DTO、现存参数、Redis 依赖和发布配置；
- 不修改已执行的 V2 历史迁移；通过新的 Contract 迁移精确删除现存 `security.captcha.enabled` 行，脚本按 tenant/key 幂等；旧代码在缺失该参数时默认 false，但仍需在隔离环境验证应用回滚；
- Contract 不与未经验证的破坏性 schema 变更混包。

### 客户端兼容与版本门禁

- PC 静态资源与后端必须在同一发布包中原子切换，禁止新 PC 对旧后端或旧 PC 对新后端的未声明组合。
- 删除 `/auth/captcha` 是公开合同破坏；release-manifest 兼容矩阵必须把旧 PC/APP 组合标为 BLOCKED，并为 APP 写入新的最低 `versionCode`/强制升级策略。旧 APP 不能通过反复调用已删除接口形成登录死循环。
- 若任何 1.x 已对外正式发布，统一产品版本必须按 SemVer 升 MAJOR；若仓库仍是从未正式发布的候选基线，只有在用户确认后才可采用新的预发布/次版本号，并在 CHANGELOG 明确 breaking contract。版本号在无 Redis 实现和合同冻结后最终确定。

### 回滚策略

- **代码未切换前**：撤回新代码，新表保持未使用，不删除数据。
- **会话切换后**：默认禁止直接回滚到依赖 Redis 的旧应用，因为目标环境不再具有可信 Redis 运行时；恢复路径为前滚修复或保持停机。只有另行恢复并验证 Redis、完成安全状态反向同步且强制全部用户重新登录后，才可例外批准旧应用回切。
- **幂等切换后**：首次 DB 幂等保护的生产写入发生后，默认禁止直接回滚到 Redis 实现；旧版看不到 DB 中的 COMPLETED/UNKNOWN，会重复业务。恢复路径只能是前滚修复或只读停机。所有 UNKNOWN 必须先完成业务对账并归并为可重放的 COMPLETED 或明确终止，UNKNOWN 数量为 0；随后反向同步全部未过期 COMPLETED、恢复并验证 Redis、再排空完整保留期后，才可评估例外回切。任一条件不满足即始终 BLOCKED。
- **迁移失败**：不做自动 down migration；保持服务停止或只读，保留备份和 Flyway 证据，以前滚修复或恢复到隔离新库为主。
- **数据库时间点恢复**：恢复旧快照前先写入不随业务 DB 回退的 host/control-plane recovery-inhibit；Backend 启动前提升外部认证 epoch 或轮换 JWT 签名密钥并强制重登录，防止快照中的 ACTIVE 会话让已撤销 token 复活。快照后的幂等 key 在恢复库中与“从未使用”无法区分，因此 PITR、外部审计或业务对账闭合缺口前，所有幂等写接口必须保持只读/fail closed，不能把无记录请求当新请求执行。
- **验证码 Contract 后**：参数缺失时旧代码虽推断为 false，但这不足以允许旧应用回滚；仍受上述“恢复完整可信 Redis 兼容栈或前滚修复”的门禁约束，并必须由真实隔离测试证明。

## 12. 云服务器准备差距（后置）

用户给出的目标生产根为 `D:\LeanTPM`：packages=`D:\LeanTPM\packages`、releases=`D:\LeanTPM\releases`、current backend/frontend=`D:\LeanTPM\current\backend|frontend`、config=`D:\LeanTPM\shared\config`、uploads=`D:\LeanTPM\shared\uploads`、data=`D:\LeanTPM\data`、backups=`D:\LeanTPM\backups`、logs=`D:\LeanTPM\logs`、tools=`D:\LeanTPM\tools`、temp=`D:\LeanTPM\temp`。端口合同为 Caddy 80/443、Backend `127.0.0.1:18080`；18080、3306、15173 不向公网开放。

旧 C 盘托管 profile 已被本任务的主机 bootstrap 策略取代；当前生产候选根为 `D:\LeanTPM\App` 与 `D:\LeanTPM\Runtime`，并采用既有 `caddy` 服务的 `EXTERNAL_EXISTING` 合同。该候选尚未取得真实主机验证或部署授权。

后续不得直接用 `AllowNonProductionRoot` 把 D 盘生产根伪装成非生产环境。无 Redis 版本确认后，需要单独完成：

- host-owned 的 D 盘 production root pair、精确子目录映射和 ACL 合同；
- 默认复用现有 `caddy` 服务并审计其二进制 pin、账户、DACL 和配置；只有用户另行批准才迁移到 `LeanTPM.Proxy`，决策前禁止并存第二套代理；
- Caddy 静态目录、`/api` 代理、附件访问、日志轮转和安全头；
- 域名、DNS、HTTPS 证书与 80/443 策略；
- MySQL 固定 server UUID、TLS/本机边界、备份与恢复演练；
- 禁止公网开放 18080、3306、15173；
- 17.66GB C 盘余量不足以承担发布 staging/备份，产物和数据应经审查后落在 D 盘，且预留双版本、解包和回滚空间。

## 13. 当前门禁结论

**状态：OFFLINE-CODE-GATES-PASS，ISOLATED-ENVIRONMENT-NOT-VALIDATED，NOT_RELEASEABLE，CLOUD-NOT-AUTHORIZED。**

进入编码的最小门禁：

1. 本规格经产品、架构安全和测试角色复核；
2. 每批修改前对具体符号重新执行 GitNexus impact；
3. 先提交可观察失败的测试；
4. 数据库写测试仅在用户授权的隔离 MySQL 上执行；
5. 不把“编译通过”或当前 71/71 发布平台测试当成全部生产验收完成；正式双 CMS 包、目标 Windows ReleaseAgent、补偿与恢复证据仍须单独取得。

## 14. HostBootstrap 只读发现批次（L4）

### 14.1 需求理解与影响

| 需求 | 本批边界 | 非本批授权 |
|---|---|---|
| 为固定生产 bootstrap 提供可重复输入 | 新增严格 schema 与只读发现器，派生脱敏 host/volume 身份、固定 App/Runtime 候选和既有 Caddy 观察值 | 不创建 `C:\ProgramData\LeanTPM-bootstrap`，不写 D 盘根，不改 ACL/注册表/SCM/防火墙 |
| 防止发现结果被误当信任锚 | 结果固定 `INPUT_REQUIRED`、`executable=false`、`trustSource=UNTRUSTED_READ_ONLY_DISCOVERY` | 不生成 READY layout，不批准 signer/toolchain，不替代 HostBootstrap verifier |
| 支持离线失败测试 | `-PlanOnly -ObservationPath` 仅接受严格 fixture；真实模式禁止 ObservationPath | 不提供生产 mock/bypass 开关 |
| 保护敏感标识 | 只输出规范化 SHA-256 hostId/volumeIdentity，不输出 MachineGuid、SMBIOS UUID、卷 DeviceID 或凭据 | 不读取服务环境、Secret、证书私钥或数据库配置内容 |

GitNexus 基线不含新增 PowerShell 发布符号，`query` 误命中业务 bootstrap，`context/impact` 为 UNKNOWN；本批按 L4 保守治理。发现器是新增叶子资产，不修改现有生产符号；未来任何初始化执行器必须重新做 impact、双审批和真实 Windows 演练。

### 14.2 Given / When / Then

1. **Given** 严格的离线 observation fixture，**When** 以 `-PlanOnly` 运行，**Then** 输出与 schema 一致的 `INPUT_REQUIRED` 报告，且测试树字节、路径、属性不变。
2. **Given** fixture 含原始 MachineGuid/SMBIOS/DeviceID，**When** 生成报告，**Then** 只输出规范化摘要，报告和日志不得包含原始值。
3. **Given** 非 PlanOnly 调用携带 ObservationPath，**When** 运行，**Then** 在任何查询或写操作前拒绝，防止生产 mock 注入。
4. **Given** Caddy 服务缺失、重复、命令行不精确、80/443 owner 不一致、卷不是固定 NTFS 或目标根位于不同卷，**When** 发现，**Then** 保持 `INPUT_REQUIRED` 并列出阻断项，不声称 host filesystem 已验证。
5. **Given** 任何调用，**When** 执行，**Then** 源码不得包含目录创建、ACL/注册表写、服务启停、防火墙变更或文件覆盖动作。

### 14.3 角色与文件所有权

| 角色 | 所有权 | 权限 |
|---|---|---|
| 主实现 | `deploy/windows/Get-LeanTpmHostBootstrapDiscovery.ps1`、discovery schema | 可写 |
| 测试 | `scripts/tests/release-platform.test.mjs` 对应合同与行为 harness | 测试先行；独立复核默认只读 |
| 架构安全 | host/volume 摘要、mock 边界、敏感值与零副作用审查 | 只读 |
| 产品验收 | 将结果限定为 INPUT_REQUIRED，不提升 Phase1 状态 | 只读 |

### 14.4 测试矩阵与回滚

- 正例：严格 observation、同一固定 NTFS 卷、唯一 caddy、固定命令行、80/443 owner 一致。
- 负例：原始标识泄漏、未知/重复字段、错误类型、非 NTFS/非固定盘、C/D 混搭、重复服务、命令行漂移、listener 漂移、非 PlanOnly mock。
- 零副作用：PlanOnly fixture 文件树快照不变；源码命令黑名单与人工审查确认无写入/ACL/服务/防火墙 mutator；只运行 Windows PowerShell AST 和 Node 离线 harness。
- 回滚：删除新增 discovery 脚本/schema 与对应测试即可；不需要数据库、SCM、ACL、注册表或云端恢复。

### 14.5 当前结果

- 失败测试先证明 PowerShell 5.1 会静默覆盖顶层及 listener 对象的重复 JSON 键；实现改为严格 UTF-8、8 MiB 上限和逐对象 Ordinal 属性集合，重复/转义/未知属性均拒绝。
- 报告固定 `INPUT_REQUIRED / executable=false / UNTRUSTED_READ_ONLY_DISCOVERY`，显式区分 `LIVE` 与 `PLAN_ONLY_FIXTURE`，并将 `D:\LeanTPM\data` 唯一绑定为 `MYSQL_DATA / PRESERVE_EXTERNAL`。
- 对跨字段注入的 MachineGuid、SMBIOS UUID 和卷 DeviceID 做统一净化；输出摘要由 Node 独立重算，schema/报告字段逐项一致。
- 安全与测试独立复核均确认当前批次无 P0/P1；剩余 schema 交叉约束、超大 fixture 行为测试、LIVE mock harness、时间新鲜度与 live 路径哈希 TOCTOU 为 P2/后续 initializer 前置门禁。
- 本批只完成不可执行发现基础，不创建 bootstrap、不写 App/Runtime、不生成 adoption receipt，也不改变 Phase 1 的 `NOT_RELEASEABLE / CLOUD-NOT-AUTHORIZED` 状态。

## 15. HostBootstrap 签名初始化仪式（L4）

### 15.1 需求、信任域与影响

HostBootstrap 初始化不是普通首次发布：目标 `Runtime\config\release-trust.json` 尚不存在，不能用它验证创建自己的计划。初始化审批必须来自独立、预置且管理员/SYSTEM 所有的固定 bootstrap authority；authority 只能由基础镜像、企业 GPO/MDM 或另一项明确授权的主机预配仪式建立，本执行器不得创建、覆盖或接受调用方自由路径。

本批最终目标是实现以下完整链路，而不是把 caller-bound discovery 升级为可信：

| 能力 | 必须绑定 | 禁止行为 |
|---|---|---|
| 初始化计划 | LIVE discovery 摘要/时间、hostId、volumeIdentity、App/Runtime、`D:\LeanTPM\data` 保留、legacy inventory、layout/trust/Caddy policy 精确字节、服务 SID、nonce/expiry、双角色 | fixture、自供 trust、未签 plan、过期/重放、legacy 非空 |
| 固定 authority | `C:\ProgramData\LeanTPM-bootstrap-authority\init-trust.json`、owner/DACL/final handle、host/environment、请求人与审批人证书 pin、initializer signer pin | 由 plan 指定 authority 路径或在同一事务创建信任锚 |
| 初始化执行 | 全局 bootstrap 锁、锁内重跑 LIVE discovery/inventory、逐文件摘要、只创建本次拥有的临时根、精确 ACL、原子提升、最终 HostBootstrap/External Caddy policy 验证、回执/nonce ledger | 触碰 MySQL data、覆盖任何非空/既有根、隐式停启 Caddy、失败时删除用户对象 |
| 回执 | plan/authority/discovery/layout/trust/policy/host/volume 摘要、创建对象列表、最终 verifier 报告、审批 signer、nonce、时间 | 把“目录创建成功”当初始化成功或省略失败证据 |

GitNexus 当前索引只覆盖基线提交，HostBootstrap 新符号的 `query` 误命中业务代码，`context/impact` 为 `UNKNOWN`；本批按 L4/CRITICAL 信任边界治理，所有新增符号修改前逐一记录 UNKNOWN，不采用图上的 LOW 作为风险结论。

### 15.2 Given / When / Then

1. **Given** 固定 authority 已由外部主机预配并通过 owner/DACL/final-path/host 校验，**When** 校验双 CMS 初始化计划，**Then** 两个不同 pinned signer 必须签同一组 plan bytes，且 nonce/expiry/host/volume/discovery/layout/trust/policy 全部一致。
2. **Given** LIVE discovery 与 legacy inventory 均新鲜、canonical roots/固定 bootstrap 不存在、MySQL data 被标记保留，**When** 执行初始化，**Then** 只在受保护临时位置创建计划拥有的 App/Runtime/bootstrap 资产，逐字节复验后原子提升。
3. **Given** discovery/inventory/卷/authority/输入字节在审批后漂移，**When** 锁内重验，**Then** 在创建任何持久对象前失败。
4. **Given** 任一目录创建、ACL、文件写、原子提升、最终 verifier、回执或 ledger 步骤失败，**When** 补偿，**Then** 只移除本事务仍能证明拥有的临时对象；已提升或身份不明对象保持隔离并写入失败状态，绝不删除 legacy 或 `D:\LeanTPM\data`。
5. **Given** external Caddy 仍是现有公网入口，**When** HostBootstrap 初始化，**Then** 只落 host policy，不停启/重载 Caddy，也不生成 adoption receipt；adoption 是下一独立双审批转换。

### 15.3 角色与文件所有权

| 角色 | 所有权 | 权限 |
|---|---|---|
| 主实现 | bootstrap authority/initialization plan/receipt schemas、plan validator、initializer | 可写 |
| 测试 | `release-platform.test.mjs` 的真实临时树与 adapter/fault harness | 测试先行，可写测试；不写生产实现 |
| 架构安全 | 信任鸡生蛋、CMS/nonce、ACL/TOCTOU、原子提升和补偿审查 | 只读 |
| 产品验收 | 首装/legacy/adoption 顺序、Phase 1 证据映射 | 只读 |

### 15.4 失败测试矩阵与回滚

- 计划：fixture discovery、过期/未来 discovery、错误 host/volume/root、缺 preserved data、legacy `IMPORT_REQUIRED`、输入摘要漂移、重复/未知字段全部失败。
- authority/CMS：自由 authority 路径、authority 自签/同证书双签、过期/撤销/身份不匹配、plan bytes TOCTOU、nonce 重放全部失败。
- 文件系统：目标已存在或非空、父链 reparse/不可信 DeleteChild、卷漂移、磁盘满、ACL 失败、原子提升竞争全部失败。
- 状态机：每个 mutation 点故障注入；断言 legacy 与 MySQL data 前后字节/路径/属性不变，且只清理由事务 token 证明拥有的临时对象。
- 回滚：提交前只删除本批新增脚本/schema/test；真实 initializer 一旦获批执行，必须按回执逐对象处置，不能用仓库回滚替代主机恢复。

### 15.5 当前离线实现状态（2026-08-09）

- legacy inventory 已解除首次初始化循环依赖：显式 `AllowMissingCanonicalRoots + PlanOnly` 可在 `App/Runtime` 同时不存在时只读盘点；单边缺失、未显式批准及 custom-root 非 PlanOnly 均零写失败。inventory schema 记录 canonical roots 存在性，legacy import planner 的实时重验与 canonical 比对已包含该字段。
- 已新增严格的 bootstrap authority、初始化 plan、初始化 receipt 与 durable journal event schemas；authority 固定逻辑路径为 `C:\ProgramData\LeanTPM-bootstrap-authority\init-trust.json`，plan 不允许选择 authority 路径。
- authority/plan/receipt 三个验证器当前全部是 `PlanOnly / INPUT_REQUIRED / executable=false`：严格单字节快照、UTF-8、重复/未知字段、JSON 原生类型、固定 App/Runtime/MySQL 路径、LIVE discovery、时效、双角色、工具链/候选策略摘要与 separate Caddy adoption。它们不会把 caller SHA、候选 `release-trust.json` 或未签回执提升为可信。
- 初始化回执只能是 `BOOTSTRAP_COMMITTED_*_ADOPTION_REQUIRED`，且强制 `productionReady=false`、`externalCaddyAdopted=false`、`mysqlDataTouched=false`；External Caddy adoption 与 legacy import（如需）仍是独立签名仪式。
- 当前离线证据为跨仓 Node 82/82、发布平台 71/71、PowerShell AST 63/63；这只证明仓库候选和离线执行边界，不证明正式双 CMS 私钥 ceremony、目标主机 Agent/ACL、生产补偿或恢复演练。
- 进入可写 initializer 前仍须关闭：内置/签名发布物钉住的 authority-policy 根证书与固定 `.p7s` 验证；authority/状态目录真实 ACL/final-handle；严格 `release-trust.json` schema 与 toolchain 交叉绑定；umbrella/MySQL ACL independence 门禁；receipt/adoption 双回执 gate；nonce journal 和每个 mutation 点的 crash/fault harness。上述条件未满足前，可写初始化继续 fail closed，Phase 1 仍为 `NOT_RELEASEABLE / CLOUD-NOT-AUTHORIZED`。
