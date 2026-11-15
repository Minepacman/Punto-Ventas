package me.eliassanchezfernandez.puntodeventa.facturacion;

import me.eliassanchezfernandez.puntodeventa.facturacion.CfdiDocumento.Concepto;
import me.eliassanchezfernandez.puntodeventa.facturacion.CfdiDocumento.Impuesto;
import me.eliassanchezfernandez.puntodeventa.facturacion.CfdiDocumento.InformacionGlobal;
import me.eliassanchezfernandez.puntodeventa.facturacion.CfdiDocumento.Receptor;
import me.eliassanchezfernandez.puntodeventa.model.ClienteFiscal;
import me.eliassanchezfernandez.puntodeventa.model.ConfiguracionFiscal;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Venta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Arma el {@link CfdiDocumento} a partir de una venta (o de varias, para la
 * factura global) con BigDecimal y redondeo HALF_UP.
 *
 * Los precios del punto de venta YA INCLUYEN IVA. Por cada renglón, con SU tasa
 * (la guardada en el renglón al cobrar: 16 %, 0 % o exento; en ventas anteriores
 * al IVA por producto, la tasa de la venta):
 *  - neto  = round2(precio × cantidad − descuento)   → lo que pagó el cliente;
 *  - base  = round2(neto / (1 + tasa))              → base gravable;
 *  - iva   = neto − base                            → así base + iva = neto exacto;
 *  - importe (sin descuento) = round2(precio × cantidad / (1 + tasa)), y
 *    descuento = importe − base (≥ 0): el descuento, el mayoreo y las promociones
 *    del ticket quedan como atributo Descuento del concepto;
 *  - valorUnitario = importe / cantidad a 6 decimales.
 * El IVA así obtenido queda dentro del margen que exige el SAT (±0.01 respecto a
 * base × tasa a 2 decimales) y, sobre todo, la suma de los conceptos coincide
 * con el total del ticket. Si por renglones a granel la suma de netos
 * redondeados difiere del total de la venta por centavos, la diferencia se
 * ajusta en el renglón de mayor importe.
 *
 * Traslados (Anexo 20, CFDI 4.0): con ObjetoImp 02 cada concepto lleva un Traslado
 * de IVA (002) con TipoFactor Tasa y TasaOCuota 0.160000 o 0.000000 (c_TasaOCuota
 * admite la tasa 0 para IVA), o TipoFactor Exento solo con Base (sin TasaOCuota ni
 * Importe). El PAC arma el resumen por tasa a nivel comprobante.
 */
public final class ConstructorCfdi {

    static final int DEC_IMPORTE = 2;
    static final int DEC_UNITARIO = 6;
    static final BigDecimal CIEN_MIL = new BigDecimal("100000");

    private static final DateTimeFormatter ANIO = DateTimeFormatter.ofPattern("yyyy");

    private ConstructorCfdi() { }

    /**
     * Renglón "crudo" con importes CON IVA (como los maneja el punto de venta).
     * @param tasa   tasa del renglón (0.16, 0); null = la tasa por defecto que reciba el constructor
     * @param exento true si el renglón es exento de IVA (TipoFactor Exento; la tasa se ignora)
     */
    public record Linea(String claveProdServ, String noIdentificacion, String descripcion, String claveUnidad,
                        String unidad, String objetoImp, BigDecimal cantidad, BigDecimal brutoConIva,
                        BigDecimal descuentoConIva, BigDecimal tasa, boolean exento) {

        /** Renglón que usa la tasa por defecto (la de la venta o la de la tienda) */
        public Linea(String claveProdServ, String noIdentificacion, String descripcion, String claveUnidad,
                     String unidad, String objetoImp, BigDecimal cantidad, BigDecimal brutoConIva,
                     BigDecimal descuentoConIva) {
            this(claveProdServ, noIdentificacion, descripcion, claveUnidad, unidad, objetoImp, cantidad, brutoConIva,
                    descuentoConIva, null, false);
        }
    }

    // ── Factura de una venta ─────────────────────────────────────────────

    /**
     * Comprobante de una venta para un receptor concreto.
     * @param formaPagoSat clave c_FormaPago elegida (por defecto {@link #formaPagoSat(Venta)})
     */
    public static CfdiDocumento paraVenta(Venta venta, ConfiguracionFiscal cfg, ClienteFiscal cliente,
                                         String usoCfdi, String formaPagoSat, String folio, BigDecimal tasaPorDefecto) {
        BigDecimal tasa = tasaDe(venta, tasaPorDefecto);
        List<Concepto> conceptos = conceptos(lineasDe(venta), tasa, BigDecimal.valueOf(venta.getTotal()));
        Receptor receptor = new Receptor(cliente.getRfc(), cliente.getNombre(), cliente.getRegimenFiscal(),
                Rfc.esGenerico(cliente.getRfc()) ? cfg.getEmisorCodigoPostal() : cliente.getCodigoPostal(),
                usoCfdi != null ? usoCfdi : cliente.getUsoCfdi());
        return new CfdiDocumento(cfg.getSerie(), folio, cfg.getEmisorCodigoPostal(),
                formaPagoSat != null ? formaPagoSat : formaPagoSat(venta), CfdiDocumento.METODO_PUE,
                receptor, conceptos, null, numeroOrdenDe(venta), "Venta " + venta.getFolioMostrado());
    }

    /** Referencia única de la venta en el PAC (para no timbrarla dos veces) */
    public static String numeroOrdenDe(Venta venta) {
        return "V-" + venta.getId();
    }

    /** Renglones de la venta con importes con IVA, con sus claves SAT resueltas */
    public static List<Linea> lineasDe(Venta venta) {
        List<Linea> lineas = new ArrayList<>();
        for (DetalleVenta d : venta.getDetalles()) {
            var p = d.getProducto();
            BigDecimal cantidad = BigDecimal.valueOf(d.getCantidad()).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
            if (cantidad.scale() < 0) cantidad = cantidad.setScale(0);
            BigDecimal bruto = BigDecimal.valueOf(d.getPrecioVenta()).multiply(BigDecimal.valueOf(d.getCantidad()));
            BigDecimal descuento = BigDecimal.valueOf(d.getDescuento());
            // Tasa guardada en el renglón al cobrar; null en ventas anteriores (se usa la de la venta)
            BigDecimal tasa = d.getTasaIva() != null ? BigDecimal.valueOf(d.getTasaIva()).setScale(6, RoundingMode.HALF_UP) : null;
            boolean exento = d.isIvaExento();
            if (d.esProductoComun()) {
                // Artículo fuera del catálogo: clave genérica 01010101 "No existe en el catálogo", sin
                // NoIdentificacion, con el nombre tecleado; pieza, o kilogramo si la cantidad trae decimales
                boolean decimales = cantidad.scale() > 0;
                String claveUnidad = decimales ? "KGM" : CatalogoSat.UNIDAD_PIEZA;
                lineas.add(new Linea(CatalogoSat.PROD_SERV_COMODIN, null, d.getDescripcionMostrada(), claveUnidad,
                        CatalogoSat.unidad(claveUnidad).map(CatalogoSat.Entrada::descripcion).orElse(decimales ? "Kilogramo" : "Pieza"),
                        ClavesSat.objetoImpDe(p), cantidad, bruto, descuento, tasa, exento));
                continue;
            }
            lineas.add(new Linea(ClavesSat.claveProdServDe(p), p.getCodigoBarras(), p.getDescripcion(),
                    ClavesSat.claveUnidadDe(p), ClavesSat.nombreUnidadDe(p), ClavesSat.objetoImpDe(p),
                    cantidad, bruto, descuento, tasa, exento));
        }
        return lineas;
    }

    /**
     * Conceptos a partir de renglones con IVA incluido. Si {@code totalEsperado}
     * no es null, la suma de los totales de concepto se ajusta a él (centavos de
     * redondeo en renglones a granel) en el renglón más grande.
     * @param tasa tasa por defecto para los renglones que no traen la suya
     */
    public static List<Concepto> conceptos(List<Linea> lineas, BigDecimal tasa, BigDecimal totalEsperado) {
        if (lineas == null || lineas.isEmpty()) throw new IllegalArgumentException("La venta no tiene renglones.");
        List<BigDecimal> netos = new ArrayList<>();
        for (Linea l : lineas) {
            BigDecimal neto = l.brutoConIva().subtract(l.descuentoConIva()).setScale(DEC_IMPORTE, RoundingMode.HALF_UP);
            if (neto.signum() < 0) throw new IllegalArgumentException("El renglón \"" + l.descripcion() + "\" tiene importe negativo.");
            netos.add(neto);
        }
        if (totalEsperado != null) {
            BigDecimal esperado = totalEsperado.setScale(DEC_IMPORTE, RoundingMode.HALF_UP);
            BigDecimal suma = netos.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal diferencia = esperado.subtract(suma);
            if (diferencia.signum() != 0) {
                BigDecimal tolerancia = new BigDecimal("0.01").multiply(BigDecimal.valueOf(lineas.size()));
                if (diferencia.abs().compareTo(tolerancia) > 0) {
                    throw new IllegalArgumentException(String.format(
                            "Los renglones suman %s pero la venta tiene total %s; no se puede facturar.", suma, esperado));
                }
                int mayor = 0;
                for (int i = 1; i < netos.size(); i++) if (netos.get(i).compareTo(netos.get(mayor)) > 0) mayor = i;
                netos.set(mayor, netos.get(mayor).add(diferencia));
            }
        }
        List<Concepto> conceptos = new ArrayList<>();
        for (int i = 0; i < lineas.size(); i++) {
            conceptos.add(concepto(lineas.get(i), netos.get(i), tasa));
        }
        return conceptos;
    }

    /**
     * Un concepto a partir del renglón y de su neto (con IVA) ya ajustado.
     * @param tasaPorDefecto tasa para el renglón si no trae la suya
     */
    static Concepto concepto(Linea l, BigDecimal neto, BigDecimal tasaPorDefecto) {
        BigDecimal cantidad = l.cantidad();
        if (cantidad.signum() <= 0) throw new IllegalArgumentException("La cantidad de \"" + l.descripcion() + "\" debe ser mayor a cero.");
        String objeto = l.objetoImp() == null ? CatalogoSat.OBJETO_IMP_SI : l.objetoImp();
        boolean desglosa = CatalogoSat.OBJETO_IMP_SI.equals(objeto);
        boolean exento = l.exento();
        BigDecimal tasa = (l.tasa() != null ? l.tasa() : tasaPorDefecto).setScale(6, RoundingMode.HALF_UP);
        if (exento || tasa.signum() < 0) tasa = BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        // Tasa 0 o exento: todo el neto es base (factor 1), igual que un concepto sin desglose
        BigDecimal factor = desglosa ? BigDecimal.ONE.add(tasa) : BigDecimal.ONE;

        BigDecimal brutoRedondeado = l.brutoConIva().setScale(DEC_IMPORTE, RoundingMode.HALF_UP);
        BigDecimal importe = brutoRedondeado.divide(factor, DEC_IMPORTE, RoundingMode.HALF_UP);
        BigDecimal base = neto.divide(factor, DEC_IMPORTE, RoundingMode.HALF_UP);
        if (base.compareTo(importe) > 0) importe = base;         // redondeo: el descuento nunca negativo
        BigDecimal descuento = importe.subtract(base);
        BigDecimal valorUnitario = importe.divide(cantidad, DEC_UNITARIO, RoundingMode.HALF_UP);

        List<Impuesto> impuestos = new ArrayList<>();
        BigDecimal total;
        if (desglosa && exento) {
            impuestos.add(Impuesto.exento(base));          // TipoFactor Exento: solo Base
            total = base;
        } else if (desglosa) {
            BigDecimal iva = neto.subtract(base);          // 0.00 con tasa 0.000000
            impuestos.add(new Impuesto(Impuesto.IVA, base, tasa, iva));
            total = base.add(iva);
        } else {
            total = base;
        }
        return new Concepto(l.claveProdServ(), l.noIdentificacion(), l.descripcion(), l.claveUnidad(), l.unidad(),
                cantidad, valorUnitario, importe, descuento.signum() > 0 ? descuento : null, objeto, impuestos, total);
    }

    // ── Forma de pago ────────────────────────────────────────────────────

    /**
     * c_FormaPago de la venta. El CFDI admite UNA forma de pago: con pago
     * dividido se usa la del pago de mayor monto (criterio del SAT para PUE
     * con varias formas: "la que corresponda al mayor importe"). Tarjeta se
     * reporta como 04 (crédito); el usuario puede cambiarla a 28 (débito) al
     * facturar porque el ticket no distingue el tipo de tarjeta.
     */
    public static String formaPagoSat(Venta venta) {
        Venta.FormaPago forma = venta.getFormaPago();
        if (venta.getPagos() != null && !venta.getPagos().isEmpty()) {
            forma = venta.getPagos().stream()
                    .max(Comparator.comparingDouble(PagoVenta::getMonto))
                    .map(PagoVenta::getFormaPago).orElse(forma);
        }
        return formaPagoSat(forma);
    }

    public static String formaPagoSat(Venta.FormaPago forma) {
        if (forma == null) return "01";
        return switch (forma) {
            case EFECTIVO      -> "01";
            case TARJETA       -> "04";
            case TRANSFERENCIA -> "03";
            case VALES         -> "08";
            case CREDITO       -> "99";
        };
    }

    // ── Factura global (público en general) ─────────────────────────────

    /**
     * Factura global: un concepto por ticket (ClaveProdServ 01010101, unidad
     * ACT, cantidad 1, NoIdentificacion = folio del ticket) con el IVA de cada
     * venta según sus propias tasas: si el ticket mezcla básicos al 0 % con
     * artículos al 16 % (o exentos), el concepto lleva un Traslado por cada tasa
     * (el Anexo 20 admite varios Traslados por concepto, uno por TasaOCuota /
     * TipoFactor). Receptor XAXX010101000, régimen 616, uso S01, código postal
     * del receptor = el del emisor.
     */
    public static CfdiDocumento global(List<Venta> ventas, ConfiguracionFiscal cfg, String periodicidad,
                                       String meses, String anio, String folio, BigDecimal tasaPorDefecto) {
        return global(ventas, cfg, periodicidad, meses, anio, folio, null, tasaPorDefecto);
    }

    /** @param numeroOrden referencia única del periodo en el PAC (null = se arma con la primera venta) */
    public static CfdiDocumento global(List<Venta> ventas, ConfiguracionFiscal cfg, String periodicidad,
                                       String meses, String anio, String folio, String numeroOrden, BigDecimal tasaPorDefecto) {
        if (ventas == null || ventas.isEmpty()) throw new IllegalArgumentException("No hay ventas para la factura global.");
        List<Concepto> conceptos = new ArrayList<>();
        for (Venta v : ventas) {
            conceptos.add(conceptoGlobalDe(v, tasaPorDefecto));
        }
        Receptor receptor = new Receptor(Rfc.PUBLICO_EN_GENERAL, "PUBLICO EN GENERAL",
                CatalogoSat.REGIMEN_SIN_OBLIGACIONES, cfg.getEmisorCodigoPostal(), CatalogoSat.USO_SIN_EFECTOS);
        return new CfdiDocumento(cfg.getSerie(), folio, cfg.getEmisorCodigoPostal(), "01", CfdiDocumento.METODO_PUE,
                receptor, conceptos, new InformacionGlobal(periodicidad, meses, anio),
                numeroOrden != null ? numeroOrden : "G-" + periodicidad + "-" + anio + "-" + meses + "-" + ventas.get(0).getId(),
                "Factura global " + ventas.size() + " venta(s)");
    }

    /**
     * Concepto de UN ticket en la factura global. Si todos sus renglones van a la misma
     * tasa (o la venta no tiene renglones cargados), un solo Traslado con el total, como
     * siempre. Si mezcla tasas, los renglones se desglosan uno por uno con la misma regla
     * de la factura normal y se funden en un concepto: Importe = suma de bases (ya sin
     * descuento), un Traslado por tasa (16 %, 0 %, exento) y Total = lo que pagó el cliente.
     */
    static Concepto conceptoGlobalDe(Venta v, BigDecimal tasaPorDefecto) {
        BigDecimal total = BigDecimal.valueOf(v.getTotal()).setScale(DEC_IMPORTE, RoundingMode.HALF_UP);
        BigDecimal tasaVenta = tasaDe(v, tasaPorDefecto);
        List<DetalleVenta> detalles = v.getDetalles();
        boolean mixta = detalles != null && !detalles.isEmpty()
                && detalles.stream().map(d -> claveTasa(d, tasaVenta)).distinct().count() > 1;
        if (!mixta) {
            Linea l = new Linea(CatalogoSat.PROD_SERV_COMODIN, v.getFolioMostrado(), "Venta", CatalogoSat.UNIDAD_ACTIVIDAD,
                    "Actividad", CatalogoSat.OBJETO_IMP_SI, BigDecimal.ONE, total, BigDecimal.ZERO,
                    detalles == null || detalles.isEmpty() ? tasaVenta : tasaLinea(detalles.get(0), tasaVenta),
                    detalles != null && !detalles.isEmpty() && detalles.get(0).isIvaExento());
            return concepto(l, total, tasaVenta);
        }
        List<Concepto> renglones = conceptos(lineasDe(v), tasaVenta, total);
        BigDecimal importe = BigDecimal.ZERO;
        BigDecimal totalConcepto = BigDecimal.ZERO;
        // Un Traslado por (tipoFactor, tasa), en el orden en que aparecen: 16 % → 0 % → exento, normalmente
        java.util.LinkedHashMap<String, BigDecimal[]> porTasa = new java.util.LinkedHashMap<>();
        java.util.Map<String, Impuesto> muestra = new java.util.HashMap<>();
        for (Concepto c : renglones) {
            importe = importe.add(c.base());
            totalConcepto = totalConcepto.add(c.total());
            for (Impuesto imp : c.impuestos()) {
                String clave = imp.tipoFactor() + "|" + (imp.esExento() ? "" : imp.tasa().toPlainString());
                BigDecimal[] s = porTasa.computeIfAbsent(clave, k -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO });
                s[0] = s[0].add(imp.base());
                s[1] = s[1].add(imp.importe());
                muestra.putIfAbsent(clave, imp);
            }
        }
        List<Impuesto> impuestos = new ArrayList<>();
        porTasa.forEach((clave, s) -> {
            Impuesto m = muestra.get(clave);
            impuestos.add(m.esExento() ? Impuesto.exento(s[0].setScale(DEC_IMPORTE, RoundingMode.HALF_UP))
                    : new Impuesto(Impuesto.IVA, s[0].setScale(DEC_IMPORTE, RoundingMode.HALF_UP), m.tasa(),
                            s[1].setScale(DEC_IMPORTE, RoundingMode.HALF_UP)));
        });
        importe = importe.setScale(DEC_IMPORTE, RoundingMode.HALF_UP);
        return new Concepto(CatalogoSat.PROD_SERV_COMODIN, v.getFolioMostrado(), "Venta", CatalogoSat.UNIDAD_ACTIVIDAD,
                "Actividad", BigDecimal.ONE, importe.setScale(DEC_UNITARIO, RoundingMode.HALF_UP), importe, null,
                CatalogoSat.OBJETO_IMP_SI, impuestos, totalConcepto.setScale(DEC_IMPORTE, RoundingMode.HALF_UP));
    }

    /** Tasa de un renglón ya cobrado: la guardada o la de la venta */
    static BigDecimal tasaLinea(DetalleVenta d, BigDecimal tasaVenta) {
        if (d.isIvaExento()) return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        return d.getTasaIva() != null ? BigDecimal.valueOf(d.getTasaIva()).setScale(6, RoundingMode.HALF_UP) : tasaVenta;
    }

    private static String claveTasa(DetalleVenta d, BigDecimal tasaVenta) {
        return (d.isIvaExento() ? "E" : "T") + tasaLinea(d, tasaVenta).toPlainString();
    }

    /** Mes (01-12) para InformacionGlobal: el mes en que termina el periodo */
    public static String mesesDe(LocalDate hasta) {
        return String.format("%02d", hasta.getMonthValue());
    }

    public static String anioDe(LocalDate hasta) {
        return hasta.format(ANIO);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    static BigDecimal tasaDe(Venta v, BigDecimal porDefecto) {
        if (v.getTasaIva() != null) return BigDecimal.valueOf(v.getTasaIva()).setScale(6, RoundingMode.HALF_UP);
        return porDefecto == null ? new BigDecimal("0.16") : porDefecto.setScale(6, RoundingMode.HALF_UP);
    }
}
