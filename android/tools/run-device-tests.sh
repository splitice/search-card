#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew connectedDebugAndroidTest --console=plain \
  -Pandroid.testInstrumentationRunnerArguments.notClass=com.splitice.searchcard.LiveAccountTest

if [[ -n "${HA_RUNTIME_ACCOUNT:-}" ]]; then
  mkdir -p device-diagnostics/standard-tests
  cp -R app/build/reports/androidTests device-diagnostics/standard-tests/
  # Stop diagnostic collection before authentication; logcat can include callback URLs.
  pkill -f '^adb logcat' || true
  adb logcat -c
  cleanup() {
    adb shell run-as com.splitice.searchcard rm -f files/runtime-account.json || true
    unset HA_RUNTIME_ACCOUNT
  }
  trap cleanup EXIT
  printf '%s' "$HA_RUNTIME_ACCOUNT" | adb shell run-as com.splitice.searchcard sh -c \
    '"umask 077; cat > files/runtime-account.json"'
  unset HA_RUNTIME_ACCOUNT
  ./gradlew connectedDebugAndroidTest --console=plain \
    -Pandroid.testInstrumentationRunnerArguments.class=com.splitice.searchcard.LiveAccountTest
fi
