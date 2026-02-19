#!/bin/bash
# build_deploy_launch.sh
# Automates build, deploy, and launch for Zebra RFID SDK Sample Application

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Optional device override:
# 1) DEVICE_ID env var
# 2) ANDROID_SERIAL env var
DEVICE_ID="${DEVICE_ID:-${ANDROID_SERIAL:-}}"

ensure_android_sdk() {
  if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "${ANDROID_SDK_ROOT}" ]; then
    export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT}}"
    return
  fi

  if [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}" ]; then
    export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME}}"
    return
  fi

  for candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [ -d "$candidate" ]; then
      export ANDROID_HOME="$candidate"
      export ANDROID_SDK_ROOT="$candidate"
      echo "Using Android SDK: $candidate"
      return
    fi
  done

  echo "Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT to a valid SDK path."
  exit 1
}

ensure_adb_available() {
  if command -v adb >/dev/null 2>&1; then
    return
  fi

  sdk_platform_tools="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/platform-tools"
  if [ -n "$sdk_platform_tools" ] && [ -x "$sdk_platform_tools/adb" ]; then
    export PATH="$sdk_platform_tools:$PATH"
    return
  fi

  echo "adb not found in PATH. Install Android platform-tools or add adb to PATH."
  exit 1
}

resolve_device_id() {
  if [ -n "$DEVICE_ID" ]; then
    echo "$DEVICE_ID"
    return
  fi

  devices_raw="$(adb devices | awk '/\tdevice$/{print $1}')"
  if [ -z "$devices_raw" ]; then
    echo ""
    return
  fi

  first_device="$(printf '%s\n' "$devices_raw" | head -n 1)"
  device_count="$(printf '%s\n' "$devices_raw" | wc -l | tr -d ' ')"

  if [ "$device_count" -gt 1 ]; then
    echo "Multiple devices detected; using first device: $first_device" >&2
    echo "Set DEVICE_ID or ANDROID_SERIAL to choose a different target." >&2
  fi

  echo "$first_device"
}

# Build the project
ensure_android_sdk
ensure_adb_available
./gradlew assembleDebug

# Find the APK path
echo "Locating APK..."
APK_PATH=$(find ./app/build/outputs/apk/debug -name "*.apk" | head -n 1)
if [ ! -f "$APK_PATH" ]; then
  echo "APK not found! Build may have failed."
  exit 1
fi

echo "APK found at $APK_PATH"

TARGET_DEVICE_ID="$(resolve_device_id)"
if [ -z "$TARGET_DEVICE_ID" ]; then
  echo "No connected Android device found (adb devices returned none in 'device' state)."
  echo "Build completed and APK is ready at $APK_PATH"
  exit 0
fi

echo "Using device: $TARGET_DEVICE_ID"

# Deploy to device
echo "Deploying APK to device..."
adb -s "$TARGET_DEVICE_ID" install -r "$APK_PATH"

echo "APK deployed. Launching app..."

# Launch the app (replace with your actual package/activity)
PACKAGE="com.zebra.rfid.demo.sdksample"
ACTIVITY=".MainActivity"
adb -s "$TARGET_DEVICE_ID" shell am start -n "$PACKAGE/$ACTIVITY"

echo "App launched!"
