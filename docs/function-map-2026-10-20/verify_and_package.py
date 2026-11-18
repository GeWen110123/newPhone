from pathlib import Path
import json, hashlib, xml.etree.ElementTree as ET, zipfile, re

p=Path(__file__).parent
data=json.loads((p/'function-map.json').read_text(encoding='utf-8'))
inv=json.loads((p/'source-inventory.json').read_text(encoding='utf-8'))
sources=json.loads((p/'source-evidence.json').read_text(encoding='utf-8'))
seen=[]
def walk(n):
    assert n['description'] and n['status'], n['title']
    seen.append(n['id'])
    for e in n['evidence']:
        assert Path(e['path']).is_file(), e
    for ch in n['children']: walk(ch)
walk(data['root'])
assert len(seen)==len(set(seen))==data['stats']['allNodes']
assert len(inv['backend'])==data['stats']['backendEndpoints']
assert len({(e['method'],e['path']) for e in inv['backend']})==len(inv['backend'])
assert len(inv['wrappers'])==data['stats']['wrapperDeclarations']
assert len({r['file'] for r in inv['wrappers']})==data['stats']['apiFiles']
for e in inv['backend']:
    q=Path(data['repositories']['backend'])/e['file']
    source=q.read_text(encoding='utf-8-sig').splitlines()
    assert 'Mapping' in source[e['line']-1], e
for e in inv['wrappers']:
    q=Path(data['repositories']['frontend'])/e['file']
    assert e['function'] in q.read_text(encoding='utf-8-sig').splitlines()[e['line']-1], e
for s in sources:
    assert hashlib.sha256(Path(s['path']).read_bytes()).hexdigest()==s['sha256'],s['path']
assert len(ET.parse(p/'手机监测平台-完整功能思维导图.mm').findall('.//node'))==len(seen)
assert len(ET.parse(p/'手机监测平台-功能大纲.opml').findall('.//outline'))==len(seen)
ET.parse(p/'功能导图总览.svg')
html=(p/'完整功能思维导图.html').read_text(encoding='utf-8')
assert '__MAP_DATA__' not in html and '__INVENTORY__' not in html
assert '<script src=' not in html and 'fetch(' not in html
js=re.findall(r'<script>\s*([\s\S]*?)</script>',html)[0]
(p/'viewer-check.js').write_text(js,encoding='utf-8')
result={
 'asOf':'2026-10-20','checks':{
  'uniqueNodeIds':len(seen),'mmNodes':len(seen),'opmlNodes':len(seen),
  'backendMappings':len(inv['backend']),'frontendApiDeclarations':len(inv['wrappers']),
  'sourceHashesChecked':len(sources),'offlineViewer':True,
  'browser':['桌面1440px导图','窄屏分支树','模块展开与节点说明','关键词搜索','完整说明','后端206项索引','前端221项索引','SVG导出']
 },
 'limitations':['未执行真实业务写入或线上端到端验收','未在特定XMind版本进行MM导入验收','直接路径引用表为静态字面量扫描，不是全量运行时请求清单']
}
(p/'校验记录.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
files=['完整功能思维导图.html','手机监测平台-完整功能思维导图.mm','手机监测平台-功能大纲.opml',
 '完整功能说明.txt','阅读说明.txt','功能导图总览.svg','function-map.json','source-evidence.json',
 'source-inventory.json','backend-index.csv','wrappers-index.csv','direct-index.csv','校验记录.json']
with zipfile.ZipFile(p/'手机监测平台-完整功能导图资料包.zip','w',zipfile.ZIP_DEFLATED) as z:
    for filename in files: z.write(p/filename,filename)
with zipfile.ZipFile(p/'手机监测平台-完整功能导图资料包.zip') as z:
    assert z.testzip() is None
print(json.dumps({'verified':True,'stats':data['stats'],'packageFiles':len(files),
    'packageBytes':(p/'手机监测平台-完整功能导图资料包.zip').stat().st_size},ensure_ascii=False))
