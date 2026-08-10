# 所在主机资源监控与图形化证据

## 结果

- `ops-control-plane` 默认只读采集其 Java 进程所在主机；本机预览显示本机，部署到阿里云 Windows 后显示该云服务器。
- 采集 CPU、系统内存、监控磁盘、JVM 堆、可用内存/磁盘、逻辑处理器、主机名、操作系统和运行时长。
- CPU、系统内存、磁盘和 JVM 使用率使用带精确文本与 ARIA 属性的进度条展示；未知值不伪装为 `0`。
- `monitoring.enabled=false` 时不会查询 SCM、MySQL、Backend readiness 或日志；`host-resources-enabled=true` 仍提供主机资源。
- 回滚开关：`leantpm.ops.monitoring.host-resources-enabled=false`。

## 验证

- 测试先失败：主机指标键缺失、默认快照未启用、静态页面没有图形合同，共 3 项失败。
- 定向回归：4/4 PASS。
- Feature flag OFF：1/1 PASS，且未调用 Windows Service 操作器。
- 模块全量回归：70/70 PASS，0 failure/error/skip。
- `node --check ops-control-plane/src/main/resources/static/app.js`：PASS。
- 本机预览：`127.0.0.1:18090`，PID `61672`，Actuator `UP`。
- 真实采样：主机 `DESKTOP-NTRMANG`、Windows 11；CPU、系统内存、磁盘、JVM 指标均由受保护 operations API 返回。
- PushPlus 和自动修复保持关闭；未连接 MySQL、未操作 SCM、未触碰阿里云。

## 浏览器验收边界

本机浏览器控制运行时因工具资产目录缺失而不可用。UI 以接口真实采样、静态 DOM/ARIA 合同测试、JavaScript 语法检查和 70 项模块回归验收。页面脚本已增加 `20260810-host-metrics` 缓存版本；点击身份按钮会立即显示验证状态、优先获取系统运行状态，辅助栏目失败不再阻断主机图表。
