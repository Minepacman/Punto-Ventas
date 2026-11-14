/*
 * datos.js — ÚNICO lugar que hay que editar para llenar los datos del sitio.
 * Un dato vacío o PENDIENTE no se muestra (se oculta) en la página.
 *
 * Después de cambiar algo aquí no hay que tocar nada más: index.html lee estos
 * valores al cargar (atributos data-dato="clave").
 */
window.DATOS_SITIO = {
  // ── Quién hace el programa ──────────────────────────────────────────
  // Nombre del producto: «Caja Libre» (los nombres técnicos —repositorio, carpeta, app— no cambian)
  producto: 'Caja Libre',

  autor: {
    nombre: 'Elias Oziel Sánchez Fernández',   // también es el beneficiario que debe mostrar el banco al donar
    ocupacion: 'Desarrollador de software',
    ciudad: 'PENDIENTE',                       // opcional: "Ciudad de México"
  },

  // ── Contacto (los ve todo el mundo en la sección Contacto y Donaciones) ──
  contacto: {
    correo: 'elias.osiel@hotmail.com',
    whatsapp: '2821416799',        // 10 dígitos de México (el sitio antepone +52)
    telefonoTexto: '282 141 6799'  // cómo mostrar el número
  },

  // ── Donaciones: valores de RESPALDO. La fuente de verdad es el archivo FIRMADO del repositorio
  //    (claves/donacion.pdvd, generado con `./gradlew firmarDatosDonacion` a partir de donacion.properties):
  //    sitio.js lo descarga, verifica la firma Ed25519 del autor cuando el navegador puede y pone esos datos
  //    aquí. Estos solo se muestran si el archivo no se pudo leer.
  donacion: {
    clabe: '722969010047831308',   // 18 dígitos
    banco: 'Mercado Pago',
    beneficiario: 'Elias Oziel Sánchez Fernández', // tal como lo muestra el banco al transferir
    concepto: 'PDV + nombre de tu negocio'   // lo que el donador escribe en el concepto de la transferencia
  },
  // Llave pública Ed25519 del autor (la misma de ClaveDonador.CLAVE_PUBLICA_OFICIAL, kid 0d2a501a)
  llavePublica: 'MCowBQYDK2VwAyEAt8n1iOJpkwZQDteDtvp7WNUvSpUopLbVrZG7PtzEABM=',

  // ── Repositorio (no hace falta cambiarlo) ──────────────────────────
  repo: {
    usuario: 'Minepacman',
    nombre: 'Punto-Ventas',
    rama: 'main'
  },

  // ── Homebrew (macOS) ───────────────────────────────────────────────
  homebrew: 'brew tap minepacman/tap && brew install --cask punto-de-venta'
};
