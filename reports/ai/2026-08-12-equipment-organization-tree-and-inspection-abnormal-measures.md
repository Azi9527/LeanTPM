# 设备组织层级选择与点检异常措施简化

## 需求与边界

1. 设备新增、编辑及调拨时，组织选择按企业/部门/车间/工段/班组的父子层级展示，不再使用容易混淆的平铺列表。
2. 点检异常处理页面移除“处理中/提交验证”的选择及页面验证入口；保存动作只记录处理信息，不推进异常状态。
3. 点检异常处理字段调整为“原因分析、临时措施、恒久对策”。
4. 只处理点检异常，不顺带修改保养异常；不提交、不推送、不部署、不操作服务器或数据库。

## Given / When / Then

- Given 多个组织名称相同但父级不同；When 在设备编辑或调拨中选择组织；Then 下拉按父子层级展示，并保留按名称过滤能力，停用节点不可选择。
- Given 用户打开未关闭的点检异常；When 填写原因分析、临时措施、恒久对策并保存；Then 三项内容被持久化，异常状态不因保存而变为“处理中”或“待验证”。
- Given 点检异常处理页面；When 用户查看操作区和弹窗；Then 不显示“处理中”“提交验证”“通过”“退回”等流程入口。
- Given V52 中已有 `final_result` 历史数据；When 升级 V53；Then 历史内容迁移至恒久对策字段，旧列保留用于回退兼容。

## 影响分析与风险

- `EquipmentLedgerView.activeOrganizations`：LOW，无上游符号。
- `InspectionAbnormalView.openHandle/saveHandle/verify`：LOW，无上游符号。
- `InspectionTaskService.handleAbnormal`：LOW，直接影响 Controller 和 MySQL 集成流程。
- `InspectionMapper.handleAbnormal`：LOW，影响异常保存服务与一条集成流程。
- `InspectionDtos`：LOW；前端 `AbnormalRow`：MEDIUM，被 19 个导入关系引用，需要全量类型检查。
- `exportWorkbook`、`InspectionImageWorkbookWriter.writeAbnormalities`：LOW；需要同步导出字段名称和顺序。
- PUT `/api/v1/inspection/abnormalities/{id}`：LOW，无识别到的外部消费者，涉及 5 条后端执行流。
- 风险等级：L2，涉及 Web、API 合同、持久化和数据库迁移。

## 模块所有权与治理

- Web 实现：`EquipmentLedgerView.vue`、`InspectionAbnormalView.vue`、`inspection.ts`。
- Backend 实现：点检 DTO、服务、Mapper/XML、异常导出。
- Persistence：新增 V53 迁移，只增列并迁移历史数据，不删除旧列。
- Test/Review：先以 Web 合同、DTO 反射、Mapper SQL 和迁移测试制造红灯；实现后执行 Backend/Web 全量回归、构建、GitNexus 复核和 diff 检查。
- 受当前会话不主动委派限制，多角色治理由同一执行者按阶段隔离完成，测试与复核阶段不做额外业务扩展。

## 实施结果

- 设备新增/编辑的“所属组织”和设备调拨的“目标组织”均改为组织树；父子层级保留，停用节点显示但不可选择，支持筛选并默认展开。
- 点检异常处理页面改为“原因分析、临时措施、恒久对策”，详情页和两类点检导出同步调整。
- 页面移除了“处理中/提交验证”选择和“通过/退回”入口；保存异常处理信息不再写 `abnormal_status`、关闭人或关闭时间。
- 旧验证 API 和旧状态数据保留，避免删除历史闭环记录；未关闭的历史 `PENDING_VERIFY` 记录仍可补录三项措施。
- V53 新增 `cause_analysis`、`permanent_countermeasure`；历史 `final_result` 迁移到恒久对策，新保存同时镜像旧列以支持回退，不删除旧列。
- 保养异常未修改，APP 未修改。

## 测试证据

- 红灯：Web 新合同 3/3 失败；Backend DTO/Mapper/V53 合同失败，确认旧界面、旧状态推进和缺失迁移均被测试捕获。
- 绿灯专项：Web 3/3；Backend DTO、Mapper、迁移合同 6/6。
- Web 全量测试：24/24；`vue-tsc -b && vite build` 成功，仅既有第三方 PURE 注释和大分块警告。
- Backend 全量：218 个测试，0 失败；64 个依赖 `LEANTPM_TEST_DB_URL` 的 MySQL 集成测试按配置跳过。
- APP 对应验证：项目检查通过（15 页面、48 源文件），52/52 测试通过。
- MySQL 实库迁移未执行：当前无隔离测试数据库环境变量，且本次未获授权操作任何数据库；已通过 V53 静态迁移合同并更新 MySQL 集成测试到版本 53。
- GitNexus `detect_changes` 已执行；整个未提交工作区因包含此前 APP/Backend/Web 改动汇总为 CRITICAL，本次符号复核保持 LOW/MEDIUM 范围。
- `git diff --check`：通过；仅提示已有生产脚本的 LF/CRLF 转换风险，本次未修改这些脚本。

## 发布判断

- APP：本次无需重新打包。
- Web：需要另行发布，组织树和异常新表单才会出现。
- Backend：需要与 Web 在同一维护窗口另行发布，并先由 Backend 启动迁移到 V53；随后发布 Web。
- 未执行提交、推送、部署、数据库或服务器操作。
