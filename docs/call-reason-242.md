# Call Reason / Dialer 242

PCS 原先只覆盖 `com.google.android.dialer.directboot/45664158`
（enableUnifiedExpresso）。Dialer 242 的设置入口还要求 `45740418`
（enableExpressoUi）；发送界面的 ExpressoController 也读取这个独立开关。
因此此前主开关生效后仍没有设置入口。

main 和 fix/dialer-hooks 使用同一修复：PCS 的 Call Reason 开启时，两项标志
都返回 true；关闭时保留两项标志的原始值。继续使用已有的独立 FlagValueHolder
覆盖机制，不修改权限、原生用户偏好、对端能力检查或短信收发逻辑。

当前版本的原生入口名称是 **Expressive Calling**，位于 Phone → Settings →
General。页面内的 Call Reason 开关和 SMS 权限仍由 Phone 管理。

两个分支分别运行 `bash gradlew --offline :app:testDebugUnitTest :app:assembleRelease`。
main 的 58 项测试、fix/dialer-hooks 的 25 项测试及两个 Release 构建均通过。
实机记录位于工作区 `diagnostics/call-reason-20261016/`，不提交设备原始日志。

2026-10-16，本机 Dialer `242.0.998227485-publicbeta-pixel2026` 实测：

- 两个分支均恢复 Expressive Calling 入口，页面主开关显示已开启；运行日志均
  确认 EXPRESSO_ENABLED 与 EXPRESSO_UI_ENABLED 实际覆盖为 true。
- fix 版中关闭原生主开关后页面正确显示关闭，再恢复开启，证明仍尊重原生用户偏好。
  关闭 PCS 的 Call Reason 后冷启动，完整设置列表确认入口消失；随后恢复 PCS 开关。
- 本机已有 SEND_SMS/RECEIVE_SMS 权限，未修改权限或“打断勿扰”设置。
- fix 版测试期间临时使用它支持的 Call Screen US 配置；回到 main 后恢复 CN。
  测试后的 PCS 偏好与测试前完全一致，手机保留 main 修复版。
