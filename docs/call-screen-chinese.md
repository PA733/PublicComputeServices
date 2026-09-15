# 简体中文 Call Screen（实验功能）

在 **PCS → Experiments → Call Screen Language → Chinese (Simplified) / 简体中文**
中选择。沿用现有语言设置的重启 Phone 行为。选择其他语言可退出中文模式。

## 使用条件

- 已启用 PCS 的 Call Screen，且原版 GACS 所需的 AICore 模型和 Phone 资源可用。
- Google Speech Services 已安装普通话离线包 `cmn-Hans-CN`。
  缺少时，使用 Phone 的语言资源下载入口；PCS 会将中文下载请求映射到该模型标签。
- 当前为实验实现；原版号码信任规则仍生效。验证模型的自动处理时，应使用未被
  用户标记为“非骚扰”的测试号码。

## 实现

- 中文选项使用 AICore GACS，强制选择 agentic Call Screen。
  保留原版 feature 305 和模型；推理参数改为 Chinese / Chinese / China，动作协议不翻译。
- 继续使用 Google 的 US GACS 资源包。下载与资源可用性检查按 `en-US` 执行，
  会话语言为 `zh-CN`。文件是否存在、版本和模型可用性仍由原逻辑检查。
- 在 Phone 的私有目录建立中文资源覆盖层，替换 19 条话术及其录音。
  会话初始化即使用覆盖层的根目录，保证播放模块生成的绝对音频路径也指向中文录音。
  原下载文件不变；未替换的资源通过符号链接访问。
  WAV 由 Google 原生 TTS 生成，格式为 16 kHz、单声道、PCM16。
- 语音服务将普通话标记为 `cmn-Hans-CN`。Android Speech 广播和 gRPC 的资源查询结果
  为它添加 `zh-CN` 别名，并保留原安装状态、版本和其他字段。
  缓存条目也使用显示语言，避免设置页反复刷新。
- 在 Phone 冷启动、DataStore 打开前，根据缓存中的普通话条目补齐中文别名，
  保留真实安装状态和版本。直接来电无需先打开设置页触发实时查询。
- `SpeechRecognizer` 请求、下载 Intent 和原生 gRPC 识别白名单均适配普通话标签。
  gRPC 白名单需要单独处理，否则未知语言会被 Phone 回退为英文。

## 已验证范围

开发设备：Pixel 11 Pro XL，Dialer 238.0.977157916-publicbeta-pixel2026，
AICore 0.release.prod_aicore_20260723.00_RC11.964081323。

组件探针已验证 Google 中文 TTS → 普通话 SODA → 原版 GACS。
设置入口、冷启动语言缓存、原版资源可用性均已实机验证。
真实来电已确认筛选界面、中文话术文字和普通话转写正常。
播放器日志已确认开场白和结束语读取中文覆盖层内的 WAV，且播放完成。
开场白为“您好，我是 Gemini，本次来电会被录音。请问您是哪位，来电有什么事？”

### 推理与转接的实测解释

2026-09-15 13:56 的两次真实来电均记录了 GACS feature 305 / version 4 推理成功。
模型返回 `Who`，解析为 `SPEAK / AskWhoIsThis`。随后原版
`AgenticCallScreenActionExecutorProcessor.calculateScoobyOverride` 读到
`USER marked NOT_SPAM`，将动作覆盖为 `START_RINGING`。这解释了为何屏幕直接进入
转接界面。该测试没有得到 `Block` 判定，不能据此宣称已验证自动拦截推销来电。
中文语言适配保留这项号码信任规则。
对应事件见 [筛选后的验证日志](call-screen-chinese-validation.txt)。

推理日志中的 `[Remote] ***` 是日志脱敏：源码用 `stateManager.c(true)` 打印日志，
实际请求使用 `stateManager.c(false)` 的完整转录。

单元测试覆盖话术和动作协议保留、覆盖层不修改原资源、缓存修复、路径校验、
录音格式，以及语言别名保留真实安装状态与版本。

Release 版本只保留初始化及错误日志；逐次资源选择、识别和推理参数的适配日志
由 PCS 的 Debug logging 开关控制。录音生成工具仅位于 `androidTest`，不包含在主 APK 中。

```sh
bash gradlew :app:testDebugUnitTest :app:assembleRelease
```

## 重新录制话术

话术在 `app/src/main/assets/callscreen/zh-CN/phrases.json`。
测试工具 `ChineseVoiceRecorder` 使用 Google TTS 和显式 `ParcelFileDescriptor`
合成完整文本。构建、安装测试 APK 后运行：

```sh
bash gradlew :app:assembleDebugAndroidTest \
  -PpcsTestRunner=com.kieronquinn.app.pcs.callscreen.ChineseVoiceRecorder
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
  com.kieronquinn.app.pcs.test/com.kieronquinn.app.pcs.callscreen.ChineseVoiceRecorder
```

录音写入 PCS 的 `files/recorded-chinese-prompts`。导出后转为上述 PCM 格式，
替换 bundled WAV，并递增 `ChineseCallScreenAssets.VERSION`，以重建旧的覆盖层。
可通过 instrumentation 的 `-e action Disclosure` 只录制一条；`-e text` 可指定
该条录音的新文本，在安装新版 PCS 之前生成对应 WAV。
