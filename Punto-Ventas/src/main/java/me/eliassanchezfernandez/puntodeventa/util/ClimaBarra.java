package me.eliassanchezfernandez.puntodeventa.util;

import me.eliassanchezfernandez.puntodeventa.model.ClimaRegistro;
import me.eliassanchezfernandez.puntodeventa.service.ClimaService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Textos e icono del clima que se muestra junto al reloj de la ventana principal
 * ("24 °C · Soleado"). Funciones puras sobre el último {@link ClimaRegistro}
 * guardado por {@link ClimaService}: aquí no hay red ni base de datos.
 *
 * <p>La condición es más fina que la categoría de Estadísticas (que agrupa para
 * las sugerencias): distingue soleado / poco nublado / nublado y llovizna /
 * lluvia / chubascos, siempre con una o dos palabras para que quepa a 1024 px.</p>
 */
public final class ClimaBarra {

    /** Un registro más viejo que esto se muestra atenuado y con "(hace X h)" */
    public static final int VIGENCIA_HORAS = ClimaService.VIGENCIA_HORAS;

    private static final DateTimeFormatter FMT_HORA = DateTimeFormatter.ofPattern("HH:mm");

    private ClimaBarra() { }

    /** Lo que la ventana principal pinta: texto, nombre del icono (util/Iconos), tooltip y si está viejo */
    public record Indicador(String texto, String icono, String tooltip, boolean desactualizado) { }

    /** Código WMO → condición corta en español */
    public static String condicion(int codigoWmo) {
        if (codigoWmo == 0) return "Soleado";
        if (codigoWmo == 1) return "Despejado";
        if (codigoWmo == 2) return "Poco nublado";
        if (codigoWmo == 3) return "Nublado";
        if (codigoWmo == 45 || codigoWmo == 48) return "Niebla";
        if (codigoWmo >= 51 && codigoWmo <= 57) return "Llovizna";
        if (codigoWmo >= 61 && codigoWmo <= 67) return "Lluvia";
        if (codigoWmo >= 80 && codigoWmo <= 82) return "Chubascos";
        if (codigoWmo >= 95 && codigoWmo <= 99) return "Tormenta";
        // Nieve (71–77, 85–86) y cualquier código desconocido: la categoría general
        return ClimaService.categoriaDe(codigoWmo);
    }

    /** Código WMO → icono Lucide: sol, sol con nube (climaSol), nube, lluvia, tormenta, niebla o nieve */
    public static String icono(int codigoWmo) {
        if (codigoWmo == 0 || codigoWmo == 1) return "sol";
        if (codigoWmo == 2) return "climaSol";
        return ClimaService.iconoDe(ClimaService.categoriaDe(codigoWmo));
    }

    /** "24 °C · Soleado" (temperatura redondeada; -0 se muestra como 0) */
    public static String texto(double temperatura, int codigoWmo) {
        long grados = Math.round(temperatura);
        return grados + " °C · " + condicion(codigoWmo);
    }

    /** ¿El registro tiene más de VIGENCIA_HORAS respecto a "ahora"? */
    public static boolean desactualizado(LocalDateTime registro, LocalDateTime ahora) {
        return registro.isBefore(ahora.minusHours(VIGENCIA_HORAS));
    }

    /** "(hace 5 h)" o "(hace 2 días)" según la antigüedad del registro */
    public static String antiguedad(LocalDateTime registro, LocalDateTime ahora) {
        long horas = Math.max(0, Duration.between(registro, ahora).toHours());
        if (horas < 48) return "(hace " + horas + " h)";
        long dias = horas / 24;
        return "(hace " + dias + " días)";
    }

    /**
     * "Clima de Xalapa, actualizado a las 14:00" y, si el registro ya está
     * viejo, " (hace 5 h)". Sin ciudad: "Clima del negocio, …".
     */
    public static String tooltip(String ciudad, LocalDateTime registro, LocalDateTime ahora) {
        String lugar = ciudad == null || ciudad.isBlank() ? "Clima del negocio" : "Clima de " + ciudad.trim();
        String base = lugar + ", actualizado a las " + registro.format(FMT_HORA);
        return desactualizado(registro, ahora) ? base + " " + antiguedad(registro, ahora) : base;
    }

    /** Arma el indicador completo a partir del último registro guardado */
    public static Indicador de(ClimaRegistro r, String ciudad, LocalDateTime ahora) {
        return new Indicador(
                texto(r.getTemperatura(), r.getCodigo()),
                icono(r.getCodigo()),
                tooltip(ciudad, r.getFechaHora(), ahora),
                desactualizado(r.getFechaHora(), ahora));
    }
}
