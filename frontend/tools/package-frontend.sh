#!/usr/bin/env bash
# package-frontend.sh -- build the front end for a CDN or any static host.
#
#   frontend/tools/package-frontend.sh [--api https://api.example.com] [--out DIR] [--skip-install]
#
# Produces frontend/dist/ (upload its CONTENTS to the CDN, index.html at the root) and a
# .tar.gz of it under frontend/build/ for hosts that take an archive. The backend origin is
# baked into the build: pass it with --api, or put it in genvn.config.json as "apiBase".
# Without either, the build expects /api on the same origin (a reverse proxy in front of the
# backend), which is the right choice when one server hosts both halves.
#
# Options
#   --api ORIGIN     backend origin to bake in, e.g. https://api.example.com (no path)
#   --out DIR        where to write the archive           (default: frontend/build)
#   --skip-install   do not run npm ci first
#   -h, --help       this text
set -euo pipefail

FRONTEND="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$FRONTEND/build"
API=""
SKIP_INSTALL=0

usage() { sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; }
say()   { printf '%s\n' "$*"; }
die()   { printf 'package-frontend: %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --api)          [ $# -ge 2 ] || die "--api needs an origin"; API="$2"; shift 2 ;;
        --out)          [ $# -ge 2 ] || die "--out needs a directory"; OUT="$2"; shift 2 ;;
        --skip-install) SKIP_INSTALL=1; shift ;;
        -h|--help)      usage; exit 0 ;;
        *)              die "unknown option '$1' (try --help)" ;;
    esac
done

command -v npm >/dev/null 2>&1 || die "npm is not on PATH; the front end needs Node 20+"
if [ -n "$API" ]; then
    case "$API" in
        http://*|https://*) ;;
        *) die "--api must be an origin such as https://api.example.com" ;;
    esac
    case "$API" in
        http://*) say "!!  $API is plain HTTP: the access key will travel unencrypted. Use https:// for anything public." ;;
    esac
fi

cd "$FRONTEND"
if [ "$SKIP_INSTALL" -eq 0 ]; then
    say "==> npm ci"
    npm ci --silent
fi

say "==> npm run build${API:+ (apiBase=$API)}"
rm -rf dist
if [ -n "$API" ]; then
    GENVN_API_BASE="$API" npm run build --silent
else
    npm run build --silent
fi
[ -f dist/index.html ] || die "build produced no dist/index.html"

BAKED="$(grep -o 'https\?://[^"]*' dist/assets/*.js 2>/dev/null | grep -v 'react\|w3\.org\|github\|npmjs' | head -n 1 || true)"
STAMP="$(date +%Y%m%d-%H%M)"
mkdir -p "$OUT"
ARCHIVE="$OUT/genvn-frontend-$STAMP.tar.gz"
(cd dist && COPYFILE_DISABLE=1 tar -czf "$ARCHIVE" .)

say ""
say "==> $FRONTEND/dist   (upload the contents of this directory)"
say "==> $ARCHIVE"
say "    backend  ${API:-same origin (/api must be proxied to the backend)}"
say "    size     $(du -h "$ARCHIVE" | cut -f1 | tr -d ' ')"
say ""
say "CDN notes: serve index.html with no-cache (or a short max-age); assets/* are content-hashed"
say "and can be cached for a year. There is no client-side routing, so no SPA fallback is needed."
say "The backend must list this site's origin in genvn.allowed-origins and set genvn.access-key."
