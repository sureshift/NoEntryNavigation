# Community reports with Google Sheets (free, private)

## One-time setup
1. Create a Google Sheet. **Never share it.** Phones never see it; only this script touches it.
2. Extensions > Apps Script. Paste `Code.gs`, Save.
3. Project Settings (gear) > Script properties > add `SECRET` = a long random string (run `python3 tools/community_server/make_config.py --new-secret`).
4. Triggers (clock icon) > Add trigger: function `onStatusEdit`, event source "From spreadsheet", event type "On edit". Authorise.
5. Deploy > New deployment > Web app: Execute as **Me**, access **Anyone**. Copy the `/exec` URL.
6. GitHub repo > Settings > Secrets and variables > Actions: add `COMMUNITY_URL` (the /exec URL) and `COMMUNITY_SECRET` (same SECRET). The build puts them into the app; they are not in the repo.

## Approval
- Every report arrives as **Pending** in sheet `Reports`. Change `status` to `Approved` or `Rejected` any time. You may fix name/vehicles/windows first.
- `MODE = 'AUTO'`: a Pending road is also approved automatically when its trust score is at least `THRESHOLD` (3.0), at least 3 different phones reported it, and the first and last report are 1+ hour apart.
  Score = sum of phone weights. A phone weighs 1.0 (0.5 during its first 12 hours), +0.25 per earlier approved report (max +1), -0.5 per rejected report (min 0.1). Phones are listed in sheet `Devices`.
- `MODE = 'MANUAL'`: only you approve. Rejected rows are never auto-approved. Setting a row to Rejected removes it from phones at their next update.

## What can and cannot be hidden
Nothing inside an app is truly secret: a determined person can extract the URL and key from the APK. So the design makes them useless: the sheet itself is never exposed, the script can only append Pending rows (no edit/delete), the script rejects unsigned/old requests, caps reports per phone per day, and nothing goes live without your approval or a multi-phone, multi-hour score. If you ever suspect leakage, change `SECRET` and the app build, and old apps lose access.
