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

定时器键：`result`、`timer_id`、`duration`、`left_time`、`time_stamp`、
`timer_status`、`description`。

## 已知限制

- **语音取消闹钟**：小布对"非 OPPO 时钟数据库中的闹钟"会回退为打开 OPPO 时钟的
  `AlarmClock` 界面让用户手动取消，而非调用 `close_alarm`。`close_alarm` 方法本身已验证可用
  （`result=1`），仅小布侧的语音触发路径有限制。
- 定时器：小布先 `check_timer`，若存在活跃定时器则可能复用而非新建；`start_timer` /
  `pause_timer` / `resume_timer` / `cancel_timer` 方法层均验证可用。

## 安全

- 令牌校验防止未授权调用（Provider 的 `arg` 与 Receiver 的 `token` 额外）。
- 模块端仅在小布作为调用方时才会转发。
