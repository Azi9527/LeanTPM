# 2026-08-11 客户反馈第三轮修复证据

## 范围与结论

本轮仅处理以下三项，不提交、不推送、不部署、不访问或修改生产数据库与服务：

1. 设备 Excel 导入改为整表预校验；任一行、任一字段存在错误时，整批导入数量为 0，并一次返回全部已识别问题。
2. 生产日期和投产日期支持 Excel 原生日期，以及 `yyyy-MM-dd`、`yyyy/MM/dd`、`yyyy.MM.dd`、`yyyy年MM月dd日` 文本格式。
3. 移动点检扫码采用专用组织范围：直属班组用户提升到班组直属父组织并覆盖其子树；直属工段、车间、工厂或公司用户覆盖本人直属组织及其子树。保留原有已授权的数据范围以兼容既有任务分派和责任人访问，但未修改公共 `DataPermissionService`。

本轮不涉及 API 路径变化、数据库 Schema 变化或 Flyway 迁移。影响后端导入事务、移动设备查询范围和 Web 导入结果展示，风险等级按 L4 治理。

## Given / When / Then

- Given 一个工作簿同时含多行、多字段错误，When 用户发起导入，Then 返回所有可识别的行号、字段和原因，且设备、状态、履历、变更日志和编号序列均不产生部分写入。
- Given 一个工作簿预校验全部通过，When 写入阶段发生数据库异常，Then 异常向事务边界抛出，整批回滚。
- Given日期是支持的文本格式或带日期样式的 Excel 原生日期，When 预校验，Then 解析为同一 `LocalDate`；非法日期或普通数字单元格明确定位到日期字段。
- Given 用户直属组织是班组，When 扫描同一直属父组织下任一设备，Then 可查看；扫描兄弟父组织或其他租户设备时拒绝。
- Given 用户直属组织不是班组，When 扫描本人组织或其任意下级设备，Then 可查看；扫描兄弟组织或其他租户设备时拒绝。

## 最小改动

- `backend/src/main/java/com/leantpm/equipment/EquipmentService.java`
  - 导入改为“读取与全量预校验 -> 错误统一返回 -> 全部通过后事务写入”。
  - 聚合必填、长度、枚举、布尔、引用、权限、日期关系、工作簿内重复编码和库内重复编码错误。
  - 支持多日期格式与 Excel 原生日期，模板填写规范同步说明。
- `frontend/src/views/equipment/ledger/EquipmentLedgerView.vue`
  - 错误场景显示“预校验未通过、整批未导入”，增加错误字段列，不再显示成功提示。
- `backend/src/main/java/com/leantpm/mobile/MobileService.java`
- `backend/src/main/java/com/leantpm/mobile/MobileMapper.java`
- `backend/src/main/resources/mapper/mobile/MobileMapper.xml`
  - 新增移动点检专用组织树查询并合并既有授权范围；公共数据权限解析器保持不变。
- 测试：`EquipmentServiceTest`、`EquipmentMySqlIntegrationTest`、`MobileInspectionScopeTest`、`MobileMySqlIntegrationTest`。

## 测试矩阵与结果

- 正常：单行/多行合法导入；ISO、斜杠、点分隔、中文年月日、Excel 原生日期；班组父组织子树；工段/车间/工厂/公司本组织子树。
- 异常：同行多字段错误、多行错误、合法行后跟非法行、工作簿重复编码、非法引用、非法布尔、非法日期、生产日期晚于投产日期。
- 边界：空日期、Excel 普通数字、重复设备编码两行均报告、兄弟组织拒绝、跨租户拒绝。
- 兼容：旧英文表头与生命周期代码继续接受；既有数据权限授权继续生效；管理员全租户范围不收窄。
- 回归：
  - 目标单元测试：16/16 通过；包含预校验失败零审计写入、库内重复编码和普通数字伪日期边界。
  - 后端全量单元测试：208 项，0 失败，0 错误，64 项环境型测试按条件跳过。
  - 隔离 MySQL 核心路径：`EquipmentMySqlIntegrationTest` 2/2、`MobileMySqlIntegrationTest` 9/9，共 11/11 通过；之后的零审计写入收紧由红转绿单元测试和最终全量测试覆盖。隔离实例已关闭，系统 MySQL80 保持 Running。
  - Web 合同测试：12/12 通过。
  - Web 正式构建：`vue-tsc -b && vite build` 通过。

## 正式本地产物

- Backend JAR：`backend/target-codex-round3-verified-release/leantpm-backend-1.0.2.jar`
- 大小：63,736,241 bytes
- SHA-256：`BC035B6F165FCD9CDC01DA17E0C2A6B1123289E6305065C663A3CAA8475683F2`
- Web：`frontend/dist`，构建完成时间 2026-08-11 16:09:34。

## 门禁与残余风险

- GitNexus `detect-changes --repo LeanTPM --scope compare --base-ref main`：46 文件、78 symbols、0 affected processes、risk low（包含前两轮尚未提交候选）。
- `git diff --check` 无空白错误，仅已有 release JSON 的 CRLF 提示。
- 本次差异秘密扫描通过；暂存区为空；HEAD、main、origin/main 均为 `9caf40194ab0625c78cd095e8671cbb95623bef5`。
- 三份用户发布文档时间戳保持 10:30:07、10:31:30、10:31:36，未被本轮改动。
- 移动扫码范围保留原有个人责任、任务分派或自定义授权，避免本轮导致既有可访问任务失效；若产品后续要求“严格只允许组织树，忽略全部既有授权”，需作为权限收窄变更单独确认并回归。
- 租户条件由 Mapper SQL 合同测试和所有递归连接中的 `tenant_id` 约束验证；本轮隔离 MySQL 用例覆盖父子树与兄弟组织拒绝，未额外构造第二租户的真实扫码负向数据。
- 当前 APP `versionCode` 仍为 101；正式移动升级包发布前仍需分配大于线上最高值的版本号并重新签名构建。本轮没有修改 APP 源码。
