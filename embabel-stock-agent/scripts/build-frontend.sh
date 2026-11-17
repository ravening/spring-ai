#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/frontend"
npm install
npm run build
rm -rf "$ROOT/src/main/resources/static"
cp -R dist "$ROOT/src/main/resources/static"
echo "Frontend copied to src/main/resources/static"
