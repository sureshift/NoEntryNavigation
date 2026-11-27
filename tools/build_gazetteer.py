#!/usr/bin/env python3
"""All Indian cities and towns (OpenStreetMap place=city|town) as a small offline list for the search box.
usage: build_gazetteer.py india.osm.pbf out.json     (input should already be filtered: osmium tags-filter ... n/place=city,town)"""
import json, sys
import osmium

class H(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.out = {}

    def node(self, n):
        p = n.tags.get('place')
        if p not in ('city', 'town') or not n.location.valid():
            return
        name = n.tags.get('name:en') or n.tags.get('name')
        if not name:
            return
        key = (name.lower(), round(n.location.lat, 1), round(n.location.lon, 1))
        if key in self.out:
            return
        pop = 0
        try:
            pop = int(str(n.tags.get('population', '0')).replace(',', '').split()[0])
        except Exception:
            pass
        self.out[key] = [name, round(n.location.lat, 4), round(n.location.lon, 4), 1 if p == 'city' else 2, pop]

h = H()
h.apply_file(sys.argv[1])
rows = sorted(h.out.values(), key=lambda r: (r[3], -r[4], r[0]))
for r in rows:
    r.pop()          # drop population
json.dump(rows, open(sys.argv[2], 'w', ensure_ascii=False), ensure_ascii=False, separators=(',', ':')) if False else open(sys.argv[2], 'w', encoding='utf-8').write(json.dumps(rows, ensure_ascii=False, separators=(',', ':')))
print(len(rows), 'cities and towns')
