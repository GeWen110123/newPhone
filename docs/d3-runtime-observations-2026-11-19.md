# D3 本机运行验收记录（2026-11-19）

本机使用用户提供的 `SHA256SUMS.zip`：Android 11 / API 30 Google APIs x86_64 r16、
Emulator 37.2.12 build 16428233、原版抖音 40.9.0（400901 / ARM64）、22 个 Serein 库。
实例为 `collector-new`，设备号 `emulator-5668`，独立 ADB 5068，SwiftShader，4 核 / 6 GiB。
包内已有资产和安装后的原版 APK 均按 SHA-256 校验。

## 已通过的范围

- 项目使用 JDK 21 构建成功，85 项 JUnit 测试通过。
- 项目真实 Appium 会话连接新设备成功，使用私有 ADB 端口 5068，ADB 服务 PID 未被替换。
- 后端 127.0.0.1:8099、Appium 1.22.3 的 127.0.0.1:4725 均正常。
- 使用项目配置认证连接 MySQL 执行 SELECT 1、Redis 执行 PING，均通过。
- 项目自带定位辅助 APK 已由新的 classpath 安装逻辑装入新设备；不再读取旧工程绝对路径。
- 19:35:08–19:39:06，项目 swipeUp() 连续上滑 30 次通过，31 次观察有 31 个不同作者。
  这段约 4 分钟的样本没有 ANR 或崩溃；它不能代表长时间视频播放或完整采集验收。

## 普通播放模式发现的问题

19:46 首次延长留屏出现 Java 崩溃：

```text
java.lang.NoClassDefFoundError: com.bytedance.mira.core.DyImageFastPluginClassLoader
Caused by: com.bytedance.librarian.LibrarianUnsatisfiedLinkError:
can not ensure abi for libinterstellar.so, check x86_64
```

安装记录确认 primaryCpuAbi=arm64-v8a。APK 中确实存在对应 ARM64 库；Librarian 的回退逻辑按
宿主 ART 的 VM 指令集过滤库，不能据此把问题当作漏装 APK 或项目 ADB 端口错误。
原始日志保存在 `target/setup/d3-idle-crash.log`。

尝试把原版 APK 的同一库放入其正常解压缓存后，另一次启动记录了视频线程原生崩溃。
当时读取的应用自有 tombstone 摘录为：

```text
pid: 25291, tid: 25891, name: VOutle2-V1 >>> com.ss.android.ugc.aweme <<<
signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0x7cae107ba880
backtrace: #00 pc 00007caaabe3d9fc <unknown>
```

该次原生崩溃由应用自己的 npth 处理，Android crash buffer 没有新增 Java 崩溃，
主进程在退出前仍保留了一段时间，且 ActivityManager 留有旧 Activity 记录。
因此不能仅用截图、Activity 名称或 pidof 输出判断应用仍可用。
后续留屏监测同时检查主进程身份、系统 crash buffer 和 npth 原生崩溃目录。

缓存预置试验未通过稳定性验收，已撤回。原 APK、原版 22 个兼容库均保留；
失败试验代码仅归档在 `target/setup/Repair-D3DouyinLibraryCache.experimental.ps1`，不在启动链路中。
现有证据不足以把原生视频问题归因于缓存操作或某一个渲染设置。

## 等待登录的运行方式

桌面“D3 抖音模拟器”和 `run-d3.ps1` 默认使用 `-LoginOnly`：直接打开
`com.ss.android.ugc.aweme.account.business.login.DYLoginActivity`，
使用 NEW_TASK / CLEAR_TASK 清除后方的视频 Activity，不输入手机号或验证码。
这用于保留独立登录页，并不把普通视频模式的已知崩溃标为已修复。

启动适配代码为 `scripts/Configure-D3Launcher.ps1`，基于原版一键脚本的固定 SHA-256 生成本机入口。
再次运行会检查当前启动归属和真实主进程；已有可用登录页时保留当前页面，崩溃退出后可重新打开。
`-Collect` 仍是普通应用模式，只能用于登录后的进一步兼容性验证。
当前采集调度和维护任务关闭，旧任务没有迁移或重跑。

第一轮独立登录页仍在约 10 分钟后退出，但此次原因不同于前述视频崩溃。系统日志确认：

```text
Z 600.000s 31077->31220->31220 [logcat] [kill]
Killing 'com.ss.android.ugc.aweme' (31077) to check forward scheduling progress in Z state for '[logcat]' (31220)
```

Android 的 llkd 检测到应用没有回收的僵尸 logcat 子进程，因而结束父进程。
当时内存充足，系统没有记录 OOM 或 ANR。原始失败观察记录为 `target/setup/d3-login-only-idle.log`。

20:42 使用 Android 11 官方支持的 `ro.llk.ignorelist.parent`，设置
`,com.ss.android.ugc.aweme&[logcat]`：开头逗号保留默认规则，`&` 仅匹配这个父子组合。
没有把抖音整体、全部 logcat 或整个 UID 加入忽略列表。
依据为 [AOSP Android 11 llkd 说明](https://android.googlesource.com/platform/system/core/+/refs/heads/android11-release/llkd/README.md)。

`scripts/Configure-D3Llkd.ps1` 在模板本身的兼容层验收之后、应用启动之前执行配置。
首次配置后重启 llkd，核对实际加载的默认项和新增项；重复执行核对同一 boot、PID 和启动时间，
不再次重启。检测器原二进制仍在运行，SELinux 仍为 Enforcing，22 个兼容库未改动。
设备重启后属性消失，由桌面/项目入口重新应用。证据位于实例的 `evidence/llkd-parent-*.json`。

20:43:10–21:03:19 的独立登录页静置验证通过：40 次采样，主进程始终为 PID 3481，
Android boot ID 不变，没有新增 Java crash buffer 或 npth 原生崩溃记录，登录 Activity 始终在前台。
结果记录在 `target/setup/d3-llkd-login-idle.log`。

21:03:50 使用项目真实 Appium 连接，打开“帮助”进入 `BulletContainerActivity`，
再返回独立登录页，确认手机号为空，验证界面仍能响应；之后关闭自动化会话。
日志为 `target/setup/d3-login-responsiveness.log`，最终截图为 `target/setup/d3-login-final-ready.png`。
最后核对后端 HTTP 200、Appium 1.22.3 且活动会话数为 0、llkd 仍运行、SELinux Enforcing。
Windows 接通交流电时的睡眠/显示超时原本均为 0，Android 保持唤醒；没有改动宿主电源设置。

当前结论是登录页通过本轮 20 分钟留屏和页面响应验证，保留画面等待人工登录。
普通视频模式的延迟初始化及原生崩溃尚未修复，完整采集、登录后流程和整夜稳定性没有验收。

## 继续处理底层问题所需材料

当前 ZIP 不含同版 Serein 源码包。若继续修改原生兼容层，需要：

`vm-template1-douyin-stable-v1.0.0-source.zip`

包内 SHA256SUMS 给出的校验值为：

`5b1359dbeb6e0838a668c86c193a67f49b559011966ff4dce05220c904670efe`

它对应 Serein 提交 `6806ec7f367a9c34b60af3625dc34f781e587c9b` 的源码与固定生成输入。
私有 Release 当前无法匿名下载，不能把其他提交的公共源码构建产物直接当作这版兼容库替换。

## 2026-11-20 续测与页面恢复

Windows 于 08:58 重新启动，原运行进程均已停止，不能将此归为模拟器自身崩溃。
本日已恢复 Docker、原 MySQL/Redis、Appium 4725、后端 8099 和模板实例。
当前 Android boot ID 为 `6818efbf-7255-47ae-bf59-43fded414f20`。

冷启动直接登录页时复现原生崩溃，完整证据在
`target/setup/d3-20261120/102735-baseline-pid8959`，包含 crash-time npth tombstone、maps/smaps。
故障为应用 `libjato.so +0x965b0` 扫描 ART ArtMethod 的 50 个指针，索引 10 时读到了
dalvik-LinearAlloc 后的保护页；不是此前仅凭 VOut 线程名称推测的同一位置。
原版 APK 内置 `/data/local/tmp/jni_guard_config`，支持按完整 JNI 方法名跳过指定优化。
已新增 `scripts/Configure-D3JniGuard.ps1`，仅配置
`Java_com_bytedance_common_jato_util_JNIHook_nativeInit`，验证原 APK/libjato 哈希、实例归属、
应用 UID 和 SELinux 类别；未知已有配置拒绝覆盖。APK 和 22 个兼容库未修改。
配置写入成功，运行时是否实际识别仍待进一步确认，不能仅凭文件存在标为根因已修复。

诊断期间设置过 `debug.berberis.translation_mode=interpret-only`，5 分钟登录页观察通过，
但随后实际操作出现 ANR，且另一次原生记录仍为 VOutle2-V1。
14:34 已清空该临时属性并重启抖音，恢复默认转译模式，后续不能沿用解释模式的短时观察结论。
新 VOut 证据在 `target/setup/d3-20261120/143333-interpreter-final-pid9743`：
guest LR 对应 `libttmplayer.so +0x21d9fc`，故障 PC 对应宿主 x86_64 `libgui.so +0xc7880`；
这为后续检查视频原生接口跨架构调用提供了位置，尚不足以确定修复方案。

14:35 再次通过真实 Appium 打开“帮助”并返回，确认独立登录页可响应且手机号为空，
随后关闭自动化会话。日志为 `target/setup/d3-login-recovered-20261120.log`。
用户随后进入人工验证窗口；保留窗口，不自动提交手机号、短信验证码或操作滑块验证。
此前截图中的弱网 5105 不能单独确认为 DNS 故障：Android 网络已验证，未设置 HTTP 代理，
Windows 对两个候选验证域名的 HTTPS 根路径请求可收到响应；尚未证明实际验证业务请求成功。

用户新放入 `C:\Users\test\Desktop\MNQ` 的部署 ZIP、APK、runtime ZIP、SHA256SUMS、
README-DEPLOY.md 和 VM-TEMPLATE.md，均与本机已部署来源逐一 SHA-256 相同，没有新增源码包。
当前使用的 D3 确实来自该批模板资产；此前旧 BlueStacks/API34 环境不是这套模板。

14:39 用户反馈持续“正在加载资源 0%”。实际前台为 `PluginDialogBlankActivity`，
主进程 PID 13700 仍存活，无新增 ANR/原生崩溃；SIGQUIT 线程记录显示主线程正常轮询，
多个下载线程等待 Socket/Cronet 返回，不是整机无响应。
从模拟器内部用 Java HTTPS 请求抖音主站、aweme.snssdk.com、百度均能收到响应，约 1–2 秒。
下载记录里仍有多个 `mime_type_plugin` 资源失败、已下载字节为 0。
其中与验证有关的 `m.l.aliyun_verify` 资源：主 HTTPS 地址握手失败，记录中的官方 HTTP 备用地址
返回 404；同一备用地址换用独立公共 DNS 得到的另一个 CDN 节点仍为 404。
这是具体失败资源的证据，尚不能单凭此确定当前对话框只依赖该插件。

14:49 通过真实 Appium 仅执行一次返回，从加载层回到 `ChooseAccountActivity`，
页面显示选择账号及“验证失败”，没有选择任何账号或提交认证。
现保留账号选择页，活动自动化会话为 0。退出加载层不代表登录故障已修复。
线程证据为 `target/setup/d3-plugin-loading-threads.txt`，返回验证为
`target/setup/d3-exit-plugin-loading.log`；本地下载数据库快照只用于检查资源元数据，不回写应用。
