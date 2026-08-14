# 重要经验（Breeno / ColorOS 集成踩坑记录）

本文记录让 ColorOS 15 的小布助手控制第三方时钟应用（本项目）过程中沉淀的经验，
供后续类似工作参考。

## 平台机制

1. **小布只认 OPPO 自带时钟**。小布设置闹钟通过 OPPO 私有 AI ContentProvider
   （`com.oplus.alarmclock.ai.AiSupportContentProvider`，authority
   `com.coloros.alarmclock.ai` / `com.oplus.alarmclock.ai`）交互，**从不向第三方应用发送
   标准 `AlarmClock` 意图**。OPPO 自带时钟再通过私有框架
   `AlarmManagerServiceExtImpl` 的 `OplusClockApp set_alarm` 事件注册系统闹钟。

2. **ColorOS 没有 `android.app.role.ALARM` 角色**。`cmd role add-role-holder
   android.app.role.ALARM` 返回 `Unknown role`；系统的"默认应用"列表里也没有"闹钟和计时器"
   项。因此"默认闹钟应用"这一概念在 ColorOS 上不存在，小布没有标准闹钟应用可路由。

3. **包可见性（Android 11+）**是本项目最大的障碍：OPPO 时钟（targetSdk 35）看不到
   `com.best.deskclock`，因此 ContentProvider 与广播均无法跨应用送达。`shell`(2000) 与
   `root`(0) 可绕过，`system_server`(1000) 与普通 App 都不行。
   甚至 system_server 调第三方 provider 也报 `Unknown authority`。

4. **进程冻结**：后台缓存（frozen）的进程收不到广播。需先唤醒进程（如 `startActivity`
   一个 `Theme.NoDisplay` 的 activity）再发广播，并给广播加
   `FLAG_RECEIVER_INCLUDE_BACKGROUND | FLAG_RECEIVER_FOREGROUND`。

5. **system_server 是唯一可靠的跨进程中继**：系统进程豁免包可见性，可通过有序广播到达
   任意 App 的导出 receiver，并能用 `ActivityThread.currentApplication()` 拿到上下文。

## LSPosed / 模块工程

6. **LSPosed 的 `system` 作用域**：在 LSPosed 管理器 UI 里勾选"系统框架"才能可靠生效；
   手动改 `modules_config.db` 的 scope 会被覆盖。普通包名作用域（如 `com.coloros.alarmclock`）
   DB 直改可用，但 system 不行。

7. **模块 APK 路径陈旧**：`adb install -r` 后 LSPosed `modules_config.db` 的 apk_path 不会
   自动更新，system_server 会加载旧 dex。需手动 `UPDATE modules SET apk_path=...`。
   卸载重装可强制重新注册（但会重置 scope/state）。

8. **Gradle 配置缓存坑**：源码改动后偶发"BUILD SUCCESSFUL"但 dex 未重新编译（stale APK），
   真机行为与源码不符。排查时先确认 APK 时间戳/dex 内容，必要时 `gradlew clean`。

9. **R8 混淆**：release 构建会混淆类名/方法名（如 provider 方法变成 `e`、`f`），崩溃堆栈
   需结合映射分析；hook 反射字符串（`Class.forName("n4.b")`）在固件升级后可能失效，必须
   全部 try/catch 并静默降级。

## Android 开发细节

10. **`ContentProvider.onCreate` 里 `getContext()` 可能为 null**：若在此处调用
    `getContext().getApplicationContext()` 会 NPE。应在 `attachInfo` 之后或安全判空。

11. **主线程执行的 Runnable 必须捕获异常**：`Handler.post(() -> { ... })` 中若抛出未捕获
    异常会崩溃整个进程。dispatch 到主线程时用 try/finally + 异常捕获。

12. **SQLite NOT NULL 约束**：外部（如小布）传来的 `label` 可能为 null，直接写入会触发
    `NOT NULL constraint failed`。插入前必须兜底为空串。

13. **`runOnMain` 的守卫分支要 `return`**：批量清理 `return null`（适配 Runnable）时容易
    误删合法返回，导致空列表 `get(-1)` 越界。谨慎处理。

## 小布取消闹钟流程（未解之谜）

14. 逆向小布 `com.heytap.speechassist` 后确认：取消闹钟只有匹配到**恰好一个"已启用"闹钟**
    时才调 `close_alarm`（`iv.n.o` → `xu.i.D` → `xu.i.G`）。补齐 `alarm_status`、完整格式、
    数据库镜像均未改变"打开自带时钟 UI"的行为，最终未定位决定性原因。详见
    `breeno-integration.md`。
