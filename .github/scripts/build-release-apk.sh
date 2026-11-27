#!/usr/bin/env bash
set -euo pipefail

umask 077
export ALIFLIX_KEYSTORE_FILE="$RUNNER_TEMP/aliflix-release.jks"
trap 'rm -f "$ALIFLIX_KEYSTORE_FILE"' EXIT
test -n "$KEYSTORE_BASE64"
test -n "$ALIFLIX_KEYSTORE_PASSWORD"
test -n "$ALIFLIX_KEY_ALIAS"
test -n "$ALIFLIX_KEY_PASSWORD"
printf '%s' "$KEYSTORE_BASE64" | base64 --decode > "$ALIFLIX_KEYSTORE_FILE"
unset KEYSTORE_BASE64

certificate="$(
  keytool -exportcert -keystore "$ALIFLIX_KEYSTORE_FILE" \
    -storepass:env ALIFLIX_KEYSTORE_PASSWORD -alias "$ALIFLIX_KEY_ALIAS" |
    sha256sum | cut -d ' ' -f1
)"
test "$certificate" = "$EXPECTED_MOBILE_SIGNER_SHA256"

gradle_args=()
if [[ "$RELEASE_APK" == 'aliflix-mobile-compat.apk' ]]; then
  gradle_args+=(-Paliflix.mobileCompatBridge=true)
  RELEASE_VERSION_NAME=3.1.175
  RELEASE_VERSION_CODE=265
fi
bash gradlew :app:packageMobileRelease "${gradle_args[@]}"
cp app/build/outputs/apk/mobile/release/app-mobile-release.apk "$RELEASE_APK"

build_tools="$ANDROID_HOME/build-tools/36.0.0"
certificate="$(
  "$build_tools/apksigner" verify --print-certs "$RELEASE_APK" |
    sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p' | sort -u
)"
test "$certificate" = "$EXPECTED_MOBILE_SIGNER_SHA256"
badging="$("$build_tools/aapt" dump badging "$RELEASE_APK")"
grep -Fq "package: name='com.aliflix.app' versionCode='$RELEASE_VERSION_CODE' versionName='$RELEASE_VERSION_NAME'" <<< "$badging"
grep -Fxq "sdkVersion:'29'" <<< "$badging"
sha256sum "$RELEASE_APK" > "$RELEASE_APK.sha256"
