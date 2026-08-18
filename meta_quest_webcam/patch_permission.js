Java.perform(function () {
    var UsbUserPermissionManager = Java.use("com.android.server.usb.UsbUserPermissionManager");

    UsbUserPermissionManager.hasPermission.overload(
        'android.hardware.usb.UsbDevice', 'java.lang.String', 'int', 'int'
    ).implementation = function (device, pkg, pid, uid) {
        console.log("[PATCH] hasPermission forced TRUE for pkg=" + pkg + " device=" + device.getDeviceName());
        return true;
    };

    console.log("[PATCH] hasPermission patch installed and active.");
});

