#!/usr/bin/env bash
# Workaround for the Quest USB-camera/HDMI-capture regression:
# com.android.server.usb.UsbUserPermissionManager.hasPermission() always
# returns false, even when dumpsys usb shows the permission as granted,
# so UsbManager.openDevice() never hands back a usable connection to any
# app (nextcamera, USB Camera, etc) for any UVC device.
#
# This attaches Frida to system_server and forces that one method to
# return true. It must stay running (foreground, in a REPL) for as long
# as you want USB camera/capture access to work. Ctrl+C to stop it.
#
# Needs: adb connected + rooted device (adb root already up), frida-tools
# installed locally (pip install --user frida-tools), frida-server running
# on-device (as root) at a version matching the local frida client.

set -euo pipefail

export PATH="$HOME/.local/bin:$PATH"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PID="$(adb shell pidof system_server | tr -d '\r\n ')"
if [ -z "$PID" ]; then
    echo "Could not find system_server pid. Is the device connected (adb devices -l)?" >&2
    exit 1
fi

echo "Attaching to system_server (pid $PID)..."
echo "Leave this running, then unplug/replug your USB camera or reopen its app."
echo "Ctrl+C to stop the patch."
echo

frida -U -p "$PID" -l "$SCRIPT_DIR/patch_permission.js"

