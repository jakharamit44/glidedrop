#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
VERSION="$(cat "$ROOT_DIR/VERSION" 2>/dev/null || echo "3.0.9")"

echo "[+] Building GlideDrop macOS daemon (v${VERSION})..."
mkdir -p -m 700 "$HOME/.glidedrop"
chmod 700 "$HOME/.glidedrop" 2>/dev/null || true
DAEMON_OUT="$HOME/.glidedrop/glidedrop-daemon"
ARM64_BIN="$(mktemp /tmp/glidedrop-arm64.XXXXXX)"
X86_64_BIN="$(mktemp /tmp/glidedrop-x86_64.XXXXXX)"
trap 'rm -f "$ARM64_BIN" "$X86_64_BIN"' EXIT
if xcrun swiftc -O -target arm64-apple-macos13.0 "$SCRIPT_DIR/glidedrop-daemon.swift" -o "$ARM64_BIN" && \
   xcrun swiftc -O -target x86_64-apple-macos13.0 "$SCRIPT_DIR/glidedrop-daemon.swift" -o "$X86_64_BIN"; then
  lipo -create "$ARM64_BIN" "$X86_64_BIN" -output "$DAEMON_OUT"
  echo "[+] Built universal binary (arm64 + x86_64)."
else
  echo "[!] Universal cross-compile unavailable; falling back to host architecture." >&2
  xcrun swiftc -O "$SCRIPT_DIR/glidedrop-daemon.swift" -o "$DAEMON_OUT"
fi

APP_DIR="$ROOT_DIR/GlideDrop.app"
APP_MACOS="$APP_DIR/Contents/MacOS"
APP_RES="$APP_DIR/Contents/Resources"
mkdir -p "$APP_MACOS" "$APP_RES"

cp -f "$HOME/.glidedrop/glidedrop-daemon" "$APP_MACOS/GlideDrop"
cp -f "$SCRIPT_DIR/glidedrop.sh" "$APP_RES/glidedrop.sh"
cp -f "$SCRIPT_DIR/claude_processor.py" "$APP_RES/claude_processor.py"
cp -f "$SCRIPT_DIR/glidedrop.sh" "$HOME/.glidedrop/glidedrop.sh"
cp -f "$SCRIPT_DIR/claude_processor.py" "$HOME/.glidedrop/claude_processor.py"
if [ ! -f "$SCRIPT_DIR/AppIcon.icns" ]; then
  echo "[+] Generating AppIcon.icns..."
  swift "$SCRIPT_DIR/generate-icon.swift" "$SCRIPT_DIR/AppIcon.icns"
fi
if [ -f "$SCRIPT_DIR/AppIcon.icns" ]; then
  cp -f "$SCRIPT_DIR/AppIcon.icns" "$APP_RES/AppIcon.icns"
  cp -f "$SCRIPT_DIR/AppIcon.icns" "$HOME/.glidedrop/AppIcon.icns"
fi
chmod +x "$APP_RES/glidedrop.sh" "$APP_RES/claude_processor.py" "$HOME/.glidedrop/glidedrop.sh" "$HOME/.glidedrop/claude_processor.py"

cat > "$APP_DIR/Contents/Info.plist" << PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleExecutable</key>
  <string>GlideDrop</string>
  <key>CFBundleIconFile</key>
  <string>AppIcon</string>
  <key>CFBundleIdentifier</key>
  <string>io.github.jakharamit44.glidedrop.mac</string>
  <key>CFBundleName</key>
  <string>GlideDrop</string>
  <key>CFBundleDisplayName</key>
  <string>GlideDrop</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>${VERSION}</string>
  <key>CFBundleVersion</key>
  <string>${VERSION}</string>
  <key>LSMinimumSystemVersion</key>
  <string>13.0</string>
  <key>NSHighResolutionCapable</key>
  <true/>
  <key>NSBluetoothAlwaysUsageDescription</key>
  <string>GlideDrop uses Bluetooth LE L2CAP to discover and transfer files with Android devices.</string>
  <key>NSLocationUsageDescription</key>
  <string>GlideDrop needs Location access so macOS lets it see Wi-Fi network names when joining a nearby phone's Wi-Fi Direct group and returning to your home Wi-Fi. Your location is never read or stored.</string>
  <key>NSLocationWhenInUseUsageDescription</key>
  <string>GlideDrop needs Location access so macOS lets it see Wi-Fi network names when joining a nearby phone's Wi-Fi Direct group and returning to your home Wi-Fi. Your location is never read or stored.</string>
  <key>NSLocalNetworkUsageDescription</key>
  <string>GlideDrop requires local network access to discover nearby devices and transfer files over high-speed Wi-Fi Direct and local sockets.</string>
</dict>
</plist>
PLIST

# Strip extended attributes and seal bundle with deep code signature.
# For Developer ID builds (GLIDEDROP_CODESIGN_IDENTITY set), use Hardened Runtime and certificate requirement.
# For ad-hoc builds (`-`), use the default cdhash designated requirement instead of an unconstrained identifier rule
# so unrelated local binaries claiming the bundle identifier cannot inherit TCC grants.
APP_ID="io.github.jakharamit44.glidedrop.mac"
SIGN_IDENTITY="${GLIDEDROP_CODESIGN_IDENTITY:--}"
sign_app() {
  if [ "$SIGN_IDENTITY" != "-" ]; then
    codesign --force --deep --options runtime --sign "$SIGN_IDENTITY" --identifier "$APP_ID" "$1"
  else
    codesign --force --deep --sign - --identifier "$APP_ID" "$1"
  fi
}
sign_app "$APP_DIR"
codesign --verify --deep --strict "$APP_DIR"

if [ -w "/Applications" ] || [ -w "/Applications/GlideDrop.app" ]; then
  echo "[+] Updating /Applications/GlideDrop.app..."
  rm -rf "/Applications/GlideDrop.app"
  cp -R "$APP_DIR" "/Applications/GlideDrop.app"
  xattr -cr "/Applications/GlideDrop.app"
  sign_app "/Applications/GlideDrop.app"
fi

echo "[+] GlideDrop.app bundle created at: $APP_DIR"

