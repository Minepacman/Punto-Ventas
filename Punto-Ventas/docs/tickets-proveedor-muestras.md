# Tickets de proveedor: qué traen las 16 fotos de muestra

Análisis de las 16 fotos reales de tickets de proveedor que mandó el dueño (noviembre 2026). Las fotos
**no** están en el repositorio (traen nombres, direcciones y RFC de clientes). Cada una se transcribió a mano,
renglón por renglón, en `src/test/resources/tickets-proveedor/tNN.txt`, cambiando datos personales por
marcadores (`CLIENTE EJEMPLO`, `DIRECCION EJEMPLO`, `0000000000`, RFC `CRJ010101AB1`); lo esperado de cada
ticket está en `tNN.esperado.json` y lo revisa `InterpreteTicketMuestrasTest`.

## Resumen por proveedor

| Fotos | Proveedor (según el ticket) | Formato | Precios | Detalle a cuidar |
|---|---|---|---|---|
| t01, t11, t13 | Cervezas y Refrescos de Jalapa (terminal de ruta) | `CLAVE DESCRIPCION CANT PRECIO DESC` y el importe (`SubTot`) en el renglón de abajo (o al final del mismo renglón) | **Con** impuestos (IMPUESTOS ya está dentro del total) | Fechas **MES-DÍA-AÑO** con guion (`11-13-2026` = 13 de noviembre). Empaque en la descripción: `AMPER REG 12/.4` = paquete de 12, `PIÑ ADA 8/2L` = 8 botellas de 2 L. Descuento por renglón **antes de IVA**: 1 × 238.00 con descuento 5.17 da 232.00 = (238 / 1.16 − 5.17) × 1.16. t13 trae **dos tickets en la misma foto**. |
| t07 | Cervezas y Refrescos de Jalapa (hoja carta, productos Danone) | Dos renglones por artículo **al revés**: arriba `ZA 4 $ $15.50` (unidad, cantidad, precio), abajo `CLAVE DESCRIPCION $desc $importe` | Con impuestos | Foto girada 90° y con la orilla izquierda cortada: `ZA` es `PZA`, `0180175` es `30180175`, `edido:` es `Pedido:`. Fecha de impresión DÍA/MES. |
| t02, t08 | Gamesa (no se ve el encabezado; se reconoce por el formato) | Descripción arriba (`* EMPERADOR SENZO DTS RG3 117GRX48X1`), abajo `N CLAVE CANT $P.UNIT $ SUBTOTAL` | **Sin IEPS** (8 %): total = subtotal + I.E.P.S. | Al final repite un "resumen por precios" (AGRUPACIONES DE PRECIOS) que **no** son artículos. `117GRX48X1` = caja de 48, pero se venden por pieza. Sin fecha ni folio en las fotos. |
| t05 | Barcel (no se ve el nombre) | Descripción arriba (`Takis Ranch 1p 70g PC FLOW`), abajo `CANT 8.0% PRECIO $TOTAL` | **Sin IEPS** (8 %) | Los totales salen **dos veces** (antes y después de los artículos). "SUBTOTAL" aquí ya incluye IEPS; el subtotal de venta es "SUB.T VENTA". Folio partido en dos renglones. |
| t14 | Lala (Cedis / Promotor) | Descripción arriba, abajo `N CLAVE CANT PRECIO IMPORTE` | Con impuestos (IVA 0 en lácteos) | Descripción partida en dos renglones (`... 330ml Tpr` / `is`). |
| t12 | Mars (Pedigree, Whiskas; no trae el nombre) | `CLAVE DESCRIPCION` arriba, abajo `CANT PRECIO DESC SUBTOTAL` | Con IVA incluido; "SUBTOTAL" del pie es **antes** de IVA | El precio impreso está redondeado: 2 × 26.00 = 52 pero el renglón dice 51.99 (manda el importe). `24/85` = caja de 24, pero se vende por pieza. |
| t06, t09 | Distribuidora de Dulces y Chicles Ramírez | Un renglón: `CANT DESCRIPCION PRECIO IMPORTE` | Con impuestos | El precio a veces sale **pegado** a la descripción (`MAZAPAN GIGA109.00`). Fecha DÍA/MES/AÑO de dos cifras. Sin columnas tituladas. |
| t03 | Distribuidor de Ferrero (persona física; impreso con RawBT) | `8-ROCHER T2  $20.52 c/u  $164.16` | Con impuestos | La **cantidad va pegada** a la descripción con guion. El precio unitario puede ir partido en dos renglones (`$10.4112 c/` / `u`). |
| t10, t16 | BT Last Mile (cigarros) | `CANTIDAD DESCRIPCION TOTAL` → `0.05 MlbRcp20CD 468.81` | Con impuestos | La cantidad es en **paquetes de 100 cajetillas** (0.05 = 5 cajetillas, lo dice "Total cajetillas"). "Costo Distribución" es un **cargo aparte** que se suma al total. |
| t15 | Comercializadora MB (hoja carta con tabla) | `PRODUCTO CANTIDAD PRECIO SUBTOTAL` | Con impuestos | **Medio paquete** (0.5 de un paquete de 24 latas = 12 latas). Total con separador de miles (`1,220`). A mano escribieron un descuento (−124 → 1096) que el lector no ve: hay que corregir el importe en la revisión. |
| t04 | Botanas y dulces (formulario impreso llenado **a mano**) | `CANT. CONCEPTO PRECIO IMPORTE` con cantidades a mano y un precio por **grupo** (`16 × 5.50 = 88`, `31 × 8 = 248`) | — | El OCR no lee letra manuscrita. Sirve el modo "Escribir o pegar el texto": `5 GIRASOL`… y debajo `16 5.50 88` pone precio a los de arriba. |

## Cómo aparecen las cosas

- **Cantidad y empaque**: casi siempre en piezas, pero hay paquetes: `8/2L` (Jalapa), `24PZS` (MB), `40PZS`
  (Ramírez), cantidades con decimales (`0.5` paquete, `0.05` paquete de cigarros). El lector propone
  "piezas por unidad" comparando con el costo actual del producto; la primera vez se confirma y luego se recuerda
  por proveedor y clave. Ojo: `117GRX48X1` (Gamesa) o `24/85` (Mars) son el tamaño de la caja master, **no** lo
  que se compró.
- **Descuento por renglón**: columna `Desc` (Jalapa, Mars, Danone). En Jalapa se aplica antes de IVA.
- **Impuestos**: Jalapa, Lala, Mars, Ramírez, MB, Ferrero y BT dan precios **con** impuestos (el total es la suma
  de los renglones). Gamesa y Barcel dan precios **sin IEPS** y lo suman al final. El lector lo decide con el
  cuadre: si la suma de renglones + IEPS = total, reparte el IEPS en el costo de cada pieza (factor
  total ÷ suma). Igual con el costo de distribución de BT.
- **Costo que se guarda**: importe del renglón (ya con su descuento) × factor ÷ piezas. Se compara contra el
  precio de venta, que en la tienda siempre incluye impuestos: "manzanas con manzanas". (Una tienda que acredita
  IVA en su contabilidad tendría que verlo con su contador; para la tienda pequeña el costo real es lo pagado.)
- **Totales**: `TOTAL`, `TOTAL $`, `TOTAL=`, `Total a pagar`, `Total final`. Subtotales: `SUBTOTAL`, `SUB.T VENTA`,
  `Total ventas`, `Importe:`. No son totales: `TOTAL DE PRODUCTOS`, `Total paquetes`, `Total cajetillas`, `PIEZAS`.
- **Folio**: `Folio:`, `Factura:`, `Nota de venta:`, `REMISION:`, `Pedido:`, `Adicion BT04 6500`.
- **Fecha**: con diagonal es DÍA/MES (México); con guion los equipos de ruta imprimen MES-DÍA.
- **Fotos**: luz morada del exhibidor, papel doblado y girado, orillas cortadas (etiquetas como `UBTOTAL`,
  `olio:`), números que se leen corridos (los valores del pie salen un renglón arriba de su etiqueta en t02 y t14
  por la inclinación). El lector tolera etiquetas recortadas y letras confundidas (O/0, l/1, S/5, B/8, punto decimal
  perdido).

## Precisión medida (pruebas automáticas)

- Texto limpio (transcripción fiel): **100 %** de los campos (753 de 753) en los 16 tickets (17 con el doble).
- Con errores típicos de OCR inyectados al azar (5 variantes por ticket): 98.9 % (ruido leve), 95.7 % (medio),
  91 % (fuerte). Lo que más se pierde con ruido fuerte: la etiqueta del total muy dañada y números pegados sin
  punto en renglones de 2 cifras.
- **De la foto al ticket** (2026-11-16, arnés opt-in `TicketsRealesExtremoAExtremoTest`, 15 fotos sin la nota a mano,
  sobre las imágenes ya preparadas por el preproceso): Tesseract 65.8 % de los campos y 4 de 15 tickets cuadran;
  PaddleOCR 82.7 % y 13 de 16 cuadran (antes de esta pasada: 60.7 %/0 y 71.3 %/6). Detalle y lo que falta en
  `investigacion-ocr-tickets.md` §12f. Por eso el lector sigue en **Beta**.
