# M6 移动端与 Android 交付记录

## 1. 交付结论

M6 已完成统一响应式移动端、设备扫码入口、点检/维保现场执行、加密离线草稿和 Capacitor Android 容器。正式 MySQL 已迁移至 V13，隔离库集成测试、前端构建和 Android APK 构建均通过。

当前 V1.0.1 调试 APK 位于本机 `runtime/deliverables/LeanTPM-V1.0.1-debug.apk`。`runtime` 不进入 Git；任意开发机可通过 `scripts/build-android.ps1` 重新生成。

调试 APK 在同一局域网联调时，服务地址填写后端地址，例如
`http://192.168.31.91:18080`；应用会自动补全 `/api/v1`。不能填写
`127.0.0.1`（它在手机上代表手机自身），也不能填写前端开发端口
`15173`。Debug 构建允许局域网 HTTP 与 Capacitor 混合内容，Release
构建仍默认禁止 HTTP，生产环境必须使用 HTTPS。

Android 登录输入使用系统 WebView 的标准 `InputConnection`，不启用
Capacitor 的简化键盘捕获；提交前还会从原生输入框同步账号和密码，
兼容系统自动填充、密码管理器以及部分厂商输入法不触发前端事件的情况。

## 2. 数据库与后端

新增不可变迁移 `V13__mobile_workbench.sql`：

- 参数：草稿保留 7 天、单附件上限 10MB、扫码令牌长度 64；
- 菜单：移动作业、工作台、扫码、任务、消息、设置；
- 角色：超级管理员、设备管理员、点检员、维保员获得对应移动权限。

新增接口：

- `GET /api/v1/mobile/bootstrap`：当前用户任务统计、消息和移动参数；
- `GET /api/v1/mobile/equipment/{token}`：按 64 位安全令牌和数据范围返回设备现场信息及可执行任务。

两项接口均校验 `mobile_enabled`、功能权限和服务端数据范围。扫码内容不携带设备敏感字段。

## 3. 前端与移动能力

移动端底部导航包含工作台、扫码、任务、消息和我的。PC 与移动端复用同一 Vue 3 工程、认证状态和业务接口。

点检与维保现场页支持：

- 任务路由直达与权限校验；
- 本地自动草稿、版本冲突丢弃和过期清理；
- 断网待提交、恢复后沿用稳定 `Idempotency-Key`；
- 原生相机拍照、类型/大小校验和附件上传；
- 大按钮、底部操作区和单手操作布局。

扫码与相机模块按页面懒加载，主包约 1.25MB，避免把扫码 Web 运行时放入首屏。

## 4. Android 安全设计

- Capacitor `8.4.2`，Android compile/target SDK 36，minSdk 26；
- 正式清单禁止明文 HTTP，调试清单仅为本机联调开放明文；
- `allowBackup=false`，避免系统备份带走应用数据；
- 自定义 `SecureVaultPlugin` 使用 Android Keystore AES/GCM；
- WebView 不在 `localStorage` 保存明文访问令牌、刷新令牌或业务草稿；
- 发布签名只从环境变量读取，不提交 keystore 或密码；
- 服务端继续执行认证、功能权限、移动开关和数据范围校验。

发布签名环境变量：

```powershell
$env:LEANTPM_ANDROID_KEYSTORE = 'D:\secure\leantpm-release.jks'
$env:LEANTPM_ANDROID_STORE_PASSWORD = '由密钥管理系统提供'
$env:LEANTPM_ANDROID_KEY_ALIAS = 'leantpm'
$env:LEANTPM_ANDROID_KEY_PASSWORD = '由密钥管理系统提供'
```

## 5. 构建与安装

调试包：

```powershell
.\scripts\build-android.ps1 -Configuration Debug
```

发布包：

```powershell
.\scripts\build-android.ps1 -Configuration Release
```

安装并启动：

```powershell
adb devices -l
adb install -r .\runtime\deliverables\LeanTPM-V1.0.1-debug.apk
adb shell am start -n com.leantpm.mobile/.MainActivity
```

模拟器访问本机后端时，默认服务地址为 `http://10.0.2.2:8080/api/v1`；正式包必须配置 HTTPS 企业服务地址。

## 6. 验证证据

| 项目 | 结果 |
|---|---|
| MySQL 空库 V1～V13 重放 | 通过 |
| MySQL 集成测试 | 19 项，0 失败 |
| 正式库升级 | V12 → V13 |
| 前端类型检查 | 通过 |
| 前端生产构建 | 通过 |
| npm 审计 | 0 个已知漏洞 |
| Android Debug 构建 | `BUILD SUCCESSFUL` |
| APK 大小 | 40,003,199 字节 |
| APK SHA-256 | `6AA63530DBFADE9DD55ED395C3CB100166F150FFF572DD9C0288F5A26FFF6A51` |
| APK 签名 | v2 调试签名校验通过 |
| APK 清单 | `com.leantpm.mobile`，versionCode 2，versionName 1.0.1，minSdk 26，targetSdk 36 |

真机首次安装反馈原生 WebView 白屏后，已修正 `SecureVault` 自定义插件注册顺序：插件在 `BridgeActivity` 创建 WebView 前注册；前端启动异常同时增加可见错误页，避免无信息白屏。修复版重新完成 Web 资源同步、Android Debug 构建、单元测试和 v2 签名校验。

构建机没有已连接 Android 设备，`adb devices -l` 返回空列表，因此没有伪造真机安装结论。接入 API 26+ 真机后，应按上节命令完成安装、登录、扫码、拍照、断网草稿和恢复提交复验。

## 7. 权限清单

- `mobile:access`
- `mobile:workbench:view`
- `mobile:scan`
- `mobile:task:view`
- `mobile:message:view`
- `mobile:profile:view`

业务任务执行仍分别要求点检或维保既有权限。

## 8. 已知风险与下一阶段

- 真机厂商相机权限、后台恢复和 WebView 网络策略需在目标设备上复验；
- 发布包必须使用企业 keystore 和 HTTPS 服务地址；
- M7 执行全量回归、安全检查、部署/升级/回滚说明与发布清单。
