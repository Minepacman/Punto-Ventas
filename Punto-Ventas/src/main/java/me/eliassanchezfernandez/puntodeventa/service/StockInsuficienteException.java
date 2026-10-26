package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Producto;

/**
 * Se lanza cuando una venta intenta llevarse más unidades
 * de las que hay en inventario (y el usuario no puede vender sin existencia).
 */
public class StockInsuficienteException extends NegocioException {

    private final Producto producto;
    private final double cantidadSolicitada;

    public StockInsuficienteException(Producto producto, double cantidadSolicitada) {
        super(String.format(
                "Stock insuficiente de \"%s\": se piden %s y solo hay %s en existencia.",
                producto.getDescripcion(),
                formatear(cantidadSolicitada),
                formatear(producto.getCantidadActual())));
        this.producto = producto;
        this.cantidadSolicitada = cantidadSolicitada;
    }

    public Producto getProducto()           { return producto; }
    public double getCantidadSolicitada()   { return cantidadSolicitada; }

    private static String formatear(double cantidad) {
        return cantidad == Math.floor(cantidad)
                ? String.valueOf((long) cantidad)
                : String.format("%.3f", cantidad).replaceAll("0+$", "");
    }
}
