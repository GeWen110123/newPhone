# 本地数据库依赖可用性

2026-10-19故障复盘：Windows 15:47:59开始安装WSL 3.0.3，15:48:33停止WSL Service，Docker引擎连接断开，15:49:09记录MySQL/Redis/MongoDB同时退出。MongoDB因unless-stopped策略自行恢复，MySQL/Redis原为no，持续停止导致Hikari30秒超时和Redis连接拒绝。

当前mysql-container、redis均已恢复，并持久化设置restart=unless-stopped。后续创建替代容器时必须显式保留该策略（docker run使用--restart unless-stopped，Compose使用restart: unless-stopped），继续挂载原数据卷；不得为恢复服务清库、删除卷或启动维护清锁程序。Docker重新可用后由引擎恢复容器，应用保留现有重连能力，不使用周期性强杀重启。

该策略解决依赖未自动恢复，不提供WSL更新/电脑关机期间的零停机。要求持续在线时应迁移至独立数据库主机，先备份与验证，再安排切换，不自动改全机Windows更新策略。完整时间线和证据位于前端new/73-容器停止根因与长期恢复配置.md。
