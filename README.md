# NoEntry Nav (Delhi)

1. `sudo apt install osmium-tool && ./scripts/prepare-delhi.sh`
2. `cp .env.example .env && docker compose up -d --build`
3. Wait for tiles: `docker logs -f ne_valhalla` (Delhi: a few minutes)
4. Test:
```
curl -s localhost:8000/zones/active?vehicle_class=truck
curl -s -X POST localhost:8000/route -H 'content-type: application/json' -d '{
 "origin":{"lat":28.5562,"lon":77.1000},
 "destination":{"lat":28.6692,"lon":77.2300},
 "vehicle_class":"truck","depart_at":"2026-11-26T09:00:00"}' | head -c 600
```
Look for `applied_restrictions` in the response and confirm the route avoids the sample CP polygon.

## Android app (android/)
Kotlin + Compose + MapLibre. Built by GitHub Actions (no Android Studio needed):
1. Create a GitHub repo, push this whole folder to `main`.
2. Actions tab -> "Build APK" runs automatically (about 5-8 min). 
3. Download `app-debug.apk` from the run's Artifacts or from Releases, copy to the phone, install (allow "install unknown apps").
4. In the app tap **Server**, enter your API URL (Cloudflare tunnel https URL, or http://LAN-IP:8000 on same Wi-Fi).

Local build alternative: open `android/` in Android Studio and Run.
