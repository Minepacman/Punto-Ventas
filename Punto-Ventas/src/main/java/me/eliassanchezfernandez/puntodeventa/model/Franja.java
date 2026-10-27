package me.eliassanchezfernandez.puntodeventa.model;

import java.time.LocalDateTime;

/**
 * Franja horaria del día para Estadísticas y Recomendaciones.
 * Los límites son semiabiertos [desde, hasta): las 12:00 ya son Mediodía.
 * El orden de las constantes es el orden en que se muestran (Madrugada al
 * final porque normalmente no hay ventas).
 */
public enum Franja {

    MANANA   ("Mañana",    6, 12),
    MEDIODIA ("Mediodía", 12, 15),
    TARDE    ("Tarde",    15, 19),
    NOCHE    ("Noche",    19, 24),
    MADRUGADA("Madrugada", 0,  6);

    private final String etiqueta;
    private final int desde;
    private final int hasta;

    Franja(String etiqueta, int desde, int hasta) {
        this.etiqueta = etiqueta;
        this.desde = desde;
        this.hasta = hasta;
    }

    public String getEtiqueta() { return etiqueta; }
    public int getDesde()       { return desde; }
    public int getHasta()       { return hasta; }

    /** "Mañana 6-12" (para encabezados) */
    public String getEtiquetaConHoras() {
        return etiqueta + " " + desde + "-" + hasta;
    }

    /** "por la mañana", "al mediodía"... (para los motivos de las sugerencias) */
    public String getFrase() {
        return switch (this) {
            case MANANA    -> "por la mañana";
            case MEDIODIA  -> "al mediodía";
            case TARDE     -> "por la tarde";
            case NOCHE     -> "por la noche";
            case MADRUGADA -> "en la madrugada";
        };
    }

    public static Franja de(int hora) {
        if (hora < 0 || hora > 23) throw new IllegalArgumentException("Hora fuera de rango: " + hora);
        for (Franja f : values()) {
            if (hora >= f.desde && hora < f.hasta) return f;
        }
        return MADRUGADA;
    }

    public static Franja de(LocalDateTime fechaHora) {
        return de(fechaHora.getHour());
    }
}
