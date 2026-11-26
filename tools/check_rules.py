#!/usr/bin/env python3
"""Validate rules.json and report how many map segments each road rule matches in delhi.db.
usage: check_rules.py rules.json delhi.db
Exit 1 on schema errors. Unmatched road rules only produce warnings (the app will not enforce them)."""
import json, re, sqlite3, sys

rules_path, db_path = sys.argv[1], sys.argv[2]
d = json.load(open(rules_path, encoding="utf-8"))
errors = []
VEH = {"lcv", "mcv", "hcv"}
TIME = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$|^24:00$")

nids = set()
for n in d.get("notifications", []):
    for k in ("id", "kind", "title", "authority", "ref", "summary"):
        if not n.get(k) and k != "ref":
            errors.append(f"notification {n.get('id')}: missing {k}")
    if n.get("kind") not in ("timing", "entry", "advisory"):
        errors.append(f"notification {n.get('id')}: bad kind")
    nids.add(n.get("id"))

def bbox_ok(b):
    return isinstance(b, list) and len(b) == 4 and b[0] < b[2] and b[1] < b[3] and 76.8 < b[0] < 77.4 and 28.3 < b[1] < 28.95

con = sqlite3.connect(db_path)
cur = con.cursor()
ids = set()
for r in d.get("rules", []):
    rid = r.get("id")
    if rid in ids: errors.append(f"duplicate rule id {rid}")
    ids.add(rid)
    if r.get("notification") not in nids: errors.append(f"{rid}: unknown notification")
    if not set(r.get("vehicles", [])) <= VEH or not r.get("vehicles"): errors.append(f"{rid}: bad vehicles")
    for w in r.get("windows", []):
        if len(w) != 2 or not all(TIME.match(x) for x in w): errors.append(f"{rid}: bad window {w}")
    if not r.get("windows"): errors.append(f"{rid}: no windows")
    if r.get("kind") == "road":
        if not r.get("names") or not bbox_ok(r.get("bbox")):
            errors.append(f"{rid}: road rule needs names and a valid bbox"); continue
        lon0, lat0, lon1, lat1 = r["bbox"]
        n = 0
        for nm in r["names"]:
            for sid, a, b in cur.execute("SELECT id,a,b FROM seg WHERE name LIKE ?", (f"%{nm}%",)):
                pa = cur.execute("SELECT lat,lon FROM node WHERE id=?", (a,)).fetchone()
                pb = cur.execute("SELECT lat,lon FROM node WHERE id=?", (b,)).fetchone()
                if not pa or not pb: continue
                la, lo = (pa[0] + pb[0]) / 2, (pa[1] + pb[1]) / 2
                if lat0 <= la <= lat1 and lon0 <= lo <= lon1: n += 1
        tag = "notice" if n else "warning"
        print(f"::{tag}::{rid} {r['name']}: {n} road segments matched on the map" + ("" if n else " - rule will NOT be enforced; fix names/bbox"))
    elif r.get("kind") == "area":
        for a in r.get("areas", []):
            if not bbox_ok(a.get("bbox")): errors.append(f"{rid}: bad area bbox {a.get('name')}")
        print(f"::notice::{rid} {r['name']}: {len(r.get('areas', []))} areas")
    else:
        errors.append(f"{rid}: kind must be road or area")

for e in errors: print(f"::error::{e}")
sys.exit(1 if errors else 0)
