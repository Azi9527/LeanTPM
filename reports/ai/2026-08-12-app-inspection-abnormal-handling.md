# APP 点检异常处理登记

## 需求

- APP 点检异常详情必须能查看已登记的原因分析、临时措施、恒久对策。
- 具备 `inspection:abnormal:handle` 权限且异常未关闭时，可以在 APP 登记或修改上述三项内容。
- 保存仅记录处理措施，不推进“处理中/待验证/关闭”等异常状态，也不清空现有责任人、完成期限或设备状态联动信息。

## Given / When / Then

1. Given 用户具备 `inspection:abnormal:view` 权限，When 打开任一点检异常，Then 详情显示三项处理登记；未填写时明确显示“尚未登记”。
2. Given 用户具备 `inspection:abnormal:handle` 权限且异常状态不是 `CLOSED`，When 点击“登记处理”或“修改登记”，Then 可以填写并保存原因分析、临时措施、恒久对策。
3. Given 用户没有处理权限或异常已关闭，When 查看异常详情，Then 只能查看登记信息，不能看到可保存的处理表单。
4. Given 保存成功，When APP 刷新异常列表，Then 显示服务端最新处理内容和版本号，异常状态保持不变。
5. Given 后端检测到版本冲突或权限不足，When 保存失败，Then APP 保留当前输入并显示服务端错误，不伪造保存成功。

## 影响分析

- APP `inspectionApi`：增加 `PUT /inspection/abnormalities/{id}/measures` 调用；GitNexus 上游影响 0，LOW。
- APP 点检异常页 `open`：打开详情时初始化查看状态；GitNexus 上游影响 0，LOW。
- Backend `recordAbnormalMeasures` 服务：2 个直接上游（Controller 与 MySQL 集成测试），LOW。
- Mapper `recordAbnormalMeasures`：1 个直接上游、3 个累计上游，LOW。
- API 路由 `PUT /api/v1/inspection/abnormalities/{id}/measures`：独立新增、0 个图谱执行流，LOW。
- 复用当前工作区已实现的三字段后端合同和 V53 迁移；不再新增表结构，不修改 Web 页面。

## 风险与治理

- 风险等级：L2（APP 交互、新增 Backend API 合同及持久化更新语句）。
- 需求/实现/测试责任：当前 Codex 主任务；修改范围限定为 APP 异常页/点检 API、Backend 点检异常 measures-only 入口与专用测试。
- 审查责任：实现完成后按只读方式检查权限边界、乐观锁版本、旧字段保留、异常状态不变和工作区差异。
- 环境边界：只修改本地工作区并执行本地测试；不启动或操作内网/云服务，不修改任何数据库，不提交、不推送、不部署。

## 测试策略

- 先增加 APP 合同测试并确认失败。
- 最小实现后运行 APP 专用测试、APP 全量测试和项目检查。
- 复跑后端异常三字段合同/服务测试，确认 APP 使用的响应与保存合同仍成立。
- 最后运行 GitNexus `detect-changes`、影响复核及 `git diff --check`。

## 实施结果

- APP 异常详情新增“异常处理登记”，始终可查看原因分析、临时措施、恒久对策；空值显示“尚未登记”。
- 仅具备 `inspection:abnormal:handle` 权限且异常未关闭时显示“登记处理/修改登记”；保存中禁止关闭详情或重复提交。
- APP 使用服务端返回的 `version` 做乐观锁；失败时保留输入并显示服务端错误，成功后刷新列表和详情。
- Backend 新增 measures-only 保存入口，只更新三项登记内容、兼容回退的 `final_result` 镜像、更新人和版本号；不更新责任人、完成期限、设备联动字段或异常状态。
- 新入口继续使用 `inspection:abnormal:handle`、数据权限范围、幂等键和未关闭状态保护，并记录 `RECORD_MEASURES` 变更日志。

## 测试证据

- 红灯：APP 专用测试 2/2 失败；Backend 专用合同测试因缺少 `RecordAbnormalMeasuresRequest` 编译失败。
- 绿灯专项：APP 2/2；Backend `InspectionAbnormalMeasuresContractTest` 2/2。
- APP 全量：项目检查通过（15 页面、48 源文件），54/54 测试通过。
- Backend 全量：219 个测试，0 失败、0 错误；64 个依赖 `LEANTPM_TEST_DB_URL` 的 MySQL 集成测试按当前环境跳过。构建使用独立目录 `target-codex-app-abnormal`，未干扰正在使用的默认 `target`。
- Web 回归：24/24 测试通过，`vue-tsc -b && vite build` 成功；仅既有第三方 PURE 注释和大分块警告。
- APP HBuilderX `build:app` 在 180 秒后超时且产物目录未更新，因此不计为通过；需在用户手工打包环节由 HBuilderX 再完成原生编译/云打包验证。
- GitNexus 强制重建成功：8,463 节点、21,225 关系、300 流；新增服务、Mapper 和 API 最终影响均为 LOW。
- GitNexus `detect-changes` 已执行；整个累积未提交工作区仍因多轮客户修复汇总为 CRITICAL，本次新增链路本身保持 LOW。
- `git diff --check` 通过；只有既有生产脚本的 LF/CRLF 转换提示，本次未修改这些脚本。

## 打包与发布判断

- 当前 APP 源码版本为 `1.0.4 (103)`，默认正式服务地址为 `http://8.163.66.164/api/v1`。
- 若 103 从未发给用户，可继续用 103 打包；若已分发过 103，必须把 `versionCode` 提高到 104 或更高，建议同时调整 `versionName`，否则 Android 无法识别为新版本。
- 打包前必须先发布 Backend 并由 Flyway 将生产库从 V52 升到 V53；否则 APP 看不到三项字段，也无法调用新登记接口。
- 本次功能本身不需要新增 Web 发布；但当前工作区还包含上一项“组织树与 Web 异常三字段表单”的未发布改动，如按本批客户修复整体交付，则 Web 仍需另行发布。
- 未执行提交、推送、部署、数据库或服务器操作。

## 客户复测补充：入口位置与误报网络错误

### 反馈与验收

- Given 用户查看异常详情且有处置权限，When 浏览完处理信息和附件，Then 在详情最底部显示醒目的整宽绿色“登记异常处置/修改异常处置”主按钮，不再把小按钮放在栏目标题右侧。
- Given 保存接口返回 404/405，When APP 保存，Then 明确提示“Backend 尚未发布对应接口”，并说明需要发布 Backend、升级 V53。
- Given 保存请求出现 `NETWORK_ERROR` 或 5xx，When 异常清单仍可读取，Then 提示优先检查 Backend 版本或反向代理，而不是笼统判定手机断网；同时说明当前输入仍保留在页面。
- Given 权限不足或版本冲突，When 保存失败，Then 分别提示联系管理员授权或刷新后重试。

### 影响与边界

- `saveHandling`：GitNexus 1 个直接上游（当前页面），LOW。
- `PUT /api/v1/inspection/abnormalities/{id}/measures`：LOW；本轮不改路由、Backend 或数据库。
- 风险等级：L1，局部 APP 布局和错误提示，不改变 API 合同。
- 根因结论：当前 APP 已调用新 measures 接口，而云端仍为 Backend/Web `1.0.4-20260812.1`、数据库 V52；查询走旧 GET 接口所以正常，保存新接口尚不存在。真正恢复保存必须另行发布 Backend 并迁移 V53，不能通过 APP 伪装为已保存或降级调用会改变流程状态的旧接口。

### 补充测试证据

- 红灯：新增入口位置与错误分类测试后，因旧按钮仍在标题旁且错误分类模块尚不存在，2 项失败。
- 绿灯专项：`abnormal-handling.test.js` 4/4 通过。
- APP 全量：项目检查通过（15 个页面、49 个源文件），56/56 测试通过。
- GitNexus 增量索引完成（8,463 节点、21,236 关系、300 条执行流）；`abnormalHandlingErrorMessage` 最终影响为 LOW，仅当前保存入口和专项测试两个直接上游。
- 已执行 `detect-changes --compare main`；累计未提交工作区因包含多轮 Backend/Web/APP 客户修复仍判定为 CRITICAL，本轮新增的 APP 布局与错误提示链路为 LOW。
- 最终 `git diff --check` 通过；仅显示既有生产脚本的 LF/CRLF 转换警告。
