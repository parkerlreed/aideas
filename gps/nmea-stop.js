// Prevent GPS from stopping when apps release location requests.
// Uses actual mangled C++ symbols from libgnss.so / libloc_core.so.

const toNeuter = [
    // libgnss.so
    { lib: 'libgnss.so', sym: '_ZN11GnssAdapter12stopTrackingEP11LocationAPIj' },
    { lib: 'libgnss.so', sym: '_ZN11GnssAdapter19stopTrackingCommandEP11LocationAPIj' },
    { lib: 'libgnss.so', sym: '_ZN11GnssAdapter30stopTimeBasedTrackingMultiplexEP11LocationAPIj' },
    { lib: 'libgnss.so', sym: '_ZN11GnssAdapter18stopClientSessionsEP11LocationAPI' },
    // libloc_core.so
    { lib: 'libloc_core.so', sym: '_ZN8loc_core10LocApiBase7stopFixEPNS_14LocApiResponseE' },
    { lib: 'libloc_core.so', sym: '_ZN8loc_core10LocApiBase21stopTimeBasedTrackingEPNS_14LocApiResponseE' },
    { lib: 'libloc_core.so', sym: '_ZN8loc_core14LocAdapterBase18stopClientSessionsEP11LocationAPI' },
];

toNeuter.forEach(({ lib, sym }) => {
    const mod = Process.findModuleByName(lib);
    if (!mod) { console.log(`[-] ${lib} not loaded`); return; }

    const exp = mod.findExportByName(sym);
    if (!exp) { console.log(`[-] ${sym} not found in ${lib}`); return; }

    Interceptor.replace(exp, new NativeCallback(function () {}, 'void', ['pointer', 'pointer']));
    console.log(`[*] Neutered ${lib}!${sym}`);
});
