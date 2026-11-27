#!/usr/bin/env python3
"""Writes android/app/src/main/assets/community_config.json with the URL and secret lightly scrambled (not plain text in the APK).
Usage: COMMUNITY_URL=... COMMUNITY_SECRET=... make_config.py     |     make_config.py --new-secret"""
import base64, json, os, secrets, sys
KEY = b"NoEntryNav-2026-sureshift"
def enc(s): return base64.b64encode(bytes(b ^ KEY[i % len(KEY)] for i, b in enumerate(s.encode()))).decode()
if '--new-secret' in sys.argv:
    print(secrets.token_urlsafe(40)); sys.exit()
url, sec = os.environ.get('COMMUNITY_URL', '').strip(), os.environ.get('COMMUNITY_SECRET', '').strip()
path = os.path.join(os.path.dirname(__file__), '..', '..', 'android', 'app', 'src', 'main', 'assets', 'community_config.json')
json.dump({'e': enc(url) if url and sec else '', 's': enc(sec) if url and sec else ''}, open(path, 'w'))
print('community config:', 'written' if url and sec else 'empty (feature off)')
