#!/usr/bin/env bash
# Downloads India north-zone extract, clips to Delhi NCR, places it where Valhalla builds tiles.
# Needs: curl, osmium-tool (apt install osmium-tool)
set -euo pipefail
cd "$(dirname "$0")/../data"
mkdir -p valhalla
[ -f northern-zone.osm.pbf ] || curl -L -o northern-zone.osm.pbf \
  https://download.geofabrik.de/asia/india/northern-zone-latest.osm.pbf
# Delhi NCR bbox: min_lon,min_lat,max_lon,max_lat
osmium extract --bbox 76.80,28.35,77.65,28.95 --strategy complete_ways \
  northern-zone.osm.pbf -o valhalla/delhi.osm.pbf --overwrite
ls -lh valhalla/delhi.osm.pbf
echo "Now: docker compose up -d valhalla   (first start builds tiles, watch: docker logs -f ne_valhalla)"
