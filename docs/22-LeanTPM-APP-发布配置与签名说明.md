# LeanTPM-APP 发布配置与签名说明

## 1. 当前发布基线

| 项目 | 配置 | 说明 |
|---|---|---|
| 应用名称 | LeanTPM-APP | HBuilderX 经典 uni-app、Vue 3 |
| Android 包名 | `com.leantpm.mobile` | 与旧 Capacitor APK 相同，保留覆盖升级能力 |
| 版本名称 | `1.0.0` | 正式发布前按交付版本调整 |
| 版本代码 | `100` | 已高于旧 APK 的 `2`，后续只允许递增 |
| 最低 Android | API 29 / Android 10 | 覆盖当前主流工业安卓终端 |
| 目标 Android | API 36 / Android 16 | 使用当前 HBuilderX 支持的目标版本 |
| CPU | `arm64-v8a` | 面向当前主流 64 位手机和平板 |
| 微信小程序 | 已可编译，AppID 待客户提供 | 生产必须配置合法 HTTPS 域名 |

## 2. 权限最小化

Android 仅声明：

- `INTERNET`：访问企业服务端；
- `ACCESS_NETWORK_STATE`：识别在线/离线状态；
- `CAMERA`：设备二维码扫描和现场拍照；
- 相机与自动对焦 feature 均为非强制硬件。

明确不申请定位、通讯录、短信、电话、账号、外部存储读写等权限。照片仅保存到应用沙箱；现场水印使用设备台账中的安装位置文字，不使用 GPS。

## 3. 签名要求

1. 新 uni-app 必须继续使用旧 APK 的正式签名，否则 Android 无法覆盖安装；没有旧签名时只能卸载重装，会丢失应用沙箱内未同步草稿。
2. 签名文件、库密码、别名和私钥密码不得提交 Git，也不得写入 `manifest.json`。
3. 在 HBuilderX“发行 → 原生 App-云打包 → 使用自有证书”中选择客户保管的 JKS/KS 文件。
4. 同时启用 V2、V3 签名；V1 仅为兼容旧设备时保留。
5. 正式包生成后执行 `apksigner verify --verbose --print-certs <apk>`，把证书 SHA-256 与旧正式 APK 对比。
6. 若客户尚无正式签名，创建后至少双人离线备份；密码存入企业密码库，禁止通过聊天或代码仓库传递。

## 4. 网络与服务器地址

- 开发局域网可使用 `http://192.168.31.91:18080`，APP 会规范化为 `/api/v1`；手机与电脑必须处于同一局域网，Windows 防火墙需放行后端端口。
- `127.0.0.1` 在手机中代表手机自身，禁止作为企业服务地址。
- 生产环境强制使用受信任证书的 HTTPS 域名，例如 `https://tpm.example.com`。证书链必须完整，禁止跳过 TLS 校验。
- 微信小程序只能访问已在微信公众平台配置的 HTTPS request/uploadFile/downloadFile 合法域名；当前 `urlCheck=false` 仅用于本地开发。

## 5. 品牌与图标

- 默认主题：宝山翠绿 `#1c7d50`、宝山墨灰 `#3e3a39`、宝山朱红 `#c4000a`。
- APP 启动后读取 `/public/branding`，系统名、简称、Logo 与三种主题色均可由 PC 系统品牌设置调整。
- 客户横版 Logo 位于 `LeanTPM-APP/static/branding/baoshan-mining-logo.png`。
- Android 图标位于 `LeanTPM-APP/static/icons/`，采用客户圆形矿山标识的安全区方形版本；图标不含小尺寸不可辨识的横版文字。

## 6. 构建命令

```powershell
cd D:\codex\LeanTPM\LeanTPM-APP
npm.cmd run verify
npm.cmd run build:h5
npm.cmd run build:weixin
npm.cmd run build:app
```

默认编译器目录为 `D:\tools\HBuilderX.5.15\HBuilderX`。安装位置变化时设置 `LEANTPM_HBUILDERX_ROOT`。输出位于 `LeanTPM-APP/unpackage/dist/build/<platform>`。

## 7. 发布门禁

- 三端编译和自动测试全部通过；
- 使用旧正式签名，包名与证书指纹均一致；
- 版本代码大于线上版本；
- Android 10、12、14、16 至少各一台或等价云真机完成登录、扫码、拍照、离线恢复测试；
- 生产 HTTPS、附件上传/下载、强制升级下载地址通过；
- 断网待提交任务全部同步完成后方可卸载旧 APP；
- 客户微信 AppID、合法域名未提供前，只能交付 Android，不宣称微信生产发布完成。

## 8. 安全存储

Android 通过系统 `AndroidKeyStore` 的 AES/GCM 密钥加密访问令牌、刷新令牌、用户资料、点检草稿和照片队列；密钥不可导出。H5 与微信小程序不具备 Android Keystore，使用各自应用沙箱存储，退出登录或切换企业服务时会清除业务缓存。
