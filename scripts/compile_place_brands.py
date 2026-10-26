#!/usr/bin/env python3
"""Compile exact brand aliases and supported country/category scopes from pinned NSI data.
Build-time downloads remain on the external volume; the app makes no catalog requests.
"""
import argparse,hashlib,json,pathlib,re,tarfile,urllib.request,unicodedata
PIN='c2b5ff98903a8dce2d9c75e9b358fb3a6f8ae024'
SHA256='8cdadb07c302a9c2f73cb62339d826397d739cd05985e32f1d914670978be065'
ROOT=pathlib.Path('/run/media/red/RedtopStorage/DarwinBuild/LiberBuild')

def read_jsonc(raw):
    # Remove comments/trailing commas only outside JSON strings. No evaluation
    # of upstream expressions or executable source is involved.
    clean=[];i=0;quoted=False;escaped=False
    while i<len(raw):
        c=raw[i]
        if quoted:
            clean.append(c)
            if escaped:escaped=False
            elif c=='\\':escaped=True
            elif c=='"':quoted=False
            i+=1;continue
        if c=='"':quoted=True;clean.append(c);i+=1;continue
        if raw.startswith('//',i):
            end=raw.find('\n',i);i=len(raw)if end<0 else end;continue
        if raw.startswith('/*',i):
            end=raw.find('*/',i+2)
            if end<0:raise ValueError('Unclosed source comment')
            clean.append(' ');i=end+2;continue
        if c==',':
            end=i+1
            while end<len(raw)and raw[end].isspace():end+=1
            if end<len(raw)and raw[end]in '}]':i+=1;continue
        clean.append(c);i+=1
    return json.loads(''.join(clean))

def name_key(raw):
    raw=unicodedata.normalize('NFKD',raw.replace("'",'').replace('’',''))
    raw=''.join(c for c in raw if not unicodedata.category(c).startswith('M')).lower()
    words=re.findall(r'[^\W_]+',raw)[:256]
    while words and words[0]=='the':words.pop(0)
    return ' '.join(w for w in words if w not in {'llc','inc','ltd','co','company','corp','corporation'})

p=argparse.ArgumentParser(description=__doc__);p.add_argument('--archive',type=pathlib.Path,default=ROOT/'source-data/name-suggestion-index'/f'{PIN}.tar.gz');p.add_argument('--verify',action='store_true');args=p.parse_args()
if not args.archive.exists():
    args.archive.parent.mkdir(parents=True,exist_ok=True)
    with urllib.request.urlopen('https://codeload.github.com/osmlab/name-suggestion-index/tar.gz/'+PIN,timeout=60)as r:
        data=r.read(20*1024*1024+1);assert len(data)<=20*1024*1024;args.archive.write_bytes(data)
assert hashlib.file_digest(args.archive.open('rb'),'sha256').hexdigest()==SHA256,'Pinned source archive changed'
rows=[]
with tarfile.open(args.archive)as archive:
    for member in sorted(archive.getmembers(),key=lambda m:m.name):
        if '/data/brands/'not in member.name or not member.name.endswith('.json'):continue
        obj=read_jsonc(archive.extractfile(member).read().decode());parts=obj['properties']['path'].split('/')
        if len(parts)!=3 or parts[0]!='brands':continue
        key,value=parts[1:]
        for item in obj.get('items',[]):
            tags=item.get('tags',{});identity=tags.get('brand:wikidata','');name=tags.get('brand','')
            if not re.fullmatch(r'Q[1-9][0-9]{0,18}',identity)or not name:continue
            scope=item.get('locationSet',{});include=scope.get('include',[]);exclude=scope.get('exclude',[])
            def supported(x):
                if isinstance(x,str):return re.fullmatch(r'(?:[a-z]{2}|001)',x)is not None
                return isinstance(x,list)and len(x)in (2,3) and all(isinstance(n,(int,float))and not isinstance(n,bool)for n in x)and -180<=x[0]<=180 and -90<=x[1]<=90 and (len(x)==2 or 0<x[2]<=10000)
            if not include or not all(supported(x)for x in include+exclude):continue
            aliases=[v for k,v in tags.items()if k=='name'or k=='brand'or k.startswith('name:')or k.startswith('brand:')and k!='brand:wikidata']+item.get('matchNames',[])
            aliases=sorted(set(name_key(x.strip())for x in aliases if isinstance(x,str)and 3<=len(x.strip())<=150 and not any(ord(c)<32 for c in x)))
            if not aliases:continue
            rows.append([identity,name,aliases,sorted(include,key=lambda x:json.dumps(x)),sorted(exclude,key=lambda x:json.dumps(x)),key,value])
    license_member=next(m for m in archive.getmembers()if m.name.count('/')==1 and m.name.endswith('/LICENSE.md'))
    license_bytes=archive.extractfile(license_member).read()
payload={'schema':1,'source':'osmlab/name-suggestion-index','commit':PIN,'archiveSha256':SHA256,'brands':rows}
data=(json.dumps(payload,ensure_ascii=False,separators=(',',':'))+'\n').encode()
target=pathlib.Path('core/src/main/resources/place-brands.json');notice=pathlib.Path('licenses/name-suggestion-index.txt');runtime_notice=pathlib.Path('core/src/main/resources/place-brands-LICENSE.txt')
if args.verify:
    assert target.read_bytes()==data and notice.read_bytes()==license_bytes and runtime_notice.read_bytes()==license_bytes,'Compiled catalog does not match pinned source'
else:
    target.write_bytes(data);notice.write_bytes(license_bytes);runtime_notice.write_bytes(license_bytes)
print(json.dumps({'brandRows':len(rows),'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'verified':args.verify}))
