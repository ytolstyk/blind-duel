#!/usr/bin/env bash
# Build a release package (AAB + APK + R8 mapping + checksums + notes) into releases/v<version>/.
#
# Usage: scripts/release.sh [--skip-tests] [--force] [--open]
#   --skip-tests  don't run ./gradlew test first
#   --force       overwrite an existing releases/v<version>/ folder
#   --open        reveal the finished folder in Finder
#
# Signing: put storeFile and keyAlias in keystore.properties (project root, gitignored).
# Passwords may be added there too, or left out — the script then prompts for them (or reads
# BLINDDUEL_STORE_PASSWORD / BLINDDUEL_KEY_PASSWORD). Without the file, artifacts are UNSIGNED.
set -euo pipefail

SKIP_TESTS=0; FORCE=0; OPEN=0
for arg in "$@"; do
  case "$arg" in
    --skip-tests) SKIP_TESTS=1 ;;
    --force) FORCE=1 ;;
    --open) OPEN=1 ;;
    -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GRADLE_FILE="app/build.gradle.kts"
VERSION_NAME="$(sed -n 's/.*versionName *= *"\(.*\)".*/\1/p' "$GRADLE_FILE" | head -1)"
VERSION_CODE="$(sed -n 's/.*versionCode *= *\([0-9]*\).*/\1/p' "$GRADLE_FILE" | head -1)"
[[ -n "$VERSION_NAME" && -n "$VERSION_CODE" ]] || { echo "Could not read version from $GRADLE_FILE" >&2; exit 1; }

OUT="$ROOT/releases/v${VERSION_NAME}"
if [[ -e "$OUT" && $FORCE -eq 0 ]]; then
  echo "Release folder already exists: $OUT" >&2
  echo "Bump versionName/versionCode in $GRADLE_FILE, or re-run with --force to overwrite." >&2
  exit 1
fi

COMMIT="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
DIRTY=""
if [[ -n "$(git status --porcelain 2>/dev/null)" ]]; then DIRTY=" (+ uncommitted changes)"; fi

SIGNED=0
if [[ -f keystore.properties ]]; then
  SIGNED=1
  KS_FILE="$(sed -n 's/^storeFile *= *//p' keystore.properties | head -1)"
  [[ -n "$KS_FILE" && "$KS_FILE" != /* ]] && KS_FILE="$ROOT/$KS_FILE"
  [[ -f "$KS_FILE" ]] || { echo "keystore.properties: storeFile not found: ${KS_FILE:-<unset>}" >&2; exit 1; }
  if ! grep -q '^storePassword' keystore.properties && [[ -z "${BLINDDUEL_STORE_PASSWORD:-}" ]]; then
    read -rsp "Keystore password: " BLINDDUEL_STORE_PASSWORD; echo
    export BLINDDUEL_STORE_PASSWORD
  fi
  if ! grep -q '^keyPassword' keystore.properties && [[ -z "${BLINDDUEL_KEY_PASSWORD:-}" ]]; then
    read -rsp "Key password (blank = same as keystore): " BLINDDUEL_KEY_PASSWORD; echo
    [[ -n "$BLINDDUEL_KEY_PASSWORD" ]] && export BLINDDUEL_KEY_PASSWORD
  fi
fi

echo "==> BlindDuel v${VERSION_NAME} (code ${VERSION_CODE}) @ ${BRANCH} ${COMMIT}${DIRTY}"
[[ -n "$DIRTY" ]] && echo "    warning: working tree is dirty; the build won't match a clean commit."
[[ $SIGNED -eq 0 ]] && echo "    warning: no keystore.properties — artifacts will be UNSIGNED (Play Console will reject the AAB)."

if [[ $SKIP_TESTS -eq 0 ]]; then
  echo "==> Running unit tests"
  ./gradlew test
fi

echo "==> Building release AAB + APK"
./gradlew bundleRelease assembleRelease

BUILD="app/build/outputs"
AAB="$BUILD/bundle/release/app-release.aab"
APK="$(ls "$BUILD"/apk/release/*.apk 2>/dev/null | head -1 || true)"
MAPPING="$BUILD/mapping/release/mapping.txt"
[[ -f "$AAB" ]] || { echo "Expected AAB not found at $AAB" >&2; exit 1; }

rm -rf "$OUT"; mkdir -p "$OUT"
BASE="BlindDuel-v${VERSION_NAME}-${VERSION_CODE}"
SUFFIX=""; [[ $SIGNED -eq 0 ]] && SUFFIX="-unsigned"
cp "$AAB" "$OUT/${BASE}${SUFFIX}.aab"
[[ -n "$APK" ]] && cp "$APK" "$OUT/${BASE}${SUFFIX}.apk"
[[ -f "$MAPPING" ]] && cp "$MAPPING" "$OUT/mapping.txt"
NATIVE_SYMS="$BUILD/native-debug-symbols/release/native-debug-symbols.zip"
[[ -f "$NATIVE_SYMS" ]] && cp "$NATIVE_SYMS" "$OUT/"

# Release notes: the [Unreleased] section of CHANGELOG.md
{
  echo "BlindDuel v${VERSION_NAME} (versionCode ${VERSION_CODE})"
  echo "Built $(date '+%Y-%m-%d %H:%M:%S') from ${BRANCH}@${COMMIT}${DIRTY}"
  echo
  awk '/^## \[Unreleased\]/{f=1;next} /^## \[/{f=0} f' CHANGELOG.md
} > "$OUT/RELEASE_NOTES.txt"

( cd "$OUT" && shasum -a 256 -- *.aab *.apk mapping.txt 2>/dev/null > SHA256SUMS.txt || true )

echo
echo "==> Release package ready"
ls -lh "$OUT" | tail -n +2 | awk '{printf "    %-6s %s\n", $5, $9}'
echo
printf 'Folder: \033]8;;file://%s\033\\file://%s\033]8;;\033\\\n' "$OUT" "$OUT"
AAB_OUT="$(ls "$OUT"/*.aab)"
printf 'AAB:    \033]8;;file://%s\033\\file://%s\033]8;;\033\\\n' "$AAB_OUT" "$AAB_OUT"
[[ $SIGNED -eq 0 ]] && echo "(unsigned — add keystore.properties before uploading to Play)"
[[ $OPEN -eq 1 ]] && open "$OUT"
exit 0
