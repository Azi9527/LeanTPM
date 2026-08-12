# LeanTPM 1.0.4 Backend/Web 云端热修发布任务

## 基本信息

- 任务类型：修复、构建、生产发布
- 工作目录：`D:\codex\LeanTPM`
- 可可信基线：`3e65060119610e5723d1bfdd7458dabf3a99b236` 加当前已验证的客户修复工作区
- 风险等级：L4（生产服务切换）
- 范围：Backend、Web、版本合同、专用 V52→V52 应用直发脚本
- 非目标：APP/APK 打包、数据库迁移、V50 恢复、OpsControl、ReleaseAgent

## 真实问题与要求

生产当前为 `1.0.3-20260811.1 / V52`。上一版发布脚本只替换了 JAR 路径，没有同步替换并验证启动脚本中的 `LEANTPM_RELEASE_VERSION` 与 `LEANTPM_DATABASE_SCHEMA_VERSION`，导致新 JAR 以旧版本/Schema 合同启动并被 readiness 拒绝。

本次必须创建独立的 `1.0.4-20260812.1 / V52` 应用直发流程：仅 Backend + Web，不运行 Flyway、不写数据库；失败仅恢复 1.0.3 Backend/Web，数据库保持 V52。

## Given / When / Then

```text
Given 生产精确运行 1.0.3-20260811.1，数据库 UUID 匹配且 Flyway 成功版本精确为 52
When 操作员执行 PlanOnly
Then 不修改文件、服务或数据库，并输出绑定包、当前服务、启动脚本、Caddy、V52 状态的 Plan SHA256

Given 启动脚本唯一包含 1.0.3、Schema 52 和 1.0.3 JAR 路径
When 生成 1.0.4 候选启动脚本
Then 三项分别唯一验证并生成 1.0.4、Schema 52 和 1.0.4 JAR 路径
And 缺失、重复或旧值漂移均在停服前失败

Given 精确 Plan SHA256 已确认且备份完成
When 执行应用直发
Then Backend readiness=UP、info.version=1.0.4、info.schema=52、branding=OK
And Web/Caddy 指向 1.0.4 且本机公网入口 HTTP 200

Given Backend 或 Caddy 切换失败
When 执行器进入恢复路径
Then 恢复已验证的 1.0.3 starter/Caddy 并启动旧应用
And 数据库保持 V52，不运行 V50 数据恢复
```

## 影响与角色

- 主代理：版本合同、构建、执行器、打包、验证；允许修改本任务文件。
- 独立测试/审查：`/root/test_review`；只读，不修改文件。
- GitNexus：索引已重建；PowerShell 发布执行器未进入符号调用图，影响为 UNKNOWN，采用 L4 fail-closed 流程。
- 回滚：应用级回切 1.0.3 starter/Caddy；数据库不变。

## 测试矩阵

| 场景 | 预期 |
|---|---|
| 正确 starter 三字段 | 唯一替换为 1.0.4 / 52 / 新 JAR |
| 版本、Schema、JAR 缺失或重复 | 停服前拒绝 |
| PlanOnly | 零 mutation |
| Plan SHA 不匹配 | 零 mutation |
| Backend 启动或 readiness 失败 | 回切 1.0.3，V52 不变 |
| Caddy 校验或启动失败 | 回切 1.0.3，V52 不变 |
| 成功路径 | Backend/Web 1.0.4，V52，APP 不包含 |

## 授权

- 用户已明确授权更新云服务器 Backend/Web；不包含 APP。
- 用户已授权创建本地 1.0.4 候选提交；未授权推送。
- 不允许更改或暂存三份用户发布文档。
