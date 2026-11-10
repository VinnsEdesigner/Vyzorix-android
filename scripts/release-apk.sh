#!/usr/bin/env bash
#
# One command: check the toolchain, build the arm64 APK, and publish it as a
# GitHub release.
#
#   scripts/release-apk.sh --tag app-v0.2.0 \
#       --server-key-file /path/server.pub.pem \
#       --cloud-url https://vyzorix.example.com
#
# Flags:
#   --debug                 build the debug APK (default: release)
#   --tag TAG               release tag (default: app-v<versionName>)
#   --version-code N        Android versionCode (default: 1)
#   --version-name X.Y.Z    Android versionName (default: 0.1.0)
#   --server-key PEM        the pinned server public key, literal
#   --server-key-file PATH  a PEM file to read the key from
#   --cloud-url URL         the server base URL compiled into the app
#   --repo OWNER/REPO       release target (default: this repo)
#   --dry-run               build and stage the release, but do not publish
#   --skip-tests            do not run the gate before building
#   --help
#
# Environment:
#   ANDROID_HOME / ANDROID_SDK_ROOT  Android SDK root (else /workspace/toolchain/android-sdk)
#   JAVA_HOME                        JDK 21 (else /workspace/toolchain/jdk)
#   GH_TOKEN / GITHUB_TOKEN          token with repo write access, for the release
#   KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
#                                    optional; when all are set the APK is signed
#                                    with apksigner before it is published
#
# What it does, in order:
#   1. checks every tool it needs is present and usable, and says what is missing;
#   2. makes sure the arm64 build inputs exist (the rootfs tarball and the Layer 3
#      binaries), fetching or building them if they do not;
#   3. runs the gate (unless --skip-tests), so a release is a green tree;
#   4. builds the APK for arm64;
#   5. signs it, if signing material is supplied;
#   6. writes SHA256SUMS and publishes a GitHub release.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

BUILD_TYPE="release"
TAG=""
VERSION_CODE="1"
VERSION_NAME="0.1.0"
SERVER_KEY=""
SERVER_KEY_FILE=""
CLOUD_URL=""
REPO=""
DRY_RUN=0
SKIP_TESTS=0
ASSET_REPO="VinnsEdesigner/gz-_ASSETS"
ROOTFS_ABI="arm64"

log()  { printf '\n=== %s\n' "$*"; }
info() { printf '    %s\n' "$*"; }
die()  { printf '\n!!! %s\n' "$*" >&2; exit 1; }

usage() { sed -n '2,40p' "$0"; exit 0; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --debug)           BUILD_TYPE="debug"; shift ;;
    --release)         BUILD_TYPE="release"; shift ;;
    --tag)             TAG="$2"; shift 2 ;;
    --version-code)    VERSION_CODE="$2"; shift 2 ;;
    --version-name)    VERSION_NAME="$2"; shift 2 ;;
    --server-key)      SERVER_KEY="$2"; shift 2 ;;
    --server-key-file) SERVER_KEY_FILE="$2"; shift 2 ;;
    --cloud-url)       CLOUD_URL="$2"; shift 2 ;;
    --repo)            REPO="$2"; shift 2 ;;
    --dry-run)         DRY_RUN=1; shift ;;
    --skip-tests)      SKIP_TESTS=1; shift ;;
    --help|-h)         usage ;;
    *) die "unknown argument: $1 (try --help)" ;;
  esac
done

# ---------------------------------------------------------------- environment
# The sandbox installs its toolchain into a persistent prefix; source it if it is
# there and the caller has not already pointed at a JDK/SDK.
if [[ -f /workspace/toolchain/env.sh ]]; then
  # shellcheck disable=SC1091
  source /workspace/toolchain/env.sh
fi

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/workspace/toolchain/android-sdk}}"
JAVA_HOME="${JAVA_HOME:-/workspace/toolchain/jdk}"
export ANDROID_HOME ANDROID_SDK_ROOT="${ANDROID_HOME}" JAVA_HOME
export PATH="${JAVA_HOME}/bin:${ANDROID_HOME}/platform-tools:${PATH}"

# ---------------------------------------------------------------- toolchain
log "checking the toolchain"
missing=()

need() { # need <tool> <hint>
  if command -v "$1" >/dev/null 2>&1; then
    info "ok   $1 ($(command -v "$1"))"
  else
    info "MISS $1 — $2"
    missing+=("$1")
  fi
}

need java        "JDK 21 (set JAVA_HOME, or run scripts/install-toolchain.sh)"
need curl        "used to fetch the rootfs and (as a fallback) to publish"
need sha256sum   "part of coreutils; used for the release checksums"

# A JDK that is too old cannot build AGP 8.3.2; check the major version, not just
# that `java` exists.
if command -v java >/dev/null 2>&1; then
  jv="$(java -version 2>&1 | head -1)"
  case "${jv}" in
    *\"21.*|*\"22.*|*\"23.*) info "ok   java is ${jv}" ;;
    *) info "MISS java 21+ — found ${jv}"; missing+=("java-version") ;;
  esac
fi

# The SDK pieces the build reads. A missing one is a configure-time failure, so it
# is worth naming here rather than letting Gradle report it less clearly.
for pkg in "platforms;android-34" "build-tools;34.0.0" "ndk;26.3.11579264" "cmake;3.22.1"; do
  name="${pkg%%;*}"; ver="${pkg##*;}"
  if [[ -d "${ANDROID_HOME}/${name}/${ver}" || -d "${ANDROID_HOME}/${name//;/\/}/${ver}" ]]; then
    info "ok   sdk ${pkg}"
  else
    info "MISS sdk ${pkg} — run scripts/install-toolchain.sh, or sdkmanager --install '${pkg}'"
    missing+=("${pkg}")
  fi
done

[[ -x "${REPO_ROOT}/gradlew" ]] || { info "MISS gradlew"; missing+=("gradlew"); }

# The Layer 3 binaries are cross-compiled for the container's glibc. The compiler is
# only needed when the arm64 archive is absent and must be built here; if it is
# already present (a release checkout, or a previous build) it is not required.
LAYER3_EXISTS=0
[[ -s "${REPO_ROOT}/app-runtime/dist/layer3-arm64.tar.gz" ]] && LAYER3_EXISTS=1
if [[ "${LAYER3_EXISTS}" -eq 1 ]]; then
  info "ok   layer3-arm64.tar.gz present (no cross-compiler needed)"
elif command -v aarch64-linux-gnu-g++ >/dev/null 2>&1; then
  info "ok   aarch64-linux-gnu-g++"
else
  info "MISS aarch64-linux-gnu-g++ — needed to build the arm64 Layer 3 binaries (scripts/install-toolchain.sh)"
  missing+=("aarch64-linux-gnu-g++")
fi

if [[ "${DRY_RUN}" -eq 0 ]]; then
  if command -v gh >/dev/null 2>&1; then
    info "ok   gh (publish path)"
  elif [[ -n "${GH_TOKEN:-${GITHUB_TOKEN:-}}" ]]; then
    info "ok   curl + token (publish fallback; gh not installed)"
  else
    info "MISS gh, or a GH_TOKEN/GITHUB_TOKEN — needed to publish"
    missing+=("publish")
  fi
fi

if [[ ${#missing[@]} -gt 0 ]]; then
  die "the toolchain is incomplete: ${missing[*]}"
fi

# ---------------------------------------------------------------- inputs
if [[ -z "${SERVER_KEY}" && -n "${SERVER_KEY_FILE}" ]]; then
  [[ -f "${SERVER_KEY_FILE}" ]] || die "no such key file: ${SERVER_KEY_FILE}"
  SERVER_KEY="$(cat "${SERVER_KEY_FILE}")"
fi
if [[ -z "${SERVER_KEY}" ]]; then
  SERVER_KEY="${VYZORIX_SERVER_KEY:-}"
fi
if [[ -z "${CLOUD_URL}" ]]; then
  CLOUD_URL="${VYZORIX_CLOUD_URL:-}"
fi

if [[ "${BUILD_TYPE}" == "release" ]]; then
  [[ -n "${SERVER_KEY}" ]] || die "a release build needs the pinned server key: --server-key-file, --server-key, or VYZORIX_SERVER_KEY"
  [[ -n "${CLOUD_URL}" ]]  || die "a release build needs the server URL: --cloud-url or VYZORIX_CLOUD_URL"
else
  info "debug build: the cloud key and URL are optional"
fi

# The build wants the PEM with literal \n escapes (it goes into a Java string
# literal). Turn a real multi-line PEM into that form.
SERVER_KEY_PROP="$(printf '%s' "${SERVER_KEY}" | awk 'BEGIN{ORS=""} NR>1{print "\\n"} {print}' | sed 's/\\n$//')"

# ---------------------------------------------------------------- arm64 inputs
log "preparing the arm64 build inputs"

DIST="${REPO_ROOT}/debian-engine/rootfs/dist"
ROOTFS="${DIST}/debian-core-${ROOTFS_ABI}.tar.gz"
if [[ -s "${ROOTFS}" ]]; then
  info "rootfs present: ${ROOTFS}"
else
  info "fetching the ${ROOTFS_ABI} rootfs from ${ASSET_REPO}"
  mkdir -p "${DIST}"
  auth=()
  [[ -n "${GIT:-}" ]] && auth=(-H "Authorization: Bearer ${GIT}")
  curl -fsSL "${auth[@]}" -o "${ROOTFS}" \
    "https://github.com/${ASSET_REPO}/releases/latest/download/debian-core-${ROOTFS_ABI}.tar.gz" \
    || die "could not fetch the rootfs; build it with scripts/build-rootfs.sh --arch ${ROOTFS_ABI}"
fi

LAYER3="${REPO_ROOT}/app-runtime/dist/layer3-${ROOTFS_ABI}.tar.gz"
if [[ -s "${LAYER3}" ]]; then
  info "Layer 3 binaries present: ${LAYER3}"
else
  info "building the ${ROOTFS_ABI} Layer 3 binaries"
  bash "${REPO_ROOT}/scripts/build-layer3.sh" --arch "${ROOTFS_ABI}"
fi

# ---------------------------------------------------------------- gate
if [[ "${SKIP_TESTS}" -eq 0 ]]; then
  log "running the gate (ktlint + detekt + dead code + tests + native)"
  ./gradlew gate --no-daemon
else
  info "skipping the gate (--skip-tests)"
fi

# ---------------------------------------------------------------- build
log "building the ${BUILD_TYPE} APK for ${ROOTFS_ABI}"
echo "sdk.dir=${ANDROID_HOME}" > local.properties

task=":app:assembleRelease"
[[ "${BUILD_TYPE}" == "debug" ]] && task=":app:assembleDebug"

./gradlew "${task}" --no-daemon \
  -ProotfsAbi="${ROOTFS_ABI}" \
  -PcloudBaseUrl="${CLOUD_URL}" \
  -PserverPublicKey="${SERVER_KEY_PROP}" \
  -PappVersionCode="${VERSION_CODE}" \
  -PappVersionName="${VERSION_NAME}"

APK_DIR="${REPO_ROOT}/app/build/outputs/apk/${BUILD_TYPE}"
APK_NAME="vyzorix-app-v${VERSION_NAME}-${BUILD_TYPE}-${ROOTFS_ABI}.apk"
STAGE="$(mktemp -d)"
APK="${STAGE}/${APK_NAME}"

# An unsigned release build is named `app-release-unsigned.apk`; a signed one is
# `app-release.apk`. Take whichever exists rather than assuming.
SOURCE_APK="${APK_DIR}/app-${BUILD_TYPE}.apk"
[[ -f "${SOURCE_APK}" ]] || SOURCE_APK="${APK_DIR}/app-${BUILD_TYPE}-unsigned.apk"
[[ -f "${SOURCE_APK}" ]] || die "no APK at ${APK_DIR}/app-${BUILD_TYPE}*.apk"

cp "${SOURCE_APK}" "${APK}"
info "built ${APK} ($(du -h "${APK}" | cut -f1))"

# ---------------------------------------------------------------- sign
if [[ -n "${KEYSTORE_FILE:-}" && -n "${KEYSTORE_PASSWORD:-}" && -n "${KEY_ALIAS:-}" && -n "${KEY_PASSWORD:-}" ]]; then
  log "signing the APK"
  apksigner="$(find "${ANDROID_HOME}/build-tools" -name apksigner 2>/dev/null | sort | tail -1)"
  [[ -n "${apksigner}" ]] || die "apksigner not found under ${ANDROID_HOME}/build-tools"
  "${apksigner}" sign \
    --ks "${KEYSTORE_FILE}" --ks-pass "pass:${KEYSTORE_PASSWORD}" \
    --ks-key-alias "${KEY_ALIAS}" --key-pass "pass:${KEY_PASSWORD}" \
    "${APK}"
  "${apksigner}" verify "${APK}" && info "signature verified"
elif [[ "${BUILD_TYPE}" == "release" ]]; then
  info "WARNING: the release APK is unsigned (no KEYSTORE_FILE/KEY_* set); Android"
  info "         will refuse to install it. Supply the KEYSTORE_* variables to sign."
fi

# ---------------------------------------------------------------- publish
log "preparing the release"
TAG="${TAG:-app-v${VERSION_NAME}}"
( cd "${STAGE}" && sha256sum "${APK_NAME}" > SHA256SUMS )
cat "${STAGE}/SHA256SUMS"

if [[ "${DRY_RUN}" -eq 1 ]]; then
  info "dry run: staged at ${STAGE}, tag would be ${TAG}; not publishing"
  exit 0
fi

NOTES="Vyzorix device agent ${VERSION_NAME} (versionCode ${VERSION_CODE}), ${BUILD_TYPE} build for arm64.

Built from $(git rev-parse --short HEAD) by scripts/release-apk.sh."

if command -v gh >/dev/null 2>&1; then
  log "publishing with gh"
  gh release create "${TAG}" "${APK}" "${STAGE}/SHA256SUMS" \
    --title "Vyzorix agent v${VERSION_NAME}" \
    --notes "${NOTES}" \
    ${REPO:+--repo "${REPO}"}
else
  log "publishing with the GitHub API (gh not installed)"
  [[ -n "${REPO}" ]] || die "--repo is required when publishing without gh"
  token="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
  [[ -n "${token}" ]] || die "no GH_TOKEN/GITHUB_TOKEN"

  release_json="$(curl -fsSL -X POST "https://api.github.com/repos/${REPO}/releases" \
    -H "Authorization: Bearer ${token}" -H "Accept: application/vnd.github+json" \
    -d "$(python3 - "${TAG}" "${NOTES}" <<'PY'
import json, sys
print(json.dumps({"tag_name": sys.argv[1], "name": sys.argv[1], "body": sys.argv[2]}))
PY
)")"
  upload_url="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["upload_url"].split("{")[0])' "${release_json}")"

  for f in "${APK}" "${STAGE}/SHA256SUMS"; do
    name="$(basename "$f")"
    curl -fsSL -X POST "${upload_url}?name=${name}" \
      -H "Authorization: Bearer ${token}" \
      -H "Content-Type: application/octet-stream" \
      --data-binary "@${f}" >/dev/null
    info "uploaded ${name}"
  done
fi

log "released ${TAG}"
if [[ -n "${REPO}" ]]; then
  info "https://github.com/${REPO}/releases/tag/${TAG}"
else
  info "https://github.com/$(git -C "${REPO_ROOT}" remote get-url origin \
    | sed -E 's#.*github\.com[:/]##; s#\.git$##')/releases/tag/${TAG}"
fi
