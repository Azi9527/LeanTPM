# 设备台账导入错误明细可见性

## 需求

- 客户上传设备台账后，系统提示“19 个问题，涉及 19 行”，但错误区域只显示空白灰条。
- 每条错误必须明确显示 Excel 行号、字段、原值、错误原因和可执行的修改建议。
- 不改变设备导入校验规则，不写入生产数据，不发布。

## Given / When / Then

- Given：Excel 中存在无效的分类、组织、位置、负责人、日期或格式值。
- When：用户在“设备台账”中选择文件并执行导入预校验。
- Then：整批不写入；页面逐条显示 Excel 行号、错误字段、Excel 原值、后端错误原因和修改建议。
- Then：即使后端意外返回空字段或空错误说明，页面也显示“整行数据 / （空） / 服务端未返回具体错误说明”，不能渲染为空白条。

## 调查证据

- 客户文件共 19 行数据，主负责人均为 `15007512772`；云端截图证明该账号存在且启用，因此排除“负责人不存在”。
- 19 行还共同使用设备分类 `PRODUCTION`、所属组织 `LINE-FX`、物理位置 `FUXUAN-3F`；实际无效项必须由导入返回明细确认。
- 19 个设备编码均已填写且互不重复；如果这些编码已经存在于云端，现有新增导入会对每行分别返回“设备编码已存在”，这也是“19 行各 1 个问题”的候选原因。当前截图隐藏了错误文本，不能据此断定是哪一种候选。
- 当前 Backend 返回 `rowNumber / field / message`，未返回原值；当前 Web 使用嵌套卡片渲染，云端实际表现为空白灰条。

## 影响与风险

- 风险：L2（Backend 响应 DTO + Web 弹窗展示）。
- GitNexus：`validateImportRow` LOW（1 个直接调用方），`importValue` LOW（1 个直接调用方），`groupEquipmentImportErrors` LOW（1 个直接调用方）；`EquipmentDtos` 容器 MEDIUM（8 个直接引用），所以采用向后兼容的可选字段扩展，不修改既有字段含义。
- API：`POST /api/v1/equipment/import` LOW，无已识别外部消费者；Web 是实际消费者。

## 文件所有权

- 实现：`backend/.../EquipmentDtos.java`、`backend/.../EquipmentService.java`、`frontend/src/api/equipment.ts`、`frontend/src/utils/equipment-import-errors.ts`、`frontend/src/views/equipment/ledger/EquipmentLedgerView.vue`。
- 测试：对应 Backend 单元测试与 Frontend 工具/展示合同测试，只读验证实现结果。
- 保护：不触碰已有 APP 首次登录相关未提交变更。

## 测试顺序

1. 先增加 Backend 原值断言和 Web 五列明细合同，确认失败。
2. 最小实现后运行 Backend 定向测试、Frontend 定向测试、类型检查/构建。
3. 执行 `git diff --check`、GitNexus 变更检测并核对工作区边界。

## 实现结果

- Backend `ImportError` 向后兼容地增加 `originalValue`，并在分类、组织、位置、负责人、日期、布尔值、字段长度、重复编码和已存在编码等校验中回传原始输入。
- Web 将容易出现空白的嵌套卡片改为 Element Plus 表格，固定展示：Excel 行号、错误字段、Excel 原值、错误原因、修改建议。
- Web 对后端意外返回的空字段、空原值和空错误说明提供可见兜底，避免再次出现只有灰条、没有文字。

## 验证证据

- 失败测试先行：Frontend 展示合同和行模型测试先失败（缺少 `importErrorRows` 表格与 `buildEquipmentImportErrorRows`）；Backend 新断言先因缺少 `originalValue` 无法编译。
- Backend 定向 `EquipmentServiceTest`：PASS。
- Backend 全量 Maven 测试：225 tests，0 failures，0 errors，66 skipped（需 MySQL 的条件集成测试），PASS。
- Frontend 全量测试：29 tests，0 failures，PASS。
- Frontend `npm run build`：Vue 类型检查和 Vite 生产构建 PASS。

## 2026-08-13 云端只读核对与本地真实导入复现

- 环境边界：云生产 `8.163.66.164:3306/leantpm` 只读；本地 MySQL `127.0.0.1:3306` 仅使用唯一命名隔离库。
- 云端临时账号仅有 `SELECT ON leantpm.*`，限定来源 IP，并通过 TLS 连接；生产 schema 为 V53。
- 云端只读查询确认：
  - 分类 `PRODUCTION` 存在、启用；
  - 组织 `LINE-FX` 存在、启用；
  - 位置 `FUXUAN-3F` 存在、启用，且属于 `LINE-FX`；
  - 负责人账号 `15007512772` 存在、启用；
  - Excel 的 19 个设备编号在生产库中全部已经存在且未删除。
- 本地真实复现：从空隔离库执行全部 53 个 Flyway 迁移，装入最小参考数据及这 19 个既有编号，再由当前 Backend 读取客户原始 Excel 执行 `EquipmentService.importWorkbook`。
- 结果：`importedRows=0`，`errors=19`；Excel 第 2～20 行全部返回字段“设备编码”、原因“设备编码已存在”；测试前后目标设备数均为 19，没有新增写入。
- Maven 结果：1 test，0 failures，0 errors，0 skipped，BUILD SUCCESS。
- 清理：本地隔离数据库和临时测试账号均已删除；云生产业务表未写入。云端临时只读账号需由云服务器本机 root 执行 `DROP USER` 后完成最终清理。

结论：客户文件本身的分类、组织、位置和负责人均正确；本次 19 行失败的唯一业务原因是 19 个设备编号已经全部存在。若客户要更新原设备，应使用编辑/批量更新能力（当前新增导入不覆盖既有设备）；若确实要新增设备，必须在 Excel 中填写新的唯一设备编号。
