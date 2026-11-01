#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

PORT="${ANIME_PORT:-8080}"
RUN_DIR=/tmp/opencode/anime-run
LOG="$RUN_DIR/server.log"
PID="$RUN_DIR/server.pid"
HASH="$RUN_DIR/.hash"
mkdir -p "$RUN_DIR"
exec 9>"$RUN_DIR/run.lock"
flock -n 9 || { echo "run.sh already active"; exit 0; }

kill_server() {
  if [[ -f "$PID" ]]; then kill "$(cat "$PID")" 2>/dev/null || true; rm -f "$PID"; fi
  pkill -f 'dev\.anime\.MainKt' 2>/dev/null || true
}

src_hash() {
  {
    find core/src server/src -type f -print0
    printf '%s\0' build.gradle.kts settings.gradle.kts server/build.gradle.kts core/build.gradle.kts
  } | sort -z | xargs -0 md5sum | md5sum | cut -d' ' -f1
}

start_server() {
  local bin=server/build/install/anime-server/bin/anime-server
  for i in $(seq 1 60); do
    curl -fs --max-time 2 -o /dev/null "http://127.0.0.1:$PORT/healthz" && return 0
    sleep 1
  done
  echo "[run.sh] server failed to come up on :$PORT; last log:" >&2
  tail -5 "$LOG" >&2 || true
  return 1
}

kill_server
if [[ ! -d server/build/install/anime-server ]] || [[ -n "$(find core/src server/src build.gradle.kts settings.gradle.kts -newer "$HASH" -print -quit 2>/dev/null)" ]] || [[ ! -f "$HASH" ]]; then
  ./gradlew -q :server:installDist
  src_hash > "$HASH"
fi
nohup ./server/build/install/anime-server/bin/anime-server > "$LOG" 2>&1 &
echo $! > "$PID"
start_server || exit 1
echo "[run.sh] serving on :$PORT (pid $(cat "$PID"))"

while true; do
  sleep 5
  if ! kill -0 "$(cat "$PID")" 2>/dev/null; then
    echo "[run.sh] server died — restarting"
    nohup ./server/build/install/anime-server/bin/anime-server > "$LOG" 2>&1 &
    echo $! > "$PID"
    start_server || true
  fi
  h="$(src_hash)"
  if [[ "$h" != "$(cat "$HASH")" ]]; then
    echo "[run.sh] sources changed — rebuilding"
    if ./gradlew -q :server:installDist; then
      kill_server
      nohup ./server/build/install/anime-server/bin/anime-server > "$LOG" 2>&1 &
      echo $! > "$PID"
      start_server || true
      echo "$h" > "$HASH"
    else
      echo "[run.sh] build failed — keeping old server, will retry"
    fi
  fi
done
