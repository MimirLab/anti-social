#!/usr/bin/env python3
"""Import reviewed public routing/filter data; never copy profile credentials."""
import gzip, hashlib, json, pathlib, shutil, urllib.parse

repo=pathlib.Path(__file__).resolve().parents[1]
darwin=pathlib.Path('/home/red/Projects/Darwin/packages/browseros-agent')
assets=repo/'app/src/main/assets';assets.mkdir(parents=True,exist_ok=True)
snapshot=json.loads((darwin/'.native/private-protections/routing-after.json').read_text())
config=json.loads((darwin/'packages/darwin-routing/component/config.json').read_text())
catalogue=json.loads(pathlib.Path('/home/red/.browseros/agents/acp-workspace/task-state/current-public-instances.json').read_text())
social=['twitter','bluesky','reddit','tumblr','tiktok','instagram','pinterest','ifunny','baiduTieba','threads']
def public(root):
    u=urllib.parse.urlsplit(root)
    return u.scheme=='https' and not u.username and not u.password and bool(u.hostname) and '.' in u.hostname and not u.query and not u.fragment
sources=[]
for service in social:
    curated=snapshot['options'].get('routingPools',{}).get(service,[])
    for n in curated:
        if public(n['instance']):sources.append(dict(platform=service,frontend=n['frontend'],root=n['instance'].rstrip('/'),fallbackOnly=n.get('fallbackOnly',False),curated=True))
    for frontend in config['services'][service]['frontends']:
        roots=list(catalogue.get(frontend,{}).get('clearnet',[]))+list(snapshot['options'].get(frontend,[]))
        for root in roots:
            if public(root) and not any(n['frontend']==frontend and n['root']==root.rstrip('/') for n in sources):sources.append(dict(platform=service,frontend=frontend,root=root.rstrip('/'),fallbackOnly=False,curated=False))
search_sources=[dict(platform='twitter',frontend=n['frontend'],root=n['instance'].rstrip('/'),fallbackOnly=True,curated=True) for n in snapshot['options'].get('routingPools',{}).get('search',[]) if n['frontend']=='librey' and public(n['instance'])]
data={'schema':1,'donor':'Darwin Alternative Routes 3.4.0.1','services':{s:config['services'][s] for s in social},'sources':sources,'searchSources':search_sources}
(assets/'darwin-routes.json').write_text(json.dumps(data,indent=2))
export=pathlib.Path('/home/red/.browseros/backups/agent-work-restart-20261002T185607Z/live-exports/ublock-native.json')
u=json.loads(export.read_text())
lists=[{'id':url,'url':url,'name':urllib.parse.urlsplit(url).hostname,'enabled':True,'origin':'Darwin custom list export'} for url in u['selectedFilterLists'] if url.startswith('https://')]
standard={
 'ublock-filters':'https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt',
 'ublock-badware':'https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/badware.txt',
 'ublock-privacy':'https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt',
 'ublock-unbreak':'https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/unbreak.txt',
 'ublock-quick-fixes':'https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/quick-fixes.txt',
 'easylist':'https://easylist.to/easylist/easylist.txt',
 'easyprivacy':'https://easylist.to/easylist/easyprivacy.txt',
}
lists += [{'id':k,'url':v,'name':k,'enabled':True,'origin':'Darwin selected standard filters'} for k,v in standard.items() if k in u['selectedFilterLists']]
(assets/'filter-sources.json').write_text(json.dumps({'schema':1,'snapshot':'2026-10-02','sources':lists},indent=2))
clean=json.loads((darwin/'.native/search-routing/clearurls-routing-state.json').read_text())
def locate(v):
    if isinstance(v,dict):
        if 'providers' in v and isinstance(v['providers'],dict):return v
        for key,x in v.items():
            if key.lower() in ['clearurlsdata','clearurlsdatahash','storage','selected','value'] or isinstance(x,(dict,list)):
                if isinstance(x,str):
                    try:x=json.loads(x)
                    except:continue
                found=locate(x)
                if found:return found
    if isinstance(v,list):
        for x in v:
            found=locate(x)
            if found:return found
rules=locate(clean)
if not rules:raise RuntimeError('Darwin cleaner rule catalogue not found')
(assets/'clean-links.json').write_text(json.dumps(rules,separators=(',',':')))
licenses=repo/'licenses';licenses.mkdir(exist_ok=True)
shutil.copyfile(darwin/'packages/darwin-routing/LICENSE',repo/'LICENSE')
for p in (darwin/'packages/darwin-browser-features/components/links').glob('LICENSE*'):shutil.copyfile(p,licenses/p.name)
proof={'donor':'Darwin','routing_component':'3.4.0.1','curated_sources':sum(s['curated'] for s in sources),'candidate_sources':len(sources),'search_discovery_sources':len(search_sources),'additional_search_providers':[{'frontend':frontend,'root':root,'donor':False,'enabled_by':'originalFallback and selected searchProvider','transport':'Tor required','purpose':'Public index link discovery; not an X search API'} for frontend,root in [('bing-rss','https://www.bing.com'),('duckduckgo','https://html.duckduckgo.com')]],'custom_filter_sources':25,'live_export_verified':False,'files':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in assets.glob('*.json')}}
(assets/'provenance.json').write_text(json.dumps(proof,indent=2))
print(json.dumps(proof,indent=2))
