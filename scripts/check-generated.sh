#!/usr/bin/env bash
# Regenerate into a temp directory and diff against tests/generated.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "${root}"
# shellcheck disable=SC1091
source "${root}/scripts/ci-setup.sh"
./gradlew --no-daemon installDist
bin="${root}/build/install/gld-kilngen-java/bin/gld-kilngen-java"
tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT
shopt -s nullglob
for schema in testdata/schema/*.json; do
  "${bin}" --schema "${schema}" --out "${tmp}" --package io.github.leogan.kiln.generated
done
diff -ru tests/generated "${tmp}"
