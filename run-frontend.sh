#!/usr/bin/env sh
# Starts the Vite dev server on 127.0.0.1:5180 and proxies /api to the backend.
set -e
cd "$(dirname "$0")/frontend"
[ -d node_modules ] || npm install
exec npm run dev
