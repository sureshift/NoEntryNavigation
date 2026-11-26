#!/usr/bin/env bash
# Gets an OpenStreetMap extract clipped to Delhi, trying several mirrors until one works.
# Output: data/delhi.osm.pbf     Needs: curl, osmium-tool
set -u
BBOX="${BBOX:-76.83,28.40,77.35,28.89}"   # min_lon,min_lat,max_lon,max_lat
OUT=data/delhi.osm.pbf
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
  if osmium extract --bbox "$BBOX" --strategy complete_ways data/src.osm.pbf -o "$OUT" --overwrite && [ -s "$OUT" ]; then
    echo "::notice::OK from $u ($(du -h "$OUT" | cut -f1) after clipping)"
    rm -f data/src.osm.pbf
    exit 0
  fi
  echo "::warning::could not clip data from $u"
done
echo "::error::every OSM mirror failed"
exit 1
