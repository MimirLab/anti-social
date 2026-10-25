#!/usr/bin/env python3
"""Compile a pinned iD vocabulary, keeping aliases separate from related terms.
Downloads are development inputs under the external build root, never app traffic.
"""
import argparse,hashlib,json,pathlib,tarfile,urllib.request
PIN='1bd56321fc3d299ad7f0714d80eaea9ec485e112'
ROOT=pathlib.Path('/run/media/red/RedtopStorage/DarwinBuild/LiberBuild')
p=argparse.ArgumentParser();p.add_argument('--archive',type=pathlib.Path,default=ROOT/'evidence/search-intent-20261025/id-schema.tar.gz');args=p.parse_args()
if not args.archive.exists():
 args.archive.parent.mkdir(parents=True,exist_ok=True)
 with urllib.request.urlopen('https://codeload.github.com/openstreetmap/id-tagging-schema/tar.gz/'+PIN,timeout=60)as r:args.archive.write_bytes(r.read(20*1024*1024))
expected='4fae3c65abf02533c98674edce66733bc45befe4bc5781b8f0ced5ca5f2ce728'
if hashlib.file_digest(args.archive.open('rb'),'sha256').hexdigest()!=expected:raise SystemExit('Pinned iD schema archive integrity check failed')
allowed={'amenity','shop','leisure','tourism','craft','office','healthcare','cuisine','diet:vegan','diet:vegetarian','organic'}
rows={}
with tarfile.open(args.archive)as t:
 for m in t.getmembers():
  marker='/data/presets/'
  if marker not in m.name or not m.name.endswith('.json'):continue
  key=m.name.split(marker,1)[1][:-5]
  if key.split('/')[0] not in allowed or any(x.startswith('_')for x in key.split('/')):continue
  obj=json.load(t.extractfile(m));tags=obj.get('tags',{})
  if obj.get('searchable')is False or not tags or len(tags)>3 or any(k not in allowed or not isinstance(v,str)or not v.replace('_','').replace('-','').isalnum()for k,v in tags.items()):continue
  if tags.get('shop') in {'vacant','no'}:continue
  rows[key]=obj
 def name(key,seen=()):
  if key in seen:return ''
  n=rows.get(key,{}).get('name','')
  return name(n[1:-1],seen+(key,))if n.startswith('{')and n.endswith('}')else n
 concepts={}
 for key,obj in sorted(rows.items()):
  title=name(key)
  if not title:continue
  tags=obj['tags'];cuisine=tags.get('cuisine');identity='cuisine:'+cuisine if cuisine else key
  c=concepts.setdefault(identity,dict(id=identity,name=(cuisine.replace('_',' ').title()+' restaurants')if cuisine else title,aliases=[],related=[],tags=[]))
  aliases=[title]+obj.get('aliases',[])
  if cuisine:aliases += [cuisine.replace('_',' '),cuisine.replace('_',' ')+' food',cuisine.replace('_',' ')+' cuisine']
  elif len(tags)==1:aliases += [next(iter(tags.values())).replace('_',' ')]
  c['aliases']+=aliases;c['related']+=obj.get('terms',[])
  if tags not in c['tags']:c['tags'].append(tags)
 for c in concepts.values():
  c['aliases']=sorted(set(c['aliases']));c['related']=sorted(set(c['related']))[:24];c['tags']=c['tags'][:4]
 payload=dict(version=1,source='openstreetmap/id-tagging-schema',commit=PIN,archiveSha256=hashlib.file_digest(args.archive.open('rb'),'sha256').hexdigest(),concepts=list(concepts.values()))
 target=pathlib.Path('core/src/main/resources/place-search.json');target.write_text(json.dumps(payload,ensure_ascii=False,separators=(',',':'))+'\n')
 print(len(concepts),'concepts;',target.stat().st_size,'bytes')
