# Call Notes / Dialer 242

Dialer 242 不再读取 PCS 原先使用的 `45661721`。新的
`FermatFeatureResolver` 根据设备、系统、语言和 SIM 地区选择功能模式，
所以 PCS 的 Call Notes 开关打开后，原生设置入口仍可能消失。

## 共用 hook 修复

`FermatFeatureResolver` 通过日志标识定位三个原生判断方法，再取它们共同调用的
私有、无参数、返回 int 的模式选择器。检查唯一性和签名，不依赖混淆名称。
只有 PCS 的 Call Notes 开关打开时才覆盖选择器。

选择器的内部模式 `4` 表示带摘要的 Call Notes；它与旧实验标志的数字不同。
不覆盖最终可用性结果，因此原生 SDK、Direct Boot、用户启用、模型检查和
录音告知仍执行。旧标志处理保留给较早版本；未知方法布局记录错误，不猜测目标。

这一部分同时应用于 main 和 fix/dialer-hooks。fix/dialer-hooks 保留原生语言及
音频地区选择，不包含下面的 main 专属配置。

## 验证

运行 `bash gradlew --offline :app:testDebugUnitTest :app:assembleRelease`。
测试覆盖共享模式选择器定位、排除最终可用性方法、未知或歧义签名拒绝。

此分支只修复上游 hook。在 CN/HK 网络环境下，Call Notes 设置入口恢复后，
原生音频地区检查仍可能报告没有可用的告知音频；不绕过或改写此语言选择。

实机诊断产物位于工作区 `diagnostics/call-notes-20261016/fix/`。

2026-10-16 验证：25 项单元测试、Release 构建通过。实机恢复 Call Notes
入口，读取到内部 mode=4、最终 feature=1、enabled=true、summary=true。
通过 PCS 界面关闭 Call Notes 后，冷启动 Phone 确认入口消失；之后恢复开关。
音频仍显示 Pending audio files download，符合保留原生地区选择的范围。
未将该 CN/HK 环境下的测试记为完整通话验证通过。

两分支共用应用数据时，main 保存的 Call Screen `CN` 枚举不被本分支识别。
测试期间临时使用已有的 `US` 配置，回到 main 后恢复 `CN`；未为此扩大源码修改。
