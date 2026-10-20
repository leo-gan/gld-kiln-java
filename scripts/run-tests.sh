#!/usr/bin/env bash
# Unit tests, including the v2 cases and the kiln_ref.py oracle.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "${root}"
# shellcheck disable=SC1091
source "${root}/scripts/ci-setup.sh"
./gradlew test --no-daemon
