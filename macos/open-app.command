#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
APP_PATH="/Applications/GlideDrop.app"

echo "================================================="
echo " GlideDrop Launcher & Gatekeeper Unblock"
echo "================================================="

TARGET_APP="$DIR/GlideDrop.app"
LAUNCH_APP=""

if [ -d "$DIR/GlideDrop.app" ]; then
    echo "[+] Installing/updating GlideDrop in /Applications..."
    if rm -rf "$APP_PATH" 2>/dev/null && cp -R "$DIR/GlideDrop.app" /Applications/ 2>/dev/null; then
        LAUNCH_APP="$APP_PATH"
    else
        echo "[!] Cannot write to /Applications without admin privileges; launching bundled copy..."
        LAUNCH_APP="$DIR/GlideDrop.app"
    fi
elif [ -d "$APP_PATH" ]; then
    LAUNCH_APP="$APP_PATH"
else
    echo "[-] Error: GlideDrop.app not found. Please drag it to Applications first."
    exit 1
fi

echo "[+] Clearing Gatekeeper quarantine attribute on $LAUNCH_APP..."
if ! xattr -cr "$LAUNCH_APP" 2>/dev/null; then
    echo "[-] Warning: Could not clear quarantine on $LAUNCH_APP (check permissions or copy to Applications)."
fi
echo "[+] Launching $LAUNCH_APP..."
open "$LAUNCH_APP"

echo "[+] Done! GlideDrop is now running in your menu bar."

