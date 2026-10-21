#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MOZC_REPO="${MOZC_REPO:-https://github.com/Lopap1pg/mozc.git}"
MOZC_COMMIT="${MOZC_COMMIT:-f183d4d3a7ba139d7cb2252c05f608e9f8266aef}"
CHECKOUT="${ROOT}/.mozc-src"
MOZC_SRC="${CHECKOUT}/src"
OUT="${ROOT}/build/mozc-generated"

if [[ ! -d "${CHECKOUT}/.git" ]]; then
  rm -rf "${CHECKOUT}"
  git init -q "${CHECKOUT}"
  git -C "${CHECKOUT}" remote add origin "${MOZC_REPO}"
fi

git -C "${CHECKOUT}" fetch -q --depth 1 origin "${MOZC_COMMIT}"
git -C "${CHECKOUT}" checkout -q --detach FETCH_HEAD

if [[ ! -d "${MOZC_SRC}" ]]; then
  echo "Mozc source directory not found: ${MOZC_SRC}" >&2
  exit 1
fi

# Mozc's Bzlmod configuration expects an NDK at this fixed path. GitHub's
# Android runner already contains several NDKs, so reuse the newest installed
# one instead of downloading another copy.
if [[ -z "${ANDROID_NDK_HOME:-}" && -n "${ANDROID_SDK_ROOT:-}" && -d "${ANDROID_SDK_ROOT}/ndk" ]]; then
  newest_ndk="$(find "${ANDROID_SDK_ROOT}/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1 || true)"
  if [[ -n "${newest_ndk}" ]]; then
    export ANDROID_NDK_HOME="${newest_ndk}"
  fi
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  echo "ANDROID_NDK_HOME is not set and no installed Android NDK was found" >&2
  exit 1
fi

mkdir -p "${MOZC_SRC}/third_party/ndk"
rm -rf "${MOZC_SRC}/third_party/ndk/android-ndk-r29"
ln -s "${ANDROID_NDK_HOME}" "${MOZC_SRC}/third_party/ndk/android-ndk-r29"

BAZEL="${BAZEL:-$(command -v bazelisk || command -v bazel || true)}"
if [[ -z "${BAZEL}" ]]; then
  echo "bazelisk/bazel was not found" >&2
  exit 1
fi

cd "${MOZC_SRC}"
QUIET=(--curses=no --show_progress_rate_limit=30)

# Build the stock upstream Android JNI library for all supported ABIs.
"${BAZEL}" build //android/jni:native_libs \
  --config oss_android --config release_build "${QUIET[@]}"

# mozc.data is architecture independent and must be built with the host config.
"${BAZEL}" build //data_manager/oss:mozc.data \
  --config oss_linux --config release_build "${QUIET[@]}"

rm -rf "${OUT}"
mkdir -p "${OUT}/jniLibs" "${OUT}/assets" "${OUT}/proto/protocol"

unzip -q -o bazel-bin/android/jni/native_libs.zip -d "${OUT}/jni.tmp"
if [[ -d "${OUT}/jni.tmp/libs" ]]; then
  cp -a "${OUT}/jni.tmp/libs/." "${OUT}/jniLibs/"
else
  cp -a "${OUT}/jni.tmp/." "${OUT}/jniLibs/"
fi
rm -rf "${OUT}/jni.tmp"

install -m 644 bazel-bin/data_manager/oss/mozc.data "${OUT}/assets/mozc.data"
install -m 644 protocol/*.proto "${OUT}/proto/protocol/"

echo "Mozc staged in ${OUT}"
find "${OUT}/jniLibs" -maxdepth 2 -type f -name '*.so' -print
ls -lh "${OUT}/assets/mozc.data"
