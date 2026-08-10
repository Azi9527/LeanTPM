# Feature flag 登记表

本文件只登记经过评审的 Feature flag，不引入任何实现。若仓库没有通用 Feature flag 机制，新增开关前必须把机制设计、存储位置、权限、审计、默认值、OFF/ON 验证和回滚纳入任务范围。

| Flag | 状态 | 默认值 | 保留原因 | 退休条件 | 建议时间 | 回滚方式 |
|---|---|---|---|---|---|---|
| `ops-auto-remediation` | 长期保留 | OFF | 固定 Windows 服务自动启动具有生产副作用，必须能独立关闭 | 建立经签名 Agent 修复协议并完成隔离 Windows 故障演练后重新评审 | 2026-Q4 | 设置 `leantpm.ops.remediation.enabled=false` 并重启控制面；只读监控继续工作 |
| `ops-pushplus-notifications` | 长期保留 | OFF | 外部消息涉及令牌、额度和接收范围，必须由生产配置显式启用 | 若迁移到统一企业告警平台并完成双通道切换后删除 | 2026-Q4 | 设置 `leantpm.ops.notifications.pushplus.enabled=false` 并重启控制面；监控和审计不受影响 |
| `ops-host-resource-monitoring` | 长期保留 | ON | 只读展示运行控制面的主机资源；需能在受限环境快速恢复纯占位状态 | 基础主机可观测能力长期保留 | 长期 | 设置 `leantpm.ops.monitoring.host-resources-enabled=false` 并重启控制面；完整监控和发布功能不受影响 |

登记规则：

- L3/L4 新行为优先具备可快速关闭的路径。
- 每个 Flag 必须同时验证 OFF 和 ON，不允许只测试默认分支。
- 临时验证 Flag 在验证完成后删除。
- 长期保留必须指定所有者、退休条件、计划时间和明确回滚步骤。
- Flag 关闭后仍需保证数据可读、合同兼容且不会产生半完成状态。
- 删除 Flag 时同时删除死代码、无效配置和对应测试，但必须保留必要的迁移兼容。
