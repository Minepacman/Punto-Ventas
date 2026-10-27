package me.eliassanchezfernandez.puntodeventa.model;

/**
 * Qué tan maduros son los datos para las estadísticas y recomendaciones
 * (según los días transcurridos desde la primera venta).
 */
public enum NivelDatos {

    SIN_DATOS("Sin datos",
            "Aún no hay ventas registradas. Las estadísticas aparecerán con las primeras ventas."),
    ENTRENAMIENTO("Entrenamiento",
            "Recopilando datos: las recomendaciones aún son preliminares"),
    PARCIAL("Datos parciales",
            "Ya hay historial suficiente para tendencias básicas; las recomendaciones mejoran con más días."),
    SUFICIENTE("Datos suficientes",
            "Hay al menos cuatro semanas de historial: las estadísticas y recomendaciones son confiables.");

    private final String etiqueta;
    private final String mensaje;

    NivelDatos(String etiqueta, String mensaje) {
        this.etiqueta = etiqueta;
        this.mensaje = mensaje;
    }

    public String getEtiqueta() { return etiqueta; }
    public String getMensaje()  { return mensaje; }
}
