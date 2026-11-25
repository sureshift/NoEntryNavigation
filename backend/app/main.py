import json, os
from datetime import datetime
from zoneinfo import ZoneInfo

import httpx
from fastapi import FastAPI, HTTPException
from psycopg_pool import ConnectionPool
from pydantic import BaseModel

IST = ZoneInfo("Asia/Kolkata")
VALHALLA = os.environ["VALHALLA_URL"]
pool = ConnectionPool(os.environ["DATABASE_URL"], min_size=1, max_size=5)
app = FastAPI(title="NoEntry Nav API")


class LatLon(BaseModel):
    lat: float
    lon: float


class RouteReq(BaseModel):
    origin: LatLon
    destination: LatLon
    vehicle_class: str = "truck"          # truck | lcv | bus | car
    height: float = 3.8
    weight: float = 12.0
    length: float = 9.0
    depart_at: datetime | None = None     # naive = IST
    language: str = "en-US"


def active_polygons(vclass: str, at: datetime):
    with pool.connection() as c:
        rows = c.execute(
            "SELECT id, name, ST_AsGeoJSON(geom) FROM active_zones(%s, %s)",
            (vclass, at.replace(tzinfo=None)),
        ).fetchall()
    out = []
    for zid, name, gj in rows:
        ring = json.loads(gj)["coordinates"][0]     # [[lon,lat],...]
        out.append({"id": zid, "name": name, "ring": ring})
    return out


@app.get("/health")
def health():
    return {"ok": True}


@app.get("/zones/active")
def zones(vehicle_class: str = "truck", at: datetime | None = None):
    at = at or datetime.now(IST)
    return active_polygons(vehicle_class, at)


@app.post("/route")
async def route(r: RouteReq):
    at = r.depart_at or datetime.now(IST)
    zs = active_polygons(r.vehicle_class, at)
    costing = "truck" if r.vehicle_class in ("truck", "lcv", "bus") else "auto"
    body = {
        "locations": [
            {"lat": r.origin.lat, "lon": r.origin.lon},
            {"lat": r.destination.lat, "lon": r.destination.lon},
        ],
        "costing": costing,
        "directions_options": {"units": "kilometers", "language": r.language},
        "exclude_polygons": [z["ring"] for z in zs],
        "alternates": 1,
    }
    if costing == "truck":
        body["costing_options"] = {"truck": {"height": r.height, "weight": r.weight, "length": r.length}}
    async with httpx.AsyncClient(timeout=30) as cx:
        resp = await cx.post(f"{VALHALLA}/route", json=body)
    if resp.status_code != 200:
        raise HTTPException(resp.status_code, resp.json().get("error", "routing failed"))
    data = resp.json()
    data["applied_restrictions"] = [{"id": z["id"], "name": z["name"]} for z in zs]
    return data
