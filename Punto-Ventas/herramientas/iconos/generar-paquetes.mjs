#!/usr/bin/env node
// Genera los paquetes de iconos de Caja Libre (src/main/resources/iconos/<id>.json) a partir de los
// paquetes npm originales, en versiones fijas. Ver LEEME.md.
//
// Uso:  node herramientas/iconos/generar-paquetes.mjs [--cache <carpeta>] [--solo-revisar]
//
// No necesita dependencias en el repositorio: baja con `npm pack` cada paquete (y la biblioteca svgpath,
// que normaliza los trazos) a una carpeta temporal fuera del repo y los descomprime con `tar`.

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const AQUI = path.dirname(fileURLToPath(import.meta.url));
const PROYECTO = path.resolve(AQUI, '..', '..');
const ICONOS_JAVA = path.join(PROYECTO, 'src/main/java/me/eliassanchezfernandez/puntodeventa/util/Iconos.java');
const INDEX_MOVIL = path.join(PROYECTO, 'src/main/resources/movil/index.html');
const SALIDA = path.join(PROYECTO, 'src/main/resources/iconos');
const MAPA = path.join(AQUI, 'mapa.json');

const SVGPATH = { npm: 'svgpath', version: '2.6.0' };

// Cómo está organizado cada paquete. `archivo(nombre)` da la ruta dentro de package/.
// `caja`: viewBox original [x, y, ancho, alto]; todo se lleva a 0 0 24 24.
const PAQUETES = [
  {
    id: 'tabler', nombre: 'Tabler Icons', npm: '@tabler/icons', version: '3.52.0', licencia: 'MIT',
    autor: 'Paweł Kuna', url: 'https://tabler.io/icons', caja: [0, 0, 24, 24],
    estilos: {
      contorno: { tipo: 'trazo', grosor: 2, archivo: n => `icons/outline/${n}.svg` },
      relleno: { tipo: 'relleno', grosor: 2, archivo: n => `icons/filled/${n}.svg`, respaldo: 'contorno' },
    },
  },
  {
    id: 'phosphor', nombre: 'Phosphor Icons', npm: '@phosphor-icons/core', version: '2.1.1', licencia: 'MIT',
    autor: 'Phosphor Icons (Tobias Fried y Helena Zhang)', url: 'https://phosphoricons.com', caja: [0, 0, 256, 256],
    estilos: {
      fino: { tipo: 'relleno', archivo: n => `assets/light/${n}-light.svg` },
      contorno: { tipo: 'relleno', archivo: n => `assets/regular/${n}.svg` },
      grueso: { tipo: 'relleno', archivo: n => `assets/bold/${n}-bold.svg` },
      relleno: { tipo: 'relleno', archivo: n => `assets/fill/${n}-fill.svg` },
      duotono: { tipo: 'relleno', archivo: n => `assets/duotone/${n}-duotone.svg` },
    },
  },
  {
    id: 'heroicons', nombre: 'Heroicons', npm: 'heroicons', version: '2.2.0', licencia: 'MIT',
    autor: 'Tailwind Labs, Inc.', url: 'https://heroicons.com', caja: [0, 0, 24, 24],
    estilos: {
      contorno: { tipo: 'trazo', grosor: 1.5, archivo: n => `24/outline/${n}.svg` },
      relleno: { tipo: 'relleno', grosor: 1.5, archivo: n => `24/solid/${n}.svg`, respaldo: 'contorno' },
    },
  },
  {
    id: 'material', nombre: 'Material Symbols Rounded', npm: '@material-symbols/svg-400', version: '0.49.0',
    licencia: 'Apache-2.0', autor: 'Google LLC', url: 'https://fonts.google.com/icons', caja: [0, -960, 960, 960],
    estilos: {
      contorno: { tipo: 'relleno', archivo: n => `rounded/${n}.svg` },
      relleno: { tipo: 'relleno', archivo: n => `rounded/${n}-fill.svg`, respaldo: 'contorno' },
    },
  },
  {
    id: 'iconoir', nombre: 'Iconoir', npm: 'iconoir', version: '7.12.2', licencia: 'MIT',
    autor: 'Luca Burgio', url: 'https://iconoir.com', caja: [0, 0, 24, 24],
    estilos: {
      contorno: { tipo: 'trazo', grosor: 1.5, archivo: n => `icons/regular/${n}.svg` },
      relleno: { tipo: 'relleno', grosor: 1.5, archivo: n => `icons/solid/${n}.svg`, respaldo: 'contorno' },
    },
  },
];

// ---------------------------------------------------------------------------------------------
// Argumentos

const args = process.argv.slice(2);
const opcion = n => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : null; };
const CACHE = path.resolve(opcion('--cache') || path.join(os.tmpdir(), 'caja-libre-iconos'));
const SOLO_REVISAR = args.includes('--solo-revisar');
if (CACHE.startsWith(path.resolve(PROYECTO, '..') + path.sep)) {
  throw new Error('La carpeta de descargas no debe estar dentro del repositorio: ' + CACHE);
}

// ---------------------------------------------------------------------------------------------
// Nombres lógicos

function nombresLogicos() {
  const java = fs.readFileSync(ICONOS_JAVA, 'utf8');
  const deJava = [...java.matchAll(/\bicono\("(\w+)"/g)].map(m => m[1]);
  const html = fs.readFileSync(INDEX_MOVIL, 'utf8');
  const bloque = html.match(/const ICONOS = \{([\s\S]*?)\n\};/);
  if (!bloque) throw new Error('No encontré «const ICONOS = {...}» en ' + INDEX_MOVIL);
  const deMovil = [...bloque[1].matchAll(/^\s*(\w+)\s*:/gm)].map(m => m[1]);
  if (deJava.length < 10 || deMovil.length < 10) throw new Error('Muy pocos nombres de iconos; ¿cambió el formato?');
  return [...new Set([...deJava, ...deMovil])];
}

// ---------------------------------------------------------------------------------------------
// Descargas (npm pack + tar), fuera del repositorio

function npm(argumentos, cwd) {
  const win = process.platform === 'win32';
  return execFileSync(win ? 'npm.cmd' : 'npm', argumentos, { cwd, encoding: 'utf8', shell: win, stdio: ['ignore', 'pipe', 'pipe'] });
}

function paquete(nombreNpm, version) {
  const carpeta = path.join(CACHE, nombreNpm.replace(/[@/]/g, '_') + '-' + version);
  const raiz = path.join(carpeta, 'package');
  if (fs.existsSync(path.join(raiz, 'package.json'))) return raiz;
  fs.mkdirSync(carpeta, { recursive: true });
  console.log(`Bajando ${nombreNpm}@${version}...`);
  const salida = npm(['pack', `${nombreNpm}@${version}`, '--silent'], carpeta).trim().split(/\r?\n/).pop();
  execFileSync('tar', ['xzf', salida], { cwd: carpeta });
  fs.rmSync(path.join(carpeta, salida));
  const pj = JSON.parse(fs.readFileSync(path.join(raiz, 'package.json'), 'utf8'));
  if (pj.version !== version) throw new Error(`${nombreNpm}: se esperaba ${version} y llegó ${pj.version}`);
  return raiz;
}

// ---------------------------------------------------------------------------------------------
// Lectura mínima de SVG: elementos con atributos heredables, sin defs/clipPath/mask

const HEREDABLES = ['fill', 'stroke', 'fill-rule', 'clip-rule', 'opacity', 'fill-opacity', 'stroke-width'];
const IGNORAR = new Set(['defs', 'clipPath', 'mask', 'title', 'desc', 'style', 'linearGradient', 'radialGradient', 'symbol']);

function atributos(texto) {
  const a = {};
  for (const m of texto.matchAll(/([\w:-]+)\s*=\s*"([^"]*)"/g)) a[m[1]] = m[2];
  return a;
}

function elementosSvg(svg) {
  const limpio = svg.replace(/<!--[\s\S]*?-->/g, '').replace(/<\?[\s\S]*?\?>/g, '');
  const pila = [{ nombre: 'raiz', attrs: {}, transform: '', ignorar: false }];
  const salida = [];
  let raiz = null;
  for (const m of limpio.matchAll(/<(\/?)([\w:-]+)((?:\s+[\w:-]+\s*=\s*"[^"]*")*)\s*(\/?)>/g)) {
    const [, cierra, nombre, textoAttrs, sola] = m;
    if (cierra) { pila.pop(); continue; }
    const padre = pila[pila.length - 1];
    const a = atributos(textoAttrs);
    if (nombre === 'svg') raiz = a;
    const heredados = { ...padre.attrs };
    for (const k of HEREDABLES) if (a[k] !== undefined) heredados[k] = a[k];
    // estilos en línea sencillos (style="fill:none;stroke:...")
    if (a.style) for (const par of a.style.split(';')) {
      const [k, v] = par.split(':').map(s => s && s.trim());
      if (k && v && HEREDABLES.includes(k)) heredados[k] = v;
    }
    const nodo = {
      nombre, attrs: heredados,
      transform: [padre.transform, a.transform || ''].join(' ').trim(),
      ignorar: padre.ignorar || IGNORAR.has(nombre),
    };
    if (!nodo.ignorar && ['path', 'circle', 'ellipse', 'rect', 'line', 'polyline', 'polygon'].includes(nombre)) {
      salida.push({ nombre, propios: a, attrs: heredados, transform: nodo.transform });
    }
    if (!sola) pila.push(nodo);
  }
  return { raiz, elementos: salida };
}

const num = v => (v === undefined || v === '' ? 0 : parseFloat(v));

function aRuta(e) {
  const a = e.propios;
  switch (e.nombre) {
    case 'path': return a.d || '';
    case 'circle': {
      const cx = num(a.cx), cy = num(a.cy), r = num(a.r);
      return `M${cx - r} ${cy}a${r} ${r} 0 1 0 ${2 * r} 0a${r} ${r} 0 1 0 ${-2 * r} 0`;
    }
    case 'ellipse': {
      const cx = num(a.cx), cy = num(a.cy), rx = num(a.rx), ry = num(a.ry);
      return `M${cx - rx} ${cy}a${rx} ${ry} 0 1 0 ${2 * rx} 0a${rx} ${ry} 0 1 0 ${-2 * rx} 0`;
    }
    case 'rect': {
      const x = num(a.x), y = num(a.y), w = num(a.width), h = num(a.height);
      let rx = a.rx !== undefined ? num(a.rx) : (a.ry !== undefined ? num(a.ry) : 0);
      let ry = a.ry !== undefined ? num(a.ry) : rx;
      rx = Math.min(rx, w / 2); ry = Math.min(ry, h / 2);
      if (!rx || !ry) return `M${x} ${y}h${w}v${h}h${-w}Z`;
      return `M${x + rx} ${y}h${w - 2 * rx}a${rx} ${ry} 0 0 1 ${rx} ${ry}v${h - 2 * ry}a${rx} ${ry} 0 0 1 ${-rx} ${ry}`
        + `h${-(w - 2 * rx)}a${rx} ${ry} 0 0 1 ${-rx} ${-ry}v${-(h - 2 * ry)}a${rx} ${ry} 0 0 1 ${rx} ${-ry}Z`;
    }
    case 'line': return `M${num(a.x1)} ${num(a.y1)}L${num(a.x2)} ${num(a.y2)}`;
    case 'polyline':
    case 'polygon': {
      const n = (a.points || '').trim().split(/[\s,]+/).map(Number);
      let d = '';
      for (let i = 0; i + 1 < n.length; i += 2) d += (i === 0 ? 'M' : 'L') + n[i] + ' ' + n[i + 1];
      return e.nombre === 'polygon' ? d + 'Z' : d;
    }
    default: return '';
  }
}

const sinPintura = v => v === undefined ? false : (v === 'none' || v === 'transparent');

/**
 * Convierte un SVG a la lista de trazos con prefijos para un estilo de tipo `tipo`.
 * Prefijos: ~ capa translúcida (duotono), ! regla par-impar, # rellenar (en estilo de trazo),
 * ^ dibujar como trazo sin relleno (en estilo de relleno; grosor = `grosor` del estilo, o 2 si no lo trae).
 */
function convertir(svg, tipo, caja, svgpath) {
  const { raiz, elementos } = elementosSvg(svg);
  if (!raiz) throw new Error('no es un SVG');
  const vb = (raiz.viewBox || '').trim().split(/[\s,]+/).map(Number);
  if (vb.length !== 4 || vb.some((v, i) => Math.abs(v - caja[i]) > 1e-9)) {
    throw new Error(`viewBox inesperado «${raiz.viewBox}» (se esperaba ${caja.join(' ')})`);
  }
  const escala = 24 / caja[2];
  const trazos = [];
  for (const e of elementos) {
    const d = aRuta(e).trim();
    if (!d) continue;
    if (/^M0 0h24v24H0z$/i.test(d.replace(/\s+/g, ''))) continue; // ayuda invisible de Tabler
    // Pintura efectiva: lo heredado del svg raíz ya está en attrs (la raíz es el primer nodo)
    const fill = e.attrs.fill === undefined ? 'black' : e.attrs.fill;
    const stroke = e.attrs.stroke === undefined ? 'none' : e.attrs.stroke;
    const rellena = !sinPintura(fill);
    const traza = !sinPintura(stroke);
    if (!rellena && !traza) continue;
    if (/^(#fff(fff)?|white)$/i.test(fill) && !traza) continue; // recortes pintados de blanco
    const opacidad = Math.min(num(e.attrs.opacity ?? '1'), num(e.attrs['fill-opacity'] ?? '1'));
    const translucida = opacidad < 0.99;
    const parImpar = e.attrs['fill-rule'] === 'evenodd' || e.attrs['clip-rule'] === 'evenodd';

    const p = svgpath(d);
    if (e.transform) p.transform(e.transform);
    p.translate(-caja[0], -caja[1]).scale(escala).round(2);
    const ruta = p.toString();
    if (!/^[Mm]/.test(ruta)) throw new Error('trazo que no empieza con M: ' + ruta.slice(0, 30));

    // Orden de los prefijos: ~, luego !, luego # o ^
    const capa = translucida ? '~' : '';
    if (tipo === 'trazo') {
      if (traza) trazos.push(capa + ruta);
      if (rellena) trazos.push(capa + (parImpar ? '!' : '') + '#' + ruta);
    } else {
      if (rellena) trazos.push(capa + (parImpar ? '!' : '') + ruta);
      if (traza) trazos.push(capa + '^' + ruta);
    }
  }
  if (!trazos.length) throw new Error('el SVG no tiene trazos visibles');
  return trazos;
}

// ---------------------------------------------------------------------------------------------
// Generación

function entradaMapa(valor, estilo) {
  if (typeof valor === 'string') return { icono: valor, nota: null };
  if (!valor || typeof valor.icono !== 'string') return null;
  return { icono: valor[estilo] || valor.icono, nota: valor.nota || null };
}

function serializar(obj) {
  // Un icono por renglón: compacto y con diferencias legibles en git.
  const est = Object.entries(obj.estilos).map(([nombre, e]) => {
    const iconos = Object.entries(e.iconos).map(([k, v]) => `   ${JSON.stringify(k)}:${JSON.stringify(v)}`).join(',\n');
    const cab = { tipo: e.tipo, ...(e.grosor !== undefined ? { grosor: e.grosor } : {}) };
    return `  ${JSON.stringify(nombre)}:${JSON.stringify(cab).slice(0, -1)},"iconos":{\n${iconos}}}`;
  }).join(',\n');
  const cabecera = { id: obj.id, nombre: obj.nombre, version: obj.version, licencia: obj.licencia, autor: obj.autor, url: obj.url, npm: obj.npm };
  return `${JSON.stringify(cabecera).slice(0, -1)},\n"estilos":{\n${est}},\n`
    + `"sustituciones":${JSON.stringify(obj.sustituciones)},\n"origen":${JSON.stringify(obj.origen)}}\n`;
}

function principal() {
  const nombres = nombresLogicos();
  const mapa = JSON.parse(fs.readFileSync(MAPA, 'utf8')).iconos;
  const sobrantes = Object.keys(mapa).filter(k => !nombres.includes(k));
  const errores = [];
  for (const n of nombres) {
    if (!mapa[n]) { errores.push(`«${n}» no está en mapa.json`); continue; }
    for (const p of PAQUETES) if (!entradaMapa(mapa[n][p.id], 'x')) errores.push(`«${n}» no tiene icono de ${p.id} en mapa.json`);
  }
  if (sobrantes.length) console.warn('Aviso: en mapa.json sobran ' + sobrantes.join(', '));
  if (errores.length) { console.error(errores.join('\n')); process.exit(1); }

  fs.mkdirSync(CACHE, { recursive: true });
  const require = createRequire(path.join(paquete(SVGPATH.npm, SVGPATH.version), 'index.js'));
  const svgpath = require('./index.js');

  const licencias = [];
  const faltan = [];
  const resultados = [];
  for (const p of PAQUETES) {
    const raiz = paquete(p.npm, p.version);
    const pj = JSON.parse(fs.readFileSync(path.join(raiz, 'package.json'), 'utf8'));
    if (pj.license !== p.licencia) throw new Error(`${p.npm}: licencia «${pj.license}», se esperaba «${p.licencia}»`);
    const salida = {
      id: p.id, nombre: p.nombre, version: p.version, licencia: p.licencia, autor: p.autor, url: p.url,
      npm: `${p.npm}@${p.version}`, estilos: {}, sustituciones: {}, origen: {},
    };
    for (const [nombreEstilo, est] of Object.entries(p.estilos)) {
      const iconos = {};
      for (const n of nombres) {
        const ent = entradaMapa(mapa[n][p.id], nombreEstilo);
        const archivo = path.join(raiz, est.archivo(ent.icono));
        try {
          if (fs.existsSync(archivo)) {
            iconos[n] = convertir(fs.readFileSync(archivo, 'utf8'), est.tipo, p.caja, svgpath);
          } else if (est.respaldo) {
            const otro = path.join(raiz, p.estilos[est.respaldo].archivo(ent.icono));
            if (!fs.existsSync(otro)) throw new Error('no existe ' + path.relative(raiz, archivo));
            const tipoResp = p.estilos[est.respaldo].tipo;
            const trazos = convertir(fs.readFileSync(otro, 'utf8'), tipoResp, p.caja, svgpath);
            // En un estilo de relleno, el dibujo de contorno va con ^ (trazo) y sus rellenos sin prefijo
            iconos[n] = tipoResp === 'trazo'
              ? trazos.map(t => {
                // trazo -> ^ (dibujar como trazo); # (relleno dentro del contorno) -> relleno normal
                const m = t.match(/^(~?)(!?)(#?)(.*)$/s);
                return m[3] ? m[1] + m[2] + m[4] : m[1] + m[2] + '^' + m[4];
              })
              : trazos;
            const previa = salida.sustituciones[n];
            const texto = `${ent.icono} (${nombreEstilo}: sin variante propia, se usa el dibujo de ${est.respaldo})`;
            salida.sustituciones[n] = previa ? previa + '; ' + texto : texto;
          } else {
            throw new Error('no existe ' + path.relative(raiz, archivo));
          }
        } catch (e) {
          faltan.push(`${p.id}/${nombreEstilo}/${n} (${ent.icono}): ${e.message}`);
          continue;
        }
        const usados = salida.origen[n] ? salida.origen[n].split(' / ') : [];
        if (!usados.includes(ent.icono)) salida.origen[n] = [...usados, ent.icono].join(' / ');
        if (ent.nota && !String(salida.sustituciones[n] || '').includes(ent.nota)) {
          salida.sustituciones[n] = `${ent.icono} — ${ent.nota}` + (salida.sustituciones[n] ? '; ' + salida.sustituciones[n] : '');
        }
      }
      salida.estilos[nombreEstilo] = { tipo: est.tipo, ...(est.grosor !== undefined ? { grosor: est.grosor } : {}), iconos };
    }
    resultados.push(salida);
    const leerTexto = f => fs.readFileSync(path.join(raiz, f), 'utf8').replace(/\r\n/g, '\n').trim();
    const notice = fs.readdirSync(raiz).find(f => /^NOTICE(\.(txt|md))?$/i.test(f));
    licencias.push({
      p, texto: leerTexto('LICENSE'),
      aviso: notice ? 'NOTICE del paquete (se reproduce sin cambios):\n\n' + leerTexto(notice)
        : (p.licencia === 'Apache-2.0' ? 'El paquete no trae archivo NOTICE. Iconos modificados por Caja Libre: '
          + 'subconjunto, trazos llevados a 24x24 y coordenadas redondeadas.' : null),
    });
  }
  if (faltan.length) { console.error('Faltan iconos:\n  ' + faltan.join('\n  ')); process.exit(1); }
  if (SOLO_REVISAR) { console.log('Todo el mapa resuelve.'); return; }

  fs.mkdirSync(SALIDA, { recursive: true });
  let total = 0;
  for (const r of resultados) {
    const texto = serializar(r);
    JSON.parse(texto); // validación
    const destino = path.join(SALIDA, r.id + '.json');
    fs.writeFileSync(destino, texto);
    total += Buffer.byteLength(texto);
    console.log(`${r.id}.json  ${(Buffer.byteLength(texto) / 1024).toFixed(1)} KB  estilos: ${Object.keys(r.estilos).join(', ')}`);
  }
  console.log(`Total ${(total / 1024).toFixed(1)} KB, ${nombres.length} iconos lógicos.`);

  const separador = '='.repeat(78);
  const lic = ['Licencias de los paquetes de iconos incluidos en Caja Libre (src/main/resources/iconos/*.json).',
    'Los iconos se incluyen en subconjunto y con los trazos modificados: llevados a una caja de 24x24,',
    'con círculos, rectángulos y líneas convertidos a trazos de ruta y coordenadas redondeadas.', ''];
  for (const { p, texto, aviso } of licencias) {
    lic.push(separador, `${p.nombre} ${p.version} (${p.npm}) — ${p.url}`, `Licencia: ${p.licencia}. Autor: ${p.autor}.`, separador, '');
    if (aviso) lic.push(aviso, '');
    lic.push(texto, '');
  }
  fs.writeFileSync(path.join(SALIDA, 'LICENCIAS.txt'), lic.join('\n'));
}

principal();
