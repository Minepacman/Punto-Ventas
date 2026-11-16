// Prueba de las funciones puras del asistente de avisos (pasosAvisos), extraídas de movil/index.html por PasosAvisosJsTest
const assert = require('assert');
const P = require(process.argv[2]);

const UA = {
  iphone17: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1',
  iphone163: 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_3_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.3 Mobile/15E148 Safari/604.1',
  iphone164: 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.4 Mobile/15E148 Safari/604.1',
  iphoneChrome: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/126.0 Mobile/15E148 Safari/604.1',
  ipadEscritorio: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15',
  mac: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15',
  android: 'Mozilla/5.0 (Linux; Android 14; SM-A146M) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36',
  androidFirefox: 'Mozilla/5.0 (Android 14; Mobile; rv:127.0) Gecko/127.0 Firefox/127.0',
  windows: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36',
};

assert.strictEqual(P.plataforma(UA.iphone17, 5), 'ios');
assert.strictEqual(P.plataforma(UA.ipadEscritorio, 5), 'ios');
assert.strictEqual(P.plataforma(UA.mac, 0), 'otro');
assert.strictEqual(P.plataforma(UA.android, 5), 'android');
assert.strictEqual(P.plataforma(UA.windows, 0), 'otro');

assert.deepStrictEqual(P.versionIos(UA.iphone17), [17, 5]);
assert.deepStrictEqual(P.versionIos(UA.iphone163), [16, 3]);
assert.deepStrictEqual(P.versionIos(UA.ipadEscritorio), [17, 4]);
assert.strictEqual(P.versionIos(UA.android), null);
assert.strictEqual(P.iosCompatible([16, 3]), false);
assert.strictEqual(P.iosCompatible([16, 4]), true);
assert.strictEqual(P.iosCompatible([15, 9]), false);
assert.strictEqual(P.iosCompatible([26, 0]), true);
assert.strictEqual(P.iosCompatible(null), true);
assert.strictEqual(P.esSafari(UA.iphone17), true);
assert.strictEqual(P.esSafari(UA.iphoneChrome), false);
assert.strictEqual(P.esFirefoxAndroid(UA.androidFirefox), true);
assert.strictEqual(P.esFirefoxAndroid(UA.android), false);

const base = { plataforma: 'android', versionIos: null, seguro: false, instalada: false, soportado: false, permiso: 'default',
  suscrito: false, httpsDisponible: true, llaveConfiable: false, avance: '' };
const sig = cambios => P.siguiente(Object.assign({}, base, cambios));

// Android por http
assert.deepStrictEqual(sig({}), { paso: 'descargar', numero: 1, total: 4 });
assert.deepStrictEqual(sig({ avance: 'descargada' }), { paso: 'instalar', numero: 2, total: 4 });
assert.deepStrictEqual(sig({ avance: 'instalada' }), { paso: 'instalar', numero: 2, total: 4 });
assert.deepStrictEqual(sig({ llaveConfiable: true }), { paso: 'abrir-segura', numero: 3, total: 4 });
assert.strictEqual(sig({ httpsDisponible: false }).paso, 'sin-https');
// Android por https
const seg = { seguro: true, soportado: true, llaveConfiable: null };
assert.deepStrictEqual(sig(seg), { paso: 'permitir', numero: 4, total: 4 });
assert.strictEqual(sig(Object.assign({ permiso: 'denied' }, seg)).paso, 'bloqueado');
assert.strictEqual(sig(Object.assign({ permiso: 'granted' }, seg)).paso, 'permitir');   // permiso sin suscripción: volver a suscribir
assert.deepStrictEqual(sig(Object.assign({ permiso: 'granted', suscrito: true }, seg)), { paso: 'listo', numero: 0, total: 4 });
assert.strictEqual(sig({ seguro: true, soportado: false }).paso, 'no-soportado');

// iPhone
const ios = { plataforma: 'ios', versionIos: [17, 5] };
assert.deepStrictEqual(sig(ios), { paso: 'descargar', numero: 1, total: 6 });
assert.deepStrictEqual(sig(Object.assign({ avance: 'descargada' }, ios)), { paso: 'instalar', numero: 2, total: 6 });
assert.deepStrictEqual(sig(Object.assign({ avance: 'instalada' }, ios)), { paso: 'confiar', numero: 3, total: 6 });
assert.deepStrictEqual(sig(Object.assign({ avance: 'instalada', llaveConfiable: true }, ios)), { paso: 'abrir-segura', numero: 4, total: 6 });
assert.deepStrictEqual(sig(Object.assign({ seguro: true, soportado: false }, ios)), { paso: 'agregar-inicio', numero: 5, total: 6 });
assert.deepStrictEqual(sig(Object.assign({ seguro: true, instalada: true, soportado: true }, ios)), { paso: 'permitir', numero: 6, total: 6 });
assert.strictEqual(sig(Object.assign({ seguro: true, instalada: true, soportado: true, permiso: 'granted', suscrito: true }, ios)).paso, 'listo');
assert.strictEqual(sig({ plataforma: 'ios', versionIos: [16, 3] }).paso, 'ios-viejo');
assert.strictEqual(sig({ plataforma: 'ios', versionIos: [16, 3], seguro: true, instalada: true, soportado: true }).paso, 'ios-viejo');

// Hash
const c = 'A'.repeat(40) + '_-b';
assert.deepStrictEqual(P.leerHash('#traspaso=' + c + '&ir=avisos'), { traspaso: c, avisos: true });
assert.deepStrictEqual(P.leerHash('#traspaso=' + c), { traspaso: c, avisos: false });
assert.deepStrictEqual(P.leerHash('#traspaso=corto&ir=avisos'), { traspaso: '', avisos: true });
assert.deepStrictEqual(P.leerHash('#/avisos'), { traspaso: '', avisos: true });
assert.deepStrictEqual(P.leerHash('#avisos'), { traspaso: '', avisos: true });
assert.deepStrictEqual(P.leerHash('#/avisosx'), { traspaso: '', avisos: false });
assert.deepStrictEqual(P.leerHash('#/inventario'), { traspaso: '', avisos: false });
assert.deepStrictEqual(P.leerHash(''), { traspaso: '', avisos: false });

console.log('pasosAvisos: todas las pruebas pasaron');
