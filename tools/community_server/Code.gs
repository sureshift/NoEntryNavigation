/**
 * NoEntry Nav - community reports backend (free: Google Sheets + Google Apps Script).
 *
 * SECURITY MODEL
 *  - Keep this spreadsheet PRIVATE (never share it). The script runs as you, so phones never touch the sheet:
 *    they can only call this web app, and it can only (a) append a validated report as Pending, (b) list Approved rows.
 *    There is no API to edit or delete anything.
 *  - Every request must carry an HMAC signature made with SECRET (Project Settings > Script properties > SECRET).
 *    Requests without a valid, fresh signature get "denied". Without the app, nobody can read or write.
 *
 * APPROVAL
 *  - MODE 'MANUAL': only you approve (change the status cell to Approved/Rejected).
 *  - MODE 'AUTO'  : you can still approve/reject any row, and a Pending row is also approved by itself once its
 *    trust SCORE reaches THRESHOLD (see score_ below). Rejected rows are never auto-approved.
 */
const MODE = 'AUTO';              // 'AUTO' (score + admin) or 'MANUAL' (admin only)
const THRESHOLD = 3.0;            // trust score needed for auto approval
const MIN_DEVICES = 3;            // and at least this many different phones
const MIN_SPREAD_HOURS = 1;       // and the first and last confirmation at least this far apart
const NEAR_M = 150;               // reports whose two ends are within this distance are the same road
const MAX_PER_DEVICE_PER_DAY = 20;
const MAX_LEN_KM = 8;             // longest stretch accepted
const MAX_AGE_SEC = 600;          // signed requests older than this are refused

const REPORTS = 'Reports', DEVICES = 'Devices';
const RH = ['id', 'received', 'name', 'a_lat', 'a_lon', 'b_lat', 'b_lon', 'vehicles', 'windows',
            'score', 'devices', 'status', 'note'];
const DH = ['device', 'first_seen', 'reports', 'approved', 'rejected'];

function sh_(name, headers) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let s = ss.getSheetByName(name);
  if (!s) { s = ss.insertSheet(name); s.appendRow(headers); s.setFrozenRows(1); }
  return s;
}
function out_(o) { return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON); }

function hex_(bytes) { return bytes.map(function (b) { return ('0' + (b & 0xff).toString(16)).slice(-2); }).join(''); }
function authOk_(p) {
  const secret = PropertiesService.getScriptProperties().getProperty('SECRET');
  if (!secret || !p.sig || !p.t) return false;
  const age = Math.abs(Date.now() / 1000 - Number(p.t));
  if (!(age <= MAX_AGE_SEC)) return false;
  const base = [p.action || '', p.t, p.dev || '', p.d || ''].join('|');
  const want = hex_(Utilities.computeHmacSha256Signature(base, secret));
  return want === String(p.sig).toLowerCase();
}

function doGet(e) {
  const p = (e && e.parameter) || {};
  try {
    if (!authOk_(p)) return out_({ ok: false, error: 'denied' });
    if (p.action === 'submit') return out_(submit_(p));
    if (p.action === 'list') return out_(approved_());
    return out_({ ok: false, error: 'denied' });
  } catch (err) {
    return out_({ ok: false, error: 'error' });
  }
}

function dist_(lat1, lon1, lat2, lon2) {
  const R = 6371000, r = Math.PI / 180, dl = (lat2 - lat1) * r, dn = (lon2 - lon1) * r;
  const a = Math.sin(dl / 2) * Math.sin(dl / 2) + Math.cos(lat1 * r) * Math.cos(lat2 * r) * Math.sin(dn / 2) * Math.sin(dn / 2);
  return 2 * R * Math.asin(Math.sqrt(a));
}

// ---- device reputation ----
function deviceRow_(dev, create) {
  const s = sh_(DEVICES, DH), last = s.getLastRow();
  const ids = last > 1 ? s.getRange(2, 1, last - 1, 1).getValues() : [];
  for (let i = 0; i < ids.length; i++) if (String(ids[i][0]) === dev) return i + 2;
  if (!create) return 0;
  s.appendRow([dev, new Date(), 0, 0, 0]);
  return s.getLastRow();
}
function weight_(dev) {
  const r = deviceRow_(dev, false);
  if (!r) return 0.5;
  const v = sh_(DEVICES, DH).getRange(r, 1, 1, 5).getValues()[0];
  const ageH = (Date.now() - new Date(v[1]).getTime()) / 3600000;
  let w = 1.0;
  if (ageH < 12) w = 0.5;                                  // brand-new phones count half
  w += Math.min(1.0, 0.25 * Number(v[3]));                 // +0.25 per approved report (max +1)
  w -= 0.5 * Number(v[4]);                                 // -0.5 per rejected report
  return Math.max(0.1, Math.min(2.0, w));
}
function bump_(dev, col) {
  const r = deviceRow_(dev, true), s = sh_(DEVICES, DH);
  s.getRange(r, col).setValue(Number(s.getRange(r, col).getValue()) + 1);
}

// Score = sum of the weights of the different phones that reported the road.
function score_(devs) {
  let t = 0;
  for (let i = 0; i < devs.length; i++) t += weight_(devs[i].d);
  return Math.round(t * 100) / 100;
}
function maybeApprove_(sheet, row, devs, status) {
  if (MODE !== 'AUTO' || status !== 'Pending') return status;
  const sc = score_(devs);
  sheet.getRange(row, 10).setValue(sc);
  const times = devs.map(function (d) { return d.t; });
  const spread = (Math.max.apply(null, times) - Math.min.apply(null, times)) / 3600000;
  if (sc >= THRESHOLD && devs.length >= MIN_DEVICES && spread >= MIN_SPREAD_HOURS) {
    sheet.getRange(row, 12).setValue('Approved');
    sheet.getRange(row, 13).setValue('Auto-approved: score ' + sc + ', ' + devs.length + ' phones');
    devs.forEach(function (d) { bump_(d.d, 4); });
    return 'Approved';
  }
  return status;
}

function submit_(p) {
  const r = JSON.parse(p.d || '{}');
  const dev = String(p.dev || '').replace(/[^A-Za-z0-9-]/g, '').slice(0, 40);
  if (!r.id || !r.a || !r.b || !dev) return { ok: false, error: 'bad' };
  const inNcr = function (v) { return v[0] > 28.0 && v[0] < 29.2 && v[1] > 76.5 && v[1] < 77.8; };
  if (!inNcr(r.a) || !inNcr(r.b)) return { ok: false, error: 'bad' };
  if (dist_(r.a[0], r.a[1], r.b[0], r.b[1]) > MAX_LEN_KM * 1000) return { ok: false, error: 'bad' };
  const veh = (r.vehicles || []).filter(function (v) { return ['lcv', 'mcv', 'hcv'].indexOf(v) >= 0; });
  if (!veh.length || !(r.windows || []).length) return { ok: false, error: 'bad' };

  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const sh = sh_(REPORTS, RH), last = sh.getLastRow();
    const rows = last > 1 ? sh.getRange(2, 1, last - 1, RH.length).getValues() : [];
    const dayAgo = Date.now() - 86400000;
    let mine = 0;
    for (let i = 0; i < rows.length; i++) {
      if (String(rows[i][0]) === String(r.id)) return { ok: true, dup: true };
      if (String(rows[i][10]).indexOf(dev) >= 0 && new Date(rows[i][1]).getTime() > dayAgo) mine++;
    }
    if (mine >= MAX_PER_DEVICE_PER_DAY) return { ok: false, error: 'limit' };
    deviceRow_(dev, true);
    bump_(dev, 3);
    const now = Date.now();
    for (let i = 0; i < rows.length; i++) {
      const x = rows[i];
      if (x[11] === 'Rejected') continue;
      const same = (dist_(x[3], x[4], r.a[0], r.a[1]) < NEAR_M && dist_(x[5], x[6], r.b[0], r.b[1]) < NEAR_M) ||
                   (dist_(x[3], x[4], r.b[0], r.b[1]) < NEAR_M && dist_(x[5], x[6], r.a[0], r.a[1]) < NEAR_M);
      if (!same) continue;
      let devs = [];
      try { devs = JSON.parse(x[10]); } catch (err) { devs = []; }
      if (!devs.some(function (d) { return d.d === dev; })) devs.push({ d: dev, t: now });
      const row = i + 2;
      sh.getRange(row, 11).setValue(JSON.stringify(devs));
      sh.getRange(row, 10).setValue(score_(devs));
      maybeApprove_(sh, row, devs, x[11]);
      return { ok: true, confirmed: devs.length };
    }
    const devs = [{ d: dev, t: now }];
    sh.appendRow([r.id, new Date(), String(r.name || '').slice(0, 80), r.a[0], r.a[1], r.b[0], r.b[1],
                  veh.join(','), JSON.stringify(r.windows), score_(devs), JSON.stringify(devs), 'Pending', '']);
    return { ok: true, pending: true };
  } finally { lock.releaseLock(); }
}

function approved_() {
  const sh = sh_(REPORTS, RH), last = sh.getLastRow();
  const rows = last > 1 ? sh.getRange(2, 1, last - 1, RH.length).getValues() : [];
  const list = [];
  rows.forEach(function (x) {
    if (x[11] !== 'Approved') return;
    let wins; try { wins = JSON.parse(x[8]); } catch (err) { return; }
    list.push({ id: String(x[0]), name: String(x[2]), ts: new Date(x[1]).getTime(),
                a: [Number(x[3]), Number(x[4])], b: [Number(x[5]), Number(x[6])],
                vehicles: String(x[7]).split(',').map(function (s) { return s.trim(); }).filter(String), windows: wins });
  });
  return { ok: true, updated: new Date().toISOString(), noentry_reports: list };
}

/**
 * Installable trigger (see README): when YOU change a status by hand, the phones that reported it
 * gain (Approved) or lose (Rejected) reputation. Auto-approvals are counted in maybeApprove_.
 */
function onStatusEdit(e) {
  const s = e.range.getSheet();
  if (s.getName() !== REPORTS || e.range.getColumn() !== 12 || e.range.getRow() < 2) return;
  const v = e.value, old = e.oldValue;
  if (v === old || (v !== 'Approved' && v !== 'Rejected')) return;
  if (v === 'Approved' && String(s.getRange(e.range.getRow(), 13).getValue()).indexOf('Auto-approved') === 0) return;
  let devs = []; try { devs = JSON.parse(s.getRange(e.range.getRow(), 11).getValue()); } catch (err) { return; }
  devs.forEach(function (d) { bump_(d.d, v === 'Approved' ? 4 : 5); });
}
