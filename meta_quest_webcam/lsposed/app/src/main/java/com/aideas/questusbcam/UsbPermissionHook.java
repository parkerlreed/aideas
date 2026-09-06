package com.aideas.questusbcam;

import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Works around the Meta Quest USB-camera/HDMI-capture block:
 * UsbUserPermissionManager.hasPermission() returns false even when
 * "dumpsys usb" shows the permission as granted, so UsbManager.openDevice()
 * never returns a usable connection for any UVC device.
 *
 * This is the persistent form of patch_permission.js (the Frida script in the
 * parent directory) - same single behaviour change, no daemon, survives reboot.
 *
 * Everything here runs inside system_server, where an uncaught throw is a
 * bootloop, so every step fails open instead of propagating.
 */
public class UsbPermissionHook implements IXposedHookLoadPackage {

    private static final String TAG = "[QuestUsbCamUnlock] ";

    /** Android 12+ (Quest 2/3 current builds). */
    private static final String CLASS_PERMISSION_MANAGER =
            "com.android.server.usb.UsbUserPermissionManager";
    /** Android 11 and earlier carried hasPermission() here instead. */
    private static final String CLASS_SETTINGS_MANAGER =
            "com.android.server.usb.UsbUserSettingsManager";

    /** Log the first forced grant only - hasPermission() is a hot path. */
    private static volatile boolean sLoggedFirstGrant = false;

    private final XC_MethodHook mForceTrue = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult(true);
            if (!sLoggedFirstGrant) {
                sLoggedFirstGrant = true;
                XposedBridge.log(TAG + "first forced grant via "
                        + param.method.getName() + " - USB camera access is unblocked");
            }
        }
    };

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        // system_server loads as package "android". Deliberately not also gating on
        // processName - the real gate is whether UsbUserPermissionManager is on this
        // classloader, which is true only inside system_server, and that survives any
        // change in how the process reports itself.
        if (!"android".equals(lpparam.packageName)) {
            return;
        }

        try {
            int hooked = hookHasPermission(lpparam, CLASS_PERMISSION_MANAGER)
                    + hookHasPermission(lpparam, CLASS_SETTINGS_MANAGER);
            if (hooked == 0) {
                XposedBridge.log(TAG + "no hasPermission() method found on either "
                        + CLASS_PERMISSION_MANAGER + " or " + CLASS_SETTINGS_MANAGER
                        + " - module is inert on this build");
            } else {
                XposedBridge.log(TAG + "installed, " + hooked + " hasPermission() overload(s) forced true");
            }
        } catch (Throwable t) {
            // Never let anything escape into system_server.
            XposedBridge.log(TAG + "install failed, leaving USB permissions untouched");
            XposedBridge.log(t);
        }
    }

    /**
     * Hooks every hasPermission() overload on the named class, if it exists.
     * Overloads differ across Android versions (UsbDevice/UsbAccessory, with and
     * without the package name and pid), so hook them all rather than pinning one
     * signature that a Quest OS update can rename out from under us.
     *
     * @return the number of methods hooked, 0 if the class is absent
     */
    private int hookHasPermission(LoadPackageParam lpparam, String className) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
        if (clazz == null) {
            return 0;
        }
        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(clazz, "hasPermission", mForceTrue);
        return unhooks.size();
    }
}
