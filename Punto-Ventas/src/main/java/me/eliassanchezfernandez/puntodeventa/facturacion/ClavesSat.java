package me.eliassanchezfernandez.puntodeventa.facturacion;

import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.Producto;

/** Qué claves del SAT lleva cada producto al facturar: la suya, o la del departamento / unidad, o la genérica. */
public final class ClavesSat {

    private ClavesSat() { }

    /** Clave de producto/servicio: la del producto, o la del departamento, o la genérica de abarrotes */
    public static String claveProdServDe(Producto p) {
        if (p.getClaveProdServ() != null && !p.getClaveProdServ().isBlank()) return p.getClaveProdServ();
        Departamento d = p.getDepartamento();
        if (d != null && d.getClaveProdServ() != null && !d.getClaveProdServ().isBlank()) return d.getClaveProdServ();
        return CatalogoSat.PROD_SERV_ABARROTES;
    }

    /** Clave de unidad: la del producto, o la de su unidad de medida (o deducida del nombre), o Pieza / Kilogramo */
    public static String claveUnidadDe(Producto p) {
        if (p.getClaveUnidad() != null && !p.getClaveUnidad().isBlank()) return p.getClaveUnidad();
        if (p.getUnidad() != null) return CatalogoSat.claveUnidadPara(p.getUnidad());
        return p.getTipoVenta() == Producto.TipoVenta.GRANEL ? "KGM" : CatalogoSat.UNIDAD_PIEZA;
    }

    /** Objeto de impuesto: el del producto o "02 Sí objeto" */
    public static String objetoImpDe(Producto p) {
        return p.getObjetoImp() != null && !p.getObjetoImp().isBlank() ? p.getObjetoImp() : CatalogoSat.OBJETO_IMP_SI;
    }

    /** Nombre de la unidad para el campo "Unidad" del concepto */
    public static String nombreUnidadDe(Producto p) {
        if (p.getUnidad() != null && p.getUnidad().getNombre() != null) return p.getUnidad().getNombre();
        return CatalogoSat.unidad(claveUnidadDe(p)).map(CatalogoSat.Entrada::descripcion).orElse("Pieza");
    }
}
