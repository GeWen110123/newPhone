# Phone

这是一个收敛后的单体 Spring Boot 服务，保留原项目的抖音 Android/Appium 采集链路、账号/视频/评论/设备业务，以及必要的用户登录与用户管理能力。

## 技术栈

- JDK 21 + Spring Boot 2.7.18
- Maven 单模块构建
- MySQL + MyBatis + PageHelper
- Redis：登录会话、采集设备分布式锁、缓存
- Appium Java Client 7.6.0 + Selenium 3.141.59：保持原采集代码的 `MobileElement`、`TouchAction` 兼容性

## 运行配置

数据库、Redis、Token 密钥和采集输出目录均使用环境变量，不再依赖原工程中硬编码的密码或本机路径：

```text
MYSQL_URL=jdbc:mysql://localhost:3306/phone?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
MYSQL_USERNAME=phone
MYSQL_PASSWORD=your-password
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
TOKEN_SECRET=replace-with-a-long-random-secret
DOUYIN_OUTPUT=D:/douyin_output
```

初始化数据库：执行 `src/main/resources/db/phone.sql`。该文件沿用原项目已有表结构；新增或启用对应 Mapper 前，应同步补齐 `module` 采集链路所需的业务表结构。

## 构建与启动

将 `JAVA_HOME` 指向 JDK 21，并将其 `bin` 目录加入 `PATH`。IntelliJ IDEA 的项目 SDK、语言级别和 Maven Runner JRE 均使用 JDK 21。

```bash
java -version
mvn -v
mvn clean package
java -jar target/phone.jar --server.port=8099 --phone.maintenance-enabled=false
```

也可在 Windows 运行 `ry.bat`，在 Linux/macOS 运行 `sh ry.sh`。两者读取 `JAVA_HOME`，支持附加 Spring Boot 参数，并以前台方式运行。按 `Ctrl+C` 停止服务。

统计、执行列表、登录和其他业务接口由同一个 Spring Boot 服务提供，统一使用8099，不需要常驻8100服务。前端开发代理仅配置 `API_TARGET=http://127.0.0.1:8099`。

当前配置的抖音定时任务默认开启；仅做隔离验证时显式关闭，避免重复调度：

```text
DOUYIN_SCHEDULER_ENABLED=false
```

正常主服务应保留业务需要的调度配置；启动维护程序默认关闭，不能为重启服务随意启用。采集实现集中在 `com.phone.adb` 与 `com.phone.module.service`，原有接口路径和 Mapper 命名保持不变。Windows上不要覆盖正在运行的jar或IDE使用的classes，先独立构建、测试，再停止旧实例并切换，避免文件锁和版本混用。

## 模拟器执行（默认采集目标）

采集任务现在只绑定 D3 模拟器实例，不再使用 ADB 连接真机。一键部署包把 SDK、AVD、抖音 40.9.0 和回执写入 `F:\D3SereinLab`，后端优先读取
`F:\D3SereinLab\instances\collector-new\evidence\one-click-*.json` 中的 `Serial` / `AdbServerPort`，回执缺失时才回退到 `application.yml` 的基线值
`emulator-5668` / `5068` / Appium `4725`。任务下发时 `devId` 必须是该序列号。

- `GET /module/emulator/status?deviceCode=`：返回实例、序列号、ADB 端口、在线状态、兼容层与校准状态。
- `POST /module/emulator/start`：启动/复用实例并恢复 Serein 兼容层，已就绪时直接返回状态。
- `GET /module/emulator/mirror?deviceCode=`：返回绑定到该实例私有 ADB 服务的 scrcpy 命令。

后端与 Appium 必须使用同一个 `adb.exe` 和服务端口；独立 ADB 服务禁止 Appium 重建。可用环境变量 `D3_INSTANCE_NAME`、`D3_DEVICE_SERIAL`、
`D3_ADB_SERVER_PORT`、`D3_APPIUM_URL`、`D3_LAB_ROOT` 覆盖默认值。手动启动时仍需先执行 `./run-d3.ps1`（或调用上面的 start 接口），
因为 Android 冷启动后兼容层不会随 userdata 保存。

Windows 上接入独立 Android 模拟器及复现模拟评论测试，见 [模拟器采集接入](docs/douyin-emulator.md)。

2026-11-20 登录资源 0% 的 VPN 路径排查、已验证的模拟器 CDN 分流与恢复方法，见
[网络恢复记录](docs/d3-network-recovery-2026-11-20.md)。登录次数限制及视频稳定性需分别处理。
同日 17:50 后已按要求清除旧 D3 实例及引擎并重新安装，当前状态见
[清理重装记录](docs/d3-clean-reinstall-2026-11-20.md)。

当前本地模拟器为 D3 Android 11 模板及抖音 40.9.0。PowerShell 7 中运行
`./run-d3.ps1` 可启动或复用该实例及 Appium，默认停在独立登录页；`-StartBackend` 启动关闭采集调度的后端。
实例设备号为 `emulator-5668`，独立 ADB 端口为 5068。普通视频模式发现原生崩溃，
`-Collect` 仅用于登录后的进一步验证，当前持续采集尚未验收，详情见 [运行记录](docs/d3-runtime-observations-2026-11-19.md)。

## 本次剥离

已移除前端、代码生成、系统监控、Swagger、Druid 双数据源、旧的多模块 Maven 壳、无引用的 Flask/IP 地理库工具和启动时强制设备处理逻辑。旧工程完整内容保存在工作区同级的 `phone-legacy-backup-20260920` 目录中，便于回溯。
