#!/usr/bin/env bash
set -e

# Maestro E2E Test Runner for Glia SDK Sample App
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

MAESTRO_BIN="${MAESTRO_BIN:-$(which maestro 2>/dev/null || echo "$HOME/.maestro/bin/maestro")}"

if [ ! -x "$MAESTRO_BIN" ]; then
    echo "❌ Maestro CLI not found. Please install via: curl -fsSL 'https://get.maestro.mobile.dev' | bash"
    exit 1
fi

echo "🚀 Building Glia Sample App..."
./gradlew :sample:assembleDebug --no-daemon

APK_PATH="sample/build/outputs/apk/debug/sample-debug.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "❌ APK not found at $APK_PATH"
    exit 1
fi

# Check for connected ADB devices/emulators
DEVICE_COUNT=$(adb devices | grep -v "List of devices" | grep "device$" | wc -l | tr -d ' ')

if [ "$DEVICE_COUNT" -eq "0" ]; then
    echo "⚠️  No active Android device or emulator detected via ADB."
    echo "   Please start an emulator (e.g. 'emulator -avd Pixel_5_Lite &') or connect a device, then re-run:"
    echo "   ./scripts/run_maestro.sh"
    exit 0
fi

echo "📲 Installing APK ($APK_PATH) on device..."
adb install -r "$APK_PATH"

echo "🧪 Running Maestro E2E Flows (.maestro/)..."
"$MAESTRO_BIN" test .maestro/

echo "✅ Maestro E2E tests completed successfully!"
