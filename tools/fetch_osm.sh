#!/usr/bin/env bash
# Gets an OpenStreetMap extract clipped to Delhi NCR (Delhi, Gurugram, Noida, Greater Noida, Ghaziabad), trying several mirrors until one works.
# Output: data/delhi.osm.pbf     Needs: curl, osmium-tool
set -u
BBOX="${BBOX:-76.80,28.30,77.62,28.90}"   # Delhi + Gurugram, Noida, Greater Noida, Ghaziabad (min_lon,min_lat,max_lon,max_lat)
OUT=data/delhi.osm.pbf
NORTH=data/north.pbf     # Delhi, Haryana (Gurugram)
EAST=data/east.pbf       # Uttar Pradesh (Noida, Greater Noida, Ghaziabad)
mkdir -p data
if [ -s "$OUT" ]; then echo "::notice::using cached $OUT"; exit 0; fi

MIRRORS=(
  "https://download.geofabrik.de/asia/india/northern-zone-latest.osm.pbf"
  "https://ftp5.gwdg.de/pub/misc/openstreetmap/download.geofabrik.de/india-latest.osm.pbf"
  "https://download.openstreetmap.fr/extracts/asia/india-latest.osm.pbf"
  "https://download.bbbike.org/osm/bbbike/Delhi/Delhi.osm.pbf"
)

for u in "${MIRRORS[@]}"; do
  echo "Trying $u"
  rm -f data/src.osm.pbf
  curl -L --fail --retry 2 --retry-delay 5 --connect-timeout 30 --max-time 1500 \
       -A "NoEntryNav-CI/1.0 (github.com/sureshift/NoEntryNavigation)" -o data/src.osm.pbf "$u"
  rc=$?
  if [ $rc -ne 0 ]; then echo "::warning::download failed (curl exit $rc): $u"; continue; fi
  if osmium extract --bbox "$BBOX" --strategy complete_ways data/src.osm.pbf -o "$NORTH" --overwrite && [ -s "$NORTH" ]; then
    echo "::notice::OK from $u ($(du -h "$NORTH" | cut -f1) after clipping)"
    rm -f data/src.osm.pbf
    break
  fi
  echo "::warning::could not clip data from $u"
done
[ -s "$NORTH" ] || { echo "::error::every OSM mirror failed"; exit 1; }

# Noida, Greater Noida and Ghaziabad are in Geofabrik's central zone (Uttar Pradesh)
EAST_MIRRORS=(
  "https://download.geofabrik.de/asia/india/central-zone-latest.osm.pbf"
  "https://ftp5.gwdg.de/pub/misc/openstreetmap/download.geofabrik.de/asia/india/central-zone-latest.osm.pbf"
)
for u in "${EAST_MIRRORS[@]}"; do
  echo "Trying $u"
  rm -f data/src.osm.pbf
  if curl -L --fail --retry 2 --retry-delay 5 --connect-timeout 30 --max-time 1700 \
       -A "NoEntryNav-CI/1.0 (github.com/sureshift/NoEntryNavigation)" -o data/src.osm.pbf "$u" \
     && osmium extract --bbox "$BBOX" --strategy complete_ways data/src.osm.pbf -o "$EAST" --overwrite && [ -s "$EAST" ]; then
    echo "::notice::east OK from $u ($(du -h "$EAST" | cut -f1) after clipping)"
    rm -f data/src.osm.pbf
    break
  fi
  echo "::warning::could not get east zone from $u"
done
if [ -s "$EAST" ]; then
  osmium merge "$NORTH" "$EAST" -o "$OUT" --overwrite
else
  echo "::warning::east zone unavailable; Noida/Ghaziabad roads will be missing"
  cp "$NORTH" "$OUT"
fi
rm -f "$NORTH" "$EAST"
echo "::notice::final extract $(du -h "$OUT" | cut -f1)"
exit 0
