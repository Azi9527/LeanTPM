# 点检方案自动编号碰撞修复

## 需求

点检方案新增页面将“方案编码”留空时，应由系统自动生成可用编号；历史数据已经占用编号规则即将生成的编号时，不应把自动编号冲突误报为用户填写的“点检方案编码已存在”。

## 验收标准

- Given 方案编码留空，且编号规则首次生成的编号已被历史方案占用；When 新增方案；Then 系统继续生成下一个编号，直到取得未占用编号后创建成功。
- Given 用户手工填写了已存在的方案编码；When 新增方案；Then 仍返回 `INSPECTION_SCHEME_CODE_EXISTS`，不自动替换用户填写的编号。
- Given 前端方案编码留空；When 组装新增请求；Then 仍提交 `schemeCode: null`，页面继续明确提示“留空自动编号”。
- 不改变点检方案 API 合同、编号规则格式、方案导入的显式编号语义，不修改数据库结构。

## 影响分析与风险

- GitNexus `createScheme` 上游影响：HIGH，直接调用方包括新增接口、`createAndPublishScheme`、点检导入；涉及 3 条执行流程。
- GitNexus `NumberRuleService.generate` 上游影响：CRITICAL，跨设备、维修、故障等模块。因此不修改共享编号生成器。
- 风险等级：L2。修复限定在 `InspectionCatalogService.createScheme` 的“编码留空”分支；手工编码、DTO、Mapper、数据库结构保持不变。
- 治理分工：本次由同一执行者分阶段完成需求/影响审查、测试先行、最小实现与回归复核；没有提交、推送、部署或外部环境操作。

## 测试顺序

1. 先增加自动编号碰撞测试，确认当前实现以 `INSPECTION_SCHEME_CODE_EXISTS` 失败。
2. 增加手工重复编号保护测试。
3. 最小修改后运行点检目录服务单测、Backend 回归及 Web 测试/构建。

## 实施结果

- Web 现有行为正确：编码留空时提交 `schemeCode: null`，输入框提示“留空自动编号”，无需修改 Web 源码。
- Backend 仅在系统自动生成编号时检查碰撞并继续取下一个可用编号，最多尝试 100 次。
- 手工输入的重复编号仍返回 `INSPECTION_SCHEME_CODE_EXISTS`；若自动序号严重失准并连续冲突 100 次，则返回 `INSPECTION_SCHEME_AUTO_CODE_EXHAUSTED`，提示校准编号规则当前序号。
- 未修改共享 `NumberRuleService`、API、Mapper、数据库结构、APP。

## 验证证据

- 红灯：修复前运行 `skipsExistingGeneratedSchemeCodeAndUsesNextAutomaticNumber`，1 个测试报错，错误为 `点检方案编码已存在`。
- 绿灯：自动编号碰撞与手工重复保护测试共 2 个，2 通过、0 失败。
- Backend 全量单测：215 个，151 通过、64 个依赖 MySQL 环境的集成测试按配置跳过，0 失败。
- Web 测试：21 个全部通过。
- Web `vue-tsc -b && vite build`：成功；只有既有的包体积和第三方 PURE 注释警告。
- GitNexus `detect_changes` 已执行；整个工作区因已有 APP/Backend/Web 客户反馈改动汇总为 CRITICAL。本次新增生产代码仍只触及 `InspectionCatalogService.createScheme`，对应方案新增、创建并发布、导入三条上游流程，复核风险为 HIGH。
- `git diff --check`：通过；仅报告已有生产脚本的 LF/CRLF 提示。

## 发布判断

- 本修复需要另行发布 Backend 才会生效。
- Web 无本次源代码改动，不需要因为此问题单独发布。
- APP 无本次源代码改动，不需要因为此问题重新打包。
- 未执行提交、推送、部署、数据库或服务器操作。
