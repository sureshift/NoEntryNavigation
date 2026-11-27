# ROUTIQ — truck navigation with no-entry rules for LCV, MCV and HCV

Routing runs on the phone. Maps come from OpenStreetMap (roads, places, chowks) and Overture Maps (extra places in Delhi NCR),
all free and open source. Nothing here uses Google data.

## How the maps work
- **Delhi NCR** is inside the app (`delhi.db`, built by CI) so the first start works offline.
- **Rest of India** is cut into 0.5° tiles (`tools/build_tiles.py`, workflow *Build map tiles*, hosted as release files `tiles-lat*`, index `feed/tiles.json`).
  - L1 tile: motorway to tertiary roads and towns (about 0.6 MB). L2 tile: every road and place (about 3 MB).
- **Highway backbone** (`tools/build_backbone.py`, release `backbone`, index `feed/backbone.json`, about 4 MB, downloaded once) is a skeleton of India's highways. It is only used to decide which tiles a trip needs.
- When you enter a start and a destination that the loaded map does not cover, the app:
  1. plans the corridor on the backbone,
  2. downloads the start area in full detail first (it shows at once), then the tiles along the route in driving order, then the destination area,
  3. joins them into one map file (`TripMaps.mergeAdd`) and routes on it with the normal engine.
- After arrival or when the route is cancelled, the app keeps only the full-detail tile where the driver is and deletes everything else. There is no manual map management.
- City and town search works offline for every Indian city (`assets/cities.json`, built by workflow *Build city list*).

## No-entry rules (`android/app/src/main/assets/rules.json`)
Delhi Transport Dept notifications of 3 Aug 2021 and 12 Aug 2022 and Delhi Traffic Police notification of 21 Jun 2024.
`notifications[]` is shown in the Rules screen. `rules[]`:
- `kind: road` with `names` (matched against OSM road names) and `bbox` [minLon,minLat,maxLon,maxLat]
- `kind: area` with `areas[{name,bbox}]`
- `vehicles` (`lcv`,`mcv`,`hcv`), `windows` (["HH:MM","HH:MM"], end earlier than start crosses midnight), `days` (1=Mon..7=Sun), `effective` yyyymmdd

CI runs `tools/check_rules.py` against the Delhi map. Outside Delhi NCR there are no gazette rules; there the app routes by road class, HGV bans, height and weight limits.

## Driver reports
Drivers mark a no-entry road (start point, end point, vehicles, hours). With *Send to admin* on (default), reports go to a private Google Sheet through a signed request
(`tools/community_server`, see its README). Reports are approved by an admin or automatically when enough different drivers agree (trust score). Approved reports show as *Verified by admin*.
Reports can also be shared as a route link (WhatsApp / share sheet).

## Build and install
Push to `main`; GitHub Actions builds `ROUTIQ.apk` and publishes it as a release (`build-N`). Every build is signed with the same key
(`android/signing/routiq.jks`, or the repository secrets `KEYSTORE_B64` and `KEYSTORE_PASS` when set), so a new build installs over the old one.
If you installed an older build that was signed with a different key, uninstall it once.

Repository secrets for driver reports: `COMMUNITY_URL`, `COMMUNITY_SECRET`.

## Workflows
| Workflow | Purpose |
|---|---|
| Build APK | app + Delhi map + signed release |
| Build map tiles | all-India tiles and `feed/tiles.json` |
| Build highway backbone | `feed/backbone.json` |
| Build city list | `assets/cities.json` |
| Build city packs / update-feed | optional older city packs, gazette feed |

## Known limits
- Turn restrictions from OSM are not used yet; live traffic is not included.
- Outside Delhi NCR the app has no no-entry rules, only driver reports.
- Between cities only main roads (L1) are on the map; full detail is loaded at the start, the destination and in Delhi NCR.
- Map data comes from OpenStreetMap volunteers and can have gaps or wrong names.
