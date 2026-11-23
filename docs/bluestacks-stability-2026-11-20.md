# 抖音闪退与蓝叠稳定性排查（2026-11-20，未通过验收）

## 2026-11-23 继续排查

### 09:37 新实例测试进度（仍未完成稳定性验收）

- 官方引导安装器的文件名携带 Android 13 目标信息。误用完整版的命令参数后，
  管理员子进程曾将 `Tiramisu64` 当作父安装器文件名；已移除参数，并直接以管理员方式
  启动官网原名文件。09:18 正确唤起管理器 `installImage`，09:21 新实例注册完成。
- 已确认 `Tiramisu64` 为 SDK 33、`zh-Hans-CN`。它的 `adb_port` 仍为起始偏好 5555，
  真正连接端口记录在 `status.adb_port`：首次为 5556，重启后变为 5555。
  启动及网络恢复脚本均已改为读取动态状态端口，避免连错实例。
- 旧 Pie64 中用户自行升级到抖音 41.2.0，仍发生退出。已从旧实例提取这个 APK，
  与原版 40.9.0 用 apksigner 校验为相同签名，SHA-256 为
  `95D0C8C8EA7BF087DEDEBD1E0855664B8352E7B52CBF9F2E58843546DA14AFE0`。
  APK 在证据目录 `douyin-41.2.0.apk`，旧实例随后关闭，登录数据未清除。
- 新 Android 13 的 41.2.0、40.9.0 默认 Vulkan 首启都在 `lynx-card-servi` 线程出现
  SIGSEGV / `0xdead1007`，栈位于 `/system/lib64/libhoudini.so`。
  ARM64 专用 ABI 对照无改善，已恢复完整 ABI 列表。
- 新实例改 OpenGL，清理新实例中未登录的抖音数据，并通过蓝叠官方 `launchApp`
  路径打开 40.9.0 后，主进程 2535（父进程 382）存活并进入首页隐私提示，
  本次崩溃缓冲区无新记录。当前等用户本人处理隐私提示后做播放、滑动和重启测试。
  这些同时变化的条件不能单独归因为 OpenGL，也不能据首屏标记稳定完成。
- 当前启动默认实例为 `Tiramisu64`，应用启动统一使用蓝叠官方命令。
  原桌面“抖音”快捷方式仍未替换，须等验收通过再切换，防止交付失败配置。
- 旧 Pie64 网络恢复后台已停止，避免旧实例的陈旧端口与新实例重启后的端口冲突。

- 用户确认管理器中也没有 Android 13，实际配置仍只有 Pie64 / Android 9。
- 新截图显示首页“网络错误”。现场发现安卓代理仍为 `127.0.0.1:18868`，
  本机转发服务与 WLAN 正常，但 `adb reverse --list` 为空。恢复 reverse 后，
  首页内容请求恢复；独立 HTTPS 探测确认转发出口为 CN。
- 用户本人完成登录后，主进程 4244（父进程 zygote64 / 1825）保持运行，
  推荐页可显示视频，滑动可切换到另一条视频。
- 这不能作为稳定完成：08:45 的关闭/重开测试退出至蓝叠桌面，随后标准
  MAIN/LAUNCHER 启动同样未保持主进程。日志有 ARM64 NPTH dumper
  `unexpected e_machine: 183`；它说明崩溃转储器加载也失败，不能把它等同于实际崩溃根因。
- 新增 `scripts/Watch-BlueStacksDouyinNetwork.ps1`，只恢复指定实例的网络通道，
  不启动应用、清数据或操作账户；抖音关闭时删除 reverse 的故障注入测试中，
  后台在 4.2 秒恢复通道。重复检查不重建已有通道、不重复写入代理设置。
- 启动脚本支持当前 Pie64，使用标准 MAIN/LAUNCHER 意图；冷启动兼容性仍未通过，
  因此没有替换用户桌面原“抖音”快捷方式。
- 从官网实际点击 Android 13 下载取得本机同版本厂商安装程序，签名为 Valid / Now.gg。
  SHA-256：`95C756239E1CB0047720F94519C38F4890E9D59FB4A658D21D02A8284DAAB717`。
  用户登录后整个 Pie64 实例已停机复制、逐文件 SHA-256 校验通过，回执在
  `target/setup/douyin-recovery-20261123/post-login-backup/backup-receipt.json`。
- 官方安装程序已识别目标 `Tiramisu64`，随后请求管理员授权。08:55 日志记录
  `Couldnt start MI as admin` / “操作已被用户取消”，新 Android 13 实例尚未安装。
  已在桌面建立“蓝叠 Android 13 官方安装”快捷方式，请用户本人完成 Windows 授权。
  不能绕过授权或把未安装的实例标记为完成。
- 备份期间曾有一次 Pie64 启动遇到 `VERR_VD_IMAGE_READ_ONLY`。备份完成后，
  同一磁盘只申请读写句柄、不修改内容的检查已通过；该事件不能作为磁盘 ACL 损坏的证据。
- 采样仍有少数应用 UID 的公网 TCP 连接，不能将已验证的 HTTP/HTTPS 转发
  夸大为整个实例所有协议均不经过 VPN。电脑 VPN 未修改。

本次证据目录：`target/setup/douyin-recovery-20261123/`。
截至本节记录时，仍未通过连续运行及冷启动验收。

用户要求解决反复退出，并以正常首页持续运行作为完成条件。
用户已允许从 D3 切换到电脑已有的蓝叠，同时要求电脑其他软件继续使用 VPN。

## 已确认的问题

1. D3 Android 11 / 原版抖音 40.9.0 在普通首页重复出现
   `DyImageFastPluginClassLoader` 初始化失败，根因为 Librarian 按宿主 x86_64 推断
   `libinterstellar.so` ABI。模板自身兼容层检查为 Healthy，不是 overlay 丢失。
2. 用原 APK 的同一 ARM64 库预置正常解压缓存后，类加载错误消失，但马上出现
   `VOutle2-V1` 的 SIGSEGV / SEGV_ACCERR。该试验未通过，新增缓存文件已撤回。
3. 蓝叠现有 Pie64（Android 9）起初安装抖音 41.1.0，出现 NPTH 原生崩溃和 SIGILL。
   原 APK 已备份。保留数据测试签名兼容的原版 40.9.0、40.3.0，仍退出。
4. 安卓系统“清除缓存”已将缓存从 25.77 MB 清至 0 B、用户数据保留 435 MB，仍退出。
5. Vulkan 改 OpenGL 的对照无改善，图形配置已恢复原值 `vlcn`。
6. 原 APK 确实在 DEX 中支持 `jni_guard_config`、`disable_sf_opt` 和 `disable-gcblocker`。
   后两项分别涉及 SurfaceTexture JNI 优化、GC blocker。蓝叠中的短时试验仍未进入可用首页，
   三个试验文件已全部撤回。不能据文件存在标记修复成功。
7. 完整回滚备份后，按用户“继续”指示重建了蓝叠中的抖音本地数据，并安装原版 40.9.0，
   当前 Android 9 实例依旧不能稳定启动。其应用数据清理前快照仍保留，可回滚。

仅有 pidof、旧 Activity 记录或启动 Logo 均不足以判断正常。崩溃报告子进程可能保留同名进程，
必须核对真实主进程、界面可响应状态与本次崩溃记录。

## 当前计划及具体阻塞

下一步使用蓝叠全新 Android 13 实例，避免继续在不兼容的 Android 9 环境反复启动。
重新查阅蓝叠官方说明后发现，5.22.288 起已移除管理器中新建 Android 11 的入口，
本机 5.22.291.1008 因而不能执行此前的新建 Android 11 步骤；已向用户纠正为 Android 13。
依据：[蓝叠实例管理说明](https://support.bluestacks.com/hc/en-us/articles/360052834092-How-to-create-and-manage-instances-using-the-Multi-instance-Manager-on-BlueStacks-5)。
已打开 BlueStacks Multi Instance Manager。自动控制能读取部分 AX 文本，但截图持续报
`FrameArrived timed out` / `window capture timed out`，点击报
`coordinate input geometry is unavailable`；刷新窗口、重建会话和键盘操作均未恢复控制。

已请用户在管理器中完成：多开引擎 → 全新实例 → Android 13 → 下载/创建并启动。
截至本记录写入时配置中仅存在 Pie64，新 Android 13 尚未创建，正在等待用户完成这一步。
本轮再次截图仍报 `window capture timed out: timed out waiting on channel`。
不得继续把旧 Pie64 当作新的 Android 13 实例测试。

`Start-BlueStacksDouyin.ps1` 已支持按实例名动态解析 ADB 端口，默认 `Tiramisu64`，并核对 SDK 33；
也保留显式指定已有 Android 11 / SDK 30 实例的支持，不接受 Android 9。
新实例准备后需实际安装 APK、验证本机直连、正常首页及连续播放/操作，才能评价稳定性。
当前没有通过稳定性验收，也未提交账号登录、短信验证码或认证。

## 网络要求

- 啊哈第一项“全局模式”需保留。第二项“全局模式（代理）”会使用户的 Codex 连接失败，
  用户已明确要求保留电脑其他软件的 VPN，不再建议全局切换该模式。
- 模拟器 HTTP/HTTPS 通过本机 loopback 转发，单连接绑定 WLAN，DNS 使用 WLAN 网关。
- 原始 D3 使用 `10.0.2.2:18868`；蓝叠使用 ADB reverse 与 `127.0.0.1:18868`。
- 独立探测确认该转发出口为 CN、电脑默认出口为 US，二者公网出口不同。
- 该配置说明 HTTP/HTTPS 路径，不代表所有 UDP 或忽略系统代理的应用均已验证。
  新实例的抖音实际连接还需核对，不能笼统声称全部虚拟机流量已直连。

## 备份与证据

目录：`target/setup/bluestacks-stability-20261120/`。

- `douyin-before-41.1.0.apk`：原蓝叠安装包备份。
- `pre-clean-backup/`：停机后复制并逐文件 SHA-256 校验的 `Data.vhdx`、`Root.vhd`、
  `bluestacks.conf` 和回执，约 11.64 GB；不能删除此回滚点。
- `install-40.9-preserve-data.log`、`install-40.3-preserve-data.log`：保留数据安装记录。
- `app-cache-cleared-ui.xml`：缓存清零、原用户数据保留的证据。
- `app-data-rebuild.log`、`install-40.9-clean-data.log`：备份后重建抖音数据的记录。
- `dex-analysis/`：原版 APK 的相关 DEX 分析，只用于兼容性诊断。
- `409-first-check.json`、`409-native-markers.json`：原生崩溃记录。

D3 已停机，失败的库缓存预置已撤回。项目后端/前端未在本轮启动，采集未启用。
旧蓝叠 Android 9 不应被标记为已修复；下一次继续应先核对 Android 13 新实例是否已创建。
