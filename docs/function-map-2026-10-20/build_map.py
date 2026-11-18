from pathlib import Path
import re, json, csv, hashlib, html, xml.etree.ElementTree as ET
from collections import Counter

OUT = Path(__file__).parent
BACK = Path('D:/IdeaProjects/newPhone')
FRONT = Path('C:/Users/test/Desktop/newDyProject')

def read(p):
    return p.read_text(encoding='utf-8-sig')

def strip_comments(s):
    # Keep literals and line positions; commented routes are not implemented routes.
    token = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|`(?:\\.|[^`\\])*`|//[^\n]*|/\*[\s\S]*?\*/')
    return token.sub(lambda m: re.sub(r'[^\n]', ' ', m[0]) if m[0].startswith(('//','/*')) else m[0], s)

def line(s, pos):
    return s.count('\n', 0, pos) + 1

def inventory():
    backend, controllers = [], []
    for p in sorted((BACK/'src/main/java').rglob('*.java')):
        raw = read(p)
        s = strip_comments(raw)
        if not re.search(r'@(RestController|Controller)\b', s):
            continue
        cl = re.search(r'public\s+class\s+(\w+)', s)
        if not cl:
            continue
        bases = re.findall(r'@RequestMapping\s*\(([^)]*)\)', s[:cl.start()])
        base = re.findall(r'"([^"]*)"', bases[-1])[0] if bases else ''
        start = len(backend)
        for m in re.finditer(r'@(Get|Post|Put|Delete|Patch|Request)Mapping\b(?:\s*\(([^)]*)\))?', s[cl.end():]):
            pos = cl.end()+m.start()
            method = re.search(r'public\s+[\w<>\[\], ?]+?\s+(\w+)\s*\(', s[cl.end()+m.end():])
            suffixes = re.findall(r'"([^"]*)"', m[2] or '') or ['']
            for suffix in suffixes:
                route = '/'+'/'.join(v.strip('/') for v in [base, suffix] if v.strip('/'))
                backend.append({'controller':cl[1], 'method':m[1].upper() if m[1]!='Request' else 'ANY', 'path':route,
                    'handler':method[1] if method else '', 'file':p.relative_to(BACK).as_posix(), 'line':line(raw,pos)})
        controllers.append({'name':cl[1], 'base':base, 'file':p.relative_to(BACK).as_posix(), 'endpointCount':len(backend)-start})
    wrappers, direct = [], []
    for p in sorted((FRONT/'src/api').rglob('*.js')):
        raw=read(p); s=strip_comments(raw)
        constants=dict(re.findall(r'(\w+)\s*:\s*["\']([^"\']+)["\']', s))
        funcs=list(re.finditer(r'export\s+(?:(?:async\s+)?function\s+|const\s+)(\w+)\s*(?:=\s*)?\(', s))
        for i,m in enumerate(funcs):
            block=s[m.end():funcs[i+1].start() if i+1<len(funcs) else len(s)]
            urls=re.findall(r'url:\s*([^\n,]+)',block)
            methods=re.findall(r'method:\s*["\'](\w+)',block)
            resolved=[]
            for u in urls:
                literal=re.match(r'["\']([^"\']+)["\']',u)
                if literal: resolved.append(literal[1]+('…' if '+' in u else ''))
                elif '.' in u: resolved.append(constants.get(u.strip().split('.')[-1],u.strip()))
                else: resolved.append(u.strip())
            wrappers.append({'function':m[1], 'methods':' / '.join(sorted(set(methods))) or 'default', 'paths':' / '.join(dict.fromkeys(resolved)),
                'file':p.relative_to(FRONT).as_posix(), 'line':line(raw,m.start())})
    for p in sorted((FRONT/'src').rglob('*')):
        if p.suffix not in ('.ts','.tsx') or '.test.' in p.name or '__tests__' in p.parts:
            continue
        raw=read(p); s=strip_comments(raw)
        for m in re.finditer(r'(?:url:\s*|useApi\(\s*|base:\s*)["\'](/[^"\']*)["\']',s):
            direct.append({'pathPrefix':m[1], 'file':p.relative_to(FRONT).as_posix(), 'line':line(raw,m.start())})
    return {'controllers':controllers,'backend':backend,'wrappers':wrappers,'direct':direct}

INV=inventory()

CONTROLLER_LABELS = {
 'AccountContentController':'账号评论及关注粉丝关系','AccountController':'账号资料与手动采集',
 'AccountIpController':'IP属地历史','AddressAccountContentController':'位置评论人员关系',
 'AddressVideoController':'位置及搜索视频','AnalyticsController':'统计分析','CommentController':'评论记录',
 'DeviceAppController':'设备应用资料','DeviceCommandController':'设备命令资料','DeviceController':'设备资料',
 'DeviceGroupController':'设备分组','DeviceLogController':'设备日志','RunLogController':'采集运行日志',
 'StrategyLogController':'策略日志','StrategyTemplateController':'策略分配模板','TaskLogController':'任务日志',
 'TaskTemplateController':'策略任务模板','VideoController':'账号视频与详情','VideoTagsController':'采集执行记录',
 'VideoTaskController':'采集主任务','StatusController':'服务状态','SysDeptController':'部门',
 'SysDictDataController':'字典数据','SysDictTypeController':'字典类型','SysIndexController':'根路径提示',
 'SysLoginController':'登录身份与路由','SysMenuController':'菜单','SysNoticeController':'公告',
 'SysPostController':'岗位','SysProfileController':'个人资料','SysRegisterController':'注册',
 'SysRoleController':'角色与分配用户','SysUserController':'用户管理'
}

HANDLERS={
 'list':'查询列表','export':'导出映射','getInfo':'读取详情','add':'新增','edit':'修改','remove':'删除',
 'listByFollow':'读取关注人员','listByFans':'读取粉丝人员','listByDid':'读取账号评论人员汇总',
 'listByIp':'读取属地选项','getAccount':'读取账号或位置资料','listUser':'读取用户选项',
 'groupAll':'读取分组设备选项','getToTask':'登记模板设备任务日志',
 'executions':'执行统计','resources':'资源统计','content':'内容统计','crawlAccount':'异步采集账号',
 'crawlVideo':'异步采集视频','login':'账号登录','getRouters':'读取菜单路由','register':'注册用户',
 'getStatus':'读取服务状态','index':'服务根响应','profile':'读取本人资料','updateProfile':'保存本人资料',
 'updatePwd':'修改本人密码','avatar':'上传本人头像','excludeChild':'读取排除子树部门',
 'treeselect':'读取树选项','roleDeptTreeselect':'读取角色部门树','roleMenuTreeselect':'读取角色菜单树',
 'refreshCache':'刷新字典缓存','optionselect':'读取选择选项','dataScope':'保存数据范围','changeStatus':'修改状态',
 'allocatedList':'查询已分配用户','unallocatedList':'查询待分配用户','cancelAuthUser':'取消单个授权',
 'cancelAuthUserAll':'批量取消授权','selectAuthUserAll':'批量分配用户','importData':'导入用户',
 'importTemplate':'获取导入模板','resetPwd':'重置密码','authRole':'读取用户角色','insertAuthRole':'保存用户角色'
}

def make_tree():
    from map_content import MODULES, BOUNDARIES, group, item
    root=group('手机监测平台 · 完整功能思维导图',
        '依据2026-10-20当前前后端源码整理。前端：'+str(FRONT)+'；后端：'+str(BACK)+
        '。主入口、隐藏/条件入口、仅后端、部分实现和前端保留分别标注；本轮未执行真实业务写入或线上端到端验收。',
        ['F:src/lib/routes.ts','B:README.md'], MODULES+[BOUNDARIES],status='源码梳理')
    index=group('18 后端全量接口索引','按当前有效方法映射扫描，排除注释与无方法映射的AccountFolderReader。Spring Security退出链另列在安全模块；静态资源映射不计为控制器接口。',[],[],status='接口索引')
    for c in INV['controllers']:
        endpoints=[e for e in INV['backend'] if e['controller']==c['name']]
        if not endpoints: continue
        label=CONTROLLER_LABELS[c['name']]
        n=group(label+' · '+str(len(endpoints))+'项',c['name']+'，基础路径：'+(c['base'] or '根路径')+
            '。此分支登记代码中的方法映射，页面可达和业务完成状态以对应功能模块说明为准。', ['B:'+c['file']],[],status='接口索引')
        for e in endpoints:
            title=e['method']+' '+e['path']
            action=HANDLERS.get(e['handler'],e['handler'])
            n['children'].append(item(title,label+'：'+action+'。处理方法：'+e['handler']+
                '。存在映射不表示已经进行线上验收；导出接口也不保证下载响应契约一致。',status='接口索引',refs=['B:'+e['file']+'#L'+str(e['line'])]))
        index['children'].append(n)
    root['children'].append(index)
    sources={}
    def resolve(ref):
        role,rel=ref.split(':',1)
        name,_,at=rel.partition('#L')
        p=(FRONT if role=='F' else BACK)/name
        if not p.is_file(): raise ValueError('Missing evidence: '+ref)
        raw=read(p)
        ln=int(at) if at else 1
        if ln<1 or ln>len(raw.splitlines()): raise ValueError('Invalid evidence line: '+ref)
        key=role+':'+name
        sources[key]={'repository':'前端' if role=='F' else '后端','file':name,'path':str(p),
            'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'lines':len(raw.splitlines())}
        return {'repository':'前端' if role=='F' else '后端','file':name,'path':str(p),'line':ln}
    nodes=[]
    def visit(n,parent=None,status=None,refs=None):
        n['status']=n['status'] or status
        n['id']='n'+str(len(nodes))
        n['parent']=parent['id'] if parent else None
        # Feature notes inherit the nearest explicit source list for traceability.
        local=n['refs'] or refs or []
        n['evidence']=[resolve(ref) for ref in dict.fromkeys(local)]
        n.pop('refs',None)
        nodes.append(n)
        for child in n['children']: visit(child,n,n['status'],local)
    visit(root)
    business=[]
    def collect(n):
        business.append(n)
        for ch in n['children']: collect(ch)
    for n in root['children'][:16]: collect(n)
    stats={'functionalModules':16,'businessNodes':len(business),'featureLeaves':sum(not n['children'] for n in business),
        'allNodes':len(nodes),'mappedControllers':sum(c['endpointCount']>0 for c in INV['controllers']),
        'backendEndpoints':len(INV['backend']),'apiFiles':len(set(v['file'] for v in INV['wrappers'])),
        'wrapperDeclarations':len(INV['wrappers']),'sourceFiles':len(sources)}
    return root,nodes,sources,stats

def note(n):
    bits=['状态：'+n['status'],n['description']]
    if n['apis']: bits+=['关联接口：']+n['apis']
    bits+=['源码依据：']+[e['repository']+' '+e['file']+':'+str(e['line']) for e in n['evidence']]
    return '\n'.join(bits)

def exports(root,nodes,sources,stats):
    data={'title':root['title'],'asOf':'2026-10-20','scope':'当前源码静态梳理；未进行线上功能验收',
        'repositories':{'frontend':str(FRONT),'backend':str(BACK)},'stats':stats,'root':root}
    (OUT/'function-map.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
    (OUT/'source-evidence.json').write_text(json.dumps(list(sources.values()),ensure_ascii=False,indent=2),encoding='utf-8')
    mm=ET.Element('map',{'version':'1.0.1'})
    def add_mm(parent,n,depth=0,side=None):
        suffix='' if n['status'] in ('主入口','源码梳理','接口索引','后端能力') else '【'+n['status']+'】'
        attrs={'TEXT':n['title']+suffix,'ID':n['id']}
        if depth==1: attrs.update(POSITION=side,FOLDED='true')
        el=ET.SubElement(parent,'node',attrs)
        rich=ET.SubElement(el,'richcontent',{'TYPE':'NOTE'})
        body=ET.SubElement(ET.SubElement(rich,'html'),'body')
        for paragraph in note(n).splitlines(): ET.SubElement(body,'p').text=paragraph
        for i,ch in enumerate(n['children']): add_mm(el,ch,depth+1,'left' if depth==0 and i<9 else 'right' if depth==0 else side)
    add_mm(mm,root)
    ET.ElementTree(mm).write(OUT/'手机监测平台-完整功能思维导图.mm',encoding='utf-8',xml_declaration=True)
    opml=ET.Element('opml',{'version':'2.0'})
    head=ET.SubElement(opml,'head'); ET.SubElement(head,'title').text=root['title']
    def add_opml(parent,n):
        el=ET.SubElement(parent,'outline',{'text':n['title'],'_note':note(n),'status':n['status']})
        for ch in n['children']: add_opml(el,ch)
    add_opml(ET.SubElement(opml,'body'),root)
    ET.ElementTree(opml).write(OUT/'手机监测平台-功能大纲.opml',encoding='utf-8',xml_declaration=True)
    lines=[root['title'],'整理日期：2026-10-20','前端：'+str(FRONT),'后端：'+str(BACK),
        json.dumps(stats,ensure_ascii=False),'',
        '状态：主入口=默认菜单/其详情已见调用代码；条件入口=保留映射但默认菜单未公开；',
        '隐藏保留=入口有意隐藏；仅后端/后端能力=有后台代码；前端保留=缺当前后端支撑；',
        '部分实现=已发现明确缺口；辅助代码=工具/维护/示例；所有状态均不是线上验收结论。','']
    def add_text(n,depth=0):
        lines.extend(['  '*depth+n['title']+' ['+n['status']+']','  '*(depth+1)+n['description']])
        if n['apis']: lines.extend('  '*(depth+1)+a for a in n['apis'])
        lines.extend('  '*(depth+1)+'依据：'+e['repository']+'/'+e['file']+':'+str(e['line']) for e in n['evidence'])
        lines.append('')
        for ch in n['children']: add_text(ch,depth+1)
    add_text(root)
    lines+=['前端API封装全量清单（含未使用兼容封装）','']
    lines.extend(v['file']+':'+str(v['line'])+' '+v['function']+' | '+v['methods']+' | '+v['paths'] for v in INV['wrappers'])
    (OUT/'完整功能说明.txt').write_text('\n'.join(lines),encoding='utf-8-sig')
    payload=json.dumps(data,ensure_ascii=False,separators=(',',':')).replace('</','<\\/')
    inv=json.dumps(INV,ensure_ascii=False,separators=(',',':')).replace('</','<\\/')
    template=read(OUT/'viewer-template.html')
    (OUT/'完整功能思维导图.html').write_text(template.replace('__MAP_DATA__',payload).replace('__INVENTORY__',inv),encoding='utf-8')
    (OUT/'阅读说明.txt').write_text('手机监测平台：完整功能思维导图\n\n'
        '1. 双击“完整功能思维导图.html”，离线浏览导图、功能说明和接口索引。\n'
        '2. 点击节点展开/收起；选中节点可读说明、状态和源码依据。支持搜索、缩放、拖动画布。\n'
        '3. “手机监测平台-完整功能思维导图.mm”为FreeMind格式，可用于支持该格式的导图软件导入；功能说明写在节点备注。未在具体XMind版本执行导入验收。\n'
        '4. OPML可导入支持大纲格式的软件；TXT为完整可检索说明，JSON为同源结构化数据。\n'
        '5. backend-index.csv为有效后端方法映射，wrappers-index.csv为前端API导出声明；重复路径保留其各自函数/处理方法，不把声明数当功能数。\n'
        '6. 所有结论依据2026-10-20当前源码；没有提交真实任务、写业务数据或验证线上服务版本。\n',encoding='utf-8-sig')
    return data

if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT/'source-inventory.json').write_text(json.dumps(INV,ensure_ascii=False,indent=2),encoding='utf-8')
    for key in ['backend','wrappers','direct']:
        with (OUT/(key+'-index.csv')).open('w',encoding='utf-8-sig',newline='') as f:
            writer=csv.DictWriter(f,fieldnames=list(INV[key][0])); writer.writeheader(); writer.writerows(INV[key])
    tree,nodes,sources,stats=make_tree()
    exports(tree,nodes,sources,stats)
    print(json.dumps(stats,ensure_ascii=False))
