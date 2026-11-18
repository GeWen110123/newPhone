"""Manually reconciled functional descriptions. Sources, not historical claims, define status."""

def item(title, desc, status=None, refs=None, apis=None):
    return dict(title=title, description=desc, status=status, refs=refs or [], apis=apis or [], children=[])

def group(title, desc, refs, children, status='主入口'):
    return dict(title=title, description=desc, status=status, refs=refs, apis=[], children=children)

def f(path): return 'F:'+path
def b(path): return 'B:'+path
CORE=f('src/features/core/')
CTL=b('src/main/java/com/phone/module/controller/')
SYS=b('src/main/java/com/phone/web/controller/system/')
SVC=b('src/main/java/com/phone/module/service/')
ADB=b('src/main/java/com/phone/adb/')
AUTH=f('src/features/auth/')
EXT=f('src/features/system/')
DEV=f('src/features/devices/')

MODULES=[
group('01 登录、会话与账户安全','账号密码登录、身份读取、会话生命周期与密码管理；兼容流程单独标注，不能据前端分支认定后端已经启用。',
 [AUTH+'AuthProvider.tsx',SYS+'SysLoginController.java'],[
 item('账号密码登录','校验账户和密码，调用 POST /login 获取令牌，再依次读取 /getInfo 和 /getRouters；无指定回跳地址时进入统计分析。',apis=['POST /login','GET /getInfo','GET /getRouters']),
 item('记住登录状态','未勾选时会话放 sessionStorage；勾选时保存有限期令牌。勾选偏好独立保存，退出时保留偏好，不保存密码。',refs=[f('src/lib/session.ts'),AUTH+'login-preferences.ts']),
 item('会话恢复与自动到期','刷新恢复有效会话；到期、窗口恢复或后端401时清理令牌和受保护缓存。旧请求或旧计时器不注销后续新会话。',refs=[f('src/lib/session.ts'),f('src/utils/request.ts')]),
 item('详情新标签与跨窗口退出','同源详情新标签可通过 BroadcastChannel 恢复当前会话；退出同步到其他窗口，凭据不放入URL。',refs=[f('src/lib/session.ts')]),
 item('退出登录','调用 /logout，随后清除本地身份、路由和令牌；接口失败仍清除本地会话。后端由 Spring Security 的退出处理链提供。',apis=['DELETE /logout'],refs=[b('src/main/java/com/phone/framework/config/SecurityConfig.java')]),
 item('修改自己的密码','填写旧密码、新密码和确认密码；前端要求8–20位且含大小写、数字及特殊字符；后端校验旧密码和新旧不同并更新密码缓存。不能据此前端校验认定所有后端入口执行同样强度规则。',apis=['PUT /system/user/profile/updatePwd'],refs=[AUTH+'PasswordDialog.tsx',SYS+'SysProfileController.java']),
 item('密码显示与隐藏','登录、注册、新建用户及改密弹窗使用各自独立的小眼睛；默认隐藏，提交锁定或重开弹窗恢复隐藏，不持久化密码和显示状态。',refs=[f('src/components/ui/password-input.tsx')]),
 item('注册页面与注册接口','前端提供用户名、密码、确认密码、验证码和提交反馈；后端注册接口存在并校验账号唯一及长度。前端必须成功读取验证码，当前后端没有 /captchaImage 映射，所以完整页面注册链路未接通。','部分实现',[AUTH+'RegisterPage.tsx',SYS+'SysRegisterController.java',b('src/main/java/com/phone/framework/web/service/SysRegisterService.java')],['POST /register','GET /captchaImage [后端缺失]']),
 item('登录验证码开关','仅在 VITE_LOGIN_CAPTCHA=true 时显示登录验证码并读取图片/uuid；刷新清除旧验证码。当前后端验证码生成接口缺失，且登录校验调用被注释。','前端保留',[AUTH+'LoginPage.tsx',b('src/main/java/com/phone/framework/web/service/SysLoginService.java')]),
 item('首次登录强制改密兼容','前端能处理 loginNew 和临时令牌，改密完成后重新登录；当前 /login 只返回普通token，没有产生 loginNew 的代码，不能当成已启用流程。','前端保留',[AUTH+'AuthProvider.tsx',AUTH+'PasswordDialog.tsx',SYS+'SysLoginController.java']),
 item('IP白名单申请兼容','登录错误为“用户登录需要申请”时可打开申请确认并调用白名单接口；当前后端没有对应控制器，审批页面也未在当前主菜单实现。','前端保留',[AUTH+'LoginPage.tsx',f('src/api/login.js')],['POST /module/WhitelistIp/ipApplication [后端缺失]']),
 item('角色与操作权限框架','前端按身份返回的 roles/permissions 过滤路由及相关按钮；后端提供方法授权和用户数据范围。当前权限服务给普通用户也返回 *:*:*，动态菜单返回空数组，因此不能描述为已经完成细粒度隔离。','部分实现',[f('src/lib/routes.ts'),b('src/main/java/com/phone/framework/web/service/SysPermissionService.java'),SYS+'SysLoginController.java'])
]),
group('02 统计分析','默认首页 /analytics，分执行洞察、内容资产、资源状态三个维度，使用当前后端聚合接口。',
 [f('src/features/analytics/AnalyticsPage.tsx'),CTL+'AnalyticsController.java',SVC+'AnalyticsService.java'],[
 group('执行洞察','基于 video_tags 当前执行记录统计，并非不可变的历史事件日志。',[f('src/features/analytics/AnalyticsPage.tsx')],[
 item('周期和任务类型切换','提供近7天、30天、90天、全部时间；按全部、账号、位置类型筛选，刷新更新统计时间。',apis=['GET /module/analytics/executions?period=7|30|90|all&kind=all|account|location']),
 item('执行指标与状态构成','展示执行记录总量、已完成、等待与处理中、异常，以及完成状态占比；未知状态单独统计，未知值不补0。'),
 item('执行时间趋势','展示记录总量、完成、异常趋势；按更新时间统计，缺失时回退创建时间。全部时间按月，其余按日，补齐无记录日期。'),
 item('地点与策略分布','按实际地址/附近目标汇总地点，排除综合搜索关键词；按完整标签组合汇总策略，展示榜单及未展示余量。'),
 item('设备执行分布','按设备展示记录量、完成和异常；活跃设备指有记录的设备标识数量，不能解释为当前在线设备。'),
 item('更新日历与累计调度','日历固定近90天，并沿用任务类型；关联任务数量包含历史关联，累计调度次数含失败尝试，不能当成期间新增执行次数。')
 ]),
 group('内容资产','展示可访问来源当前存量，时间筛选只影响首次入库趋势。',[f('src/features/analytics/AnalyticsPage.tsx'),SVC+'AnalyticsService.java'],[
 item('来源视频与评论指标','展示来源视频资产、归档评论记录、采集目标资料、视频关联目标；账号与位置/搜索来源分别保留。',apis=['GET /module/analytics/content']),
 item('视频资产首次入库趋势','按各来源视频UID的首次采集时间计新增，后续快照不重复贡献新增量；按周期显示账号和位置来源。'),
 item('来源构成','展示账号来源与位置/搜索来源视频占比；每个来源内部按UID去重，两来源合计仍可能包含同一作品。'),
 item('两类目标资产排行','展示账号目标排行和位置/搜索目标排行；同一视频关联多个目标时可在各目标各计一次，百分比分母使用目标关联数。'),
 item('评论与目标统计口径','归档评论是入库记录数，不保证历史数据中评论绝对唯一；已识别评论作者按非空UID去重；位置表也含搜索/附近归档标签。'),
 item('文件覆盖指标仅后端保留','后端仍返回视频、截图、评论文件路径覆盖统计；当前页面已移除该展示，路径存在也不表示文件能读取。','仅后端',[SVC+'AnalyticsService.java'])
 ]),
 group('资源状态','当前任务、设备、分组、用户配置快照，不随日期筛选变化。',[f('src/features/analytics/AnalyticsPage.tsx')],[
 item('资源总数与快捷跳转','展示账号任务、位置任务、设备总数、分组数，可跳回对应列表；这里账号/位置数量指主任务数量。',apis=['GET /module/analytics/resources']),
 item('设备状态与型号','展示在线/离线/未知状态构成和型号分布；状态为当前连接标志，不代表在线时长。'),
 item('任务类型与用户状态','展示账号/位置任务构成，以及当前有权访问的正常/停用/未知用户数量；用户统计沿用后端数据范围。'),
 item('分组设备数排行','拆分真实成员标识，关联现存设备并在组内去重；一个设备属于多组时可跨组重复，不能累加成独立设备数。')
 ]),
 item('图表交互、响应式与数据状态','支持悬停、键盘、触屏查看真实详情；图表自适应宽度，趋势图区固定高度。按来源区分读取失败、无权限、缺失、真实0，并支持刷新与减少动画。',refs=[f('src/features/analytics/ChartTooltip.tsx'),f('src/features/analytics/use-server-analytics.ts'),f('src/features/analytics/chart-motion.ts')]),
 item('服务端统一统计快照','三个统计接口使用只读事务读取汇总、趋势及榜单，时间边界按北京时间计算；部分资源来源异常不会伪造成0或抹掉其他健康来源。',refs=[SVC+'AnalyticsService.java',b('src/main/java/com/phone/module/domain/analytics/AnalyticsQuery.java')])
]),
group('03 账号采集任务','主入口 /index，账号采集主任务与执行明细采用两层结构。',[CORE+'tasks.tsx',CTL+'VideoTaskController.java',CTL+'VideoTagsController.java'],[
 item('主任务列表与状态摘要','展示任务名、设备ID、创建时间、采集标签、未完成/已完成/异常条数和备注；任务名或行可打开人员执行结果。',apis=['GET /module/video_task/list?type=1']),
 item('新增批量账号任务','填写任务名、设备、换行分隔抖音ID、采集内容与备注；前端将多行转成逗号分隔，后端按目标生成执行记录。',apis=['POST /module/video_task']),
 item('采集内容选择','可选择视频、基本信息、评论；至少选择一项，设备选项未成功读取时阻止提交。'),
 item('编辑任务与目标同步','修改名称、设备、目标、标签、备注；后端对比新旧目标集合，新增目标入执行表、移除已删目标并更新保留目标。',apis=['PUT /module/video_task']),
 item('删除主任务','确认后删除所选主任务，成功后重查列表；当前服务只删除主任务表，不能宣称相关执行及已采集资产全部级联清理。',apis=['DELETE /module/video_task/{ids}'],refs=[SVC+'impl/VideoTaskServiceImpl.java']),
 item('人员执行结果','展示昵称/头像、抖音ID、实名/年龄/IP、采集时间与标签、备注、关注/粉丝、已采集/总作品、评论及状态。',apis=['GET /module/VideoTags/list?taskId=...']),
 item('执行备注在线编辑','备注可直接编辑并保存，支持Enter提交、Escape取消及请求失败反馈。',apis=['PUT /module/VideoTags']),
 item('执行状态与详情入口','0等待、1处理中、2完成、3异常；账号明细在非0状态提供详情，携带真实设备和抖音ID打开新标签。'),
 item('分页、列设置与刷新','主列表和执行结果按真实total分页；支持10/20/30/50/100条；主列表支持显示/隐藏列、调整顺序和刷新。')
]),
group('04 账号详情','隐藏详情路由 /basic/detailPage?devId=...&douyinId=...，从任务、运行日志或人员关系打开。',[CORE+'details.tsx',CORE+'account-overview.tsx',CTL+'VideoController.java'],[
 item('资料概览与完整简介','展示昵称、头像、抖音ID、实名、年龄/性别、IP、简介、作品、获赞、粉丝、关注等真实字段；长简介可展开，缺失不推测。',apis=['GET /module/video/getAccount']),
 item('主页截图及原图查看','按原始比例完整展示个人主页截图，可打开预览、切换适应窗口与放大滚动查看。'),
 item('上次采集对比','已有 oldString 时比较上次与当前关注、粉丝、获赞、作品，并展示真实差值和上次采集时间；非有效数值显示缺失。'),
 item('IP属地历史','查看当前账号已记录的IP属地与采集时间历史。',apis=['GET /module/ipAddress/list']),
 item('采集操作记录','显示该账号运行日志及最近采集时间，长记录区可独立滚动。',apis=['GET /module/RunLog/list?douyinId=...']),
 item('视频卡片与评论','按页显示多条视频和各自评论，桌面最多双列、窄屏单列；有真实总数和页码、分页加载保留布局。',apis=['GET /module/video/list']),
 item('关注、粉丝与评论人员','按实际已采集关系提供粉丝、关注、账号评论人员及关联人员列表；入口是否可用取决于真实返回的关系状态。',refs=[CORE+'models.ts',CORE+'people.tsx']),
 item('补充采集全部作品','当前资料标签为前六条时显示获取视频入口；确认后投递后续采集任务，区别于只读刷新。',refs=[CORE+'shared.tsx',CORE+'models.ts'],apis=['POST /module/VideoTags']),
 item('刷新、空态与返回顶部','分别重查资料、视频、IP及操作记录；无视频时提供只读刷新；缺少设备/目标参数时给出返回入口；滚动后显示回顶按钮。',refs=[f('src/components/DetailBackTop.tsx')])
]),
group('05 位置采集任务','主入口 /address，支持地址与综合视频两种前端采集模式。',[CORE+'tasks.tsx',CTL+'VideoTaskController.java'],[
 item('位置主任务列表','按type=2读取，展示设备、创建时间、标签、汇总状态和备注；点击进入地址执行明细。',apis=['GET /module/video_task/list?type=2']),
 item('批量新增和编辑位置','任务名、设备、每行一个位置名称、采集内容、视频范围及备注；位置任务表单支持设备名称或编号搜索。',apis=['POST /module/video_task','PUT /module/video_task']),
 item('地址与综合搜索互斥','“地址”与“综合视频”选择一项时取消另一项；同样可选视频、基本信息、评论。当前表单不提供附近/GPS配置入口。'),
 item('发布者视频范围','可选单用户前六条视频或单用户所有视频；后端确认真实发布者身份后创建/升级相应账号采集子任务。',refs=[SVC+'DouyinTaskService.java']),
 item('地址任务执行明细','展示位置名称、采集时间/标签/备注、抓取信息、视频数、评论数和状态；后端原始抓取文字完整保留。',apis=['GET /module/VideoTags/list?taskId=...']),
 item('详情入口始终可点','地址执行明细不按状态隐藏或禁用“查看详情”，真实未采集内容由位置详情空态表示。'),
 item('重新获取和删除执行','确认后将执行status重置0、runNum重置0或删除执行；删执行时后端重算所属主任务剩余目标。',apis=['PUT /module/VideoTags','DELETE /module/VideoTags/{ids}']),
 item('删除位置主任务与分页刷新','确认删除主任务、刷新和列设置复用账号任务公共能力；分页保留真实total，不能把删除主任务解释为删除所有历史资产。')
]),
group('06 位置详情与发布人员','隐藏路由 /address/detailPage，承接位置任务、搜索归档及运行记录。',[CORE+'details.tsx',CTL+'AddressVideoController.java'],[
 item('位置截图与介绍','展示真实位置名称、地区展示图和完整介绍，支持滚动/展开及查看完整原图；介绍中的#话题做纯文本高亮。',apis=['GET /module/video/getAccount']),
 item('地区相关评论','展示位置资料中的评论，含昵称、时间、正文与@/话题高亮；每次增加20条，可查看完整原文及全部已加载计数。'),
 item('按粉丝和关注排序','粉丝数、关注数支持降序/升序/取消三态，切换后回第一页，由后端白名单字段排序。',apis=['GET /module/AddressVideo/list?sortField=fansCount|followCount&sortOrder=desc|asc']),
 item('IP属地搜索筛选','读取实际可用IP选项，搜索并选择属地；支持清除、重置筛选与排序。',apis=['GET /module/AddressVideo/listByIp','GET /module/AddressVideo/list?ipAddress=...']),
 item('人员与视频卡片','展示真实作者身份、IP/实名/简介、关注粉丝等指标和完整主页截图；当前列表按发布者组织，不应把卡片数当全部视频文件数。',refs=[b('src/main/resources/mapper/module/AddressVideoMapper.xml')]),
 item('查看人员或创建采集任务','状态已获取时打开账号详情；状态9时“未获取”打开预填该作者的账号任务表单，可选设备后提交。未知和其他状态不冒充已完成。'),
 item('当前位置视频弹窗','打开当前行视频、评论、点赞/评论/收藏/分享等真实统计，支持评论人员资料及采集入口；宽屏左右布局、窄屏纵向。',refs=[CORE+'media.tsx']),
 item('分页与资料刷新','按真实total切页并选择每页条数；重新读取位置、IP、设备、发布者视频，支持加载失败重试、真实空态与回到顶部。')
]),
group('07 视频、评论与人员关系','账号和位置共用的媒体、人物浮层、关系名单与继续采集链路。',[CORE+'media.tsx',CORE+'people.tsx',CORE+'models.ts',CORE+'shared.tsx'],[
 item('视频原生播放与倍速','使用真实返回的视频地址，保留播放/暂停、进度、音量等原生控制并可调整播放速度；按真实媒体尺寸完整contain展示。'),
 item('视频懒加载和资源状态','可见区域附近才载入，离屏或隐藏暂停；无地址、加载、失败、重试分开显示，不自动同时播放多条视频。'),
 item('作品指标与时间','展示视频标题及发布时间/采集时间、点赞、原视频评论、收藏、分享；平台评论总数与已采集条数分别标注。'),
 item('评论列表与逐步加载','展示已采集评论头像、昵称、IP、时间、正文和回复；每次加载更多只展开当前已返回数据，不代表重新采集平台评论。'),
 item('回复引用、话题与提及','结构化回复保留对象昵称和被引用内容；统一识别#话题和@提及高亮，文字作为React纯文本显示，不增加话题跳转。',refs=[CORE+'comment-text.tsx',CORE+'topic-text.tsx',f('src/lib/display-text.ts')]),
 item('评论者悬浮资料','仅头像/昵称触发，展示ID、地区、简介、关注/粉丝/作品及真实主页截图；可打开人员详情或查看原图，共用一个活动浮层。'),
 item('六类人员关系查询','①账号视频评论人员；②位置视频评论人员；③账号汇总评论人员；④粉丝；⑤关注；⑥已关联采集的评论作者。不同来源使用各自查询条件。',apis=['GET /module/accountContent/list','GET /module/addressAccountContent/list','GET /module/accountContent/listByDid','GET /module/accountContent/listByFans','GET /module/accountContent/listByFollow','GET /module/comment/list']),
 item('人员关系卡片','展示头像、ID、IP、实名、可展开简介、关注/粉丝/获赞/评论及主页截图，支持分页、刷新和打开个人详情。',refs=[CORE+'relation-person-card.tsx']),
 item('从评论者或关系人继续采集','评论者status=0或关系人status=9时可确认投递视频/基本信息/评论任务。请求不等于采集完成；后端按目标复用或创建等待任务并写运行日志。',apis=['POST /module/VideoTags']),
 item('多步请求部分成功提示','先投递任务再更新评论关联标记；任务成功但标记失败时单独提示，避免把该情况当成整体失败再次投递。',apis=['PUT /module/comment'],refs=[CORE+'models.ts']),
 item('截图适应窗口与放大','所有截图预览默认显示整张，显式切换放大后在图片区滚动，可切回适应窗口，关闭控件保持可达。')
]),
group('08 设备列表与设备分组','主入口 /Facility、/device-group，设备和分组是采集任务及策略配置的基础资源。',[DEV+'DevicesPage.tsx',DEV+'DeviceGroupsPage.tsx',CTL+'DeviceController.java',CTL+'DeviceGroupController.java'],[
 group('设备列表','分页读取设备资料及关联分组。',[DEV+'DevicesPage.tsx'],[
 item('设备资料和连接状态','展示设备码、设备名、型号、系统版本、内核、存储使用、分配用户和分组；1为连接、0为未连接，缺失状态显示未知。',apis=['GET /module/device/list']),
 item('选择设备并批量设组','单选/全选当前页设备后选择多个分组，后端更新成员归属、成员数量并记录分组增加/移除活动。',apis=['PUT /module/device/edit']),
 item('修改设备名称和分组','可编辑设备名称及分组，后端按旧名称_ID精确同步分组内引用；表单选项读取失败时禁止提交。'),
 item('设备分配用户存在落库缺口','前端要求选择分配用户并提交users，但当前 DeviceController.edit 未把users写入加载的Device，因此不能确认“修改分配用户”真正持久化。','部分实现',[DEV+'api.ts',CTL+'DeviceController.java']),
 item('设备活动日志','打开所选设备日志，展示操作时间、活动内容，支持加载、空态和错误重试。',apis=['GET /module/DeviceLog/list?deviceId=...']),
 item('分页、列排序与刷新','真实total、每页10/20/30/50/100、列显示和顺序设置；查询刷新不混淆实际连接状态。')
 ]),
 group('设备分组','分组接口返回完整数组，列表使用本地分页。',[DEV+'DeviceGroupsPage.tsx'],[
 item('分组列表','展示分组名、设备数量、创建时间、分配用户、设备ID与备注；数量按接口真实字段显示。',apis=['GET /module/deviceGroup/list']),
 item('新增分组','选择分配用户、设备，填写分组名和备注；用户、设备、分组名必填，后端保存成员字符串及资料。',apis=['POST /module/deviceGroup','GET /module/deviceGroup/listUser']),
 item('编辑分组与选项搜索','回填现有分配，支持多选、搜索、移除已选和清空；保留当前真实值，成功后重查列表。',apis=['PUT /module/deviceGroup']),
 item('分组树供策略分配','按组返回组内设备选项，保留设备与分组组合标识。',apis=['GET /module/deviceGroup/groupAll']),
 item('删除和导出仅后端存在','当前分组页没有删除/导出按钮；后端保留删除、详情及导出映射，不能视为当前页面操作。','仅后端',[CTL+'DeviceGroupController.java'])
 ])
]),
group('09 运行日志与执行记录','主入口 /operationLog 实际读取 VideoTags 执行记录，不是系统用户登录日志。',[EXT+'ProbeRecordsPage.tsx',CTL+'VideoTagsController.java'],[
 item('按抖音ID与状态查询','支持目标ID、等待/处理中/异常/完成状态筛选与重置；跨账号和位置读取执行列表。',apis=['GET /module/VideoTags/list']),
 item('执行资料展示','展示账号/昵称、时间、任务标签、实名/年龄/性别、关注粉丝、已采集/总作品、评论和地区；实名为空使用“未实名”。'),
 item('按标签打开对应详情','地址或综合视频标签打开位置详情，其余打开账号详情；真实设备与目标随URL传递。'),
 item('重新采集与删除','非等待状态提供“重新探测”，将status重置0并将runNum清零；删除前确认，成功后重查真实列表。重新探测本身当前直接提交，不能统一描述为全部写操作都有二次确认。',apis=['PUT /module/VideoTags','DELETE /module/VideoTags/{ids}']),
 item('后台采集运行日志','账号详情读取 /module/RunLog 的启动、采集、新增及中断等事件；该表与执行状态记录、登录审计分别存储。',refs=[CTL+'RunLogController.java',SVC+'DouyinVideoService.java'],apis=['GET /module/RunLog/list']),
 item('分页、真实总数与稳定排序','执行接口按update_time和id倒序稳定排序，保留不同执行记录，不按账号在前端去重。',refs=[b('src/main/resources/mapper/module/VideoTagsMapper.xml')])
]),
group('10 用户管理与个人设置','/system 是主入口，用户管理后端主要操作实际要求admin角色；个人设置通过账户菜单进入，个人中心入口被隐藏。',[EXT+'UsersPage.tsx',EXT+'SettingsPage.tsx',EXT+'AccountPages.tsx',SYS+'SysUserController.java'],[
 item('用户查询和列表','按用户名、昵称查询；展示编号、用户名、昵称、正常/停用及创建时间，分页使用真实total。',apis=['GET /system/user/list']),
 item('新建用户','填写用户名、昵称、初始密码；前端此入口的初始密码校验为5–20位，与修改密码的强度规则不同。',apis=['POST /system/user']),
 item('修改用户昵称','先读匹配用户ID的详情，保留后端真实角色、岗位和可见管理员配置；编辑表单主要修改昵称，不擅自清空缺失授权。',apis=['GET /system/user/{userId}','PUT /system/user']),
 item('启用、停用与删除','按相应权限显示操作，状态变更及删除需要确认；超级管理员行隐藏修改/删除/重置密码操作。',apis=['PUT /system/user/changeStatus','DELETE /system/user/{userIds}']),
 item('重置他人密码','权限允许时输入新密码与确认密码提交重置；共用独立密码显示控件、提交锁定和失败保留草稿。',apis=['PUT /system/user/resetPwd']),
 item('个人安全设置','隐藏路由 /account/settings 中提供修改自己的密码；账户菜单也保留改密与退出。',refs=[EXT+'SettingsPage.tsx',AUTH+'PasswordDialog.tsx']),
 item('个人资料与头像编辑','保留条件组件，可编辑昵称、手机、邮箱、性别，支持头像文件、裁剪/缩放预览和上传；后端白名单复制资料字段，头像限制图片扩展名，更新会话缓存。','条件入口',[EXT+'AccountPages.tsx',SYS+'SysProfileController.java'],['GET /system/user/profile','PUT /system/user/profile','POST /system/user/profile/avatar']),
 item('个人中心隐藏保留','底层资料/标签组件保留，但顶部、侧栏和手机账户菜单不公开个人中心；后端别名也强制隐藏。不能把它算成新增可见入口。','隐藏保留',[EXT+'AccountPages.tsx',f('src/lib/routes.ts')]),
 item('双因子验证页面','保留动态口令启用、密钥展示、验证、重置与状态读取界面；当前后端无tcodesetup/tcodecheck/tcodestatus/resetUserTcode接口，不能完成启用。','前端保留',[EXT+'AccountPages.tsx',f('src/api/system/user.js')]),
 item('用户导入、导出和角色授权接口','后端保留Excel导入、模板导出、导出用户、读取及保存授权角色。当前简化用户页不显示这些按钮；模板/导出还存在前端POST与后端GET差异。','仅后端',[SYS+'SysUserController.java',EXT+'common.tsx',f('src/utils/request.ts')])
]),
group('11 策略模板与设备分配','前端已实现Task/Allocation组件映射，未在默认7个主菜单公开；当前 /getRouters 也不返回扩展菜单。',[DEV+'StrategyPages.tsx',CTL+'TaskTemplateController.java',CTL+'StrategyTemplateController.java'],[
 item('策略模板列表','分页展示策略名称、策略方法scriptPath及描述。',apis=['GET /module/taskTemplate/list']),
 item('新增与修改模板','策略名称、方法、描述为必填，保存成功后刷新，保留失败草稿。',apis=['POST /module/taskTemplate','PUT /module/taskTemplate']),
 item('选择设备执行策略','前端选择设备后显式调用getToTask；后端按设备建立task_log并返回成功，未执行scriptPath、未见真实脚本调用，属于日志登记。','部分实现',[CTL+'TaskTemplateController.java'],['GET /module/taskTemplate/getToTask']),
 item('策略设备配置列表','展示策略名称、方法、描述、设备和设备分组。',apis=['GET /module/template/list']),
 item('按设备及分组分配','读取真实设备/分组树，多选后保存devId、groups和allDevIds，供后端策略配置读取。',apis=['GET /module/deviceGroup/groupAll','PUT /module/template']),
 item('每日策略任务','后端每天零点读取ID=1的策略配置并为关联设备写strategy_log；未见执行脚本动作，不可据日志success称策略已执行成功。','部分实现',[b('src/main/java/com/phone/ScheduledTask.java')]),
 item('模板及策略日志维护接口','后端保留两类模板和task_log、strategy_log的列表、详情、新增、修改、删除、导出；目前没有完整独立日志主页面。','仅后端',[CTL+'TaskLogController.java',CTL+'StrategyLogController.java'])
],status='条件入口'),
group('12 系统管理扩展','保留页面映射与后台管理接口；默认主导航不开放，部分功能还有请求路径/方法缺口。',[EXT+'AdministrationPage.tsx',EXT+'schemas.ts'],[
 group('角色与授权','角色/菜单授权及数据范围组件保留。',[EXT+'CrudPage.tsx',EXT+'RolePages.tsx',SYS+'SysRoleController.java'],[
 item('角色查询、增改删除及状态','按名称、权限字符、状态、日期查询；维护名称、角色标识、排序、状态、备注；支持启停与批量删除。',apis=['GET /system/role/list','POST /system/role','PUT /system/role','PUT /system/role/changeStatus','DELETE /system/role/{roleIds}']),
 item('菜单权限树与数据范围','菜单树使用 /system/menu/roleMenuTreeselect，前后端路径一致。数据范围可选全部/自定义/本部门/下级部门/仅本人；但前端读取 /system/role/deptTree，后端实际提供 /system/dept/roleDeptTreeselect，数据范围部门树无法完整回填。','部分实现',[EXT+'CrudPage.tsx',EXT+'RolePages.tsx',SYS+'SysMenuController.java',SYS+'SysDeptController.java']),
 item('分配和取消角色用户','查看已分配/未分配用户、筛选、多选添加、单个及批量取消；后端对应authUser接口存在，仍需扩展菜单与数据库满足条件。',apis=['GET /system/role/authUser/allocatedList','GET /system/role/authUser/unallocatedList','PUT /system/role/authUser/selectAll','PUT /system/role/authUser/cancel','PUT /system/role/authUser/cancelAll'])
 ],status='条件入口'),
 group('部门、岗位与菜单','组织结构和界面菜单基础维护。',[EXT+'CrudPage.tsx',EXT+'schemas.ts',SYS+'SysDeptController.java',SYS+'SysPostController.java',SYS+'SysMenuController.java'],[
 item('部门树维护','按名称/状态查询，新增子部门、修改负责人/电话/邮箱/排序、删除及展开收起；部门导入按钮存在但当前后端没有部门importData/importTemplate映射。','部分实现'),
 item('岗位维护','按编码/名称/状态筛选；新增、修改、删除岗位，维护排序及备注；导出按钮POST与当前后端GET映射不一致。','部分实现'),
 item('菜单目录和按钮维护','维护上级、目录/菜单/按钮类型、名称、图标、排序、外链、路由、组件、权限字符、缓存、显示及启停；图标可搜索选择。上级选项取完整列表构建树，保存前按菜单类型显示对应字段。',refs=[EXT+'IconPicker.tsx'])
 ],status='条件入口'),
 group('字典、公告与参数','通用配置页面和内容管理。',[EXT+'schemas.ts',EXT+'NoticePages.tsx'],[
 item('字典类型与字典值','维护字典名称/类型、状态及备注，可进入字典值维护标签、键值、排序；支持刷新字典缓存；字典导出按钮同样存在POST/GET差异。','部分实现',[SYS+'SysDictTypeController.java',SYS+'SysDictDataController.java']),
 item('公告编辑与详情','保留标题、富文本内容编辑，以及发布时选择通知/公告类型和状态；富文本内容经DOMPurify清洗。后端支持基础公告CRUD，列表入口仍受缺失的消息收件接口影响。','部分实现',[EXT+'NoticePages.tsx',SYS+'SysNoticeController.java']),
 item('公告收件列表与已读','前端列表使用 /module/message/list，并可标已读；当前后端只有 /system/notice 基础CRUD，没有message收件与已读接口，列表链路未接通。','前端保留',[EXT+'NoticePages.tsx'],['GET /module/message/list [后端缺失]','GET /module/message/read/{id} [后端缺失]']),
 item('系统参数配置','保留参数名称/键/值、是否内置、日期查询、增改删除、导出、缓存刷新界面；当前后端无 /system/config 控制器。','前端保留',[EXT+'schemas.ts',f('src/api/system/config.js')])
 ],status='条件入口'),
 item('导出和导入兼容边界','通用前端下载使用POST并期望二进制；当前角色、岗位、字典、用户导出为GET且常返回AjaxResult。按钮存在不能算完成Excel下载链路。','部分实现',[f('src/utils/request.ts'),EXT+'CrudPage.tsx',SYS+'SysRoleController.java',SYS+'SysDictTypeController.java'])
],status='条件入口'),
group('13 系统监控与开发工具保留','这些React页面和API封装存在，当前单体后端已移除对应监控/代码生成控制器，也未在主菜单公开。',[EXT+'MonitorPages.tsx',EXT+'GeneratorPages.tsx',EXT+'AdministrationPage.tsx'],[
 item('操作审计日志页面','可按条件查询操作日志、展开请求与异常明细；另有对应日志API封装。当前后端没有 /monitor/operlog 映射。','前端保留',[EXT+'MonitorPages.tsx',f('src/api/monitor/operlog.js')]),
 item('登录审计封装与运行页兼容','保留 /monitor/logininfor 查询/清空/解锁封装，但此组件别名在当前路由实际映射到VideoTags运行记录页，不能把 /operationLog 当成登录审计页面。','前端保留',[f('src/api/monitor/logininfor.js'),f('src/components/RoutePage.tsx')]),
 item('在线用户与强制退出','保留会话列表、筛选和确认强退请求；缺 /monitor/online 后端。','前端保留',[EXT+'MonitorPages.tsx']),
 item('服务器监控','保留CPU、内存、服务器、Java虚拟机、磁盘显示界面；缺 /monitor/server 后端。','前端保留',[EXT+'MonitorPages.tsx']),
 item('Redis缓存监控和清理','保留基本信息、命令统计图、缓存分类/键/值查看及按键/分类/全部清理；缺 /monitor/cache 后端。','前端保留',[EXT+'MonitorPages.tsx',EXT+'CacheCharts.tsx']),
 item('通用定时任务管理','保留任务分组/方法/Cron/并发/失败策略、启停、执行一次、详情和增改删导出；缺 /monitor/job 后端。此管理器与现有后端固定采集调度不同。','前端保留',[EXT+'schemas.ts',EXT+'CrudPage.tsx']),
 item('Cron表达式编辑','前端保留秒、分、时、日、月、周、年选择与表达式生成，用于任务表单；仅本地编辑能力不代表后端任务已注册。','前端保留',[EXT+'CronDialog.tsx']),
 item('调度日志','保留任务/分组/状态/日期查询、详情、删除、清空、导出；缺 /monitor/jobLog 后端。','前端保留',[EXT+'MonitorPages.tsx']),
 item('代码生成器','保留生成表列表、导入数据库表、编辑基础/字段/生成配置、同步元数据、预览并复制代码、下载ZIP或服务端生成；缺 /tool/gen 后端。','前端保留',[EXT+'GeneratorPages.tsx']),
 item('代码生成配置细项','维护包路径、模块、业务名、功能名、模板、上级菜单、生成路径，以及树表/主子表字段；字段列表可配置显示、增改、查询、HTML控件、字典等。','前端保留',[EXT+'GeneratorPages.tsx']),
 item('Swagger与Druid嵌入页','保留iframe与新窗口入口；安全放行规则或静态路径残留不等于当前项目部署了Swagger或Druid服务。','前端保留',[EXT+'MonitorPages.tsx',b('README.md')]),
 item('外部表单构建器入口','保留外部网站iframe和新窗口链接；依赖外部服务，本轮未联网验证其可达性，也不是本项目本地表单生成引擎。','前端保留',[EXT+'MonitorPages.tsx'])
],status='前端保留'),
group('14 后端采集引擎与调度','Android / ADB / Appium采集实现，按标签选择账号、地址、综合搜索或附近分支；依赖设备、抖音登录、数据库、Redis与媒体工具。',[b('src/main/java/com/phone/ScheduledTask.java'),SVC+'DouyinVideoService.java',SVC+'DouyinTaskService.java'],[
 group('自动调度与任务生命周期','phone.scheduler-enabled 控制固定采集调度，当前配置默认开启。',[b('src/main/java/com/phone/ScheduledTask.java')],[
 item('每分钟扫描待执行队列','读取status=0执行记录，成功获得设备锁后先写运行状态与次数，再异步交给采集服务并记录启动日志。','后端能力'),
 item('设备租约与所有权保护','按设备Redis原子加锁，5分钟有效、每分钟续租；释放和续租都校验token，丢失租约中断当前工作线程，减少同设备任务重叠。','后端能力',[SVC+'DeviceLeaseService.java']),
 item('异常恢复与重试上限','运行记录对应设备锁消失后重新排队或置异常；异常等待至少5分钟可再排队，累计尝试达到30次保持异常。当前源码已修正“耗尽重试被标完成”的旧逻辑。','后端能力'),
 item('完整性判断与部分保存','保存数达到可确认的页面作品目标才标完成；数量未知或未确认完整置异常。已采集数据保留，便于重试恢复。','后端能力',[SVC+'DouyinVideoService.java']),
 item('长任务让出设备','完成当前批次、评论延后或存在排队任务时返回CrawlBatch，任务回待执行并恢复本轮次数，让出设备后继续，不标成全部完成。','后端能力',[SVC+'DouyinVideoService.java',ADB+'AccountInfoFetcherVoid.java']),
 item('固定周期评论维护','启动30秒后、其后30分钟补齐评论所属作者；每30分钟把原始评论JSON转存为可查询记录，每轮有批次数上限。','后端能力'),
 item('午夜策略日志登记','每天零点按固定策略配置写设备策略日志，当前没有脚本执行动作；与真实采集队列分开理解。','部分实现')
 ],status='后端能力'),
 group('采集分支与身份识别','使用保留抖音登录状态的UiAutomator2/Appium会话。',[SVC+'DouyinVideoService.java',ADB+'DouyinCrawler.java',ADB+'DouyinAddressCrawler.java'],[
 item('账号搜索和主页解析','搜索指定账号，校验当前主页抖音号与任务目标；读取公开可见昵称、性别/年龄、学校、简介、IP、头像、主页截图及关注/粉丝/获赞/作品。','后端能力',[ADB+'AccountInfoFetcher.java']),
 item('公开实名和数字解析','只读取公开页面可见实名，不主动进入认证入口；支持数字单位和新版UI树解析，旧XPath作为兼容。字段未读到不能据默认对象证明真实存在。','后端能力',[ADB+'AccountInfoFetcher.java']),
 item('关注粉丝关系存储','保留关注/粉丝列表抓取辅助方法和关系存储/查询。当前基础资料流程未自动调用两类列表抓取，因此不能保证每次基本信息采集都会补齐关系名单。','部分实现',[ADB+'AccountInfoFetcher.java',SVC+'DouyinTaskService.java']),
 item('地址目标搜索','支持地点名及区域|地点匹配，进入地点页读取图片、介绍、评论与作品，再采集地点视频。','后端能力',[ADB+'DouyinAddressCrawler.java',ADB+'AddressInfoFetcher.java']),
 item('综合视频搜索','按关键词进入综合视频页、滚动统计并逐条采集，归入位置/搜索来源；数量缺失时仍有100等技术兜底，不应宣称已准确枚举平台全部作品。','后端能力',[SVC+'DouyinVideoService.java']),
 item('附近与模拟定位','标签为附近时可配置模拟定位应用、输入地址并选择搜索结果、开始模拟，再进入附近视频流；当前前端任务表单没有附近/定位配置。','仅后端',[SVC+'DouyinVideoService.java',ADB+'PlaceMockLocationHelper.java']),
 item('发布者任务联动','地址/搜索采集识别真实发布者后，按前六条/全部选项创建或升级账号子任务；已有全部任务不降级为六条，缺失资产的历史完成任务可重新排队。','后端能力',[SVC+'DouyinTaskService.java']),
 item('手工异步采集接口','保留GET /module/account/crawl与crawlVideo；按设备与目标提交异步采集并做设备占用检查，当前主页面主要使用任务投递链路。','仅后端',[CTL+'AccountController.java'],['GET /module/account/crawl','GET /module/account/crawlVideo'])
 ],status='后端能力'),
 group('视频、评论与文件归档','采集过程中按条保存资料与资产，失败保留已完成部分。',[ADB+'AccountInfoFetcherVoid.java',ADB+'VideoCommentsFetcher.java',SVC+'CommentArchiveService.java'],[
 item('视频UID识别及续采','识别视频UID、标题、作者和互动数量，检查已有资产避免重复录制；位置/搜索中保留同一作品与不同目标的关联。','后端能力'),
 item('分段录屏与本地文件','ADB录屏生成视频分段、拉取到输出目录并校验本地文件；结合截图、JSON和路径保存记录，录制不是直接下载原平台源视频。','后端能力',[ADB+'AdbVideoRecorder.java',ADB+'AccountInfoFetcherVoid.java']),
 item('FFmpeg合并','分段合并后检查进程退出及文件存在/非空，失败保留分段和日志用于排查，不把无效合并产物视作成功视频。','后端能力',[ADB+'VideoMergeTest.java']),
 item('评论与评论者采集','读取正文、回复对象、时间、昵称、作者UID，按采集逻辑进入评论者主页补充公开资料及截图；保留长任务中断和延后能力。','后端能力',[ADB+'VideoCommentsFetcher.java']),
 item('评论JSON事务转存','锁定并复查原始快照，按用户标识/正文/回复/时间组合减少重复写入；全部成功后标记归档并清空源content，失败回滚保留原JSON。','后端能力',[SVC+'CommentArchiveService.java']),
 item('资料快照与IP记录','保存账号当前JSON、旧JSON/时间及独立IP属地记录，供详情历史比较和IP历史查询；采集身份不匹配阻止写入。','后端能力',[SVC+'DouyinTaskService.java'])
 ],status='后端能力'),
 item('运行依赖与资源访问','服务默认端口8099，前端开发代理统一API_TARGET；输出文件通过/profile映射。采集依赖Android设备、ADB、Appium、FFmpeg、MySQL及Redis；本导图按源码说明，不等于这些外部依赖均已验收。','后端能力',[b('README.md'),b('src/main/resources/application.yml'),b('src/main/java/com/phone/framework/config/ResourcesConfig.java')])
],status='后端能力'),
group('15 通用交互与界面设置','当前前端已使用的导航、列表、反馈、媒体和个性化能力。',[f('src/App.tsx'),f('src/components/business.tsx'),f('src/components/WorkspacePreferences.tsx')],[
 item('顶部与侧边导航','默认顶部按数据概览、设备管理、采集工作台、系统管理分组；可切侧边，手机用顶部展开菜单或抽屉，详情保留父项高亮和面包屑。',refs=[f('src/components/TopNavigation.tsx'),f('src/components/ResponsiveSidebar.tsx')]),
 item('动态菜单与组件白名单','合并固定路由与后端路由，继承父级权限和隐藏状态，只映射本地审查过的组件；未知组件显示无法匹配。当前后端getRouters返回空，默认仍是7个可见主入口。',refs=[f('src/lib/routes.ts'),f('src/components/RoutePage.tsx'),SYS+'SysLoginController.java']),
 item('亮暗主题、主题色及布局','选择亮/暗风格和主题色，流式/固定内容宽度、固定顶栏、侧栏、隐藏页脚、色弱模式等；保存在本地偏好并支持恢复默认和拷贝。'),
 item('多页签与部分页面保留','可开启多页签，关闭当前/其他/左侧/右侧；统计页签保留。设备、分组、账号任务、运行日志页面有访问缓存，退出会清理保护内容。',refs=[f('src/components/WorkspacePages.tsx')]),
 item('列设置与固定密度','多类列表支持列显隐、顺序和恢复默认；统一中等密度，不再提供密度切换，旧偏好迁移；表格边框可按界面设置控制。',refs=[f('src/features/system/TablePreferences.tsx'),DEV+'controls.tsx',CORE+'table-tools.tsx']),
 item('统一分页规则','支持真实total、可点击页码与10/20/30/50/100条；完整数组才本地切片，修改条数回第一页，越界回合法页；未知total不伪造成0。'),
 item('加载、刷新、失败与空态','统一加载标识和骨架，分页保留最近真实内容及高度并阻止旧内容操作；成功、失败、无权限、空列表和缺失值分别呈现。',refs=[f('src/components/loading-state.tsx'),f('src/components/stable-loading-region.tsx')]),
 item('弹窗、抽屉及焦点管理','普通弹窗横向居中、纵向靠上，长内容内部滚动；支持Escape、焦点恢复，内层菜单先关闭；抽屉和人员浮层保留自身定位。'),
 item('提交防重复与草稿保护','提交期间锁定相关字段及改变草稿的入口，危险变更显示确认，失败保留可编辑内容；写请求不通过通用查询重试机制伪装成功。'),
 item('响应式、媒体与回顶','窄屏表格内部横向滚动，账号视频双列转单列，截图保持完整比例；详情回顶按钮显示滚动进度并可键盘操作。',refs=[f('src/components/DetailBackTop.tsx')]),
 item('动效与可访问反馈','内容卡片入视淡入、真实数值CountUp、统计图绘制、弹窗进出与加载反馈；遵守减少动画、隐藏/离屏停止；不以动画展示虚假业务进度。',refs=[f('src/components/react-bits/FadeContent.tsx'),f('src/components/react-bits/motion-policy.ts'),f('src/features/analytics/chart-motion.ts')]),
 item('统一请求与文本处理','同源/api代理、Bearer认证、业务错误和下载错误识别、并发401去重；数字字符实体只在展示副本中安全单次解码，媒体限制安全地址，公告富文本独立清洗。',refs=[f('src/utils/request.ts'),f('src/lib/display-text.ts'),EXT+'NoticePages.tsx'])
]),
group('16 支撑数据接口与维护代码','这些接口/辅助代码是代码存量的一部分，缺少独立前端页面或真实执行器时不扩大业务含义。',[CTL+'DeviceAppController.java',CTL+'DeviceCommandController.java'],[
 item('设备应用资料CRUD','提供设备ID、应用名、包名、版本、状态、安装时间记录的查询、详情、增改删和导出。当前只是资料CRUD，不等于远程安装/卸载应用已接通。','仅后端',[CTL+'DeviceAppController.java',SVC+'impl/DeviceAppServiceImpl.java'],['/module/DeviceApp']),
 item('设备命令资料CRUD','提供command/params/status等命令记录维护；reboot、factory_reset、install_app是模型中的示例文字，当前服务只落库，没有命令消费和实际执行链路。','仅后端',[CTL+'DeviceCommandController.java',SVC+'impl/DeviceCommandServiceImpl.java'],['/module/DeviceCommand']),
 item('账号、IP、视频和评论资料CRUD','账号、IP、账号视频、地址视频、评论、账号评论关系、地址评论关系均保留列表、详情、创建、修改、删除、导出接口；当前UI主要使用读取和部分状态更新。','仅后端',[CTL+'AccountController.java',CTL+'AccountIpController.java',CTL+'VideoController.java',CTL+'AddressVideoController.java',CTL+'CommentController.java',CTL+'AccountContentController.java',CTL+'AddressAccountContentController.java']),
 item('运行/设备/策略/任务日志CRUD','四类日志均有相应后台数据维护端点；前端当前仅直接展示部分设备日志、账号运行日志。','仅后端',[CTL+'RunLogController.java',CTL+'DeviceLogController.java',CTL+'StrategyLogController.java',CTL+'TaskLogController.java']),
 item('Excel导出映射保留','大量业务控制器保留/export方法。各方法返回类型、HTTP动词及前端是否使用并不一致，只记录存在的映射，未实测文件下载不能标成已验收导出。','仅后端',[CTL+'VideoTaskController.java',SYS+'SysUserController.java']),
 item('服务健康检查和根响应','GET /module/status 返回调用成功；根路径返回固定服务提示字符串。健康接口受当前认证规则控制，不等于采集设备、数据库和所有业务功能健康。','仅后端',[b('src/main/java/com/phone/module/StatusController.java'),SYS+'SysIndexController.java']),
 item('目录JSON导入辅助器','AccountFolderReader按ID去重解析账号JSON，成功解析后删除源文件。虽有类级@RequestMapping，但没有方法级映射，不是已可调用的HTTP导入接口。','辅助代码',[CTL+'AccountFolderReader.java']),
 item('显式启用的启动维护','maintenance-enabled默认关闭；启用后清抖音设备锁、安装定位应用并修补统计/IP数据。这是维护程序，不是每次启动默认执行的业务步骤。','辅助代码',[b('src/main/java/com/phone/test.java'),b('src/main/resources/application.yml')]),
 item('外部采集服务客户端','保留获取账号、粉丝、关注、录制视频的外部HTTP客户端及Main示例；未见当前主业务服务调用，不能认定外部采集服务已部署或主链路使用它。','辅助代码',[b('src/main/java/com/phone/module/douyin/DouyinApiClient.java'),b('src/main/java/com/phone/module/douyin/Main.java')]),
 item('文章流转遗留API封装','Home.js保留文章查询、新增编辑删除、上报、撤回、审核、采纳、评价、评分及取消等请求；当前没有对应功能页面和wzinfo/WzTypeTag后端控制器。','前端保留',[f('src/api/module/Home.js')]),
 item('旧统计和管理封装','保留旧城市/统计/汇总、用户登录状态、可见用户/部门树，以及/user、/role、/service、/permission/no-pager、/org/tree、/sub等通用管理封装；多数当前后端未实现，不能从函数名认定业务可用。','前端保留',[f('src/api/system/user.js'),f('src/api/manage.js')])
],status='仅后端'),
]

BOUNDARIES=group('17 使用状态与关键边界','本次依据当前两套源码和接口映射整理，未启动采集、修改业务数据、登录线上服务或重新执行端到端验收。',[
 f('src/lib/routes.ts'),SYS+'SysLoginController.java',b('README.md')],[
 item('默认7个主入口','统计分析、设备列表、设备分组、账号信息、位置信息、用户管理、运行日志。隐藏详情和账户设置另算；条件组件不等于默认公开功能。','说明'),
 item('源码优先于旧验收文档','历史文档记录过旧逻辑，例如重试30次可能置完成；当前ScheduledTask源码已经改成异常。导图描述当前源码，不推断正在运行实例的版本。','说明',[b('src/main/java/com/phone/ScheduledTask.java')]),
 item('条件页面和存量表依赖','角色、菜单、部门、岗位、字典等有前后端代码，但当前后端不下发这些菜单，数据库是否具备所需表也未做本轮实库验证。','说明'),
 item('接口路径/方法不能只凭名字匹配','角色数据范围部门树路径、公告收件箱、导出POST/GET、验证码、OTP和监控工具等已在各节点明确标记缺口；完整接口索引可用于后续修复核对。','说明'),
 item('页面成功反馈与真实完成不同','策略返回成功只是写日志；采集任务已提交表示入队；设备命令/应用记录CRUD不是实际执行/安装；完成状态占比不应被称为可靠采集成功率。','说明'),
 item('权限当前仍有宽泛授权','前端虽做权限校验，当前后端普通用户同样返回通配权限，业务接口方法授权也不均衡；本导图不将其写成已完善的RBAC隔离。','说明',[b('src/main/java/com/phone/framework/web/service/SysPermissionService.java')]),
 item('可见字段不等于全部自动采集','实名、关注/粉丝关系、截图和评论均取决于实际公开页面、历史数据和采集分支；当前抓取器不主动读取非公开实名，不保证每次补齐所有关系。','说明',[ADB+'AccountInfoFetcher.java'])
],status='说明')

