# APP 升级下载与强制门禁修复

## 需求与验收

- 目标：修复“立即升级”和“我的→下载最新版”点击无反应；强制升级未完成时不能继续操作业务功能。
- 非目标：不修改 Backend、Web、数据库或生产服务器。
- 风险：L2，Android 原生下载/安装交互；GitNexus 对 `openUpgradeUrl`、`checkAndroidUpgrade` 和个人页入口的上游影响均为 LOW，无受影响执行流程。

```text
Given APP 收到有效的 APK 相对下载地址
When 用户点击“立即升级”或“下载最新版”
Then APP 显示不可穿透的下载遮罩，下载 APK 后唤起 Android 系统安装程序

Given 当前版本低于强制升级版本
When 下载失败、安装程序未打开或用户返回 APP
Then 显示可理解的失败原因并恢复不可取消的升级门禁，不能继续操作业务页面
```

## 失败测试与最小修复

- 失败测试证明旧实现只有 `plus.runtime.openURL`，没有 APK 下载、安装和失败后持续门禁。
- `utils/version.js` 改为 `uni.downloadFile` 下载并显示进度，完成后调用 `plus.runtime.install`。
- 下载任务使用单例 Promise，避免重复点击造成并发下载。
- 强制升级下载/安装失败后显示错误说明，并立即恢复强制升级弹窗。
- 个人页下载按钮等待下载结果，失败时不再静默。

## 验证结果

- 云端下载接口：HTTP 200，类型 `application/vnd.android.package-archive`，大小 25,973,962 字节。
- APP 项目检查：15 个页面、50 个源文件通过。
- APP 测试：60/60 通过。
- `git diff --check`：通过。
- Git/发布：未提交、未推送、未发布；需要重新用 HBuilderX 打包并上传 APK。
