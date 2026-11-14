package me.eliassanchezfernandez.puntodeventa.importacion;

import java.util.List;

/**
 * Campo destino de una importación (un atributo de producto o de proveedor).
 * Cada campo conoce sus nombres habituales en otros sistemas ({@link #alias()})
 * para proponer el mapeo de columnas automáticamente.
 */
public interface Campo {

    /** Nombre que ve el usuario en el asistente ("Precio de venta") */
    String etiqueta();

    /** Encabezados con los que suele venir en otros sistemas, ya normalizados con {@link Texto#clave} */
    List<String> alias();

    /** Sin este campo la fila no se puede importar */
    boolean obligatorio();

    /** Nombre del enum (clave estable) */
    String name();

    /** Texto de ayuda breve que se muestra junto al campo en la plantilla y el asistente */
    String ayuda();
}
