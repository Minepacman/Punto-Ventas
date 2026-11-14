/*
 * sitio.js — comportamiento del sitio público (sin dependencias).
 *  1. Rellena los datos de datos.js (autor, contacto, donación).
 *  2. Detecta el sistema del visitante y apunta "Descargar gratis" al instalador correcto.
 *  3. Consulta la última versión publicada en GitHub y llena las tarjetas de descarga
 *     (si falla, los enlaces siguen apuntando a la página de versiones).
 *  4. Detalles visuales: estrellas del hero, aparición al hacer scroll, ticket animado,
 *     números que cuentan. Todo respeta prefers-reduced-motion.
 */
(function () {
  'use strict';

  const D = window.DATOS_SITIO || {};
  const repo = D.repo || { usuario: 'Minepacman', nombre: 'Punto-Ventas' };
  const REPO_URL = 'https://github.com/' + repo.usuario + '/' + repo.nombre;
  const RELEASES_URL = REPO_URL + '/releases';
  const API_URL = 'https://api.github.com/repos/' + repo.usuario + '/' + repo.nombre + '/releases?per_page=15';
  const reducirMovimiento = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));

  // ── 1. Datos configurables ─────────────────────────────────────────
  function valor(ruta) {
    return ruta.split('.').reduce((o, k) => (o == null ? undefined : o[k]), D);
  }
  function pendiente(v) {
    return v == null || String(v).trim() === '' || /^PENDIENTE$/i.test(String(v).trim());
  }
  $$('[data-dato]').forEach(el => {
    const v = valor(el.dataset.dato);
    if (pendiente(v) && el.dataset.pendiente === undefined && !el.closest('.cuenta')) {
      el.hidden = true;   // dato opcional sin llenar: se oculta, no se muestra «por confirmar»
    } else if (pendiente(v)) {
      el.textContent = el.dataset.pendiente || 'Por confirmar';
      el.classList.add(el.closest('.cuenta') ? 'pendiente' : 'pendiente-chip');
    } else {
      el.textContent = v;
    }
  });
  $$('[data-enlace]').forEach(a => {
    const tipo = a.dataset.enlace;
    const c = D.contacto || {};
    if (tipo === 'correo' && !pendiente(c.correo)) {
      a.href = 'mailto:' + c.correo; a.textContent = c.correo;
    } else if (tipo === 'whatsapp' && !pendiente(c.whatsapp)) {
      const digitos = String(c.whatsapp).replace(/\D/g, '');
      a.href = 'https://wa.me/' + (digitos.length === 10 ? '52' + digitos : digitos);
      a.textContent = pendiente(c.telefonoTexto) ? c.whatsapp : c.telefonoTexto;
    } else if (tipo === 'repo') {
      a.href = REPO_URL;
    } else if (tipo === 'issues') {
      a.href = REPO_URL + '/issues';
    } else if (tipo === 'releases') {
      a.href = RELEASES_URL;
    } else if (tipo === 'licencia') {
      a.href = REPO_URL + '/blob/main/LICENSE';
    } else {
      a.removeAttribute('href');
      a.textContent = 'Por confirmar';
      a.classList.add('pendiente-chip');
    }
  });
  $$('[data-comando]').forEach(el => { el.textContent = D.homebrew || ''; });

  // ── 1b. Datos de donación FIRMADOS por el autor (claves/donacion.pdvd del repositorio) ──
  // Mismo archivo que coteja el programa. Formato: PDVD1.<base64url(json)>.<base64url(firma Ed25519)>.
  // Si el navegador tiene WebCrypto con Ed25519 (Chrome 137+, Firefox, Safari 17+) se verifica la firma y se
  // dice «verificados»; si no, solo se muestran los datos del archivo. Si no se puede leer, quedan los de datos.js.
  const DONACION_URL = 'https://raw.githubusercontent.com/' + repo.usuario + '/' + repo.nombre + '/' + (repo.rama || 'main') + '/claves/donacion.pdvd';
  function b64urlABytes(t) {
    const b64 = t.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((t.length + 3) % 4);
    const bin = atob(b64); const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }
  function pintarDonacion(d, verificada) {
    const estado = $('#donacion-estado');
    const poner = (clave, v) => $$('[data-dato="donacion.' + clave + '"]').forEach(el => {
      if (pendiente(v)) { el.textContent = el.dataset.pendiente || 'Por confirmar'; el.classList.add('pendiente'); }
      else { el.textContent = v; el.classList.remove('pendiente'); }
    });
    if (d.pendiente) {
      poner('clabe', ''); poner('banco', ''); poner('beneficiario', '');
      if (estado) estado.textContent = 'El autor todavía no publica su cuenta de donación (archivo firmado del ' + d.emitido + '). Mientras tanto no dones a ninguna cuenta que diga ser de Caja Libre.';
      return;
    }
    const clabe = String(d.clabe || '').replace(/\D/g, '');
    poner('clabe', clabe.length === 18 ? clabe.slice(0, 3) + ' ' + clabe.slice(3, 6) + ' ' + clabe.slice(6, 17) + ' ' + clabe.slice(17) : clabe);
    poner('banco', d.banco); poner('beneficiario', d.beneficiario);
    if (estado) estado.textContent = verificada
      ? 'Datos verificados: firmados por el autor el ' + d.emitido + ' (firma Ed25519 comprobada en tu navegador).'
      : 'Datos publicados por el autor el ' + d.emitido + ' (archivo firmado claves/donacion.pdvd del repositorio).';
  }
  async function verificarFirma(carga, firma) {
    try {
      if (!(window.crypto && crypto.subtle) || pendiente(D.llavePublica)) return false;
      const spki = b64urlABytes(String(D.llavePublica).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''));
      const llave = await crypto.subtle.importKey('spki', spki, { name: 'Ed25519' }, false, ['verify']);
      return await crypto.subtle.verify({ name: 'Ed25519' }, llave, firma, carga);
    } catch (e) { return false; }   // navegador sin Ed25519: se muestran los datos sin la marca «verificados»
  }
  if (window.fetch) {
    fetch(DONACION_URL, { cache: 'no-cache' })
      .then(r => (r.ok ? r.text() : Promise.reject(new Error('HTTP ' + r.status))))
      .then(async texto => {
        const partes = texto.trim().split('.');
        if (partes.length !== 3 || partes[0] !== 'PDVD1') throw new Error('formato');
        const carga = b64urlABytes(partes[1]), firma = b64urlABytes(partes[2]);
        const d = JSON.parse(new TextDecoder().decode(carga));
        if (d.v !== 1) throw new Error('versión');
        pintarDonacion(d, await verificarFirma(carga, firma));
      })
      .catch(() => { /* sin red o archivo: quedan los datos de datos.js y el texto base */ });
  }

  // ── 2. Sistema operativo del visitante ─────────────────────────────
  // Devuelve { clave, nombre, alt, altNombre, incierto, movil, dispositivo, nota }.
  //  - clave: instalador recomendado ('windows-x64', 'macos-arm64', ...); null si es una Mac cuyo chip no se pudo saber.
  //  - movil: teléfono/tableta/Chromebook. Regla del dueño: se muestra la descarga de Windows y una nota.
  const WIN64 = { clave: 'windows-x64', nombre: 'Windows (64 bits)', alt: 'windows-x86', altNombre: 'Windows (32 bits)' };
  function esMac(ua, plat) { return /Macintosh|Mac OS X|MacIntel|MacPPC/i.test(ua) || /^Mac/i.test(plat); }
  function detectarSO() {
    const ua = navigator.userAgent || '';
    const uad = navigator.userAgentData;
    const plat = (uad && uad.platform) || navigator.platform || '';
    const toques = navigator.maxTouchPoints || 0;
    // Teléfonos y tabletas. iPadOS se presenta como Mac: una Mac de verdad no tiene pantalla táctil.
    if (/iPhone|iPod/i.test(ua)) return Object.assign({ movil: true, dispositivo: 'un iPhone' }, WIN64);
    if (/Android/i.test(ua)) return Object.assign({ movil: true, dispositivo: /Mobile/i.test(ua) ? 'un teléfono Android' : 'una tableta Android' }, WIN64);
    if (/iPad/i.test(ua) || (esMac(ua, plat) && toques > 1)) return Object.assign({ movil: true, dispositivo: 'una tableta (iPad)' }, WIN64);
    if (/CrOS/i.test(ua)) return Object.assign({ movil: true, dispositivo: 'una Chromebook' }, WIN64);
    if (/Windows|Win32|Win64/i.test(ua) || /^Win/i.test(plat)) {
      // «Win32» a secas (sin Win64, WOW64, x64 ni ARM) es un Windows de 32 bits; con WOW64 el sistema es de 64 bits.
      const x86 = /Win32/i.test(ua) && !/Win64|WOW64|x64|amd64|ARM/i.test(ua) && !/Windows NT 1[01]\.0; Win64/i.test(ua);
      return x86 ? { clave: 'windows-x86', nombre: 'Windows (32 bits)', alt: 'windows-x64', altNombre: 'Windows (64 bits)' } : Object.assign({}, WIN64);
    }
    if (esMac(ua, plat)) {
      // Safari y Firefox dicen «Intel» aunque sea Apple Silicon: la UA no sirve. Se prueba la GPU (WebGL).
      const arq = arquitecturaMacPorGpu();
      if (arq === 'arm') return { clave: 'macos-arm64', nombre: 'Mac (Apple Silicon)', alt: 'macos-x64', altNombre: 'Mac Intel' };
      if (arq === 'x64') return { clave: 'macos-x64', nombre: 'Mac (Intel)', alt: 'macos-arm64', altNombre: 'Mac Apple Silicon' };
      return { clave: null, nombre: 'Mac', incierto: true, alt: 'macos-arm64', altNombre: 'Mac Apple Silicon (M1, M2, M3, M4…)', alt2: 'macos-x64', alt2Nombre: 'Mac Intel' };
    }
    if (/Linux|X11|Ubuntu|Fedora|Debian/i.test(ua) || /Linux/i.test(plat)) {
      const rpm = /Fedora|SUSE|Red Hat|CentOS/i.test(ua);
      const nota = /aarch64|armv|arm64/i.test(ua) ? 'Los instaladores de Linux son para procesadores de 64 bits Intel/AMD; en ARM (p. ej. Raspberry Pi) no hay versión todavía.' : '';
      return rpm
        ? { clave: 'linux-x64.rpm', nombre: 'Linux (Fedora / openSUSE)', alt: 'linux-x64.deb', altNombre: 'Ubuntu / Debian (.deb)', nota }
        : { clave: 'linux-x64.deb', nombre: 'Linux (Ubuntu / Debian)', alt: 'linux-x64.rpm', altNombre: 'Fedora / openSUSE (.rpm)', nota };
    }
    return Object.assign({ movil: true, dispositivo: 'un dispositivo que no reconocemos' }, WIN64);
  }
  function arquitecturaMacPorGpu() {
    try {
      const c = document.createElement('canvas');
      const gl = c.getContext('webgl') || c.getContext('experimental-webgl');
      if (!gl) return null;
      const ext = gl.getExtension('WEBGL_debug_renderer_info');
      const r = String(ext ? gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER));
      if (/Apple M\d/i.test(r)) return 'arm';
      if (/Intel|AMD|Radeon|NVIDIA|GeForce/i.test(r)) return 'x64';
    } catch (e) { /* sin WebGL: no se sabe */ }
    return null;   // «Apple GPU» (Safari) no distingue chip
  }
  let so = detectarSO();
  // Chrome/Edge/Opera dicen la arquitectura real; si responde, manda sobre la prueba de GPU.
  if (so.incierto || (so.clave && so.clave.indexOf('macos') === 0)) {
    const uad = navigator.userAgentData;
    if (uad && uad.getHighEntropyValues) {
      uad.getHighEntropyValues(['architecture']).then(v => {
        const a = String(v.architecture || '').toLowerCase();
        if (a === 'arm') so = { clave: 'macos-arm64', nombre: 'Mac (Apple Silicon)', alt: 'macos-x64', altNombre: 'Mac Intel' };
        else if (a === 'x86') so = { clave: 'macos-x64', nombre: 'Mac (Intel)', alt: 'macos-arm64', altNombre: 'Mac Apple Silicon' };
        else return;
        pintarDeteccion();
      }).catch(() => {});
    }
  }

  // ── 3. Última versión publicada ────────────────────────────────────
  const SUFIJOS = {
    'windows-x64': /-windows-x64\.exe$/i,
    'windows-x86': /-windows-x86\.exe$/i,
    'macos-arm64': /-macos-arm64\.dmg$/i,
    'macos-x64': /-macos-x64\.dmg$/i,
    'linux-x64.deb': /-linux-x64\.deb$/i,
    'linux-x64.rpm': /-linux-x64\.rpm$/i,
    'linux-x64.tar.gz': /-linux-x64\.tar\.gz$/i,
    'sha': /^SHA256SUMS\.txt$/i
  };
  function tamano(bytes) {
    if (!bytes) return '';
    return bytes >= 1048576 ? (bytes / 1048576).toFixed(0) + ' MB' : (bytes / 1024).toFixed(0) + ' KB';
  }
  function fechaLarga(iso) {
    try { return new Date(iso).toLocaleDateString('es-MX', { day: 'numeric', month: 'long', year: 'numeric' }); }
    catch (e) { return ''; }
  }
  // Estado de la última versión (null mientras no llegue o si la API falla)
  let ultima = null;   // { version, activos, url }
  const EXTENSION = { 'windows-x64': '.exe', 'windows-x86': '.exe', 'macos-arm64': '.dmg', 'macos-x64': '.dmg', 'linux-x64.deb': '.deb', 'linux-x64.rpm': '.rpm', 'linux-x64.tar.gz': '.tar.gz' };

  function configurarBoton(a, clave, textoBase) {
    if (!a) return;
    const act = ultima && ultima.activos[clave];
    const t = a.lastElementChild;
    if (act) {
      a.href = act.browser_download_url; a.setAttribute('download', '');
      if (t) t.textContent = textoBase + ' · v' + ultima.version + ' (' + tamano(act.size) + ')';
    } else {
      a.href = ultima ? ultima.url : RELEASES_URL; a.removeAttribute('download');
      if (t) t.textContent = ultima ? 'Ver en la página de versiones' : textoBase;
    }
  }
  function pintarDeteccion() {
    const dt = $('#detecta-so');
    const btn = $('#btn-descarga-principal');
    const hay = clave => !ultima || !!ultima.activos[clave];   // sin datos de la API se supone que existe
    const version = ultima ? ' · v' + ultima.version : '';
    // Tarjeta recomendada
    $$('.tarjeta-descarga').forEach(t => t.classList.remove('recomendada'));
    if (so.clave) { const a = $('[data-activo="' + so.clave + '"]'); if (a) a.closest('.tarjeta-descarga').classList.add('recomendada'); }
    else if (so.incierto) { const a = $('[data-activo="macos-arm64"]'); if (a) a.closest('.tarjeta-descarga').classList.add('recomendada'); }

    // Botón principal del hero
    if (btn) {
      const sub = $('.sub', btn);
      if (so.incierto) {
        btn.href = '#descargas';
        sub.textContent = 'para Mac · elige Apple Silicon o Intel';
      } else {
        const act = ultima && ultima.activos[so.clave];
        btn.href = act ? act.browser_download_url : (ultima ? '#descargas' : '#descargas');
        if (act) btn.setAttribute('download', ''); else btn.removeAttribute('download');
        sub.textContent = (so.movil ? 'para Windows (en computadora)' : 'para ' + so.nombre) + version + (act ? ' · ' + tamano(act.size) : '');
      }
    }
    if (!dt) return;
    const h3 = $('h3', dt), p = $('p', dt), nota = $('#detecta-nota'), alt = $('#detecta-alt'), wa = $('#detecta-wa');
    const boton = $('a.btn', dt);
    const textoOtras = 'Si tu equipo es distinto, elige otro en la lista de abajo.';
    const waCaja = $('#detecta-wa-caja');
    nota.hidden = true; alt.hidden = true; waCaja.hidden = true;
    if (so.movil) {
      h3.textContent = 'Estás en ' + so.dispositivo + ': el programa es para computadora';
      p.textContent = 'Caja Libre se instala en la computadora de la tienda, no en el celular. Te dejamos la descarga de Windows (la más común); ' + textoOtras.toLowerCase().replace('si tu equipo es distinto', 'si tu PC es Mac o Linux');
      configurarBoton(boton, 'windows-x64', 'Descargar para Windows');
      waCaja.hidden = false;
      const enlace = 'https://minepacman.github.io/Punto-Ventas/';
      wa.href = 'https://wa.me/?text=' + encodeURIComponent('Caja Libre, punto de venta gratis para tu tienda: ' + enlace);
    } else if (so.incierto) {
      h3.textContent = 'Detectamos que usas una Mac';
      p.textContent = 'Tu navegador no nos dice si es Apple Silicon (M1, M2, M3, M4…) o Intel. Para saberlo: menú Apple › Acerca de esta Mac; si dice «Chip Apple…» baja la primera, si dice «Procesador Intel» la segunda.';
      configurarBoton(boton, 'macos-arm64', 'Mac Apple Silicon');
      alt.hidden = false; configurarBoton(alt, 'macos-x64', 'Mac Intel');
    } else {
      h3.textContent = 'Detectamos que usas ' + so.nombre;
      if (hay(so.clave)) {
        p.textContent = 'Este es el instalador que te toca. ' + textoOtras;
        configurarBoton(boton, so.clave, 'Descargar');
      } else {
        p.textContent = 'Esta versión no incluye el instalador para ' + so.nombre + '; elige otro de la lista o entra a la página de versiones.';
        configurarBoton(boton, so.clave, 'Descargar');
      }
      if (so.nota) { nota.textContent = so.nota; nota.hidden = false; }
    }
  }
  function aplicarVersion(release) {
    const version = (release.tag_name || '').replace(/^v/, '');
    const activos = {};
    (release.assets || []).forEach(a => {
      Object.keys(SUFIJOS).forEach(k => { if (SUFIJOS[k].test(a.name)) activos[k] = a; });
    });
    ultima = { version, activos, url: release.html_url || RELEASES_URL };
    $$('[data-version]').forEach(el => { el.textContent = version; });
    $$('[data-fecha-version]').forEach(el => { el.textContent = fechaLarga(release.published_at); });
    $$('[data-activo]').forEach(a => {
      const act = activos[a.dataset.activo];
      const tam = $('small', a);
      if (act) {
        a.href = act.browser_download_url;
        a.removeAttribute('target');
        a.setAttribute('download', '');
        if (tam) tam.textContent = tamano(act.size);
        a.classList.remove('no-disponible');
      } else {
        a.href = release.html_url || RELEASES_URL;
        if (tam) tam.textContent = 'no incluido en esta versión';
        a.classList.add('no-disponible');
        a.setAttribute('aria-disabled', 'true');
      }
    });
    $$('[data-notas]').forEach(a => { a.href = release.html_url || RELEASES_URL; });
    pintarDeteccion();
  }
  function sinApi() {
    $$('[data-version]').forEach(el => { el.textContent = 'más reciente'; });
    $$('[data-fecha-version]').forEach(el => { el.textContent = 'ver página de versiones'; });
    pintarDeteccion();
    const dt = $('#detecta-so');
    if (dt && so.clave) {
      $('p', dt).textContent = 'No pudimos consultar la última versión en este momento: en la página de versiones busca el archivo que termina en «' +
        so.clave.replace('linux-x64.', 'linux-x64.').replace(/^(windows|macos)-/, '-$1-') + (EXTENSION[so.clave] && so.clave.indexOf('linux') !== 0 ? EXTENSION[so.clave] : '') + '».';
    }
  }
  pintarDeteccion();
  if (window.fetch) {
    const ctl = typeof AbortController === 'function' ? new AbortController() : null;
    if (ctl) setTimeout(() => ctl.abort(), 8000);
    fetch(API_URL, { headers: { Accept: 'application/vnd.github+json' }, signal: ctl && ctl.signal })
      .then(r => (r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status))))
      .then(lista => {
        const estable = (Array.isArray(lista) ? lista : []).find(r => !r.draft && !r.prerelease);
        if (!estable) throw new Error('sin versiones');
        aplicarVersion(estable);
      })
      .catch(sinApi);
  } else {
    sinApi();
  }

  // ── 4. Menú móvil, copiar comando ──────────────────────────────────
  const menuBtn = $('.menu-boton');
  const navMovil = $('.nav-movil');
  if (menuBtn && navMovil) {
    menuBtn.addEventListener('click', () => {
      const abierto = navMovil.classList.toggle('abierto');
      menuBtn.setAttribute('aria-expanded', abierto ? 'true' : 'false');
    });
    $$('a', navMovil).forEach(a => a.addEventListener('click', () => { navMovil.classList.remove('abierto'); menuBtn.setAttribute('aria-expanded', 'false'); }));
  }
  $$('[data-copiar]').forEach(b => {
    b.addEventListener('click', () => {
      const texto = $(b.dataset.copiar) ? $(b.dataset.copiar).textContent : '';
      const listo = () => { const t = b.textContent; b.textContent = 'Copiado'; setTimeout(() => { b.textContent = t; }, 1600); };
      if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(texto).then(listo, listo);
      else listo();
    });
  });

  // ── 4b. Pestañas de la vista rápida ────────────────────────────────
  const vista = $('#vista-rapida');
  if (vista) {
    const tabs = $$('[role="tab"]', vista), paneles = $$('[role="tabpanel"]', vista);
    function activar(tab) {
      tabs.forEach(t => { const on = t === tab; t.setAttribute('aria-selected', on ? 'true' : 'false'); t.tabIndex = on ? 0 : -1; });
      paneles.forEach(p => { const on = p.id === tab.getAttribute('aria-controls'); p.classList.toggle('activo', on); p.hidden = !on; });
    }
    tabs.forEach((t, i) => {
      t.addEventListener('click', () => activar(t));
      t.addEventListener('keydown', e => {
        if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
          e.preventDefault();
          const n = tabs[(i + (e.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length];
          n.focus(); activar(n);
        }
      });
    });
    activar(tabs[0]);
  }

  // ── 5. Aparición al hacer scroll ───────────────────────────────────
  const reveal = $$('.reveal');
  if ('IntersectionObserver' in window && !reducirMovimiento) {
    const io = new IntersectionObserver(entries => {
      entries.forEach(e => { if (e.isIntersecting) { e.target.classList.add('visible'); io.unobserve(e.target); } });
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.12 });
    reveal.forEach(el => io.observe(el));
  } else {
    reveal.forEach(el => el.classList.add('visible'));
  }

  // ── 6. Estrellas del hero (canvas ligero) ──────────────────────────
  const lienzo = $('#estrellas');
  if (lienzo && lienzo.getContext) {
    const ctx = lienzo.getContext('2d');
    let estrellas = [], w = 0, h = 0, dpr = Math.min(window.devicePixelRatio || 1, 2);
    function medir() {
      const r = lienzo.parentElement.getBoundingClientRect();
      w = Math.max(1, r.width); h = Math.max(1, r.height);
      lienzo.width = w * dpr; lienzo.height = h * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      const n = Math.min(160, Math.floor(w * h / 9000));
      estrellas = Array.from({ length: n }, () => ({
        x: Math.random() * w, y: Math.random() * h, r: Math.random() * 1.3 + 0.3,
        a: Math.random() * Math.PI * 2, v: 0.004 + Math.random() * 0.01, b: 0.25 + Math.random() * 0.6
      }));
    }
    function pintar(estatico) {
      ctx.clearRect(0, 0, w, h);
      estrellas.forEach(s => {
        const alfa = estatico ? s.b : s.b * (0.55 + 0.45 * Math.sin(s.a));
        s.a += s.v;
        ctx.beginPath(); ctx.arc(s.x, s.y, s.r, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(214, 228, 248,' + alfa.toFixed(3) + ')'; ctx.fill();
      });
    }
    let visible = true, animando = false;
    function ciclo() { if (!visible) { animando = false; return; } pintar(false); requestAnimationFrame(ciclo); }
    medir(); pintar(reducirMovimiento);
    if (!reducirMovimiento) {
      animando = true; ciclo();
      if ('IntersectionObserver' in window) {
        new IntersectionObserver(en => { visible = en[0].isIntersecting; if (visible && !animando) { animando = true; ciclo(); } }).observe(lienzo);
      }
    }
    let t; window.addEventListener('resize', () => { clearTimeout(t); t = setTimeout(() => { medir(); pintar(reducirMovimiento); }, 150); });
  }

  // ── 7. Ticket animado: escanear → renglones → total → cobrar ───────
  const demo = $('#demo-ticket');
  if (demo) {
    const cuerpo = $('tbody', demo), campo = $('.campo', demo), lector = $('.lector', demo),
          total = $('.total .m', demo), pie = $('.pie', demo), boton = $('.cobrar', demo), cambio = $('.cambio', demo);
    const productos = [
      ['7501055300778', 'Coca-Cola 600 ml', 1, 20], ['7501000111589', 'Sabritas Sal 45 g', 2, 20],
      ['2004', 'Aguacate', '0.750 kg', 48.75], ['7501020511116', 'Leche Lala 1 L', 1, 29],
      ['7501073200145', 'Huevo 12 pz', 1, 48]
    ];
    const dinero = n => '$' + n.toFixed(2);
    const esperar = ms => new Promise(r => setTimeout(r, ms));
    function pintarEstatico() {
      cuerpo.innerHTML = '';
      let suma = 0;
      productos.forEach(p => { suma += p[3]; const tr = document.createElement('tr'); tr.innerHTML = '<td>' + p[1] + '</td><td>' + p[2] + '</td><td>' + dinero(p[3]) + '</td>'; cuerpo.appendChild(tr); });
      total.textContent = dinero(suma);
      boton.textContent = 'Cobrado ✓'; boton.classList.add('ok'); cambio.textContent = 'Cambio: $34.25';
    }
    async function ciclo() {
      while (true) {
        cuerpo.innerHTML = ''; total.textContent = '$0.00'; campo.textContent = ''; campo.classList.add('activo');
        boton.textContent = 'Cobrar  F12'; boton.classList.remove('ok'); cambio.textContent = ''; $('.ayuda', pie).textContent = 'Escanea los productos…';
        let suma = 0;
        for (const p of productos) {
          await esperar(650);
          lector.classList.add('escaneando');
          for (let i = 1; i <= p[0].length; i++) { campo.textContent = p[0].slice(0, i); await esperar(p[0].length > 6 ? 22 : 70); }
          await esperar(140);
          lector.classList.remove('escaneando');
          campo.textContent = '';
          const tr = document.createElement('tr'); tr.className = 'nueva';
          tr.innerHTML = '<td>' + p[1] + '</td><td>' + p[2] + '</td><td>' + dinero(p[3]) + '</td>';
          cuerpo.appendChild(tr);
          suma += p[3]; total.textContent = dinero(suma);
        }
        await esperar(900);
        $('.ayuda', pie).textContent = 'F12 · Recibe $200.00';
        campo.classList.remove('activo');
        await esperar(1100);
        boton.textContent = 'Cobrado ✓'; boton.classList.add('ok');
        cambio.textContent = 'Cambio: ' + dinero(200 - suma);
        $('.ayuda', pie).textContent = 'Ticket impreso · cajón abierto';
        await esperar(3800);
      }
    }
    if (reducirMovimiento) pintarEstatico();
    else if ('IntersectionObserver' in window) {
      let iniciado = false;
      new IntersectionObserver((en, obs) => { if (en[0].isIntersecting && !iniciado) { iniciado = true; ciclo(); obs.disconnect(); } }, { threshold: 0.3 }).observe(demo);
    } else ciclo();
  }

  // ── 8. Números que cuentan ─────────────────────────────────────────
  const contadores = $$('[data-contar]');
  function contar(el) {
    const fin = parseFloat(el.dataset.contar), dec = parseInt(el.dataset.decimales || '0', 10),
          pre = el.dataset.prefijo || '', suf = el.dataset.sufijo || '', dur = 1400, t0 = performance.now();
    function paso(t) {
      const k = Math.min(1, (t - t0) / dur), e = 1 - Math.pow(1 - k, 3);
      el.textContent = pre + (fin * e).toLocaleString('es-MX', { minimumFractionDigits: dec, maximumFractionDigits: dec }) + suf;
      if (k < 1) requestAnimationFrame(paso);
    }
    requestAnimationFrame(paso);
  }
  if (contadores.length) {
    if (reducirMovimiento || !('IntersectionObserver' in window)) {
      contadores.forEach(el => { el.textContent = (el.dataset.prefijo || '') + parseFloat(el.dataset.contar).toLocaleString('es-MX', { minimumFractionDigits: parseInt(el.dataset.decimales || '0', 10), maximumFractionDigits: parseInt(el.dataset.decimales || '0', 10) }) + (el.dataset.sufijo || ''); });
    } else {
      const io = new IntersectionObserver(en => { en.forEach(e => { if (e.isIntersecting) { contar(e.target); io.unobserve(e.target); } }); }, { threshold: 0.5 });
      contadores.forEach(el => io.observe(el));
    }
  }

  // Año del pie
  $$('[data-anio]').forEach(el => { el.textContent = new Date().getFullYear(); });
})();
