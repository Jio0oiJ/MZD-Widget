# 手环 ↔ 手机 互联协议

经小米 `system.interconnect` / XMS MessageApi 透传，UTF-8 JSON 字符串。
两端包名 `com.example.mzdwidget`、签名指纹相同，否则系统拒绝通信。

## 手环 → 手机（指令）

```json
{ "type": "mzd_cmd", "action": "unlock", "reqId": "m1690000000000_1" }
```

- `type`: 固定 `mzd_cmd`
- `action`: 仅 `unlock` / `lock`。手机端只接受这两个白名单值，其余拒绝（不开放 start/stall/search，防误着车）。
- `reqId`: 手环生成的唯一请求 ID，用于回执配对与去重。

## 手机 → 手环（回执）

```json
{ "type": "mzd_result", "reqId": "m1690000000000_1", "action": "unlock", "ok": true, "msg": "已发送" }
```

- `ok`: 指令是否成功下发（服务端是否接受），非车辆实际执行结果。真实门态以车况快刷为准。
- `msg`: 失败原因（如「未登录」「操作过快」或网络错误摘要）。

## 手机端行为

- 收到 `mzd_cmd` → 校验登录 → reqId 去重 + 3 秒节流 → 工作线程执行 `CarActions.send(action, -1, "手环遥控解锁/上锁")` → 回传 `mzd_result`。
- 指令与桌面按钮共用 `CarActions.send()`，写 CtrlLog、登记提示音期望，不新增绕过路径。
- 未登录直接回失败，不发指令。

## 手环端行为

- 发指令后置 busy，8 秒未收回执则判超时并显示失败。
- 收到 `mzd_result` 匹配 `type` 即更新显示并振动（成功 60ms / 失败 200ms）。
- 连接状态由 `conn.onopen/onclose/onerror` + `conn.diagnosis()` 维护。
