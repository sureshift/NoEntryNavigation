#!/usr/bin/env python3
"""Download Overture Maps places (open data, CDLA-Permissive / ODbL sources) for Delhi NCR into a CSV.
Usage: fetch_overture.py out.csv    (needs: pip install duckdb). Failure is non-fatal for the build."""
import csv, json, sys, urllib.request

BBOX = (76.80, 28.35, 77.60, 28.95)   # minlon, minlat, maxlon, maxlat

def latest():
    with urllib.request.urlopen("https://stac.overturemaps.org/catalog.json", timeout=30) as r:
        return json.load(r)["latest"]

def main(out):
    import duckdb
    rel = latest()
    print("Overture release", rel)
    con = duckdb.connect()
    con.execute("INSTALL httpfs; LOAD httpfs; INSTALL spatial; LOAD spatial; SET s3_region='us-west-2';")
    x0, y0, x1, y1 = BBOX
    rows = con.execute(f"""
        SELECT p.names.primary AS name, p.categories.primary AS cat,
               ST_Y(p.geometry) AS lat, ST_X(p.geometry) AS lon,
               p.addresses[1].freeform AS street, p.addresses[1].locality AS loc, p.addresses[1].postcode AS pc,
               p.phones[1] AS phone, p.confidence
        FROM read_parquet('s3://overturemaps-us-west-2/release/{rel}/theme=places/type=place/*', hive_partitioning=1) AS p
        WHERE p.bbox.xmin BETWEEN {x0} AND {x1} AND p.bbox.ymin BETWEEN {y0} AND {y1}
          AND p.names.primary IS NOT NULL AND p.confidence >= 0.6
    """).fetchall()
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        for r in rows:
            addr = ", ".join(str(v) for v in (r[4], r[5], r[6]) if v)
            w.writerow([r[0], r[1] or "", r[2], r[3], addr, r[7] or ""])
    print(len(rows), "places written")

if __name__ == "__main__":
    main(sys.argv[1])
