/**
 * NoEntry Nav - community reports backend (free: Google Sheets + Google Apps Script).
 *
 * Drivers' phones send new no-entry reports here (status = Pending). You review them in the sheet and change
 * the Status cell to Approved (or Rejected). Every phone downloads only the Approved rows.
 * A road reported by AUTO_APPROVE_AT different phones is approved automatically (set to 0 to switch this off).
 */
const SHEET_NAME = 'Reports';
const AUTO_APPROVE_AT = 3;        // distinct drivers needed for automatic approval; 0 = manual approval only
const NEAR_M = 150;               // two reports whose ends are within this distance are the same road
const MAX_PER_DEVICE_PER_DAY = 30;
const HEADERS = ['id', 'received', 'name', 'a_lat', 'a_lon', 'b_lat', 'b_lon', 'vehicles', 'windows',
                 'confirmations', 'devices', 'status', 'note'];

function sheet_() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sh = ss.getSheetByName(SHEET_NAME);
  if (!sh) {
    sh = ss.insertSheet(SHEET_NAME);
    sh.appendRow(HEADERS);
    sh.setFrozenRows(1);
    sh.getRange('L2:L5000').setDataValidation(
      SpreadsheetApp.newDataValidation().requireValueInList(['Pending', 'Approved', 'Rejected'], true).build());
  }
  return sh;
}

function out_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

function doGet(e) {
  const p = (e && e.parameter) || {};
  try {
    if (p.action === 'submit') return out_(submit_(p));
    return out_(approved_());
  } catch (err) {
    return out_({ ok: false, error: String(err) });
  }
}

function dist_(lat1, lon1, lat2, lon2) {
  const R = 6371000, r = Math.PI / 180;
  const dl = (lat2 - lat1) * r, dn = (lon2 - lon1) * r;
  const a = Math.sin(dl / 2) ** 2 + Math.cos(lat1 * r) * Math.cos(lat2 * r) * Math.sin(dn / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(a));
}

function submit_(p) {
  const r = JSON.parse(p.d || '{}');
  const dev = String(p.dev || '').slice(0, 40);
  if (!r.id || !r.a || !r.b || !dev) return { ok: false, error: 'bad report' };
  const ok = (v) => v[0] > 28.0 && v[0] < 29.2 && v[1] > 76.5 && v[1] < 77.8;
  if (!ok(r.a) || !ok(r.b)) return { ok: false, error: 'outside NCR' };
  const veh = (r.vehicles || []).filter((v) => ['lcv', 'mcv', 'hcv'].indexOf(v) >= 0);
  if (!veh.length || !(r.windows || []).length) return { ok: false, error: 'missing vehicles or hours' };

  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const sh = sheet_();
    const last = sh.getLastRow();
    const rows = last > 1 ? sh.getRange(2, 1, last - 1, HEADERS.length).getValues() : [];
    const dayAgo = Date.now() - 86400000;
    let mine = 0;
    for (let i = 0; i < rows.length; i++) {
      if (String(rows[i][0]) === String(r.id)) return { ok: true, dup: true };
      if (String(rows[i][10]).indexOf(dev) >= 0 && new Date(rows[i][1]).getTime() > dayAgo) mine++;
    }
    if (mine >= MAX_PER_DEVICE_PER_DAY) return { ok: false, error: 'too many reports today' };
    // same road already reported? then this is a confirmation
    for (let i = 0; i < rows.length; i++) {
      const x = rows[i];
      if (x[11] === 'Rejected') continue;
      const same = (dist_(x[3], x[4], r.a[0], r.a[1]) < NEAR_M && dist_(x[5], x[6], r.b[0], r.b[1]) < NEAR_M) ||
                   (dist_(x[3], x[4], r.b[0], r.b[1]) < NEAR_M && dist_(x[5], x[6], r.a[0], r.a[1]) < NEAR_M);
      if (!same) continue;
      const devs = String(x[10]).split(',').filter(String);
      if (devs.indexOf(dev) < 0) devs.push(dev);
      const row = i + 2;
      sh.getRange(row, 10).setValue(devs.length);
      sh.getRange(row, 11).setValue(devs.join(','));
      if (AUTO_APPROVE_AT > 0 && devs.length >= AUTO_APPROVE_AT && x[11] === 'Pending') {
        sh.getRange(row, 12).setValue('Approved');
        sh.getRange(row, 13).setValue('Auto-approved: ' + devs.length + ' drivers');
      }
      return { ok: true, confirmed: devs.length };
    }
    sh.appendRow([r.id, new Date(), String(r.name || '').slice(0, 80), r.a[0], r.a[1], r.b[0], r.b[1],
                  veh.join(','), JSON.stringify(r.windows), 1, dev, 'Pending', '']);
    return { ok: true, pending: true };
  } finally {
    lock.releaseLock();
  }
}

function approved_() {
  const sh = sheet_();
  const last = sh.getLastRow();
  const rows = last > 1 ? sh.getRange(2, 1, last - 1, HEADERS.length).getValues() : [];
  const list = [];
  rows.forEach((x) => {
    if (x[11] !== 'Approved') return;
    let wins;
    try { wins = JSON.parse(x[8]); } catch (err) { return; }
    list.push({
      id: String(x[0]), name: String(x[2]), ts: new Date(x[1]).getTime(),
      a: [Number(x[3]), Number(x[4])], b: [Number(x[5]), Number(x[6])],
      vehicles: String(x[7]).split(',').map((s) => s.trim()).filter(String), windows: wins
    });
  });
  return { ok: true, updated: new Date().toISOString(), noentry_reports: list };
}
