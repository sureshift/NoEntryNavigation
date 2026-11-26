# NoEntry Nav — offline Delhi navigation for LCV, MCV and HCV trucks

Everything runs on the phone. No server, no internet permission.

- Roads, places and street names: built from OpenStreetMap into `delhi.db` and bundled in the APK
- Routing: on-device A* that respects one-ways, HGV bans, height/weight limits and your no-entry zones
- No-entry rules: Delhi gazette notifications in `android/app/src/main/assets/rules.json` (edit, push, new APK is built)
- Start and end points: type a start place, long-press the map, or use GPS

## Get the APK
Push to `main`. GitHub Actions downloads OSM data, builds the database and the APK (about 10-15 min).
Download `app-debug.apk` from Releases or the run's Artifacts and install it.

## No-entry rules (rules.json)
Based on the Delhi Transport Dept notifications of 3 Aug 2021 and 12 Aug 2022 and Delhi Traffic Police notification of 21 Jun 2024
(data as of 26 Nov 2026). `notifications[]` is shown in the app's Rules screen. `rules[]`:
- `kind: road` with `names` (matched against OSM road names) and `bbox` [minLon,minLat,maxLon,maxLat] limiting where the match counts
- `kind: area` with `areas[{name,bbox}]` (whole box blocked)
- `vehicles` (`lcv`,`mcv`,`hcv`), `windows` (["HH:MM","HH:MM"], end earlier than start crosses midnight, "24:00" allowed), `days` (1=Mon..7=Sun), `effective` yyyymmdd

CI runs `tools/check_rules.py` and prints how many map segments each road rule matched (warnings for rules matching nothing; those are not enforced).
Road extents and area boxes are approximate. Older 1996-2017 road notifications are not encoded yet.

## Change the area
Edit the `--bbox` in `.github/workflows/build-apk.yml` (min_lon,min_lat,max_lon,max_lat).

## Known limits
- Turn restrictions (no-right-turn etc.) from OSM are not used yet
- Map shows roads only, no labels or buildings
- Debug-signed APK (not Play Store ready)
