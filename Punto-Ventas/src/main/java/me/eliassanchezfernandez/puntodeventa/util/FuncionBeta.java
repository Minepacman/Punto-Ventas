package me.eliassanchezfernandez.puntodeventa.util;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Funciones que ya funcionan pero aún no se han probado lo suficiente en una tienda real: llevan
 * la etiqueta «Beta» ({@link EtiquetaBeta} en escritorio, {@code etiquetaBeta()} en la página móvil,
 * que recibe la lista en {@code /api/sesion → beta}).
 *
 * <p><b>Quitar la etiqueta = una sola edición:</b> sacar la constante de {@link #EN_BETA}. Todas las
 * marcas (pantallas, botones, diálogos y la página móvil) desaparecen solas. {@code FuncionesBetaTest}
 * recuerda entonces quitarla también de {@code docs/funciones-beta.md}.
 */
public enum FuncionBeta {

    LECTOR_TICKETS("Leer ticket de proveedor (OCR)",
            "Motor nuevo; falta probarlo con fotos reales de los tickets de los proveedores."),
    AVISOS_TELEFONO("Avisos en el teléfono",
            "Notificaciones push recientes; probadas en pocos teléfonos (iPhone y Android) y redes reales."),
    FACTURACION_CFDI("Facturación electrónica (CFDI 4.0)",
            "Nunca se ha probado contra el sandbox de Facturama ni con un timbrado real."),
    VARIAS_CAJAS("Varias cajas (servidor PostgreSQL)",
            "Probada con PostgreSQL embebido; falta probarla en la red de una tienda con varias cajas."),
    BASCULA("Báscula",
            "Sin probar con una báscula física conectada."),
    TERMINAL_BANCARIA("Terminal bancaria",
            "Sin probar con terminales reales en la caja."),
    ABRIR_AL_ENCENDER("Abrir al encender",
            "Sin probar en Windows (solo en macOS).");

    /** LA lista: lo que hoy lleva la etiqueta. Quitar de aquí una función quita su etiqueta en todos lados. */
    private static final Set<FuncionBeta> EN_BETA = Collections.unmodifiableSet(EnumSet.of(
            LECTOR_TICKETS,
            AVISOS_TELEFONO,
            FACTURACION_CFDI,
            VARIAS_CAJAS,
            BASCULA,
            TERMINAL_BANCARIA,
            ABRIR_AL_ENCENDER));

    /** Texto de la pastilla y su ayuda (escritorio y página móvil) */
    public static final String TEXTO = "Beta";
    public static final String AYUDA = "En etapa beta: funciona, pero aún se está probando. Si algo falla, avísanos.";

    private final String nombre;
    private final String motivo;

    FuncionBeta(String nombre, String motivo) {
        this.nombre = nombre;
        this.motivo = motivo;
    }

    public String nombre() { return nombre; }
    public String motivo() { return motivo; }

    /** ¿Esta función lleva hoy la etiqueta «Beta»? */
    public boolean enBeta() { return EN_BETA.contains(this); }

    /** Título de ventana (barra del sistema): «Báscula (Beta)» mientras la función siga en beta */
    public static String tituloVentana(String titulo, FuncionBeta f) {
        return f != null && f.enBeta() ? titulo + " (" + TEXTO + ")" : titulo;
    }

    /** Nombres (constantes) en beta, para la página móvil */
    public static List<String> nombresEnBeta() {
        return EN_BETA.stream().map(Enum::name).toList();
    }

    /** Busca por nombre de constante; null si no existe (p. ej. una marca vieja en un FXML) */
    public static FuncionBeta buscar(String nombre) {
        if (nombre == null || nombre.isBlank()) return null;
        for (FuncionBeta f : values()) if (f.name().equals(nombre.trim())) return f;
        return null;
    }
}
