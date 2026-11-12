/*
 * datos.js — ÚNICO lugar que hay que editar para llenar los datos del sitio.
 * Todo lo que diga PENDIENTE se muestra en la página como "por confirmar".
 *
 * Después de cambiar algo aquí no hay que tocar nada más: index.html lee estos
 * valores al cargar (atributos data-dato="clave").
 */
window.DATOS_SITIO = {
  // ── Quién hace el programa ──────────────────────────────────────────
  autor: {
    nombre: 'Elias Oziel Sánchez Fernández',   // REVISAR: ortografía del nombre (en git aparece "Oziel")
    ocupacion: 'PENDIENTE',                    // REVISAR: p. ej. "Desarrollador de software"
    ciudad: 'PENDIENTE',                       // opcional: "Ciudad de México"
  },

  // ── Contacto (los ve todo el mundo en la sección Contacto y Donaciones) ──
  contacto: {
    correo: 'PENDIENTE',       // p. ej. "hola@ejemplo.com"
    whatsapp: 'PENDIENTE',     // 10 dígitos de México, p. ej. "5512345678" (el sitio antepone +52)
    telefonoTexto: 'PENDIENTE' // cómo mostrar el número, p. ej. "55 1234 5678"
  },

  // ── Donaciones: misma cuenta que Punto-Ventas/src/main/resources/donacion.properties ──
  donacion: {
    clabe: 'PENDIENTE',        // 18 dígitos
    banco: 'PENDIENTE',
    beneficiario: 'PENDIENTE',
    concepto: 'PDV + nombre de tu negocio'   // lo que el donador escribe en el concepto de la transferencia
  },

  // ── Repositorio (no hace falta cambiarlo) ──────────────────────────
  repo: {
    usuario: 'Minepacman',
    nombre: 'Punto-Ventas',
    rama: 'main'
  },

  // ── Homebrew (macOS) ───────────────────────────────────────────────
  homebrew: 'brew tap minepacman/tap && brew install --cask punto-de-venta'
};
