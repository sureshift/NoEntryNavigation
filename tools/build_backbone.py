#!/usr/bin/env python3
"""All-India highway backbone: a small road graph (motorway, trunk, primary) used only to plan which map tiles a trip needs.
usage: build_backbone.py <filtered.osm.pbf> <out.bin.gz> [classes]     classes: comma list, default motorway,trunk,primary
Binary layout (little endian), then gzip:
  'BBK1' nN:u32 nS:u32 | lat[nN]:i32 (deg*1e5) | lon[nN]:i32 | a[nS]:u32 | b[nS]:u32 | len[nS]:u16 (10 m units) | cls[nS]:u8 (0 motorway/trunk, 1 primary)"""
import gzip, hashlib, json, math, struct, sys
from array import array
import osmium

CLASSES = (sys.argv[3] if len(sys.argv) > 3 else 'motorway,trunk,primary').split(',')
EPS = 0.0016   # about 170 m: shape points closer than this to the chord are dropped


def keep(tags):
    h = tags.get('highway')
    return h if h in CLASSES else None


class Count(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.cnt = {}

    def way(self, w):
        if not keep(w.tags):
            return
        refs = [n.ref for n in w.nodes]
        if len(refs) < 2:
            return
        for r in refs:
            self.cnt[r] = self.cnt.get(r, 0) + 1
        self.cnt[refs[0]] += 1
        self.cnt[refs[-1]] += 1


def dp(pts, eps):
    """Douglas-Peucker; returns indices to keep."""
    n = len(pts)
    if n <= 2:
        return list(range(n))
    keepi = [False] * n
    keepi[0] = keepi[-1] = True
    stack = [(0, n - 1)]
    while stack:
        i, j = stack.pop()
        (y1, x1), (y2, x2) = pts[i], pts[j]
        k = math.cos(math.radians((y1 + y2) / 2))
        dx, dy = (x2 - x1) * k, y2 - y1
        L2 = dx * dx + dy * dy
        best, bi = -1.0, -1
        for m in range(i + 1, j):
            px, py = (pts[m][1] - x1) * k, pts[m][0] - y1
            if L2 == 0:
                d = math.hypot(px, py)
            else:
                t = max(0.0, min(1.0, (px * dx + py * dy) / L2))
                d = math.hypot(px - t * dx, py - t * dy)
            if d > best:
                best, bi = d, m
        if best > eps and bi > 0:
            keepi[bi] = True
            stack.append((i, bi)); stack.append((bi, j))
    return [i for i in range(n) if keepi[i]]


def hav(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 12742000 * math.asin(math.sqrt(h))


class Build(osmium.SimpleHandler):
    def __init__(self, cnt):
        super().__init__()
        self.cnt = cnt
        self.idx = {}
        self.lat = array('i'); self.lon = array('i')
        self.a = array('I'); self.b = array('I'); self.ln = array('H'); self.cls = array('B')

    def node_index(self, ref, la, lo):
        i = self.idx.get(ref)
        if i is None:
            i = len(self.lat)
            self.idx[ref] = i
            self.lat.append(int(round(la * 1e5))); self.lon.append(int(round(lo * 1e5)))
        return i

    def way(self, w):
        h = keep(w.tags)
        if not h:
            return
        try:
            pts = [(n.lat, n.lon, n.ref) for n in w.nodes]
        except osmium.InvalidLocationError:
            return
        if len(pts) < 2:
            return
        c = 0 if h in ('motorway', 'trunk') else 1
        key = [0] + [i for i in range(1, len(pts) - 1) if self.cnt.get(pts[i][2], 0) > 1] + [len(pts) - 1]
        for s, e in zip(key, key[1:]):
            part = pts[s:e + 1]
            kept = dp([(p[0], p[1]) for p in part], EPS)
            for u, v in zip(kept, kept[1:]):
                pu, pv = part[u], part[v]
                d = 0.0
                for m in range(u, v):
                    d += hav(part[m], part[m + 1])
                ia = self.node_index(pu[2], pu[0], pu[1]); ib = self.node_index(pv[2], pv[0], pv[1])
                if ia == ib:
                    continue
                self.a.append(ia); self.b.append(ib); self.ln.append(min(65535, int(round(d / 10)))); self.cls.append(c)


def main():
    src, out = sys.argv[1], sys.argv[2]
    c = Count(); c.apply_file(src)
    b = Build(c.cnt); b.apply_file(src, locations=True)
    nN, nS = len(b.lat), len(b.a)
    raw = b'BBK1' + struct.pack('<II', nN, nS)
    for arr in (b.lat, b.lon, b.a, b.b, b.ln, b.cls):
        if sys.byteorder != 'little':
            arr.byteswap()
        raw += arr.tobytes()
    gz = gzip.compress(raw, 9)
    open(out, 'wb').write(gz)
    sha = hashlib.sha256(gz).hexdigest()
    print(f'SUMMARY classes={CLASSES} nodes={nN} segs={nS} raw={len(raw)/1e6:.1f}MB gz={len(gz)/1e6:.2f}MB sha={sha}')
    json.dump({'v': 1, 'size': len(gz), 'sha256': sha, 'nodes': nN, 'segs': nS, 'classes': CLASSES,
               'url': 'https://github.com/sureshift/NoEntryNavigation/releases/download/backbone/backbone.bin.gz'},
              open(out + '.json', 'w'))


main()
