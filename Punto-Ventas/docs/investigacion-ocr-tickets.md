# Lectura de tickets de proveedor con OCR — investigación y prototipo

Punto-Ventas · 2026-11-12 · Carpeta del prototipo: `/private/tmp/claude-501/-Users-eliassanchez-Desarrollo-GitHub-Punto-Ventas/1c42309b-c1f3-40e5-a0a0-8b1b94fbe5e7/scratchpad/ocr/`

## 1. Resumen y recomendación

**Costo por ticket: $0. Todo corre sin internet, dentro de lo que ya se instala.** La parte difícil (interpretar
el texto) se resuelve sin ningún modelo de lenguaje: reglas + comparación difusa con el catálogo de la tienda +
**aprendizaje por proveedor** (cada confirmación del usuario se guarda como alias "proveedor + texto del ticket →
producto + empaque"; el siguiente ticket del mismo repartidor se reconoce solo).

| Decisión | Recomendación | Por qué |
|---|---|---|
| Dónde corre el OCR | **En la PC (Java), dentro del servidor móvil que ya existe.** El teléfono solo toma la foto y muestra la pantalla de revisión. | Un solo lugar que programar y probar; el aprendizaje vive junto a la BD; funciona con cualquier teléfono (incluso viejos); no hay nada que descargar al celular. |
| Motor OCR, fase 1 | **Tesseract 5 vía Tess4J** (jar 9 MB + Leptonica 3.7 MB + `spa.traineddata` rápido 2.3 MB). | Único motor con DLL **Windows x86 de 32 bits** incluida; integración de 1–2 días; en el prototipo leyó las fotos con 2.2 % de error de caracteres y ~0.4 s por foto en una Mac (estimado 3–8 s en una PC de 2010). |
| Motor OCR, fase 3 (mejora) | **PP-OCRv5 "mobile" sobre ONNX Runtime Java** (modelos ≈ 20 MB), solo en x64. | Fue el más preciso del prototipo (0.7 % de error; 92 % de productos bien emparejados *sin* aprendizaje contra 78 % de Tesseract). Cuesta más integrarlo (pre/posproceso) y no hay ONNX Runtime para x86 de 32 bits. |
| Alternativa "teléfono" | **Tesseract.js (WASM) servido por la propia app** (≈ 6 MB que el celular guarda en caché), como opción cuando la PC sea muy lenta. | Mismo motor y misma calidad que Tesseract nativo; quita carga a la PC; no requiere internet ni HTTPS. Probado en Node, no en un teléfono real. |
| Nube / LLM | **Solo opcional, con la llave de la tienda** (Configuración → "Lectura asistida en la nube"). Por defecto apagado. | Un ticket con Claude Haiku 5.5 cuesta ≈ US$0.001 (2 centavos MXN) y con Gemini Flash-Lite algo menos, pero exige internet, mandar el ticket a un tercero y una llave por tienda; no puede ser el camino por defecto de una app gratuita. |
| Respaldo sin OCR | **Foto del código de barras** (API `BarcodeDetector` de Chrome Android, incluida desde Chrome 83) y buscador rápido de productos en la misma pantalla. | Para notas manuscritas y productos nuevos. Ningún motor gratuito lee bien letra a mano. |

**Expectativa honesta** (ver §6): con tickets impresos y foto razonable, la primera vez con un proveedor el usuario
corregirá 1 de cada 4–5 renglones; a partir del segundo o tercer ticket de ese proveedor, 0–1 renglón de cada 20.
La lectura de cantidades y precios es casi siempre correcta y la **verificación contra el total** del ticket detecta
los errores que quedan. Notas manuscritas: capturar a mano (buscador + código de barras).

## 2. Dónde correr el OCR — comparativa

### 2a. En el teléfono (navegador)

| Opción | Estado | Tamaño a descargar | RAM | Velocidad | Español | Licencia | Veredicto |
|---|---|---|---|---|---|---|---|
| **Tesseract.js 5/6** (Tesseract 5 compilado a WASM) | Maduro | núcleo `tesseract-core-simd-lstm.wasm` 2.9 MB + `spa` rápido ≈ 2.3 MB (se cachea) | 150–250 MB (worker) | Node/M5: 0.4–1.0 s por foto. Estimado en Android de gama media (2019+): 4–12 s. No medido en teléfono. | Sí (`spa`) | Apache-2.0 | **Viable como modo opcional.** Funciona sobre HTTP (Web Workers y WASM no exigen HTTPS; la cámara se abre con `<input capture>`, no con `getUserMedia`, que sí exige HTTPS). |
| **PP-OCR en el navegador** (`@paddleocr/paddleocr-js`, `ppu-paddle-ocr`, `ffocr`) sobre onnxruntime-web | Nuevo (2025–26) | `ort-wasm-simd-threaded.wasm` 11.2 MB + det 4.7 MB + rec latin 14 MB ≈ 30 MB | 300–400 MB | Sin SIMD/threads es lento; threads exigen cabeceras COOP/COEP y origen seguro | Modelo `latin_PP-OCRv5_mobile_rec` | Apache-2.0 / MIT | Demasiado pesado para celulares baratos y para un servidor HTTP en LAN. Descartado. |
| **`TextDetector`** (Shape Detection API) | **Nunca salió de la bandera** `#enable-experimental-web-platform-features`; solo Chrome Android con Play Services | 0 | — | Nativo (ML Kit) | Sí | — | No usable en producción. En cambio **`BarcodeDetector` sí está activo por defecto** (Chrome 83+, Android): sirve para el respaldo de códigos de barras. |
| ML Kit / Live Text nativos | No accesibles desde una página web | — | — | — | — | — | Requerirían una app nativa; fuera de alcance. |

### 2b. En la PC (Java)

| Opción | Tamaño en el instalador | RAM | Velocidad (M5 medida → PC vieja estimada ×8–15) | Windows x86 32 bits | Integración | Licencia |
|---|---|---|---|---|---|---|
| **Tess4J 5.13** (Tesseract 5.4.1 + Leptonica 1.84) | tess4j.jar 9.1 MB (DLL `win32-x86` 2.6 MB y `win32-x86-64` 3.3 MB **incluidas**) + lept4j 3.7 MB + JNA + `spa.traineddata` 2.3 MB (rápido) o 12 MB (mejor). En mac/Linux hay que empaquetar `libtesseract` aparte (brew/apt o compilado). | 80–150 MB por foto de 1.3 MP | 0.32–0.47 s foto → **3–8 s** | **Sí** | 1–2 días. Ojo: las DLL están compiladas con VS2022 → requieren el **VC++ 2022 Redistributable** (x86 o x64); el instalador WiX puede ejecutarlo en silencio o se copian `vcruntime140.dll`/`msvcp140.dll` junto a la app. | Apache-2.0 (Leptonica: BSD-2) |
| **PP-OCRv5 mobile en ONNX Runtime Java** (`com.microsoft.onnxruntime:onnxruntime`) | jar 93 MB porque trae nativos de 4 plataformas (se puede recortar a ≈ 25 MB por plataforma en jpackage) + det 4.7 MB + `latin_PP-OCRv5_mobile_rec` 14 MB + cls 0.6 MB | 200–300 MB pico | 0.6–1.1 s foto (RapidOCR, Python+ORT) → **6–15 s** | **No** (ORT dejó de publicar x86 de 32 bits) | 4–6 días si se escribe el pre/posproceso DB+CRNN en Java puro (sin OpenCV); 1–2 días usando `RapidOCR4j`/`mica-ppocr`/`RapidOcr-Java`, pero arrastran OpenCV nativo (+60 MB) y no publican nativos x86. | Apache-2.0 (modelos) + MIT (ORT) |
| **Python (RapidOCR/PaddleOCR) empaquetado** | ≥ 150 MB (intérprete + numpy + onnxruntime + modelos) | 300 MB+ | igual que la fila anterior | ORT sin x86 | Complica mucho jpackage (proceso hijo, rutas, antivirus); mantener dos runtimes | Apache-2.0 |
| **docTR / EasyOCR / Surya** | 200 MB–1 GB, PyTorch | 1–2 GB | lentos en CPU vieja | No | — | Descartados para este hardware |

### 2c. Nube (opcional, "trae tu propia llave")

| Servicio | Gratis | Después | Observaciones |
|---|---|---|---|
| Google Cloud Vision `TEXT_DETECTION` | 1 000 imágenes/mes | US$1.50 por 1 000 | Muy bueno con térmicos y aceptable con letra a mano. Requiere cuenta de facturación aunque no se cobre. |
| Azure AI Vision Read (F0) | 5 000 transacciones/mes, 20/min | US$1.00 por 1 000 | Similar a Google. Cuenta Azure. |
| OCR.space (gratuito) | 25 000/mes, archivo ≤ 1 MB | — | Sin tarjeta; calidad media; hay que comprimir la foto; sin garantías de privacidad. |
| Gemini 2.5 Flash-Lite (API) | Capa gratuita existe pero Google ya no publica límites fijos (del orden de 15 RPM / ~1 000 por día por proyecto) y **usa los datos para entrenar** | US$0.10 / 0.40 por millón de tokens | Multimodal: lee e interpreta en un paso. |
| Claude Haiku 5.5 | No hay capa gratuita | US$0.10 / 0.50 por millón | Ver costos por ticket en §7. |

En todos los casos el ticket (nombre de la tienda, RFC del proveedor, precios de compra) sale de la tienda: debe ser
una decisión explícita del dueño, por tienda.

## 3. Prototipo: qué se hizo y resultados reales

No encontré fotos públicas utilizables de notas de remisión de Bimbo/FEMSA/Sabritas (las búsquedas devuelven
plantillas y PDF fiscales). Generé **4 tickets sintéticos** con los formatos típicos (`py/generar_tickets.py`):

- **FEMSA**: código + descripción abreviada + `2 CJ`/`3 PQ` + precio unitario + importe, IEPS/IVA, un renglón `BONIF`.
- **Bimbo** (impresora de mano, angosta): cantidad + producto + importe (sin precio unitario), devolución con cantidad negativa y renglón `DEVOLUCION/CADUCO`, un `CAMBIO`.
- **Sabritas**: clave + descripción + `PAQ` + una sola cifra, `BONIF`, "DESC. PRONTO PAGO 3 %".
- **Abarrotera local**: `$2,610.60` con signo y coma de miles, empaques `CJ/12`, `BULTO/10`, `X12`, descuento 2 %.

De cada uno hay 3 imágenes: `limpio` (impresión), `foto` (1 300 px, papel gris, tinta tenue, luz dispareja,
giro de hasta 2°, desenfoque, ruido, JPEG 78) y `dificil` (900 px, menos contraste, más desenfoque, JPEG 60).
Carpeta `muestras/`. Texto esperado y líneas estructuradas en `muestras/verdad.json` y `java/verdad_*.csv`.

### 3a. Motores (Mac M5, un hilo de trabajo; multiplicar ×8–15 para una PC de ≤ 4 GB)

| Motor | Carga | Tiempo por imagen (foto/difícil) | CER limpio | CER foto | CER difícil | **CER global** |
|---|---|---|---|---|---|---|
| Tesseract 5.5 nativo, `spa`, PSM 6 | ≈ 0.1 s | 0.32–0.47 s | 0.0–0.6 % | 1.2–3.9 % | 2.7–6.3 % | **2.2 %** |
| Tesseract.js 5 (WASM, Node 24) | 0.34 s (datos ya en caché) | 0.74–1.0 s | 0.3–0.9 % | 1.9–7.5 % | 3.8–10.5 % | **3.9 %** |
| RapidOCR 3 = PP-OCRv6 det+rec "small" en ONNX Runtime CPU | 0.35 s | 0.62–1.07 s | 0.3–1.1 % | 0.0–1.1 % | 0.2–1.1 % | **0.7 %** |

CER = tasa de error de caracteres contra el texto real, sin contar líneas separadoras (`py/evaluar_cer.py`). Salidas
completas en `out/<motor>/*.txt`.

Errores típicos de Tesseract en las fotos: `680G → 6806`, `50G → 506`, `4 → A`, `5 → s`, `2.5L → 229`,
`309.60 → 2309.60`, `268.80 → 268.90`, `543.52 → 543.592`, y líneas de basura en los bordes (`EE KK`). PP-OCR casi no
confunde caracteres pero **pierde fragmentos pequeños** (un `1` de "1 PQ") y, al devolver cajas sueltas, hay que
**enderezar antes de agrupar por renglón**: con 2° de giro el agrupado ingenuo por Y mezcló columnas (CER 40 %);
estimando el ángulo con la mediana de las cajas y girando las coordenadas, bajó a 1.1 %. Tesseract hace ese
enderezado internamente.

### 3b. Intérprete (Java, sin dependencias: `java/InterpreteTicket.java`, ≈ 330 líneas)

Se corrió sobre la salida de cada motor, para los 4 proveedores × 2 variantes de foto (64 renglones esperados),
dos veces: **frío** (sin alias) y **caliente** (tras "confirmar" el ticket limpio de ese proveedor una sola vez, lo que
simula la primera revisión del usuario). Catálogo de prueba: 40 productos con nombres como los escribiría la tienda
("Coca-Cola 600 ml", "Pan Bimbo blanco grande 680 g") e incluyendo distractores (355 ml lata, 1.5 L, Light, 460 g,
Gansito doble, Doritos 150 g…). Resultados (`out/parser_resultados.txt`):

| Motor | Fase | Renglones detectados | Producto correcto | Cantidad correcta | Costo unitario correcto | "Verdes" (conf ≥ 0.85) / equivocados | Renglones falsos | Total cuadra (de 8 tickets) |
|---|---|---|---|---|---|---|---|---|
| PP-OCR (RapidOCR) | frío | 64/64 | **59/64 (92 %)** | 64/64 | 63/64 | 51 / 0 | 0 | 7 |
| PP-OCR (RapidOCR) | caliente | 64/64 | **64/64 (100 %)** | 64/64 | 63/64 | 62 / 0 | 0 | 7 |
| Tesseract nativo | frío | 62/64 | **50/64 (78 %)** | 59/64 | 56/64 | 29 / 0 | 1 | 7 |
| Tesseract nativo | caliente | 62/64 | **61/64 (95 %)** | 59/64 | 56/64 | 58 / 0 | 1 | 7 |
| Tesseract.js | frío | 61/64 | 47/64 (73 %) | 59/64 | 59/64 | 21 / 0 | 2 | 6 |
| Tesseract.js | caliente | 61/64 | 58/64 (91 %) | 59/64 | 59/64 | 59 / 1 | 2 | 6 |

Lo importante: **ningún renglón marcado en verde apuntó a un producto equivocado** (salvo 1 caso con Tesseract.js),
los tipos especiales (BONIF, CAMBIO, DEVOLUCION con cantidad negativa) se clasificaron bien en todos los casos, el
empaque (`24PZ`, `CJ/12`, `X12`, `BULTO/10`, `6P`) se extrajo, y el **cuadre contra el total** señaló exactamente los
tickets donde el OCR había dañado un precio (p. ej. Tesseract leyó `2309.60`: la fila se marcó "cantidad×precio ≠
importe" y el ticket "REVISAR: renglones suman 803.00; subtotal 901.50"). Tiempo de interpretación: 15–30 ms.

Ejemplo real de la corrida (Bimbo, Tesseract, foto; frío → caliente): en frío "DONAS AZUCAR 6P", "GANSITO 506" y
"TORTILLINAS 10P" salieron en rojo (el catálogo dice "azucaradas", "Marinela", "Tía Rosa"); tras una confirmación,
en el ticket *difícil* del mismo proveedor los 8 renglones salieron en verde por alias, incluso "GANSITO 506" (alias
por texto *parecido*, Jaro-Winkler ≥ 0.92 sobre la clave normalizada).

Advertencias sobre estos números: son tickets sintéticos con fuentes monoespaciadas y sin arrugas, sombras de la
mano, perspectiva fuerte ni tinta borrada. **Antes de cerrar la fase 1 hay que fotografiar 20–30 tickets reales de los
proveedores del dueño** y meterlos como recursos de prueba (sin datos personales). Espero que en real los porcentajes
"frío" bajen (60–80 %) y los "caliente" se mantengan (≥ 90 %) porque el alias por proveedor no depende de la calidad
del emparejamiento difuso.

## 4. Diseño del intérprete (sin LLM)

Entrada: lista de renglones de texto en orden vertical (Tesseract los entrega; con PP-OCR se construyen enderezando y
agrupando cajas por Y). Todo en un servicio puro `InterpreteTicketService` (sin Spring ni BD) para que se pruebe con
textos fijos, igual que `CalculoCobro`.

1. **Normalizar**: mayúsculas, sin acentos, espacios colapsados; en fichas numéricas corregir `O→0, I/l→1, S→5, B→8`
   (solo dentro de patrones `dd.dd`).
2. **Clasificar el renglón**: *pie* (`SUBTOTAL|TOTAL|IVA|IEPS|VENTA|DEVOLUCION|DESC`), *encabezado/informativo*
   (`RFC|FECHA|RUTA|CLIENTE|FOLIO|VENDEDOR|ENVASE|GRACIAS|www`…), *calificador* de la línea anterior
   (`DEVOLUCION/CADUCO`, `MERMA`, `CAMBIO` solos), o *artículo* (tiene ≥ 3 letras y al menos una cifra de dinero o
   una cantidad con unidad).
3. **Extraer campos** del artículo, de derecha a izquierda: cifras de dinero (`-?$?1,234.50`); marcas de tipo
   (`BONIF*`, `CAMBIO`, `DEV|DEVOLUCION|CADUCO|MERMA`); código de proveedor al inicio (4–8 dígitos); **empaque antes que
   cantidad** (`24PZ`, `X12`, `CJ/12`, `BULTO/10`, `6P`) para no confundir "24" con la cantidad; cantidad+unidad
   (`2 CJ`, `3 PQ`, `1 PAQ`) o cantidad al inicio; unidad suelta cuando el OCR perdió el número; medidas canónicas
   (`2.5L → 2500ML`, `1KG → 1000G`, `680 g → 680G`); el resto es la descripción del ticket.
4. **Completar con aritmética**: dos cifras → precio unitario e importe; una cifra → importe (o precio, según la
   plantilla del proveedor); `cantidad = importe / precio` si falta y da entero; `costo = importe / cantidad`;
   bonificación y cambio → costo 0; cantidad negativa → devolución. Aviso si `cantidad × precio ≠ importe` (±2 %).
5. **Emparejar con el catálogo** en este orden: (a) alias por **código del proveedor**; (b) alias por **texto exacto**
   normalizado; (c) alias por **texto parecido** (JW ≥ 0.92); (d) **difuso** contra todo el catálogo:
   `0.45·cobertura de fichas del ticket + 0.25·Jaccard + 0.30·Jaro-Winkler`, con expansión de abreviaturas
   (`CC→COCA COLA`, `NJA→NARANJA`, `GDE→GRANDE`, `ADOB→ADOBADAS`, `TORC→TORCIDITOS`, `EST→ESTANDAR`…) y **la medida
   manda**: misma medida +0.15, medida distinta −0.35 (así "600 ml" nunca cae en "2.5 L"). Si el segundo mejor está a
   < 0.08 del primero se baja la confianza y se avisa "otro producto parecido". Umbral mínimo 0.55; (e) código de barras
   EAN-13/8 presente en el texto → `ProductoRepository.findByCodigoBarras`.
   Con catálogos de 2 000–5 000 productos el difuso sigue siendo instantáneo (puro CPU, < 5 ms por renglón); se puede
   pre-filtrar por trigramas si creciera.
6. **Confianza y color**: verde ≥ 0.85, amarillo 0.60–0.85, rojo < 0.60 o con aviso aritmético. Verde = alias
   confirmado o difuso claro.
7. **Cuadre**: suma de importes de compras (y con devoluciones) contra `SUBTOTAL/VENTA` y `TOTAL`, aceptando
   descuentos/devoluciones del pie; si no cuadra, el ticket queda "REVISAR" y la pantalla lo dice.
8. **Aprender al confirmar** (§5.2): por cada renglón confirmado se crea/actualiza el alias por código y por texto
   (con `piezasPorEmpaque` y último costo) y se actualiza la plantilla del proveedor (cuántas cifras por renglón, código
   al inicio, palabra del total, firma del encabezado).
9. **Identificar al proveedor**: firma del encabezado contra `ProveedorPlantilla.firmaEncabezado` y `Proveedor.nombre`
   (difuso); si no, se propone el proveedor cuyo día de visita es hoy (`Proveedor.visitaEn`) y el usuario confirma.

### 4a. Precio de venta sugerido (no se lee del ticket)

`costoPieza = costoUnitario / piezasPorEmpaque` (alias → catálogo `piezasPorEmpaque`/`Unidad` → 1).
`precioSugerido = redondear(costoPieza × (1 + margen))`, con margen = del departamento (nuevo campo
`Departamento.margenSugerido`) → global en `Configuracion` → margen actual del producto (`precioVenta/precioCosto − 1`).
Redondeo a 0.50 configurable. Solo es una **sugerencia** mostrada cuando el costo cambió más de X % ("Costo subió de
$12.00 a $12.90: ¿subir precio de $17 a $18?"); si el usuario acepta, pasa por `HistorialPrecio` como hoy.

## 5. Modelo de datos y servicios (en el estilo del proyecto)

### 5.1 Entidades nuevas (`model/`)

```java
/** Lo que el sistema aprende de cada confirmación: cómo llama este proveedor a un producto de la tienda. */
@Entity @Table(name = "proveedor_alias",
       uniqueConstraints = @UniqueConstraint(columnNames = {"proveedor_id", "tipo_clave", "clave"}))
@Getter @Setter @NoArgsConstructor
public class ProveedorAlias {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "proveedor_id") private Proveedor proveedor;
    /** CODIGO = clave interna del proveedor ("2057"); TEXTO = descripción normalizada sin espacios ("COCACOLA600MLNR") */
    @Enumerated(EnumType.STRING) @Column(name = "tipo_clave", nullable = false, length = 10) private TipoClave tipoClave;
    @Column(nullable = false, length = 120) private String clave;
    @ManyToOne(optional = false) @JoinColumn(name = "producto_id") private Producto producto;
    /** Piezas que trae la unidad del ticket (CJ/PQ/PAQ/BULTO). Null = pieza suelta */
    @Column(name = "piezas_por_empaque") private Integer piezasPorEmpaque;
    @Column(name = "unidad_ticket", length = 10) private String unidadTicket;
    @Column(name = "ultimo_costo") private Double ultimoCosto;          // por pieza
    @Column(nullable = false) private int confirmaciones = 1;
    @Column(name = "ultima_vez") private LocalDateTime ultimaVez = LocalDateTime.now();
    public enum TipoClave { CODIGO, TEXTO }
}

/** Formato del ticket de un proveedor, deducido de las confirmaciones (no lo captura nadie). */
@Entity @Table(name = "proveedor_plantillas")
public class ProveedorPlantilla {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @OneToOne(optional = false) @JoinColumn(name = "proveedor_id", unique = true) private Proveedor proveedor;
    /** IMPORTE_SOLO (Bimbo), PRECIO_E_IMPORTE (FEMSA, abarrotera), PRECIO_SOLO (Sabritas) */
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Columnas columnas = Columnas.IMPORTE_SOLO;
    @Column(name = "codigo_al_inicio") private boolean codigoAlInicio;
    @Column(name = "palabra_subtotal", length = 30) private String palabraSubtotal;   // "VENTA", "SUBTOTAL"
    @Column(name = "palabra_total", length = 30) private String palabraTotal;         // "TOTAL A PAGAR"
    @Column(name = "impuestos_desglosados") private boolean impuestosDesglosados;     // IVA/IEPS aparte
    /** Texto del encabezado para reconocer al proveedor en la foto: "GRUPO BIMBO|AGENCIA" */
    @Column(name = "firma_encabezado", length = 200) private String firmaEncabezado;
    @Column(nullable = false) private int tickets;
    public enum Columnas { IMPORTE_SOLO, PRECIO_E_IMPORTE, PRECIO_SOLO }
}

/** Un ticket leído (borrador hasta que se aplica). Guarda el texto OCR para poder reinterpretar y depurar. */
@Entity @Table(name = "tickets_proveedor")
public class TicketProveedor {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne @JoinColumn(name = "proveedor_id") private Proveedor proveedor;
    @Column(nullable = false) private LocalDateTime fecha = LocalDateTime.now();
    @Column(length = 40) private String folio;
    @Lob @Column(name = "texto_ocr") private String textoOcr;
    @Column(name = "motor_ocr", length = 30) private String motorOcr;              // "tesseract-5.4", "ppocr-v5"
    @Column(name = "total_leido") private Double totalLeido;
    @Column(name = "total_calculado") private double totalCalculado;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 12) private Estado estado = Estado.BORRADOR;
    @Column(name = "usuario_id") private Long usuarioId;
    @Column(name = "usuario_nombre", length = 100) private String usuarioNombre;
    @Column(name = "orden_compra_id") private Long ordenCompraId;                  // si se recibió contra una orden
    @Column(name = "ruta_foto", length = 300) private String rutaFoto;             // opcional, carpeta de config
    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true) @OrderBy("renglon")
    private List<TicketProveedorLinea> lineas = new ArrayList<>();
    public enum Estado { BORRADOR, APLICADO, DESCARTADO }
}

@Entity @Table(name = "tickets_proveedor_lineas")
public class TicketProveedorLinea {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "ticket_id") private TicketProveedor ticket;
    @Column(nullable = false) private int renglon;
    @Column(name = "texto_ticket", length = 200) private String textoTicket;
    @Column(name = "codigo_proveedor", length = 20) private String codigoProveedor;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 14) private Tipo tipo = Tipo.COMPRA;
    @Column(nullable = false) private double cantidad;                              // en unidades del ticket
    @Column(name = "unidad_ticket", length = 10) private String unidadTicket;
    @Column(name = "piezas_por_empaque") private Integer piezasPorEmpaque;
    @Column(name = "costo_unitario") private double costoUnitario;                  // por unidad del ticket
    @Column private double importe;
    @ManyToOne @JoinColumn(name = "producto_id") private Producto producto;
    @Column(nullable = false) private double confianza;
    @Column(name = "origen_coincidencia", length = 12) private String origenCoincidencia; // ALIAS_COD, ALIAS_TXT, DIFUSO, USUARIO
    @Column(nullable = false) private boolean confirmada;
    @Column(name = "precio_venta_sugerido") private Double precioVentaSugerido;
    public enum Tipo { COMPRA, BONIFICACION, DEVOLUCION, CAMBIO }
    public double getPiezas() { return cantidad * (piezasPorEmpaque == null ? 1 : piezasPorEmpaque); }
    public double getCostoPieza() { return piezasPorEmpaque == null || piezasPorEmpaque == 0 ? costoUnitario : costoUnitario / piezasPorEmpaque; }
}
```

Campos nuevos en existentes: `Departamento.margenSugerido` (Double, null = usar global), `Configuracion.margenSugeridoGlobal`,
`Configuracion.ocrMotor` (`AUTO|TESSERACT|PPOCR|TELEFONO|NUBE`), `Configuracion.ocrNubeProveedor/llave` (cifrada como
las de Facturama), `Producto.piezasPorEmpaque` (opcional; hoy no existe, se puede derivar de `Unidad` si ya tiene factor).

### 5.2 Servicios

- `ocr/MotorOcr` (interfaz: `ResultadoOcr leer(BufferedImage)` → renglones con texto, caja y confianza) con
  `TesseractMotor` (Tess4J, instancia única, `setPageSegMode(6)`, `preserve_interword_spaces=1`, `spa`),
  más adelante `PpOcrOnnxMotor`, y `MotorNube` opcional. `OcrService` elige por `Configuracion.ocrMotor` y por
  arquitectura (`os.arch`), y hace el **preproceso**: reducir a 1 600 px de lado mayor, gris, umbral adaptativo
  (Sauvola, ventana 31 px — importante por la luz dispareja de las fotos), enderezar (proyección o el ángulo que
  reporta Tesseract) — todo con `java.awt.image`, sin OpenCV.
- `InterpreteTicketService` (puro): §4. Entrada `List<String>` + catálogo + alias + plantilla; salida
  `TicketProveedor` en memoria. 100 % cubierto con los 36 textos OCR del prototipo como recursos de prueba.
- `AprendizajeProveedorService`: `aprender(TicketProveedor confirmado)` → upsert de `ProveedorAlias` y
  `ProveedorPlantilla`; `olvidar(alias)` desde la pantalla de proveedores (por si se confirmó mal).
- `EntradaTicketService.aplicar(ticketId, usuario)`: verifica permiso (`AJUSTAR_INVENTARIO`; costos solo visibles con
  `VER_GANANCIAS`/`GESTIONAR_PROVEEDORES` como ya se decidió), por cada línea confirmada registra
  `MovimientoInventario` ENTRADA (devolución → SALIDA con motivo "Devolución a proveedor"; cambio → SALIDA del
  caducado + ENTRADA del nuevo, costo 0; bonificación → ENTRADA costo 0 sin tocar `precioCosto`), actualiza
  `Producto.precioCosto` (costo por pieza; promedio ponderado opcional), guarda `HistorialPrecio` si el usuario aceptó
  el precio sugerido, marca la orden de compra como recibida si venía de una, `AuditoriaService.registrar(...)`,
  estado APLICADO. Todo en una transacción, con los `UPDATE` atómicos de stock que ya existen para varias cajas.
- Servidor móvil (`ServidorMovilService`): `POST /api/tickets/foto` (JPEG ≤ 2 MB; el teléfono ya reduce con
  `canvas` a 1 600 px antes de subir) → `{ticketId, proveedor: {id, nombre, confianza, candidatos[]}, lineas[],
  cuadre}`; `POST /api/tickets/{id}/confirmar` (líneas editadas) → aplica y aprende; `GET /api/productos/buscar?q=`
  (ya hay inventario) para el selector; `POST /api/tickets/{id}/descartar`. El OCR corre en un hilo aparte con
  cola de 1 (una foto a la vez) y tiempo máximo 60 s; la página muestra progreso.

## 6. Flujo de uso (webapp móvil)

1. **Escanear ticket** (vista 10 actual): botón "Tomar foto" (`<input capture="environment">`), vista previa, consejo
   "ticket plano, sin sombra, que llene la pantalla". Se envía reducida.
2. **Leyendo…** (3–15 s según la PC): rueda con pasos "Enderezando / Leyendo / Buscando productos".
3. **Pantalla de revisión**:
   - Cabecera: proveedor detectado (chip editable; propone el que visita hoy), folio/fecha leídos, y el **cuadre**:
     "✔ Los 8 renglones suman $3,034.80 = subtotal" o "⚠ Suman $803.00 y el ticket dice $901.50: revisa los renglones
     en amarillo".
   - Lista de renglones, cada uno con color de confianza (borde verde/amarillo/rojo), texto original del ticket en
     gris pequeño, producto de la tienda en grande, `cantidad × empaque = piezas`, costo por unidad y por pieza, tipo
     (etiquetas "Bonificación", "Devolución", "Cambio" en otro color) y casilla. **Verdes pre-marcados; amarillos y
     rojos sin marcar** hasta que el usuario los toque.
   - Tocar un renglón: hoja con buscador de productos (el de inventario), ajuste de cantidad y piezas por empaque
     ("2 CJ × 24 = 48 pzas"), costo, tipo, "Crear producto nuevo" (prellena descripción, costo y precio sugerido) y
     "Este proveedor lo llama así: recordar" (activado por defecto → alias).
   - Renglón con costo distinto al actual: muestra "costo $12.00 → $12.90 (+7 %)" y, si aplica, "precio sugerido
     $18.00 (margen 40 %) [Aplicar]".
   - Botones: "Aplicar N renglones marcados" (confirma: entradas de inventario + aprendizaje), "Guardar borrador",
     "Descartar". Si hay orden de compra pendiente de ese proveedor, ofrece "Recibir contra la orden #123".
4. **Resultado**: "8 entradas registradas; 1 devolución; 1 cambio. Aprendidos 3 nombres nuevos de Bimbo."
5. **Respaldos** en la misma pantalla: "Agregar producto por código de barras" (foto del código → `BarcodeDetector`
   si existe, si no, el buscador) y "Capturar a mano" para notas manuscritas.
6. En el escritorio, pestaña Proveedores → "Tickets leídos" (historial, reabrir borrador, ver texto OCR, borrar un
   alias aprendido). No es indispensable en la fase 1.

## 7. LLM opcional: costos reales por ticket (para que quede claro por qué no puede ser el camino por defecto)

Supuestos: ticket de 30 renglones; foto de 1 300 × 1 800 px (los modelos la reescalan: Anthropic a ≤ 1 568 px de
lado ≈ 2 400 tokens; Gemini por mosaicos de 768 px ≈ 1 500 tokens); instrucciones 500 tokens; salida JSON de 30
renglones ≈ 1 200 tokens. Tipo de cambio de referencia 18.5 MXN/USD. Precios públicos a noviembre de 2026.

| Opción | Entrada | Salida | **Por ticket** | 60 tickets/mes por tienda | 1 000 tiendas (si lo pagara el proyecto) |
|---|---|---|---|---|---|
| Claude Haiku 5.5 (foto → JSON) | 2 900 tok × $0.10/M | 1 200 × $0.50/M | **≈ US$0.0009 (≈ 2 ¢ MXN)** | US$0.05 | US$54/mes |
| Claude Haiku 5.5 (solo texto: OCR local + interpretar) | 1 600 × $0.10/M | 1 000 × $0.50/M | ≈ US$0.0007 | US$0.04 | US$40/mes |
| Claude Sonnet 5.5 (foto) | 2 900 × $2/M | 1 200 × $10/M | ≈ US$0.018 (33 ¢ MXN) | US$1.08 | US$1 080/mes |
| Claude Opus 5.5 (foto) | 2 900 × $4/M | 1 200 × $20/M | ≈ US$0.036 (66 ¢ MXN) | US$2.14 | US$2 140/mes |
| Gemini 2.5 Flash-Lite (foto) | 2 000 × $0.10/M | 1 200 × $0.40/M | ≈ US$0.0007 | US$0.04 | US$41/mes |
| Mistral OCR 4 (solo OCR, sin interpretar) | $4 por 1 000 páginas | — | US$0.004 | US$0.24 | US$240/mes |
| Google Vision (solo OCR) | 1 000 gratis/mes, luego $1.50/1 000 | — | US$0–0.0015 | US$0 | US$0–90/mes |

Conclusión: con Haiku o Flash-Lite el costo por ticket es simbólico, pero exige (a) internet en la tienda, (b) una
llave de API por tienda o un servidor intermedio del proyecto con cuota y costo recurrente, y (c) mandar datos de
compra a un tercero. Por eso: **offline por defecto; nube como opción con la llave de la tienda**, y si se agrega,
que sea en el paso de *interpretación* (texto → JSON, más barato y privado que mandar la foto) con el mismo esquema
`TicketProveedor` como `output_config.format`, y que sus resultados pasen por la misma pantalla de revisión y el
mismo aprendizaje. Un modelo local pequeño (Qwen2-VL 2B, etc.) necesita 4–8 GB de RAM y minutos por foto en CPU vieja:
descartado.

## 8. Precisión que se puede prometer

| Situación | Lo que verá el usuario |
|---|---|
| Ticket impreso (térmico o matriz), foto plana con buena luz, proveedor ya "aprendido" | 0–1 corrección por ticket de 20 renglones; cantidades y costos bien en > 97 % de los renglones; cuadre OK. |
| Mismo caso, proveedor nuevo | 3–6 renglones por revisar (amarillo/rojo): nombres que el catálogo escribe distinto, productos que la tienda no tiene. Se corrigen una vez. |
| Foto torcida, con sombra o ticket arrugado/desteñido | Tesseract empieza a fallar en dígitos (`6/G`, `0/O`, `5/S`); el cuadre y la regla `cant × precio = importe` detectan la mayoría; el usuario corrige 2–5 cifras. PP-OCR (fase 3) aguanta mucho mejor estas fotos. |
| Ticket muy largo (> 40 renglones) | Hay que tomar 2 fotos; la pantalla permite "agregar otra foto al mismo ticket". |
| Nota manuscrita | No se lee. Captura manual con buscador y código de barras (o nube opcional: Google Vision lee letra de molde regular). |
| Tickets con dos renglones por artículo (descripción y debajo los números) | Soportado por la plantilla (`descripcionArriba`) después de la primera confirmación; en frío puede unir mal. |

Límites del prototipo: no hubo fotos reales (ni teléfono real para Tesseract.js); los tiempos son de una Mac M5
(multiplicar ×8–15 para la PC objetivo). Faltan por medir: Tess4J en Windows x86 de 32 bits con 4 GB (debería caber:
≈ 150 MB extra), consumo de batería del teléfono en modo Tesseract.js, y fotos de tickets reales de cada proveedor.

## 9. Esfuerzo estimado por fases

| Fase | Contenido | Días |
|---|---|---|
| 0. Núcleo | Entidades §5.1 + repositorios; `InterpreteTicketService` portado del prototipo; pruebas con los 36 textos OCR y 4 verdades (+ las fotos reales que aporte el dueño); `AprendizajeProveedorService`. | 2–3 |
| 1. OCR en la PC | Tess4J + `spa` rápido en el instalador (Windows x86/x64 con VC++ redist; mac con `libtesseract` empaquetado); preproceso `java.awt`; `MotorOcr`; endpoint `/api/tickets/foto` con cola; prueba en la PC real. | 2–3 |
| 2. Pantalla y aplicación | Vista de revisión en `movil/index.html` (colores, selector, empaque, crear producto, precio sugerido, cuadre); `EntradaTicketService` (entradas, devoluciones, cambios, orden de compra, auditoría); margen por departamento; respaldo por código de barras y captura manual. | 3–4 |
| 3. Precisión (después de ver tickets reales) | Opción A: `PpOcrOnnxMotor` en Java puro para x64 (det + cls + rec, posproceso DB sin OpenCV), modelos ≈ 20 MB, recorte del jar de ORT por plataforma. Opción B (más barata): modo "OCR en el teléfono" con Tesseract.js servido por la app. | A: 4–6 · B: 1–2 |
| 4. Opcional | Nube con llave propia (Google Vision para OCR o Haiku/Flash-Lite para interpretar texto) detrás de Configuración, con aviso de privacidad. | 1–2 |
| **Total** | Fases 0–2 dejan la función usable; 3 y 4 según resultados reales. | **8–10 (+ 2–8)** |

## 10. Licencias y empaquetado

- Tesseract 5 (Apache-2.0), Leptonica (BSD-2 "Leptonica license"), Tess4J y Lept4J (Apache-2.0), JNA (Apache-2.0 /
  LGPL-2.1 dual), `tessdata_fast` (Apache-2.0): todas compatibles con distribuir el instalador bajo GPLv3; basta
  incluir los avisos de licencia en el "Acerca de"/carpeta `licencias/`.
- Tesseract.js y tesseract.js-core: Apache-2.0. Se servirían desde los recursos de la app (≈ 6 MB), sin CDN.
- PaddleOCR y sus modelos PP-OCRv5: Apache-2.0. RapidOCR: Apache-2.0. ONNX Runtime: MIT.
- VC++ 2022 Redistributable: Microsoft permite redistribuirlo con la aplicación (instalación silenciosa `/quiet
  /norestart` desde WiX, o copiar las DLL de runtime junto al ejecutable).
- Servicios de nube: términos propios; Gemini en capa gratuita usa los datos para mejorar sus productos; Google/Azure
  de pago no. Debe quedar escrito en la pantalla de activación.
- Las fotos de tickets reales que se usen como pruebas no deben llevar datos personales (nombre de la tienda está
  bien si el dueño lo autoriza; evitar RFC de personas físicas).

## 11. Archivos del prototipo

- `py/generar_tickets.py` — genera las 12 imágenes y `muestras/verdad.json`.
- `py/correr_tesseract.sh`, `py/correr_rapidocr.py`, `js/correr_tesseractjs.mjs` — corren cada motor y guardan
  `out/<motor>/*.txt` y tiempos. `py/evaluar_cer.py` — CER por motor.
- `java/InterpreteTicket.java` — intérprete + emparejamiento + aprendizaje + evaluación (`--aprender`, `--evaluar`);
  `java/catalogo.csv` (40 productos), `java/verdad_*.csv`, `java/correr_todo.sh` (matriz completa),
  `out/parser_resultados.txt` (resultados).
- Nada de esto toca el repositorio; el intérprete está pensado para copiarse a
  `service/InterpreteTicketService` con pruebas en `src/test/resources/ocr/`.

### Fuentes consultadas (precios y datos de plataforma)

Cloud Vision: https://cloud.google.com/vision/pricing · Azure AI Vision: https://azure.microsoft.com/pricing/details/computer-vision/ ·
OCR.space: https://ocr.space/ocrapi · Gemini: https://ai.google.dev/gemini-api/docs/pricing · Mistral: https://mistral.ai/pricing/api/ ·
Anthropic: tabla de precios de la skill `claude-api` (2026-10) · PP-OCRv5 modelos: https://www.paddleocr.ai/latest/en/version3.x/module_usage/text_recognition.html ·
PaddleOCR.js: https://www.npmjs.com/package/@paddleocr/paddleocr-js · Tesseract.js: https://github.com/naptha/tesseract.js ·
onnxruntime-web tamaños: https://cdn.jsdelivr.net/npm/onnxruntime-web@1.20.1/dist/ · ONNX Runtime Java (plataformas): https://onnxruntime.ai/docs/get-started/with-java ·
ONNX Runtime sin 32 bits: https://onnxruntime.ai/docs/build/inferencing · Tess4J: https://tess4j.sourceforge.net/tutorial/ ·
Shape Detection API: https://developer.chrome.com/docs/capabilities/shape-detection · Benchmarks de recibos (vendedor, con reservas): https://imagetotable.ai/references/tesseract-vs-paddleocr-receipt-benchmark

## 12. Resultados con tickets reales (2026-11-15)

16 fotos reales de WhatsApp (900×1600, JPEG muy comprimido) de 9 proveedores distintos: térmicas (60 y 80 mm),
matriz de puntos con ceros con diagonal/punto (`Ø`), una hoja carta impresa, una nota a mano (no se evalúa) y
una foto girada 90°. Luz morada del mostrador, sombras de la mano, papel ondulado y doblado, otro ticket encimado.
**Las fotos tienen datos personales: no están en el repositorio.** La transcripción a mano (renglones de artículos
y totales de 15 tickets, 167 importes) vive fuera del repo; el arnés la lee de una ruta.

Arnés: `OcrMuestrasRealesTest` (opt-in, `APP_OCR_MUESTRAS=true`; variables en su javadoc). Medidas:
**CER por renglón** = distancia de edición mínima de cada renglón esperado contra cualquier tramo de lo leído
(sin mayúsculas, acentos ni espacios: el encabezado y la basura del fondo no cuentan) e **importes exactos** =
cifras con dos decimales que aparecen idénticas (lo que más le importa al intérprete). Mac M5.

### 12a. Tesseract 5.5.4 (`spa+eng`, `--psm 4`, proceso aparte) y el efecto de cada paso del preproceso

| Variante | CER | Importes exactos | ms/foto (prom.; máx.) |
|---|---|---|---|
| Foto cruda | 35.8 % | 34.7 % | 870; 1 480 |
| + recorte al papel + balance de blancos | 38.0 % | 37.1 % | 820; 1 520 |
| + aplanado del fondo (sombras, dobleces) | 24.4 % | 52.1 % | 970; 2 250 |
| + orientación 0/90 (docstrum) e inclinación | 12.7 % | 65.9 % | 1 200; 2 700 |
| **+ ampliar a renglón de 28 px (predeterminado)** | **11.6 %** | **71.9 %** | 1 760; 4 820 |
| ampliar a 36 px / 48 px | 12.8 % / 12.1 % | 66.5 % / 67.7 % | 1 980 / 2 540 |
| + Sauvola en Java (en vez del umbral de Tesseract) | 16.6 % | 66.5 % | 2 550 |
| sin recorte | 11.7 % | 68.9 % | 2 320 |
| sin balance de blancos | 13.4 % | 64.1 % | 2 130 |
| `--psm 6` / `--psm 3` / `--psm 11` | 14.8 / 12.1 / 12.4 % | 64.7 / 65.3 / 69.5 % | 3 360 / 2 250 / 2 780 |
| solo `spa` | 14.5 % | 59.9 % | 1 950 |

(Las filas de variantes se midieron con renglón de 36 px salvo la marcada como predeterminada.) El preproceso cuesta
0.12–0.3 s por foto. Lo que más ayuda: aplanar el fondo y orientar/enderezar. Detalle que costó: la proyección de
tinta **no** sirve para decidir 0° contra 90° en tickets de fuente monoespaciada (las columnas alineadas dan un perfil
tan nítido como los renglones); se decide por la dirección a la letra vecina más cercana ("docstrum") y la
inclinación fina sí por proyección, solo con componentes del tamaño de una letra (el fondo oscuro, las líneas `=====`
y el QR la torcían). Si la confianza sale < 0.72 se prueba la imagen a 180°.

### 12b. PaddleOCR (PP-OCRv6 small, ONNX Runtime 1.31.1), implementado en Java

| Motor / preproceso | CER | Importes exactos | ms/foto 4 hilos (prom.; máx.) | 1 hilo (prom.; máx.) |
|---|---|---|---|---|
| RapidOCR (Python), foto cruda (la de 90° sin girar) | 7.3 % | 97.0 % | 1 360; 2 040 | 3 000; 6 780 |
| Java, solo orientación | 7.9 % | 97.6 % | 740; 1 180 | — |
| Java, sin balance ni aplanado, con orientación | 6.7 % | 96.4 % | 770; 1 200 | — |
| **Java, balance + aplanado + orientación (predeterminado)** | **6.9 %** | **98.8 %** | 770; 1 250 | **1 510; 2 560** |
| Java, preproceso completo de Tesseract | 7.8 % | 95.8 % | 1 030; 1 640 | — |

Carga de los modelos: 0.7–0.8 s (una vez). Conclusión: **PaddleOCR gana con claridad** (importes 99 % contra 72 %) y
en esta Mac tarda ~1.5 s con un solo hilo, así que se implementó detrás de la selección automática. En una PC de 2
núcleos (×8–15) serían 12–38 s por ticket: ahí la medición automática elige Tesseract, como se aprobó.
Después de estas medidas se restringió el decodificador CTC a caracteres latinos (el modelo es multilingüe y a veces
sacaba `长`/`丨`) y se corrigió el diccionario (ONNX Runtime para Java corta los metadatos en el primer emoji); está
cubierto por pruebas unitarias y por el ticket de muestra, pero no se volvió a medir con las fotos.

### 12c. Integración elegida

- **Tesseract como proceso aparte** (`tesseract stdin stdout … tsv`, imagen en PGM por stdin, sin shell ni temporales),
  no Tess4J/JNA: un fallo nativo no tumba la caja, el límite de tiempo y la cancelación matan el proceso, la memoria
  vuelve al sistema al terminar y no hay jars nativos en el classpath. Costo: ~0.1 s de arranque por lectura.
  Búsqueda: `-Dapp.ocr.tesseract` / `CAJA_LIBRE_TESSERACT` → `<app>/ocr/tesseract/{bin,lib,tessdata}` del instalador →
  Homebrew / `/usr/bin` / `Program Files\Tesseract-OCR` / PATH (`ocr/LocalizadorTesseract`).
- **PaddleOCR**: `compileOnly` contra ONNX Runtime; el jar (50.9 MB, nativos de win-x64, linux-x64/aarch64 y
  osx-aarch64; **sin Mac Intel ni 32 bits**) y los modelos (det 9.9 MB + rec 21.2 MB) se bajan una vez a
  `<carpeta de la app>/ocr-ppocr/` con SHA-256 fijo (`ocr/RecursosPpOcr`), solo cuando la selección lo pide y solo al
  usar el lector o cambiar la preferencia (nunca al arrancar). `ocr/CargadorPpOcr` define `ocr.ppocr.MotorPpOcr` en un
  cargador "hijo primero" que ve el jar descargado (probado con el jar real y sin ORT en el classpath: lee el ticket
  de muestra en 0.9 s). Si ORT no carga se anota `ppocr.onnx=false` en `ocr.properties` y se usa Tesseract sin error.
- **Selección** (`ocr/SeleccionMotorOcr`, 14 pruebas): Rápido = Tesseract; Preciso = PaddleOCR si es de 64 bits y
  carga (mientras baja, Tesseract); Automático = 32 bits → Tesseract; RAM < ~6 GB → Tesseract (antes ~3 GB; ver 12e-8); ORT no carga →
  Tesseract; si no, mide el ticket de muestra incluido (`/ocr/ticket-muestra.jpg`, sintético) en segundo plano:
  ≤ 5 s → PaddleOCR. La medida se guarda con una huella (sistema, arquitectura, núcleos, GB) y se repite si cambia.
  Preferencia y medida por computadora en `ocr.properties` (no en la BD). Configuración → Funciones → "Lector de
  tickets" muestra qué se usa y por qué.
- **Ejecución**: `LectorTicketsService` (una hebra de baja prioridad, `Future` cancelable, límite de 60 s; la descarga
  y la medición van en otra hebra).

### 12d. Empaquetado de Tesseract por plataforma

Estado al 2026-11-15 (detalle operativo en `docs/publicar.md`, «Lector de tickets (Tesseract) en los instaladores»).
Todas las plataformas llevan los mismos idiomas: `spa` + `eng` de **tessdata_fast 4.1.0** (SHA-256 fijos en
`packaging/tesseract/tessdata.sha256`; idénticos a los de Homebrew) y `tessdata/configs/tsv`.

| Plataforma | Estado | Tamaño en el instalador |
|---|---|---|
| macOS arm64 | **Hecho** y en el CI: `brew install tesseract` + `bajar-tessdata.sh` → `empaquetarTesseract` copia el `tesseract` de Homebrew y sus bibliotecas que no son del sistema a `app/ocr/tesseract`, las reapunta (`install_name_tool`), las firma ad hoc, copia la licencia de cada fórmula a `licencias/` y comprueba que arranque y vea `spa`/`eng`. `comprobar-dmg.sh` monta el `.dmg` y lee el ticket de muestra desde la app empaquetada. | 14.7 MB sin comprimir (ejecutable 70 KB + 15 bibliotecas 8.4 MB + idiomas 6.4 MB); dmg probado en esta Mac |
| macOS x64 | Mismo código en el runner `macos-15-intel` con el mismo `brew install`; **sin probar en el CI todavía**. Sin PaddleOCR (ORT 1.31 no trae osx-x64). | ≈ igual |
| Windows x64 | `tesseract-windows.yml` compila con vcpkg (commit fijo de 2026.10.16: tesseract 5.5.4 + leptonica 1.87.0) un `tesseract.exe` **estático** (triplet propio `x64-windows-static-rel`: CRT estático, solo Release, sin curl/libarchive), con caché de Actions; `-PtesseractDir` lo copia a la app y el flujo lo prueba desde la app-image intermedia. **Sin probar en el CI todavía.** | ≈ 13–15 MB (estimado) |
| Windows x86 | Igual con `x86-windows-static-rel` (UB Mannheim ya no publica 32 bits). Opcional: si falla, el x86 sale sin lector incluido. **Sin probar en el CI ni en una PC de 32 bits real.** | ≈ 12–14 MB (estimado) |
| Linux | El `.deb` depende de `tesseract-ocr, tesseract-ocr-spa` y el `.rpm` de `/usr/bin/tesseract, (tesseract-langpack-spa or tesseract-ocr-traineddata-spanish)` (`--linux-package-deps` de jpackage); el `.tar.gz` lo documenta. El CI instala el `.deb` y lee el ticket con `/usr/bin/tesseract`. | 0 (del sistema) |

Hallazgo al empaquetar: `MotorTesseract` pide la salida con el config `tsv` (archivo `tessdata/configs/tsv`); la
carpeta `tessdata` del instalador de macOS no lo traía y Tesseract contestaba texto plano, que `TsvTesseract` no
entiende. Ahora todos los instaladores lo llevan, `comprobar.sh` exige salida TSV y `LocalizadorTesseract` ignora
una `tessdata` sin `configs/tsv`. (Más robusto aún: pedir `-c tessedit_create_tsv=1` en vez del config.)

Sin la carpeta, el programa usa un Tesseract instalado en el sistema si lo hay; si no hay ninguno, Configuración lo
dice ("no se encontró Tesseract"). macOS: Homebrew compila para la versión del runner (`minos`), así que el Tesseract
incluido solo arranca de esa versión de macOS en adelante; en una más vieja se descarta solo. Firma: si algún día se
firma el `.app` con Developer ID, revisar que `--mac-sign` firme también `app/ocr/tesseract/{bin,lib}`. Licencias en
`AVISOS-TERCEROS.md` y, junto al binario, `ocr/tesseract/LICENSE-tesseract.txt` + `ocr/tesseract/licencias/`.

### 12e. Lo que NO quedó resuelto (para la siguiente pasada) — estado al 2026-11-16 en §12f

1. ~~**Ceros de matriz de puntos**~~ (`Ø` leído `8`/`6`): resuelto en el intérprete. `Solucionador.variantes` prueba cada
   cifra con sus 8/6 como 0 y gana la combinación que cuadra cantidad × precio − descuento = importe (renglón en
   amarillo con el motivo); en el pie, `DESCUENTO: 8.88` pasa a 0 cuando subtotal = total lo exige.
2. **Papel ondulado**: mejorado. `PostprocesoPpOcr.renglones` ya no agrupa por la altura promedio del renglón sino por
   el fragmento **vecino a lo ancho** (el más cercano en x sin encimarse), con tolerancia que crece con el hueco;
   además el intérprete acepta el importe en otra columna (`resolver` prueba cualquier número con centavos como
   importe) y renglones de solo números con cuentas exactas (descripción perdida, en amarillo). Queda sin enderezado
   local por franjas; los dos tickets más arrugados (t11, t15) siguen mal.
3. **Renglones que se pierden con PaddleOCR**: sigue. Con la agrupación nueva ya no se *mezclan*, pero la detección
   aún se come la descripción bajo un doblez (t12: 3 de 6 descripciones) y el primer renglón bajo un QR (t03).
   Pendiente probar `box_thresh` más bajo y partir cajas demasiado altas (no se midió en esta pasada).
4. ~~**Asteriscos y símbolos**~~: `×`, `¥`, `|`, `]`, `}`, `)` se tratan como ruido o como el `1` que suelen ser
   (`fichas`); `TOTA]` → `TOTAL`, `IUA` → `IVA`, `TOTALAPAGAR` → `TOTAL A PAGAR` (`etiquetas`); `S9.31` contra `59.31` lo
   decide la cuenta subtotal + impuestos = total o IVA incluido (`alternativaConCinco`).
5. **Recorte al papel**: con Tesseract no mejora (11.6 % contra 11.7 % sin recorte) y podría cortar renglones si el
   papel toca otra hoja blanca; encendido para Tesseract, apagado para PaddleOCR. Falta una máscara que blanquee el
   fondo fuera del papel sin perder renglones que tocan la orilla.
6. **180°** se decide por confianza (segunda lectura completa): en una PC vieja duplica el tiempo de las fotos de
   cabeza. Mejor: el clasificador de ángulo de PaddleOCR (`cls`, 0.6 MB) o leer solo una franja. **Sigue pendiente.**
7. **Nota a mano**: ningún motor la lee (esperado): captura manual.
8. **PC vieja real**: tiempos no medidos en hardware de 2 núcleos. Memoria nativa de ORT: medida en simulación de 2
   núcleos subía a ~1.2 GB y no volvía (arena del CPU allocator); ahora `MotorPpOcr` crea las sesiones con
   `setCPUArenaAllocator(false)` y sin *memory pattern*, y el umbral de "Automático" para PaddleOCR subió de ~3 a
   **~6 GB de RAM** (`SeleccionMotorOcr.RAM_MINIMA_PPOCR`): la PC típica de tienda (4 GB) se queda con Tesseract.
9. ~~Empaquetado Windows/Linux de Tesseract y `brew install` en el runner Intel~~: preparado (tabla 12d); falta la
    primera corrida del flujo de release para confirmarlo en Windows x64/x86, macOS Intel y Linux.
10. ~~Espejo de descargas~~: `RecursosPpOcr` prueba primero la release `ocr-modelos-1` del proyecto y después la
    fuente original (mismo SHA-256); el espejo lo crea el flujo manual `ocr-modelos.yml`
    (`gh workflow run ocr-modelos.yml -R Minepacman/Punto-Ventas`, una vez, desde `main`).
11. **Endpoint móvil** que llame a `LectorTicketsService.leer(foto, espera)` y el intérprete (otro agente).
12. **Modo mixto** (Tesseract y re-lectura con PaddleOCR de renglones dudosos) no implementado: con PaddleOCR a ~1.5 s
    en equipos aptos no hace falta y en los no aptos no hay PaddleOCR. Revaluar con medidas en PC real.
13. **Salida TSV de Tesseract**: `MotorTesseract` ya la pide con `-c tessedit_create_tsv=1` en vez del config `tsv`, así
    que una `tessdata` sin `configs/tsv` sirve igual (`LocalizadorTesseract` dejó de descartarla).

### 12f. De la foto al ticket: medición de extremo a extremo (2026-11-16)

Hasta aquí solo se había medido el OCR (CER e importes). Esta pasada mide lo que importa al tendero: foto → motor →
`InterpreteTicket` → campos del ticket comparados con `tNN.esperado.json` (los mismos 16 fixtures de
`InterpreteTicketMuestrasTest`, 623 campos: proveedor, folio, fecha, totales y cada renglón con clave, descripción,
cantidad, empaque, precio, descuento e importe; una descripción cuenta bien con parecido Jaro-Winkler ≥ 0.85) y si la suma
de renglones **cuadra** con el total. Arnés opt-in `TicketsRealesExtremoAExtremoTest` (variables en su javadoc; también
mide el intérprete sobre textos ya leídos con `APP_TICKETS_TEXTOS`). La nota a mano (t04) no se evalúa.

**Ojo con la entrada**: el proceso de pruebas de esta sesión no podía leer las fotos originales (cuarentena de macOS),
así que corrió sobre las 16 imágenes **ya preparadas** por el preproceso de Tesseract de la sesión anterior (recorte,
aplanado, enderezado y ampliación; una de ellas sí fue el JPEG original). Para Tesseract es casi lo mismo; para PaddleOCR
la imagen ampliada le resta un poco (en la sesión anterior, 95.8 % de importes sobre preparadas contra 98.8 % sobre las
fotos). Conviene repetir con las fotos originales (`APP_TICKETS_FOTOS` a la carpeta del dueño).

| Motor | Campos correctos antes → después | Tickets que cuadran antes → después | Renglones encontrados (de 58) |
|---|---|---|---|
| Tesseract 5.5.4 (el de las PC viejas) | 60.7 % → **65.8 %** | 0/15 → **4/15** | 70 (con basura) → 73 |
| PaddleOCR (PP-OCRv6 small) | 71.3 % → **82.7 %** | 6/15 → **13/16** (t13 trae dos tickets) | 55 → 56 |

Por ticket (PaddleOCR, después): 100 % t06; 98 % t05; 92 % t01; 91 % t08; 88–89 % t02, t07, t09, t10, t16; 86 % t14;
79 % t12 (tres descripciones que la detección no vio; sin total en la foto); 71 % t13 (dos tickets: el primero perdió el
encabezado y la descripción); 62 % t03 (la descripción del primer renglón bajo el QR no salió); 50 % t11 y 33 % t15
(papel muy arrugado / hoja carta en ángulo: columnas revueltas por el OCR). Con Tesseract los tickets térmicos limpios
quedan en 88–92 % (t01, t03, t10, t16) y los de matriz de puntos o arrugados en 28–66 %.

Qué se corrigió para llegar ahí (todo con prueba en `InterpreteTicketCorreccionesTest` y `PostprocesoPpOcrTest`):
agrupación de fragmentos por vecino horizontal (PaddleOCR); importe en cualquier columna; renglones sin descripción pero
con cuentas exactas o con clave; renglón de solo "número y clave" absorbido por el de arriba; clave larga al final de la
descripción; clave con el número de renglón pegado (`7023427` → `023427`); ceros de matriz de puntos; centavos
truncados (`34.5`, `17.2`); números partidos (`324. 00`, `468 .81`); `|`/`]` sueltos = 1; unidad pegada al precio
(`$20.52c/u`); basura corta al final del renglón ya no convierte el importe en descripción; `pr: 126 DANONE…` ya no es
encabezado; etiquetas con corchetes/dos letras cambiadas; valor del pie = último número con centavos; **rescate
aritmético del pie** (el total un renglón arriba de su etiqueta o sin etiqueta: se adopta el número del pie que es la suma
de renglones, la suma más otro número del pie, o la suma con IEPS 8 % / IVA 16 %, con aviso); `S9.31`/`59.31` por
aritmética; fechas `11-13 2026` y años mal leídos (`2028` → `2026`); fecha de pasado mañana se voltea; después de
"AGRUPACIONES DE PRECIOS"/"FORMA DE PAGO" ya no se buscan artículos.

**Veredicto**: la etiqueta **Beta se queda**. El criterio era ≥ 95 % de renglones correctos y totales que cuadren con el
motor predeterminado; Tesseract (el que toca en PC de 4 GB) está en 66 % de campos y 4 de 15 tickets cuadran; PaddleOCR
en 83 % y 13 de 16. Lo que falta no es del intérprete sino del OCR en fotos malas (papel arrugado, dobleces, QR encima):
descripciones que no se detectan y columnas revueltas antes de llegar al texto. Siguiente paso con más efecto:
`box_thresh` más bajo y partir cajas altas en PaddleOCR, y repetir la medición con las fotos originales.

Pendiente de empaquetado (no se tocó en esta pasada): primera corrida del flujo de release con Tesseract en Windows
x64/x86 (vcpkg estático), macOS Intel y el `.deb`/`.rpm` con dependencia del sistema (tabla 12d); espejo de modelos
PP-OCR en la release `ocr-modelos-1` (`ocr-modelos.yml`, una vez desde `main`); en una PC de 32 bits real comprobar que
sin PaddleOCR la pantalla lo explique. Interfaz pendiente (plan aprobado, sin hacer): obligar a confirmar cada renglón
amarillo antes de «Registrar entrada» (o "Confirmar todos los dudosos" con conteo), que cambiar de proveedor no pierda lo
editado, recorte de la foto por renglón (la `y` de `Renglon` está en coordenadas de la imagen enderezada/recortada, no
de la foto original: si se gira 90° no corresponde; hay que devolver la imagen orientada o el giro y el recorte en
`ResultadoOcr`) y la pantalla "Lo que aprendí de este proveedor" (`aprendidoDe`/`olvidar` ya existen). Varias cajas: el
folio repetido ahora lleva `tickets_proveedor.repeticion` con índice único (proveedor, folio, repetición) que PostgreSQL
sí crea (Hibernate no lo crea en SQLite, donde no hace falta: un proceso y escrituras en serie); la segunda caja recibe
"Otra caja acaba de registrar este mismo ticket" y su entrada se deshace.
