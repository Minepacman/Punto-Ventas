package me.eliassanchezfernandez.puntodeventa.model;

/**
 * IVA de un producto. Los precios de venta YA incluyen el impuesto; esto solo
 * decide cómo se desglosa en el ticket, el corte y la factura (CFDI 4.0).
 *
 * En una tienda de abarrotes mexicana la mayoría de los básicos (tortilla, leche,
 * huevo, frutas y verduras, carne, pan, frijol, arroz...) van a tasa 0 % (LIVA
 * art. 2-A); refrescos, botanas, cerveza, cigarros, jabón y casi todo lo demás al
 * 16 % (art. 1). Muy pocos artículos de tienda son exentos (art. 9: libros y
 * revistas, por ejemplo). Tasa 0 % y exento se cobran igual al cliente, pero en la
 * factura se informan distinto (TasaOCuota 0.000000 contra TipoFactor Exento).
 */
public enum TipoIva {

    /** Toma la tasa general configurada en Configuración → Negocio e IVA (normalmente 16 %) */
    TIENDA("Usar el de la tienda", Double.NaN, false),
    TASA_16("IVA 16 %", 0.16, false),
    TASA_0("IVA 0 %", 0.0, false),
    EXENTO("Exento de IVA", 0.0, true);

    private final String etiqueta;
    private final double tasa;
    private final boolean exento;

    TipoIva(String etiqueta, double tasa, boolean exento) {
        this.etiqueta = etiqueta;
        this.tasa = tasa;
        this.exento = exento;
    }

    public String getEtiqueta() { return etiqueta; }

    public boolean esExento() { return exento; }

    /** Tasa como fracción; para {@link #TIENDA} la que se reciba como tasa de la tienda */
    public double tasa(double tasaTienda) {
        return this == TIENDA ? tasaTienda : tasa;
    }

    /** El mismo valor pero sin null: un producto sin IVA capturado usa el de la tienda */
    public static TipoIva oTienda(TipoIva t) {
        return t == null ? TIENDA : t;
    }

    @Override
    public String toString() {
        return etiqueta;
    }
}
