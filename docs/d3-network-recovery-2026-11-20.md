# D3 资源加载 0% 排查与网络恢复（2026-11-20）

## 实测结论

此前的“正在加载资源 0%”与验证插件下载失败有关。当前啊哈加速器通过 TAP-Windows
网卡的 `0.0.0.0/1`、`128.0.0.0/1` 路由接管网络。
同一个原始验证插件地址，经此默认路由无法正常下载，经 WLAN 直连则成功。
这次对照把问题定位到当前 VPN 下载路径，不能把普通网页可访问当作插件下载正常。

| 检查 | 默认 VPN 路由 | 本次 CDN 分流 |
| --- | --- | --- |
| 原始主 HTTPS 地址 | TLS 握手失败 | 曾返回有效 ZIP；最终复测部分动态节点仍 502 |
| 原始备用 HTTPS 地址 | TLS 握手失败 | HTTP 206、ZIP 数据 |
| 原始备用 HTTP 地址 | HTTP 404 | HTTP 206、ZIP 数据 |
| 普通 HTTPS 网站 | 可访问 | 可访问 |

TLS 原始回包还表明：故障路径在收到 ClientHello 后、尚未收到任何 URL 请求前，
就返回了明文 `HTTP/1.1 404 Not Found`。因此该回包不能证明插件文件已从 CDN 删除。
独立公共 DNS 的节点对照仍失败，没有据此修改 Windows 或 Android DNS。

最终验收范围是原始备用下载链路恢复。主地址的动态节点仍有间歇超时，
尚未在应用内验证其自动回退结果，不能把下载探测成功等同于登录后的 0% 页面已完全修复。

Android 内的实际下载验证通过：通过配置的本机代理，从原始官方备用 HTTPS 地址
完整读取 `2,894,174` 字节，MD5 为 `2eeb5f84419c262f4174eb7199110cc0`，
与原始下载记录中的内容标识完全一致。数据仅用于校验，没有手动安装插件或修改下载数据库。

## 修复与重启入口

- `scripts/d3-cdn-proxy.py` 只监听 `127.0.0.1:18868`。
- 此模拟器的公网 HTTP/HTTPS 连接从 WLAN 发出；电脑其他应用继续沿用原有 Windows 路由。
  原始主地址会跳转到变化的第三方 CDN 域名。仅直连固定域名时复测出现过 502，
  因此最终采用整个模拟器的 HTTP/HTTPS 直连，避免跳转再次进入 VPN。
- Android 的全局 HTTP 代理设为 `10.0.2.2:18868`，只影响该模拟器。
- 代理自身使用 WLAN 网关 DNS，避免直连请求继续使用加速器的 DNS 节点选择；
  没有改动 Windows 或 Android 的系统 DNS 配置。
- HTTPS 使用 CONNECT 透传，保留原始 TLS 验证；没有安装证书、解密流量或记录请求内容。
- WLAN 出口使用 Windows 每连接的
  [IP_UNICAST_IF](https://learn.microsoft.com/en-us/windows/win32/winsock/ipproto-ip-socket-options)
  选项，没有修改电脑路由表或关闭 VPN。
- `scripts/Configure-D3Network.ps1` 支持启用、恢复原配置、重用现有进程和 WLAN 地址变化后的重建。
- 桌面与项目使用的模板启动脚本均接入此配置：宿主辅助服务先启动，Android 就绪后核对代理。
  已启用状态保存在实例 `evidence/network-direct-proxy.json`；默认不会给未启用的环境添加代理。
- 当前出口固定为本机 `WLAN`。改用有线网卡时须相应调整配置脚本。

启用、关闭、重复执行和停止后重新启动辅助服务均已实测。没有重启或清除抖音数据。
原版模板及 APK 的校验保持不变，模板入口仍由已校验的 `.original` 生成。

重新检查网络：

```powershell
cd D:\IdeaProjects\newPhone
python scripts\diagnose-d3-network.py --via-d3-proxy
```

不加 `--via-d3-proxy` 可对照原来的 Windows 默认路径；输出中的原始握手对照始终使用默认路径。
该工具只验证此已观察到的插件资源，不提交登录或验证码。

恢复模拟器原来的网络配置：

```powershell
& 'F:\D3SereinLab\tools\powershell\7.6.9\pwsh.exe' -NoProfile -File 'D:\IdeaProjects\newPhone\scripts\Configure-D3Network.ps1' -Disable
```

## 服务及登录状态

本轮已恢复 D3 模拟器、私有 ADB 5068、Appium 4725、Docker 的原 MySQL/Redis。
WHPX 加速可用，Android 本轮启动约 34 秒。数据库实际认证 SELECT 1、Redis AUTH/PING 通过。
登录页打开“帮助”并返回的真实 Appium 检查通过，结束后活动会话为 0。

用户随后提供的新截图提示 `Maximum number of attempts reached. Try again later.`。
这是当前登录频率限制，需要暂停继续发送验证请求，等待抖音解除限制；界面没有提供准确恢复时间。
资源下载恢复不能证明该限制已解除，也不能替代用户手动完成登录。
此前视频原生转译崩溃仍属于独立问题，本轮未做登录后视频或持续采集验收。
最终检查还发现本轮 Android 启动中的 `VOutle2-V1` 原生 `SIGSEGV/SEGV_ACCERR` 记录，
应用主进程仍为 8780。该证据已摘录为 `native-crash-summary.txt`。
这说明登录页能响应及网络恢复均不能代表视频兼容性已修复；采集保持未启用。
继续修改这套兼容层需要 19 号运行记录中注明的同版源码包，现有 MNQ 部署资产未包含它。

后端 8099 与前端 5555 的启动操作被自动审批拒绝，原因仅为 `blocked by policy`。
本轮没有通过其他入口绕过该拒绝，二者仍停止。可由操作者在两个 PowerShell 窗口分别执行：

```powershell
# 窗口一：后端；保持采集调度及维护任务关闭。
& 'F:\D3SereinLab\tools\powershell\7.6.9\pwsh.exe' -NoProfile -File 'D:\IdeaProjects\newPhone\run-d3.ps1' -StartBackend

# 窗口二：前端。
cd C:\Users\test\Desktop\newDyProject
npm run dev -- --host 127.0.0.1
```

## 证据

本轮输出均在 `target/setup/d3-restart-20261120/`：

- `network-check.json`：修复前默认路径失败。
- `wlan-only-probes.json`：每连接 WLAN 出口对照。
- `network-fixed-check.json`：早期三个原始下载地址均成功的样本。
- `network-final-check.json`：最终备用 HTTP/HTTPS 均成功，主地址动态节点仍 502。
- `android-download-check.log`：Android 完整下载及 MD5 校验。
- `android-download-final-check.log`：最终版本代理下再次完整下载及 MD5 校验。
- `services-check.log`：MySQL/Redis 实际认证检查。
- `login-response.log`：真实帮助页打开、返回与会话关闭。

本轮没有修改 Java 业务代码、重建后端 JAR，或重新执行历史采集任务。
