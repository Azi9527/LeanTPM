# 点检异常处置保存后状态修复

## 需求与验收

- Given 点检异常状态为 `OPEN`，When PC 或 APP 保存原因分析、临时措施、恒久对策，Then 异常状态更新为 `PROCESSING`，界面显示“已处理”。
- Given 已处理异常需要补充内容，When 再次保存处置登记，Then 允许修改并继续显示“已处理”。
- 不恢复“提交验证”交互，不新增数据库字段，不修改历史数据。

## 风险与影响

- 风险：L2，涉及点检异常 API、持久化和 PC/APP 显示。
- GitNexus impact：`InspectionMapper.handleAbnormal`、`recordAbnormalMeasures`、PC `saveHandle`、APP `saveHandling` 均为 LOW。
- 变更范围：点检异常 Mapper、PC 点检异常页、APP 点检异常页及对应合同测试。

## 实现

- PC 保存时显式提交 `targetStatus: PROCESSING`。
- Backend 对 PC 旧/新请求均在未进入验证状态时写入 `PROCESSING`；APP 三措施接口保存后同样写入 `PROCESSING`。
- PC/APP 将 `PROCESSING` 的业务文案统一显示为“已处理”。

## 验证

- Backend：221 项，156 通过、65 项 MySQL 环境测试跳过、0 失败。
- Web：25/25 测试通过，生产构建通过。
- APP：项目检查通过，57/57 测试通过；HBuilder APP 编译在工具链目录摘要阶段超过 120 秒，未生成新的编译产物，需打包前重新执行。
- `git diff --check`：通过。

## 发布要求

- PC 当前问题至少需要重新发布 Web。
- APP 与服务端兜底行为一致需要另行发布 Backend，并重新打包 APP。
- 数据库仍为 V53，无需迁移或执行 SQL。
