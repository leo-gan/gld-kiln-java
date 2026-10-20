#!/usr/bin/env bash
# Generate testdata/schema/*.json into tests/generated/.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "${root}"
# shellcheck disable=SC1091
source "${root}/scripts/ci-setup.sh"
./gradlew --no-daemon installDist
bin="${root}/build/install/gld-kilngen-java/bin/gld-kilngen-java"
mkdir -p tests/generated
shopt -s nullglob
for schema in testdata/schema/*.json; do
  "${bin}" --schema "${schema}" --out tests/generated --package io.github.leogan.kiln.generated
done
