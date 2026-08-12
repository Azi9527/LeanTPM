# LeanTPM 1.0.2 生产发布复盘

## 任务与验收

目标：把 2026-08-11 的生产发布最佳实践、失败根因和长期门禁固化到项目文档，避免未来重复踩坑，并禁止未经重复验证的独立运维平台承担生产发布。

```text
Given 后续发布人员只拥有仓库和当次发布产物
When 其准备生产发布
Then 可从长期发布手册获得经过验证的直接发布、备份、PlanOnly、切换、验收和回滚规则
And 未满足重复隔离验证准入条件的 OpsControl/ReleaseAgent 会被明确阻止用于生产发布
```

风险等级：L0。仅修改 Markdown，不修改程序、数据库、生产配置、服务或外部系统。文档变更无法用失败单元测试先行；以链接、关键门禁、Markdown diff 和空白错误检查作为替代验证。

## 生产结果证据

- Release：`1.0.2-20260811.1`。
- Previous：`1.0.1-20260809.1`。
- Release source commit：`22a2496987e093783cbc700fe838fb58ee297566`。
- Plan SHA-256：`b213e6c3414d34240b595b3e711cec92d1346095adaaca0e787b0bdac8782c10`。
- Backend SHA-256：`a748f269bcab625ca6a134bc17ab5efe8738694b98735638de7af3b96a2d3bf3`。
- Database backup SHA-256：`eb3759b9712e68535f32ff18f905bac8fd2d83fc224d93a12bf046d68cd676da`。
- MySQL server UUID：`007df095-92ef-11f1-8f53-00163e059faa`。
- Database schema：V50。
- Backend：Running；readiness `UP`；branding `OK`。
- Caddy：Running；HTTP 200；public branding `OK`。
- Evidence：`D:\LeanTPM\Runtime\logs\direct-deployment-20260811-101213\resume-20260811-102008`。

以上只记录路径和摘要，不复制生产数据库、秘密、Token、DPAPI 文件或日志正文到 Git。

## 决策

1. 独立运维平台仍是未来目标，不是当前生产发布入口。
2. 在相同 Windows WORKGROUP 条件下完成连续 bootstrap、发布、故障回滚和重启恢复演练前，不再在客户等待版本时反复尝试 bootstrap。
3. 当前使用已经通过 1.0.2 实际验证的直接业务发布路径，并继续要求备份、PlanOnly、精确计划确认和失败补偿。
4. 每次发布后必须更新 `docs/release-platform/production-deployment-practices.md` 的版本记录和永久规则。

## 修改范围

- 新增长期手册：`docs/release-platform/production-deployment-practices.md`。
- 更新 Windows runbook 的入口与当前策略提示。
- 新增本复盘记录。

## 未执行动作

- 未提交或推送 Git。
- 未部署、启停服务或修改 Caddy。
- 未连接或修改生产数据库。
- 未发送 PushPlus。
- 未删除任何本地或服务器文件。
