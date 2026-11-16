# Paquetes de iconos

`generar-paquetes.mjs` produce `src/main/resources/iconos/<id>.json` (tabler, phosphor, heroicons, material,
iconoir) y `src/main/resources/iconos/LICENCIAS.txt` a partir de los paquetes npm originales en versiones fijas.
Lucide no se genera: sigue en `util/Iconos.java` y en `movil/index.html`.

## Regenerar

```sh
cd Punto-Ventas
node herramientas/iconos/generar-paquetes.mjs                     # baja a <tmp>/caja-libre-iconos
node herramientas/iconos/generar-paquetes.mjs --cache /otra/carpeta
node herramientas/iconos/generar-paquetes.mjs --solo-revisar      # solo comprueba que el mapa resuelve
```

Requiere Node 18 o más nuevo, `npm` y `tar` en el PATH. No instala nada en el repositorio: hace `npm pack` de
cada paquete (y de `svgpath`, que transforma los trazos) en la carpeta de descargas y los descomprime ahí.
Las versiones están en la constante `PAQUETES` del script; al subir una versión, regenerar y revisar con la
prueba `PaquetesIconosDatosTest` y una hoja de contacto.

## Agregar o cambiar un icono

1. El nombre lógico sale de `icono("…")` en `util/Iconos.java` y de las claves de `const ICONOS` en
   `movil/index.html`. El script falla si algún nombre no está en `mapa.json`.
2. En `mapa.json`, cada nombre lógico dice qué icono usar en cada paquete: `"nombre"` o
   `{"icono": "nombre", "nota": "por qué es aproximado", "<estilo>": "otro nombre solo para ese estilo"}`.
   Las notas acaban en `sustituciones` del JSON.
3. Correr el script y la prueba.

## Formato de `<id>.json`

```json
{"id":"tabler","nombre":"Tabler Icons","version":"3.52.0","licencia":"MIT","autor":"…","url":"…","npm":"@tabler/icons@3.52.0",
 "estilos":{"contorno":{"tipo":"trazo","grosor":2,"iconos":{"buscar":["M…","M…"]}},
            "relleno":{"tipo":"relleno","grosor":2,"iconos":{…}}},
 "sustituciones":{"climaSol":"sun-wind — por qué es aproximado"},
 "origen":{"buscar":"search"}}
```

- Todos los trazos están en una caja `0 0 24 24` (escalados desde 256 en Phosphor y desde `0 -960 960 960`
  en Material), con 2 decimales como máximo. Círculos, elipses, rectángulos, líneas y polilíneas van
  convertidos a `d`; se quitan los trazos invisibles y se aplican los `transform`.
- `tipo: "trazo"`: cada trazo se dibuja sin relleno, con extremos y uniones redondeados y grosor `grosor`.
- `tipo: "relleno"`: cada trazo se rellena, sin borde.
- Prefijos de un trazo (en este orden, solo los necesarios):
  - `~` capa translúcida (fondo del estilo duotono de Phosphor, opacidad 0.2).
  - `!` regla de relleno par-impar (`fill-rule="evenodd"`).
  - `#` en un estilo de trazo: este elemento se rellena (sin borde), p. ej. un punto.
  - `^` en un estilo de relleno: este elemento se dibuja como trazo, con el `grosor` del estilo (2 si no
    lo trae). Se usa cuando el paquete no tiene variante rellena del icono (se toma el contorno y se anota
    en `sustituciones`) o cuando el SVG relleno mezcla trazos (Iconoir).
- `origen`: icono del paquete usado para cada nombre lógico (varios separados por ` / ` si un estilo usa otro).
