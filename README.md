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
- Redis 7.x（认证会话、令牌撤销、在线用户和登录失败限制必须使用）

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

前端默认地址为 `http://localhost:5173`，后端接口为 `http://localhost:8080/api/v1`，接口文档为 `http://localhost:8080/swagger-ui.html`。

Redis 不可用时，健康检查会显示异常，登录及受保护接口返回 `503 REDIS_UNAVAILABLE`，系统不会回退到不可撤销的无状态令牌。

如果 8080 已被其他程序占用，可同时设置：

```powershell
$env:LEANTPM_SERVER_PORT = '18080'
$env:VITE_BACKEND_URL = 'http://127.0.0.1:18080'
```

首次启动时，只有设置 `LEANTPM_BOOTSTRAP_ADMIN_PASSWORD` 才会初始化 `admin` 管理员；该用户会被标记为首次登录必须修改密码。生产环境不得使用示例密码。

当前本机数据库已初始化 `admin`；临时密码仅在本次本地交付说明中提供，不写入 Git。首次登录后系统会强制修改。

## 设计与交付

- [总体设计](docs/01-总体设计.md)
- [第一阶段交付说明](docs/02-第一阶段交付说明.md)
- [V1 开发计划](docs/03-V1开发计划.md)
- [M0 基础能力交付记录](docs/04-M0基础能力交付记录.md)
