# LeanTPM API 错误码与幂等说明

## 1. 在线接口文档

- Swagger UI：`http://localhost:8080/swagger-ui.html`
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`
- 业务接口前缀：`/api/v1`

受保护接口使用 Bearer JWT。成功和失败响应均采用：

```json
{
  "code": "OK",
  "message": "操作成功",
  "data": {},
  "timestamp": "2026-07-30T12:00:00Z"
}
```

## 2. 写请求幂等

标记为幂等的 `POST`、`PUT`、`PATCH` 和 `DELETE` 接口必须携带：

```http
Idempotency-Key: web-550e8400-e29b-41d4-a716-446655440000
```

约束：

- 键长度为 8～128 位，仅允许字母、数字、冒号、下划线和连字符。
- 第一次请求通过 Redis 原子占位后执行。
- 相同键、相同请求完成后再次提交，返回第一次的完整响应，不再次执行业务写入。
- 相同键、不同请求返回 `409 IDEMPOTENCY_KEY_CONFLICT`。
- 相同请求尚在处理时返回 `409 REQUEST_IN_PROGRESS`。
- 业务执行失败会释放占位，允许修正后重试。
- Redis 不可用时返回 `503 REDIS_UNAVAILABLE`，不绕过幂等保护执行写入。
- 前端自动为写请求生成键；401 刷新令牌后的自动重试会保留原键。

示例：

```bash
curl -X POST http://localhost:8080/api/v1/system/parameters \
  -H "Authorization: Bearer <access-token>" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-parameter-20260730-001" \
  -d '{
    "parameterKey": "demo.api.enabled",
    "parameterName": "API演示开关",
    "parameterValue": "true",
    "valueType": "BOOLEAN",
    "groupCode": "DEMO",
    "enabled": true
  }'
```

## 3. 通用错误码

| HTTP | code | 含义 |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | 请求字段校验失败 |
| 400 | `CAPTCHA_INVALID` | 验证码缺失、错误、过期或已使用 |
| 400 | `IDEMPOTENCY_KEY_REQUIRED` | 幂等写接口未提供键 |
| 400 | `IDEMPOTENCY_KEY_INVALID` | 幂等键格式不正确 |
| 401 | `INVALID_TOKEN` | JWT 无效或类型错误 |
| 401 | `TOKEN_REVOKED` | 会话已退出或被强制下线 |
| 401 | `TOKEN_SESSION_INVALID` | Redis 会话不存在或过期 |
| 403 | `FORBIDDEN` | 缺少功能权限 |
| 403 | `DATA_SCOPE_DENIED` | 目标资源不在数据范围内 |
| 409 | `OPTIMISTIC_LOCK_CONFLICT` | 数据版本已变化 |
| 409 | `IDEMPOTENCY_KEY_CONFLICT` | 同一幂等键用于不同请求 |
| 409 | `REQUEST_IN_PROGRESS` | 相同请求仍在处理中 |
| 429 | `LOGIN_TEMPORARILY_LOCKED` | 登录失败次数过多 |
| 500 | `INTERNAL_ERROR` | 未处理的服务端异常 |
| 503 | `REDIS_UNAVAILABLE` | 必要的 Redis 服务不可用 |

具体业务错误码会在统一响应的 `code` 和 `message` 中返回，调用方不得仅依赖中文消息判断分支。

## 4. M0 新增接口

### 登录验证码

- `GET /api/v1/auth/captcha`：公开接口，返回当前开关状态和可选挑战。
- 开关关闭时返回 `{"enabled": false}`。
- 开关开启时返回 `captchaId`、SVG `imageDataUrl` 和 `expiresAt`。
- `POST /api/v1/auth/login` 在原账号密码外接收可选字段 `captchaId`、`captchaCode`；服务端开关开启时两者必填。
- 挑战有效期为 5 分钟且只能校验一次，前端登录失败后应获取新挑战。

### 数据变更日志

- `GET /api/v1/system/change-logs`
- 查询参数：`resourceType`、`keyword`、`startDate`、`endDate`、`page`、`pageSize`
- 权限：`system:change-log:view`

### 附件关系

- `POST /api/v1/system/attachments/{attachmentId}/relations`
- `DELETE /api/v1/system/attachments/relations/{relationId}`
- 权限：`system:attachment:relation`

关系类型包括 `IMAGE`、`DOCUMENT`、`MODEL` 和 `OTHER`。业务模块绑定附件前仍需先校验对应业务记录的数据范围。
