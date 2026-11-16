package me.eliassanchezfernandez.puntodeventa.model;

/**
 * "Producto común" (Ctrl/Cmd+P en Ventas): un artículo que no está en el
 * catálogo y se cobra tecleando nombre, precio y cantidad.
 *
 * Para no tocar el esquema de {@link DetalleVenta} (su producto es obligatorio
 * en todas las bases ya instaladas) todos esos renglones apuntan a UN producto
 * del sistema, oculto, identificado por {@link #CODIGO}: sin inventario, sin
 * departamento, con las claves genéricas del SAT. El nombre y el precio que
 * tecleó el cajero viven en el renglón ({@code DetalleVenta.descripcion} y
 * {@code precioVenta}); el costo se estima en el momento con
 * {@link #MARGEN_GANANCIA} para que el corte y las estadísticas de ganancia lo
 * incluyan. Java puro (sin Spring): lo usan modelo, servicios y pantallas.
 */
public final class ProductoComun {

    /** Código de barras reservado del producto del sistema (ningún lector produce esto) */
    public static final String CODIGO = "PRODUCTO-COMUN";

    /** Nombre que propone la ventana y que se usa si el cajero deja el campo vacío */
    public static final String NOMBRE_POR_DEFECTO = "Producto común";

    /** Ganancia estimada sobre lo cobrado: el costo se registra como el 80 % del importe */
    public static final double MARGEN_GANANCIA = 0.20;

    /** Largo máximo del nombre tecleado (columna detalle_ventas.descripcion) */
    public static final int MAX_LARGO_NOMBRE = 150;

    private ProductoComun() { }

    public static boolean es(Producto p) {
        return p != null && CODIGO.equals(p.getCodigoBarras());
    }

    public static boolean es(DetalleVenta d) {
        return d != null && es(d.getProducto());
    }

    /** Nombre limpio: sin espacios sobrantes, el de por defecto si viene vacío, recortado al máximo */
    public static String nombreNormalizado(String nombre) {
        String n = nombre == null ? "" : nombre.trim().replaceAll("\\s+", " ");
        if (n.isEmpty()) return NOMBRE_POR_DEFECTO;
        return n.length() > MAX_LARGO_NOMBRE ? n.substring(0, MAX_LARGO_NOMBRE) : n;
    }

    /**
     * Costo unitario estimado del renglón: (1 − margen) × lo que se cobró por
     * unidad (ya con descuento), de modo que la ganancia registrada sea exactamente
     * el {@link #MARGEN_GANANCIA} del importe del renglón.
     */
    public static double costoUnitarioEstimado(double importe, double cantidad) {
        if (cantidad <= 0 || !Double.isFinite(importe) || importe <= 0) return 0;
        return (1.0 - MARGEN_GANANCIA) * importe / cantidad;
    }
}
