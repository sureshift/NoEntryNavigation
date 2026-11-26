#!/usr/bin/env python3
"""Build the offline Delhi database (road graph + places) from an OSM extract.

usage: build_data.py delhi.osm.pbf out/delhi.db
Needs: pip install osmium
"""
import math, os, re, sqlite3, sys, time
import hashlib
import osmium

# highway -> (road class, default km/h). Class: 0 motorway/trunk, 1 primary/secondary, 2 tertiary/residential, 3 service
HW = {
    'motorway': (0, 70), 'motorway_link': (0, 40), 'trunk': (0, 55), 'trunk_link': (0, 35),
    'primary': (1, 40), 'primary_link': (1, 30), 'secondary': (1, 35), 'secondary_link': (1, 28),
    'tertiary': (2, 30), 'tertiary_link': (2, 25), 'unclassified': (2, 25), 'residential': (2, 20),
    'living_street': (2, 10), 'service': (3, 15),
}
# everything that has a name and one of these keys becomes searchable (places, businesses, institutions, buildings)
POI_KEYS = ('amenity', 'shop', 'office', 'craft', 'healthcare', 'tourism', 'historic', 'leisure', 'place',
            'industrial', 'man_made', 'landuse', 'building', 'railway', 'aeroway', 'public_transport')
POI_SKIP = {'bench', 'waste_basket', 'bicycle_parking', 'parking_space', 'parking_entrance', 'post_box',
            'vending_machine', 'shelter', 'drinking_water', 'recycling', 'no'}
LANDUSE_OK = {'industrial', 'commercial', 'retail', 'residential', 'education', 'institutional', 'railway'}
MANMADE_OK = {'works', 'storage_tank', 'wastewater_plant', 'water_works', 'petroleum_well'}
NAME_TAGS = ('name:en', 'name', 'name:hi', 'alt_name', 'old_name', 'short_name', 'brand', 'operator')


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


def posted_speed(t):
    """Speed limit actually posted in OSM (km/h), or 0 when the road has no maxspeed tag."""
    s = t.get('maxspeed')
    if s:
        m = re.match(r'\s*(\d+)', s)
        if m:
            v = int(m.group(1))
            if 'mph' in s:
                v = int(v * 1.6)
            if 5 <= v <= 120:
                return v
    return 0


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
        self.segs, self.cells, self.places, self.cams = [], [], [], []
        self.seen = set()

    def node_id(self, p):
        i = self.nid.get(p[0])
        if i is None:
            i = len(self.nodes)
            self.nid[p[0]] = i
            self.nodes.append((p[1], p[2]))
        return i

    def add_place(self, name, lname, kind, lat, lon, addr='', phone='', grouped=False):
        key = (lname, round(lat, 3), round(lon, 3))
        if key in self.seen:
            return
        self.seen.add(key)
        # streets are grouped by name when searching (a long road is many OSM ways); other places stay separate
        # integer group key: streets with the same name share one (hash); every other place has its own negative number
        gk = (int.from_bytes(hashlib.blake2b(lname.encode(), digest_size=7).digest(), 'big')) if grouped else -(len(self.places) + 1)
        self.places.append((name[:120], lname[:240], kind, lat, lon, addr[:160], phone[:40], gk))

    @staticmethod
    def names_text(t, extra=()):
        seen, out = set(), []
        for k in NAME_TAGS + tuple(extra):
            v = t.get(k)
            if v:
                for part in re.split(r'[;/]', v):
                    part = part.strip().lower()
                    if part and part not in seen:
                        seen.add(part)
                        out.append(part)
        return ' '.join(out)

    @staticmethod
    def address(t):
        if t.get('addr:full'):
            return t['addr:full']
        parts = []
        hn = ' '.join(x for x in (t.get('addr:housename'), t.get('addr:housenumber')) if x)
        if hn:
            parts.append(hn)
        for k in ('addr:street', 'addr:place', 'addr:suburb', 'addr:neighbourhood', 'addr:subdistrict', 'addr:city', 'addr:postcode'):
            v = t.get(k)
            if v and v not in parts:
                parts.append(v)
        return ', '.join(parts)

    def poi(self, t, lat, lon):
        name = t.get('name:en') or t.get('name')
        if not name or t.get('highway') and t.get('highway') not in ('bus_stop',):
            return
        for k in POI_KEYS:
            v = t.get(k)
            if not v or v in POI_SKIP:
                continue
            if k == 'railway' and v not in ('station', 'halt', 'tram_stop'):
                continue
            if k == 'aeroway' and v not in ('terminal', 'aerodrome'):
                continue
            if k == 'public_transport' and v != 'station':
                continue
            if k == 'landuse' and v not in LANDUSE_OK:
                continue
            if k == 'man_made' and v not in MANMADE_OK:
                continue
            kind = 'building' if (k == 'building' and v == 'yes') else v
            lname = self.names_text(t)
            self.add_place(name, lname, kind, lat, lon, self.address(t), t.get('phone') or t.get('contact:phone') or '')
            return

    def node(self, n):
        if n.tags and n.location.valid():
            t = n.tags
            if t.get('highway') == 'speed_camera' or t.get('enforcement') in ('maxspeed', 'average_speed'):
                self.cams.append((n.location.lat, n.location.lon, posted_speed(t), 'speed'))
            elif t.get('highway') == 'traffic_signals' and t.get('enforcement') == 'traffic_signals' or t.get('enforcement') == 'red_light':
                self.cams.append((n.location.lat, n.location.lon, 0, 'redlight'))
            self.chowk(n.tags, n.location.lat, n.location.lon)
            self.poi(n.tags, n.location.lat, n.location.lon)

    def chowk(self, t, lat, lon):
        """Named junctions (chowks, crossings, circles) so the map and search show them."""
        name = t.get('name:en') or t.get('name')
        if not name:
            return
        hw = t.get('highway')
        low = name.lower()
        if hw in ('traffic_signals', 'mini_roundabout', 'turning_circle', 'crossing', 'stop') or t.get('junction') or \
           re.search(r'chowk|chauraha|chaurah|crossing|circle|chowkdi|\bmor\b|\bmod\b|\bmore\b|intersection|red light|roundabout|gol chakkar', low):
            if hw and hw not in ('traffic_signals', 'mini_roundabout', 'turning_circle', 'crossing', 'stop') and not t.get('junction'):
                return
            self.add_place(name, self.names_text(t), 'chowk', lat, lon)

    def area(self, a):
        """Named buildings, campuses, markets, colonies... (closed ways and multipolygon relations), placed at their centre."""
        t = a.tags
        if not t.get('name') and not t.get('name:en'):
            return
        try:
            best = None
            for ring in a.outer_rings():
                pts = [(n.lat, n.lon) for n in ring]
                if best is None or len(pts) > len(best):
                    best = pts
            if not best:
                return
            lat = sum(p[0] for p in best) / len(best)
            lon = sum(p[1] for p in best) / len(best)
            self.poi(t, lat, lon)
        except Exception:
            return

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
            return          # named areas are handled in area()
        if not drivable(t):
            return
        cls, dspeed = HW[hw]
        kph = speed(t, dspeed)
        posted = posted_speed(t)
        ow = oneway(t, hw)
        flags = 1 if (t.get('hgv') == 'no' or t.get('goods') == 'no') else 0
        maxh, maxw = num(t.get('maxheight')), num(t.get('maxweight'))
        name = t.get('name:en') or t.get('name') or t.get('ref') or ''
        if name:
            mid = pts[len(pts) // 2]
            lname = self.names_text(t, extra=('ref',))
            self.add_place(name, lname, 'street', mid[1], mid[2], '', '', grouped=True)
        start = 0
        for i in range(1, len(pts)):
            if self.cnt.get(pts[i][0], 0) >= 2 or i == len(pts) - 1:
                self.emit(pts[start:i + 1], name, cls, kph, ow, flags, maxh, maxw, posted)
                start = i

    def emit(self, pts, name, cls, kph, ow, flags, maxh, maxw, posted=0):
        a, b = self.node_id(pts[0]), self.node_id(pts[-1])
        if a == b and len(pts) < 3:
            return
        length = sum(hav(pts[i][1], pts[i][2], pts[i + 1][1], pts[i + 1][2]) for i in range(len(pts) - 1))
        ll = [(p[1], p[2]) for p in pts]
        sid = len(self.segs)
        self.segs.append((sid, a, b, int(round(length)), kph, ow, cls, flags, maxh, maxw, name, encode(ll), posted))
        lats = [p[0] for p in ll]
        lons = [p[1] for p in ll]
        for cell in cells_for(min(lats), max(lats), min(lons), max(lons)):
            self.cells.append((cell, sid))


SCHEMA = """
CREATE TABLE node(id INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL);
CREATE TABLE seg(id INTEGER PRIMARY KEY, a INTEGER, b INTEGER, len INTEGER, speed INTEGER, oneway INTEGER,
                 cls INTEGER, flags INTEGER, maxh REAL, maxw REAL, name TEXT, geom TEXT, posted INTEGER DEFAULT 0);
CREATE TABLE cam(id INTEGER PRIMARY KEY, lat REAL, lon REAL, lim INTEGER, kind TEXT);
-- plain grid index (Android's SQLite may not ship the R-Tree module)
CREATE TABLE seg_cell(cell INTEGER NOT NULL, seg INTEGER NOT NULL, PRIMARY KEY(cell, seg)) WITHOUT ROWID;
CREATE TABLE place(id INTEGER PRIMARY KEY, name TEXT, lname TEXT, kind TEXT, lat REAL, lon REAL, addr TEXT, phone TEXT, gk INTEGER);
CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT);
"""


OV_KINDS = [
    (r'college|university|higher_edu', 'college'),
    (r'school|education|academy|institute|coaching|kindergarten|preschool|tutor', 'school'),
    (r'hospital|clinic|medical|doctor|dentist|health|diagnostic|pathology', 'hospital'),
    (r'pharmacy|drugstore', 'pharmacy'),
    (r'temple|mosque|church|gurudwara|religious|worship|monastery|shrine', 'place_of_worship'),
    (r'apartment|condominium|housing|residential|society|hostel|pg_', 'apartments'),
    (r'hotel|lodging|guest_house|resort|motel|dharamshala', 'hotel'),
    (r'restaurant|cafe|food|bakery|sweet|dhaba|bar_|pub', 'restaurant'),
    (r'bank|atm|financial', 'bank'),
    (r'gas_station|fuel|petrol|charging', 'fuel'),
    (r'metro|train|railway|bus_|transit|airport|station', 'station'),
    (r'landmark|monument|historical|museum|attraction|tourist|memorial|fort|tomb', 'attraction'),
    (r'park|garden|stadium|sport|gym|playground', 'park'),
    (r'government|police|fire_station|court|municipal|post_office|embassy', 'government'),
    (r'mall|shopping|supermarket|market|store|shop|retail', 'shop'),
    (r'office|service|business|professional|company', 'office'),
]


def overture_kind(cat):
    c = (cat or '').lower()
    for rx, k in OV_KINDS:
        if re.search(rx, c):
            return k
    return 'shop'


def add_overture(b, path):
    """Merge Overture Maps businesses/places; skip ones OpenStreetMap already has (same name within ~120 m)."""
    import csv
    have = {}
    for _n, ln, _k, la, lo, *_ in b.places:
        have.setdefault(ln, []).append((la, lo))
    added = 0
    with open(path, newline='', encoding='utf-8') as f:
        for name, cat, la, lo, addr, phone in csv.reader(f):
            name = name.strip()
            if len(name) < 2:
                continue
            la, lo = float(la), float(lo)
            ln = name.lower().replace("'", '')
            if any(abs(la - a) < 0.0011 and abs(lo - o) < 0.0011 for a, o in have.get(ln, ())):
                continue
            have.setdefault(ln, []).append((la, lo))
            k = overture_kind(cat)
            b.add_place(name, ln, k, la, lo, addr, phone)
            added += 1
    print(f'  merged {added} Overture places', flush=True)

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
    if len(sys.argv) > 3 and os.path.exists(sys.argv[3]):
        add_overture(b, sys.argv[3])
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    if os.path.exists(out):
        os.remove(out)
    con = sqlite3.connect(out)
    con.execute('PRAGMA journal_mode=OFF')
    con.execute('PRAGMA synchronous=OFF')
    con.executescript(SCHEMA)
    con.executemany('INSERT INTO node VALUES (?,?,?)', ((i, la, lo) for i, (la, lo) in enumerate(b.nodes)))
    con.executemany('INSERT INTO seg VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)', b.segs)
    con.executemany('INSERT INTO cam(lat,lon,lim,kind) VALUES (?,?,?,?)', b.cams)
    print(f'  {len(b.cams)} speed/red-light cameras, {sum(1 for x in b.segs if x[-1])} segments with a posted limit', flush=True)
    con.executemany('INSERT OR IGNORE INTO seg_cell VALUES (?,?)', b.cells)
    con.executemany('INSERT INTO place(name,lname,kind,lat,lon,addr,phone,gk) VALUES (?,?,?,?,?,?,?,?)', b.places)
    con.execute("INSERT INTO meta VALUES ('built', ?)", (time.strftime('%Y-%m-%d'),))
    con.execute('CREATE INDEX place_ll ON place(lat, lon)')
    con.commit()
    con.close()
    print(f'wrote {out} ({os.path.getsize(out) / 1e6:.1f} MB) in {time.time() - t0:.0f}s')


if __name__ == '__main__':
    main()
