package me.eliassanchezfernandez.puntodeventa.util;

import me.eliassanchezfernandez.puntodeventa.model.Venta;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cuentas del cobro, sin interfaz: a partir del total y de lo capturado en
 * cada forma de pago dice cuánto se pagó, cuánto falta o cuánto cambio hay, y
 * si el pago es válido con las MISMAS reglas que {@code VentaService.cobrar}:
 * la suma cubre el total y lo que no es efectivo no puede exceder el total
 * (el cambio solo sale del efectivo).
 *
 * Es lo que la pantalla de Ventas y la ventana de cobro muestran en vivo; el
 * servicio vuelve a validar todo al guardar.
 */
public final class CalculoCobro {

    /** Tolerancia de centavos por residuos de punto flotante */
    private static final double TOLERANCIA = 0.001;

    /**
     * Un monto de pago a partir de aquí (y mayor que el total) no lo da un
     * cliente de abarrotes: casi siempre es un código de barras que el lector
     * escribió en el campo de pago porque el cursor estaba ahí (un EAN-13 son
     * billones de pesos). Se rechaza en lugar de cobrar con ese "cambio".
     */
    public static final double MONTO_MAXIMO = 1_000_000;

    /** Denominaciones de billetes para los botones rápidos (pesos mexicanos) */
    public static final List<Integer> BILLETES = List.of(20, 50, 100, 200, 500, 1000);

    private CalculoCobro() { }

    /** Resultado de las cuentas; {@code error} es null cuando se puede cobrar */
    public record Resultado(double total, double pagado, double efectivo, double noEfectivo,
                            double faltante, double cambio, String error) {
        public boolean esValido() { return error == null; }
        /** ¿Hay algún monto capturado? (para no mostrar "Faltan $X" en un ticket recién armado) */
        public boolean hayPago() { return pagado > 0; }
    }

    /**
     * @param total  total del ticket (ya redondeado a centavos)
     * @param montos monto capturado por forma de pago (solo los > 0; puede estar vacío)
     */
    public static Resultado calcular(double total, Map<Venta.FormaPago, Double> montos) {
        double totalR = redondear(total);
        double efectivo = 0, noEfectivo = 0;
        for (Map.Entry<Venta.FormaPago, Double> e : montos.entrySet()) {
            Double m = e.getValue();
            if (m == null || m <= 0) continue;
            if (!Double.isFinite(m)) {
                return new Resultado(totalR, 0, 0, 0, totalR, 0, "Escribe montos válidos (ej: 200).");
            }
            if (m >= MONTO_MAXIMO && m > totalR) {
                return new Resultado(totalR, 0, 0, 0, totalR, 0, String.format(
                        "El monto %s es demasiado grande: ¿se escaneó un código en el campo de pago? Corrígelo.",
                        Moneda.formato(m)));
            }
            if (e.getKey() == Venta.FormaPago.EFECTIVO) efectivo += m; else noEfectivo += m;
        }
        efectivo = redondear(efectivo);
        noEfectivo = redondear(noEfectivo);
        double pagado = redondear(efectivo + noEfectivo);
        double faltante = Math.max(0, redondear(totalR - pagado));
        double cambio = Math.max(0, redondear(pagado - totalR));

        String error = null;
        if (totalR <= 0) {
            error = "El ticket está vacío.";
        } else if (pagado <= 0) {
            error = "Indica cómo pagó el cliente.";
        } else if (noEfectivo > totalR + TOLERANCIA) {
            error = String.format("El pago con tarjeta u otro medio distinto de efectivo (%s) no puede ser mayor que el total (%s).",
                    Moneda.formato(noEfectivo), Moneda.formato(totalR));
        } else if (pagado + TOLERANCIA < totalR) {
            error = String.format("Faltan %s: el cliente pagó %s y el total es %s.",
                    Moneda.formato(faltante), Moneda.formato(pagado), Moneda.formato(totalR));
        }
        return new Resultado(totalR, pagado, efectivo, noEfectivo, faltante, cambio, error);
    }

    /**
     * Lee los montos de varios campos de texto. Devuelve solo los mayores a 0,
     * o {@code null} si algún texto no es un número válido.
     */
    public static Map<Venta.FormaPago, Double> leerMontos(Map<Venta.FormaPago, String> textos) {
        Map<Venta.FormaPago, Double> montos = new LinkedHashMap<>();
        for (Map.Entry<Venta.FormaPago, String> e : textos.entrySet()) {
            double monto = parsearMonto(e.getValue(), 0);
            if (Double.isNaN(monto)) return null;
            if (monto > 0) montos.put(e.getKey(), monto);
        }
        return montos;
    }

    /**
     * Número de un campo de dinero; vacío = porDefecto; inválido/infinito/negativo = NaN.
     * Admite "$", "%" y la coma a la mexicana (ver {@link #normalizarNumero}), igual
     * en Login, Corte, Inventario, Productos, Órdenes y Promociones.
     */
    public static double parsearMonto(String txt, double porDefecto) {
        if (txt == null) return porDefecto;
        String t = normalizarNumero(txt);
        if (t.isEmpty()) return porDefecto;
        double v = parsearNumero(t);
        return Double.isNaN(v) || v < 0 ? Double.NaN : v;
    }

    /**
     * Número tal como se teclea en México; NaN si no es un número (vacío también es NaN).
     * Acepta negativos: quien lo llama decide si los permite.
     */
    public static double parsearNumero(String txt) {
        String t = normalizarNumero(txt);
        if (t.isEmpty()) return Double.NaN;
        try {
            double v = Double.parseDouble(t);
            return Double.isFinite(v) ? v : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * Regla única para lo que se teclea en campos de dinero y cantidades:
     * se quitan "$", "%" y espacios; si hay punto, las comas son de miles
     * ("1,500.50" = 1500.50); si solo hay UNA coma seguida de 1, 2 o más de 3
     * dígitos es coma decimal ("12,50" = 12.50, "2,5" = 2.5); una coma seguida
     * de exactamente 3 dígitos, o varias comas, son separadores de miles
     * ("1,500" = 1500, "1,250,000" = 1250000). Devuelve el texto listo para
     * {@code Double.parseDouble} (o vacío).
     */
    public static String normalizarNumero(String txt) {
        if (txt == null) return "";
        String t = txt.trim().replace("$", "").replace("%", "").replace(" ", "").replace(" ", "");
        if (t.isEmpty()) return "";
        int comas = t.length() - t.replace(",", "").length();
        if (t.contains(".") || comas == 0) return t.replace(",", "");
        if (comas > 1) return t.replace(",", "");
        String despues = t.substring(t.indexOf(',') + 1);
        boolean soloDigitos = !despues.isEmpty() && despues.chars().allMatch(Character::isDigit);
        if (soloDigitos && despues.length() == 3) return t.replace(",", "");   // "1,500" = miles
        return t.replace(',', '.');
    }

    /** Texto para un campo de dinero ("150.00") */
    public static String formatoCampo(double monto) {
        return String.format(java.util.Locale.US, "%.2f", redondear(monto));
    }

    /**
     * Billete que "sigue" al total para el botón de pago rápido: con un total de
     * $137.50 sugiere 140, 150, 200, 500... Devuelve las sugerencias en orden,
     * sin repetir y siempre mayores o iguales al total.
     */
    public static List<Double> sugerenciasDeEfectivo(double total) {
        java.util.LinkedHashSet<Double> s = new java.util.LinkedHashSet<>();
        double t = redondear(total);
        if (t <= 0) return List.of();
        s.add(t);                                       // pago exacto
        double decena = Math.ceil(t / 10.0) * 10;       // siguiente decena
        if (decena > t) s.add(decena);
        double cincuenta = Math.ceil(t / 50.0) * 50;    // siguiente múltiplo de 50
        if (cincuenta > t) s.add(cincuenta);
        for (int b : BILLETES) {
            if (b >= t) s.add((double) b);
        }
        return s.stream().sorted().limit(5).toList();   // de menor a mayor: F1 exacto, F2 el siguiente…
    }

    static double redondear(double monto) {
        return Math.round(monto * 100.0) / 100.0;
    }
}
