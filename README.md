# Phone

这是一个收敛后的单体 Spring Boot 服务，保留原项目的抖音 Android/Appium 采集链路、账号/视频/评论/设备业务，以及必要的用户登录与用户管理能力。

## 技术栈

- Spring Boot 2.7.18
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

```bash
mvn package
java -jar target/phone.jar --server.port=8099 --phone.maintenance-enabled=false
```

统计、执行列表、登录和其他业务接口由同一个 Spring Boot 服务提供，统一使用8099，不需要常驻8100服务。前端开发代理仅配置 `API_TARGET=http://127.0.0.1:8099`。

当前配置的抖音定时任务默认开启；仅做隔离验证时显式关闭，避免重复调度：

```text
DOUYIN_SCHEDULER_ENABLED=false
```

正常主服务应保留业务需要的调度配置；启动维护程序默认关闭，不能为重启服务随意启用。采集实现集中在 `com.phone.adb` 与 `com.phone.module.service`，原有接口路径和 Mapper 命名保持不变。Windows上不要覆盖正在运行的jar或IDE使用的classes，先独立构建、测试，再停止旧实例并切换，避免文件锁和版本混用。

## 本次剥离

已移除前端、代码生成、系统监控、Swagger、Druid 双数据源、旧的多模块 Maven 壳、无引用的 Flask/IP 地理库工具和启动时强制设备处理逻辑。旧工程完整内容保存在工作区同级的 `phone-legacy-backup-20260920` 目录中，便于回溯。
