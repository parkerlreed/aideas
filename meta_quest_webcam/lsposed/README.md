# Quest USB Cam Unlock (LSPosed module)

Persistent version of `../patch_permission.js`. Same single behaviour change --
`com.android.server.usb.UsbUserPermissionManager.hasPermission()` is forced to
return `true` inside `system_server`, so `UsbManager.openDevice()` finally hands
back a usable connection for UVC cameras and HDMI capture sticks.

Unlike the Frida script this needs no adb tether, no `frida-server`, and no
foreground REPL. It survives reboot.

## Requirements

- Rooted Quest (tested on Singularity) with Magisk or KernelSU, Zygisk enabled
- LSPosed installed and active

## Build

The Android SDK and a JDK are the only prerequisites; there is no wrapper, so
the system `gradle` is used.

```bash
ANDROID_HOME=~/Android/Sdk gradle assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (~9 KB).

`assembleDebug` is the intended target -- LSPosed does not check signatures, and
a debug-signed APK installs without extra keystore setup.

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then in the LSPosed manager:

1. Modules -> enable **Quest USB Cam Unlock**
2. Scope -> tick **System Framework** (the module already requests it, so it
   should be pre-ticked)
3. Reboot

To confirm it took, check the LSPosed log for:

```
[QuestUsbCamUnlock] installed, N hasPermission() overload(s) forced true
```

and, after you first open a camera app, `first forced grant via hasPermission`.

## Notes

- **This grants USB device permission to every app, for every device.** That is
  exactly what the Frida script did, but it is a system-wide relaxation, not a
  per-app one. If you want it narrowed, filter on `packageName` (arg 1 of the
  4-arg overload) in `UsbPermissionHook`.
- Every `hasPermission` overload on the class is hooked rather than one pinned
  signature -- the overload set differs between Android versions
  (`UsbDevice`/`UsbAccessory`, with and without the package name), and a Quest OS
  update should not silently break the module. `UsbUserSettingsManager` is also
  tried, for Android 11 and earlier where `hasPermission` lived there.
- The hook runs in `system_server`, where an uncaught throw is a bootloop, so
  every step fails open: missing class means the module logs and does nothing.
  If you do manage to bootloop the device, remove the module by deleting it from
  `/data/adb/lsposed/` in recovery, or boot with Magisk core-only mode.
- `hasPermission()` is a hot path, so only the first forced grant is logged, not
  every call (the Frida script logged all of them).
