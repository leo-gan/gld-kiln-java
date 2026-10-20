#!/usr/bin/env bash
# Small struct batch and one 1024-element f32 tensor, encode and decode.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "${root}"
# shellcheck disable=SC1091
source "${root}/scripts/ci-setup.sh"
./gradlew --no-daemon -q classes
run_once() {
  ./gradlew --no-daemon -q microbench --offline
}
for _ in 1 2 3 4 5; do
  if command -v taskset >/dev/null 2>&1; then
    taskset -c 1 bash -c 'cd "'"${root}"'" && ./gradlew --no-daemon -q microbench --offline'
  else
    run_once
  fi
done
