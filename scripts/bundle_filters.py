#!/usr/bin/env python3
"""Fetch Darwin's exact source collection and produce a bounded offline seed."""
import concurrent.futures,gzip,hashlib,json,pathlib,urllib.request
root=pathlib.Path(__file__).resolve().parents[1]
assets=root/'app/src/main/assets'
sources=json.loads((assets/'filter-sources.json').read_text())['sources']
cache=pathlib.Path('/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/filter-downloads');cache.mkdir(parents=True,exist_ok=True)
def fetch(source):
    url=source['url']; key=hashlib.sha256(url.encode()).hexdigest();p=cache/key
    try:
        if not p.exists():
            req=urllib.request.Request(url,headers={'User-Agent':'AntiSocial filter seed builder'})
            with urllib.request.urlopen(req,timeout=35) as response:raw=response.read(64*1024*1024+1)
            if len(raw)>64*1024*1024:raise ValueError('list exceeds limit')
            text=raw.decode('utf-8-sig')
            if '<html' in text[:1024].lower() or '<!doctype' in text[:1024].lower():raise ValueError('source returned HTML')
            p.write_bytes(raw)
        raw=p.read_bytes(); text=raw.decode('utf-8-sig'); lines=text.splitlines()
        return source,lines,{'id':source['id'],'url':url,'status':'ready','sha256':hashlib.sha256(raw).hexdigest(),'bytes':len(raw),'lines':len(lines)}
    except Exception as e:return source,[],{'id':source['id'],'url':url,'status':'unavailable','error':str(e)}
rules=set();notices=[];records=[]
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as ex:
    for source,lines,record in ex.map(fetch,sources):
        records.append(record);print(source['id'],record['status'],record.get('lines',0),flush=True)
        notices.append('\nSOURCE '+source['url']+'\n'+'\n'.join(line for line in lines if line.strip().startswith(('!','#'))))
        rules.update(line.strip() for line in lines if line.strip() and not line.strip().startswith(('!','#','[')))
with gzip.open(assets/'network-rules.seed','wt',encoding='utf-8') as f:f.write('\n'.join(sorted(rules)))
(assets/'filter-seed-status.json').write_text(json.dumps({'rules':len(rules),'sources':records},indent=2))
(root/'licenses/filter-notices.txt').write_text('\n'.join(notices))
print('Seed rules:',len(rules),'gzip bytes:',(assets/'network-rules.seed').stat().st_size)
