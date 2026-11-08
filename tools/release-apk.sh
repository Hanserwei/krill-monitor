#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
tag="${1:?Usage: tools/release-apk.sh v0.1.0}"
version=$(sed -n 's/^[[:space:]]*versionName: "\([0-9][0-9.]*\)"$/\1/p' androidApp/module.yaml)
if [[ ! "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ || "$tag" != "v$version" ]]; then
  echo "Release tag must match androidApp/module.yaml versionName: v$version" >&2
  exit 1
fi
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
: "${ANDROID_KEYSTORE_PATH:?Set ANDROID_KEYSTORE_PATH to the release PKCS12 keystore}"
: "${ANDROID_KEYSTORE_PASSWORD:?Set ANDROID_KEYSTORE_PASSWORD}"
if [[ ! -f "$ANDROID_KEYSTORE_PATH" ]]; then
  echo "Release keystore does not exist" >&2
  exit 1
fi
build_tools="$ANDROID_HOME/build-tools/37.0.0"
for tool in zipalign apksigner aapt; do
  if [[ ! -x "$build_tools/$tool" ]]; then
    echo "Missing Android build tool: $tool (37.0.0)" >&2
    exit 1
  fi
done

./kotlin build -m androidApp --variant release
unsigned="build/tasks/_androidApp_buildAndroidRelease/gradle-project-release-unsigned.apk"
if [[ ! -f "$unsigned" ]]; then
  echo "Release APK was not produced at $unsigned" >&2
  exit 1
fi
mkdir -p dist
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
apk="krill-monitor-$version.apk"
"$build_tools/zipalign" -P 16 -f 4 "$unsigned" "$scratch/aligned.apk"
"$build_tools/apksigner" sign \
  --ks "$ANDROID_KEYSTORE_PATH" \
  --ks-key-alias krill-monitor \
  --ks-pass env:ANDROID_KEYSTORE_PASSWORD \
  --out "$scratch/$apk" "$scratch/aligned.apk"
"$build_tools/apksigner" verify --verbose --print-certs "$scratch/$apk" > "$scratch/ANDROID-SIGNING-CERT.txt"
"$build_tools/zipalign" -c -P 16 4 "$scratch/$apk"
badging=$("$build_tools/aapt" dump badging "$scratch/$apk")
grep -F "package: name='com.hanserwei.krill_monitor' versionCode='" <<< "$badging" >/dev/null
grep -F "versionName='$version'" <<< "$badging" >/dev/null
if grep -q '^application-debuggable' <<< "$badging"; then
  echo "Refusing to publish a debuggable APK" >&2
  exit 1
fi
mv "$scratch/$apk" "dist/$apk"
mv "$scratch/ANDROID-SIGNING-CERT.txt" dist/ANDROID-SIGNING-CERT.txt
(cd dist && sha256sum "$apk" > SHA256SUMS)
printf 'Signed release: dist/%s\n' "$apk"
