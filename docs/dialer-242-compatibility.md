# Dialer 242 / Agentic Call Screen 兼容性修复

## 此分支的移植范围

本分支保留原有系统属性配置与功能范围，移植模型选择器、设置页定位及空 MDD
模型组校验/恢复。中文 Call Screen、中文 AI Replies 和相关资源尚不在本分支，
对应改动保留在原提交 `2a0193c`。此移植的 7 项相关单元测试与 Release 构建通过。
以下完整排查与实机记录来自 main 分支。

后续点击 Screen 的启动失败也已修复。2026-10-15 `16:00` 的真实来电使用
v6_3 资源成功启动中文 GACS，用户确认测试成功。最终版本通过 47 项单元测试。

## 本分支实机回归：资源齐全仍显示红色提示

对 `06eb1f6` 构建并安装测试后，英文 Agentic 设置页仍出现
“Missing required resources”。当时三个检查均正常：MDD 本地/请求版本都是
`1790632200`，SODA 为 `Soda already working`，AICore feature 305/version 4
为 `DOWNLOADED_MODEL`。本次并非前述空模型组或下载失败。

通过临时的只读 provider 诊断，在 `2026-10-15 17:06:01.391` 得到直接证据：

```text
BANNER_TRACE enableUnconditionalResourcesBanner:
  native=false, associated=XATU_ENABLED, override=true, holder=265203114
BANNER_TRACE provider result=true
```

新版 APK 中，`nyc` 的构造器参数下标 15（从 0 开始）是 `enableUnconditionalResourcesBanner`，
`hiw.ii` → `hiv` case 467 → Direct Boot flag `45794883`。这个开关会走
无条件资源横幅路径。

旧 `PhoneHooks` 把 creator 返回对象加入各个 flag 的集合，再用
`firstOrNull { holder in it.value }` 反查。Dialer 对不同布尔开关复用默认 holder，
所以未被模块跟踪的横幅开关也被误认为 Direct My Call 的 `XATU_ENABLED`，
并套用了启用 Direct My Call 的 `true`。实际取到哪个 flag 还会随对象登记/遍历
顺序变化。这也是只强制关闭横幅无法解决整个问题的原因。

本次在保持分支原有功能范围与系统属性配置的前提下补齐通用修复：

- 为每个已覆盖的 flag 和原对象身份创建独立 holder，保留 supplier 与元数据；
  原始共享对象不登记、不修改。删除会把未知读取归到当前 flag 的 ThreadLocal 回退。
- 显式按 `persist.pcs.call_screen_agentic` 覆盖 Agentic 会话开关
  `45770994` / `45788921`，不再依赖串值意外打开 GACS。
- Force Agentic 使用 Tidepods/GACS 入口，Duplex 模式才强制旧入口。
- 横幅开关保留原生值，真实资源缺失仍由原生检查和 MDD 空组保护报告。

11 项单元测试和 Release 构建通过；测试包括共享默认值、未知开关不被覆盖、
对象身份、supplier/元数据保留及并发读取。临时诊断代码已从最终源码移除。
修复后冷启动，设置页红条消失；日志显示多个地区开关原来共用同一 holder，
隔离后 US 为 true，其余地区为 false，`DOBBY_AGENTIC_ENABLED` 独立应用为 true。
用户在修复版测试后确认生效。结束时已恢复测试前的中文 PCS APK，CE/DE 设置文件
均与备份逐字节一致，调试恢复为关闭；恢复后的新进程确认 `region=zh-CN` 和
`Simplified Chinese GACS hooks installed`。

本轮分支修复版 APK 的 SHA-256 为
`c1c8c2e49e20040f8f8a9ea922b501ce2f3c1509e195b1b1e395bd437041b9ee`。

本地构建还补齐了 README 要求的 ARM64 `libsekret.so` 与匹配签名的生成输入，
均位于已忽略的本地构建目录；没有将密钥或二进制依赖提交到源码。
本轮测试日志与 APK 位于工作区 `diagnostics/dialer-20261015/fix-branch-device/`。

以下保留 main 分支原始排查记录。

## 现场与根因

2026-10-15，设备为 `242.0.998227485-publicbeta-pixel2026`，versionCode
`20148978`。安装的 PCS 仍输出旧版 `Overriding ... holder(s)` 日志，与当前源码
已有的 holder 隔离、Direct Boot 设置读取修复不一致。原 APK 已保留在工作区的
`diagnostics/dialer-20261015/pcs-before.apk`。

最初发现四类确定的兼容性问题：

1. **模型选择器定位依赖版本号。** 两处 hook 使用完整的
   `/call_assistant_extraction-v6_2-en_us.zip`。242 已改为 `v6_3`，导致
   `Dobby model picker not found`，模型选择与整套中文 GACS hook 都未安装。
2. **构造器参数变化。** 旧选择器有 11 个参数，242 的 `nfi` 删除了
   `enableGacsManualScreening`，只剩 10 个；七个地区 provider 全部前移一位。
   AI Replies 的 singleton config、call config、consent controller、settings source
   分别变成 15、12、9、20 个参数。settings source 的旧日志字符串也已删除。
3. **新版资源新增动作。** v6_3 的 Agentic specs 从 19 项增为 20 项，新增
   `DisclosureUrgent`。原中文资源映射会抛出
   `No Chinese recording for GACS intent DisclosureUrgent`，使整个 overlay 失败。
4. **当前源码不能编译。** `PhoneHooks` 三处仍调用已删除的
   `callScreenAgenticMode()`，应使用已有的 `PhoneSettings.effectiveCallScreenMode`。

## 修改

- `DobbyModelPicker` 使用下载组及依赖名共同定位代码，并检查 provider 类型。
  模型 hook 和中文 locale hook 共用同一解析器；按已验证的 10/11 参数布局
  设置 Agentic 和地区 provider，保留 locale 与下载地址。未知签名明确报错。
- settings source 改用稳定的依赖名定位，适配 20/21/22 参数布局；AI Replies
  适配上述构造器变化，保留原来的用户同意与模型可用性检查。
- `DisclosureUrgent` 映射到已有 `ContactUrgentDisclosure` 中文录音，保留
  新版 intent 名称、录音路径和未知 protobuf 字段。未替换 Google 下载文件。
- 恢复使用统一的 `effectiveCallScreenMode`，使入口、会话开关和模型选择一致。

## 验证

`bash gradlew --offline :app:testDebugUnitTest :app:assembleRelease` 成功，43 项测试
通过。新增选择器测试覆盖两个版本、七个地区、Agentic/Duplex，以及未知签名拒绝。

v6_3 specs 从 APK 内置的 Google 下载地址取得。ZIP 大小 `144369074` 字节，SHA-1
`088f6c1f767aafdbe331b83b311a1253d579145c`，与 `nfi` 内声明一致。
新增的真实 specs 回归测试先复现 `DisclosureUrgent` 异常，修复后验证全部 20 项
中文化、完整 overlay、打包录音存在及源配置未改变。v6_3 的 smart-reply specs
与已有 fixture 逐字节一致。

Release 包已通过 `adb install -r` 保留数据安装，并重启 Dialer。虽然原 APK
来自 GitHub Actions 的临时签名，设备实际接受了更新；未卸载 PCS。
初始化日志确认：

- `Simplified Chinese GACS hooks installed`；
- `Native AI Replies hooks installed; original consent and availability checks retained`；
- `Forcing the agentic Call Screen model for CN in nfi (10 dependencies)`；
- `Phone hooks initialized before Application.onCreate`；
- `Dobby locale: zh-CN`，SODA 使用 `cmn-Hans-CN`。

从主界面导航菜单进入设置，只有一个 Call Screen 入口，页面显示
`Automatic Call Screen`。运行日志确认 `DOBBY_AGENTIC_ENABLED=true`、选择器请求
资源版本 `1790632200`，资源可用性检查通过，AICore feature 305/version 4 的状态
为 `DOWNLOADED_MODEL`。设备仍有 v6_2 的解压缓存 `1785445514`；可用性检查通过
不等同于已下载并运行 v6_3。新版包的中文覆盖另由上述真实资源回归测试验证。

首次两通测试来电在约半秒内由 RINGING 转为 DISCONNECTED，系统记录
`MISSED / CODE_USER_TERMINATED_BY_REMOTE`。Dialer screening service 返回 Allow，
没有进入 GACS 会话，不能据此判断中文播报或推理是否成功；断线原因也不足以
确定是主叫设备还是网络环节结束了呼叫。

最终修复包的第二轮实时采集进一步确认：`14:26:46.252`，测试号码因已有
“非骚扰”人工标记被 `DobbyCallResponseVoter` 放行；`14:26:46.562` 所有手动
筛选前置检查通过，`14:26:46.568` 进入 `READY_FOR_MANUAL_SCREENING`。
`14:26:46.750` 系统收到相同的远端断线原因。没有本机拒接、挂断、接听请求，
也没有启动 GACS 会话。该号码不会自动触发筛选，需保持来电连接并手动点 Screen
才能验证播报和推理；尚不能将本次测试记为完整通话验证通过。

## 后续：点击 Screen 会话退出与空模型组修复

用户改拨另一张 SIM 后，能保持来电连接，但点击 Screen 会退出筛选。
`15:15:17.995` 复现日志为 `DUPLEX_START_FAILURE_NO_FILE_IN_FILE_GROUP`，
随后 `DOBBY_ERROR_AUDIO_PRISM_START_FAILS`。这次没有拨号器进程崩溃；此前
短暂来电的远端断线是独立问题，用户反馈可能来自运营商。

MDD 已下载元数据中，`dobby-duplex-files` 只有名称、locale 和记录时间，没有
文件，版本为 0。Dialer 242 的可用性检查没有验证文件列表，仍将此组判为可用。
启动器发现请求的 v6_3 解压目录不存在后清理旧目录，再从空组取 ZIP，最终失败。

新增 `DobbyMddHooks` 和 `CallScreenModelFiles`：

- 只对启用 Call Screen 时的 `dobby-duplex-files` 查询生效。
- 保留原生 ListenableFuture 接口、回调、超时、异常和取消语义；将没有文件的
  ClientFileGroup 视为缺失。正常组保留原对象，不篡改实际可用状态。
- 启动时尝试检查；遇到空组后在后台调用原生 MDD 维护任务与标准下载 API。
  同一时刻只执行一次恢复，重试间隔至少五分钟，保留原生下载网络限制。
- 通过公开接口及前台服务 API 的特征区分标准下载方法，排除新版同签名的内部方法。

空组保护在实机上使资源检查从 `available=true` 改为
`DUPLEX_AVAILABILITY_FAILURE_MISSING_FILE_GROUP`，设置页正确显示原生 Download
入口。新增四项测试验证两种 get、异步 listener、超时、异常、取消及正常对象保留；
最终 `:app:testDebugUnitTest :app:assembleRelease` 成功，47 项测试全部通过。

### 本次设备缓存恢复

原生维护任务成功登记版本 `1790632200`，原生 Download 按钮请求的确切地址是
`call_assistant_extraction-v6_3-en_us.zip`，但手机的 Cronet 下载报
`net::ERR_CONNECTION_CLOSED`。因此本次还需要一次性导入已下载的官方 ZIP：

1. 停止 Dialer，备份 MDD groups/shared-files/manager 偏好文件。
2. 将官方 ZIP 放入 MDD 已为该 SHA-1 分配的文件
   `datadownload/shared/public/datadownloadfile_1787933728143`，恢复应用的 UID、权限和
   SELinux 文件标签。手机上再次验证 SHA-1 和大小均与内置配置一致。
3. 仅将 shared-files 中 `088f6c1f767aafdbe331b83b311a1253d579145c|0` 的缓存状态
   从 pending 改为 downloaded；其他条目不变。没有手动将空模型组改成可用。
4. 新增恢复逻辑调用原生维护与下载 API，原生 MDD 使用校验后的本地文件完成组登记。
   `15:57:14.630` 记录 `Native Call Screen model recovery complete; files available=true`。

偏好备份与导入记录位于 `diagnostics/dialer-20261015/mdd-backup/`。导入是本次设备
的网络故障处理，不是模块内绕过网络限制的新功能。后续下载仍需要正常网络。

### 最终实机验证

页面在下载前缓存了“Missing required resources”；退出并重新进入页面后提示消失。
`15:59:14.836` 记录 `Request:1790632200, Local:1790632200, available:true`，
SODA 显示 `Soda already working`，AICore feature 305/version 4 为 `DOWNLOADED_MODEL`。
资源包的 v6_3 与 AICore 模型版本号属于不同层次。

`16:00` 真实来电：

- `EVENT_DOBBY_USE_GACS_MANUAL_SCREENING_SELECTED`；
- `16:00:26.595` 实际解压本地 `1790632200`，与请求版本一致；
- `16:00:28.743` 中文资源根目录为 `624af52e53f536c1866e7b3d`；
- `16:00:28.755` 参数为 `Chinese / Chinese / China`；
- `16:00:28.760` 记录 `Duplex conversation started`（GACS 也使用这个底层 runner）；
- 用户确认本次测试成功。

一次临时 Frida 附加诊断曾造成目标进程退出，已停止该诊断并移除服务端；此事件与
最初的空模型组会话失败分开记录。最终恢复和通话测试均通过常规 PCS/LSPosed 路径。

诊断日志和构建输出保存在工作区 `diagnostics/dialer-20261015/`，不提交含通话信息
的原始日志。测试前后的 PCS 设置文件已对比一致。临时的 `persist.pcs.debug`
已恢复为 `false`，并在无通话时重启 Dialer，以移除本轮临时安装的详细日志 hook。

最终 APK：`app/build/outputs/apk/release/app-release.apk`，SHA-256：
`64b7186ce1704a021dc0d80e0466355d9e2a312e93e5bf592fa3c6d96973f2b6`。
