# APP 设备状态专用接口错误提示修正

## 需求

APP 其他功能可以正常访问时，设备状态页不能把专用接口失败笼统提示为“企业服务暂时不可用”，应明确区分 Backend 未发布接口、权限不足和接口内部异常。

## Given / When / Then

1. Given `/mobile/equipment-status` 返回 404/405，When 打开设备状态页，Then 提示当前 Backend 尚未发布设备状态接口，需要发布匹配版本。
2. Given 接口返回 403，When 打开设备状态页，Then 提示账号缺少移动端设备扫码查看权限。
3. Given 接口返回 5xx、`SERVICE_UNAVAILABLE` 或 `NETWORK_ERROR`，When 其他 APP 功能仍正常，Then 明确说明手机网络和登录通常正常，应检查设备状态专用接口、反向代理和 Backend 日志，并显示 HTTP 状态或错误码。
4. Given 其他业务错误，When 加载失败，Then 保留服务端返回的具体错误信息。

## 影响与风险

- APP 设备状态页 `load`：GitNexus 2 个直接上游，LOW。
- APP `mobileApi.equipmentStatus`：GitNexus 上游影响 0，LOW。
- 本轮不修改 Backend、API 路由、权限模型或数据库。
- 风险等级：L1（APP 局部错误提示）。
- 仅修改本地工作区并执行本地测试；不提交、不推送、不部署，不操作内网或云服务器、数据库。

## 测试顺序

- 先增加错误分类专项测试并确认失败。
- 最小实现后运行专项测试和 APP 全量验证。
- 最后执行 GitNexus `detect-changes`、影响复核和 `git diff --check`。

## 调查结论

- 设备状态页使用新增的 `GET /api/v1/mobile/equipment-status`，当前云端 `Backend 1.0.4-20260812.1` 未包含本地新增接口；旧功能正常只能证明手机网络、登录和旧接口正常。
- 异常处置登记使用新增的 `PUT /api/v1/inspection/abnormalities/{id}/measures`。该接口除需发布匹配 Backend 外，还依赖 V53 新增的原因分析、临时措施和恒久对策字段；云端仍为 V52 时无法保存。
- 本轮仅优化设备状态页的错误分类。异常处置页已有专项错误分类，客户截图中的新提示已正确把问题指向 Backend 版本、反向代理及日志，不再误判为整机断网。

## 测试证据

- 红灯：新增专项测试后首次执行失败，原因是 `utils/equipment-status.js` 尚不存在。
- 绿灯：`node --test tests/customer-feedback.test.js`，7/7 通过。
- APP 全量：`npm.cmd run verify`，项目检查通过，57/57 测试通过。
- GitNexus `detect-changes`：工作区既有 56 个变更文件、107 个符号，整体风险 LOW、未识别受影响执行流；本轮文件位于预期的 APP 设备状态范围。
- GitNexus 增量重建因本地索引 `file_fts` 不一致失败；未清理或重建用户索引。修改前对设备状态页 `load` 的影响分析结果为 LOW（2 个直接上游）。
- `git diff --check`：通过；仅出现工作区既有部署脚本的 LF/CRLF 警告。

## 发布判断

- 仅修复设备状态专用错误提示，需要重新打包 APP 才能看到新文案。
- 要让设备状态和异常处置真正可用，需要另行授权发布匹配的 Backend；异常处置还必须让 Flyway 从 V52 升到 V53。
- 这两个 APP 接口问题本身不要求发布 Web。若要同时交付当天其他 PC 页面修改，应另行安排 Web 发布。
