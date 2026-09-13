#!/usr/bin/env bash
# macOS/Linux app actions. Never fall back to unfiltered device logs.
set -euo pipefail
usage() { printf 'Usage: %s {launch|stop|logcat} [logcat-output-file]\n' "$0" >&2; exit 2; }
[[ $# -ge 1 && $# -le 2 ]] || usage
action=$1
output=${2:-}
case "$action" in launch|stop) [[ $# -eq 1 ]] || usage ;; logcat) ;; *) usage ;; esac
root=$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
# BSD sed and GNU sed both support this; stock macOS has no grep -P.
app_id=$(sed -n 's/^[[:space:]]*applicationId[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$root/app/build.gradle.kts")
[[ "$app_id" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] || {
    printf 'A single literal applicationId is required.\n' >&2; exit 2;
}
adb=adb
if ! command -v "$adb" >/dev/null 2>&1; then
    sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
    [[ -n "$sdk" && -x "$sdk/platform-tools/adb" ]] || { printf 'adb was not found.\n' >&2; exit 2; }
    adb="$sdk/platform-tools/adb"
fi
# ADB honors ANDROID_SERIAL; ambiguous/missing devices fail instead of choosing one.
case "$action" in
    launch) exec "$adb" shell monkey -p "$app_id" -c android.intent.category.LAUNCHER 1 ;;
    stop) exec "$adb" shell am force-stop "$app_id" ;;
    logcat)
        if ! process_id=$("$adb" shell pidof -s "$app_id" | tr -d '\r'); then
            printf 'Could not resolve the app PID. No logs were collected.\n' >&2; exit 2
        fi
        if [[ ! "$process_id" =~ ^[1-9][0-9]*$ || ${#process_id} -gt 10 ]] || (( process_id > 2147483647 )); then
            printf 'App is not running, or PID is invalid. No logs were collected.\n' >&2; exit 2
        fi
        if [[ -n "$output" ]]; then
            # Prevent an output name from being interpreted as a tee option.
            [[ "$output" != -* ]] || output="./$output"
            "$adb" logcat --pid "$process_id" -v time | tee "$output"
        else
            exec "$adb" logcat --pid "$process_id" -v time
        fi
        ;;
esac
