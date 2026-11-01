#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

REPO="VLingott/anime"
PROPS="version.properties"

if [[ "${1:-}" == "--bump" ]]; then
  python3 - <<'PY'
from pathlib import Path
p = Path("version.properties")
values = {}
for line in p.read_text().splitlines():
    if "=" in line:
        k, v = line.split("=", 1)
        values[k.strip()] = v.strip()
parts = values.get("version", "1.0.0").split(".")
while len(parts) < 3:
    parts.append("0")
parts[2] = str(int(parts[2]) + 1)
values["version"] = ".".join(parts[:3])
values["versionCode"] = str(int(values.get("versionCode", "1")) + 1)
p.write_text("version={version}\nversionCode={versionCode}\n".format(**values))
PY
fi

VERSION="$(grep -E '^version=' "$PROPS" | cut -d= -f2)"
CODE="$(grep -E '^versionCode=' "$PROPS" | cut -d= -f2)"
TAG="v$VERSION"

echo "Building Anime $VERSION ($CODE)"
./gradlew :app:assembleDebug :desktop:createDistributable

rm -rf dist
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk "dist/anime-$VERSION.apk"
tar -czf "dist/anime-linux-$VERSION.tar.gz" -C desktop/build/compose/binaries/main/app Anime

if [[ "${1:-}" == "--bump" ]]; then
  git add "$PROPS"
  git commit -m "Release $TAG" || true
  git push origin HEAD || true
fi

if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  gh release upload "$TAG" dist/* --repo "$REPO" --clobber
else
  gh release create "$TAG" dist/* \
    --repo "$REPO" \
    --title "Anime $TAG" \
    --notes "APK and Linux desktop build for $TAG."
fi

echo "Released https://github.com/$REPO/releases/tag/$TAG"
