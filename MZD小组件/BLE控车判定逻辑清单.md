# BLE 控车判定逻辑清单（v1.9.8）

> 供复核。覆盖从「服务启停 → 扫描命中 → 靠近解锁 → 离开上锁 → 一键启动」的全部判定分支，  
> 逐条标注源码位置、判定条件、满足/不满足时动作。所有指令发出统一在 `CarActions.send` 写 log  
> （tag `MZDCtrlLog`），记录：指令 cmd、boot_time、触发原因、carid、时间戳、成功/失败。



---

## 0. 关键常量与状态

| 项                      | 值            | 含义                         | 位置             |
| ---------------------- | ------------ | -------------------------- | -------------- |
| `WATCHDOG_MS`          | 2 秒          | 看门狗循环周期                    | BleScanService |
| `DEDUP_MS`             | 10 秒         | 解锁/上锁共用的最小间隔窗口（仅 BLE 自动指令） | BleScanService |
| `SCREEN_ON_GRACE_MS`   | 10 秒         | 亮屏后重新发现目标的宽限期              | BleScanService |
| `AWAY_SCREEN_OFF_MS`   | 90 秒         | 熄屏时离开判定固定阈值                | BleScanService |
| `btAwaySec`            | 用户设置，默认 30 秒 | 亮屏时离开阈值                    | Prefs          |
| `NO_RESULT_RESTART_MS` | 60 秒         | 零回调自愈重启                    | BleScanService |
| `RESTART_SCAN_MS`      | 5 分钟         | 防扫描降级重建                    | BleScanService |
| `FAIL_RETRY_MS`        | 10 秒         | 扫描失败退避重启                   | BleScanService |
| `bleUnlockLatched`     | 布尔，落盘        | 解锁闩锁：本次靠近已解锁则不再重复解锁        | Prefs          |
| `present`              | 布尔，落盘        | 当前是否在附近                    | BleScanService |

扫描参数：MAC+名称 `ScanFilter` 硬件过滤、`LOW_LATENCY`、`AGGRESSIVE`、`MAX_ADVERTISEMENT`。

---

## 1. 服务启动判定（`onStartCommand`，BleScanService）

| 条件                               | 动作                                  |
| -------------------------------- | ----------------------------------- |
| action = `ACTION_STOP`           | 停扫描、清 `present=false`、移除看门狗、停前台、停服务 |
| `Prefs.btEnabled==false` 或 无蓝牙权限 | log「未开启或缺权限」、停服务                    |
| 蓝牙适配器未开                          | log 提示，`scanNote=蓝牙未开启`（看门狗后续会自动恢复） |
| 正在扫描且 filterKey(MAC|名称) 变了       | `restartScan(true)` 重置在场状态          |
| 其他                               | `startScanInternal(false)` 保留在场状态   |
| 返回                               | `START_STICKY`（被回收后重建并重读开关/权限）      |

进程重建时 `present` 从 prefs 恢复；若为 true 则 `lastSeen=当前时间`，避免误判离开。

---

## 2. 目标设备判定（`isTarget`，BleScanService）

硬件 ScanFilter 之后的软件二次确认：

1. `dev == null` → **否**
2. MAC 匹配（目标 MAC 非空且 `equalsIgnoreCase`）→ **是**
3. 目标名称为空或「未知设备」→ **否**
4. 取广播包内 Local Name（`ScanRecord.getDeviceName()`），为空再取 `dev.getName()`
5. 名称 `equalsIgnoreCase` 目标名 → **是**，否则 **否**

计数：`diagCb` 每次回调 +1；`diagHit` 命中目标 +1。

---

## 3. 靠近 → 解锁判定（`onScanResult`，BleScanService）— v1.8.1 加 RSSI 阈值

```
命中目标 → lastRssi/RSSI更新, hasRssi=true
  └─ 信号达标？rssi != 127 且 rssi >= 阈值(默认-70dBm)
       ├─ 否 → 不刷新 lastSeen，diagWeak++，不动作
       │       （关键：弱信号不算「可见」，否则走远后 lastSeen 被持续刷新，
       │         看门狗永远判不出「离开」，车无法上锁）
       └─ 是 → lastSeen=now
               └─ present == false ?
                    ├─ 否 → 不动作（已在场，闩锁交给 CarActions 判定）
                    └─ 是 → now - lastBleAction >= 10s ?
                                 ├─ 否 → 不动作，保持 present=false，后续回调继续重试
                                 └─ 是 → lastBleAction=now; present=true; 计数+log
                                          → 子线程 CarActions.onBlePresent()
```

回调结果：`ok=false`（真失败）→ `setPresent(false)` 让后续扫描重试；`ok=true`（含「已解锁跳过」）→ 不复位。

**RSSI 阈值细则**：

| 项 | 说明 |
| -- | -- |
| 判定式 | `rssi >= 阈值`，RSSI 为负，越接近 0 越强（-60 比 -80 近） |
| 默认值 | -70 dBm；可调范围 -100（极远）~ -30（极近） |
| 设置位置 | 主界面「解锁信号阈值」，输入即存、实时生效，无需重启服务 |
| 无效值 | `rssi == 127`（Android 无效哨兵）一律判不达标，防走远时误解锁 |
| 对上锁的影响 | 走远→信号跌破阈值→`lastSeen` 停更→静默满 away 秒→判离开上锁；阈值调太近会导致在车旁也解不了锁并提前上锁，调太远会走远仍不锁 |
| 诊断 | 通知栏显示当前 RSSI「达标/未达标」、阈值、弱信号计数；解锁 log 记 `RSSI=值≥阈值` |

---

## 4. 解锁子判定（`CarActions.onBlePresent`）

> **v1.8.3 修正**：判定改为**以实时车况为准**，不再开头无条件信任闩锁，
> 解决「熄火回家闩锁卡死导致再也不自动解锁」。
> **v1.8.4 安全修正**：v1.8.3 漏判行驶态，「车已锁就无条件解锁」会在行驶中
> BLE 瞬时漏扫→重扫到时开门。现把「发动机已启动」提为**最高优先级跳过**。
> **v1.9.1 修正**：车况查询失败不再「按靠近务必开盲开」，改为跳过、不发解锁。
> **v1.9.3 修正**：因失败而跳过时回调 `ok=false`，让服务复位 present——否则 present 卡在 true，
> 设备一直在旁边时看门狗只判离开、不重判靠近，本次跳过后将永不再尝试解锁。

| 顺序 | 条件                                  | 动作                                                     |
| -- | ----------------------------------- | ------------------------------------------------------ |
| 前置 | 未登录                                | 直接返回，不动作                                             |
| 0  | 车况不可用（`s==null` 或 started/unlocked 全缺） | **跳过解锁、不置闩锁，回调 ok=false 复位 present 待重试**（不盲开） |
| 1  | `started==true`（发动机已启动）             | 确定性跳过（行驶/怠速中绝不开门），不置闩锁，回调 ok=true            |
| 2  | `unlocked==true`（车已解锁）               | 置闩锁=true，确定性跳过（防一直靠近重复解锁），回调 ok=true           |
| 3  | `unlocked==false`（车已锁）               | 先清残留闩锁→发 `unlock`→成功后置闩锁=true + 快刷（无视旧闩锁）       |
| 4  | 车况可用但字段组合无法判定                   | 跳过，回调 ok=false 复位 present 待重试                        |

发指令失败不置闩锁，回调 ok=false，后续扫描会重试。

关键：
- **行驶中（started==true）绝不自动解锁**——即使车况显示已锁也不开，这是 v1.8.4 的核心安全护栏。
- **回调 ok 语义**：ok=true = 确定性结果（已解锁/发动机启动/解锁成功），present 保持；ok=false = 失败或车况不可用待重试，BleScanService 复位 present 让下次扫描重试。两者不能混，否则要么 present 卡死不再解锁，要么一直重复解锁。
- 车况查询失败不再盲开（v1.9.1 起），与离开分支一致地保守。

---

## 5. 离开 → 上锁判定（`watchdogLoop`，每 2 秒）

```
前置: present==true && lastSeen>0 && 蓝牙开
  silent = now - lastSeen
  gone =
    亮屏: (now - screenOnAt >= 10s) && silent >= btAwaySec(默认30s)
    熄屏: silent >= 60s（固定，不随设置缩放）
  └─ gone && now - lastBleAction >= 10s ?
       ├─ 否 → 不动作
       └─ 是 → lastBleAction=now; present=false; 计数+log
                → 子线程 CarActions.onBleAbsent()
```

亮屏宽限：亮屏后先给 10 秒重新发现目标，设备其实在旁则 `present` 不翻转，不会误锁。  
熄屏放宽：Doze 压低扫描占空比，90 秒内无回调属正常漏扫，不判离开。  
**与 RSSI 阈值联动（v1.8.1）**：`lastSeen` 只在信号达标时刷新；走远后信号跌破阈值，`silent` 从最后一次达标开始累积，满 away 秒即判离开。阈值设置过近会让静止在车旁也因信号波动被判离开，需按实机调整。

屏幕/蓝牙事件（`screenReceiver` + watchdog）：

- `SCREEN_OFF` → `screenOn=false`；`SCREEN_ON` → `screenOn=true`、`screenOnAt=now`、`lastResultAt=now`（重置自愈计时）
- 蓝牙关→开 → `lastSeen=now`、`screenOnAt=now`（等同重新发现，给宽限）
- 蓝牙关且正在扫描 → 停扫描；蓝牙开且未扫描且过退避期 → 重启扫描
- 扫描中且（运行≥5 分钟 或 零回调≥60 秒）→ 重建扫描

---

## 6. 上锁子判定（`CarActions.onBleAbsent`）

| 顺序 | 条件                            | 动作                                        |
| -- | ----------------------------- | ----------------------------------------- |
| 前置 | 未登录                          | 直接返回                                      |
| 1  | 查询车况**抛异常**                  | 回调失败，**不发锁、闩锁保持**（保守，绝不盲发）           |
| 2  | `s==null` 或 `unlocked != true`（含字段全缺/部分缺失，v1.9.3 收紧） | **跳过上锁**；仅明确 `unlocked==false`（车已锁）时复位闩锁=false，其余闩锁保持 |
| 3  | `unlocked==true` 且 `started==true`（车门未锁但发动机已启动） | 保持现状，闩锁保持（避免行驶中重复解锁）                 |
| 4  | `unlocked==true` 且 `started!=true`（车门未锁、发动机未启动） | 发 `lock`（原因「BLE 离开」）→ 成功闩锁复位=false + 快刷 |

关键：**只有明确 `unlocked==true`（车门确实开着）才允许发上锁**——绝不盲锁。
v1.8.4 补齐"字段全 null"、v1.9.3 进一步收紧为"任何 `unlocked!=true` 一律跳过"，堵住
`started=false, unlocked=null`（字段部分缺失）这种可能误落发锁分支的情况。

---

## 7. 10 秒窗口与闩锁的关系

- **10 秒窗口**（`lastBleAction`，进程内 AtomicLong）：解锁与上锁共用，任意两条 BLE 自动指令至少间隔 10 秒。进程重启归零。
- **闩锁**（`bleUnlockLatched`，落盘）：只在 CarActions 层判定，控制「本次靠近是否重复解锁」。
  - 置 true：靠近成功解锁 / 确认车已解锁
  - 置 false：离开发出锁车命令 / 确认车已锁
- **桌面按钮、一键启动不受 10 秒窗口限制**（显式用户操作）。

---

## 8. 一键启动判定（`CarActions.smartStartAndUnlock` + WidgetActions）— v1.8 状态确认式三步 / v1.9 走全局缓存

**核心变化**：从「发启动→盲等 15 秒→发解锁」改为「每步都查车况确认达成才进下一步」，不再依赖固定延时。v1.9 起所有车况查询统一走全局缓存 `CarStateCache.refreshAndWait()`（触发快刷 + 等待本轮真实结果），不再自己发 `/car/status`。

```
入口：未登录→回调失败；SMART_RUNNING 占用→「流程进行中」，均不动作。
置 SMART_RUNNING=true（不再排快刷 tick；桌面由缓存回调 onGlobalStatus 刷新）
│
├─ 步骤1 预锁车
│    query 车况（走全局缓存，等待本轮结果）
│    ├─ unlocked==false（已锁）→ 直接进步骤2
│    └─ 未锁 / unlocked==null（未知）→ 发 lock
│          poll：每 3 秒查（缓存硬节流），直到 unlocked==false → 进步骤2
│          20 秒未确认 → 重发 lock（最多 2 次）→ 仍未确认 → 终止
│
├─ 步骤2 启动
│    发 start（boot_time=15）
│    poll：每 3 秒查，直到 started==true → 进步骤3
│    ⏱ 30 秒未确认 → 重发 start（最多 3 次）→ 仍未确认 → 终止（绝不解锁）
│
└─ 步骤3 解锁
     发 unlock
     poll：每 3 秒查，直到 unlocked==true → 打 markSmartUnlockDone → 完成
     ⏱ 15 秒未确认 → 重发 unlock（最多 3 次）→ 仍未确认 → 结束

异常 / 任一步失败 → cancelSmartUnlockFallback（清掉旧兜底，绝不误补发解锁）
finally → SMART_RUNNING=false
```

**判定细则**：

| 目标状态 | 命中条件（`Api.CarState`） | 未确认时 |
| ---- | ----------------------- | ------ |
| 已锁（步骤1） | `Boolean.FALSE.equals(unlocked)` | 未锁 / `null` 都发 lock 并轮询 |
| 已启动（步骤2） | `Boolean.TRUE.equals(started)` | `null`（含业务码异常）视为未确认继续查 |
| 已解锁（步骤3） | `Boolean.TRUE.equals(unlocked)` | `null` 视为未确认继续查 |

**关键不变量**：

1. **null 绝不当达成**——车况接口业务失败会返回 `started/unlocked=null`，旧版把它当"未启动"导致误判；新版 null 一律继续轮询，直到拿到明确的 `true/false`。
2. **步骤2 未确认启动绝不解锁**——这是旧版"只启动不解锁"事故的反面保护：宁可不解锁，也不在发动机没起来时开门。
3. **重发有上限**（lock 2 次 / start 3 次 / unlock 3 次）——用户要求"直到拿到状态"，但必须有硬上限防止 goAsync 线程死循环拖垮广播；上限内仍拿不到就记录失败终止。日志 tag `MZDCtrlLog` 会打印每轮车况与每次重发。
4. **旧 18 秒兜底闹钟已停用**——它会在步骤2等待期间误补发解锁，破坏新流程；入口处显式 `cancelSmartUnlockFallback`，失败路径也清理。
5. 每轮 `query` 走全局缓存，车况变化由缓存回调 `onGlobalStatus` 实时反映到组件上。

---

## 9. 全局车况缓存（`CarStateCache`）— v1.9 新增

**目标**：所有 `/car/status` 网络请求收敛到唯一的独立工作线程；其它模块一律只读缓存或请求刷新，杜绝多处各自查网。

```
单线程串行工作，任意两次真实请求间隔 >= 3 秒（MIN_GAP 硬约束）
│
├─ 标准模式：每 10 分钟自动查一次（STANDARD_MS），维持缓存鲜活、最省请求
└─ 快刷模式：被触发后 20 秒窗口内每 3 秒查一次（FAST_MS）
      ├─ 触发后立即查一次（有 pending 且距上次请求 ≥3 秒即发起）
      └─ 窗口内无新触发 → 自动退回标准模式

触发源（都会重置 20 秒快刷窗口）：
  · 一键启动判定 CarActions.query      → refreshAndWait（触发 + 等待本轮结果）
  · BLE 靠近/离开 onBlePresent/Absent   → refreshAndWait（判定用本轮真实结果）
  · 桌面按钮 / 点击状态栏刷新           → trigger（不等待，结果回调自动重绘）
  · 主界面「刷新」                      → refreshAndWait
```

**对外接口**：

| 接口 | 用途 | 是否查网 / 阻塞 |
| ---- | ---- | -------------- |
| `refreshAndWait(c)` | 安全判定：触发快刷并等待本轮结果 | 由工作线程查网；等待本轮，失败/超时返回 null |
| `trigger(c)` | 手动/显示类：触发快刷 | 不等待，结果出来后回调重绘 |
| `snapshot()` | 桌面重绘 / 显示 | 纯读缓存，绝不查网、绝不阻塞 |

**关键不变量**：

1. **判定用本轮真实结果，不用旧缓存**——`refreshAndWait` 仅当本轮请求（`successSeq >= target`）成功才返回车况，失败/超时返回 null，由判定方按「未确认 / 车况未知」处理。杜绝拿 10 分钟前的状态在行驶中开门。
2. **请求频率硬上限**——单工作线程串行 + `MIN_GAP_MS=3000`，任意两次请求间隔必 ≥3 秒，覆盖手动连点、BLE 抖动、一键启动轮询等所有场景。
3. **双模式自动切换**——静默时 10 分钟一查省请求；有操作/状态变化时进 20 秒快刷窗口（3 秒一查，触发即查一次），窗口静默后自动回落。
4. **失败可见**——查询失败写 `widget_status_err`，桌面组件显示失败原因，绝不拿旧值冒充实时状态。
   - **v1.9.3 修漏洞**：失败时内存 `cached` 保留上一次成功值，`refreshAndUpdate()` 原样 `publishStatus` 会清空错误标记、把旧车况当实时显示。现改为 `lastError!=null` 时不再 `publishStatus`、只重绘保留失败提示；直到缓存下一轮成功后由 `onGlobalStatus` 恢复正常显示。
5. **桌面刷新零查网**——`CarWidget.refreshAndUpdate()` 只读 `snapshot()`，真正查网只发生在缓存工作线程。

**查询失败的分类处理（v1.9.1）**——「车况不可用」= 网络失败/超时返回 null，或 HTTP 200 但 started/unlocked 字段全缺（`statusUnavailable()`）：

| 调用类型 | 入口 | 查询失败时的行为 |
| -------- | ---- | ---------------- |
| 手动类 | 桌面按钮、点击刷新 | **忽略失败、照常执行**。按钮走 `trigger`（不等待结果）后独立发指令，不读车况，网络失败不影响控车指令下发 |
| 判定类 | 一键启动 `smartStartAndUnlock` | **失败先重试一次，两轮仍失败才忽略后续执行**。首查失败 → 自动进入第二轮查询，第二轮仍失败 → 终止、不发任何指令；poll 每轮同样是首查失败重试一次，两轮均失败才终止、不重发、不进下一步（两次查询经缓存自动间隔 ≥3 秒，应对单次网络抖动） |
| 判定类 | BLE 靠近 `onBlePresent` | **忽略后续执行**。失败即跳过解锁、不置闩锁，回调 `ok=false` 让服务复位 present 待下次扫描重试（v1.9.3 修复 present 卡死；不再凭「靠近务必开」盲开） |
| 判定类 | BLE 离开 `onBleAbsent` | **忽略后续执行**。失败即跳过上锁（绝不盲锁），保持闩锁，等下次有效车况再判 |

---

## 9. 指令发出日志（v1.7.2 新增，全部指令统一）

所有 `start/unlock/lock/search/stall` 最终都走 `CarActions.send(c, cmd, bootTime, reason)`，每次发出打印：

```
【指令发出】cmd=unlock boot_time=-1 触发=BLE 靠近：扫到目标自动解锁 carid=xxx at=时间戳
【指令成功】cmd=unlock 触发=BLE 靠近：扫到目标自动解锁
【指令失败】cmd=unlock 触发=... 原因=...
```

`reason` 取值一览：

| 触发来源        | reason                |
| ----------- | --------------------- |
| BLE 靠近解锁       | `BLE 靠近：扫到目标自动解锁`         |
| BLE 离开上锁       | `BLE 离开：判定离开自动上锁`         |
| 一键启动-步骤1 预锁车  | `一键启动步骤1：预锁车` / `重发锁车(第N次)` |
| 一键启动-步骤2 启动   | `一键启动步骤2：发送启动` / `重发启动(第N次)` |
| 一键启动-步骤3 解锁   | `一键启动步骤3：发送解锁` / `重发解锁(第N次)` |
| 桌面按钮          | `桌面按钮：解锁/上锁/熄火/寻车/启动`     |

Token 不写日志；logcat tag：`MZDCtrlLog`。

---

## 10. 复核时建议重点确认的不对称设计 / 已知取舍

1. ~~靠近失败仍解锁 vs 离开失败不上锁~~ — **已统一**：v1.9.1 起 `onBlePresent` 车况查询失败也不再盲开，与 `onBleAbsent` 同样保守跳过。
2. ~~车况状态未知时的上锁~~ — **已修**：v1.8.4 拦字段全 null，v1.9.3 收紧为 `unlocked!=true` 一律跳过，不再误落发锁分支。
3. **10 秒窗口进程内不落盘**：进程重启后窗口归零，理论上可能比预期更快地发出下一条自动指令；且用 wall-clock，系统校时回退/前进可能影响判定。属已知取舍，未改（改需持久化+elapsedRealtime，面较大）。**v1.9.4 已修并发竞态**：解锁/上锁两条线程原来 `if(now-last>=10s) last=now` 是 check-then-set 非原子，改 `AtomicLong.compareAndSet` 原子占窗，保证任意两条自动指令间隔必 ≥10 秒。
4. **熄屏 90 秒固定阈值**：不随用户设置的「离开判定时间」变化，用户改小阈值在熄屏时无效。符合预期（熄屏 Doze 下刻意放宽）。
5. **无靠近去抖/双阈值滞回**：单次 RSSI 达标即触发靠近，无「连续 N 次达标」确认，解锁/离开共用同一阈值。RSSI 尖峰可能误触发；属已知取舍，未改。
6. **凭证 apply 落盘 / 诊断 5 秒节流**：token 用 `apply` 异步落盘，进程被立即回收理论上可能读到旧值；BLE 诊断「关键节点立即落盘」被 5 秒节流吞掉，关键时刻计数可能丢。均属低概率取舍，未改。
7. **签名校验只拦 status==401**：其他业务失败码（如 403/500、code/errno）不拦截；`control()` 不解析响应业务码，服务端若用非 2xx 之外的码表示失败会被记为「指令成功」。需结合逆向协议确认失败码全集，未改。
