// Prevent GPS from stopping on Motorola (android.hardware.gnss-aidl-service-qti)
// Symbols pulled from libgnss.so / libloc_core.so on this device.

const toNeuter = [
    // libgnss.so
    { lib: 'libgnss.so', sym: '_ZN11GnssAdapter12stopTrackingEP11LocationAPIj' },
    { lib: 'libgnss.so', sym: '_ZN11GnssAdapter19stopTrackingCommandEP11LocationAPIj' },
    { lib: 'libgnss.so', sym: '_ZN11GnssAdapter30stopTimeBasedTrackingMultiplexEP11LocationAPIj' },
    { lib: 'libgnss.so', sym: '_ZN11GnssAdapter18stopClientSessionsEP11LocationAPIb' },
    // libloc_core.so
    { lib: 'libloc_core.so', sym: '_ZN8loc_core10LocApiBase21stopTimeBasedTrackingEPNS_14LocApiResponseE' },
    { lib: 'libloc_core.so', sym: '_ZN8loc_core14LocAdapterBase18stopClientSessionsEP11LocationAPIb' },
];

toNeuter.forEach(({ lib, sym }) => {
    const mod = Process.findModuleByName(lib);
    if (!mod) { console.log(`[-] ${lib} not loaded`); return; }

    const exp = mod.findExportByName(sym);
    if (!exp) { console.log(`[-] ${sym} not found in ${lib}`); return; }

    Interceptor.replace(exp, new NativeCallback(function () {}, 'void', ['pointer', 'pointer']));
    console.log(`[*] Neutered ${lib}!${sym}`);
});
