# LeanTPM 精益设备管理系统

LeanTPM 面向制造企业设备管理、点检、维保、状态、OEE 与运行可视化场景。仓库采用前后端分离的单体模块化架构，第一阶段先建设安全、权限、字典、日志、附件与统一工程能力。

## 工程结构

```text
LeanTPM
├─ backend/                 Spring Boot 业务服务
├─ frontend/                Vue 3 管理端与响应式移动端
├─ docs/                    架构、数据与阶段交付文档
└─ scripts/                 本地初始化与验证脚本
```

## 本地环境

- Java 21
- Maven 3.9+
- Node.js 20.19+ 或 22.12+
- MySQL 8.0+
- Redis 7.x（认证会话、令牌撤销、在线用户、登录失败限制、验证码和请求幂等必须使用）

## 快速启动

1. 创建数据库：

   ```sql
   CREATE DATABASE IF NOT EXISTS leantpm
     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
   ```

2. 在 PowerShell 中设置本机环境变量（值按实际环境填写）：

   ```powershell
   $env:LEANTPM_DB_USERNAME = 'root'
   $env:LEANTPM_DB_PASSWORD = '你的数据库密码'
   $env:LEANTPM_JWT_SECRET = '至少32位的随机字符串'
   $env:LEANTPM_BOOTSTRAP_ADMIN_PASSWORD = '首次管理员密码'
   $env:LEANTPM_REDIS_HOST = '127.0.0.1'
   $env:LEANTPM_REDIS_PORT = '6379'
   ```

3. 启动后端：

   ```powershell
   cd backend
   mvn spring-boot:run
   ```

4. 启动前端：

   ```powershell
   cd frontend
   npm install
   npm run dev
   ```

## 测试

后端单元测试：

```powershell
cd backend
mvn test
```

使用独立临时数据库执行 Flyway、递归组织范围和 JSON 变更快照集成测试：

```powershell
.\scripts\run-mysql-integration.ps1 `
  -MySqlUser root `
  -MySqlPassword '你的数据库密码'
```

脚本会创建唯一命名的临时数据库，并在成功或失败后通过 `finally` 自动删除。

完整验证登录验证码默认开关、必填校验、正确登录和一次性消费：

```powershell
.\scripts\verify-captcha-e2e.ps1 `
  -MySqlUser root `
  -MySqlPassword '你的数据库密码'
```

该脚本下载并校验临时 Redis 压缩包，在独立端口和临时数据库运行；结束后自动停止进程并清理数据。

接口错误码、幂等约束和调用示例见 `docs/05-API错误码与幂等说明.md`。

前端默认地址为 `http://localhost:15173`，后端接口为 `http://localhost:18080/api/v1`，接口文档为 `http://localhost:18080/swagger-ui.html`。本地端口固定使用这两个值；上线时可通过 `LEANTPM_SERVER_PORT` 与 `VITE_BACKEND_URL` 覆盖。

Redis 不可用时，健康检查会显示异常，登录及受保护接口返回 `503 REDIS_UNAVAILABLE`，系统不会回退到不可撤销的无状态令牌。

上线部署需要修改后端端口时，可同时设置：

```powershell
$env:LEANTPM_SERVER_PORT = '上线后端端口'
$env:VITE_BACKEND_URL = 'http://127.0.0.1:上线后端端口'
```

首次启动时，只有设置 `LEANTPM_BOOTSTRAP_ADMIN_PASSWORD` 才会初始化 `admin` 管理员；该用户会被标记为首次登录必须修改密码。生产环境不得使用示例密码。

当前本机数据库已初始化 `admin`；临时密码仅在本次本地交付说明中提供，不写入 Git。首次登录后系统会强制修改。

## Android 构建

安装 Java 21、Node.js 和 Android SDK Platform 36 后，可生成调试 APK：

```powershell
.\scripts\build-android.ps1 -Configuration Debug
```

发布构建必须先配置企业 keystore 环境变量，详见 `docs/11-M6-移动端与Android交付记录.md`。构建产物默认写入不受 Git 管理的 `runtime/deliverables`。

## 设计与交付

- [总体设计](docs/01-总体设计.md)
- [第一阶段交付说明](docs/02-第一阶段交付说明.md)
- [V1 开发计划](docs/03-V1开发计划.md)
- [M0 基础能力交付记录](docs/04-M0基础能力交付记录.md)
- [M1 设备基础管理交付记录](docs/06-M1设备基础管理交付记录.md)
- [M2 点检管理交付记录](docs/07-M2点检管理交付记录.md)
- [M3 维保管理交付记录](docs/08-M3维保管理交付记录.md)
- [M4 OEE 管理交付记录](docs/09-M4-OEE管理交付记录.md)
- [M5 可视化中心交付记录](docs/10-M5-可视化中心交付记录.md)
- [M6 移动端与 Android 交付记录](docs/11-M6-移动端与Android交付记录.md)
- [V1 发布与运维手册](docs/12-V1发布与运维手册.md)
- [M7 V1.0.0 发布验收记录](docs/13-M7-V1发布验收记录.md)
