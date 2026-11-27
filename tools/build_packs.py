#!/usr/bin/env python3
"""Build downloadable city map packs (same offline database as the bundled Delhi map).
usage: build_packs.py <zone> <outdir> [id,id,...]
Reads tools/regions.json, downloads the Geofabrik zone extract(s), clips each city and builds <id>.db.gz + <id>.json."""
import gzip, hashlib, json, os, shutil, sqlite3, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, '..', 'data')
UA = 'NoEntryNav-CI/1.0 (github.com/sureshift/NoEntryNavigation)'


def sh(*a, **k):
    print('+', ' '.join(map(str, a)), flush=True)
    return subprocess.run(list(map(str, a)), check=k.pop('check', True), **k)


def ensure_zone(zone):
    out = os.path.join(DATA, f'zone-{zone}.pbf')
    if os.path.exists(out) and os.path.getsize(out) > 1_000_000:
        return out
    urls = [f'https://download.geofabrik.de/asia/india/{zone}-latest.osm.pbf',
            f'https://ftp5.gwdg.de/pub/misc/openstreetmap/download.geofabrik.de/asia/india/{zone}-latest.osm.pbf']
    for u in urls:
        r = sh('curl', '-L', '--fail', '--retry', '3', '--retry-delay', '10', '--connect-timeout', '30', '--max-time', '2400',
               '-A', UA, '-o', out, u, check=False)
        if r.returncode == 0 and os.path.getsize(out) > 1_000_000:
            return out
        print('::warning::download failed:', u, flush=True)
    raise SystemExit(f'::error::cannot download {zone}')


def build(r, outdir):
    os.makedirs(DATA, exist_ok=True)
    w, s, e, n = r['bbox']
    bbox = f'{w},{s},{e},{n}'
    parts = []
    for z in [r['zone']] + r.get('also', []):
        part = os.path.join(DATA, f"part-{r['id']}-{z}.pbf")
        sh('osmium', 'extract', '--bbox', bbox, '--strategy', 'complete_ways', ensure_zone(z), '-o', part, '--overwrite')
        parts.append(part)
    pbf = os.path.join(DATA, f"{r['id']}.osm.pbf")
    if len(parts) == 1:
        shutil.move(parts[0], pbf)
    else:
        sh('osmium', 'merge', *parts, '-o', pbf, '--overwrite')
        for p in parts:
            os.remove(p)
    csv = os.path.join(DATA, f"{r['id']}-places.csv")
    if os.path.exists(csv):
        os.remove(csv)
    try:
        sh(sys.executable, os.path.join(HERE, 'fetch_overture.py'), csv, bbox)
    except Exception as ex:
        print('::warning::Overture skipped:', ex, flush=True)
    db = os.path.join(DATA, f"{r['id']}.db")
    sh(sys.executable, os.path.join(HERE, 'build_data.py'), pbf, db, csv)
    con = sqlite3.connect(db)
    nodes = con.execute('select count(*) from node').fetchone()[0]
    segs = con.execute('select count(*) from seg').fetchone()[0]
    places = con.execute('select count(*) from place').fetchone()[0]
    con.close()
    if nodes < 200 or segs < 200:
        raise RuntimeError(f"{r['id']}: too little road data ({nodes} nodes)")
    gz = os.path.join(outdir, f"{r['id']}.db.gz")
    with open(db, 'rb') as i, gzip.open(gz, 'wb', compresslevel=9) as o:
        shutil.copyfileobj(i, o, 1 << 20)
    sha = hashlib.sha256(open(gz, 'rb').read()).hexdigest()
    meta = dict(id=r['id'], name=r['name'], state=r['state'], bbox=r['bbox'], size=os.path.getsize(gz),
                dbsize=os.path.getsize(db), sha256=sha, built=time.strftime('%Y-%m-%d'),
                nodes=nodes, segs=segs, places=places)
    json.dump(meta, open(os.path.join(outdir, f"{r['id']}.json"), 'w'))
    for f in (pbf, db, csv):
        if os.path.exists(f):
            os.remove(f)
    print('built', meta, flush=True)


def main():
    zone, outdir = sys.argv[1], sys.argv[2]
    only = set(sys.argv[3].split(',')) if len(sys.argv) > 3 and sys.argv[3] else None
    os.makedirs(outdir, exist_ok=True)
    regions = json.load(open(os.path.join(HERE, 'regions.json')))
    todo = [r for r in regions if r['zone'] == zone and (not only or r['id'] in only)]
    bad = []
    for r in todo:
        try:
            build(r, outdir)
        except Exception as ex:
            print(f"::error::{r['id']} failed: {ex}", flush=True)
            bad.append(r['id'])
    print('done; failed:', bad)


if __name__ == '__main__':
    main()
