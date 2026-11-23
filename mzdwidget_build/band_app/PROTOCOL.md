# 手环 ↔ 手机 互联协议

经小米 `system.interconnect` / XMS MessageApi 透传，UTF-8 JSON 字符串。
两端包名 `com.example.mzdwidget`、签名指纹相同，否则系统拒绝通信。

## 手环 → 手机（指令）

### 普通解锁 / 上锁（短按）

```json
{ "type": "mzd_cmd", "action": "unlock", "reqId": "m1690000000000_1" }
```

- `type`: 固定 `mzd_cmd`
- `action`: `unlock` / `lock`
- `reqId`: 手环生成的唯一请求 ID，用于回执配对与去重

### 一键启动（必须长按 3 秒）

```json
{ "type": "mzd_cmd", "action": "start", "reqId": "m1690000000000_2", "holdMs": 3000 }
```

- `holdMs`: **必填且 >= 3000**。手机端只接受由手环本地计时满 3 秒后发出的启动请求；
  短按、伪造或未带该字段的 `start` 一律拒绝，防止误碰着车。
- 手机端收到后复用 `CarActions.smartStartAndUnlock()` 完整三步流程：
  预锁车 → 确认已启动 → 解锁。不开放裸 `start` 指令，不绕过任何安全判定。

## 手机 → 手环（回执）

```json
{ "type": "mzd_result", "reqId": "m1690000000000_1", "action": "unlock", "ok": true, "msg": "已发送" }
```

- `ok`: 指令是否被手机端受理（非车辆实际执行结果）。真实门态以车况快刷为准。
- `msg`: 失败原因（如「未登录」「操作过快」或网络错误摘要），最长 24 字。

### 启动的两段回执（同一 reqId）

1. 流程受理后立即回 `{"ok":true,"msg":"启动流程已开始"}` —— 避免手环 8 秒超时误判；
2. 三步流程结束（成功或失败）后再以同一 `reqId` 回最终结果，例如
   `{"ok":true,"msg":"一键启动完成：已锁→已启动→已解锁"}` 或 `{"ok":false,"msg":"启动后未确认…"}`。

手环端仍以 `reqId` 匹配回执；若期间又发了新指令（reqId 已变），旧流程的最终回执会被忽略，
最终结果以手机端指令日志为准。

## 手机端行为

- 收到 `mzd_cmd` → 校验登录 → reqId 去重 + 3 秒节流 → 工作线程执行：
  - unlock/lock：`CarActions.send(action, -1, "手环遥控解锁/上锁")`
  - start（且 holdMs>=3000）：`CarActions.smartStartAndUnlock(...)`
  - 回传 `mzd_result`。
- 指令与桌面按钮共用同一套 CarActions 入口，写 CtrlLog、登记提示音期望，不新增绕过路径。
- `stall` / `search` 等非白名单 action 一律拒绝。
- 未登录直接回失败，不发指令。

## 手环端行为

- 短按解锁/上锁：置 busy，8 秒未收回执判超时并显示失败。
- 一键启动：手指按下后本地倒计时 3 秒，期间显示「启动中 3s/2s/1s」；提前松开即取消，
  满 3 秒才发送 `action=start, holdMs=3000`。15 秒无回执判超时。
- 收到 `mzd_result` 匹配 `type` 与当前 `reqId` 即更新显示并振动（成功 60ms / 失败 200ms）。
- 连接状态由 `conn.onopen/onclose/onerror` + `conn.diagnosis()` 维护。
- UI 使用固定 class 与固定文本节点，不用动态 class / 条件节点，避免 Vela 重绘文字重影。
