package me.eliassanchezfernandez.puntodeventa.facturacion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.model.Unidad;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Subconjunto de los catálogos del SAT que necesita una tienda de abarrotes
 * para el CFDI 4.0. NO es el catálogo completo (c_ClaveProdServ tiene más de
 * 50,000 claves): son las más comunes, leídas de recursos JSON, más los
 * catálogos chicos (régimen, uso, forma de pago, periodicidad) en código.
 *
 * Una clave que no esté aquí sigue siendo válida: el producto puede llevar
 * cualquier clave con el formato correcto y el PAC la valida contra el catálogo
 * oficial al timbrar.
 */
public final class CatalogoSat {

    /** Entrada de un catálogo: clave y descripción */
    public record Entrada(String clave, String descripcion) {
        @Override public String toString() { return clave + " — " + descripcion; }
    }

    public static final String PROD_SERV_COMODIN = "01010101";
    public static final String PROD_SERV_ABARROTES = "50000000";
    public static final String UNIDAD_PIEZA = "H87";
    public static final String UNIDAD_ACTIVIDAD = "ACT";

    public static final String OBJETO_IMP_NO = "01";
    public static final String OBJETO_IMP_SI = "02";
    public static final String OBJETO_IMP_SI_SIN_DESGLOSE = "03";

    public static final String REGIMEN_SIN_OBLIGACIONES = "616";
    public static final String USO_SIN_EFECTOS = "S01";
    public static final String USO_GASTOS_GENERAL = "G03";

    private static final Pattern FORMATO_PROD_SERV = Pattern.compile("^\\d{8}$");
    private static final Pattern FORMATO_UNIDAD = Pattern.compile("^[A-Z0-9]{2,3}$");
    private static final Pattern FORMATO_CP = Pattern.compile("^\\d{5}$");

    private static final List<Entrada> PROD_SERV = cargar("/sat/clave_prod_serv.json");
    private static final List<Entrada> UNIDADES = cargar("/sat/clave_unidad.json");

    public static final List<Entrada> REGIMENES = List.of(
            new Entrada("601", "General de Ley Personas Morales"),
            new Entrada("603", "Personas Morales con Fines no Lucrativos"),
            new Entrada("605", "Sueldos y Salarios e Ingresos Asimilados a Salarios"),
            new Entrada("606", "Arrendamiento"),
            new Entrada("607", "Régimen de Enajenación o Adquisición de Bienes"),
            new Entrada("608", "Demás ingresos"),
            new Entrada("610", "Residentes en el Extranjero sin Establecimiento Permanente en México"),
            new Entrada("611", "Ingresos por Dividendos (socios y accionistas)"),
            new Entrada("612", "Personas Físicas con Actividades Empresariales y Profesionales"),
            new Entrada("614", "Ingresos por intereses"),
            new Entrada("615", "Régimen de los ingresos por obtención de premios"),
            new Entrada("616", "Sin obligaciones fiscales"),
            new Entrada("620", "Sociedades Cooperativas de Producción"),
            new Entrada("621", "Incorporación Fiscal"),
            new Entrada("622", "Actividades Agrícolas, Ganaderas, Silvícolas y Pesqueras"),
            new Entrada("623", "Opcional para Grupos de Sociedades"),
            new Entrada("624", "Coordinados"),
            new Entrada("625", "Actividades Empresariales con ingresos a través de Plataformas Tecnológicas"),
            new Entrada("626", "Régimen Simplificado de Confianza (RESICO)"));

    public static final List<Entrada> USOS_CFDI = List.of(
            new Entrada("G01", "Adquisición de mercancías"),
            new Entrada("G02", "Devoluciones, descuentos o bonificaciones"),
            new Entrada("G03", "Gastos en general"),
            new Entrada("I01", "Construcciones"),
            new Entrada("I02", "Mobiliario y equipo de oficina por inversiones"),
            new Entrada("I03", "Equipo de transporte"),
            new Entrada("I04", "Equipo de cómputo y accesorios"),
            new Entrada("I05", "Dados, troqueles, moldes, matrices y herramental"),
            new Entrada("I06", "Comunicaciones telefónicas"),
            new Entrada("I07", "Comunicaciones satelitales"),
            new Entrada("I08", "Otra maquinaria y equipo"),
            new Entrada("D01", "Honorarios médicos, dentales y gastos hospitalarios"),
            new Entrada("D02", "Gastos médicos por incapacidad o discapacidad"),
            new Entrada("D03", "Gastos funerales"),
            new Entrada("D04", "Donativos"),
            new Entrada("D05", "Intereses reales efectivamente pagados por créditos hipotecarios"),
            new Entrada("D06", "Aportaciones voluntarias al SAR"),
            new Entrada("D07", "Primas por seguros de gastos médicos"),
            new Entrada("D08", "Gastos de transportación escolar obligatoria"),
            new Entrada("D09", "Depósitos en cuentas para el ahorro, primas de pensiones"),
            new Entrada("D10", "Pagos por servicios educativos (colegiaturas)"),
            new Entrada("S01", "Sin efectos fiscales"),
            new Entrada("CP01", "Pagos"));

    public static final List<Entrada> FORMAS_PAGO = List.of(
            new Entrada("01", "Efectivo"),
            new Entrada("02", "Cheque nominativo"),
            new Entrada("03", "Transferencia electrónica de fondos"),
            new Entrada("04", "Tarjeta de crédito"),
            new Entrada("28", "Tarjeta de débito"),
            new Entrada("08", "Vales de despensa"),
            new Entrada("99", "Por definir"));

    public static final List<Entrada> OBJETOS_IMP = List.of(
            new Entrada(OBJETO_IMP_SI, "Sí objeto de impuesto (IVA desglosado)"),
            new Entrada(OBJETO_IMP_NO, "No objeto de impuesto"),
            new Entrada(OBJETO_IMP_SI_SIN_DESGLOSE, "Sí objeto, no obligado al desglose"));

    /** c_Periodicidad de la factura global */
    public static final List<Entrada> PERIODICIDADES = List.of(
            new Entrada("01", "Diario"),
            new Entrada("02", "Semanal"),
            new Entrada("03", "Quincenal"),
            new Entrada("04", "Mensual"),
            new Entrada("05", "Bimestral"));

    /** Clave de unidad del SAT por nombre de unidad del catálogo propio (minúsculas, sin acentos) */
    private static final Map<String, String> UNIDAD_POR_NOMBRE = new LinkedHashMap<>();
    static {
        UNIDAD_POR_NOMBRE.put("pieza", "H87");
        UNIDAD_POR_NOMBRE.put("pza", "H87");
        UNIDAD_POR_NOMBRE.put("unidad", "H87");
        UNIDAD_POR_NOMBRE.put("kilogramo", "KGM");
        UNIDAD_POR_NOMBRE.put("kilo", "KGM");
        UNIDAD_POR_NOMBRE.put("kg", "KGM");
        UNIDAD_POR_NOMBRE.put("gramo", "GRM");
        UNIDAD_POR_NOMBRE.put("g", "GRM");
        UNIDAD_POR_NOMBRE.put("litro", "LTR");
        UNIDAD_POR_NOMBRE.put("l", "LTR");
        UNIDAD_POR_NOMBRE.put("mililitro", "MLT");
        UNIDAD_POR_NOMBRE.put("ml", "MLT");
        UNIDAD_POR_NOMBRE.put("metro", "MTR");
        UNIDAD_POR_NOMBRE.put("m", "MTR");
        UNIDAD_POR_NOMBRE.put("caja", "XBX");
        UNIDAD_POR_NOMBRE.put("paquete", "XPK");
        UNIDAD_POR_NOMBRE.put("bolsa", "XBG");
        UNIDAD_POR_NOMBRE.put("botella", "XBO");
        UNIDAD_POR_NOMBRE.put("lata", "XCA");
        UNIDAD_POR_NOMBRE.put("docena", "DZN");
        UNIDAD_POR_NOMBRE.put("saco", "XSA");
        UNIDAD_POR_NOMBRE.put("costal", "XSA");
    }

    private CatalogoSat() { }

    // ── Consultas ────────────────────────────────────────────────────────

    public static List<Entrada> clavesProdServ() { return PROD_SERV; }
    public static List<Entrada> clavesUnidad()   { return UNIDADES; }

    /** Busca por clave o por palabra en la descripción (sin acentos ni mayúsculas); vacío = todo */
    public static List<Entrada> buscarProdServ(String texto) { return buscar(PROD_SERV, texto); }
    public static List<Entrada> buscarUnidad(String texto)   { return buscar(UNIDADES, texto); }

    public static Optional<Entrada> prodServ(String clave)  { return porClave(PROD_SERV, clave); }
    public static Optional<Entrada> unidad(String clave)    { return porClave(UNIDADES, clave); }
    public static Optional<Entrada> regimen(String clave)   { return porClave(REGIMENES, clave); }
    public static Optional<Entrada> usoCfdi(String clave)   { return porClave(USOS_CFDI, clave); }
    public static Optional<Entrada> formaPago(String clave) { return porClave(FORMAS_PAGO, clave); }

    public static String descripcionRegimen(String clave)   { return regimen(clave).map(Entrada::descripcion).orElse(clave); }
    public static String descripcionUso(String clave)       { return usoCfdi(clave).map(Entrada::descripcion).orElse(clave); }
    public static String descripcionFormaPago(String clave) { return formaPago(clave).map(Entrada::descripcion).orElse(clave); }

    public static boolean esRegimen(String clave)   { return regimen(clave).isPresent(); }
    public static boolean esUsoCfdi(String clave)   { return usoCfdi(clave).isPresent(); }
    public static boolean esFormaPago(String clave) { return formaPago(clave).isPresent(); }
    public static boolean esObjetoImp(String clave) { return porClave(OBJETOS_IMP, clave).isPresent(); }

    /** Regímenes que puede usar un receptor persona física / moral (SAT: c_RegimenFiscal, columnas Física/Moral) */
    public static List<Entrada> regimenesPara(String rfc) {
        if (Rfc.esPersonaMoral(rfc)) {
            return REGIMENES.stream().filter(r -> List.of("601", "603", "610", "620", "622", "623", "624", "626").contains(r.clave())).toList();
        }
        if (Rfc.esPersonaFisica(rfc)) {
            return REGIMENES.stream().filter(r -> !List.of("601", "603", "620", "623", "624").contains(r.clave())).toList();
        }
        return REGIMENES;
    }

    // ── Formato de claves ────────────────────────────────────────────────

    /** 8 dígitos; null si está vacío. IllegalArgumentException si no tiene formato. */
    public static String normalizarProdServ(String clave) {
        String c = limpiar(clave);
        if (c == null) return null;
        if (!FORMATO_PROD_SERV.matcher(c).matches()) {
            throw new IllegalArgumentException("La clave de producto/servicio del SAT debe tener 8 dígitos (ej. 50000000).");
        }
        return c;
    }

    /** 2 o 3 letras/dígitos en mayúsculas; null si está vacío */
    public static String normalizarUnidad(String clave) {
        String c = limpiar(clave);
        if (c == null) return null;
        c = c.toUpperCase(Locale.ROOT);
        if (!FORMATO_UNIDAD.matcher(c).matches()) {
            throw new IllegalArgumentException("La clave de unidad del SAT debe tener 2 o 3 letras o dígitos (ej. H87, KGM).");
        }
        return c;
    }

    public static String normalizarCodigoPostal(String cp) {
        String c = limpiar(cp);
        if (c == null) return null;
        if (!FORMATO_CP.matcher(c).matches()) {
            throw new IllegalArgumentException("El código postal debe tener 5 dígitos.");
        }
        return c;
    }

    /** Clave de unidad para una unidad del catálogo propio: la suya, o la deducida del nombre, o Pieza */
    public static String claveUnidadPara(Unidad unidad) {
        if (unidad == null) return UNIDAD_PIEZA;
        if (unidad.getClaveUnidad() != null && !unidad.getClaveUnidad().isBlank()) return unidad.getClaveUnidad();
        return claveUnidadPorNombre(unidad.getNombre())
                .or(() -> claveUnidadPorNombre(unidad.getAbreviatura()))
                .orElse(unidad.isPermiteDecimales() ? "KGM" : UNIDAD_PIEZA);
    }

    public static Optional<String> claveUnidadPorNombre(String nombre) {
        if (nombre == null) return Optional.empty();
        return Optional.ofNullable(UNIDAD_POR_NOMBRE.get(sinAcentos(nombre.trim().toLowerCase(Locale.ROOT))));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static List<Entrada> buscar(List<Entrada> lista, String texto) {
        String t = texto == null ? "" : sinAcentos(texto.trim().toLowerCase(Locale.ROOT));
        if (t.isEmpty()) return lista;
        List<Entrada> r = new ArrayList<>();
        for (Entrada e : lista) {
            if (e.clave().toLowerCase(Locale.ROOT).startsWith(t) || sinAcentos(e.descripcion().toLowerCase(Locale.ROOT)).contains(t)) {
                r.add(e);
            }
        }
        return r;
    }

    private static Optional<Entrada> porClave(List<Entrada> lista, String clave) {
        String c = limpiar(clave);
        if (c == null) return Optional.empty();
        return lista.stream().filter(e -> e.clave().equalsIgnoreCase(c)).findFirst();
    }

    private static String limpiar(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }

    static String sinAcentos(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private static List<Entrada> cargar(String recurso) {
        try (InputStream in = CatalogoSat.class.getResourceAsStream(recurso)) {
            if (in == null) throw new IllegalStateException("Falta el recurso " + recurso);
            JsonNode raiz = new ObjectMapper().readTree(in);
            List<Entrada> lista = new ArrayList<>();
            for (JsonNode n : raiz) lista.add(new Entrada(n.path("clave").asText(), n.path("descripcion").asText()));
            return Collections.unmodifiableList(lista);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el catálogo " + recurso + ": " + e.getMessage(), e);
        }
    }
}
