# Community reports with Google Sheets (free)

1. Create a new Google Sheet (name it "NoEntry Nav reports").
2. Extensions > Apps Script. Delete the sample code, paste `Code.gs`, Save.
3. Deploy > New deployment > type **Web app**. Execute as: **Me**. Who has access: **Anyone**. Deploy and authorise.
4. Copy the Web app URL (ends with `/exec`) and put it in `android/app/src/main/assets/community_config.json`
   as `{"endpoint": "https://script.google.com/macros/s/.../exec"}`. Build the app.

How it works
- A driver saves a report: it works on his phone at once, and (if he leaves "Send to Sure Shift" on) it is sent to the sheet as **Pending**.
- Open the sheet "Reports". Change **status** to `Approved` or `Rejected`. You can fix the name, vehicles or hours in the cells first.
- Phones fetch only Approved rows (when the app checks for updates) and show them as "Verified by Sure Shift".
- If 3 different drivers report the same road (ends within 150 m) it is approved automatically. Change
  `AUTO_APPROVE_AT` in the script (0 = approve manually only).
- To remove a wrong road later, set its status to `Rejected`; phones drop it on the next update.
