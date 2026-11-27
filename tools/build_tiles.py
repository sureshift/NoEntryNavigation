#!/usr/bin/env python3
"""Build 0.5-degree map tiles for all of India, in two layers:
  L1 'lite'  : motorway..tertiary roads + towns/villages (used between cities, small)
  L2 'full'  : every road + all named places (used around the start and the destination)
usage: build_tiles.py <zone> <outdir> [minlon,minlat,maxlon,maxlat]   (optional area filter)
Tile id = "<y>_<x>" with x = floor(lon*2), y = floor(lat*2)."""
import gzip, hashlib, json, math, os, shutil, sqlite3, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, '..', 'data')
UA = 'NoEntryNav-CI/1.0 (github.com/sureshift/NoEntryNavigation)'
MARGIN = 0.01
BATCH = 24
# rough coverage of each Geofabrik zone (minlon, minlat, maxlon, maxlat)
ZONES = {
    'northern-zone': (72.5, 23.0, 80.5, 37.2),
    'central-zone': (73.0, 21.0, 84.8, 31.5),
    'eastern-zone': (83.0, 19.2, 89.9, 28.0),
    'north-eastern-zone': (87.5, 21.5, 97.5, 29.6),
    'southern-zone': (72.5, 6.5, 85.0, 20.0),
    'western-zone': (68.0, 15.4, 77.5, 25.5),
}


def sh(*a, check=True, env=None):
    print('+', ' '.join(map(str, a)), flush=True)
    return subprocess.run(list(map(str, a)), check=check, env=env)


def get_zone(zone):
    out = os.path.join(DATA, f'zone-{zone}.pbf')
    if os.path.exists(out) and os.path.getsize(out) > 1_000_000:
        return out
    for u in (f'https://download.geofabrik.de/asia/india/{zone}-latest.osm.pbf',
              f'https://ftp5.gwdg.de/pub/misc/openstreetmap/download.geofabrik.de/asia/india/{zone}-latest.osm.pbf'):
        r = sh('curl', '-L', '--fail', '--retry', '3', '--retry-delay', '10', '--connect-timeout', '30',
               '--max-time', '2700', '-A', UA, '-o', out, u, check=False)
        if r.returncode == 0 and os.path.getsize(out) > 1_000_000:
            return out
    raise SystemExit('cannot download ' + zone)


def tiles_in(zone, area):
    x0, y0, x1, y1 = ZONES[zone]
    if area:
        x0, y0, x1, y1 = max(x0, area[0]), max(y0, area[1]), min(x1, area[2]), min(y1, area[3])
    out = []
    for y in range(int(math.floor(y0 * 2)), int(math.ceil(y1 * 2))):
        for x in range(int(math.floor(x0 * 2)), int(math.ceil(x1 * 2))):
            out.append((y, x))
    return out


def build_db(pbf, db, lite):
    env = dict(os.environ)
    env['LITE'] = '1' if lite else '0'
    if os.path.exists(db):
        os.remove(db)
    sh(sys.executable, os.path.join(HERE, 'build_data.py'), pbf, db, env=env)
    con = sqlite3.connect(db)
    segs = con.execute('select count(*) from seg').fetchone()[0]
    nodes = con.execute('select count(*) from node').fetchone()[0]
    places = con.execute('select count(*) from place').fetchone()[0]
    con.close()
    return nodes, segs, places


def pack(db, gz):
    with open(db, 'rb') as i, gzip.open(gz, 'wb', compresslevel=9) as o:
        shutil.copyfileobj(i, o, 1 << 20)
    return hashlib.sha256(open(gz, 'rb').read()).hexdigest()


def main():
    zone, outdir = sys.argv[1], sys.argv[2]
    area = [float(v) for v in sys.argv[3].split(',')] if len(sys.argv) > 3 and sys.argv[3] else None
    os.makedirs(outdir, exist_ok=True)
    os.makedirs(DATA, exist_ok=True)
    zpbf = get_zone(zone)
    tiles = tiles_in(zone, area)
    print(len(tiles), 'candidate tiles', flush=True)
    stats = []
    for b in range(0, len(tiles), BATCH):
        batch = tiles[b:b + BATCH]
        exdir = os.path.join(DATA, 'ex')
        shutil.rmtree(exdir, ignore_errors=True)
        os.makedirs(exdir)
        cfg = {'directory': exdir, 'extracts': [
            {'output': f'{y}_{x}.osm.pbf',
             'bbox': [x / 2 - MARGIN, y / 2 - MARGIN, x / 2 + 0.5 + MARGIN, y / 2 + 0.5 + MARGIN]} for y, x in batch]}
        cp = os.path.join(DATA, 'extract.json')
        json.dump(cfg, open(cp, 'w'))
        r = sh('osmium', 'extract', '--strategy', 'complete_ways', '--overwrite', '-c', cp, zpbf, check=False)
        if r.returncode != 0:
            print('::warning::extract batch failed', b, flush=True)
            continue
        for y, x in batch:
            tid = f'{y}_{x}'
            pbf = os.path.join(exdir, tid + '.osm.pbf')
            if not os.path.exists(pbf) or os.path.getsize(pbf) < 20_000:
                continue                                    # sea / empty
            try:
                rec = dict(id=tid, y=y, x=x)
                for lite, tag in ((False, 'L2'), (True, 'L1')):
                    db = os.path.join(DATA, f'{tag}_{tid}.db')
                    n, s, p = build_db(pbf, db, lite)
                    if s < 20:
                        raise RuntimeError('no roads')
                    gz = os.path.join(outdir, f'{tag}_{tid}.db.gz')
                    sha = pack(db, gz)
                    rec[tag] = dict(size=os.path.getsize(gz), dbsize=os.path.getsize(db), sha=sha, nodes=n, segs=s, places=p)
                    os.remove(db)
                json.dump(rec, open(os.path.join(outdir, f'{tid}.json'), 'w'))
                stats.append(rec)
                print('tile', tid, rec['L1']['size'], rec['L2']['size'], flush=True)
            except Exception as ex:
                print(f'::warning::tile {tid} skipped: {ex}', flush=True)
            finally:
                try: os.remove(pbf)
                except OSError: pass
    l1 = sum(r['L1']['size'] for r in stats); l2 = sum(r['L2']['size'] for r in stats)
    print(f'SUMMARY zone={zone} tiles={len(stats)} L1={l1/1e6:.1f}MB L2={l2/1e6:.1f}MB '
          f'avgL1={l1/max(1,len(stats))/1e6:.2f}MB avgL2={l2/max(1,len(stats))/1e6:.2f}MB', flush=True)


if __name__ == '__main__':
    main()
