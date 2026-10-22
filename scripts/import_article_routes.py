#!/usr/bin/env python3
"""Import only public Darwin article routing data; never change donor state."""
import hashlib, json, pathlib, re, urllib.parse

repo = pathlib.Path(__file__).resolve().parents[1]
donor = pathlib.Path('/home/red/Projects/Darwin/packages/browseros-agent/packages/darwin-routing/component')
catalog = pathlib.Path('/home/red/.browseros/agents/acp-workspace/task-state/current-public-instances.json')
config = json.loads((donor / 'config.json').read_text())
instances = json.loads(catalog.read_text())
defaults = {'scribe': ['https://scribe.rip'], 'libMedium': ['https://md.vern.cc'],
            'small': ['https://small.bloat.cat'], 'freedium': ['https://freedium.cfd']}
sources = []
for frontend, roots in defaults.items():
    for raw in roots + instances.get(frontend, {}).get('clearnet', []):
        u = urllib.parse.urlsplit(raw)
        if u.scheme != 'https' or not u.hostname or not '.' in u.hostname or u.username or u.password or u.query or u.fragment or u.port:
            continue
        item = {'frontend': frontend, 'root': raw.rstrip('/')}
        if item not in sources:
            sources.append(item)
names = ['config.json', 'assets/javascripts/news-archive-routes.js',
         'assets/javascripts/news-archive-content.js', 'assets/javascripts/publisher-lite-routes.js',
         'assets/javascripts/scientific-routes.js', 'assets/javascripts/scientific-discovery.js']
data = {'schema': 1, 'donor': 'Darwin', 'mediumTargets': config['services']['medium']['targets'],
        'mediumSources': sources, 'sourceHashes': {n: hashlib.sha256((donor/n).read_bytes()).hexdigest() for n in names},
        'protocolCorrection': 'Freedium uses its documented encoded full original URL; other Medium readers use Darwin path translation.',
        'protocolReference': 'https://github.com/Freedium-cfd'}
target = repo/'core/src/main/resources/darwin-article-routes.json'
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(json.dumps(data, indent=2)+'\n')
print(json.dumps({'donor': 'Darwin', 'mediumSources': len(sources), 'target': str(target)}))
