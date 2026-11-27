#!/usr/bin/env python3
"""Merge catalog-*.json shard files into feed/tiles.json. usage: make_tile_catalog.py <dir> <out>"""
import glob, json, os, sys
d, out = sys.argv[1], sys.argv[2]
t = {}
for f in glob.glob(os.path.join(d, 'catalog-*.json')):
    for r in json.load(open(f)):
        t[r['id']] = [r['y'], r['x'], r['L1']['size'], r['L1']['sha'], r['L2']['size'], r['L2']['sha'], r['L2']['dbsize'], r['L1']['dbsize']]
json.dump({'v': 1, 'cell': 0.5, 'base': 'https://github.com/sureshift/NoEntryNavigation/releases/download', 't': t}, open(out, 'w'), separators=(',', ':'))
print(len(t), 'tiles')
