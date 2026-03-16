/*
 * nmea_hook.js - Frida script to intercept NMEA sentences on Qualcomm/Samsung Android
 *
 * Usage:
 *   frida -U -n system_server -l nmea_hook.js   (Java layer)
 *   frida -U --attach-pid 1238 -l nmea_hook.js  (native HAL layer)
 */

'use strict';

// ─── Deduplication & output ───────────────────────────────────────────────────
// Same sentence arrives from several hooks simultaneously; only print it once
// per unique (sentence-type, full-content) pair within a short time window.

const DEDUP_WINDOW_MS = 50;
const seen = new Map(); // key → last timestamp

function emit(nmea) {
    const now = Date.now();
    const key = nmea.trim();
    const last = seen.get(key);
    if (last !== undefined && (now - last) < DEDUP_WINDOW_MS) return;
    seen.set(key, now);

    send(key);
}

// ─── 1. Java Layer ───────────────────────────────────────────────────────────
// Hooks the Android framework NMEA dispatch path inside system_server.
// Works regardless of which app is foregrounded.

function hookJava() {
    Java.perform(function () {

        // Android 10+ path: GnssNmeaProvider (system_server)
        try {
            const GnssNmeaProvider = Java.use('com.android.server.location.gnss.GnssNmeaProvider');
            GnssNmeaProvider.reportNmea.overload('long', 'java.lang.String').implementation = function (timestamp, nmea) {
                emit(nmea);
                return this.reportNmea(timestamp, nmea);
            };
            console.log('[*] Hooked GnssNmeaProvider.reportNmea');
        } catch (e) {
            console.log('[-] GnssNmeaProvider not found: ' + e.message);
        }

        // Android 9 fallback: GnssLocationProvider
        try {
            const GnssLocationProvider = Java.use('com.android.server.location.GnssLocationProvider');
            // reportNmea(long timestamp, String nmea)
            GnssLocationProvider.reportNmea.overload('long', 'java.lang.String').implementation = function (timestamp, nmea) {
                emit(nmea);
                return this.reportNmea(timestamp, nmea);
            };
            console.log('[*] Hooked GnssLocationProvider.reportNmea');
        } catch (e) {
            console.log('[-] GnssLocationProvider not found: ' + e.message);
        }

        // Hook any registered OnNmeaMessageListener (app-level)
        try {
            const LocationManager = Java.use('android.location.LocationManager');
            // addNmeaListener registers callbacks — hook the dispatcher instead
            const NmeaAdapter = Java.use('android.location.LocationManager$NmeaAdapter');
            NmeaAdapter.onNmeaReceived.implementation = function (timestamp, nmea) {
                emit(nmea);
                return this.onNmeaReceived(timestamp, nmea);
            };
            console.log('[*] Hooked NmeaAdapter.onNmeaReceived');
        } catch (e) {
            console.log('[-] NmeaAdapter not found: ' + e.message);
        }
    });
}

// ─── 2. Native Layer ─────────────────────────────────────────────────────────
// Hooks C functions in Qualcomm loc stack that handle NMEA strings.
// Attach directly to gnss_service (PID 1238) for these to fire.

function hookNative() {
    const libs = [
        'libloc_core.so',
        'libgnss.so',
        'libloc_api_v02.so',
        'vendor.samsung.hardware.gnss@2.0.so',
    ];

    // Common symbol patterns across Qualcomm loc versions
    const nmeaSymbols = [
        'reportNmea',
        'onNmeaReceived',
        'GnssAdapter_reportNmea',
        'loc_eng_nmea_send',
        'LocApiBase_reportNmea',
    ];

    libs.forEach(function (libName) {
        const lib = Process.findModuleByName(libName);
        if (!lib) {
            console.log('[-] ' + libName + ' not loaded in this process');
            return;
        }
        console.log('[+] Found ' + libName + ' @ ' + lib.base);

        nmeaSymbols.forEach(function (sym) {
            const addr = lib.findExportByName(sym);
            if (!addr) return;

            console.log('[*] Hooking ' + libName + '!' + sym + ' @ ' + addr);
            Interceptor.attach(addr, {
                onEnter: function (args) {
                    // Most Qualcomm NMEA callbacks: (void* obj, const char* nmea, int len)
                    // Try reading arg[1] as a C string
                    try {
                        const nmea = args[1].readCString();
                        if (nmea && nmea.startsWith('$')) emit(nmea);
                    } catch (_) {}
                }
            });
        });
    });

    // Broad scan: enumerate all exports containing "nmea" (case-insensitive)
    libs.forEach(function (libName) {
        const lib = Process.findModuleByName(libName);
        if (!lib) return;

        lib.enumerateExports().forEach(function (exp) {
            if (exp.type !== 'function') return;
            if (!exp.name.toLowerCase().includes('nmea')) return;
            if (exp.name.toLowerCase().includes('config') ||
                exp.name.toLowerCase().includes('init')) return; // skip non-data paths

            try {
                Interceptor.attach(exp.address, {
                    onEnter: function (args) {
                        for (let i = 0; i < 3; i++) {
                            try {
                                const s = args[i].readCString();
                                if (s && s.startsWith('$') && s.length > 5) { emit(s); break; }
                            } catch (_) {}
                        }
                    }
                });
            } catch (e) {
                console.log('[-] Failed to hook ' + exp.name + ': ' + e.message);
            }
        });
    });
}

// ─── Entry point ─────────────────────────────────────────────────────────────

console.log('[*] nmea_hook.js loaded');
console.log('[*] Process PID ' + Process.id);

if (typeof Java !== 'undefined' && Java.available) {
    console.log('[*] Java runtime available — hooking Java layer');
    hookJava();
}

hookNative();
