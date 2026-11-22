#!/usr/bin/env bash
# NewTube for Android Auto - installer (macOS / Linux). Same steps as install.ps1:
# Android Auto may refuse car apps recorded as installed by adb or a file manager, so the APK is
# handed to the phone's system installer with Google Play named as the installer.
#   ./install.sh [path-or-https-URL-of-the-APK]
# With no argument it uses the NewTube*.apk next to it, or downloads the latest release.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
latest="https://github.com/ruipereira1/newtube-android-auto/releases/latest/download/NewTube-Android-Auto-arm64-v8a.apk"
packages="io.github.aleixrodriala.arc.auto io.github.aleixrodriala.arc.auto.debug"
fail() { echo; echo "ERROR: $*" >&2; exit 1; }

apk="${1:-}"
if [ -z "$apk" ]; then
  apk="$(ls -t "$here"/NewTube*.apk 2>/dev/null | head -1 || true)"
  [ -n "$apk" ] || apk="$latest"
fi
case "$apk" in http://*|https://*)
  echo "Downloading the app: $apk"; curl -fL -o /tmp/NewTube-Auto-download.apk "$apk"; apk=/tmp/NewTube-Auto-download.apk;;
esac
[ -f "$apk" ] || fail "APK not found: $apk"
echo "APK: $apk"

adb="$(command -v adb || true)"
if [ -z "$adb" ]; then
  adb="$here/platform-tools/adb"
  if [ ! -x "$adb" ]; then
    os=linux; [ "$(uname)" = Darwin ] && os=darwin
    echo "Downloading Android platform-tools (adb) from Google..."
    curl -fL -o /tmp/platform-tools.zip "https://dl.google.com/android/repository/platform-tools-latest-$os.zip"
    unzip -qo /tmp/platform-tools.zip -d "$here"
  fi
fi

"$adb" start-server >/dev/null 2>&1
devices() { "$adb" devices | awk -F'\t' '$2=="device"{print $1}'; }
serial="$(devices | head -1)"
if [ -z "$serial" ]; then
  svc="$("$adb" mdns services 2>/dev/null | awk '/_adb-tls-connect/{print $NF; exit}')"
  [ -n "$svc" ] && "$adb" connect "$svc" >/dev/null 2>&1 || true
  serial="$(devices | head -1)"
fi
if [ -z "$serial" ]; then
  echo "Waiting for the phone. On the phone: Settings > Developer options > USB debugging ON,"
  echo "plug it in with a cable and tap 'Allow' on the prompt."
  for _ in $(seq 60); do sleep 3; serial="$(devices | head -1)"; [ -n "$serial" ] && break; done
fi
[ -n "$serial" ] || fail "No phone found."
echo "Phone: $serial"
p() { "$adb" -s "$serial" "$@"; }
state() { for k in $packages; do p shell dumpsys package "$k" | grep -E 'lastUpdateTime|initiatingPackageName' | tr -d ' \r' || true; done; }
before="$(state)"

echo "Copying the app to the phone..."
p push "$apk" /sdcard/Download/NewTube-Auto.apk >/dev/null
p shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/NewTube-Auto.apk >/dev/null
id=""
for _ in $(seq 10); do
  sleep 1
  id="$(p shell "content query --uri content://media/external/file --projection _id --where \"_data LIKE '%/Download/NewTube-Auto.apk'\"" \
        | sed -n 's/.*_id=\([0-9]*\).*/\1/p' | tail -1)"
  [ -n "$id" ] && break
done
[ -n "$id" ] || fail "The phone did not index the APK. Try again."

p shell input keyevent KEYCODE_WAKEUP >/dev/null
p shell am start -a android.intent.action.INSTALL_PACKAGE -d "content://media/external/file/$id" \
  -t application/vnd.android.package-archive \
  --es android.intent.extra.INSTALLER_PACKAGE_NAME com.android.vending --grant-read-uri-permission >/dev/null
echo
echo "On the phone (unlock it if needed): tap Install / Update."
echo "If Google Play Protect warns about the app: More details > Install anyway."

for _ in $(seq 100); do
  sleep 3
  now="$(state)"
  if [ "$now" != "$before" ]; then
    echo "$now" | grep -q packageinstaller || fail "Installed, but not through the system installer: Android Auto may refuse it. Run this again."
    p shell rm -f /sdcard/Download/NewTube-Auto.apk >/dev/null
    echo; echo "Done. Android Auto will list NewTube; reconnect the car."; exit 0
  fi
done
fail "The install was not confirmed on the phone in 5 minutes. Run this again."
