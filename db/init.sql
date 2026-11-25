CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE restriction_zone (
  id            BIGSERIAL PRIMARY KEY,
  name          TEXT NOT NULL,
  geom          GEOMETRY(Polygon, 4326) NOT NULL,   -- for roads, buffer the line (~15 m) into a polygon
  vehicle_class TEXT[] NOT NULL DEFAULT '{truck}',  -- truck, lcv, tempo, bus, car
  days_of_week  SMALLINT[] NOT NULL DEFAULT '{1,2,3,4,5,6,7}',  -- ISO: 1=Mon..7=Sun
  start_time    TIME NOT NULL DEFAULT '00:00',
  end_time      TIME NOT NULL DEFAULT '23:59',      -- if end_time < start_time the window crosses midnight
  valid_from    DATE,
  valid_to      DATE,
  source        TEXT,                               -- notification no. / URL
  confidence    SMALLINT NOT NULL DEFAULT 50,       -- 0-100
  status        TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','verified','rejected')),
  verified_at   TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX restriction_zone_geom_gix ON restriction_zone USING GIST (geom);

CREATE TABLE restriction_report (
  id          BIGSERIAL PRIMARY KEY,
  zone_id     BIGINT REFERENCES restriction_zone(id),
  location    GEOMETRY(Point, 4326) NOT NULL,
  note        TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Zones active for a vehicle class at a given local (Asia/Kolkata) time.
CREATE OR REPLACE FUNCTION active_zones(vclass TEXT, at_ts TIMESTAMP)
RETURNS SETOF restriction_zone LANGUAGE sql STABLE AS $$
  SELECT * FROM restriction_zone z
  WHERE z.status = 'verified'
    AND vclass = ANY(z.vehicle_class)
    AND (z.valid_from IS NULL OR at_ts::date >= z.valid_from)
    AND (z.valid_to   IS NULL OR at_ts::date <= z.valid_to)
    AND (
      (z.start_time <= z.end_time
        AND EXTRACT(ISODOW FROM at_ts)::smallint = ANY(z.days_of_week)
        AND at_ts::time BETWEEN z.start_time AND z.end_time)
      OR
      (z.start_time > z.end_time AND (
        (EXTRACT(ISODOW FROM at_ts)::smallint = ANY(z.days_of_week) AND at_ts::time >= z.start_time)
        OR
        (EXTRACT(ISODOW FROM at_ts - INTERVAL '1 day')::smallint = ANY(z.days_of_week) AND at_ts::time <= z.end_time)
      ))
    );
$$;

-- Sample zone (replace with real notified data). Verified so you can test immediately.
INSERT INTO restriction_zone (name, geom, vehicle_class, start_time, end_time, source, confidence, status)
VALUES ('SAMPLE: Connaught Place truck ban',
  ST_GeomFromText('POLYGON((77.2000 28.6250,77.2250 28.6250,77.2250 28.6400,77.2000 28.6400,77.2000 28.6250))',4326),
  '{truck,lcv}', '07:00', '22:00', 'sample', 10, 'verified');
