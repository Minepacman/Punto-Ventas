package me.eliassanchezfernandez.puntodeventa.controller.estadisticas;

import javafx.scene.Node;

/**
 * Una sección (tarjeta) de la pestaña Estadísticas que se registra en
 * {@code EstadisticasController.secciones()} y se coloca sola en su pestaña.
 *
 * Ciclo: {@link #construir(Acciones)} una vez al abrir la pantalla (hilo de
 * JavaFX); después, en cada recarga, {@link #cargar(ContextoEstadisticas)} en
 * un hilo aparte (aquí van las consultas) y {@link #pintar(Object)} en el de
 * JavaFX con lo que regresó cargar. Si cargar lanza, se llama
 * {@link #pintarError(String)}.
 *
 * Para agregar una sección nueva: implementar esta interfaz, dar de alta su
 * texto de ayuda en {@link AyudaEstadisticas} con la misma clave y añadirla a
 * la lista de {@code EstadisticasController.secciones()}. Nada más.
 *
 * @param <D> lo que lee cargar() y recibe pintar()
 */
public interface SeccionEstadisticas<D> {

    /** En qué pestaña se coloca la tarjeta */
    enum Ubicacion { PRODUCTOS, REABASTECIMIENTO, RECOMENDACIONES, CLIMA }

    /** Lo que una sección puede pedirle a la pantalla */
    interface Acciones {
        /** Abre la ayuda ("?") de la clave dada (ver {@link AyudaEstadisticas}) */
        void ayuda(String clave);
        /** Vuelve a cargar SOLO esta sección (por ejemplo, al cambiar un filtro propio) */
        void recargar(SeccionEstadisticas<?> seccion);
    }

    /** Clave única; es también la clave de su texto de ayuda en {@link AyudaEstadisticas} */
    String clave();

    Ubicacion ubicacion();

    /** Construye la tarjeta (se llama una sola vez, en el hilo de JavaFX) */
    Node construir(Acciones acciones);

    /** Consultas de la sección. Corre FUERA del hilo de JavaFX; no tocar nodos aquí. */
    D cargar(ContextoEstadisticas contexto);

    /** Pinta lo que regresó cargar(). Hilo de JavaFX. */
    void pintar(D datos);

    /** cargar() falló: mostrar el mensaje en la tarjeta. Hilo de JavaFX. */
    void pintarError(String mensaje);

    /** true si debe recargarse cuando solo cambia el periodo o la caja elegidos arriba */
    default boolean dependeDelPeriodo() { return false; }
}
