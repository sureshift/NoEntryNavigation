#!/usr/bin/env python3
"""Collect new Delhi Traffic Police notifications/advisories (title + link) into feed/updates.json -> advisories[].
Best effort: if the site is unreachable or its layout changes, the feed is left as it was (warning only)."""
import hashlib, json, re, sys, urllib.request, html, datetime, urllib.parse

SOURCES = [
    "https://traffic.delhipolice.gov.in/",
    "https://traffic.delhipolice.gov.in/en/notifications-and-permission",
    "https://traffic.delhipolice.gov.in/en/no-entry-permission-1",
]
KEY = re.compile(r"(advisory|advisories|diversion|no[ -]?entry|restriction|closure|closed|notification|circular|traffic arrangement|prohibit|order)", re.I)
A = re.compile(r'<a\b[^>]*?href\s*=\s*["\']([^"\']+)["\'][^>]*>(.*?)</a>', re.I | re.S)
GENERIC = re.compile(r"^(download|view|click here|read more|more|pdf|english|hindi|open|details)$", re.I)
FOLLOW = re.compile(r"/(advis|notif|circular|diversion|restrict|news)", re.I)
DATE = re.compile(r"(\d{1,2})[-_.](\d{1,2})[-_.](20\d{2})")

NOENTRY = re.compile(r"no[\s_-]*entry|\bNEP\b|goods|HGV|MGV|LGV|heavy|truck|diversion|restriction|plying", re.I)

def clean(inner):
    t = html.unescape(re.sub(r"<[^>]+>", " ", inner))
    return re.sub(r"\s+", " ", t).strip()

def extract(page, base):
    """(title, url, date|None) for document links and long, descriptive advisory/notification links."""
    out = []
    for href, inner in A.findall(page):
        href = html.unescape(href.strip())
        if href.startswith("#") or href.lower().startswith(("javascript:", "mailto:", "tel:")):
            continue
        url = urllib.parse.urljoin(base, href)
        if not url.startswith("https://") or "delhipolice.gov.in" not in url:
            continue
        text = clean(inner)
        is_doc = bool(re.search(r"\.(pdf|docx?)(\?|$)", url, re.I))
        fname = urllib.parse.unquote(url.rsplit("/", 1)[-1]).rsplit(".", 1)[0].replace("_", " ").replace("-", " ").strip()
        if is_doc and (len(text) < 8 or GENERIC.match(text)):
            text = fname
        if len(text) < 8 or GENERIC.match(text):
            continue
        # menu items such as "Traffic Advisory" are short; real entries are documents or long descriptive titles
        if not is_doc and len(text) < 32:
            continue
        if not (is_doc or KEY.search(text)):
            continue
        if not NOENTRY.search(text + " " + fname):
            continue
        m = DATE.search(url) or DATE.search(text)
        d = None
        if m:
            dd, mm, yy = int(m.group(1)), int(m.group(2)), int(m.group(3))
            if 1 <= dd <= 31 and 1 <= mm <= 12:
                d = "%04d-%02d-%02d" % (yy, mm, dd)
        out.append((text[:140], url, d))
    return out

def follow_links(page, base):
    """Listing pages linked from a source page (one level), same site only."""
    links = []
    for href, inner in A.findall(page):
        url = urllib.parse.urljoin(base, html.unescape(href.strip()))
        url = url.split("#")[0]
        if re.fullmatch(r"[A-Za-z0-9/_.:=&%?-]+", url) and url.startswith("https://traffic.delhipolice.gov.in/") and FOLLOW.search(url) and not re.search(r"\.(pdf|docx?|jpg|png)$", url, re.I):
            if url not in links:
                links.append(url)
    return links

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (NoEntryNav feed collector)"})
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.read(2_000_000).decode("utf-8", "replace")

def main(path="feed/updates.json"):
    feed = json.load(open(path, encoding="utf-8"))
    # drop entries from an earlier, cruder version of this script (menu links without documents)
    adv = [x for x in feed.setdefault("advisories", []) if x.get("url", "").startswith("https://") and "#" not in x["url"]
           and not (x["id"].startswith("DTP-") and x.get("title") in ("Traffic Advisory", "No Entry Permission", "Notifications", "Notifications & Circulars"))]
    adv = [x for x in adv if NOENTRY.search(x.get("title", "") + " " + x["url"].rsplit("/", 1)[-1].replace("_", " "))]
    feed["advisories"] = adv
    feed["advisories"] = adv
    have = {x["id"] for x in adv}
    today = datetime.datetime.utcnow().date().isoformat()
    pages, seen_pages, ok, found = list(SOURCES), set(), 0, 0
    while pages and len(seen_pages) < 12:
        src = pages.pop(0)
        if src in seen_pages:
            continue
        seen_pages.add(src)
        try:
            page = fetch(src)
            ok += 1
        except Exception as e:
            print(f"::warning::could not read {src}: {e}")
            continue
        items = extract(page, src)
        print(f"::notice::{src}: {len(items)} candidate items, {len(A.findall(page))} links")
        if src in SOURCES:
            for u in follow_links(page, src)[:6]:
                if u not in seen_pages:
                    pages.append(u)
        for title, url, d in items:
            i = "DTP-" + hashlib.sha1(url.encode()).hexdigest()[:10]
            if i in have:
                continue
            have.add(i); found += 1
            adv.append({
                "id": i, "kind": "advisory", "title": title, "authority": "Delhi Traffic Police", "ref": "",
                "date": d or today, "gazette": "",
                "summary": "Collected automatically from the Delhi Traffic Police website. Open the source for details. "
                           "Not applied to routing unless a matching rule is added.",
                "url": url,
            })
    if ok == 0:
        print("::warning::no source could be read; feed unchanged")
        return 0
    adv.sort(key=lambda x: x["date"], reverse=True)
    del adv[60:]
    feed["updated"] = today
    json.dump(feed, open(path, "w", encoding="utf-8"), indent=2, ensure_ascii=False)
    open(path, "a").write("\n")
    for x in adv[:8]:
        print(f"::notice::{x['date']} {x['title'][:80]}")
    print(f"::notice::{found} new advisories collected ({ok} pages readable)")
    return 0

if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:]))
