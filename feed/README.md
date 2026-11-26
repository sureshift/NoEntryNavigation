# Online update feed

The app downloads `feed/updates.json` (raw.githubusercontent.com) when it opens (at most every 6 hours) and on
Rules > Check for updates. It works fully offline without it.

- `notifications[]`: same shape as in `android/app/src/main/assets/rules.json` (use kind `timing`, `entry`, `advisory` or `temporary`)
- `rules[]`: same shape as `rules.json` rules. A rule with the same `id` replaces the built-in one. Add `"until": yyyymmdd` for a temporary
  no-entry or diversion; expired rules are ignored. Online rules are limited to areas of 0.06 degrees and roads of 0.3 degrees.
- `advisories[]`: information items shown on the Gazette tab (not used for routing). Filled automatically by
  `.github/workflows/update-feed.yml` from the Delhi Traffic Police site, and you can add your own.

Routing only changes for entries in `rules[]`. Free-text advisories from the police site are shown to the driver but are not
turned into routing rules automatically, because their wording (landmarks, "towards X") cannot be mapped to roads reliably.
