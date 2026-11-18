# 统计分析接口与指标口径

三个只读接口均返回现有 `AjaxResult` 格式：`{code:200,msg:"操作成功",data:{...}}`。需要现有登录令牌，不拉取分页明细、不受原前端 5,000 条上限影响。数据库需 MySQL 8+（内容资产使用窗口函数）。本轮不改表、不增加生产数据、不累计执行记录上的关联资料快照。

## 权限、故障与公共字段

- 业务来源沿用原模块 list 接口的已登录访问范围。原业务表没有用户/部门数据范围过滤，本接口不引入更宽的匿名访问。
- 用户来源还必须通过 `system:user:list`，并经单独 Spring Bean 上的 `@DataScope(deptAlias="u",userAlias="u")`，保留原部门、自定义部门、下级部门、仅本人范围。`sys_user` 已有 `dept_id`，无需无条件关联部门表。权限范围由原框架生成，客户端不能提交 `params.dataScope`。
- 现有 `SysPermissionService` 会给普通用户 `*:*:*`；本接口继承原权限服务结果，未修改全局权限策略。若要收紧全系统权限，应另行修复该原有配置。
- `resources.sources`、`content.kind` 用于只查询页面当前可访问的来源，不是替代后端身份验证的授权机制。
- 资源与内容按来源隔离异常，`sources[key]={status:"ok"|"denied"|"error",message?:string}`。不可用数值为 `null`，不可用图表为 `[]`，不能显示成真实 0。执行接口任一必要查询失败则沿用统一错误响应。
- 每个聚合请求使用只读 `REPEATABLE_READ` 事务，在同一快照中读取汇总、趋势与榜单，避免采集中多条查询跨时点导致彼此不一致。
- `generatedAt` 为带 `+08:00` 的生成时间。时间边界在 Java 中按 `Asia/Shanghai` 生成，不依赖 MySQL 的 UTC `CURDATE()`。
- `period` 只允许 `7/30/90/all`；`kind` 只允许 `all/account/location`；不支持的值返回业务 `code:400`。默认 `period=30,kind=all`。
- `rangeStart` 为所选起日（all 为 null），`rangeEnd` 为今天，日区间是起日 00:00 至明日 00:00（不含）。7 日包括今天及前 6 天。
- `trendUnit` 为 `day/month`，all 使用月。日趋势补齐缺失日期为 0；月趋势从首个有记录月补到当前月。跨度超过 1,200 个月时只返回有记录月份，`trendContinuous=false`；否则为 true。无历史数据时月趋势为空。
- 比率单位为百分比，保留一位小数；分母为 0 返回 null。

## GET /module/analytics/executions

参数：`period=7|30|90|all&kind=all|account|location`。

返回字段：

```text
summary: {total,waiting,running,completed,error,unknown,completionRate,taskTotal,activeDevices,runTotal,runKnownTotal,runMissing,runInvalid}
missingTime: number
trend: [{date,total,completed,error}]
calendar: [{date,value}]
regions: [{key,name,value}]
regionSummary: {total,missing,searchExcluded,displayed,other}
tags: [{name,value}]
devices: [{name,total,completed,error,completionRate}]
tasks: [{key,name,total,completed,error,completionRate}]
breakdowns: {tags:{total,displayed,other,limit},devices:{...},tasks:{...}}
```

- 数据为 `video_tags` 当前执行记录，一行计一条。不是执行日志，更新同一条记录不会新增历史事件。
- 统计日期为 `COALESCE(update_time,create_time)`：记录更新时间，缺失时回退创建时间。趋势表示按当前记录的该日期归属；不能解释为当日真实完成次数或耗时。
- 状态：0 等待、1 运行、2 完成、3 异常；其他及 null 为未知。`completionRate` 是“完成状态占比”。原调度器在累计尝试达到 30 次时也会将状态置 2，因此该指标不能称“采集成功率”。
- `missingTime` 是该 kind 下两个时间均为空的总记录数，与 period 无关。这些记录只计入 all 汇总，不归属任何日期。未来时间不计入执行区间。
- 地点采集：标签含“地址”“附近”“综合视频”。账号采集：不属于地点，且含“视频”“基本信息”“评论”“粉丝”“关注”中的至少一个已知标签。未知/空标签只进入 all。
- `taskTotal` 是所选记录中不同非空 `task_id` 数，包含已经删除的历史主任务关联，不是现存任务配置总量。`activeDevices` 是不同非空 `dev_id` 标识数，不是设备当前在线数。
- `run_num` 在调度器成功取得设备锁并提交异步采集后递增，包含失败的尝试。`runTotal` 是所选记录的累计调度尝试次数，不是所选期间发生的历史事件数。只要所选记录有 null 或负值，runTotal=null，避免将未知当 0；runKnownTotal 为已知非负值之和，runMissing/runInvalid 分别为 null/负值行数，用于诊断。
- `calendar` 固定近 90 天，独立于 period、保留 kind，不将热力颜色当作历史在线状态。
- `regions` 是“采集目标地点”，按抓取器真实分支优先级处理：标签含“地址”时用 douyin_id 目标地点；否则含“综合视频”是搜索关键词，排除并计入 searchExcluded；否则含“附近”时用 address 模拟定位配置地址。附近的 douyin_id 是归档标签，不猜测它为地理位置。账号任务不参与地点统计。
- `regionSummary.total` 是同 period/kind 下地址/附近记录的独立分母，绝不能使用 summary.total 作为地点占比分母；missing 是其中未填写实际目标字段的条数。regions 展示最多 12 个已知地点（合计 displayed），并在必要时添加“未指定目标”（missing）和“其他地点”（other）桶，所以其 value 之和恒等于 regionSummary.total。kind=account 时 regions=[]、该分母为 0。
- 地点实际名称使用 `key=target:<name>`，补充桶使用 `synthetic:missing/other`，即便某实际地点名称恰好与补充桶同名也不会混淆。这里统计的是配置的采集目标，不是设备 GPS、IP 地域或人物所在地。
- `tags` 为完整采集策略组合 Top 12，不拆词、不把一条记录重复分摊给多个标签。设备和任务榜为 Top 10；任务按 task_id 分组，名称是记录保存的名称，key 用 task_id，未关联单列。
- `breakdowns` 每项的 total 是完整分母，displayed 是返回 Top 数组中数值的合计，other 是未展示的其余记录数，limit 是最大条目数。Top 数组合计不是“全部”，各 Top 百分比必须除以对应 total。

## GET /module/analytics/resources

可选 `sources=accounts,locations,devices,groups,users`，默认全部；空字符串表示不请求任何来源。未选来源 status=denied，且不执行相应数据库查询。

```text
accountsTotal: number|null
locationsTotal: number|null
deviceTotal: number|null
groupTotal: number|null
userTotal: number|null
deviceStatuses: [{name,value}]
deviceModels: [{name,value}]
groups: [{key,name,value,currentMemberCount,recordedCount:number|null}]
userStatuses: [{name,value}]
deviceModelsSummary: {total,displayed,other,limit}|null
groupSummary: {membershipsTotal,displayedMemberships,omittedGroups,mismatchedGroups,unknownRecordedGroups}|null
sources: {accounts,locations,devices,groups,users}
```

- 本接口是当前资源配置快照，不接受 period。
- `accountsTotal` 沿用原页面含义，为 `video_task.type=1` 的账号采集任务数；`locationsTotal` 为 `type=2` 的地点采集任务数。未知任务 type 不强行归类。
- 设备按 `device.status=1/0/其他` 分在线/离线/未知。型号 Top 12，空型号单列未知。
- `groupTotal` 为分组行数；`groups` Top 12 的 value/currentMemberCount 是真实有效成员数：拆分 dev_id 中逗号分隔的“名称_ID”，使用末尾数字 ID 关联现存 device，再按设备 ID 去重。设备重命名不影响计数，已删除设备、空值、无效后缀不计入。groups 来源因此会联查 device 表；不改写业务库。
- 原 `device_count` 只是设备修改分组时写入的缓存，已有陈旧记录，改为仅保留 recordedCount 供诊断，null 原样保留。groupSummary 的 membershipsTotal 是全部组内有效成员数之和，displayedMemberships 是返回榜单之和，omittedGroups 是未展示组数；mismatchedGroups/unknownRecordedGroups 记录缓存不一致/缺失。一个设备可以属于多个组，这些成员数不能当独立设备总量。
- 同名组通过稳定 group_id key 区分。型号摘要 deviceModelsSummary 使用完整设备数作分母，避免把 Top 12 合计误当全部；这两个摘要在来源未请求或失败时显式为 null。
- 用户排除 `del_flag != 0`，按正常(0)/停用(1)/未知统计，仅包含原数据权限范围。没有权限时 userTotal=null，userStatuses=[]。

## GET /module/analytics/content

参数：`period=7|30|90|all&kind=all|account|location`。period **仅控制首次采集趋势**，资产、资料、评论总量及榜单始终是所选来源当前全量快照。

```text
summary: {videosTotal,accountVideos,locationVideos,accountsTotal,locationsTotal,sourceLabelsTotal,accountTargetMemberships,locationTargetMemberships,commentRecords,uniqueCommenters}
assets: [{name,covered,total,rate}]
trend: [{date,accountVideos,locationVideos,total}]
topAccounts: [{key,name,value}]
topLocations: [{key,name,value}]
breakdowns: {topAccounts:{total,displayed,other,limit}|null,topLocations:{...}|null}
sources: {accountVideos,locationVideos,accounts,comments}
```

- 账号来源为 `video`，位置/搜索来源为 `address_video`。后者混合地点、附近和综合搜索归档，不将整张表都视为地理数据。资产指标每个来源内部以非空视频 uid 去重并取最新 create_time 的一条快照。缺失 uid 的行逐行保留，且缺失行标识与真实 uid 使用不同前缀，不会相互碰撞。位置/搜索表没有主键，时间相同按视频序号、文件路径、账号、目标标签确定用于统计的稳定次序。
- `videosTotal = accountVideos + locationVideos`，准确标题为“来源视频资产”或“来源内去重视频合计”。**同一视频可在两种来源重复出现，该值不是跨平台唯一作品总数。** trend.total 同样是两来源计数相加。
- `assets` 的“视频文件 / 页面截图 / 评论文件”分别统计最新快照中非空 `video_path/image_path/json_path` 的覆盖数。只表示数据库记录了文件路径，不证明文件仍存在或下载完好。
- `trend` 用每个来源 uid 的最早 create_time，表示首次入库资产数量，后续快照不重复贡献新增量。
- 内容接口的 `accountsTotal` 是 `account` 表不同非空 douyin_id 的资料目标数，库中可能混合人物、地点及搜索资料，标题应为“采集目标资料”。**不要与资源接口同名的任务数混用。** kind=location 时该来源未请求，返回 null。
- `sourceLabelsTotal` 是位置/搜索来源非空 address 去重数，可能包含搜索关键词、地点名称、附近归档标签，准确标题是“采集目标标签”或“视频关联目标”。locationsTotal 保留为同值兼容字段，不再称地理地点数；kind=account 时两者均为 null。
- `topAccounts/topLocations` 按“采集目标 × 视频 uid”去重后计数，重复快照不加量，同一视频关联多个目标会在每个目标分别计 1，避免全源按 uid 只取最新记录而抹去更早目标关联。accountTargetMemberships/locationTargetMemberships 是各自完整的关联数。
- 内容榜分母是 `breakdowns.topAccounts/topLocations.total`（对应目标关联数），不是来源视频资产数；displayed 是 Top 10 返回之和，other 是未展示的关联数。不可用来源对应关联数与摘要为 null；breakdowns 容器始终存在。缺失目标归“未指定目标”，与同名真实目标用 key 区分。
- `commentRecords` 为 `comment` 中关联所选来源视频 uid 的入库记录数，使用先去重的 uid 集合连接，避免视频重复快照放大评论行数。原表没有可靠唯一评论键，可能有重复采集行，故不能称唯一评论数。
- `uniqueCommenters` 为上述评论记录中不同非空 `user_uid` 数，准确含义为“已识别评论作者 UID 数”，缺失 UID 不虚构为人物。
- kind=account 不查询地点视频；kind=location 不查询账号视频和 account 资料。评论查询只连接所选视频来源。评论 uid 的旧表 collation 不同，在聚合中统一字符集排序规则，避免非法 collation 比较。
- 不使用 account_content/address_account_content 的 count 相加，不对历史人物资料快照中的点赞、粉丝等重复累计。任一视频来源故障时合计 videosTotal=null，assets/trend=[]；仍可显示其他健康来源的独立数值与榜单。

## 启动与验证

- 原 `com.phone.test` 启动维护程序会清 Redis 锁、向设备安装应用并更新采集数据，本轮为其增加 `phone.maintenance-enabled=true` 才运行的条件。默认关闭，生产需要维护时显式开启。
- 隔离联调必须同时关闭调度与维护：`--phone.scheduler-enabled=false --phone.maintenance-enabled=false`，使用独立端口，避免影响已有服务。
- Windows 上不要对正在运行进程所持有的同名 jar 直接重打包：普通 jar 写出后，Spring Boot 重打包的重命名步骤可能因文件锁失败，留下不可直接启动的中间包。使用独立 finalName 生成新的可运行产物，再由运行服务的负责人切换。当前修正版独立产物为 `target/phone-analytics-audit.jar`；启动示例：`java -jar target/phone-analytics-audit.jar --server.port=8100 --phone.scheduler-enabled=false --phone.maintenance-enabled=false`。
- `mvn test` 包含 6,000+ 行聚合不截断、账号/地点/未知分类、地理目标分支优先级与独立分母、缺失目标及 Top 尾项、目标关联跨来源快照去重、缺失 uid 与真实标识碰撞、调度次数空/非法值、原 DataScope 本人/部门过滤、未选来源不查询、权限拒绝、部分来源异常隔离、日/月补零与空分母测试。
- MySQL 8 实库只读验证覆盖 Mapper 所有查询，并对照执行状态/周期、资产去重、评论关联与分组字段。没有修改业务数据。
