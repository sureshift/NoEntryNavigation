#!/usr/bin/env python3
"""Build the offline Delhi database (road graph + places) from an OSM extract.

usage: build_data.py delhi.osm.pbf out/delhi.db
Needs: pip install osmium
"""
import math, os, re, sqlite3, sys, time
import osmium

# highway -> (road class, default km/h). Class: 0 motorway/trunk, 1 primary/secondary, 2 tertiary/residential, 3 service
HW = {
    'motorway': (0, 70), 'motorway_link': (0, 40), 'trunk': (0, 55), 'trunk_link': (0, 35),
    'primary': (1, 40), 'primary_link': (1, 30), 'secondary': (1, 35), 'secondary_link': (1, 28),
    'tertiary': (2, 30), 'tertiary_link': (2, 25), 'unclassified': (2, 25), 'residential': (2, 20),
    'living_street': (2, 10), 'service': (3, 15),
}
POI_KEYS = ('amenity', 'shop', 'tourism', 'historic', 'leisure', 'office', 'place', 'building',
            'railway', 'aeroway', 'public_transport')
POI_SKIP = {'bench', 'waste_basket', 'bicycle_parking', 'parking_space', 'parking_entrance', 'post_box',
            'vending_machine', 'shelter', 'drinking_water', 'recycling', 'yes', 'no'}


CELL = 0.005   # grid cell size in degrees (~550 m). Must match Engine.kt


def cells_for(minlat, maxlat, minlon, maxlon):
    r0, r1 = int(math.floor(minlat / CELL)), int(math.floor(maxlat / CELL))
    c0, c1 = int(math.floor(minlon / CELL)), int(math.floor(maxlon / CELL))
    return [r * 100000 + c for r in range(r0, r1 + 1) for c in range(c0, c1 + 1)]


def hav(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 12742000.0 * math.asin(min(1.0, math.sqrt(a)))


def enc_val(v, out):
    v = ~(v << 1) if v < 0 else (v << 1)
    while v >= 0x20:
        out.append(chr((0x20 | (v & 0x1F)) + 63))
        v >>= 5
    out.append(chr(v + 63))


def encode(pts):
    out, plat, plon = [], 0, 0
    for lat, lon in pts:
        la, lo = round(lat * 1e6), round(lon * 1e6)
        enc_val(la - plat, out)
        enc_val(lo - plon, out)
        plat, plon = la, lo
    return ''.join(out)


def num(s):
    if not s or "'" in s:
        return 0.0
    m = re.match(r'\s*([0-9]+(?:[.,][0-9]+)?)', s)
    if not m:
        return 0.0
    v = float(m.group(1).replace(',', '.'))
    return v / 1000.0 if 'kg' in s else v


def drivable(t):
    hw = t.get('highway')
    if hw not in HW:
        return False
    if hw == 'service' and t.get('service') in ('parking_aisle', 'driveway', 'drive-through', 'emergency_access'):
        return False
    if t.get('area') == 'yes':
        return False
    mv = t.get('motor_vehicle')
    if mv in ('no', 'private'):
        return False
    if mv is None and t.get('access') in ('no', 'private'):
        return False
    return True


def oneway(t, hw):
    v = t.get('oneway')
    if v in ('yes', 'true', '1'):
        return 1
    if v == '-1':
        return -1
    if v in ('no', 'false', '0'):
        return 0
    if t.get('junction') in ('roundabout', 'circular') or hw in ('motorway', 'motorway_link'):
        return 1
    return 0


def speed(t, default):
    s = t.get('maxspeed')
    if s:
        m = re.match(r'\s*(\d+)', s)
        if m:
            v = int(m.group(1))
            if 'mph' in s:
                v = int(v * 1.6)
            if 5 <= v <= 120:
                return min(v, 80)
    return default


class Count(osmium.SimpleHandler):
    """Pass 1: count how many drivable ways use each node, so we know where the intersections are."""
    def __init__(self):
        super().__init__()
        self.cnt = {}

    def way(self, w):
        if not drivable(w.tags):
            return
        refs = [n.ref for n in w.nodes]
        if len(refs) < 2:
            return
        c = self.cnt
        for r in refs:
            c[r] = c.get(r, 0) + 1
        c[refs[0]] += 1
        c[refs[-1]] += 1


class Build(osmium.SimpleHandler):
    def __init__(self, cnt):
        super().__init__()
        self.cnt = cnt
        self.nid, self.nodes = {}, []
        self.segs, self.cells, self.places = [], [], []
        self.seen = set()

    def node_id(self, p):
        i = self.nid.get(p[0])
        if i is None:
            i = len(self.nodes)
            self.nid[p[0]] = i
            self.nodes.append((p[1], p[2]))
        return i

    def add_place(self, name, lname, kind, lat, lon):
        key = (lname, round(lat, 3), round(lon, 3))
        if key in self.seen:
            return
        self.seen.add(key)
        self.places.append((name, lname, kind, lat, lon))

    def poi(self, t, lat, lon):
        name = t.get('name:en') or t.get('name')
        if not name:
            return
        for k in POI_KEYS:
            v = t.get(k)
            if v and v not in POI_SKIP:
                if k == 'railway' and v not in ('station', 'halt', 'tram_stop'):
                    continue
                if k == 'aeroway' and v not in ('terminal', 'aerodrome'):
                    continue
                if k == 'public_transport' and v != 'station':
                    continue
                lname = ((t.get('name:en') or '') + ' ' + (t.get('name') or '')).strip().lower()
                self.add_place(name, lname, v, lat, lon)
                return

    def node(self, n):
        if n.tags and n.location.valid():
            self.poi(n.tags, n.location.lat, n.location.lon)

    def way(self, w):
        t = w.tags
        hw = t.get('highway')
        pts = []
        for n in w.nodes:
            if n.location.valid():
                pts.append((n.ref, n.location.lat, n.location.lon))
        if len(pts) < 2:
            return
        if hw is None:
            if t.get('name') and not t.get('barrier'):
                self.poi(t, pts[0][1], pts[0][2])
            return
        if not drivable(t):
            return
        cls, dspeed = HW[hw]
        kph = speed(t, dspeed)
        ow = oneway(t, hw)
        flags = 1 if (t.get('hgv') == 'no' or t.get('goods') == 'no') else 0
        maxh, maxw = num(t.get('maxheight')), num(t.get('maxweight'))
        name = t.get('name:en') or t.get('name') or t.get('ref') or ''
        if name and cls <= 2:
            mid = pts[len(pts) // 2]
            lname = ((t.get('name:en') or '') + ' ' + (t.get('name') or t.get('ref') or '')).strip().lower()
            self.add_place(name, lname, 'street', mid[1], mid[2])
        start = 0
        for i in range(1, len(pts)):
            if self.cnt.get(pts[i][0], 0) >= 2 or i == len(pts) - 1:
                self.emit(pts[start:i + 1], name, cls, kph, ow, flags, maxh, maxw)
                start = i

    def emit(self, pts, name, cls, kph, ow, flags, maxh, maxw):
        a, b = self.node_id(pts[0]), self.node_id(pts[-1])
        if a == b and len(pts) < 3:
            return
        length = sum(hav(pts[i][1], pts[i][2], pts[i + 1][1], pts[i + 1][2]) for i in range(len(pts) - 1))
        ll = [(p[1], p[2]) for p in pts]
        sid = len(self.segs)
        self.segs.append((sid, a, b, int(round(length)), kph, ow, cls, flags, maxh, maxw, name, encode(ll)))
        lats = [p[0] for p in ll]
        lons = [p[1] for p in ll]
        for cell in cells_for(min(lats), max(lats), min(lons), max(lons)):
            self.cells.append((cell, sid))


SCHEMA = """
CREATE TABLE node(id INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL);
CREATE TABLE seg(id INTEGER PRIMARY KEY, a INTEGER, b INTEGER, len INTEGER, speed INTEGER, oneway INTEGER,
                 cls INTEGER, flags INTEGER, maxh REAL, maxw REAL, name TEXT, geom TEXT);
-- plain grid index (Android's SQLite may not ship the R-Tree module)
CREATE TABLE seg_cell(cell INTEGER NOT NULL, seg INTEGER NOT NULL, PRIMARY KEY(cell, seg)) WITHOUT ROWID;
CREATE TABLE place(id INTEGER PRIMARY KEY, name TEXT, lname TEXT, kind TEXT, lat REAL, lon REAL);
CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT);
"""


def main():
    src, out = sys.argv[1], sys.argv[2]
    t0 = time.time()
    print('pass 1: counting intersections ...', flush=True)
    c = Count()
    c.apply_file(src)
    print(f'  {len(c.cnt)} nodes on drivable ways ({time.time() - t0:.0f}s)', flush=True)
    print('pass 2: building graph + places ...', flush=True)
    b = Build(c.cnt)
    del c
    b.apply_file(src, locations=True)
    print(f'  {len(b.nodes)} intersections, {len(b.segs)} segments, {len(b.places)} places ({time.time() - t0:.0f}s)', flush=True)
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    if os.path.exists(out):
        os.remove(out)
    con = sqlite3.connect(out)
    con.execute('PRAGMA journal_mode=OFF')
    con.execute('PRAGMA synchronous=OFF')
    con.executescript(SCHEMA)
    con.executemany('INSERT INTO node VALUES (?,?,?)', ((i, la, lo) for i, (la, lo) in enumerate(b.nodes)))
    con.executemany('INSERT INTO seg VALUES (?,?,?,?,?,?,?,?,?,?,?,?)', b.segs)
    con.executemany('INSERT OR IGNORE INTO seg_cell VALUES (?,?)', b.cells)
    con.executemany('INSERT INTO place(name,lname,kind,lat,lon) VALUES (?,?,?,?,?)', b.places)
    con.execute('CREATE INDEX place_l ON place(lname)')
    con.execute("INSERT INTO meta VALUES ('built', ?)", (time.strftime('%Y-%m-%d'),))
    con.commit()
    con.close()
    print(f'wrote {out} ({os.path.getsize(out) / 1e6:.1f} MB) in {time.time() - t0:.0f}s')


if __name__ == '__main__':
    main()
