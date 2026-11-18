"""Derive the phone/Douyin acquisition map from the verified full source inventory."""
from pathlib import Path
from copy import deepcopy
import json, re, hashlib, xml.etree.ElementTree as ET, zipfile

OUT=Path(__file__).parent
BASE=OUT.parent/'function-map-2026-10-20'
original=json.loads((BASE/'function-map.json').read_text(encoding='utf-8'))
by_title={}
def index(n):
    by_title.setdefault(n['title'],[]).append(n)
    for c in n['children']: index(c)
index(original['root'])

def take(title, rename=None, description=None):
    found=by_title.get(title,[])
    assert len(found)==1,(title,len(found))
    node=deepcopy(found[0])
    if rename: node['title']=rename
    if description: node['description']=description
    return node

def group(title,description,children,status='采集相关'):
    refs={}
    for ch in children:
        for e in ch['evidence']: refs[e['repository']+e['file']]=e
    return {'title':title,'description':description,'status':status,'children':children,
        'evidence':list(refs.values())[:4],'apis':[]}

modules=[
group('01 采集任务配置与启动','为指定手机配置抖音账号、地点或搜索目标，选择需要获取的内容，提交后交由采集队列执行。',[
    take('新增批量账号任务','批量配置账号目标'),
    take('采集内容选择','选择视频、基本信息与评论'),
    take('批量新增和编辑位置','批量配置位置或搜索目标'),
    take('地址与综合搜索互斥','选择地址或综合视频模式'),
    take('发布者视频范围','选择发布者前六条或全部视频'),
    take('编辑任务与目标同步','修改设备、目标和采集内容'),
    take('手工异步采集接口','直接异步获取账号或视频'),
    take('删除主任务','删除采集主任务'),
]),
group('02 账号基础资料获取','通过手机抖音搜索并进入真实账号主页，读取公开信息，保存当前资料与历史快照。',[
    take('账号搜索和主页解析','搜索账号并核验抖音号'),
    take('公开实名和数字解析','解析公开实名与数量单位'),
    take('资料概览与完整简介','获取昵称、简介、地区及账号指标'),
    take('主页截图及原图查看','获取和查看主页截图'),
    take('资料快照与IP记录','保存资料快照与IP属地'),
    take('上次采集对比','对比上次与当前采集指标'),
    take('IP属地历史','查询历史IP属地'),
]),
group('03 视频录制与文件获取','采集账号作品、地点视频和搜索结果，按作品识别、录屏、合并、保存，并支持继续获取剩余作品。',[
    take('视频UID识别及续采','识别视频并避免重复录制'),
    take('分段录屏与本地文件','手机分段录屏与本地保存'),
    take('FFmpeg合并','合并视频分段并校验产物'),
    take('补充采集全部作品','前六条之后继续获取作品'),
    take('视频原生播放与倍速','查看已获取视频与倍速播放'),
    take('视频懒加载和资源状态','视频缺失、失败及重新载入'),
    take('作品指标与时间','查看作品时间和互动指标'),
]),
group('04 评论与评论者资料获取','获取视频评论、回复及评论者资料，保存可查询记录，并区分平台评论总数和实际已采集数量。',[
    take('评论与评论者采集','获取正文、回复和评论者主页资料'),
    take('评论JSON事务转存','评论去重转存与失败回滚'),
    take('固定周期评论维护','定期补齐作者与归档评论'),
    take('评论列表与逐步加载','查看评论并逐步展开已采集内容'),
    take('回复引用、话题与提及','展示回复引用、话题与提及'),
    take('评论者悬浮资料','查看评论者资料和主页截图'),
]),
group('05 地址、综合搜索与附近获取','按地点、关键词或附近分支采集；地址/搜索结果还可联动获取发布者的账号作品。',[
    take('地址目标搜索','搜索地点并获取地点资料'),
    take('位置截图与介绍','查看地点图片与完整介绍'),
    take('地区相关评论','查看地点相关评论'),
    take('综合视频搜索','按关键词获取综合视频'),
    take('附近与模拟定位','模拟定位并获取附近视频'),
    take('发布者任务联动','识别发布者并追加账号采集'),
    take('人员与视频卡片','查看位置视频的发布人员'),
    take('按粉丝和关注排序','按发布者粉丝或关注排序'),
    take('IP属地搜索筛选','按发布者IP属地筛选结果'),
    take('当前位置视频弹窗','查看当前地点视频和评论'),
]),
group('06 关联人员与继续获取','以评论者、粉丝、关注和位置发布者为线索，查看已保存的关系资料，再投递新的账号视频获取任务。',[
    take('六类人员关系查询','六类关联人员查询'),
    take('人员关系卡片','查看关联人员资料与截图'),
    take('关注粉丝关系存储','关注与粉丝列表获取能力边界'),
    take('从评论者或关系人继续采集','从评论者或关系人继续获取'),
    take('多步请求部分成功提示','区分任务投递与关联标记成功'),
    take('查看人员或创建采集任务','从位置发布者创建账号获取任务'),
]),
group('07 手机调度、断点与异常恢复','选择采集手机并串行使用设备；调度器负责入队、租约、重试、部分保存和长任务让出设备。',[
    take('每分钟扫描待执行队列','扫描等待队列并异步启动'),
    take('设备租约与所有权保护','同手机互斥与锁续期'),
    take('异常恢复与重试上限','异常恢复、重试等待和次数上限'),
    take('完整性判断与部分保存','判断完整性并保留部分数据'),
    take('长任务让出设备','让出手机并继续未完成采集'),
    take('重新获取和删除执行','重置执行并重新获取'),
    take('运行依赖与资源访问','手机采集运行前提',
        '需要已连接的Android手机、可用的抖音登录状态、ADB、Appium、FFmpeg及MySQL/Redis。设备选择来自真实设备列表；采集文件通过后端资源路径访问。本图只整理代码中的采集能力，未执行真实手机采集验收。'),
],status='后端能力'),
group('08 采集结果、进度与运行记录','查看获取任务的真实状态、账号/地点结果、已保存视频评论和运行日志，并按需刷新或重新获取。',[
    take('主任务列表与状态摘要','查看采集任务与状态汇总'),
    take('人员执行结果','查看账号获取结果'),
    take('地址任务执行明细','查看地址获取结果'),
    take('详情入口始终可点','未完成的地址结果也可查看'),
    take('执行备注在线编辑','编辑单条执行备注'),
    take('视频卡片与评论','按页查看账号视频及评论'),
    take('执行状态与详情入口','识别等待、运行、完成和异常状态'),
    take('按抖音ID与状态查询','按目标和采集状态查记录'),
    take('按标签打开对应详情','进入账号或位置结果详情'),
    take('后台采集运行日志','查看启动、采集、中断和新增日志'),
    take('重新采集与删除','重新排队或删除执行记录'),
    take('截图适应窗口与放大','查看完整截图和放大细节'),
    take('分页与资料刷新','分页、筛选与刷新获取结果'),
]),
]

root=group('手机抖音数据获取','手机抖音获取专版：配置目标 → 手机执行 → 获取公开资料、视频与评论 → 保存查看 → 关联人员继续获取。按当前前后端源码筛选采集链路；各节点保留已有功能说明和实现状态。',modules,status='获取功能专版')
all_nodes=[]
def number(n,parent=None):
    n['id']='dy'+str(len(all_nodes)); n['parent']=parent
    all_nodes.append(n)
    for c in n['children']: number(c,n['id'])
number(root)
assert len({n['title'] for n in all_nodes})==len(all_nodes),'Duplicate functional nodes'

evidence_keys={(e['repository'],e['file']) for n in all_nodes for e in n['evidence']}
sources=[s for s in json.loads((BASE/'source-evidence.json').read_text(encoding='utf-8')) if (s['repository'],s['file']) in evidence_keys]
for source in sources:
    assert hashlib.sha256(Path(source['path']).read_bytes()).hexdigest()==source['sha256'],source['path']
for n in all_nodes:
    assert n['description'] and n['evidence']
    for e in n['evidence']: assert Path(e['path']).is_file()

stats={'functionalModules':len(modules),'featureLeaves':sum(not n['children'] for n in all_nodes),'allNodes':len(all_nodes),'sourceFiles':len(sources)}
data={'title':root['title'],'asOf':'2026-10-20','scope':'只包含手机抖音数据获取链路，保留源码实现状态，未进行真实手机采集验收。',
    'repositories':original['repositories'],'stats':stats,'root':root}
OUT.mkdir(parents=True,exist_ok=True)
(OUT/'function-map.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
(OUT/'source-evidence.json').write_text(json.dumps(sources,ensure_ascii=False,indent=2),encoding='utf-8')

def note(n):
    return '\n'.join(['实现状态：'+n['status'],n['description']]+(['关联接口：']+n['apis'] if n['apis'] else [])+
        ['源码依据：']+[e['repository']+' '+e['file']+':'+str(e['line']) for e in n['evidence']])

mm=ET.Element('map',{'version':'1.0.1'})
def add_mm(parent,n,level=0,side=None):
    status='' if n['status'] in ('主入口','采集相关','后端能力','获取功能专版') else '【'+n['status']+'】'
    attrs={'TEXT':n['title']+status,'ID':n['id']}
    if level==1: attrs.update(POSITION=side,FOLDED='true')
    el=ET.SubElement(parent,'node',attrs)
    body=ET.SubElement(ET.SubElement(ET.SubElement(el,'richcontent',{'TYPE':'NOTE'}),'html'),'body')
    for text in note(n).splitlines(): ET.SubElement(body,'p').text=text
    for i,ch in enumerate(n['children']): add_mm(el,ch,level+1,('left' if i<4 else 'right') if level==0 else side)
add_mm(mm,root)
ET.ElementTree(mm).write(OUT/'手机抖音获取功能思维导图.mm',encoding='utf-8',xml_declaration=True)
opml=ET.Element('opml',{'version':'2.0'})
ET.SubElement(ET.SubElement(opml,'head'),'title').text=root['title']
def add_outline(parent,n):
    el=ET.SubElement(parent,'outline',{'text':n['title'],'_note':note(n),'status':n['status']})
    for ch in n['children']: add_outline(el,ch)
add_outline(ET.SubElement(opml,'body'),root)
ET.ElementTree(opml).write(OUT/'手机抖音获取功能大纲.opml',encoding='utf-8',xml_declaration=True)

text=['手机抖音数据获取 · 功能说明','依据当前前后端源码筛选，整理日期2026-10-20。',
    '范围：采集任务、账号公开资料、视频、评论、地点/搜索/附近、关联人员、手机调度及采集结果。',
    '状态为源码实现情况：主入口/详情、后端能力、仅后端、部分实现等；本轮未执行真实手机采集。','']
def outline(n,depth=0):
    text.extend(['  '*depth+n['title']+' ['+n['status']+']','  '*(depth+1)+n['description']])
    text.extend('  '*(depth+1)+a for a in n['apis'])
    text.extend('  '*(depth+1)+'依据：'+e['repository']+'/'+e['file']+':'+str(e['line']) for e in n['evidence'])
    text.append('')
    for ch in n['children']: outline(ch,depth+1)
outline(root)
(OUT/'手机抖音获取功能说明.txt').write_text('\n'.join(text),encoding='utf-8-sig')

viewer=(BASE/'viewer-template.html').read_text(encoding='utf-8')
viewer=viewer.replace('<button type="button" role="tab" id="tab-api" aria-controls="api-panel" aria-selected="false" data-view="api">接口索引</button>','')
viewer=re.sub(r'<section id="api-panel"[\s\S]*?</section>\s*','',viewer,count=1)
viewer=re.sub(r'<script id="inventory"[\s\S]*?</script>\s*','',viewer,count=1)
viewer=viewer.replace(', INV=JSON.parse(document.getElementById(\'inventory\').textContent)','')
viewer=viewer.replace("['map','report','api']","['map','report']")
viewer=viewer.replace("if(view==='api')renderAPI();",'')
viewer=viewer.replace("if(mode==='api')renderAPI()",'')
viewer=re.sub(r'function renderAPI\(\)[\s\S]*?\$\(\'api-kind\'\)\.onchange=renderAPI;\s*','',viewer,count=1)
viewer=viewer.replace('renderReport();renderAPI();','renderReport();')
viewer=viewer.replace("$('stats').textContent=DATA.asOf+' 源码快照 · '+DATA.stats.functionalModules+' 个功能模块 · '+DATA.stats.featureLeaves+' 项功能说明 · '+DATA.stats.backendEndpoints+' 个后端方法映射';",
    "$('stats').textContent=DATA.asOf+' 源码整理 · '+DATA.stats.functionalModules+' 个获取模块 · '+DATA.stats.featureLeaves+' 项相关功能';")
viewer=re.sub(r"\$\('footer'\)\.textContent=[^\n]+;",
    "$('footer').textContent='手机抖音获取专版 · 每个节点附功能说明与源码依据。部分实现、仅后端入口及采集范围限制已在对应节点标注；本轮未执行真实手机采集。';",viewer,count=1)
viewer=viewer.replace('手机监测平台 · 完整功能思维导图','手机抖音数据获取 · 功能思维导图').replace('PHONE MONITOR / FUNCTION ATLAS','DOUYIN / MOBILE ACQUISITION')
viewer=viewer.replace('手机监测平台-完整功能思维导图.mm','手机抖音获取功能思维导图.mm').replace('完整功能说明.txt','手机抖音获取功能说明.txt').replace('手机监测平台-功能大纲.opml','手机抖音获取功能大纲.opml')
viewer=viewer.replace('搜索功能说明和接口','搜索抖音获取功能').replace('搜索功能、状态或接口路径','搜索账号、视频、评论或地点获取')
viewer=viewer.replace('依据当前前后端源码整理。点击节点展开并查看说明；“条件入口”“前端保留”和“部分实现”已分别注明。所有状态均表示代码情况，本轮未提交采集任务或执行线上验收。',
    '按手机抖音获取链路展开：配置目标 → 手机执行 → 资料 / 视频 / 评论 → 保存与继续获取。点击节点查看功能说明及实现状态。')
viewer=viewer.replace('<span class="badge">条件 / 隐藏入口</span>','').replace('<span class="badge" data-kind="warn">部分实现 / 前端保留</span>','<span class="badge" data-kind="warn">部分实现</span>')
viewer=viewer.replace('手机监测平台完整功能树','手机抖音数据获取功能树').replace("手机监测平台 · '+DATA.stats.featureLeaves+' 项功能说明","手机抖音数据获取 · '+DATA.stats.featureLeaves+' 项功能说明")
viewer=viewer.replace('手机监测平台-当前功能导图.svg','手机抖音获取功能导图.svg')
viewer=viewer.replace('完整功能思维导图，点击节点展开','手机抖音获取思维导图，点击节点展开')
payload=json.dumps(data,ensure_ascii=False,separators=(',',':')).replace('</','<\\/')
viewer=viewer.replace('__MAP_DATA__',payload)
assert '__INVENTORY__' not in viewer and '__MAP_DATA__' not in viewer
assert 'function renderAPI' not in viewer and 'INV.' not in viewer
(OUT/'手机抖音获取功能思维导图.html').write_text(viewer,encoding='utf-8')
js=re.findall(r'<script>\s*([\s\S]*?)</script>',viewer)[0]
(OUT/'viewer-check.js').write_text(js,encoding='utf-8')

(OUT/'阅读说明.txt').write_text('手机抖音获取功能专版\n\n'
    '双击HTML查看交互导图；支持展开、搜索、缩放和完整说明。窄屏自动显示可展开分支树。\n'
    'MM是FreeMind导图格式，功能说明在节点备注；另附OPML、TXT及JSON。\n'
    '本版仅包含手机抖音获取相关功能，按上一版已经核对的源码筛选，并再次校验引用文件SHA256未变化。\n'
    '“部分实现”保留实际限制：例如关注/粉丝名单抓取并非每次基础资料获取都会自动执行；附近配置目前仅后端存在。\n'
    '未执行真实采集任务，也未在具体XMind版本验证导入。\n',encoding='utf-8-sig')

assert len(ET.parse(OUT/'手机抖音获取功能思维导图.mm').findall('.//node'))==len(all_nodes)
assert len(ET.parse(OUT/'手机抖音获取功能大纲.opml').findall('.//outline'))==len(all_nodes)
with zipfile.ZipFile(OUT/'手机抖音获取功能导图资料包.zip','w',zipfile.ZIP_DEFLATED) as z:
    for name in ['手机抖音获取功能思维导图.html','手机抖音获取功能思维导图.mm','手机抖音获取功能大纲.opml','手机抖音获取功能说明.txt','function-map.json','source-evidence.json','阅读说明.txt']:
        z.write(OUT/name,name)
with zipfile.ZipFile(OUT/'手机抖音获取功能导图资料包.zip') as z: assert z.testzip() is None
print(json.dumps({'stats':stats,'verifiedSourceHashes':len(sources),'packageFiles':7},ensure_ascii=False))
