package me.eliassanchezfernandez.puntodeventa.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Permisos configurables por usuario (USR-16).
 *
 * Cada rol trae un conjunto por defecto (porDefecto) y el administrador
 * puede conceder o revocar permisos individuales a cada usuario.
 * El ADMIN siempre tiene todos.
 */
public enum Permiso {

    EDITAR_PRODUCTOS   ("Editar productos y departamentos"),
    CAMBIAR_PRECIOS    ("Cambiar precios"),
    CREAR_PROMOCIONES  ("Crear y editar promociones"),
    AJUSTAR_INVENTARIO ("Entradas y ajustes de inventario"),
    VER_HISTORIAL      ("Ver historial de ventas"),
    VER_GANANCIAS      ("Ver ganancias"),
    HACER_CORTE        ("Hacer el corte de caja"),
    APLICAR_MAYOREO    ("Aplicar precio de mayoreo"),
    DESCUENTO_MANUAL   ("Hacer descuentos manuales"),
    VENDER_SIN_STOCK   ("Vender sin existencia"),
    CANCELAR_VENTA     ("Cancelar ventas cobradas");

    private final String descripcion;

    Permiso(String descripcion) {
        this.descripcion = descripcion;
    }

    public String getDescripcion() {
        return descripcion;
    }

    /**
     * Permisos que trae cada rol antes de los ajustes por usuario.
     *
     * GERENTE: el dueño solo confirmó "agregar y editar productos" (USR-17);
     * CAMBIAR_PRECIOS se incluye como parte de editar productos (pendiente de
     * confirmar). Todo lo demás se lo concede el administrador por usuario.
     * CAJERO: ninguno (todo lo de la lista es de administrador, USR-13).
     */
    public static Set<Permiso> porDefecto(Cajero.Rol rol) {
        if (rol == null) return Collections.emptySet();
        return switch (rol) {
            case ADMIN   -> EnumSet.allOf(Permiso.class);
            case GERENTE -> EnumSet.of(EDITAR_PRODUCTOS, CAMBIAR_PRECIOS);
            case CAJERO  -> EnumSet.noneOf(Permiso.class);
        };
    }
}
