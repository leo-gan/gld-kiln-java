#!/usr/bin/env bash
# Ensure Java 21. Downloads Temurin into ~/.cache/gld-kiln-java/jdk-21 when needed.
set -euo pipefail

sourced=0
if [[ "${BASH_SOURCE[0]}" != "$0" ]]; then
  sourced=1
fi

java_major() {
  local ver
  ver="$("$1" -version 2>&1 | head -n 1)"
  if [[ "$ver" =~ version\ \"1\.([0-9]+) ]]; then
    echo "${BASH_REMATCH[1]}"
  elif [[ "$ver" =~ version\ \"([0-9]+) ]]; then
    echo "${BASH_REMATCH[1]}"
  else
    echo 0
  fi
}

use_java() {
  export JAVA_HOME="$1"
  export PATH="${JAVA_HOME}/bin:${PATH}"
  if [[ -n "${GITHUB_PATH:-}" ]]; then
    echo "${JAVA_HOME}/bin" >> "${GITHUB_PATH}"
  fi
  if [[ -n "${GITHUB_ENV:-}" ]]; then
    echo "JAVA_HOME=${JAVA_HOME}" >> "${GITHUB_ENV}"
  fi
}

chosen=""
if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
  if [[ "$(java_major "${JAVA_HOME}/bin/java")" -ge 21 ]]; then
    chosen="${JAVA_HOME}"
  fi
fi

if [[ -z "${chosen}" ]] && command -v java >/dev/null 2>&1; then
  bin="$(command -v java)"
  if [[ "$(java_major "${bin}")" -ge 21 ]]; then
    chosen="$(cd "$(dirname "$(readlink -f "${bin}")")/.." && pwd)"
  fi
fi

if [[ -z "${chosen}" ]]; then
  cache="${HOME}/.cache/gld-kiln-java/jdk-21"
  if [[ ! -x "${cache}/bin/java" ]]; then
    arch="$(uname -m)"
    case "${arch}" in
      x86_64) api_arch=x64 ;;
      aarch64 | arm64) api_arch=aarch64 ;;
      *)
        echo "unsupported architecture ${arch}" >&2
        exit 1
        ;;
    esac
    tmp="$(mktemp -d)"
    url="https://api.adoptium.net/v3/binary/latest/21/ga/linux/${api_arch}/jdk/hotspot/normal/eclipse?project=jdk"
    echo "downloading Temurin 21 into ${cache}"
    curl -fL --retry 3 -o "${tmp}/jdk.tgz" "${url}"
    tar -xzf "${tmp}/jdk.tgz" -C "${tmp}"
    extracted="$(find "${tmp}" -maxdepth 1 -type d -name 'jdk-*' | head -n 1)"
    if [[ -z "${extracted}" ]]; then
      echo "Temurin archive did not contain a jdk directory" >&2
      rm -rf "${tmp}"
      exit 1
    fi
    mkdir -p "${HOME}/.cache/gld-kiln-java"
    rm -rf "${cache}"
    mv "${extracted}" "${cache}"
    rm -rf "${tmp}"
  fi
  chosen="${cache}"
fi

use_java "${chosen}"
java -version

if [[ "${sourced}" -eq 1 ]]; then
  return 0
fi
exit 0
