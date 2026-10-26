package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Producto;

/**
 * Se lanza cuando una venta intenta llevarse más unidades
 * de las que hay en inventario.
 */
public class StockInsuficienteException extends NegocioException {

    private final Producto producto;
    private final int cantidadSolicitada;

    public StockInsuficienteException(Producto producto, int cantidadSolicitada) {
        super(String.format(
                "Stock insuficiente de \"%s\": se piden %d y solo hay %s en existencia.",
                producto.getDescripcion(),
                cantidadSolicitada,
                formatear(producto.getCantidadActual())));
        this.producto = producto;
        this.cantidadSolicitada = cantidadSolicitada;
    }

    public Producto getProducto()        { return producto; }
    public int getCantidadSolicitada()   { return cantidadSolicitada; }

    private static String formatear(double cantidad) {
        return cantidad == Math.floor(cantidad)
                ? String.valueOf((long) cantidad)
                : String.format("%.2f", cantidad);
    }
}
