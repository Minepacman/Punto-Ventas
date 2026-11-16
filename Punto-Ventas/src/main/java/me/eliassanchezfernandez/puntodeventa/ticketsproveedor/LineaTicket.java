package me.eliassanchezfernandez.puntodeventa.ticketsproveedor;

import lombok.Getter;
import lombok.Setter;

/**
 * Un artículo leído del ticket del proveedor, tal como viene impreso (en las unidades del ticket:
 * pieza, caja, paquete…). La pantalla de revisión lo deja corregir antes de registrar la entrada.
 */
@Getter
@Setter
public class LineaTicket {

    /** Semáforo que ve el usuario */
    public enum Estado {
        /** Verde: se leyó y la cuenta cantidad × precio = importe sale */
        RECONOCIDO,
        /** Amarillo: se leyó, pero algo se corrigió o no cuadra del todo: revisar */
        REVISAR,
        /** Rojo: no se pudo leer (faltan números o no salen las cuentas) */
        ILEGIBLE
    }

    /** Índice del primer renglón del texto donde empieza el artículo */
    private int renglon;
    /** Posición vertical en la foto (la da el OCR; -1 si no se sabe) */
    private int y = -1;
    /** Texto original de los renglones del artículo, para mostrarlo tal cual */
    private String textoOriginal;
    /** Clave del producto en el sistema del proveedor (null si el ticket no la trae) */
    private String codigoProveedor;
    private String descripcion;
    /** Cantidad en las unidades del ticket (0.5 paquete, 6 piezas, 0.05 paquetes de cigarros…) */
    private double cantidad;
    /** Unidad impresa en el renglón (PZA, ZA, CJ…), si la trae */
    private String unidad;
    /** Pista de piezas por unidad sacada de la descripción ("8/2L" → 8, "24PZS" → 24); se confirma con el usuario */
    private Integer empaque;
    private Double precioUnitario;
    /** Descuento impreso en el renglón (null si el ticket no tiene esa columna) */
    private Double descuento;
    /** Importe del renglón ya con su descuento: lo que realmente se paga por él */
    private double importe;
    /** El descuento del renglón se aplicó antes del IVA (algunos sistemas de reparto lo hacen así) */
    private boolean descuentoAntesDeIva;
    private Estado estado = Estado.RECONOCIDO;
    /** Por qué está en amarillo o rojo (texto para el usuario) */
    private String motivo;

    public LineaTicket copia() {
        LineaTicket c = new LineaTicket();
        c.renglon = renglon; c.y = y; c.textoOriginal = textoOriginal; c.codigoProveedor = codigoProveedor;
        c.descripcion = descripcion; c.cantidad = cantidad; c.unidad = unidad; c.empaque = empaque;
        c.precioUnitario = precioUnitario; c.descuento = descuento; c.importe = importe;
        c.descuentoAntesDeIva = descuentoAntesDeIva; c.estado = estado; c.motivo = motivo;
        return c;
    }

    @Override
    public String toString() {
        return String.format("%s | %s | %s x %s - %s = %s [%s]", codigoProveedor, descripcion, cantidad,
                precioUnitario, descuento, importe, estado);
    }
}
