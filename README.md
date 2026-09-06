# aideas
Dumping ground for the projects I've always wanted, implemented with AI

android-thermal-viewer - Infiray P2 Pro viewer application for Android. I got tired of the official application being the only option. It doesn't reflow nicely with screen/window changes. This version keeps the viewport correct no matter how it's laid out. Tap to hide/show temps and overlay controls.

night_vision - Based on https://github.com/lvonasek/3DLiveScanner/tree/main/night_vision Viewer for the depth sensor on various Android devices. Tested with S20 Plus. Removed the onscreen buttons and updated the drawing to handle different sizes better as well. Tap to take picture. Long hold for menu. Streaming available to send rendered viewport to computer. Use the included python script (needs OpenCV) to view. 

gps - Frida scripts to hook and print RAW NMEA to termainl/pty. Run the python script alone for terminal. Pass in --pty to create /tmp/gps0 to use with gpsd. Regular is for Samsung. Motorola included separately.

meta_quest_webcam - Fix for Meta intentionally blocking USB webcam access. Two forms of the same one-line patch: a Frida hook for quick testing (needs a rooted headset with frida-server running, tested with Singularity), and meta_quest_webcam/lsposed, an LSPosed module that makes it permanent - no adb tether, no daemon, survives reboot.
