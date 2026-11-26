#!/usr/bin/env python3
"""Collect new Delhi Traffic Police notifications/advisories (title + link) into feed/updates.json -> advisories[].
Best effort: if the site is unreachable or its layout changes, the feed is left as it was (warning only)."""
import hashlib, json, re, sys, urllib.request, html, datetime, urllib.parse

SOURCES = [
    "https://traffic.delhipolice.gov.in/",
    "https://traffic.delhipolice.gov.in/notifications",
    "https://traffic.delhipolice.gov.in/node/41",
]
KEY = re.compile(r"(advisory|diversion|no[ -]?entry|restriction|closure|closed|notification|traffic arrangement|prohibit)", re.I)
A = re.compile(r'<a\b[^>]*?href\s*=\s*["\']([^"\']+)["\'][^>]*>(.*?)</a>', re.I | re.S)

def extract(page, base):
    out = []
    for href, inner in A.findall(page):
        text = html.unescape(re.sub(r"<[^>]+>", " ", inner))
        text = re.sub(r"\s+", " ", text).strip()
        if len(text) < 8 or not (KEY.search(text) or KEY.search(href)):
            continue
        url = urllib.parse.urljoin(base, html.unescape(href.strip()))
        if not url.startswith("https://") or "delhipolice.gov.in" not in url:
            continue
        out.append((text[:140], url))
    return out

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (NoEntryNav feed collector)"})
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.read(2_000_000).decode("utf-8", "replace")

def main(path="feed/updates.json"):
    feed = json.load(open(path, encoding="utf-8"))
    adv = feed.setdefault("advisories", [])
    have = {a["id"] for a in adv}
    today = datetime.datetime.utcnow().date().isoformat()
    found = 0
    ok = 0
    for src in SOURCES:
        try:
            page = fetch(src)
            ok += 1
        except Exception as e:
            print(f"::warning::could not read {src}: {e}")
            continue
        for title, url in extract(page, src):
            i = "DTP-" + hashlib.sha1(url.encode()).hexdigest()[:10]
            if i in have:
                continue
            have.add(i); found += 1
            adv.append({
                "id": i, "kind": "advisory", "title": title, "authority": "Delhi Traffic Police", "ref": "",
                "date": today, "gazette": "",
                "summary": "Collected automatically from the Delhi Traffic Police website. Open the source for details. "
                           "Not applied to routing unless a matching rule is added.",
                "url": url,
            })
    if ok == 0:
        print("::warning::no source could be read; feed unchanged")
        return 0
    adv.sort(key=lambda a: a["date"], reverse=True)
    del adv[60:]
    if found:
        feed["updated"] = today
        json.dump(feed, open(path, "w", encoding="utf-8"), indent=2, ensure_ascii=False)
        open(path, "a").write("\n")
    print(f"::notice::{found} new advisories collected ({ok}/{len(SOURCES)} sources readable)")
    return 0

if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:]))
