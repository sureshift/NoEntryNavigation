# NoEntry Nav — offline Delhi navigation for trucks, LCVs and cars

Everything runs on the phone. No server, no internet permission.

- Roads, places and street names: built from OpenStreetMap into `delhi.db` and bundled in the APK
- Routing: on-device A* that respects one-ways, HGV bans, height/weight limits and your no-entry zones
- No-entry rules: `android/app/src/main/assets/zones.json` (edit, push, new APK is built)

## Get the APK
Push to `main`. GitHub Actions downloads OSM data, builds the database and the APK (about 10-15 min).
Download `app-debug.apk` from Releases or the run's Artifacts and install it.

## Edit no-entry rules
`zones.json` entries: name, vehicles (`truck`, `lcv`, `car`), days (1=Mon..7=Sun), start/end (24h, IST,
end earlier than start = crosses midnight), valid_from/valid_to (yyyymmdd), status (`verified` to enforce), polygon ([lon,lat] points).
The shipped zone is a SAMPLE. Replace it with real Delhi Traffic Police notified rules.

## Change the area
Edit the `--bbox` in `.github/workflows/build-apk.yml` (min_lon,min_lat,max_lon,max_lat).

## Known limits
- Turn restrictions (no-right-turn etc.) from OSM are not used yet
- Map shows roads only, no labels or buildings
- Debug-signed APK (not Play Store ready)
