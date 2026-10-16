# Phone 配置来源

Phone/Dialer 的配置统一保存到 PCS 设置中，由 `PhoneSettingsProvider` 返回给
Phone 进程。修改 PCS 实验设置后会重启 Phone，下一次启动读取新快照。

| 上游已有系统属性 | PCS 设置 |
| --- | --- |
| `persist.phone.pcs_enabled` | Handle Phone Manifests，仅控制清单代理 |
| `persist.pcs.debug` | Phone 调试日志，包括 Phone 进程中的 gRPC 日志 |

首次打开设置存储时，仅导入这两个上游属性中没有对应偏好设置的旧值。已在 UI 中保存的值优先，
例如显式关闭的开关不会被旧属性重新打开。导入值与迁移标记一起写入；完成后
修改旧属性不会改变 Phone 行为。旧属性不删除，其他应用的属性控制保持原状。

Call Screen 模式、Scam Detection 国家策略和计费网络下载直接使用 UI 保存值或
代码默认值，不导入开发分支中间版本的系统属性。

Provider 与 UI 使用同一进程和同一份 Device Protected Storage 偏好设置，取消
应答后的定时退出，避免跨进程缓存导致旧值或 Provider 突然死亡。旧 CE 设置只在
首次解锁后迁移；已经迁移的配置可在 Direct Boot 读取。Provider 启动不初始化
UI、加密库或 Koin，UI 与后台刷新任务按需初始化。访问仍只允许 Google Phone。

Phone 在 Application.onCreate 前读取一次配置，再按该快照安装清单代理、调试
及功能 hooks。Provider 不可用时不回退到系统属性。清单代理开关不控制其他
Phone 功能；Call Screen 和资源修复仍按各自设置工作。

## 回归验证

- `bash gradlew --offline :app:testDebugUnitTest :app:assembleRelease`。
- 单元测试覆盖上游属性的布尔解析、已保存设置优先和迁移后不再读取属性，
  并确认迁移只读取上游已有的两个属性。

历史设备验证记录保存在工作区 diagnostics 中：

- Dialer 242 实机反转旧属性后，日志仍显示 Provider 中的 Agentic、国家策略、
  计费网络和清单代理设置；Agentic 设置页没有 Missing required resources 提示。
- 实机从 UI 开启 Phone 调试、关闭清单代理后，下一次启动读取到新值：25 个
  日志方法被 hook，gRPC 代理未安装，Agentic 模型 hook 仍正常安装。
