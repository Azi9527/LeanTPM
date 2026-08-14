# 表头复合筛选输入值不可见

## 需求

修复服务端表格列头复合筛选弹窗：用户输入后文字必须立即可见，同时保留现有应用、重开回显和清除逻辑。

## 验收标准（Given / When / Then）

- Given 打开任一文本列的复合筛选弹窗，When 输入筛选值，Then 输入框立即显示完整文字。
- Given 已输入筛选值，When 点击“应用”，Then 查询条件照常生效。
- Given 已应用筛选值，When 重新打开弹窗，Then 原值正确回显。
- Given 弹窗已有筛选值，When 点击“清除”，Then 条件和输入值同时清空。

## 影响与风险

- 范围：`frontend/src/components/table/SmartElTableColumn.ts` 公共服务端表格筛选控件。
- GitNexus：该 TypeScript 渲染组件未被索引为可定位符号，`context`/`impact` 均返回 UNKNOWN。
- 实际影响面：所有使用公共服务端表格复合筛选的页面；不修改查询参数和后端 API。
- 风险等级：L1。采用局部输入组件承载草稿响应式状态，并做前端合同测试、类型检查和构建验证。
