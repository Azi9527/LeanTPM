# GitNexus-First 代码理解与变更规范

本项目使用 GitNexus 进行代码理解、调用链调查、影响分析和提交前审计。本规范适用于代码理解、Bug 修复、新功能、重构、API/DTO 修改、数据库合同调整和代码审查；纯文档任务仅执行与文档范围相称的状态检查和质量门禁。

## 1. 开始前检查

在目标工作树根目录执行：

```powershell
git status --short --branch
npx.cmd gitnexus status
```

记录分支、用户未提交文件、索引提交和当前提交。保护全部用户未提交内容，不覆盖、不清理、不暂存、不自动格式化、不自动 stash。

如果索引缺失、陈旧或与当前提交不一致，执行普通分析：

```powershell
npx.cmd gitnexus analyze
```

如果仓库提供 `node .gitnexus/run.cjs analyze`，优先使用仓库内脚本。不要默认使用 `--force`。仅在索引损坏、上次分析未完成或普通分析无法恢复时，才允许：

```powershell
npx.cmd gitnexus analyze --force
```

## 2. 代码调查顺序

调查陌生功能时，严格按照以下顺序：

1. `query`：定位相关功能社区、模块和执行流。
2. `context`：确认关键符号的调用者、依赖和所属执行流。
3. `impact`：在修改符号前分析上游影响范围。
4. 阅读实际源文件和测试。
5. 写失败测试并实施最小修改。
6. `detect-changes`：检查真实变化范围是否符合计划。

全文搜索可以补充确认字符串、配置和动态引用，但不能替代调用图调查。

## 3. Query 使用规则

Query 描述功能或执行过程，不只写文件名或模糊名词。例如：

- 用户登录和权限校验流程；
- 页面保存设置的 API 调用链；
- 任务创建、执行和状态更新；
- 文件上传、校验和持久化；
- 缓存失效和重新加载。

MCP 调用优先使用：

```text
gitnexus_query({
  query: "[功能或业务概念]",
  goal: "[希望理解的问题]",
  task_context: "[当前任务]",
  repo: "[仓库名称或绝对路径]"
})
```

CLI 降级：

```powershell
npx.cmd gitnexus query "[功能或执行过程]" --goal "[调查目标]" --context "[任务上下文]" --repo "[仓库名称或绝对路径]"
```

Query 后报告：

- 涉及模块；
- 功能社区或 cluster；
- 相关执行流；
- 入口点；
- 关键符号；
- 可能的终点或持久化位置。

## 4. Context 使用规则

确定关键函数、方法、类、接口或组件后执行 Context：

```text
gitnexus_context({
  name: "[符号名称]",
  file_path: "[可选，用于消除重名]",
  kind: "[Class / Method / Function / Interface 等]",
  repo: "[仓库名称或绝对路径]"
})
```

CLI 降级：

```powershell
npx.cmd gitnexus context "[符号名称]" --file "[文件路径]" --repo "[仓库名称或绝对路径]"
```

Context 用于确认：

- 上游调用者；
- 下游依赖；
- import、extends、implements；
- 字段读写；
- 所属执行流；
- 源文件位置；
- 是否存在同名符号。

返回多个同名符号时，必须使用 UID、`--file` 或 `--kind` 消除歧义，不得凭名称随意选择。

## 5. Impact 强制门禁

修改以下对象前必须执行 Impact：

- 函数、方法、类和接口；
- DTO、Controller、Service、Repository 和 Mapper；
- 前端组件、导出函数和共享状态；
- API 合同、数据库映射和核心业务符号。

MCP 调用：

```text
gitnexus_impact({
  target: "[符号名称]",
  file_path: "[文件路径]",
  kind: "[符号类型]",
  direction: "upstream",
  includeTests: true,
  summaryOnly: true,
  repo: "[仓库名称或绝对路径]"
})
```

CLI 降级：

```powershell
npx.cmd gitnexus impact "[符号名称]" --file "[文件路径]" --kind "[符号类型]" --direction upstream --include-tests --summary-only --repo "[仓库名称或绝对路径]"
```

修改前向用户报告：

| 项目 | 分析结果 |
|---|---|
| 目标符号 | |
| 直接调用者 | |
| 间接影响数量 | |
| 受影响执行流 | |
| 受影响模块 | |
| 风险等级 | |
| 主要风险 | |
| 计划回归范围 | |

风险处理：

- `LOW`：按最小边界修改。
- `MEDIUM`：补充直接调用者测试和模块回归。
- `HIGH`：先告警，缩小修改范围，禁止大规模重写。
- `CRITICAL`：停止编码，报告影响并取得方案确认。

不得为了减少测试而降低风险等级。`HIGH` 或 `CRITICAL` 不得静默继续。

## 6. API 合同检查

修改 API、Controller、请求/响应 DTO 或接口返回结构时，优先使用可用的 API 专项工具：

```text
gitnexus_api_impact({
  route: "[API 路径]",
  file: "[Controller 或 handler 文件]",
  repo: "[仓库名称或绝对路径]"
})
```

工具支持时继续使用 `route_map` 查找处理器和消费者，使用 `shape_check` 比对响应字段与消费者访问字段。如果专项工具不可用，必须使用 `query`、`context`、`impact`、源文件和合同测试完成降级调查，并明确记录限制。

必须核对：

- 服务端路径、方法、请求和响应字段；
- 客户端类型定义和实际访问字段；
- 空值、错误响应和错误码；
- 权限、中间件和数据范围；
- 上下游调用者与向后兼容性。

API 测试通过不能替代合同影响检查。

## 7. 重命名规则

禁止通过全局查找替换重命名符号。优先使用 GitNexus Rename 预览：

```text
gitnexus_rename({
  symbol_name: "[旧名称]",
  new_name: "[新名称]",
  file_path: "[文件路径]",
  dry_run: true,
  repo: "[仓库名称或绝对路径]"
})
```

检查预览结果后再决定是否执行，特别复核低置信度的 `text_search` 结果。Rename 工具不可用时，停止自动重命名，先报告限制和替代方案。

## 8. 提交前 Detect Changes

提交前必须在目标工作树根目录执行。MCP 可显式传入工作树；当前 CLI 不提供 `--worktree` 参数，因此必须先切换到目标工作树目录。

### 8.1 当前工作区

```powershell
npx.cmd gitnexus detect-changes --scope all --repo "[仓库名称或绝对路径]"
```

### 8.2 与可信基线比较

```powershell
npx.cmd gitnexus detect-changes --scope compare --base-ref "[主分支或可信提交]" --repo "[仓库名称或绝对路径]"
```

### 8.3 精确暂存后复查

仅在用户明确授权暂存或提交后执行：

```powershell
npx.cmd gitnexus detect-changes --scope staged --repo "[仓库名称或绝对路径]"
git diff --cached --check
git diff --cached --name-status
```

所有代码或文档变更都执行：

```powershell
git diff --check
```

提交前报告：

- 实际修改文件；
- 识别出的修改符号；
- 受影响执行流；
- 风险等级；
- 是否出现计划外文件或模块；
- 是否需要扩大回归范围。

出现计划外模块或执行流时不得直接提交，必须先调查原因。未经授权不得暂存、提交、Push、创建 PR 或部署。

## 9. 索引异常处理

如果 Analyze 异常退出：

1. 同一失败命令不连续执行超过三次。
2. 重新运行 `npx.cmd gitnexus status`。
3. 比较索引时间、索引提交和当前提交。
4. 状态已是 `up-to-date` 时不盲目重建。
5. 上次增量分析未完成时重新执行普通 `analyze`。
6. 普通恢复失败后才考虑 `--force`。
7. 长日志写入 `reports/ai/`，对话只报告摘要和证据路径。

如果存在多个同名仓库，`query/context/impact/detect-changes` 使用绝对路径作为 `--repo` 值；`status` 不支持 `--repo`，必须在目标工作树目录中执行。

## 10. 工具不可用时

如果 GitNexus MCP 工具不可用：

1. 使用仓库内 GitNexus 脚本或 GitNexus CLI。
2. CLI 也不可用时报告工具缺失。
3. 不自动安装全局依赖，除非用户授权。
4. 使用 Git、`rg`、语言服务器和现有测试作为临时降级方案。
5. 明确说明降级调查不能完全替代调用图影响分析。

## 11. 最终报告

涉及代码理解或修改的最终交付必须包含：

1. GitNexus 索引状态；
2. 使用过的 Query；
3. 调查过的关键 Context；
4. 修改前 Impact 风险；
5. 直接调用者；
6. 受影响模块；
7. 受影响执行流；
8. `HIGH/CRITICAL` 风险处理；
9. Detect Changes 结果；
10. 实际变化是否符合预期；
11. 对应回归测试；
12. 未覆盖或无法识别的风险；
13. 回滚方法；
14. 是否执行暂存、提交、Push、PR 或部署。

## 12. 日常简化提示词

完整规范已由 `AGENTS.md` 引用。日常任务可以使用：

```text
请使用 GitNexus-First 流程完成本任务：

1. 执行 git status 和 GitNexus status。
2. 使用 query 查找相关执行流。
3. 使用 context 确认关键符号的上下游关系。
4. 修改任何符号前执行 impact。
5. HIGH/CRITICAL 风险先告警，不直接大范围修改。
6. 先写失败测试，再实施最小修复。
7. 提交前执行必要的 analyze、detect-changes 和 git diff --check。
8. 只处理本任务文件，不处理用户已有修改；未经授权不暂存。
9. 最终报告 GitNexus 影响范围、测试证据、残余风险和回滚方法。

本轮任务：
[填写具体需求]
```
