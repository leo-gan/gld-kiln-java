#!/usr/bin/env bash
# Publish to Maven Central when MAVEN_TOKEN is set. Otherwise say so and exit 0.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "${root}"

if [[ "${1:-}" == "--dry-run" ]]; then
  echo "dry-run: Maven Central was not called"
  if [[ -z "${MAVEN_TOKEN:-}" ]]; then
    echo "MAVEN_TOKEN is unset. Skipping Maven Central."
  fi
  exit 0
elif [[ -n "${1:-}" ]]; then
  echo "usage: scripts/publish.sh [--dry-run]" >&2
  exit 2
fi

if [[ -z "${MAVEN_TOKEN:-}" ]]; then
  echo "MAVEN_TOKEN is unset. Skipping Maven Central."
  exit 0
fi

# shellcheck disable=SC1091
source "${root}/scripts/ci-setup.sh"
./gradlew --no-daemon publish
