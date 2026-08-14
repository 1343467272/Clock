# Breeno 助手集成（ColorOS 15）

本应用（`com.best.deskclock`）通过一个 LSPosed 模块（见 [BreenoProxy](https://github.com/BreenoProxy)）
接收 ColorOS 15 小布助手的闹钟/定时器指令，让语音设置直接落在本应用内。

## 新增组件

| 组件 | 作用 |
|---|---|
| `com.best.deskclock.breeno.BreenoProxyProvider` | ContentProvider（`content://com.best.deskclock.breeno`），实现闹钟/定时器全部操作 |
| `com.best.deskclock.breeno.BreenoProxyReceiver` | 有序广播接收器（`com.best.deskclock.action.BREENO_PROXY`），把请求转给 Provider 的静态入口 |

两个组件均在 `AndroidManifest.xml` 中声明为 `exported="true"`。

## 入口

- **Provider** `call(method, arg, extras)`：`arg` 必须是共享令牌，否则返回 `result=-1`。
- **Receiver**：校验令牌后调用 `BreenoProxyProvider.handle(context, method, args)`，结果经
  有序广播 `setResultExtras` 返回。

令牌与模块端一致：`BreenoProxy#2026#com.best.deskclock`。

## 实现说明

- 所有操作通过 `runOnMain` 投递到主线程执行（`DataModel` 强制主线程）。
- 闹钟操作复用现有 API：`Alarm`、`AlarmInstance`、`AlarmStateManager`、`Events`。
- 定时器操作复用 `DataModel`：`addTimer` / `startTimer` / `pauseTimer` / `resetTimer`。
- 结果以 OPPO 私有 provider 的 Bundle 格式返回（`alarm_id_list`、`alarm_hour_list`、
  `alarm_state_list` 等），使小布能正确解析。

## 结果格式

返回的闹钟列表键（与 OPPO `AiSupportContentProvider` 对齐）：

```
result, alarm_id_list, alarm_hour_list, alarm_min_list, alarm_label_list,
alarm_state_list, alarm_repeat_list, alarm_repeat_set_list, alarm_time_list, ...
```

**注意**：小布的闹钟列表解析器（`com.heytap.speechassist` 中的 `iv.n.m`）有两个分支，
按 `FeatureOption.r()` 区分读取的"启用状态"字段：

| `FeatureOption.r()` | 读取的字段 |
|---|---|
| `true` | `alarm_status` |
| `false` | `alarm_state_list` |

因此 Provider 必须**同时输出 `alarm_status` 与 `alarm_state_list`**，否则启用过滤会把闹钟
全部滤掉。

定时器键：`result`、`timer_id`、`duration`、`left_time`、`time_stamp`、
`timer_status`、`description`。

## 小布取消闹钟流程（逆向结论）

小布取消闹钟的客户端逻辑（`com.heytap.speechassist`，混淆后）大致为：

```
语音「取消X点闹钟」
  → CloseAlarmOperation.process()        (skill.clock.operation)
      → iv.n.m(get_alarm_list, TRUE)    解析闹钟列表，按 enabled 过滤
      → iv.n.o(QueryAlarm, ...)         匹配用户请求，返回 condition
          - condition=2 → xu.i.D(...)   (按时间精确匹配)
              - arrayList3.size()==1    恰好一个启用闹钟命中
                  → xu.i.G(id, ...)     → 调 provider close_alarm  ← 只有这里才会真正关闭
              - arrayList3.size()==0    无启用闹钟命中 → 走其他分支（可能打开 UI）
```

关键点：
- 只有匹配到**恰好一个"已启用"闹钟**时，小布才调用 `close_alarm`（`xu.i.G`）。
- 我们尝试补齐 `alarm_status` / 完整格式 / 数据库镜像后，小布仍打开 UI，说明匹配在更早环节
  （`iv.n.o` 的 `QueryAlarm` 解析或 `iv.n.m` 过滤）未命中我们的闹钟，具体原因未最终定位。
- 仅当闹钟由 OPPO 时钟**原生创建于同一会话**时，小布才会直接调用 `close_alarm`。

## 已知限制

- **语音取消闹钟**：小布取消闹钟时是客户端决策 —— 先 `get_alarm_list` 拿到闹钟，然后**打开
  OPPO 时钟的 `AlarmClock` 界面**让用户手动取消，而**不调用 `close_alarm`**（`close_alarm`
  方法本身可用，`result=1`）。
  - 已尝试但未改变该行为的方案：
    1. 拦截 `add_alarm` 时把闹钟**镜像写入 OPPO 时钟数据库**（禁用状态，避免双响）——
       镜像创建成功，但小布仍打开 UI。
    2. 让 `get_alarm_list` 返回**完整 OPPO 格式**（所有 30 个数组填充基本闹钟值）——
       仍打开 UI。
    3. 补充 `alarm_uuid_list` —— 仍打开 UI。
    4. 补充 `alarm_status` 字段（小布解析器的一个分支读它）—— 仍打开 UI。
  - 逆向分析（见上文"小布取消闹钟流程"）确认关闭链路，但未定位到决定性的匹配失败原因。
  - 结论：小布对"非原生 OPPO 闹钟"的语音取消固定回退为打开自带时钟 UI，无法通过
    provider 数据操控改变（仅当闹钟由 OPPO 时钟原生创建于同一会话时才会直接调用
    `close_alarm`）。
- 定时器：小布先 `check_timer`，若存在活跃定时器则可能复用而非新建；`start_timer` /
  `pause_timer` / `resume_timer` / `cancel_timer` 方法层均验证可用。

## 安全

- 令牌校验防止未授权调用（Provider 的 `arg` 与 Receiver 的 `token` 额外）。
- 模块端仅在小布作为调用方时才会转发。
