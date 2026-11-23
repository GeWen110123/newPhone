# D3 清理重装记录（2026-11-20）

按用户要求清理并重装当前 D3 模拟器，完成于北京时间 17:58 左右。

## 清理与安装范围

- 停止旧 D3 模拟器、私有 ADB、scrcpy、Appium 4725 和网络辅助进程。
- 删除旧实例及两份失败创建残留，清除其中的应用、登录数据和 Android 用户分区。
- 删除旧模拟器引擎、SDK 中的引擎副本、模板缓存和旧启动配置，再从已校验安装包重新安装。
- 共享 SDK 基础镜像、JDK、便携 PowerShell 和原安装压缩包保留供校验复用。
- 项目源码、MySQL/Redis、旧 BlueStacks 和其他 Android AVD 均未删除。

安装来源为用户提供的桌面 `MNQ` 文件夹。主部署 ZIP、APK、运行库 ZIP 及内嵌模板 ZIP
均核对 SHA-256。引擎使用已校验的 Google 安装 ZIP 重新解压，并重新验证固定二进制哈希。
没有恢复旧实例、快照、应用数据、JNI 调试配置或插件缓存。

当前版本仍为该部署包指定的 Emulator 37.2.12、Android 11 / API 30 r16、原版抖音 40.9.0。
本机 JDK 安装脚本的既有版本校验修正继续保留，安装包和签名校验未放宽。
启动入口继续使用项目的独立登录页、窄范围 llkd 配置和模拟器直连网络配置；未修改 APK 或兼容库。

## 验证结果

- 仅存在一个新建的 `collector-new` 实例；旧的失败创建目录已移除。
- Android 用户分区重建后，旧 JNI 配置、旧资源探针和旧代理设置均不存在。
- 新启动回执 `RuntimeReady=true`、`AppLaunchVerified=true`。
- 新 Android boot ID：`3af2b7fc-c7cd-4bef-b244-8e3862444715`。
- 模拟器 `emulator-5668`、私有 ADB 5068、Appium 1.22.3 / 4725 正常。
- 新实例重新应用 HTTP/HTTPS 直连配置，电脑其他软件的 VPN 设置未修改。
- 真实 Appium 打开“帮助”并返回通过，手机号为空；测试会话随后关闭。
- 手机预览窗口已打开，桌面“D3 抖音模拟器”快捷方式的目标与参数均有效。

没有输入账号、发送短信验证码、提交认证或轮换账号验证。
前后端仍保持停止，采集调度未启用。

## 登录限制的判断边界

用户确认同一批账号在实体手机上正常、仅此模拟器失败。重装前的实际提示是
`Maximum number of attempts reached. Try again later.`，没有明确的永久设备封禁通知。
现有日志也未提供可核实的设备封禁响应码。因此更应怀疑模拟器登录环境或该环境的频率限制，
不能认定账号都被封禁，也不能仅凭页面断言电脑硬件被永久封禁。

当前重装保留正常的模板硬件和系统特征，没有伪造设备标识或绕过验证。
重装完成不代表服务端限制已解除；新实例的真实登录、视频稳定性和完整采集尚未验收。
后续如仍提示限制，应暂停反复尝试并通过抖音 App 的帮助/反馈核实。

## 证据位置

`target/setup/d3-clean-reinstall-20261120/` 保存：

- `uninstall-receipt.json`、`verified-cleanup-targets.json`：实际清理范围与完成回执。
- `engine-install.out.log`、`fresh-launch.out.log`：引擎安装及新实例创建记录。
- `fresh-userdata-check.log`：旧调试文件和代理设置未保留的检查。
- `fresh-login-response.log`：帮助页打开、返回、手机号为空与关闭会话。
- `fresh-ready.png`：新实例未登录画面。

原实例问题排查记录保留在 `d3-network-recovery-2026-11-20.md`，其中旧 boot、PID 和登录状态
属于重装前历史，当前状态以本记录和新启动回执为准。
