/*
 * sitio.js — comportamiento del sitio público (sin dependencias). Lo cargan todas las páginas;
 * cada parte solo actúa si en la página existen sus elementos.
 *  1. Rellena los datos de datos.js (autor, contacto, donación).
 *  1b. Descarga los datos de donación FIRMADOS del repositorio y verifica la firma Ed25519.
 *  2. Detecta el sistema del visitante y apunta los botones de descarga al instalador correcto.
 *  3. Consulta la última versión publicada en GitHub y llena los enlaces de descarga
 *     (si falla, los enlaces siguen apuntando a la página de versiones).
 *  4. Pequeñas ayudas: copiar un comando, «Enviarme el enlace» en el teléfono, año del pie.
 */
(function () {
  'use strict';

  const D = window.DATOS_SITIO || {};
  const repo = D.repo || { usuario: 'Minepacman', nombre: 'Punto-Ventas' };
  const REPO_URL = 'https://github.com/' + repo.usuario + '/' + repo.nombre;
  const RELEASES_URL = REPO_URL + '/releases';
  const API_URL = 'https://api.github.com/repos/' + repo.usuario + '/' + repo.nombre + '/releases?per_page=15';
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
    if (pendiente(v)) {
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
    } else if (tipo === 'correo' || tipo === 'whatsapp') {
      a.removeAttribute('href');
      a.textContent = 'Por confirmar';
      a.classList.add('pendiente-chip');
    }
  });
  $$('[data-comando]').forEach(el => { if (D.homebrew) el.textContent = D.homebrew; });

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
      if (estado) estado.textContent = 'Todavía no publico mi cuenta de donación (archivo firmado del ' + d.emitido + '). Mientras tanto no dones a ninguna cuenta que diga ser de Caja Libre.';
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
  if (window.fetch && $('[data-dato^="donacion."]')) {
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
  function detectarSO() {
    const ua = navigator.userAgent || '';
    const plat = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || '';
    if (/Android|iPhone|iPad|iPod|Mobile/i.test(ua)) return null;   // teléfono o tableta
    if (/Windows/i.test(ua) || /Win/i.test(plat)) {
      const x64 = /WOW64|Win64|x64|amd64/i.test(ua) || (navigator.userAgentData && navigator.userAgentData.platform === 'Windows');
      return { clave: x64 ? 'windows-x64' : 'windows-x86', nombre: x64 ? 'Windows (64 bits)' : 'Windows (32 bits)' };
    }
    if (/Macintosh|Mac OS X/i.test(ua) || /Mac/i.test(plat)) {
      // El navegador no distingue Apple Silicon de Intel: se ofrece el de Apple Silicon y el otro está en la lista.
      return { clave: 'macos-arm64', nombre: 'Mac (Apple Silicon)' };
    }
    if (/Linux|X11/i.test(ua)) {
      return { clave: 'linux-x64.deb', nombre: 'Linux (paquete .deb)' };
    }
    return null;
  }
  const so = detectarSO();
  const enTelefono = so === null && /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent || '');

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
  function poner(sel, texto) { $$(sel).forEach(el => { el.textContent = texto; }); }

  function aplicarVersion(release) {
    const version = (release.tag_name || '').replace(/^v/, '');
    const activos = {};
    (release.assets || []).forEach(a => {
      Object.keys(SUFIJOS).forEach(k => { if (SUFIJOS[k].test(a.name)) activos[k] = a; });
    });
    poner('[data-version]', version);
    poner('[data-fecha-version]', fechaLarga(release.published_at));
    $$('[data-activo]').forEach(a => {
      const act = activos[a.dataset.activo];
      const tam = $('small', a);
      if (act) {
        a.href = act.browser_download_url;
        a.setAttribute('download', '');
        if (tam) tam.textContent = tamano(act.size);
        a.classList.remove('no-disponible');
        a.removeAttribute('aria-disabled');
      } else {
        a.href = release.html_url || RELEASES_URL;
        if (tam) tam.textContent = 'no incluido en esta versión';
        a.classList.add('no-disponible');
      }
    });
    $$('[data-notas]').forEach(a => { a.href = release.html_url || RELEASES_URL; });

    // Botón de la portada
    const btn = $('#btn-descarga-principal');
    if (btn && so && activos[so.clave]) {
      btn.href = activos[so.clave].browser_download_url;
      btn.setAttribute('download', '');
      const det = $('.detalle', btn);
      if (det) det.textContent = 'para ' + so.nombre + ' · versión ' + version + ' · ' + tamano(activos[so.clave].size);
    }
    // Bloque «tu sistema» de la página de descargas
    const auto = $('#descarga-auto');
    if (auto) {
      const boton = $('[data-auto-boton]', auto);
      if (so && activos[so.clave]) {
        poner('[data-auto-titulo]', 'Para esta computadora: ' + so.nombre);
        poner('[data-auto-texto]', 'Es el instalador que le toca a este equipo. Si no es el correcto, elige otro de la lista de abajo.');
        boton.href = activos[so.clave].browser_download_url;
        boton.setAttribute('download', '');
        boton.lastElementChild.textContent = 'Descargar versión ' + version + ' (' + tamano(activos[so.clave].size) + ')';
      } else if (so) {
        poner('[data-auto-titulo]', 'Detecté ' + so.nombre);
        poner('[data-auto-texto]', 'Esta versión no incluye ese instalador; elige otro de la lista o entra a la página de versiones.');
        boton.href = release.html_url || RELEASES_URL;
      }
    }
  }
  function sinApi() {
    poner('[data-version]', 'más reciente');
    poner('[data-fecha-version]', 'ver página de versiones');
    const auto = $('#descarga-auto');
    if (auto && so) {
      poner('[data-auto-titulo]', 'Para esta computadora: ' + so.nombre);
      poner('[data-auto-texto]', 'No pude consultar la última versión en este momento. En la página de versiones busca el archivo que termina en «' + so.clave + (so.clave.indexOf('.') < 0 ? (so.clave.indexOf('windows') === 0 ? '.exe' : '.dmg') : '') + '».');
    }
  }
  // Teléfono: se esconde el bloque de detección y se muestra «Enviarme el enlace»
  if (enTelefono) {
    const auto = $('#descarga-auto'); if (auto) auto.hidden = true;
    const tel = $('#solo-telefono'); if (tel) tel.hidden = false;
    const btn = $('#btn-descarga-principal');
    if (btn) { const det = $('.detalle', btn); if (det) det.textContent = 'se instala en la computadora de la tienda'; }
  } else if (so) {
    poner('[data-auto-titulo]', 'Para esta computadora: ' + so.nombre);
    const btn = $('#btn-descarga-principal');
    if (btn) { const det = $('.detalle', btn); if (det) det.textContent = 'para ' + so.nombre; }
  }
  if (window.fetch && ($('[data-activo]') || $('#btn-descarga-principal'))) {
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
  } else if ($('[data-activo]')) {
    sinApi();
  }

  // ── 4. Copiar comando, «Enviarme el enlace», año del pie ──────────
  function copiar(texto) {
    if (navigator.clipboard && navigator.clipboard.writeText) return navigator.clipboard.writeText(texto);
    return new Promise((ok, no) => {
      const ta = document.createElement('textarea');
      ta.value = texto; ta.setAttribute('readonly', ''); ta.style.position = 'fixed'; ta.style.opacity = '0';
      document.body.appendChild(ta); ta.select();
      try { document.execCommand('copy') ? ok() : no(); } catch (e) { no(e); }
      document.body.removeChild(ta);
    });
  }
  $$('[data-copiar]').forEach(b => {
    b.addEventListener('click', () => {
      const el = $(b.dataset.copiar);
      const texto = el ? el.textContent : '';
      const listo = () => { const t = b.textContent; b.textContent = 'Copiado'; setTimeout(() => { b.textContent = t; }, 1600); };
      copiar(texto).then(listo, listo);
    });
  });

  const enlacePagina = 'https://minepacman.github.io/' + repo.nombre + '/descargar.html';
  const estadoCompartir = $('[data-compartir-estado]');
  const avisar = t => { if (estadoCompartir) estadoCompartir.textContent = t; };
  $$('[data-compartir="whatsapp"]').forEach(a => { a.href = 'https://wa.me/?text=' + encodeURIComponent('Caja Libre, punto de venta gratis para la tienda. Descargar: ' + enlacePagina); });
  $$('[data-compartir="copiar"]').forEach(b => {
    b.addEventListener('click', () => copiar(enlacePagina).then(() => avisar('Enlace copiado. Pégalo en un mensaje para ti o escríbelo en la computadora: ' + enlacePagina), () => avisar('No se pudo copiar. El enlace es: ' + enlacePagina)));
  });
  $$('[data-compartir="compartir"]').forEach(b => {
    if (!(navigator.share)) { b.hidden = true; return; }
    b.addEventListener('click', () => {
      navigator.share({ title: 'Descargar Caja Libre', text: 'Caja Libre, punto de venta gratis para la tienda.', url: enlacePagina })
        .then(() => avisar('Listo. Abre el enlace desde la computadora de la tienda.'))
        .catch(() => { /* el visitante canceló */ });
    });
  });

  $$('[data-anio]').forEach(el => { el.textContent = new Date().getFullYear(); });
})();
