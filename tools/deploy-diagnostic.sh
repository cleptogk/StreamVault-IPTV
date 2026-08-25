#!/usr/bin/env bash

set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly APK_PATH="${PROJECT_DIR}/app/build/outputs/apk/diagnostic/app-diagnostic.apk"

usage() {
    cat <<'EOF'
Usage: tools/deploy-diagnostic.sh [ADB_SERIAL]

Builds the signed, unshrunk diagnostic APK and installs it in place with
`adb install -r`. Set SHIELD_ADB_SERIAL instead of passing ADB_SERIAL when
using this from an IDE or another automation.

Examples:
  SHIELD_ADB_SERIAL=192.0.2.10:5555 tools/deploy-diagnostic.sh
  tools/deploy-diagnostic.sh 192.0.2.10:5555

The existing Sports Wall package data is preserved. Release builds are not
changed or run by this command.
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    usage
    exit 0
fi

if (( $# > 1 )); then
    usage >&2
    exit 2
fi

readonly ADB_SERIAL="${1:-${SHIELD_ADB_SERIAL:-}}"
if [[ -z "${ADB_SERIAL}" ]]; then
    echo "Missing Shield ADB serial. Pass it as an argument or set SHIELD_ADB_SERIAL." >&2
    exit 2
fi

java_major="$(java -version 2>&1 | awk -F '[\".]' '/version/ { print $2; exit }' || true)"
if [[ "${java_major}" != "17" ]]; then
    if [[ -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java ]]; then
        JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
    elif [[ -x /usr/libexec/java_home ]]; then
        JAVA_HOME="$(/usr/libexec/java_home -v 17)"
    fi
    export JAVA_HOME
fi

for command_name in adb java; do
    if ! command -v "${command_name}" >/dev/null 2>&1; then
        echo "Required command not found: ${command_name}" >&2
        exit 1
    fi
done

java_major="$(java -version 2>&1 | awk -F '[\".]' '/version/ { print $2; exit }')"
if [[ "${java_major}" != "17" ]]; then
    echo "JDK 17 is required for this Android build; found Java ${java_major:-unknown}." >&2
    exit 1
fi

has_environment_signing=true
for signing_variable in \
    SPORTS_WALL_STORE_FILE \
    SPORTS_WALL_STORE_PASSWORD \
    SPORTS_WALL_KEY_ALIAS \
    SPORTS_WALL_KEY_PASSWORD; do
    if [[ -z "${!signing_variable:-}" ]]; then
        has_environment_signing=false
        break
    fi
done

if [[ "${has_environment_signing}" != "true" && ! -s "${PROJECT_DIR}/keystore.properties" ]]; then
    echo "Diagnostic deployment requires the Sports Wall release signing environment or keystore.properties." >&2
    exit 1
fi

# A TCP serial may not yet be present in this adb server. Connecting before the
# build fails fast on an unreachable Shield and avoids wasting a compile cycle.
if [[ "${ADB_SERIAL}" == *:* ]] && ! adb devices | awk 'NR > 1 { print $1 }' | grep -Fxq "${ADB_SERIAL}"; then
    adb connect "${ADB_SERIAL}" >/dev/null
fi

if [[ "$(adb -s "${ADB_SERIAL}" get-state 2>/dev/null || true)" != "device" ]]; then
    echo "Shield is not available through adb: ${ADB_SERIAL}" >&2
    exit 1
fi

build_started_at=${SECONDS}
(
    cd "${PROJECT_DIR}"
    ./gradlew :app:assembleDiagnostic \
        --daemon \
        --parallel \
        --build-cache \
        --console=plain \
        -x lintVitalAnalyzeDiagnostic \
        -x lintVitalReportDiagnostic \
        -x lintVitalDiagnostic
)
readonly build_elapsed=$((SECONDS - build_started_at))

if [[ ! -f "${APK_PATH}" ]]; then
    echo "Diagnostic APK was not produced at ${APK_PATH}" >&2
    exit 1
fi

install_started_at=${SECONDS}
adb -s "${ADB_SERIAL}" install -r "${APK_PATH}"
readonly install_elapsed=$((SECONDS - install_started_at))

echo "Diagnostic deployed to ${ADB_SERIAL} (build ${build_elapsed}s, install ${install_elapsed}s)."
