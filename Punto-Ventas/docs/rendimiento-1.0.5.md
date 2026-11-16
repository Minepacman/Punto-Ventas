# Rendimiento 1.0.5 — medición en una PC vieja simulada y correcciones

Fecha: 2026-11-15. Versión 1.0.5 (rama `pos-basico` con el merge `4d2d543` y posteriores). Comparado contra los
números de la sesión 2026-11-12 (CLAUDE.md, «Rendimiento») y contra la **v1.0.4 publicada**, compilada desde su
etiqueta y medida en las MISMAS condiciones, alternando corridas.

Arnés, datos crudos y registros: carpeta `perf-105/` del scratchpad de la sesión (fuera del repo):
`harness/src/perf105/*.java` (Base, Esquema, Bench, Arranque, FxFlujo, Concurrencia, OcrBench, Sql),
`run.sh`, `fx.sh`, `ab.sh`, `comparar-*.sh`, `vidrio-ab.sh`, `ocr.sh`, `generar_datos.py`, `logs/`.

## 1. Método

- **Equipo simulado**: Mac Apple Silicon, JDK 17.0.19 (el del toolchain), jars de `installDist`, las MISMAS opciones de
  la JVM del instalador (`jvmOpciones` de build.gradle: `-Xms64m -Xmx384m -XX:+UseSerialGC -XX:TieredStopAtLevel=1
  -XX:MaxMetaspaceSize=128m` + límites del servidor móvil) y `-XX:ActiveProcessorCount=2`. Sin archivo CDS (el
  `$APPDIR/pos.jsa` no existe fuera del instalador): los tiempos de arranque son los de la primera vez de cada versión.
  `-Dprism.order=sw` simula una PC sin aceleración gráfica útil (dibujo por software).
- **No se pudo simular**: disco duro frío (`sudo purge` no está disponible), CPU lenta (`cpulimit` no está instalado;
  `taskpolicy -c background` no limita de forma reproducible). Igual que en 11-12: en la PC objetivo multiplicar la CPU
  por ×3–5 y lo que toca disco frío por bastante más.
- **Ruido**: la Mac estuvo muy cargada (carga 10–15: otros agentes con Gradle y la app, un juego abierto). Por eso cada
  comparación es **alternada** (A, B, A, B…) y además se reporta la **CPU del proceso** consumida hasta cada hito, que
  mide trabajo y no espera. Las primeras corridas de la tarde (carga 13–15) dan tiempos 2–3× más altos que las últimas
  (carga 6); en las tablas se dicen las condiciones de cada número.
- **Base de datos** (copia limpia en cada corrida; nunca el `datos.db` real; `-Duser.home` temporal): esquema creado por
  la propia app y llenado con `generar_datos.py`: 5 000 productos (20 departamentos, 4 % a granel, 1/3 con proveedor
  habitual), 30 571 ventas / **106 593 renglones** en 365 días, 91 098 movimientos de inventario + 6 000 entradas con
  proveedor, **300 promociones** (por producto y por departamento, horarios, días, «hasta agotar»), **50 proveedores**
  con días de entrega/preventa y **2 547 códigos aprendidos**, 600 caducidades, 365 cortes y fondos, 30 575 filas de
  bitácora, **catálogo de códigos cargado** (24 887) y agregados de Estadísticas calculados. Acceso móvil encendido.
- **Programa real**: `FxFlujo` llama a `Launcher.main` y un observador mide por cuadros dibujados (AnimationTimer, 2
  cuadros tras la condición): primera ventana, login, entrar (clic → principal), 11 escaneos, F6 (abrir, 5 teclas,
  cerrar, reabrir), 4 cobros, las 7 pestañas dos vueltas, Configuración → Apariencia, aplicar tema/iconos/letra/escala,
  300 cuadros desplazando la tabla de 5 000 productos sin y con vidrio, y uso simulado de 30 min.

## 2. Resultados: objetivo de 11-12 contra hoy

| Medida (2 núcleos simulados) | Objetivo / 11-12 | v1.0.4 (hoy, mismas condiciones) | 1.0.5 antes | 1.0.5 después |
|---|---:|---:|---:|---:|
| Primera ventana dibujada | 0.4–0.9 s | 0.88–1.02 s | 0.86–1.1 s | 0.86–1.27 s (igual) |
| Login dibujado, sin CDS (carga ≈6) | 3.2–4.0 s | 3.9–4.1 s (CPU 6.4–6.8 s) | **4.9–5.2 s (CPU 7.7–8.3 s)** | **2.9–4.6 s (CPU 5.0–7.6 s)**, mediana 3.3 s |
| Login dibujado con CDS | 2.4–2.9 s | — | — | no medido (ver §5) |
| Entrar (clic → principal) tras 3 s escribiendo | ~0.5 s | 0.18–0.26 s | 0.23 s | 0.19–0.31 s |
| Entrar con clic inmediato (precalentado sin terminar) | — | — | 0.7–1.5 s | 1.7–2.5 s (ver §4.2) |
| Tecla en F6 (hilo FX, síncrono) | 2 ms | — | 2–8 ms | **2.2–2.6 ms** |
| Sentencias por lista (`listarTodos`, Inventario) | 3 | 3 | 3 | 3 |
| Pestaña Promociones (300): sentencias / tiempo de servicio | — | (no existía) | **248 / 38 ms** | **5 / 6.5 ms** |
| Escaneo → renglón dibujado | — | — | — | 21–35 ms (hilo FX 20–34 ms de CPU) |
| Cobro de 4 renglones / 11 renglones (hasta ticket vacío) | — | — | — | 21 ms / 76 ms (66 ms en el hilo FX) |
| F6: abrir el buscador (5 000 productos) | 100–200 ms | — | 260–410 ms (cargada) | 104–136 ms |
| Pestañas, primera vez | 15–191 ms | — | 400–860 ms (cargada) | 56–215 ms (Productos 215, Promociones 167, Estadísticas 160) |
| Pestañas, segunda vez | 10–70 ms | — | — | 21–83 ms |
| **Montón tras abrir Estadísticas (tras GC)** | — | — | **+150 MB (247 MB); OutOfMemoryError al seguir** | **+29 MB (129 MB)** |
| Nodos en escena tras recorrer las pestañas | — | — | 21 307 | 6 612 |
| RSS tras el login | — | 389–397 MB | 440–470 MB | 439–471 MB |
| Clases cargadas tras el login | — | 17 520 | 18 500 | 18 550 |

### 2.1 Servicios (Bench, cargado = carga 13, luego carga 6)

| Operación | Antes (cargada) | Después |
|---|---:|---:|
| `PromocionService.listarTodas` (300) | 38 ms, **248 sentencias** | 6.5 ms, **5 sentencias** |
| `promocionVigente` (por renglón) | 1.0 ms, 2 sent. | 0.34 ms, 2 sent. |
| `recalcularTicket` 20 renglones | 8.9 ms, 34 sent. | 3.1 ms, 34 sent. |
| cobrar 5 / 20 renglones (incl. crearDetalle) | 25 / 154 ms; 43 / 208 sent. | 6.6 / 25 ms; igual |
| Corte (`estadoTurnoActual`) | 13 ms, 21 sent. | 6.1 ms |
| `avisos()` con caché / sin caché | 1.8 / 162 ms (8 251 entidades) | 0.9 / 89 ms |
| `ProgramadorAvisosPush.evaluar` (tic, sin suscripciones) | 0.5 ms | 0.24 ms |
| Reabastecimiento: plan / rezagados | 380 / 298 ms (12 000 entidades) | 156 / 190 ms |
| Sugerencias de Ventas (cada ticket y al volver a Ventas) | — | 142–144 ms, 12 220 entidades |
| Reporte de ventas 30 días / 365 días (`ventasEntre`) | 931 ms / 5.2 s | 211 ms / 2.8 s (173 000 entidades) |
| Catálogo `buscarLocal` | 0.24 ms | 0.11 ms |

### 2.2 Teléfonos, catálogo, Web Push, avisos

- **2 teléfonos pidiendo sin pausa** 45 s (sesión, avisos, ventas de hoy y 7 días, inventario, estadísticas día/semana/
  mes, proveedores, caducidades): 5 342 respuestas, **todas 200** (el semáforo de 2 llamadas pesadas no tuvo que dar
  503). Lo más caro: estadísticas del mes p50 70 ms, semana 49 ms. **El cobro en la caja no empeoró**: p50 5.3 ms,
  p95 8.4 ms con teléfonos (sin teléfonos: 9.8 / 41 ms, ruido de arranque).
- **Carga del catálogo incluido** (24 887 códigos, hilo de prioridad mínima): 189 ms (414 ms con la Mac cargada). Un
  cobro durante la carga tardó 5.8 ms; no hay efecto medible. En disco duro es una sola transacción.
- **Web Push**: el tic del programador sin suscripciones cuesta 0.24 ms/min. **Avisos**: la caché funciona (0.9 ms);
  sin caché 89–180 ms y 8 251 entidades (se recalcula pocas veces: aceptable).

### 2.3 Apariencia y vidrio

| | GPU (Metal) | Dibujo por software (`prism.order=sw`, PC sin aceleración) |
|---|---:|---:|
| Desplazar 5 000 filas sin vidrio (CPU de dibujo por cuadro) | 1.2 ms | 11.0–12.8 ms |
| …con vidrio completo | 1.2 ms (igual) | **21.5–21.7 ms (+70 %)** |
| …con vidrio ligero (nuevo, automático en equipos modestos) | — | **12.7–13.0 ms (+17 %)** |
| Aplicar tema oscuro / paquete de iconos / letra Inter / escala 130 % | 165+250 / 260 / 410 / 300 ms en el hilo FX | igual |
| Abrir Configuración → Apariencia | 76–370 ms | |

Aplicar una apariencia recalcula el estilo de todo lo abierto (~6 600 nodos): 0.25–0.45 s de hilo FX; es una acción
del usuario y ya no carga fuentes ni iconos al arrancar (la de fábrica no genera hoja ni carga letras).

### 2.4 Lector de tickets (SOLO medido; `ocr/` y `ticketsproveedor/` no se tocaron)

12 fotos de `scratchpad/ocr/muestras` (limpias, foto y difíciles de 4 proveedores), JVM del instalador, 2 núcleos:

| Motor | Carga | Por ticket (prom. / máx.) | RSS |
|---|---:|---:|---:|
| Tesseract 5.5.4 (proceso aparte, 1 hilo) | — | **1.7 s / 3.4 s** | JVM 142 MB (el proceso tesseract aparte) |
| PP-OCR v6 (ONNX, 2 hilos) | 0.8 s | **1.4 s / 1.8 s** | **207 MB al cargar → 1.2 GB tras leer** |

Para validar la selección «Automático» (`SeleccionMotorOcr`: RAM ≥ ~3 GB y PP-OCR ≤ 5 s): con 2 núcleos PP-OCR cabe
holgado en tiempo, **pero su memoria nativa crece a ~1.2 GB** (arena de ONNX Runtime con imágenes de distinto tamaño)
y no se devuelve. Sumado a los ~450–700 MB del programa, en una PC de 3–4 GB con Windows deja muy poco. Recomendación
para el agente del lector: subir el umbral de RAM de PP-OCR (≥ 6 GB) o cerrar la sesión ONNX tras cada lectura /
desactivar la arena (`SessionOptions.setCPUArenaAllocator(false)`, `setMemoryPatternOptimization(false)`) y volver a
medir. Tesseract en 1 hilo: 1–3.4 s aquí → 4–14 s en la PC vieja, en segundo plano (aceptable con el aviso de espera).

### 2.5 Uso prolongado (30 min)

Ver §6.

## 3. Regresiones encontradas y corregidas (cada una medida antes/después)

1. **Estadísticas llenaba el montón (crítica).** La sección «Frecuencia de surtido» armaba una tabla de 11 columnas por
   cada proveedor (50 + grupos sin proveedor), todas abiertas: 17 500 nodos y **+150 MB** retenidos de los 384 MB; al
   seguir usando el programa (Apariencia, F6) se llegó a **OutOfMemoryError** (dos corridas, GC completos de 0.7 s
   seguidos). Ahora cada grupo arma su tabla al abrir su panel y solo los 3 primeros con algo que pedir salen abiertos
   (los demás muestran en el título cuántos productos y cuánto cuesta el pedido). Nada se quitó. Antes/después: +150 →
   **+29 MB**, 20 774 → 6 079 nodos, montón tras recorrer todo 247 → 129 MB; sin OOM. `SeccionSurtido`.
2. **Login 1.5–2 s más tarde que en 1.0.4.** Con Spring Boot 3.5 crear los 39 repositorios analiza cada `@Query` con
   ANTLR: 3.2 s de pasos `repository.proxy` contra 1.2 s en 1.0.4, todo antes del login (`bootstrap-mode=deferred`).
   Ahora `bootstrap-mode=lazy` + `config/PrecalentarRepositorios`: la primera tarea del hilo diferido crea los que
   falten con el login ya en pantalla (prioridad mínima; solo CPU, nada de disco; no corre mientras se vende).
   Medido alternado: login 4.9–5.2 s (CPU 7.7–8.3) → **2.9–3.4 s (CPU 5.0–5.9)**; con 3 s escribiendo la contraseña la
   ventana principal sigue en 0.19–0.24 s. Las pruebas (modo de siempre) no cambian; `RepositoriosPerezososTest` cubre
   el modo real; el calentador CDS corre sin diferir y sigue cargando esas clases.
3. **Pestaña Promociones N+1**: `Promocion.producto` (EAGER) cargaba cada producto con su propio SELECT: 248
   sentencias. `@BatchSize(size = 100)` en `Producto` (regla de CLAUDE.md): **5 sentencias**, 38 → 6.5 ms. Ayuda
   también a códigos aprendidos, caducidades y renglones de órdenes.
4. **Vidrio caro sin aceleración gráfica.** Con dibujo por software las tres manchas `radial-gradient` del fondo se
   recalculaban bajo todo lo que se repinta: +70 % de CPU de dibujo al desplazar. Nuevo **vidrio ligero**, automático
   si `AplicadorApariencia.equipoModesto()` (≤ 2 núcleos o < ~4 GB) o si JavaFX dibuja por software
   (`-Dapp.vidrio.ligero=true|false` lo fuerza): las mismas manchas se pintan una vez en un PNG de 1440×900 sin escalar
   (escalar uno chico costaba igual que los degradados); paneles, tablas y sombras idénticos. +70 % → **+17 %**. Se
   probó también tablas opacas/rectas y sin sombra en tarjetas: sin efecto medible, descartado (no se ve distinto).
   `HojaApariencia`, `AplicadorApariencia`, prueba en `HojaAparienciaTest`.
5. **ClassCastException Double→Size en cada pasada de estilo** de `.imagen-zona`, `.imagen-marco`, `.asistente-logo`,
   `.donacion-cuenta` y el bloque de código: JavaFX solo resuelve variables de COLOR; `-pos-radius` en
   `-fx-*-radius` lanzaba (y registraba con traza) en cada CSS pass, y la esquina no se aplicaba. Números literales;
   comentario en el token. Cero advertencias en las corridas siguientes.
6. **ClimaService construía su HttpClient al crear el bean** (inicializa SSL y un ejecutor; ~100 ms antes del login en
   cada arranque, aunque el clima esté apagado). Ahora al primer uso, como `ActualizacionService`.

## 4. Lo que sigue arriba del objetivo o vale la pena vigilar

1. **Arranque de Spring con SB 3.5**: aun con repositorios perezosos, Hibernate 6.6 + autoconfiguración siguen siendo
   ~0.5–1 s más de CPU que 1.0.4 antes del login. El AppCDS (CalentadorCds) recupera parte; **falta volver a medir con
   un app-image y su `pos.jsa`** (no se armó jpackage en esta sesión para no cargar más la Mac).
2. **Entrar antes de que termine el precalentado**: si el cajero entra en < 2 s, los repositorios que falten se crean al
   usarse (Spring los serializa: no hay doble creación ni bloqueo de la caja, pero la ventana principal tardó 1.7–2.5 s
   en vez de 0.2 s en esa carrera). Con escritura normal (≥ 3 s) no se nota.
3. **RSS**: 440–470 MB tras el login (1.0.4: 390–400) y 690–740 MB tras recorrer todo con dibujo por software (montón
   comprometido al tope de 371 MB + metaspace 90 MB + nativos de JavaFX). En una PC de 4 GB es aceptable; con PP-OCR
   cargado no (ver 2.4).
4. **Sugerencias de Ventas**: 142 ms y 12 220 entidades por cálculo (cada ticket, con espera, y al volver a Ventas), en
   un hilo aparte. En la PC vieja ~0.5 s de un núcleo por ticket compitiendo con la caja. Candidato: cachear los
   puntajes día/hora (cambian con la hora, no con el ticket).
5. **Reporte de ventas de un año**: 2.8 s y 173 000 entidades en memoria (montón de 384 MB): con más volumen puede
   agotar el montón. Candidato: paginar o agregar en SQL como ya hace el móvil.
6. **Cobro en el hilo de JavaFX**: 11 renglones = 66 ms aquí (con `synchronous=FULL`: un fsync por venta); en disco duro
   ~0.3–1 s de pantalla quieta por cobro grande. Es a propósito (simple y seguro); no se cambió.
7. **Estadísticas recalcula todas sus secciones** (también las sub-pestañas no visibles: surtido, rezagados) cada vez que
   se entra: 0.35–0.85 s de CPU en segundo plano por visita.
8. **Aplicar apariencia**: 0.25–0.45 s de hilo FX con ~6 600 nodos; aceptable por ser acción del usuario.

## 5. Cambios de este trabajo (para el agente de «optimización extrema»)

- `model/Producto.java`: `@BatchSize(size = 100)`.
- `controller/estadisticas/SeccionSurtido.java`: tablas por grupo al abrir el panel (`GRUPOS_ABIERTOS = 3`).
- `config/PrecalentarRepositorios.java` (nuevo) y `application.properties`: `bootstrap-mode=lazy`.
- `service/ClimaService.java`: `HttpClient` perezoso.
- `util/apariencia/HojaApariencia.java`, `AplicadorApariencia.java`: vidrio ligero (`vidrioLigero`, `manchasPng`).
- `css/app.css`: radios literales en lugar de `-pos-radius`.
- Pruebas: `config/RepositoriosPerezososTest` (nueva), `HojaAparienciaTest` (vidrio ligero).
- No se tocaron: `ocr/`, `ticketsproveedor/`, `LeerTicketDialogo`, pragmas de SQLite, índices, opciones de la JVM.

## 6. Uso simulado de 30 minutos

Tras el recorrido completo (pestañas, apariencias, vidrio), 650 ciclos sin pausa de: 5 escaneos, cobrar, F6 + «coca» +
cerrar, Productos → Estadísticas → Inventario → Promociones → Ventas (400 ms en cada una). Es mucho más intenso que una
caja real (cambiar de pestaña cada 0.4 s recarga 5 000 productos cada vez). Registro GC en `logs/gc-soak30c.log`.

- **Sin fuga**: lo vivo tras cada GC completo se quedó entre **139 y 157 MB** de principio a fin (141 GC completos);
  clases 20 700 y metaspace 95 MB estables (tope 128 MB); RSS 690–760 MB. Cero tiempos agotados, cero errores.
- Con esa intensidad hay un GC completo cada ~13 s de **155–225 ms** (SerialGC, montón de 371 MB). En la PC vieja,
  ×3–4. En uso normal (sin recargar listas grandes cada medio segundo) son mucho menos frecuentes.
- Antes de la corrección de Estadísticas (§3.1) este mismo recorrido llegaba a OutOfMemoryError antes de empezar el
  uso prolongado.
