# 系统重建与输入法保活

参考 `SPRCSY/cmb_accounting_widget` 的 `master@fd036de` 中
`KeepAliveService` 的前台通知、`START_STICKY` 和事件恢复方式。
该版本没有独立 daemon。BiBi 原有服务已经返回 `START_STICKY`，本次补齐
生命周期恢复与失败处理，继续复用 `FloatingKeepAliveService`，不再增加第二个常驻服务。

## 使用与生命周期

- 用户在“其他设置”中开启原有前台保活开关后生效；默认仍关闭。
- Activity 恢复到前台、系统创建输入法、键盘再次显示时，检查服务并尝试恢复。
  进程启动的 `Application.onCreate` 不再盲目启动保活服务，避免后台初始化时丢失启动机会。
- 正常提升到前台后返回 `START_STICKY`。系统回收进程后可用空 Intent 重建，
  重建时重新读取开关。关闭或读取失败则停止，避免误保活。
- 收起键盘、结束输入、销毁 IME 不主动停止用户开启的常驻服务；
  IME 本身仍由系统绑定，不从广播或守护服务强行启动。
- 开机解锁后及应用升级后按原有偏好恢复。广播内立即提交启动，
  不把普通启动延迟到接收器生命周期结束后。未配置 Direct Boot，
  不监听锁定状态开机，不在解锁前读取凭据加密偏好。
- 启动受限时记录日志并等待下一次合法事件；提升失败返回 `START_NOT_STICKY`。
  关闭使用 `stopService`，不会为了停止而新建后台服务。

## 权限与边界

沿用已有 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、开机和通知权限，
不增加权限、不要求悬浮窗、Shizuku 或 root 来使用普通保活。
保活不录音；录音仍由现有用户触发流程和麦克风授权控制。
Android 13+ 拒绝通知权限不会禁止 FGS，但通知栏可能不显示通知。
`specialUse` 的用途仍是用户主动开启的键盘常驻，商店分发需要相应申报审核。

不移植源项目静态 `SCREEN_ON`/`USER_PRESENT` 接收器：它们不能保证唤起已死进程，
亮屏解锁也不是通用后台 FGS 启动豁免。不增加定时轮询、精确闹钟、唤醒锁或多进程互拉。
`START_STICKY` 是系统尽力重建，不保证恢复时限；强行停止、用户停止应用、
厂商后台限制和设备重启不能靠它绕过。原有可选 Shizuku/root 增强路径保留。

官方依据：
- https://developer.android.com/reference/android/app/Service#START_STICKY
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/develop/background-work/services/fgs/service-types#special-use
- https://developer.android.com/develop/ui/views/notifications/notification-permission#exemptions

## 验证

自动化：`KeepAliveRecoveryTest` 覆盖空 Intent 重建、开关关闭退出、前台通知、
重复恢复去重、携带调用令牌，以及后台启动被拒绝时的降级。

设备回归（需 Android 12、13、14+，含目标厂商机型）：

1. 保活关闭时使用/收起键盘、打开设置，无新增保活通知。
2. 开启保活且不开悬浮球、不授权麦克风，确认保活通知可出现，收起键盘后继续存在。
3. 用可回收的进程终止方式模拟系统杀进程（不要用 force-stop 当作低内存回收），
   观察系统空 Intent 重建与通知；再次调出键盘或打开设置可恢复。
4. 关闭保活后模拟旧服务重建，不应再次常驻。
5. 重启并解锁、覆盖升级，确认按开关恢复；关闭状态不恢复。
6. 拒绝通知权限、施加后台启动限制，确认键盘不崩溃，合法前台机会可重试。
7. 强行停止后应尊重系统停止状态，用户再次打开应用后再按设置恢复。
