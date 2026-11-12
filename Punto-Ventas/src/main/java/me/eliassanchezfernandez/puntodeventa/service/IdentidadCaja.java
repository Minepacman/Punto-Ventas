package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.config.PerfilBaseDatos;
import me.eliassanchezfernandez.puntodeventa.util.ConexionLocal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Qué caja es esta instalación. El número lo fija el Launcher (desde
 * conexion.properties) en la propiedad app.caja.numero antes de arrancar
 * Spring; cada venta, corte, fondo, ticket pendiente y movimiento de
 * inventario lo lleva. Una tienda de una sola caja es siempre la caja 1.
 *
 * {@link #esMultiCaja()} es true en modo PostgreSQL (varias cajas contra un
 * servidor): ahí los tickets y el corte muestran la caja.
 */
@Component
public class IdentidadCaja {

    public static final int CAJA_POR_DEFECTO = 1;

    @Value("${" + ConexionLocal.PROP_CAJA_NUMERO + ":1}")
    private int numero = CAJA_POR_DEFECTO;

    @Value("${" + ConexionLocal.PROP_CAJA_NOMBRE + ":}")
    private String nombre = "";

    @Value("${" + ConexionLocal.PROP_MODO + ":SQLITE}")
    private String modo = "SQLITE";

    public int numero() {
        return numero <= 0 ? CAJA_POR_DEFECTO : numero;
    }

    /** "Caja 1", o el nombre que le dio el administrador */
    public String nombre() {
        return nombre == null || nombre.isBlank() ? etiqueta(numero()) : nombre;
    }

    public boolean esMultiCaja() {
        return PerfilBaseDatos.POSTGRESQL.name().equalsIgnoreCase(modo);
    }

    /** Número de caja de una fila, tratando las filas anteriores a esta versión (null) como caja 1 */
    public static int numeroONumeroPorDefecto(Integer caja) {
        return caja == null || caja <= 0 ? CAJA_POR_DEFECTO : caja;
    }

    public static String etiqueta(Integer caja) {
        return "Caja " + numeroONumeroPorDefecto(caja);
    }

    /** Solo para pruebas: simula que este proceso es otra caja */
    void fijarParaPruebas(int numero, String nombre, boolean multiCaja) {
        this.numero = numero;
        this.nombre = nombre;
        this.modo = multiCaja ? PerfilBaseDatos.POSTGRESQL.name() : PerfilBaseDatos.SQLITE.name();
    }
}
