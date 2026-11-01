#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :core:test :app:assembleDebug :desktop:compileKotlin --console=plain
