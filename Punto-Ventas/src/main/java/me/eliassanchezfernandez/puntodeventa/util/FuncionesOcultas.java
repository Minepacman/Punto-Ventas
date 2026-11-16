package me.eliassanchezfernandez.puntodeventa.util;

import me.eliassanchezfernandez.puntodeventa.model.Permiso;

/**
 * Funciones terminadas (con su servicio, tablas y pruebas) que por ahora NO se muestran.
 *
 * <p><b>Volver a mostrar una = cambiar su constante a {@code true}.</b> Todo lo visible lee de
 * aquí: no hay que tocar nada más.
 */
public final class FuncionesOcultas {

    private FuncionesOcultas() { }

    /**
     * Clientes con cuenta (fiado) y abonos. Apagado oculta:
     * <ul>
     *   <li>la pestaña Clientes y su atajo ({@code Atajo.IR_CLIENTES}, también en la lista F10);</li>
     *   <li>en la ventana de cobro, el campo «A cuenta (fiado)», el botón «Cliente… F6», «Todo a cuenta F7» y la
     *       línea del cliente;</li>
     *   <li>el paso «Clientes (fiado)» del tutorial general;</li>
     *   <li>los permisos {@link Permiso#VENDER_A_CREDITO} y {@link Permiso#AJUSTAR_CREDITO} en el editor de
     *       usuarios (conservan su valor guardado);</li>
     *   <li>en la página móvil, la sección «Fiado — Quién me debe»; {@code /api/clientes/saldos} responde 404.</li>
     * </ul>
     * El corte y las devoluciones solo mencionan el fiado cuando hubo ventas a cuenta (con esto apagado no
     * se crean). {@code CuentaClienteService} sigue funcionando y probado.
     */
    public static final boolean FIADO_HABILITADO = false;

    /** ¿Se muestra este permiso en el editor de usuarios? */
    public static boolean permisoVisible(Permiso p) {
        if (p == Permiso.VENDER_A_CREDITO || p == Permiso.AJUSTAR_CREDITO) return FIADO_HABILITADO;
        return true;
    }
}
