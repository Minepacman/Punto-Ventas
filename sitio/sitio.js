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
    } else {
      a.removeAttribute('href');
      a.textContent = 'Por confirmar';
      a.classList.add('pendiente-chip');
    }
  });
  $$('[data-comando]').forEach(el => { el.textContent = D.homebrew || ''; });

  // ── 2. Sistema operativo del visitante ─────────────────────────────
  function detectarSO() {
    const ua = navigator.userAgent || '';
    const plat = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || '';
    if (/Windows/i.test(ua) || /Win/i.test(plat)) {
      const x64 = /WOW64|Win64|x64|amd64/i.test(ua) || (navigator.userAgentData && navigator.userAgentData.platform === 'Windows');
      return { clave: x64 ? 'windows-x64' : 'windows-x86', nombre: x64 ? 'Windows (64 bits)' : 'Windows (32 bits)', alt: x64 ? 'windows-x86' : 'windows-x64' };
    }
    if (/Macintosh|Mac OS X/i.test(ua) || /Mac/i.test(plat)) {
      // El navegador no distingue Apple Silicon de Intel: se ofrece el de Apple Silicon y el otro al lado.
      return { clave: 'macos-arm64', nombre: 'Mac (Apple Silicon)', alt: 'macos-x64' };
    }
    if (/Linux/i.test(ua) && !/Android/i.test(ua)) {
      return { clave: 'linux-x64.deb', nombre: 'Linux (Ubuntu / Debian)', alt: 'linux-x64.tar.gz' };
    }
    return null;   // teléfono u otro: se manda a la lista completa
  }
  const so = detectarSO();

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
  function aplicarVersion(release) {
    const version = (release.tag_name || '').replace(/^v/, '');
    const activos = {};
    (release.assets || []).forEach(a => {
      Object.keys(SUFIJOS).forEach(k => { if (SUFIJOS[k].test(a.name)) activos[k] = a; });
    });
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
    // Botón principal
    const btn = $('#btn-descarga-principal');
    if (btn && so && activos[so.clave]) {
      btn.href = activos[so.clave].browser_download_url;
      btn.setAttribute('download', '');
      $('.sub', btn).textContent = 'para ' + so.nombre + ' · v' + version + ' · ' + tamano(activos[so.clave].size);
    }
    const dt = $('#detecta-so');
    if (dt) {
      const boton = $('a.btn', dt);
      if (so && activos[so.clave]) {
        $('h3', dt).textContent = 'Detectamos que usas ' + so.nombre;
        $('p', dt).textContent = 'Este es el instalador que te toca. Si tu equipo es distinto, elige otro abajo.';
        boton.href = activos[so.clave].browser_download_url;
        boton.setAttribute('download', '');
        boton.lastElementChild.textContent = 'Descargar v' + version + ' (' + tamano(activos[so.clave].size) + ')';
      } else if (so) {
        $('h3', dt).textContent = 'Detectamos que usas ' + so.nombre;
        $('p', dt).textContent = 'Esta versión no incluye ese instalador; elige otro de la lista o entra a la página de versiones.';
        boton.href = release.html_url || RELEASES_URL;
      } else {
        $('h3', dt).textContent = '¿Estás en el teléfono?';
        $('p', dt).textContent = 'El programa se instala en la computadora de la tienda. Abre esta página desde esa PC y el botón elegirá el instalador correcto.';
        boton.href = release.html_url || RELEASES_URL;
      }
    }
  }
  function sinApi() {
    $$('[data-version]').forEach(el => { el.textContent = 'más reciente'; });
    $$('[data-fecha-version]').forEach(el => { el.textContent = 'ver página de versiones'; });
    const dt = $('#detecta-so');
    if (dt && so) {
      $('h3', dt).textContent = 'Detectamos que usas ' + so.nombre;
      $('p', dt).textContent = 'No pudimos consultar la última versión en este momento: en la página de versiones busca el archivo ' +
        'que termina en "' + so.clave + (so.clave.indexOf('.') < 0 ? (so.clave.indexOf('windows') === 0 ? '.exe' : '.dmg') : '') + '".';
    }
  }
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
  // Sin API todavía, el botón principal al menos nombra el sistema detectado
  const btn0 = $('#btn-descarga-principal');
  if (btn0 && so) $('.sub', btn0).textContent = 'para ' + so.nombre;

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
