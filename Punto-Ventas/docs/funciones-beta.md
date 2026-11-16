# Funciones en etapa beta

Lo que ya funciona pero aún no se ha probado lo suficiente en una tienda real lleva la pastilla
**Beta** (tooltip: «En etapa beta: funciona, pero aún se está probando. Si algo falla, avísanos.»).

**La lista vive en un solo lugar:** `EN_BETA` en `src/main/java/.../util/FuncionBeta.java`.
Para quitar la etiqueta de una función basta sacarla de `EN_BETA`: desaparece de todas las pantallas,
botones, diálogos y de la página móvil (que la recibe en `/api/sesion → beta`). Después quita su
renglón de abajo (`FuncionesBetaTest` lo revisa).

Cómo se marca: `BotonOpcion beta="CONSTANTE"`, `<EtiquetaBeta funcion="CONSTANTE"/>` en FXML,
`EtiquetaBeta.marcar(control | diálogo | pestaña | menú, FuncionBeta.X)`, `EtiquetaBeta.tituloVentana(...)`
y `etiquetaBeta('CONSTANTE')` en `movil/index.html`.

| Constante | Función | Por qué sigue en beta | Dónde se ve |
|---|---|---|---|
| `LECTOR_TICKETS` | Leer ticket de proveedor (OCR) | Motor nuevo; falta probarlo con fotos reales de los tickets de los proveedores. | Inventario → «Leer ticket» (botón, ventana y su título); Configuración → Lector de tickets |
| `AVISOS_TELEFONO` | Avisos en el teléfono | Notificaciones push recientes; probadas en pocos teléfonos y redes reales. | Página móvil → Ajustes → «Avisos en este teléfono»; Configuración → Acceso móvil → «Activar avisos en un teléfono» |
| `FACTURACION_CFDI` | Facturación electrónica (CFDI 4.0) | Nunca se ha probado contra el sandbox de Facturama ni con un timbrado real. | Configuración → Facturación (botón y ventana); Ventas → «Facturar»; Historial → «Facturar» y ventana Facturas; diálogos Facturar venta / Factura global |
| `VARIAS_CAJAS` | Varias cajas (servidor PostgreSQL) | Probada con PostgreSQL embebido; falta en la red de una tienda con varias cajas. | Primer arranque «Varias cajas»; Configuración → Base de datos → «Cajas y servidor» y la opción «Varias cajas» |
| `BASCULA` | Báscula | Sin probar con una báscula física conectada. | Configuración → Báscula (botón y ventana) |
| `TERMINAL_BANCARIA` | Terminal bancaria | Sin probar con terminales reales en la caja. | Configuración → Terminal bancaria (botón y diálogo) |
| `ABRIR_AL_ENCENDER` | Abrir al encender | Sin probar en Windows (solo en macOS). | Configuración → Abrir al encender (botón y diálogo) |
