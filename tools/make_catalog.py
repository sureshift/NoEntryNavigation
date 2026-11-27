#!/usr/bin/env python3
"""Merge per-pack <id>.json files into feed/catalog.json. usage: make_catalog.py <dir with *.json> <out> <download-base-url>"""
import glob, json, os, sys
d, out, base = sys.argv[1], sys.argv[2], sys.argv[3].rstrip('/')
packs = []
for f in sorted(glob.glob(os.path.join(d, '*.json'))):
    if os.path.basename(f) == 'catalog.json':
        continue
    m = json.load(open(f))
    m['url'] = f"{base}/{m['id']}.db.gz"
    packs.append(m)
packs.sort(key=lambda m: (m['state'], m['name']))
json.dump({'version': 1, 'packs': packs}, open(out, 'w'), indent=1)
print(len(packs), 'packs in catalog')
