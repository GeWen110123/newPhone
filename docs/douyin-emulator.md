# Windows 本地模拟器环境

## 2026-11-23 地址任务及模拟器连接

已通过真实业务服务创建任务 `959`：任务名“地址搜索：台湾圆山大饭店”，
关键词“台湾圆山大饭店”，设备 `127.0.0.1:5555`（蓝叠 Pie64，设备登记 ID 7）。
任务选项为“地址,视频,基本信息,单用户前六条视频”，旧任务 927 未修改。
任务 959 的首次真实执行因模拟器退出而中断，目前为异常待重试（`status=3`、`run_num=1`），
尚未完成搜索或确认新增采集结果。Player.log 记录 08:49:32 正常退出，不能据此认定为原生崩溃。
重新打开蓝叠后仍停在初始化阶段，ADB 5555 未监听，窗口截图超时；未修改应用数据或登录信息。

本次代码调整：新增任务按目标、设备、任务选项及所属任务查询，保留任务名称、地址、备注；
运行中的任务不重复排队，明确重试时重置重试计数。ADB 支持对未在线的本机 TCP 模拟器尝试重连，
重连后仍须核实 `device` 状态。`run-local.ps1` 可显式指定 ADB、Appium、设备及 JAR，启动前检查连接。
后端和 Appium 必须使用同一 ADB 可执行文件及服务端口；本机现有 Appium 4725 使用 D3 SDK 的 ADB，
连接蓝叠时其服务端口为 5037，不能照搬 D3 模拟器的私有端口 5068。

隔离构建已通过 90 项 JUnit 测试，产物为 `target/emulator-task-build/phone.jar`，
未覆盖正在运行的 IDEA classes。自动审批拒绝停止并切换现有后端/Appium，返回 `blocked by policy`；
8099 仍是原 IDE 进程，新代码仅在已结束的独立执行进程中验证过创建任务与采集调度。
模拟器恢复且原 8099 服务停止后，可用以下命令启动新包，仅消费蓝叠设备的任务队列：

```powershell
.\run-local.ps1 -Device '127.0.0.1:5555' `
  -JarPath 'target\emulator-task-build\phone.jar' `
  -AdbPath 'F:\D3SereinLab\sdk-win\platform-tools\adb.exe' `
  -AdbServerPort 5037 -AppiumUrl 'http://127.0.0.1:4725/wd/hub'
```

验证日志：`target/setup/emulator-task-build.log`、`target/setup/emulator-address-run.log`；
Appium 日志仍在 `target/setup/d3-appium.out.log`。数据库回查确认任务 959 和对应运行日志已保存。

## 当前环境：D3 Android 11 模板（2026-11-19）

使用桌面 `SHA256SUMS.zip` 中的 D3 `collector-vm-2026.11.11` 一键部署包，
部署目录为 `F:\D3SereinLab`。安装包、22 个兼容库和原版抖音 APK 均按包内清单校验。
固定环境为 Android 11 / API 30 Google APIs x86_64 r16、Emulator 37.2.12 build 16428233、
抖音 40.9.0（400901），4 核 / 6 GiB / 720×1280 / density 320。

- 实例：`collector-new` / `D3_collector-new`，设备号 `emulator-5668`。
- 独立 ADB：`127.0.0.1:5068`，程序使用 `F:\D3SereinLab\sdk-win\platform-tools\adb.exe`。
- D3 专用 Appium 1.22.3：`http://127.0.0.1:4725/wd/hub`。
- 便携 PowerShell 7.6.9：`F:\D3SereinLab\tools\powershell\7.6.9\pwsh.exe`。
- 本项目后端仍使用 JDK 21、原有 MySQL/Redis 和 8099 端口。
- 数据库已登记 D3 设备：`id=6`，名称 `D3_Android11_40.9.0`。
- 旧 BlueStacks 和 API 34 AVD 数据保留；旧设备号 `emulator-5554` 不代表 D3 设备。

PowerShell 7.4+ 中运行：

```powershell
# 启动或复用 D3，检查兼容层，打开独立登录页及 scrcpy，准备专用 Appium。
.\run-d3.ps1

# 启动后端供页面查询；调度、维护任务均关闭，可等待人工登录。
.\run-d3.ps1 -StartBackend

# 登录后进一步验证普通采集模式时使用；需先停止旧后端。持续采集尚未验收。
.\run-d3.ps1 -Collect
```

`-Build` 会先构建项目；`-NoPreview` 可省略 scrcpy 窗口。运行后端的终端需保留。
`run-d3.ps1` 的环境变量仅作用于自身及子进程，不覆盖系统 SDK 或 JDK 配置。
手动/IDE 启动后端时需设置 `ADB_PATH`、`ADB_SERVER_PORT=5068` 和上述 `APPIUM_URL`，
Appium 服务本身也必须使用 `F:\D3SereinLab\sdk-win` SDK，避免不同版本 ADB 替换模板拥有的服务。
`AndroidCollector` 的 ADB 命令、录屏和 Appium 会话使用同一端口；独立服务禁止 Appium 重建 ADB。
配置参数对应 [Appium 官方能力说明](https://appium.github.io/appium.io/docs/en/writing-running-appium/caps/)。

每次 Windows/Android 冷启动后必须通过模板入口恢复兼容层，不能直接以普通 `emulator -avd` 启动。
实例数据及启动回执位于 `F:\D3SereinLab\instances\collector-new`，
部署检查、测试和连接验证记录位于 `target/setup/d3-*`；后端前台输出保留在运行终端。
模板的 `calibration.json` 是 D3 自带 worker 的校准骨架；本项目使用原有 Appium 采集实现，
不启动模板的 Web、数据库或 worker，也不把该骨架设为已验收。
未登录时仅验证连接、界面及滑动，账号采集和地点采集需登录后另行验收。

部署包的本地修正：固定哈希 MSI 实际为 Microsoft JDK 17.0.20+8，
原脚本却校验 17.0.20.1+1，且 MSI 安装目录漏了版本子目录。
本机 `deployment/one-click/Install-D3CollectorVmDependencies.ps1` 已修正这两项，
原脚本保留为 `.original`；MSI 下载哈希及签名校验保持不变。
模板创建工具使用该 JDK 17，项目继续使用已有 JDK 21。
API 30 r16 镜像复用本机已安装内容，源归档 SHA-256 已与模板要求核对；
SDK build-tools 35.0.0 复用本机工具以安装 Appium 辅助 APK。
另外补齐 SDK 根目录下的 Emulator 包及其 37.2.12 登记元数据，供 avdmanager 识别；
实际启动仍使用模板固定的 `emulator-versions/37.2.12` 可执行文件，二进制哈希不变。
ADB 首次切换 root 可能返回 `unable to connect for root: closed`，现将它按预期断连处理，
随后仍必须重新登记这个实例的传输并验证 root 身份；没有跳过身份、端口或库哈希检查。
该修正同时应用于本地持久脚本及一键部署的临时脚本，原 ZIP 保持原有哈希。

### 本机验证结果

- JDK 21 构建成功，85 项 JUnit 测试全部通过。
- 正式启动回执：`RuntimeReady=True`、`AppLaunchVerified=True`。
- 后端 `127.0.0.1:8099` 返回 HTTP 200，Appium `127.0.0.1:4725` 为 1.22.3。
- 使用项目配置实际认证连接 MySQL 执行 `SELECT 1`，Redis 执行 `PING`，均通过。
- 19:35:08–19:39:06 使用项目 `DouyinAddressCrawler.swipeUp()` 连续上滑 30 次，
  31 次页面观察中有 31 个不同作者；本轮完整通过，崩溃日志为空、无 ANR。
- ADB 服务 PID 始终为 `98320`，未被 Appium 替换；会话使用 `adbPort=5068`、
  `suppressKillServer=true` 和 `noReset=true`。
- 已停在手机号登录页，手机号为空；自动化会话已关闭，手机画面与应用状态保留。
- 后端采集调度、维护任务均关闭。旧任务 927 仍绑定旧设备 `emulator-5554`，
  没有迁移或重跑；登录后如需继续该任务，再切换设备绑定并验收采集。

本次结论覆盖约 4 分钟的未登录视频流测试；账号、地点等登录后流程及长期运行尚待验收。
追加留屏时，19:46 出现 `DyImageFastPluginClassLoader` 延迟初始化崩溃，
根因日志为 Librarian 将 ARM64 的 `libinterstellar.so` 按宿主 ART 的 x86_64 指令集查找。
缓存预置试验后又记录到视频输出线程 SIGSEGV，未通过验收，已撤回该试验。
默认入口现直接打开独立登录 Activity，清除后方视频任务；配置代码为 `scripts/Configure-D3Launcher.ps1`。
该模式用于等待人工登录，不把普通视频模式的崩溃标为已修复。
独立登录页的首轮观察发现 llkd 因应用遗留的僵尸 logcat 子进程，在 600 秒后结束抖音。
现通过 Android 11 官方支持的父子组合忽略项处理，仅匹配 `com.ss.android.ugc.aweme&[logcat]`，
保留默认规则、运行中的 llkd 和 SELinux Enforcing。配置代码为 `scripts/Configure-D3Llkd.ps1`，
由桌面和项目入口在已验证的模板启动后应用；不会修改 APK 或兼容库。
20:43:10–21:03:19 的登录页静置观察通过：40 次采样，同一主进程，无新增系统或应用原生崩溃记录。
随后真实打开“帮助”并返回，页面响应正常、手机号为空；活动 Appium 会话已关闭。
观察日志为 `target/setup/d3-llkd-login-idle.log`，最终截图为 `target/setup/d3-login-final-ready.png`。
这不代表普通视频模式、完整采集或整夜稳定性已经通过。
详情见 [本机运行验收记录](d3-runtime-observations-2026-11-19.md)。
桌面入口为 `D3 抖音模拟器.lnk`，可用于之后重新打开实例和手机窗口。
桌面入口和 `.cmd` 均已验证可使用 D3 工具目录内的便携 PowerShell；不依赖 Codex 缓存路径。
系统版 PowerShell 的 UAC 安装已取消，便携版保留原运行时文件及许可，版本检查与部署包校验均通过。
完整测试记录：`target/setup/d3-final-build.log`、`d3-final-swipe-stability.log`、
`d3-service-readiness.log`、`d3-device-registration.log` 和 `d3-login-handoff.png`。
定位辅助应用已改为从本项目内置 `/PlaceMockLocation-debug.apk` 安装，真实安装验证通过。
如需覆盖该 APK，可设置 `MOCK_LOCATION_APK` 为本机文件路径；无需保留旧工程目录。

## 旧环境记录（供回退）

本机 JDK 21 安装在 `C:\Users\test\AppData\Local\PhoneDev\jdk-21.0.13+9`，
Android SDK 安装在 `C:\Android\sdk`。用户级 `JAVA_HOME`、`ANDROID_HOME`、
`ANDROID_SDK_ROOT` 与开发工具 PATH 已配置。项目 SDK、语言级别和 Maven JRE 使用 21。
已经打开的 IDEA 或终端需要重新加载配置，才能读取新的用户环境变量。
系统 PATH 中仍有 Oracle Java 17；本项目启动脚本使用 `JAVA_HOME` 中的 JDK 21。

## 后端

MySQL 和 Redis 复用现有 Docker 容器 `mysql-container`、`redis`，不需要重新初始化数据库。

```powershell
.\run-local.ps1 -Build
```

本地准备阶段关闭采集调度与维护任务，服务端口为 8099。
正式采集时按 README 中的正常启动方式设置调度，避免在等待登录期间自动执行已有任务。

需要只执行模拟器的任务队列时，先停止旧后端，再运行：

```powershell
.\run-local.ps1 -Device emulator-5554
```

该模式设置 `DOUYIN_SCHEDULER_DEVICE_ID=emulator-5554`，任务查询、异常任务恢复均限于该设备，
并跳过全局策略和全局评论维护。仍使用原有 Redis 设备锁、重试上限和采集状态记录。

## Android 与 Appium

模拟器引擎使用稳定版 37.2.13；设备使用 Android 14 / API 34、Google APIs、x86_64 系统镜像。
当前独立测试 AVD 为 `Phone_Douyin_403_API34`，安装抖音 40.3.0；
原 AVD `Phone_Douyin_API34` 保留抖音 41.1.0 和原有登录数据，已停止。
该镜像支持 ARM 应用转译。首次启动需要等待 Android 完成启动。

```powershell
& 'C:\Android\sdk\emulator\emulator.exe' -avd Phone_Douyin_403_API34 -port 5554 -no-snapshot -gpu host
& 'C:\Android\sdk\platform-tools\adb.exe' devices -l
& 'C:\Users\test\AppData\Roaming\npm\appium.cmd' --address 127.0.0.1 --port 4723
```

设备序列号为 `emulator-5554`，Appium 1.22.3 地址为
`http://127.0.0.1:4723/wd/hub`，保持与项目的 Java Client 7.6.0 兼容。
`AndroidCollector` 使用 `noReset=true`、`autoLaunch=false` 和 `dontStopAppOnReset=true`，保留设备登录状态与任务结束时的页面。
视频动画场景设置 `waitForIdleTimeout=0`，页面就绪仍由采集代码的显式定位与检查判断。

可通过以下环境变量覆盖连接配置：

- `ADB_PATH`：ADB 可执行文件路径；默认从 Android SDK 查找。
- `APPIUM_URL`：Appium 服务 URL。
- `DOUYIN_APP_PACKAGE`：默认 `com.ss.android.ugc.aweme`。
- `DOUYIN_APP_ACTIVITY`：默认 `.main.MainActivity`。

抖音 41.1.0 安装包来自[抖音官方下载页](https://www.douyin.com/downloadpage/app)，已验证 ByteDance 签名。
该版本在 Android 14 模拟器上出现无响应，暂未完成地址任务。
40.3.0 安装包来自 [Uptodown 历史版本页](https://douyin.en.uptodown.com/android/download/1209647357)，
已验证版本号 400301、包名及与官方包一致的 ByteDance 签名：
`5182ae3b1b85337bb182cf24882449f84447ded18e296a747a9f6a0a2622512e`。
安装包 SHA-256 为 `bbe30732825297514bb223800a3a05a9fd4e66ad5bead7d78e96b7b43c927bdc`。
Android 11 镜像上曾出现 `libndk_translation.so` / `ubidi_openSized_66` 原生崩溃，
最终改用 Android 14 镜像；旧 AVD `Phone_Douyin_API30` 已停止。
项目 `AndroidCollector` 已在 JDK 21 上完成真实设备连接与关闭会话验证，使用 W3C 协议。
操作者已完成新设备 40.3.0 登录，首页与视频搜索的自动化定位正常。
2026-11-19 地址任务第 5 次尝试在搜索结果加载阶段失败，已补充分类与视频卡片的显式等待，
地址任务从视频分类进入，避免混入图文结果；83 项测试及 JDK 21 打包通过。
随后等价流程验证能点击准确的“台北圆山大饭店”地点标签，
但应用地点面板持续空白，日志中 `TripPoiHeadWidget` / `TripPoiHeadTagsWidget` 反复请求布局。
临时修改显示密度未改善，已恢复默认 440；目前未采集到新的酒店视频。
正在准备 BlueStacks 5 Pie64 兼容性验证，安装器来自[官方网站](https://www.bluestacks.com/download.html)，
已验证 Now.gg, INC 数字签名有效；Windows 受保护界面需操作者手动处理。
本次排查期间已停止后端；重启操作被自动审批拒绝，原因仅为 `blocked by policy`，未重试。

## BlueStacks 兼容性验证进度

BlueStacks 5.22.291.1008 Pie64 已安装并启动，Android 9，系统支持 ARM64 应用。
主程序为 `C:\Program Files\BlueStacks_nxt\HD-Player.exe`，设备仍识别为 `emulator-5554`。
两个 Android 14 AVD 的应用和登录数据均保留，当前已停止。
操作者已在 BlueStacks 的“设置 → 高级”中开启“Android 调试桥（ADB）”并保存。
通过 ADB `install --no-streaming` 安装了签名已校验的抖音 40.3.0（versionCode 400301）和项目辅助应用。
Appium 真实连接、读取首页层级及关闭会话验证通过，未自动启动抖音。
设备已停在显示“抖音”图标的 BlueStacks 桌面，等待操作者完成登录。
后端仍停止，操作者可在登录完成后用 PowerShell 执行：

```powershell
& 'D:\IdeaProjects\newPhone\run-local.ps1' -Device emulator-5554
```

保留该窗口运行。任务 927 仍为失败待重试状态（第 5 次），尚无新酒店视频入库。
官方安装日志位于 `C:\ProgramData\BlueStacks_nxt\Logs`。
本机已存在 `F:\douyin_output\ffmpeg\bin\ffmpeg.exe`，可供项目的视频合并流程使用。
运行日志、构建日志与连接验证记录位于 `target/setup/`。

参考：[Android Emulator 命令行](https://developer.android.com/studio/run/emulator-commandline)、
[Android 官方 ARM 应用支持说明](https://android-developers.googleblog.com/2020/03/run-arm-apps-on-android-emulator.html)、
[Temurin JDK 下载 API](https://adoptium.net/installation/ci-scripts/)。
