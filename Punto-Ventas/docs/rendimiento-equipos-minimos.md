# Caja Libre en equipos mínimos ("que corra hasta en una piedra")

Revisión de plataforma, memoria, arranque, dibujo y SQLite para la PC más débil que razonablemente tendrá una
tienda: Windows 10 de 32 o 64 bits, Celeron/Atom de 2 núcleos a ~1.6 GHz, 2 GB de RAM (algunas 4 GB), disco duro
de 5400 rpm, gráficos Intel GMA/HD 2000 o sin controlador (dibujo por software), pantalla de 1024×768 o 1366×768.

Fecha: 2026-11-15 · versión 1.0.5 · datos crudos y arnés en el scratchpad `piedra/` de esa sesión.

## Cómo se midió

- El programa REAL (`Launcher` → JavaFX + Spring) con un observador que mide por cuadros dibujados (arnés de
  perf-105 adaptado): escaneos, F6, cobros, las 7 pestañas dos veces, desplazar la tabla de 5 000 productos y
  **dos teléfonos** consultando el servidor móvil cada 300 ms mientras la caja sigue vendiendo.
- Base realista: 5 000 productos, ~30 500 ventas / 106 000 renglones en 365 días, 300 promociones,
  50 proveedores, 91 000 movimientos de inventario, catálogo de 24 887 códigos.
- Mismas opciones de JVM que el instalador; `-XX:ActiveProcessorCount=1|2` para 1 o 2 núcleos; distintos
  `-Xmx`; con y sin archivo CDS (generado como lo hace `CalentadorCds`); `-Dprism.order=sw` para el dibujo sin
  tarjeta de video.
- Máquina: Mac con Apple Silicon. **Ojo**: un núcleo de Apple Silicon es ~5-8× más rápido que un Celeron
  N3050/Atom Z8350, y el Mac estaba muy cargado por otros agentes (Gradle e instancias del programa a la vez):
  los tiempos absolutos son ruidosos (medianas de 3) y sirven para comparar antes/después, no como promesa.
  La **memoria sí es comparable** entre plataformas (mismo programa, mismos datos).
- No se pudo simular un disco duro lento de forma fiable (macOS no deja estrangular un disco sin privilegios);
  el efecto del disco se razona por lecturas (CDS, índices, consultas que recorren tablas enteras).

## Lo más importante: la memoria se agotaba con 384 MB

Con el `-Xmx384m` de siempre, una sesión normal (vender, cobrar, abrir las 7 pestañas una vez, desplazar
Productos, dos teléfonos) **se quedaba sin memoria**: el recolector trabajaba sin parar («antes», 2 núcleos:
92 recolecciones completas y 32 s de GC en pocos minutos, la caja congelada al desplazar Productos; en otra
corrida, `OutOfMemoryError`). En una PC de 2 GB eso es una caja que se traba.

Memoria viva (heap usado justo después de un GC completo), misma sesión:

| Momento | Antes | Después |
|---|---|---|
| Tras el login | 77 MB | 76 MB |
| + Promociones, Productos, Inventario, Corte | 104 MB | 104 MB |
| + **Estadísticas** | **292 MB** | **130 MB** |
| Fin de la sesión (cobros, tablas, móvil) | 260-290 MB | 150-170 MB |

Causa (histograma de objetos antes/después de abrir Estadísticas): Estadísticas → **Reabastecimiento** armaba,
en cuanto se abre la pestaña Estadísticas y aunque nadie abra esa sub-pestaña, una `TableView` por proveedor
(50 en la base de prueba, desplegadas): ~5 100 celdas, 10 600 regiones, 7 500 rectángulos ≈ 150 MB de nodos de
JavaFX que se quedaban para siempre.

Arreglo (`controller/estadisticas/SeccionSurtido` + `Tarjetas.cuandoSeVea`): la tabla de cada grupo se arma
**cuando se ve** (sub-pestaña elegida y grupo desplegado) o al desplegarlo. Se ve igual y hace lo mismo; si el
dueño abre Reabastecimiento con muchos proveedores desplegados, la memoria sube entonces (y solo entonces).

Mínimo de memoria de Java (`-Xmx`) que aguanta la sesión completa con 2 teléfonos, ya con el arreglo:

| -Xmx | Resultado |
|---|---|
| 160 MB | no: recolección continua (la memoria viva es ~150-170 MB) |
| 192 MB | no: recolección continua al desplazar Productos |
| **256 MB** | **sí**: 98 GC, 1.1 s de GC en total; móvil p50 8 ms / p95 22 ms |
| 384 MB (instalador) | sí, con holgura: pico 242 MB, 95 GC, 0.9 s de GC; móvil p50 6 ms / p95 19 ms |

Se **conserva `-Xmx384m`**: el pico de una sesión normal es ~240 MB y hay operaciones raras que piden más
(«Ventas por período» de un año entero: pico de ~360 MB; ver decisiones). `-Xmx` es un techo, no una reserva:
Java arranca con 64 MB y solo toma lo que usa.

### Memoria según la RAM del equipo (decisión: fija)

jpackage no puede calcular opciones al arrancar y `-XX:MaxRAMPercentage` no admite piso y techo junto con
`-Xmx`. Se evaluó (a) `-XX:MaxRAMPercentage=12.5 -XX:MaxRAM=6g` (2 GB→256 MB, 3 GB→384 MB, 4 GB→512 MB) y
(b) que el programa reescriba su `PuntoDeVenta.cfg` según la RAM. (a) le da a la PC de 2 GB *menos* margen justo
donde una consulta grande podría quedarse sin memoria, y en equipos grandes más heap no le sirve a un punto de
venta; (b) arriesga dejar el programa sin arrancar si el archivo queda a medias. **Queda fijo en 384 MB** para
todos (también en 32 bits, donde cabe en los 2 GB de direcciones con todo lo demás).

## Arranque

AppCDS (`pos.jsa`, lo genera `CalentadorCds`) es lo que más pesa en un equipo lento. Se comprobó si la lista de
clases del calentador (Spring + JavaFX sin ventanas) se quedó corta con las funciones nuevas: le faltan ~2 100 de
16 000 clases (TLS del servidor móvil, controles y gráficas, controladores de login y principal), pero **el
arranque medido es igual** con esa lista que con la de una sesión real completa (login 3.6-3.8 s contra
3.7-4.1 s, 2 núcleos): no hace falta cambiar el calentador.

Arranque hasta el login (medianas de 3; «CPU» = tiempo de procesador gastado, el mejor indicador para un equipo
lento). `-XX:ActiveProcessorCount` solo cambia cuántos hilos crea la JVM, macOS sigue repartiendo en todos los
núcleos: por eso 1 y 2 «núcleos» dan casi lo mismo en reloj aquí.

| Escenario | Primera ventana | Login dibujado | CPU hasta login | Login → ventana principal |
|---|---|---|---|---|
| Antes, 2 núcleos, con CDS | 0.74 s | 4.7 s | 7.9 s | 0.29 s |
| Después, 2 núcleos, con CDS | 0.71 s | 4.0 s | 6.6 s | 0.28 s |
| Antes, 1 núcleo, con CDS | 0.65 s | 3.9 s | 6.6 s | 0.24 s |
| Después, 1 núcleo, con CDS | 0.68 s | 3.9 s | 6.5 s | 0.29 s |
| Después, 1 núcleo, **sin CDS** | 0.90 s | 5.3 s | 8.5 s | 0.27 s |
| Antes, 2 núcleos, sin CDS (otra tanda) | 0.95-1.24 s | 4.9-7.3 s | 7.9-10.8 s | 0.31 s |

El arranque no cambió (no era el objetivo de estos cambios; las diferencias son ruido de la máquina cargada).
**Estimación para un Celeron/Atom de 2 núcleos con disco duro**: ~6.5 s de CPU de Apple Silicon ≈ 35-50 s de un
núcleo de Celeron, repartidos en 2 núcleos ≈ **20-30 s hasta el login con CDS** (40-50 s sin CDS o en frío tras
encender la PC, cuando el disco duro todavía lee los ~110 MB del archivo CDS y los jars). La primera ventana
(«Abriendo la tienda…») sale en ~3-5 s, así que el cajero ve que algo pasa.

Sin archivo CDS (Windows de 32 bits si su JDK no lo admite, o el primer arranque) el login tarda bastante más. El
archivo dinámico (`-XX:ArchiveClassesAtExit`) no es alternativa: también requiere CDS y en JDK 17 necesita el
archivo base del JDK, que el runtime de jlink no trae.

Spring: las auto-configuraciones activas ya son pocas (no web, sin JMX). Quitar Jackson/JdbcClient/SSL/JTA
ahorraría decenas de ms y `JdbcTemplate` lo usan pruebas; no se tocó. La inicialización perezosa de beans y los
repositorios (varios segundos sin CDS, dominados por el análisis de las consultas de Spring Data) los trabaja el
agente perf-105.

## Dibujo con tarjeta de video débil o sin ella: «Modo ligero»

- **Cuándo se enciende**: Automático (de fábrica) = solo si JavaFX dibuja por software (`com.sun.prism.sw`: sin
  tarjeta de video o con un controlador que JavaFX rechaza, típico de Intel GMA y HD viejas; en Windows JavaFX
  ya prueba `d3d` y cae solo en `sw`, sin `-Dprism.order`). Se cambia en **Configuración → Apariencia →
  «Modo ligero (para computadoras lentas)»: Automático (recomendado) / Siempre / Nunca**. Es de *esta
  computadora* (`equipo.properties` en la carpeta de datos), no de la tienda ni del usuario, y se aplica al
  momento. `-Dapp.modo-ligero=true|false` lo fuerza (soporte).
- **Qué hace** (`css/ligero.css` + `AplicadorApariencia`): quita todas las sombras (`-fx-effect`), no genera el
  efecto vidrio (fondos degradados del tamaño de la ventana que se repintan en cada cuadro; la elección del
  usuario se conserva y vuelve al apagar el modo) y apaga las animaciones de paneles plegables y pestañas.
  Colores, tema, letra, iconos y **todas las funciones** se quedan igual: se ve completo y limpio (app.css ya
  pinta sus "sombras" con bordes y fondos).
- **Sugerencia**: `util/MedidorFluidez` anota en el registro (`logs/caja-libre.log`, para soporte) cuánto tardó
  en abrir, la tubería de dibujo, núcleos, RAM, memoria máxima y bits, y mide casi gratis (dos `nanoTime` por
  cuadro, solo los primeros 400 cuadros con cambios) lo que tarda JavaFX en estilos y acomodo. Si abrió en más
  de 30 s o la mediana pasa de 25 ms (o el p90 de 120 ms), la barra de estado muestra **una sola vez**
  «Equipo lento: probar el modo ligero» (con confirmación; nunca un diálogo que interrumpa la venta).

Medido con `-Dprism.order=sw` (dibujo por software, como una PC sin tarjeta), tabla de 5 000 productos
desplazándose 300 cuadros:

| | CPU de dibujo por cuadro | Cuadros |
|---|---|---|
| Sin vidrio, modo ligero apagado | 9.9-10.9 ms | 89-99 fps |
| **Con vidrio, modo ligero apagado** | **17.2 ms** | 58 fps (p95 21 ms) |
| Con vidrio pedido, **modo ligero automático (encendido)** | **9.8 ms** | 101 fps (p95 14 ms) |

El vidrio casi duplica el costo de dibujar por software; con el modo ligero el equipo dibuja como sin vidrio
aunque la tienda lo tenga elegido. En un Celeron (5-8× más lento) eso es pasar de ~100 ms por cuadro
(entrecortado) a ~55 ms. Las demás acciones (escaneo, cobro, F6, pestañas) cuestan lo mismo con y sin modo
ligero: su costo es de CPU en el hilo de JavaFX, no de dibujo. Con la apariencia de fábrica (sin vidrio) el modo
ligero solo quita las sombras de la tarjeta del login, las tarjetas de Configuración y los menús emergentes.

## SQLite en disco duro

- Pragmas ya correctos (WAL, `cache_size` 8 MB, `temp_store=MEMORY`, `mmap_size` 64 MB, `synchronous=FULL`,
  `journal_size_limit`). Cambio: **`mmap_size=0` con Java de 32 bits** (cada conexión del pool mapearía su
  propia ventana de 64 MB dentro de 2 GB de direcciones).
- `EXPLAIN QUERY PLAN` de las 104 consultas distintas de una sesión (servicios + pantallas + móvil): nada recorre
  tablas grandes al escanear ni al cobrar. Tres consultas recorrían tablas enteras:

  | Consulta | Antes | Después |
  |---|---|---|
  | Días tocados de los agregados (`fecha OR cancelación OR devolución`, tras cada cobro) | 6.5 ms, recorre ventas | 0.03 ms (MULTI-INDEX OR) |
  | Último día vendido por producto (rezagados, avisos) | 27 ms, recorre producto_diario | 5 ms |
  | Entradas con proveedor por producto (surtido) | 66 ms, recorre movimientos | 5 ms |

  Índices nuevos en `IndicesSqlite`: `ventas(fecha_ultima_devolucion)`, `producto_diario(producto_id, fecha)`,
  `movimientos_inventario(tipo, producto_id, id)`; y **`PRAGMA optimize`** (con `analysis_limit=1000`: ms y
  pocas páginas) una vez por arranque en la cola de `ArranqueDiferido` (con la ventana ya dibujada, prioridad
  mínima). Sin estadísticas SQLite no combina índices en consultas con OR. En disco duro, cada recorrido de
  tabla entera en frío son ~10 MB de lectura (0.5-1 s).
- No se agregó `VACUUM` automático (reescribe toda la base: minutos de disco duro con la escritura bloqueada).
  Los respaldos ya usan `VACUUM INTO` (copia compacta).

## Otros hallazgos

- `app.css` usaba variables (`-pos-radius`) en radios: JavaFX no las resuelve ahí y lanzaba y registraba una
  `ClassCastException` en cada pase de estilos de fotos de producto y de la donación. Ahora son números.
- Runtime de jpackage sin las herramientas de desarrollo (compilador, javadoc, jshell, jlink, jpackage,
  depurador, consola JMX, incubadoras…): en macOS el runtime pasa de 64 módulos / 129 MB a **44 módulos / 102 MB** (dmg de 112 MB); el
programa instalado desde ese dmg arrancó y llegó al login contra una copia de la base. Se calcula con el JDK de jpackage (vale igual para Windows de
  32 bits, que trae otros módulos); se excluye por nombre en vez de listar lo necesario para no romper algo raro
  (impresoras, TLS, locales) que las pruebas no ven. `-PruntimeCompleto` vuelve a lo de antes.

## Opciones de la JVM

| Opción | Decisión |
|---|---|
| `-Xms64m -Xmx384m` | se queda (ver arriba) |
| `-XX:+UseSerialGC` | se queda (medido antes: igual que G1 y con menos memoria) |
| `-XX:TieredStopAtLevel=1` | se queda (0.3-0.5 s menos de arranque) |
| `-XX:MaxMetaspaceSize=128m` | se queda: con CDS se usan 14-19 MB; sin CDS ~100 MB |
| `-XX:+UseStringDeduplication` | no aplica: en JDK 17 solo existe con G1/Shenandoah |
| `-XX:MaxHeapFreeRatio` / `MinHeapFreeRatio` | no: Serial solo devuelve memoria en un GC completo, que en uso normal casi no ocurre |
| `-Xss` menor | no: el analizador de consultas de Hibernate y los estilos de JavaFX son recursivos |
| `-XX:ReservedCodeCacheSize` | no hace falta: con `TieredStopAtLevel=1` la JVM ya reserva 48 MB (no 240); una sesión completa usa 31 MB |
| `-Dprism.order`, `-Dprism.vsync=false`, `-Dprism.dirtyopts` | no: JavaFX ya cae solo a `sw`; `dirtyopts` ya viene encendido; vsync no aplica al dibujo por software |

Todas las opciones valen en Windows de 32 bits (JDK 17 x86): ninguna es solo de 64 bits.

## Precargar pantallas (pedido del dueño: «pensarlo bien»)

**No se precarga nada.** Primera apertura de cada pestaña (2 núcleos): 90-300 ms; en una PC 5-8× más lenta,
0.5-2 s **una vez por sesión**; las siguientes, 20-170 ms. Precargarlas costaría la memoria de todas a la vez
(Estadísticas sola eran +188 MB antes del arreglo, +26 MB después) y trabajo de disco y procesador justo después
del login, cuando el cajero empieza a vender. Si algún día se quisiera: solo con ≥4 GB de RAM, en un hilo de
prioridad mínima, tras 2 minutos sin teclear y cancelable al primer escaneo.

## Lo que se puede prometer

**Mínimo que se puede afirmar con honestidad**: Windows 10 de 32 o 64 bits, procesador de 2 núcleos (Celeron/
Atom de ~1.6 GHz o mejor), **2 GB de RAM**, disco duro, cualquier tarjeta de video (o ninguna: modo ligero
automático), pantalla de 1024×768.

Con eso, estimado a partir de lo medido (CPU de Apple Silicon × 5-8, repartida en 2 núcleos):

| Acción | Medido aquí (2 núcleos, CDS) | Estimado en la PC mínima |
|---|---|---|
| Hasta el login | 4 s (6.5 s de CPU) | 20-30 s (40-50 s en frío / sin CDS) |
| Entrar → ventana principal | 0.3 s | 1-2 s |
| Escanear un producto | ~20 ms (15 ms en el hilo de JavaFX) | 0.1-0.15 s |
| Cobrar un ticket de 11 renglones | ~50 ms | 0.3-0.4 s (+ fsync del disco duro ~20 ms) |
| Abrir F6 | ~110 ms | 0.6-1 s |
| Primera vez en Estadísticas | ~0.25 s (0.4 s de CPU) | 1.5-3 s, una vez por sesión |
| Teléfono consultando mientras se vende | p50 6-8 ms, p95 16-22 ms | p50 ~50 ms |
| Memoria (proceso completo) | 420-580 MB (incluye ~110 MB del archivo CDS, compartido y en disco) | igual |

Con 2 GB, Windows 10 usa ~1.2-1.4 GB; el programa cabe sin paginar mientras no haya otros programas pesados
abiertos (navegador). Con 4 GB sobra. No se puede prometer fluidez con el efecto vidrio en dibujo por software
(por eso el modo ligero lo quita).

Antes de estos cambios el mínimo honesto era **4 GB**: con la memoria viva en ~290 MB tras abrir Estadísticas, el
techo de 384 MB se llenaba y la caja se trababa en recolección de basura.

## Decisiones para el dueño

1. **`synchronous=FULL` → `NORMAL`**: NO se cambió. En WAL, `NORMAL` ahorra un `fsync` por venta (~10-20 ms en
   disco duro, imperceptible) a cambio de que una venta ya cobrada pueda perderse si se va la luz antes del
   siguiente checkpoint. Recomendación: dejar `FULL`.
2. **«Ventas por período» con un año entero**: carga todas las ventas con renglones y pagos (~173 000 entidades;
   5.7 s aquí a 2 núcleos, ~30 s en un Celeron; pico de ~250 MB) y con más de 32 766 ventas en el rango la carga
   de pagos (`IN :ventas`) fallaría por el límite de parámetros de SQLite. Propuesta: limitar a 3 meses (como el
   móvil, `MAX_DIAS_VENTAS`) o cargar los renglones al elegir una venta. No se cambió (comportamiento visible).
3. **F6 lee los 5 000 productos de la base en cada apertura** (~110 ms aquí, ~0.5-1 s en Celeron con disco duro).
   Se podría conservar la lista entre aperturas y refrescar solo existencias; no se hizo porque la columna
   Existencia debe ser exacta tras cada venta.
4. **Reabastecimiento con muchos proveedores desplegados** sigue costando ~3 MB por grupo al abrirlo; si molesta,
   desplegar de inicio solo los primeros grupos.
5. Windows de 32 bits: confirmar en un equipo real que `CalentadorCds` genera `pos.jsa`; si el JDK x86 no lo
   admite, el arranque será notablemente más lento (sigue funcionando).
