package me.eliassanchezfernandez.puntodeventa.ticketsproveedor;

import me.eliassanchezfernandez.puntodeventa.ocr.Renglon;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intérprete de tickets de proveedor SIN inteligencia artificial: reglas y aritmética.
 *
 * Recibe los renglones que leyó el OCR (o el texto que el usuario pegó) y devuelve uno o más
 * {@link TicketLeido} (una foto puede traer dos tickets). Pasos:
 * <ol>
 *   <li>Limpia: quita separadores ("=====") y une fragmentos cortos que el ticket partió ("Tpr" / "is").</li>
 *   <li>Parte en tickets: cada ticket termina en su TOTAL final (después de tener artículos).</li>
 *   <li>Busca el renglón de títulos de columnas (DESCRIPCION, CANT, PRECIO, IMPORTE…): los artículos van debajo.</li>
 *   <li>Arma artículos con ventanas de 1 o 2 renglones (descripción arriba y números abajo, o al revés) y elige
 *       los números con la cuenta: cantidad × precio − descuento = importe. Así no importa el orden de las
 *       columnas ni si el OCR movió algo de lugar; también prueba "le faltó el punto decimal" (1550 → 15.50).</li>
 *   <li>Lee el pie (SUBTOTAL, DESCUENTO, IVA, IEPS, IMPUESTOS, costo de distribución, TOTAL), el folio y la fecha.</li>
 *   <li>Cuadra la suma de los renglones contra el total (directo, o sumando impuestos no incluidos y cargos).</li>
 * </ol>
 * Es puro (sin Spring ni base de datos) para probarlo con los textos de muestra.
 */
public final class InterpreteTicket {

    private InterpreteTicket() { }

    /** Formatos reconocidos. El nombre sugerido solo se usa si el ticket no imprime el proveedor. */
    public enum Formato {
        GENERICO("Genérico", null),
        /** Terminal de ruta de Cervezas y Refrescos de Jalapa: fechas MES-DÍA-AÑO, precios con impuestos */
        RUTA_JALAPA("Ruta (Cervezas y Refrescos de Jalapa)", "Cervezas y Refrescos de Jalapa"),
        /** "NS CLAVE CANT P.UNIT SUBTOTAL" con descripción arriba y PT REGALO / I.E.P.S. en el pie */
        NOTA_CON_CLAVE("Nota de venta con clave (Gamesa)", "Gamesa"),
        /** SUB.T VENTA / IEPS / PIEZAS EN VENTA, totales antes y después de los artículos */
        BARCEL("Barcel", "Barcel"),
        /** Cedis# / Promotor# */
        LALA("Lala", "Lala"),
        /** Cigarros: cantidad en paquetes y "Total cajetillas" */
        CAJETILLAS("Cigarros (paquetes y cajetillas)", null),
        /** Formulario impreso llenado a mano (CANT. / CONCEPTO / PRECIO) */
        FORMULARIO("Formulario de pedido", null);

        private final String descripcion;
        private final String proveedorSugerido;
        Formato(String d, String p) { descripcion = d; proveedorSugerido = p; }
        public String getDescripcion() { return descripcion; }
        public String getProveedorSugerido() { return proveedorSugerido; }
    }

    // ── Renglones y fichas ─────────────────────────────────────────────────

    static final class Ln {
        final int idx;
        String texto;
        String norm;
        final int y;
        Ln(int idx, String texto, int y) { this.idx = idx; this.texto = texto; this.norm = etiquetas(unirNumerosPartidos(texto)); this.y = y; }
    }

    enum Tipo { NUM, PCT, UNIDAD, PALABRA }
    enum Rol { NINGUNO, INDICE, CODIGO, DESCRIPCION }

    static final class Tok {
        Tipo tipo;
        String txt;
        double valor;
        boolean conPunto;
        boolean cantidadPegada;
        /** Ficha inventada a partir de un signo suelto ("|", "]") que casi siempre es un 1 mal leído */
        boolean conjetura;
        /** Trae un solo decimal ("34.5"): al OCR se le perdió la última cifra de los centavos */
        boolean unDecimal;
        int digitos;
        Rol rol = Rol.NINGUNO;
        boolean deRenglonConTexto;
        Tok(Tipo tipo, String txt) { this.tipo = tipo; this.txt = txt; }
    }

    static final class Analisis {
        final Ln ln;
        final List<Tok> toks;
        final boolean tienePalabras;
        Analisis(Ln ln, List<Tok> toks, boolean tienePalabras) { this.ln = ln; this.toks = toks; this.tienePalabras = tienePalabras; }
    }

    private static final Set<String> UNIDADES = Set.of("ZA", "PZA", "PZ", "PZAS", "CJ", "CAJA", "PAQ", "PQ", "BULTO",
            "C/U", "C/", "U", "MXN", "$");
    /** Unidades que vale la pena mostrar (las demás son ruido: c/u, MXN) */
    private static final Set<String> UNIDADES_VISIBLES = Set.of("ZA", "PZA", "PZ", "PZAS", "CJ", "CAJA", "PAQ", "PQ", "BULTO");

    private static final Pattern CANT_PEGADA = Pattern.compile("^(\\d{1,4}(?:[.,]\\d+)?)-(\\p{L}.*)$");
    private static final Pattern PRECIO_PEGADO = Pattern.compile("^(\\p{L}{2,})(\\d{1,6}[.,]\\d{2})$");
    /** "$20.52c/u": precio con la unidad pegada */
    private static final Pattern UNIDAD_PEGADA = Pattern.compile("(?i)^(\\$?\\d{1,6}[.,]\\d{1,4})(c/u|c/|pz|pza|pzas|kg)$");
    private static final Pattern FOLIOS_DESC = Pattern.compile("(?i)folios\\s+desc\\.?:?");

    // ── Entrada ────────────────────────────────────────────────────────────

    /** Interpreta texto pegado o escrito (un renglón por línea) */
    public static List<TicketLeido> interpretar(String texto) {
        List<Renglon> rs = new ArrayList<>();
        if (texto != null) {
            String[] partes = texto.split("\\R", -1);
            for (int i = 0; i < partes.length; i++) rs.add(new Renglon(partes[i], 1.0, -1));
        }
        return interpretar(rs);
    }

    /** Interpreta los renglones que leyó el OCR */
    public static List<TicketLeido> interpretar(List<Renglon> renglones) {
        List<Ln> lns = preparar(renglones);
        List<TicketLeido> tickets = new ArrayList<>();
        if (lns.isEmpty()) return tickets;
        Formato formato = detectarFormato(lns, 0, lns.size());

        if (formato == Formato.FORMULARIO) {
            tickets.add(interpretarFormulario(lns));
            return tickets;
        }

        // Partir en tickets: cada uno termina en su TOTAL final, ya con artículos
        record Seg(int inicio, int desde, int finItems, int fin) { }
        List<Seg> segs = new ArrayList<>();
        int inicio = 0;
        while (inicio < lns.size()) {
            int h = buscarTitulos(lns, inicio, lns.size());
            int desde = h >= 0 ? h + 1 : inicio;
            Formato f = detectarFormato(lns, inicio, lns.size());
            int finItems = lns.size(), fin = lns.size();
            for (int k = desde; k < lns.size(); k++) {
                if (esTotalFinal(lns.get(k)) && !leerArticulos(lns, desde, k, f).isEmpty()) {
                    finItems = k;
                    fin = k + 1;
                    break;
                }
            }
            List<LineaTicket> arts = leerArticulos(lns, desde, finItems, f);
            boolean conArticulos = !arts.isEmpty();
            // Después de un ticket, lo que sigue solo es OTRO ticket si trae su propio encabezado
            // (folio, fecha, RFC…); si no, es la cola del anterior (resumen por precios, forma de pago…)
            if (conArticulos && !segs.isEmpty()) {
                int primero = indiceDe(lns, arts.get(0).getRenglon());
                conArticulos = tieneEncabezadoPropio(lns, inicio, primero);
            }
            if (!conArticulos && !segs.isEmpty()) {
                Seg prev = segs.remove(segs.size() - 1);
                segs.add(new Seg(prev.inicio(), prev.desde(), prev.finItems(), fin));   // cola del anterior
            } else {
                segs.add(new Seg(inicio, desde, finItems, fin));
            }
            inicio = fin;
        }

        for (Seg s : segs) {
            Formato f = detectarFormato(lns, s.inicio(), s.fin());
            TicketLeido t = new TicketLeido();
            t.setFormato(f);
            t.setProveedorPorFormato(f.getProveedorSugerido());
            List<LineaTicket> lineas = leerArticulos(lns, s.desde(), s.finItems(), f);
            normalizarClaves(lineas);
            t.setLineas(lineas);
            int finEncabezado = lineas.isEmpty() ? s.desde() : indiceDe(lns, lineas.get(0).getRenglon());
            for (int i = s.inicio(); i < Math.max(finEncabezado, s.inicio()); i++) t.getEncabezado().add(lns.get(i).texto);
            StringBuilder sb = new StringBuilder();
            for (int i = s.inicio(); i < s.fin(); i++) sb.append(lns.get(i).texto).append('\n');
            t.setTexto(sb.toString());
            leerEncabezado(t, lns, s.inicio(), s.fin(), f);
            List<Double> numerosPie = leerPie(t, lns, s.inicio(), s.fin(), s.finItems());
            completarEmpaques(t);
            t.setCuadre(cuadrar(t, numerosPie));
            tickets.add(t);
        }
        return tickets;
    }

    private static final Pattern P_ENCABEZADO_PROPIO = Pattern.compile(
            "\\bFOLIO\\b|\\bFACTURA\\b|NOTA DE VENTA|REMISION|\\bRFC\\b|\\bFECHA\\b|IMPRESION|\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}");

    private static boolean tieneEncabezadoPropio(List<Ln> lns, int desde, int hasta) {
        for (int i = desde; i < hasta && i < lns.size(); i++) {
            if (P_ENCABEZADO_PROPIO.matcher(lns.get(i).norm).find()) return true;
        }
        return false;
    }

    private static int indiceDe(List<Ln> lns, int renglonOriginal) {
        for (int i = 0; i < lns.size(); i++) if (lns.get(i).idx == renglonOriginal) return i;
        return 0;
    }

    // ── Limpieza ───────────────────────────────────────────────────────────

    static List<Ln> preparar(List<Renglon> renglones) {
        List<Ln> out = new ArrayList<>();
        for (int i = 0; i < renglones.size(); i++) {
            Renglon r = renglones.get(i);
            String t = r.texto() == null ? "" : r.texto().replace('\t', ' ').strip();
            if (!t.matches(".*[\\p{L}\\p{N}].*")) continue;          // vacíos y separadores
            // Fragmento corto que el ticket partió ("Tpr" / "is"): se pega al renglón anterior
            if (!out.isEmpty() && (t.matches("\\p{L}{1,2}") || t.matches("\\p{Ll}{3}"))) {
                Ln prev = out.get(out.size() - 1);
                if (Character.isLetter(prev.texto.charAt(prev.texto.length() - 1))) {
                    prev.texto = prev.texto + " " + t;
                    prev.norm = etiquetas(prev.texto);
                    continue;
                }
            }
            out.add(new Ln(i, t, r.y()));
        }
        return out;
    }

    /** Texto para buscar etiquetas: normal y con 0/1 entre letras corregidos (T0TAL → TOTAL) */
    static String etiquetas(String s) {
        String n = TextoTicket.normal(s);
        n = n.replaceAll("(?<=[A-Z])0(?=[A-Z])", "O").replaceAll("(?<=[A-Z])1(?=[A-Z])", "I");
        // "TOTA]" / "TOTA}" (la L leída como corchete), "I|A" y "IUA" (la V como U), "TOTALAPAGAR" pegado
        n = n.replaceAll("(?<=[A-Z]{3})[\\]}](?=[^A-Z]|$)", "L").replaceAll("(?<=[A-Z])[|!](?=[A-Z])", "I")
                .replaceAll("\\bIUA\\b", "IVA").replaceAll("\\bI\\.U\\.A\\b", "I.V.A").replaceAll("TOTAL ?A ?PAGAR", "TOTAL A PAGAR");
        // Etiquetas con una letra mal leída ("T0TAL", "SUBnOTAL", "CAJETIELAS") se corrigen; las largas aguantan dos
        Matcher m = Pattern.compile("[A-Z]{5,}").matcher(n);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String w = m.group();
            String e = etiquetaParecida(w);
            m.appendReplacement(sb, e != null ? e : w);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static final List<String> PALABRAS_ETIQUETA = List.of("TOTAL", "SUBTOTAL", "DESCUENTO", "DESCUENTOS",
            "IMPUESTOS", "PIEZAS", "CANTIDAD", "IMPORTE", "CAJETILLAS", "PAQUETES", "FOLIO", "FECHA", "FACTURA",
            "REMISION", "DESCRIPCION", "PRECIO", "IMPRESION", "CAPTURA", "DISTRIBUCION", "PRODUCTOS");

    /** La etiqueta conocida que difiere de la palabra en una letra (dos si la etiqueta tiene 8+), mismo largo; o null */
    static String etiquetaParecida(String w) {
        for (String e : PALABRAS_ETIQUETA) {
            if (e.equals(w)) return null;
        }
        for (String e : PALABRAS_ETIQUETA) {
            if (e.length() != w.length()) continue;
            int dif = 0;
            for (int i = 0; i < e.length() && dif <= 2; i++) if (e.charAt(i) != w.charAt(i)) dif++;
            if (dif == 1 || (dif == 2 && e.length() >= 8)) return e;
        }
        return null;
    }

    /** "324. 00" y "468 .81": un número al que el OCR le metió un espacio junto al punto */
    static String unirNumerosPartidos(String s) {
        return s.replaceAll("(?<=\\d)\\. (?=\\d{2}(?!\\d))", ".").replaceAll("(?<=\\d) \\.(?=\\d{2}(?!\\d))", ".");
    }

    static List<Tok> fichas(String texto) {
        String s = FOLIOS_DESC.matcher(unirNumerosPartidos(texto)).replaceAll(" ");
        s = s.replace("=", " ").replace("*", " ").replace("\u00d7", " ").replace("\u00a5", " ").replace("\uffe5", " ");
        List<Tok> out = new ArrayList<>();
        String[] crudos = s.trim().split("\\s+");
        for (int ix = 0; ix < crudos.length; ix++) {
            String crudo = crudos[ix];
            // Un "|", "]", "l" o "I" solo entre cifras es casi siempre un 1 (cantidad) mal leído
            if (crudo.matches("[|\\]lI!}]") && ix > 0 && ix < crudos.length - 1
                    && crudos[ix - 1].matches("\\$?[\\d.,]*\\d[\\d.,]*") && crudos[ix + 1].matches("\\$?[\\d.,]*\\d[\\d.,]*")) {
                Tok uno = numero("1");
                uno.conjetura = true;
                out.add(uno);
                continue;
            }
            String t = crudo.replaceAll("^[\"'()\\[\\]{}|]+", "").replaceAll("[\"'),;\\]|]+$", "");
            if (t.length() > 1 && t.endsWith(":")) t = t.substring(0, t.length() - 1);
            if (t.isEmpty() || t.equals("$") || t.equals("-") || t.equals(":")) continue;
            Matcher mu = UNIDAD_PEGADA.matcher(t);
            if (mu.matches()) {
                Tok n = numero(mu.group(1));
                if (n != null) { out.add(n); out.add(new Tok(Tipo.UNIDAD, mu.group(2))); continue; }
            }
            Matcher m = CANT_PEGADA.matcher(t);
            if (m.matches()) {
                Tok q = numero(m.group(1));
                if (q != null) { q.cantidadPegada = true; out.add(q); t = m.group(2); }
            }
            m = PRECIO_PEGADO.matcher(t);
            if (m.matches()) {
                out.add(new Tok(Tipo.PALABRA, m.group(1)));
                Tok n = numero(m.group(2));
                if (n != null) out.add(n);
                continue;
            }
            String c = TextoTicket.corregirNumero(t);
            if (c.endsWith("%") && TextoTicket.numero(c.substring(0, c.length() - 1)) != null) {
                out.add(new Tok(Tipo.PCT, c));
                continue;
            }
            Tok n = numero(c);
            if (n != null) { out.add(n); continue; }
            if (UNIDADES.contains(TextoTicket.normal(t))) { out.add(new Tok(Tipo.UNIDAD, t)); continue; }
            out.add(new Tok(Tipo.PALABRA, t));
        }
        return out;
    }

    private static Tok numero(String t) {
        String s = t.startsWith("-$") ? "-" + t.substring(2) : t;
        Double v = TextoTicket.numero(s);
        if (v == null) return null;
        Tok k = new Tok(Tipo.NUM, t);
        k.valor = v;
        String sin = s.replace("$", "").replace("-", "");
        k.conPunto = sin.contains(".") || sin.matches("\\d+,\\d{1,2}");
        k.unDecimal = sin.matches("\\d+[.,]\\d");
        int d = 0;
        for (char c : sin.toCharArray()) if (Character.isDigit(c)) d++;
        k.digitos = d;
        return k;
    }

    static Analisis analizar(Ln ln) {
        List<Tok> toks = fichas(ln.texto);
        int primera = -1, ultima = -1;
        boolean palabras = false;
        for (int i = 0; i < toks.size(); i++) {
            Tok t = toks.get(i);
            if (t.tipo == Tipo.PALABRA) {
                if (primera < 0) primera = i;
                int letras = t.txt.replaceAll("[^\\p{L}]", "").length();
                // Basura corta al final del renglón ("pa", "Na", "u") no convierte el importe de antes en descripción
                if (letras >= 3 || (i < toks.size() - 1 && toks.get(i + 1).tipo == Tipo.PALABRA)) ultima = i;
                if (letras >= 2) palabras = true;
            }
        }
        if (!toks.isEmpty()) {
            Tok a = toks.get(0);
            if (esEntero(a) && a.digitos >= 4 && !a.cantidadPegada) {
                a.rol = Rol.CODIGO;
            } else if (toks.size() > 1 && esEntero(a) && a.digitos <= 3 && esEntero(toks.get(1)) && toks.get(1).digitos >= 4) {
                a.rol = Rol.INDICE;
                toks.get(1).rol = Rol.CODIGO;
            } else if (palabras) {
                // Clave larga (7+ cifras) al final de la descripción: el OCR juntó dos renglones
                for (int i = toks.size() - 1; i > 0; i--) {
                    Tok t = toks.get(i);
                    if (t.tipo != Tipo.NUM) continue;
                    if (esEntero(t) && t.digitos >= 7 && !t.cantidadPegada) t.rol = Rol.CODIGO;
                    break;
                }
            }
        }
        for (int i = primera + 1; i < ultima; i++) {
            Tok t = toks.get(i);
            if (t.tipo == Tipo.NUM && t.rol == Rol.NINGUNO && !t.cantidadPegada) t.rol = Rol.DESCRIPCION;
        }
        for (Tok t : toks) t.deRenglonConTexto = palabras;
        return new Analisis(ln, toks, palabras);
    }

    private static boolean esEntero(Tok t) {
        return t.tipo == Tipo.NUM && !t.conPunto && !t.txt.contains(",") && !t.txt.contains("$") && t.valor >= 0;
    }

    // ── Clasificación de renglones ────────────────────────────────────────

    private static final Set<String> PALABRAS_TITULO = Set.of("DESCRIPCION", "CANT", "CANT.", "CANTIDAD", "PRECIO",
            "P.UNIT", "PRECI", "IMPORTE", "SUBTOTAL", "SUBTOT", "TOTAL", "PRODUCTO", "CONCEPTO", "CLAVE", "PROD",
            "DESC", "DESC.", "NS", "NO.", "IMP.", "COD");

    /** Renglón de títulos de columnas: dos o más títulos y ningún importe */
    static boolean esTitulos(Ln ln) {
        if (ln.norm.matches(".*\\d[.,]\\d.*")) return false;
        String[] ps = ln.norm.split(" ");
        int titulos = 0;
        for (String p : ps) if (PALABRAS_TITULO.contains(p.replaceAll("[:]", ""))) titulos++;
        return titulos >= 2 && titulos * 2 >= ps.length;
    }

    static int buscarTitulos(List<Ln> lns, int desde, int hasta) {
        for (int i = desde; i < hasta; i++) if (esTitulos(lns.get(i))) return i;
        return -1;
    }

    private static final Pattern P_TOTAL = Pattern.compile(
            "(^|[^A-Z.])TOTAL(\\s+A\\s+PAGAR|\\s+FINAL)?\\s*[:=$]*\\s*(\\d+\\s+)?\\$?\\s*\\d[\\d,]*(\\.\\d+)?");
    private static final Pattern P_TOTAL_NO = Pattern.compile(
            "TOTAL\\s+(DE\\s+PRODUCTOS|PAQUETES|CAJETILLAS|VENTAS|PIEZAS)|SUB\\.?\\s*TOTAL|SUBTOT");

    static boolean esTotalFinal(Ln ln) {
        String n = ln.norm;
        if (P_TOTAL_NO.matcher(n).find()) return false;
        if (n.matches("^\\p{L}{3,8}TOTAL.*")) return false;              // "UBTOTAL" recortado
        return P_TOTAL.matcher(n).find();
    }

    private static final Pattern P_PIE = Pattern.compile(
            "\\bSUB\\.?\\s*T\\b|SUBTOTAL|SUB TOTAL|\\bSUBTOT\\b|\\bTOTAL\\b|\\bDESCUENTOS?\\b|DESC/BONIF|\\bI\\.?V\\.?A\\.?(\\b|=|:)"
            + "|\\bI\\.?E\\.?P\\.?S\\.?(\\b|=|:)|\\bIMPUESTOS\\b|\\bPIEZAS\\b|\\bCANTIDAD\\s*:|\\bIMPORTE\\s*:|PT REGALO"
            + "|\\bVENTAS?\\s*[=:]|COSTO\\s+DE\\s+DISTRIBUCION|COSTO\\s+DISTRIBUCION|\\bFLETE\\b|COSTO DE ENVIO"
            + "|CUPONES|^-?\\s*\\(?\\d+\\s*%\\)?\\s");

    /** Etiquetas recortadas por la orilla de la foto ("UBTOTAL", "ESCUENTO") */
    private static final String[] ETIQUETAS_LARGAS = { "SUBTOTAL", "DESCUENTO", "IMPUESTOS", "IMPRESION", "CAPTURA",
            "FOLIO", "PEDIDO", "FACTURA", "REMISION", "VENDEDOR", "CLIENTE" };

    /** Etiqueta canónica del primer término del renglón, tolerando que la foto haya cortado las primeras letras */
    static String etiquetaRecortada(String norm) {
        String primera = norm.split("[ :]", 2)[0].replaceAll("[^A-Z]", "");
        if (primera.length() < 4 || primera.equals("TOTAL")) return null;
        for (String e : ETIQUETAS_LARGAS) {
            if (e.equals(primera)) return e;
            if (e.endsWith(primera) && primera.length() >= e.length() - 3) return e;
        }
        return null;
    }

    static boolean esPie(Ln ln) {
        if (P_PIE.matcher(ln.norm).find()) return true;
        String e = etiquetaRecortada(ln.norm);
        return "SUBTOTAL".equals(e) || "DESCUENTO".equals(e) || "IMPUESTOS".equals(e);
    }

    private static final Pattern P_FECHA_TEXTO = Pattern.compile("\\d{1,4}[/-]\\d{1,2}[/-]\\d{2,4}");
    private static final Pattern P_ETIQUETA = Pattern.compile("^([\\p{L}.#]+( [\\p{L}.#]+){0,3})\\s*:");
    private static final Pattern P_FIN = Pattern.compile("METODO DE PAGO|FORMA DE PAGO|AGRUPACIONES|FIRMA|GRACIAS POR|"
            + "NO ES COMPROBANTE|SIN VALOR FISCAL|PEDIDOS Y QUEJAS");

    /** Renglón de encabezado (Cliente:, Calle:, Fecha…): nunca es artículo */
    static boolean esEncabezado(Ln ln) {
        if (P_FECHA_TEXTO.matcher(ln.norm).find()) return true;
        Matcher m = P_ETIQUETA.matcher(ln.norm);
        // "pr: 126 DANONE MANZANA $0.00 $31.00": basura con dos puntos al inicio de un renglón de artículo
        if (m.find() && dineros(ln).size() < 2) return true;
        return P_FIN.matcher(ln.norm).find();
    }

    // ── Artículos ──────────────────────────────────────────────────────────

    /** Artículos entre [desde, hasta): ventanas de 1–2 renglones elegidas con programación dinámica */
    static List<LineaTicket> leerArticulos(List<Ln> lns, int desde, int hasta, Formato formato) {
        List<Analisis> cand = new ArrayList<>();
        for (int i = Math.max(0, desde); i < Math.min(hasta, lns.size()); i++) {
            Ln ln = lns.get(i);
            if (!cand.isEmpty() && P_FIN.matcher(ln.norm).find()) break;    // resumen por precios, forma de pago: ya no hay artículos
            if (esTitulos(ln) || esPie(ln) || esEncabezado(ln) || esTotalFinal(ln)) continue;
            cand.add(analizar(ln));
        }
        int n = cand.size();
        double[] mejor = new double[n + 1];
        Articulo[] eleccion = new Articulo[n + 1];
        int[] paso = new int[n + 1];
        for (int i = n - 1; i >= 0; i--) {
            mejor[i] = mejor[i + 1];
            paso[i] = 1;
            eleccion[i] = null;
            for (int k = 1; k <= 2 && i + k <= n; k++) {
                Articulo a = resolverVentana(cand.subList(i, i + k));
                if (a == null) continue;
                double total = a.puntaje + mejor[i + k];
                if (total > mejor[i] + 1e-9) {
                    mejor[i] = total;
                    paso[i] = k;
                    eleccion[i] = a;
                }
            }
        }
        List<LineaTicket> out = new ArrayList<>();
        Set<Integer> usados = new HashSet<>();
        for (int i = 0; i < n; ) {
            if (eleccion[i] != null) {
                out.add(eleccion[i].linea);
                for (int j = i; j < i + paso[i]; j++) usados.add(j);
                i += paso[i];
            } else {
                i++;
            }
        }
        // Renglones con importe que no se pudieron leer: van en rojo para que el usuario los capture
        for (int i = 0; i < n; i++) {
            if (usados.contains(i)) continue;
            Analisis a = cand.get(i);
            List<Tok> nums = numerosLibres(List.of(a));
            boolean conImporte = nums.stream().anyMatch(t -> t.conPunto);
            Analisis texto = a.tienePalabras ? a : (i > 0 && !usados.contains(i - 1) && cand.get(i - 1).tienePalabras ? cand.get(i - 1) : null);
            if (!conImporte || texto == null || nums.size() + (a.tienePalabras ? 1 : 0) < 2) continue;
            LineaTicket l = new LineaTicket();
            l.setRenglon(texto.ln.idx);
            l.setY(texto.ln.y);
            l.setTextoOriginal(texto == a ? a.ln.texto : texto.ln.texto + " / " + a.ln.texto);
            l.setDescripcion(descripcionDe(List.of(texto), Set.of()));
            l.setCodigoProveedor(codigoDe(List.of(texto, a)));
            l.setCantidad(1);
            l.setImporte(nums.get(nums.size() - 1).valor);
            l.setEstado(LineaTicket.Estado.ILEGIBLE);
            l.setMotivo("No salen las cuentas de este renglón: escribe la cantidad y el precio.");
            out.add(l);
        }
        out.sort((x, y) -> Integer.compare(x.getRenglon(), y.getRenglon()));
        return out;
    }

    private record Articulo(LineaTicket linea, double puntaje) { }

    private static List<Tok> numerosLibres(List<Analisis> ventana) {
        List<Tok> nums = new ArrayList<>();
        for (Analisis a : ventana) {
            for (Tok t : a.toks) if (t.tipo == Tipo.NUM && (t.rol == Rol.NINGUNO)) nums.add(t);
        }
        return nums;
    }

    private static Articulo resolverVentana(List<Analisis> ventana) {
        boolean texto = ventana.stream().anyMatch(a -> a.tienePalabras);
        List<Tok> nums = numerosLibres(ventana);
        if (nums.isEmpty()) return null;
        Solucion s = Solucionador.resolver(nums);
        if (s == null) return null;
        String desc = descripcionDe(ventana, s.usados());
        int letras = desc.replaceAll("[^\\p{L}]", "").length();
        boolean sinDescripcion = false;
        String codigo = codigoDe(ventana);
        if (!texto || letras < 2) {
            // Sin palabras (el OCR se comió la descripción): solo si es claramente un renglón de artículo, o sea
            // cantidad × precio − descuento = importe con 3+ cifras, o clave de proveedor con precio e importe.
            // Se marca para que el usuario elija el producto.
            boolean cuentas = s.usados().size() >= 3 && !s.cantidadSupuesta() && s.cantidad() == Math.floor(s.cantidad())
                    && s.diferencia() <= Solucionador.tolerancia(s.cantidad());
            boolean conClave = codigo != null && s.usados().size() >= 2 && s.diferencia() <= 0.011;
            if (s.correcciones() > 0 || !(cuentas || conClave) || (ventana.size() == 2 && codigo == null)) return null;
            sinDescripcion = true;
            desc = "";
        }
        // Sin descripción vale mucho menos que emparejarse con la descripción de arriba (si la hay)
        double puntaje = s.puntaje() + Math.min(letras / 10.0, 1.5) - (ventana.size() - 1);
        if (sinDescripcion) puntaje = Math.max(0.5, puntaje - 8);
        // Un renglón que solo trae número y clave ("2 300062346") no puede ser artículo solo: conviene absorberlo
        if (ventana.size() == 2) {
            for (Analisis a : ventana) {
                if (!a.tienePalabras && codigoDe(List.of(a)) != null && numerosLibres(List.of(a)).isEmpty()) puntaje += 1.5;
            }
        }
        // Desempate: lo más común es la descripción arriba y los números abajo
        if (ventana.size() == 2 && ventana.get(0).tienePalabras && !ventana.get(1).tienePalabras) puntaje += 0.05;

        LineaTicket l = new LineaTicket();
        Analisis primero = ventana.get(0);
        l.setRenglon(primero.ln.idx);
        l.setY(primero.ln.y);
        StringBuilder orig = new StringBuilder();
        for (Analisis a : ventana) { if (orig.length() > 0) orig.append(" / "); orig.append(a.ln.texto); }
        l.setTextoOriginal(orig.toString());
        l.setCodigoProveedor(codigoDe(ventana));
        l.setDescripcion(desc);
        l.setUnidad(unidadDe(ventana));
        l.setCantidad(s.cantidad());
        l.setPrecioUnitario(s.precio());
        l.setDescuento(s.descuento());
        l.setImporte(s.importe());
        l.setDescuentoAntesDeIva(s.descuentoAntesDeIva());
        List<String> motivos = new ArrayList<>();
        if (sinDescripcion) motivos.add("No se leyó la descripción de este renglón: elige el producto.");
        if (s.conjeturas() > 0) motivos.add("La cantidad se leyó como un signo: se tomó 1.");
        if (s.cerosCorregidos() > 0) motivos.add("Ceros de impresora de matriz leídos como 8 o 6: se corrigieron para que cuadre.");
        if (s.cantidadSupuesta()) motivos.add("No se vio la cantidad: se supuso 1.");
        if (s.correcciones() > 0) motivos.add("A un número le faltaba el punto decimal: revisa precio e importe.");
        if (s.diferencia() > 0.02) motivos.add(String.format(Locale.ROOT, "Cantidad × precio no da exacto el importe (diferencia de %.2f).", s.diferencia()));
        if (!motivos.isEmpty()) {
            l.setEstado(LineaTicket.Estado.REVISAR);
            l.setMotivo(String.join(" ", motivos));
        }
        return new Articulo(l, puntaje);
    }

    private static String descripcionDe(List<Analisis> ventana, Set<Tok> usados) {
        StringBuilder sb = new StringBuilder();
        for (Analisis a : ventana) {
            for (Tok t : a.toks) {
                boolean va = t.tipo == Tipo.PALABRA
                        || (t.tipo == Tipo.NUM && t.rol == Rol.DESCRIPCION)
                        || (t.tipo == Tipo.NUM && t.rol == Rol.NINGUNO && a.tienePalabras && !usados.contains(t)
                            && !t.cantidadPegada);
                if (!va) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(t.txt);
            }
        }
        return sb.toString().replaceAll("^[-*·.\\s]+", "").trim();
    }

    /**
     * "7023427" entre claves de 6 cifras: el OCR pegó el número de renglón a la clave. Si la mayoría de las
     * claves miden L cifras, a las de L+1 que empiezan con una cifra de 1 a 9 se les quita esa cifra.
     */
    static void normalizarClaves(List<LineaTicket> lineas) {
        Map<Integer, Integer> largos = new HashMap<>();
        for (LineaTicket l : lineas) {
            String c = l.getCodigoProveedor();
            if (c != null && c.matches("\\d{4,}")) largos.merge(c.length(), 1, Integer::sum);
        }
        if (largos.isEmpty()) return;
        int largo = -1, cuantos = 0;
        for (var e : largos.entrySet()) if (e.getValue() > cuantos) { cuantos = e.getValue(); largo = e.getKey(); }
        if (cuantos < 2 || cuantos * 2 < lineas.size()) return;
        for (LineaTicket l : lineas) {
            String c = l.getCodigoProveedor();
            if (c != null && c.matches("[1-9]\\d{" + largo + "}")) {
                l.setCodigoProveedor(c.substring(1));
                if (l.getEstado() == LineaTicket.Estado.RECONOCIDO) {
                    l.setEstado(LineaTicket.Estado.REVISAR);
                    l.setMotivo("La clave traía pegado el número de renglón: se leyó " + c.substring(1) + ".");
                }
            }
        }
    }

    private static String codigoDe(List<Analisis> ventana) {
        for (Analisis a : ventana) for (Tok t : a.toks) if (t.rol == Rol.CODIGO) return t.txt;
        return null;
    }

    private static String unidadDe(List<Analisis> ventana) {
        for (Analisis a : ventana) {
            for (Tok t : a.toks) {
                if (t.tipo == Tipo.UNIDAD && UNIDADES_VISIBLES.contains(TextoTicket.normal(t.txt))) return TextoTicket.normal(t.txt);
            }
        }
        return null;
    }

    // ── Aritmética del renglón ─────────────────────────────────────────────

    record Solucion(double cantidad, Double precio, Double descuento, double importe, Set<Tok> usados,
                    boolean descuentoAntesDeIva, int correcciones, boolean cantidadSupuesta, double diferencia,
                    double puntaje, int cerosCorregidos, int conjeturas) {
        Solucion(double cantidad, Double precio, Double descuento, double importe, Set<Tok> usados,
                 boolean descuentoAntesDeIva, int correcciones, boolean cantidadSupuesta, double diferencia, double puntaje) {
            this(cantidad, precio, descuento, importe, usados, descuentoAntesDeIva, correcciones, cantidadSupuesta, diferencia, puntaje, 0, 0);
        }
        Solucion conPuntaje(double p) {
            return new Solucion(cantidad, precio, descuento, importe, usados, descuentoAntesDeIva, correcciones, cantidadSupuesta,
                    diferencia, p, cerosCorregidos, conjeturas);
        }
        Solucion conCeros(int ceros) {
            return new Solucion(cantidad, precio, descuento, importe, usados, descuentoAntesDeIva, correcciones, cantidadSupuesta,
                    diferencia, puntaje - 0.45 * ceros, ceros, conjeturas);
        }
        Solucion conConjeturas(int c) {
            return new Solucion(cantidad, precio, descuento, importe, usados, descuentoAntesDeIva, correcciones, cantidadSupuesta,
                    diferencia, puntaje - 0.3 * c, cerosCorregidos, c);
        }
    }

    static final class Solucionador {
        private Solucionador() { }

        static double tolerancia(double q) { return 0.011 + 0.0051 * Math.abs(q); }

        /**
         * Variantes de un número: {valor, correcciones de punto, ceros corregidos}. Tal cual; si no traía punto,
         * "le faltó el punto" (1550 → 15.50); y, si trae 8 o 6, con esas cifras como 0 (las impresoras de matriz
         * de puntos imprimen el cero con diagonal y el OCR lo lee 8 o 6: 215.08 → 215.00, 1.8 → 1.0).
         */
        static List<double[]> variantes(Tok t) {
            List<double[]> v = new ArrayList<>();
            v.add(new double[] { t.valor, 0, 0 });
            if (!t.conPunto && t.digitos >= 3 && !t.cantidadPegada) v.add(new double[] { t.valor / 100.0, 1, 0 });
            if (!t.conjetura) {
                for (double[] c : conCerosCorregidos(t.txt)) v.add(new double[] { c[0], 0, c[1] });
            }
            return v;
        }

        /** El mismo texto numérico con sus 8 y/o 6 como ceros: {valor, cuántas cifras cambiaron} */
        static List<double[]> conCerosCorregidos(String txt) {
            List<double[]> out = new ArrayList<>();
            String base = txt.replace("$", "");
            if (!base.matches("[\\d.,]+") || (base.indexOf('8') < 0 && base.indexOf('6') < 0)) return out;
            String[] formas = { base.replace('8', '0'), base.replace('6', '0'), base.replace('8', '0').replace('6', '0') };
            Set<String> vistas = new HashSet<>();
            vistas.add(base);
            for (String f : formas) {
                if (!vistas.add(f)) continue;
                Double v = TextoTicket.numero(f);
                if (v == null) continue;
                int cambios = 0;
                for (int i = 0; i < f.length(); i++) if (f.charAt(i) != base.charAt(i)) cambios++;
                out.add(new double[] { v, cambios });
            }
            return out;
        }

        static Solucion resolver(List<Tok> nums) {
            Solucion mejor = null;
            int n = nums.size();
            // El importe es casi siempre el último número; si el OCR revolvió las columnas puede ser otro con punto
            for (int im = n - 1; im >= 0; im--) {
                if (im < n - 1 && !(nums.get(im).conPunto && nums.get(im).digitos >= 3)) continue;
                List<Tok> orden = new ArrayList<>(nums);
                Tok ti = orden.remove(im);
                orden.add(ti);
                Solucion s = resolverConImporteAlFinal(orden);
                if (s == null) continue;
                if (im < n - 1) s = s.conPuntaje(s.puntaje() - 1.5);
                mejor = mejorDe(mejor, s);
            }
            return mejor;
        }

        private static Solucion resolverConImporteAlFinal(List<Tok> nums) {
            int n = nums.size();
            Tok ti = nums.get(n - 1);
            Solucion mejor = null;
            for (double[] vi : variantes(ti)) {
                double imp = vi[0];
                int ci = (int) vi[1];
                int zi = (int) vi[2];
                if (imp <= 0) continue;
                if (n >= 3) {
                    for (int i = 0; i < n - 1; i++) {
                        Tok tq = nums.get(i);
                        double q = tq.valor;
                        if (q <= 0 || q > 10000) continue;
                        for (int j = 0; j < n - 1; j++) {
                            if (j == i) continue;
                            for (double[] vp : variantes(nums.get(j))) {
                                double p = vp[0];
                                if (p <= 0) continue;
                                int c = ci + (int) vp[1];
                                int z = zi + (int) vp[2];
                                for (double[] vq : variantes(tq)) {
                                    if (vq[1] > 0) continue;                       // a la cantidad no le falta el punto
                                    double qq = vq[0];
                                    if (qq <= 0 || qq > 10000) continue;
                                    int zq = z + (int) vq[2];
                                    mejor = mejorDe(mejor, conCeros(probar(nums, i, j, -1, qq, p, 0, imp, c, false), zq));
                                    for (int k = Math.max(i, j) + 1; k < n - 1; k++) {
                                        for (double[] vd : variantes(nums.get(k))) {
                                            double d = vd[0];
                                            if (d < 0 || d >= qq * p) continue;
                                            int zd = zq + (int) vd[2];
                                            mejor = mejorDe(mejor, conCeros(probar(nums, i, j, k, qq, p, d, imp, c + (int) vd[1], false), zd));
                                            if (d > 0) mejor = mejorDe(mejor, conCeros(probar(nums, i, j, k, qq, p, d, imp, c + (int) vd[1], true), zd));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // Dos números: (precio, importe) iguales, (cantidad, importe) o (precio con punto, importe = n × precio).
                // Con más números se prueba igual por pares, castigando los que quedan sin usar.
                for (int i = 0; i < n - 1; i++) {
                    Tok ta = nums.get(i);
                    double a = ta.valor;
                    double castigo = 0;
                    for (int k = 0; k < n - 1; k++) if (k != i) castigo += nums.get(k).deRenglonConTexto ? 0.5 : 1.0;
                    if (n > 2) castigo += 1.0;
                    Set<Tok> usados = Set.of(ta, ti);
                    if (a > 0 && Math.abs(a - imp) <= tolerancia(1) && !ta.cantidadPegada) {
                        mejor = mejorDe(mejor, conCeros(new Solucion(1, a, null, imp, usados, false, ci, true,
                                Math.abs(a - imp), 7 - 0.6 * ci - castigo), zi));
                    } else if (a > 0 && !ta.cantidadPegada && ta.unDecimal && Math.abs(Math.floor(imp * 10 + 1e-6) / 10 - a) <= 0.011) {
                        mejor = mejorDe(mejor, conCeros(new Solucion(1, imp, null, imp, usados, false, ci + 1, true, 0, 6.4 - 0.6 * ci - castigo), zi));
                    } else if (a > 0 && !ta.cantidadPegada && ti.unDecimal && Math.abs(Math.floor(a * 10 + 1e-6) / 10 - imp) <= 0.011) {
                        mejor = mejorDe(mejor, conCeros(new Solucion(1, a, null, a, usados, false, ci + 1, true, 0, 6.4 - 0.6 * ci - castigo), zi));
                    }
                    if (a > 0 && a <= 1000 && (a == Math.floor(a) || a < 1)) {
                        double p = Math.round(imp / a * 10000.0) / 10000.0;   // precio derivado, 4 decimales
                        double puntaje = 7 + (ta.cantidadPegada ? 5 : 0) - 0.5 - 0.6 * ci - castigo;
                        mejor = mejorDe(mejor, conCeros(new Solucion(a, p, null, imp, usados, false, ci, false, 0, puntaje), zi));
                    }
                    // "16.00 32.00": un precio con centavos y un importe que es 2, 3… veces el precio (la cantidad se perdió)
                    if (ta.conPunto && a > 0 && imp > a + 0.001) {
                        long qr = Math.round(imp / a);
                        if (qr >= 2 && qr <= 500 && Math.abs(qr * a - imp) <= tolerancia(qr)) {
                            mejor = mejorDe(mejor, conCeros(new Solucion(qr, a, null, imp, usados, false, ci, true,
                                    Math.abs(qr * a - imp), 7 - 0.4 - 0.6 * ci - castigo), zi));
                        }
                    }
                }
                if (n == 1) {
                    mejor = mejorDe(mejor, conCeros(new Solucion(1, imp, null, imp, Set.of(ti), false, ci, true, 0, 5 - 0.6 * ci), zi));
                }
            }
            return mejor;
        }

        private static Solucion conCeros(Solucion s, int ceros) {
            if (s == null) return null;
            int conjeturas = 0;
            for (Tok t : s.usados()) if (t.conjetura) conjeturas++;
            Solucion r = ceros > 0 ? s.conCeros(ceros) : s;
            return conjeturas > 0 ? r.conConjeturas(conjeturas) : r;
        }

        private static Solucion probar(List<Tok> nums, int i, int j, int k, double q, double p, double d, double imp,
                                       int correcciones, boolean antesDeIva) {
            double bruto = q * p;
            double neto = antesDeIva ? bruto - 1.16 * d : bruto - d;
            double dif = Math.abs(neto - imp);
            if (dif > tolerancia(q)) {
                // "34.5" por 34.52 o "17.2" por 17.26: al OCR se le perdió la última cifra; si lo demás cuadra, se completa
                if (nums.get(nums.size() - 1).unDecimal && Math.abs(Math.floor(neto * 10 + 1e-6) / 10 - imp) <= 0.011) {
                    imp = TextoTicket.centavos(neto);
                    correcciones++;
                } else if (nums.get(j).unDecimal && q > 0) {
                    double p2 = antesDeIva ? (imp + 1.16 * d) / q : (imp + d) / q;
                    if (Math.abs(Math.floor(p2 * 10 + 1e-6) / 10 - p) > 0.011) return null;
                    p = Math.round(p2 * 10000.0) / 10000.0;
                    correcciones++;
                } else {
                    return null;
                }
                dif = 0;
            }
            int n = nums.size();
            Set<Tok> usados = new HashSet<>();
            usados.add(nums.get(i));
            usados.add(nums.get(j));
            usados.add(nums.get(n - 1));
            if (k >= 0) usados.add(nums.get(k));
            double sinUsar = 0;
            for (Tok t : nums) if (!usados.contains(t)) sinUsar += t.deRenglonConTexto ? 0.5 : 1.0;
            Tok tq = nums.get(i);
            double puntaje = 10 - sinUsar - 0.6 * correcciones
                    + (tq.cantidadPegada ? 5 : 0)
                    + (i < j ? 2 : 0)
                    + (q == Math.floor(q) ? 1 : 0)
                    + (dif < 0.005 ? 0.3 : 0)
                    + (k >= 0 ? 0.2 : 0)
                    - (antesDeIva ? 0.3 : 0)
                    - (q > 1000 ? 3 : 0);
            return new Solucion(q, p, k >= 0 ? d : null, imp, usados, antesDeIva, correcciones, false, dif, puntaje);
        }

        private static Solucion mejorDe(Solucion a, Solucion b) {
            if (b == null) return a;
            if (a == null) return b;
            return b.puntaje() > a.puntaje() + 1e-9 ? b : a;
        }
    }

    // ── Formulario llenado a mano ──────────────────────────────────────────

    private static final Pattern F_ARTICULO = Pattern.compile("^(\\d+(?:[.,]\\d+)?)\\s+(\\D.*?)(?:\\s+(\\d+(?:[.,]\\d+)?)\\s+(\\d+(?:[.,]\\d+)?))?$");
    private static final Pattern F_GRUPO = Pattern.compile("^(\\d+)\\s+(\\d+(?:[.,]\\d+)?)\\s+(\\d+(?:[.,]\\d+)?)$");

    /**
     * Formulario de pedido llenado a mano: "5 GIRASOL" (cantidad y producto) y, para un grupo,
     * un renglón "16 5.50 88" (piezas del grupo, precio, importe) que pone precio a los de arriba.
     * El OCR no lee letra manuscrita: esto sirve cuando el usuario escribe el texto.
     */
    /** Cifras mal leídas en un renglón de formulario: "S GIRASOL" → "5 GIRASOL", "l6 S.5O 88" → "16 5.50 88" */
    static String formularioCorregido(String texto) {
        StringBuilder sb = new StringBuilder();
        String[] ps = texto.trim().split("\\s+");
        for (int i = 0; i < ps.length; i++) {
            String p = ps[i];
            String c = TextoTicket.corregirNumero(p);
            if (c.equals(p) && p.length() == 1 && "SOlIB".indexOf(p.charAt(0)) >= 0 && (i == 0 || i == ps.length - 1 || i < 3)) {
                c = switch (p.charAt(0)) { case 'S' -> "5"; case 'O' -> "0"; case 'B' -> "8"; default -> "1"; };
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append(c);
        }
        return sb.toString();
    }

    static TicketLeido interpretarFormulario(List<Ln> lns) {
        TicketLeido t = new TicketLeido();
        t.setFormato(Formato.FORMULARIO);
        StringBuilder sb = new StringBuilder();
        for (Ln l : lns) sb.append(l.texto).append('\n');
        t.setTexto(sb.toString());
        int h = buscarTitulos(lns, 0, lns.size());
        for (int i = 0; i < Math.max(h, 0); i++) t.getEncabezado().add(lns.get(i).texto);
        List<LineaTicket> pendientes = new ArrayList<>();
        for (int i = h + 1; i < lns.size(); i++) {
            Ln ln = lns.get(i);
            String s = TextoTicket.normal(formularioCorregido(ln.texto));
            if (esTotalFinal(ln)) { t.setTotal(ultimoNumero(ln)); continue; }
            Matcher g = F_GRUPO.matcher(s);
            if (g.matches()) {
                double piezas = TextoTicket.numero(g.group(1));
                double precio = TextoTicket.numero(g.group(2));
                double importe = TextoTicket.numero(g.group(3));
                double suma = pendientes.stream().mapToDouble(LineaTicket::getCantidad).sum();
                boolean ok = Math.abs(piezas * precio - importe) <= 0.05 && Math.abs(suma - piezas) < 0.001;
                for (LineaTicket l : pendientes) {
                    l.setPrecioUnitario(precio);
                    l.setImporte(TextoTicket.centavos(l.getCantidad() * precio));
                    if (!ok) { l.setEstado(LineaTicket.Estado.REVISAR); l.setMotivo("El precio del grupo no cuadra con las piezas: revisa."); }
                    else { l.setEstado(LineaTicket.Estado.RECONOCIDO); l.setMotivo(null); }
                }
                pendientes.clear();
                continue;
            }
            Matcher m = F_ARTICULO.matcher(s);
            if (!m.matches()) continue;
            LineaTicket l = new LineaTicket();
            l.setRenglon(ln.idx);
            l.setY(ln.y);
            l.setTextoOriginal(ln.texto);
            l.setCantidad(TextoTicket.numero(m.group(1)));
            l.setDescripcion(m.group(2).trim());
            if (m.group(3) != null) {
                double precio = TextoTicket.numero(m.group(3));
                double importe = TextoTicket.numero(m.group(4));
                l.setPrecioUnitario(precio);
                l.setImporte(importe);
                if (Math.abs(l.getCantidad() * precio - importe) > Solucionador.tolerancia(l.getCantidad())) {
                    l.setEstado(LineaTicket.Estado.REVISAR);
                    l.setMotivo("Cantidad × precio no da el importe: revisa.");
                }
            } else {
                l.setEstado(LineaTicket.Estado.ILEGIBLE);
                l.setMotivo("Falta el precio de este producto.");
                pendientes.add(l);
            }
            t.getLineas().add(l);
        }
        t.setCuadre(cuadrar(t));
        return t;
    }

    // ── Encabezado: formato, folio, fecha, RFC ─────────────────────────────

    static Formato detectarFormato(List<Ln> lns, int desde, int hasta) {
        StringBuilder sb = new StringBuilder();
        for (int i = desde; i < hasta; i++) sb.append(lns.get(i).norm).append('\n');
        String t = sb.toString();
        if (t.contains("CONCEPTO") && (t.contains("DEV.") || t.contains("PART.")) || t.contains("PEDIDOS Y QUEJAS")) return Formato.FORMULARIO;
        if (t.contains("DOCRED") || (t.contains("TOTAL DE PRODUCTOS") && t.contains("APTURA")) || t.contains("REFRESCOS DE JALAPA")) return Formato.RUTA_JALAPA;
        if (t.contains("PT REGALO") || (t.contains("NS") && t.contains("CLAVE") && t.contains("P.UNIT"))) return Formato.NOTA_CON_CLAVE;
        if (t.contains("SUB.T VENTA") || t.contains("PIEZAS EN VENTA")) return Formato.BARCEL;
        if (t.contains("CEDIS#") || (t.contains("CEDIS") && t.contains("PROMOTOR"))) return Formato.LALA;
        if (t.contains("CAJETILLAS")) return Formato.CAJETILLAS;
        return Formato.GENERICO;
    }

    private static final Pattern P_RFC = Pattern.compile("\\b([A-Z&]{3,4})[- ]?([0-9OIl]{6})[- ]?([A-Z0-9]{3})\\b");
    private static final Pattern P_FECHA = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})|(\\d{1,2})([/-])(\\d{1,2})\\5(\\d{4}|\\d{2})");

    private static final String[][] ETIQUETAS_FOLIO = {
            { "FOLIO", "4" }, { "FACTURA", "3" }, { "NOTA DE VENTA", "3" }, { "REMISION", "2" },
            { "PEDIDO", "1" }, { "ADICION", "1" } };

    static void leerEncabezado(TicketLeido t, List<Ln> lns, int desde, int hasta, Formato f) {
        // RFC (el genérico de público en general no identifica a nadie)
        for (int i = desde; i < hasta; i++) {
            Matcher m = P_RFC.matcher(lns.get(i).norm);
            while (m.find()) {
                String rfc = m.group(1) + m.group(2).replace('O', '0').replace('I', '1').replace('l', '1') + m.group(3);
                if (rfc.startsWith("XAXX") || rfc.startsWith("XEXX")) continue;
                if (!t.getRfcs().contains(rfc)) t.getRfcs().add(rfc);
            }
        }
        t.setFolio(buscarFolio(lns, desde, hasta));
        t.setFecha(buscarFecha(lns, desde, hasta, f));
    }

    static String buscarFolio(List<Ln> lns, int desde, int hasta) {
        String mejor = null;
        int prioridadMejor = 0;
        for (int i = desde; i < hasta; i++) {
            Ln ln = lns.get(i);
            for (String[] e : ETIQUETAS_FOLIO) {
                int prio = Integer.parseInt(e[1]);
                if (prio <= prioridadMejor) continue;
                String valor = valorTrasEtiqueta(ln, e[0]);
                if (valor == null) continue;
                // Folio partido en dos renglones por lo angosto del papel
                if (i + 1 < hasta && (prio >= 3) && ln.norm.endsWith(TextoTicket.normal(valor).replace(" ", ""))) {
                    String sig = lns.get(i + 1).texto.trim();
                    if (sig.matches("[A-Za-z0-9_-]{4,}") && sig.matches(".*\\d.*") && valor.length() >= 10) valor = valor + sig;
                }
                mejor = valor;
                prioridadMejor = prio;
            }
        }
        return mejor;
    }

    private static String valorTrasEtiqueta(Ln ln, String etiqueta) {
        String n = ln.norm;
        int pos = -1;
        Matcher m = Pattern.compile("(^|[^A-Z])" + Pattern.quote(etiqueta) + "(?![A-Z])").matcher(n);
        if (m.find()) {
            pos = m.end();
        } else if (etiqueta.equals(etiquetaRecortada(n))) {
            String primera = n.split("[ :]", 2)[0];
            pos = primera.length();
        }
        if (pos < 0) return null;
        String resto = n.substring(pos).replaceFirst("^[\\s.:#]+", "");
        List<String> partes = new ArrayList<>();
        for (String p : resto.split("\\s+")) {
            if (p.isEmpty()) continue;
            if (p.endsWith(":")) break;                                // otra etiqueta
            String limpio = p.replace("#", "");
            // O/I/L/S pegadas a cifras dentro del folio son 0/1/1/5 mal leídos ("6O809" → "60809")
            for (int vuelta = 0; vuelta < 2; vuelta++) {
                limpio = limpio.replaceAll("(?<=\\d)O|O(?=\\d)", "0").replaceAll("(?<=[\\d-])[IL](?=\\d)", "1")
                        .replaceAll("(?<=[\\d-])S(?=\\d)", "5").replaceAll("(?<=[\\d-])B(?=\\d)", "8");
            }
            if (!limpio.matches(".*\\d.*")) break;
            if (P_FECHA_TEXTO.matcher(limpio).matches()) break;
            partes.add(limpio);
        }
        if (partes.isEmpty()) return null;
        String v = String.join(" ", partes);
        if (v.replaceAll("[^0-9]", "").length() < 3) return null;
        // Conservar mayúsculas/minúsculas del original no importa en folios: van normalizados
        return v;
    }

    static LocalDate buscarFecha(List<Ln> lns, int desde, int hasta, Formato f) {
        LocalDate mejor = null;
        int prioridadMejor = 0;
        for (int i = desde; i < hasta; i++) {
            Ln ln = lns.get(i);
            StringBuilder corregido = new StringBuilder();
            for (String p : ln.texto.split("\\s+|(?<=:)")) corregido.append(TextoTicket.corregirNumero(p)).append(' ');
            String textoFecha = TextoTicket.normal(corregido.toString()).replaceAll("(\\d{1,2}-\\d{1,2}) (\\d{4})\\b", "$1-$2");
            Matcher m = P_FECHA.matcher(textoFecha);
            if (!m.find()) continue;
            String et = etiquetaRecortada(ln.norm);
            int prio = ln.norm.contains("FECHA") ? 3
                    : (ln.norm.contains("IMPRESION") || ln.norm.contains("EMISION") || "IMPRESION".equals(et)
                       || "CAPTURA".equals(et) || ln.norm.contains("CAPTURA")) ? 2 : 1;
            if (prio <= prioridadMejor) continue;
            LocalDate d = fechaDe(m, f);
            if (d == null) continue;
            mejor = d;
            prioridadMejor = prio;
        }
        return mejor;
    }

    private static LocalDate fechaDe(Matcher m, Formato f) {
        try {
            if (m.group(1) != null) {
                return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            }
            int a = Integer.parseInt(m.group(4)), b = Integer.parseInt(m.group(6));
            Integer anio = anioPlausible(m.group(7));
            if (anio == null) return null;
            boolean guion = "-".equals(m.group(5));
            // Con diagonal es día/mes (México); con guion, los equipos de ruta imprimen mes-día
            boolean mesPrimero = guion || f == Formato.RUTA_JALAPA;
            if (a > 12) mesPrimero = false;
            if (b > 12) mesPrimero = true;
            int dia = mesPrimero ? b : a, mes = mesPrimero ? a : b;
            LocalDate d = LocalDate.of(anio, mes, dia);
            // Un ticket no puede ser de pasado mañana: si al voltear día y mes queda en el pasado, era al revés
            LocalDate hoy = LocalDate.now();
            if (d.isAfter(hoy.plusDays(1)) && dia <= 12) {
                LocalDate volteada = LocalDate.of(anio, dia, mes);
                if (!volteada.isAfter(hoy.plusDays(1))) return volteada;
            }
            return d;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Año de dos o cuatro cifras. "2028" o "2925" leídos en 2026 son un 6 o un 0 confundidos: se prueba 8→6,
     * 8→0, 6→0, 9→0 y se acepta el que caiga entre hace 3 años y el que viene; si ninguno, null.
     */
    static Integer anioPlausible(String texto) {
        int hoy = LocalDate.now().getYear();
        List<String> formas = new ArrayList<>(List.of(texto));
        formas.add(texto.replace('8', '6'));
        formas.add(texto.replace('8', '0'));
        formas.add(texto.replace('6', '0'));
        formas.add(texto.replace('9', '0'));
        formas.add(texto.replace('8', '6').replace('9', '0'));
        for (String f : formas) {
            int anio = Integer.parseInt(f);
            if (anio < 100) anio += 2000;
            if (anio >= hoy - 3 && anio <= hoy + 1) return anio;
        }
        return null;
    }

    // ── Pie: subtotal, descuento, impuestos, cargos, total ──────────────────

    private static final Pattern P_NUMERO = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    static Double ultimoNumero(Ln ln) {
        Matcher m = P_NUMERO.matcher(textoNumerico(ln));
        String ultimo = null;
        while (m.find()) ultimo = m.group();
        if (ultimo == null) return null;
        return TextoTicket.numero(ultimo.endsWith(",") ? ultimo.substring(0, ultimo.length() - 1) : ultimo);
    }

    private static String textoNumerico(Ln ln) {
        StringBuilder sb = new StringBuilder();
        for (String p : unirNumerosPartidos(ln.texto).split("\\s+|(?<=:)")) sb.append(TextoTicket.corregirNumero(p)).append(' ');
        return sb.toString();
    }

    private static final Pattern P_DINERO = Pattern.compile("\\d[\\d,]*[.,]\\d{2}(?!\\d)");

    /** Valor de un renglón del pie: el último con centavos ("Costo Distribucion: 11.60 1 j 8" → 11.60); si no hay, el último número */
    static Double valorPie(Ln ln) {
        Matcher m = P_DINERO.matcher(textoNumerico(ln));
        String ultimo = null;
        while (m.find()) ultimo = m.group();
        return ultimo != null ? TextoTicket.numero(ultimo) : ultimoNumero(ln);
    }

    /** "S9.31" se corrige a $9.31, pero pudo ser 59.31: la otra lectura, si existe, para decidir con las cuentas */
    static Double alternativaConCinco(Ln ln, Double v) {
        if (v == null) return null;
        for (String p : ln.texto.split("\\s+")) {
            if (!p.matches("S\\d+\\.\\d{2}")) continue;
            Double leido = TextoTicket.numero(p.substring(1));
            if (leido != null && Math.abs(leido - v) < 0.001) return TextoTicket.numero("5" + p.substring(1));
        }
        return null;
    }

    /** Números con centavos de un renglón (para el rescate aritmético del pie) */
    private static List<Double> dineros(Ln ln) {
        List<Double> out = new ArrayList<>();
        Matcher m = P_DINERO.matcher(textoNumerico(ln));
        while (m.find()) { Double v = TextoTicket.numero(m.group()); if (v != null) out.add(v); }
        return out;
    }

    /** Lee el pie y devuelve todos los importes con centavos que aparecen en él (para el rescate aritmético) */
    static List<Double> leerPie(TicketLeido t, List<Ln> lns, int desde, int hasta, int finItems) {
        Double subtotalVenta = null, subtotal = null, descuento = null, iva = null, ieps = null, impuestos = null;
        Double cargos = null, total = null, piezas = null, cajetillas = null, impuestosAlt = null;
        List<Double> numerosPie = new ArrayList<>();
        int ultimoArticulo = -1;
        for (LineaTicket l : t.getLineas()) ultimoArticulo = Math.max(ultimoArticulo, indiceDe(lns, l.getRenglon()));
        for (int i = desde; i < hasta; i++) {
            Ln ln = lns.get(i);
            String n = ln.norm;
            String et = etiquetaRecortada(n);
            // Candidatos para el rescate aritmético: importes del pie, menos los de conteos (piezas, paquetes, cajetillas)
            if (i > ultimoArticulo && (esPie(ln) || esTotalFinal(ln) || !ln.norm.matches(".*[A-Z]{3,}.*"))
                    && !P_TOTAL_NO.matcher(n).find() && !n.matches(".*(\\bPIEZAS\\b|\\bCANTIDAD\\b|CAJETILLAS|PAQUETES).*")) {
                numerosPie.addAll(dineros(ln));
            }
            if (esTotalFinal(ln)) {
                if (total == null || i == finItems) total = valorPie(ln);
                continue;
            }
            if (!esPie(ln)) continue;
            Double v = valorPie(ln);
            if (v == null) continue;
            if (n.matches(".*\\bSUB\\.?\\s*T\\.?\\s+VENTA.*")) { if (subtotalVenta == null) subtotalVenta = v; }
            else if (n.contains("SUBTOTAL") || n.contains("SUB TOTAL") || "SUBTOTAL".equals(et) || n.contains("TOTAL VENTAS")
                    || n.matches(".*\\bIMPORTE\\s*:.*")) { if (subtotal == null) subtotal = v; }
            else if (n.matches(".*(\\bDESCUENTOS?\\b|DESC/BONIF).*") || "DESCUENTO".equals(et)
                    || n.matches("^-?\\s*\\(?\\d+\\s*%\\)?\\s.*")) { if (descuento == null) descuento = Math.abs(v); }
            else if (n.matches(".*\\bI\\.?E\\.?P\\.?S\\.?(\\b|=|:).*")) { if (ieps == null) ieps = v; }
            else if (n.matches(".*\\bI\\.?V\\.?A\\.?(\\b|=|:).*")) { if (iva == null) iva = v; }
            else if (n.contains("IMPUESTOS") || "IMPUESTOS".equals(et)) { if (impuestos == null) { impuestos = v; impuestosAlt = alternativaConCinco(ln, v); } }
            else if (n.matches(".*(COSTO\\s+(DE\\s+)?DISTRIBUCION|\\bFLETE\\b|COSTO DE ENVIO).*")) { cargos = (cargos == null ? 0 : cargos) + v; }
            else if (n.contains("CAJETILLAS")) { cajetillas = v; }
            else if (n.matches(".*(\\bPIEZAS\\b|TOTAL DE PRODUCTOS|\\bCANTIDAD\\s*:).*")) { if (piezas == null) piezas = v; }
        }
        Double st = subtotalVenta != null ? subtotalVenta : subtotal;
        if (impuestosAlt != null && total != null) {
            // "S9.31": ¿$9.31 o 59.31? Lo decide la cuenta: subtotal + impuestos = total, o el IVA ya incluido en el total
            if (explicaImpuesto(impuestosAlt, st, total) && !explicaImpuesto(impuestos, st, total)) impuestos = impuestosAlt;
        }
        // Ceros de matriz de puntos en el pie ("DESCUENTO: 8.88"): si subtotal − descuento = total exige 0, era 0
        if (descuento != null && descuento > 0 && st != null && total != null && Math.abs(st - total) < 0.011
                && !Solucionador.conCerosCorregidos(String.format(Locale.ROOT, "%.2f", descuento)).isEmpty()) {
            for (double[] c : Solucionador.conCerosCorregidos(String.format(Locale.ROOT, "%.2f", descuento))) if (c[0] == 0) descuento = 0.0;
        }
        t.setSubtotal(st);
        t.setDescuento(descuento);
        t.setIva(iva);
        t.setIeps(ieps);
        if (impuestos != null) t.setImpuestos(impuestos);
        else if (iva != null || ieps != null) t.setImpuestos(TextoTicket.centavos((iva == null ? 0 : iva) + (ieps == null ? 0 : ieps)));
        t.setCargos(cargos);
        t.setTotal(total);
        t.setPiezas(cajetillas != null ? cajetillas : piezas);
        if (cajetillas != null) t.getAvisos().add("CAJETILLAS");
        return numerosPie;
    }

    private static boolean explicaImpuesto(Double imp, Double subtotal, Double total) {
        if (imp == null || total == null) return false;
        if (subtotal != null && Math.abs(subtotal + imp - total) <= 0.02) return true;
        if (subtotal != null && Math.abs(subtotal - subtotal / 1.16 - imp) <= 0.02) return true;
        return Math.abs(total - total / 1.16 - imp) <= 0.02 || Math.abs(total - total / 1.08 - imp) <= 0.02;
    }

    // ── Empaque (piezas por unidad) ─────────────────────────────────────────

    private static final Pattern E_GAMESA = Pattern.compile("X(\\d{1,3})X1\\b");
    private static final Pattern E_PIEZAS = Pattern.compile("\\b(\\d{1,3})\\s*PZS?\\b");
    private static final Pattern E_DIAGONAL = Pattern.compile("(?:^|\\s)(\\d{1,3})/");

    /** Pista de piezas por unidad sacada de la descripción; el usuario la confirma una vez y se recuerda */
    public static Integer empaqueDe(String descripcion) {
        if (descripcion == null) return null;
        String d = TextoTicket.normal(descripcion);
        for (Pattern p : List.of(E_GAMESA, E_PIEZAS, E_DIAGONAL)) {
            Matcher m = p.matcher(d);
            if (m.find()) {
                int n = Integer.parseInt(m.group(1));
                if (n >= 2) return n;
            }
        }
        return null;
    }

    static void completarEmpaques(TicketLeido t) {
        for (LineaTicket l : t.getLineas()) l.setEmpaque(empaqueDe(l.getDescripcion()));
        // Cigarros: "Total cajetillas: 5" con un solo renglón de 0.05 paquetes → 100 cajetillas por paquete
        boolean cajetillas = t.getAvisos().remove("CAJETILLAS");
        if (cajetillas && t.getPiezas() != null && t.getLineas().size() == 1) {
            LineaTicket l = t.getLineas().get(0);
            if (l.getCantidad() > 0) {
                long e = Math.round(t.getPiezas() / l.getCantidad());
                if (e >= 2 && Math.abs(e * l.getCantidad() - t.getPiezas()) < 0.01) l.setEmpaque((int) e);
            }
        }
    }

    // ── Cuadre ─────────────────────────────────────────────────────────────

    public static Cuadre cuadrar(TicketLeido t) {
        return cuadrar(t, List.of());
    }

    /**
     * Cuadre; si no cuadra, se intenta con los números del pie ({@code numerosPie}): la foto inclinada deja el
     * total un renglón arriba o abajo de su etiqueta, o la etiqueta no se leyó. Se acepta un número del pie como
     * total si es la suma de los renglones, o la suma más otro número del pie (impuestos), o la suma con IEPS 8 %
     * o IVA 16 %.
     */
    public static Cuadre cuadrar(TicketLeido t, List<Double> numerosPie) {
        Cuadre c = cuadrarTalCual(t);
        if (!c.cuadra() && !numerosPie.isEmpty() && t.sumaLineas() > 0) {
            Cuadre r = rescatarPie(t, numerosPie);
            if (r != null) return r;
        }
        // ¿Al total o al subtotal se le perdió el punto decimal (48041 en vez de 480.41)?
        if (!c.cuadra()) {
            Double total = t.getTotal(), subtotal = t.getSubtotal();
            if (total != null && total >= 100 && total == Math.floor(total)) {
                t.setTotal(total / 100.0);
                Cuadre otra = cuadrarTalCual(t);
                if (otra.cuadra()) { t.getAvisos().add("Al total le faltaba el punto decimal: se leyó " + dinero(total / 100.0) + "."); return otra; }
                t.setTotal(total);
            }
            if (total == null && subtotal != null && subtotal >= 100 && subtotal == Math.floor(subtotal)) {
                t.setSubtotal(subtotal / 100.0);
                Cuadre otra = cuadrarTalCual(t);
                if (otra.cuadra()) return otra;
                t.setSubtotal(subtotal);
            }
            // ¿A un renglón le faltó el punto (46881 en vez de 468.81)? Si con eso cuadra, se corrige en amarillo
            for (LineaTicket l : t.getLineas()) {
                double imp = l.getImporte();
                if (imp < 100 || imp != Math.floor(imp)) continue;
                l.setImporte(imp / 100.0);
                Double precio = l.getPrecioUnitario();
                if (precio != null && Math.abs(l.getCantidad() * precio - imp) < 0.05 * imp) l.setPrecioUnitario(precio / 100.0);
                Cuadre otra = cuadrarTalCual(t);
                if (otra.cuadra()) {
                    l.setEstado(LineaTicket.Estado.REVISAR);
                    l.setMotivo("Al importe le faltaba el punto decimal: se leyó " + dinero(imp / 100.0) + ". Revísalo.");
                    return cuadrarTalCual(t);
                }
                l.setImporte(imp);
                l.setPrecioUnitario(precio);
            }
        } else if (t.getSubtotal() != null && Math.abs(t.getSubtotal() - c.sumaLineas() * 100) < 1 && c.sumaLineas() > 0) {
            t.setSubtotal(t.getSubtotal() / 100.0);                   // subtotal sin punto que sí es la suma
        }
        return c;
    }

    private static Cuadre rescatarPie(TicketLeido t, List<Double> numerosPie) {
        double suma = t.sumaLineas();
        Double totalAntes = t.getTotal(), impAntes = t.getImpuestos();
        List<Double> candidatos = new ArrayList<>(numerosPie);
        candidatos.sort(Comparator.reverseOrder());
        double mayorImporte = t.getLineas().stream().mapToDouble(LineaTicket::getImporte).max().orElse(0);
        candidatos.removeIf(c -> c < 1 || c < mayorImporte - 0.005);
        for (Double candidato : candidatos) {
            double tol = 0.05 + 0.002 * candidato;
            if (candidato < suma - tol) continue;
            if (Math.abs(candidato - suma) <= tol) return adoptarTotal(t, candidato, null, totalAntes, impAntes);
            for (Double otro : numerosPie) {
                if (otro.equals(candidato) || otro <= 0) continue;
                if (Math.abs(suma + otro - candidato) <= tol) return adoptarTotal(t, candidato, otro, totalAntes, impAntes);
            }
        }
        for (double factor : new double[] { 1.08, 1.16 }) {
            for (Double candidato : candidatos) {
                double tol = 0.05 + 0.002 * candidato;
                if (Math.abs(suma * factor - candidato) <= tol) {
                    return adoptarTotal(t, candidato, TextoTicket.centavos(candidato - suma), totalAntes, impAntes);
                }
            }
        }
        return null;
    }

    private static Cuadre adoptarTotal(TicketLeido t, double total, Double impuestos, Double totalAntes, Double impAntes) {
        boolean cambioTotal = totalAntes == null || Math.abs(totalAntes - total) > 0.005;
        if (cambioTotal) t.setTotal(total);
        if (impuestos != null && (impAntes == null || Math.abs(impAntes - impuestos) > 0.005)) t.setImpuestos(impuestos);
        Cuadre c = cuadrarTalCual(t);
        if (!c.cuadra()) {
            t.setTotal(totalAntes);
            t.setImpuestos(impAntes);
            return null;
        }
        if (cambioTotal) t.getAvisos().add("El total no se leyó junto a su etiqueta: se tomó " + dinero(total) + " porque cuadra con la suma. Revísalo.");
        return c;
    }

    private static Cuadre cuadrarTalCual(TicketLeido t) {
        double suma = t.sumaLineas();
        List<Integer> sospechosos = new ArrayList<>();
        for (int i = 0; i < t.getLineas().size(); i++) {
            if (t.getLineas().get(i).getEstado() != LineaTicket.Estado.RECONOCIDO) sospechosos.add(i);
        }
        Double total = t.getTotal();
        double imp = t.getImpuestos() == null ? 0 : t.getImpuestos();
        double cargos = t.getCargos() == null ? 0 : t.getCargos();
        double desc = t.getDescuento() == null ? 0 : t.getDescuento();
        if (total != null && total > 0) {
            double tol = 0.05 + 0.002 * total;
            if (Math.abs(total - suma) <= tol) {
                return new Cuadre(suma, total, true, true, 1.0, TextoTicket.centavos(total - suma),
                        "La suma cuadra con el total del ticket.", sospechosos);
            }
            double[][] explicaciones = { { imp, cargos, -desc }, { imp, cargos, 0 }, { 0, cargos, -desc }, { 0, cargos, 0 },
                    { imp, 0, -desc }, { 0, 0, -desc } };
            for (double[] e : explicaciones) {
                double esperado = suma + e[0] + e[1] + e[2];
                if ((e[0] != 0 || e[1] != 0 || e[2] != 0) && Math.abs(total - esperado) <= tol) {
                    StringBuilder msg = new StringBuilder("La suma cuadra con el total del ticket");
                    List<String> partes = new ArrayList<>();
                    if (e[0] != 0) partes.add("impuestos " + dinero(e[0]));
                    if (e[1] != 0) partes.add("cargos " + dinero(e[1]));
                    if (e[2] != 0) partes.add("descuento " + dinero(-e[2]));
                    msg.append(" (").append(String.join(", ", partes)).append(" se reparten en el costo de cada producto).");
                    return new Cuadre(suma, total, true, e[0] == 0, total / suma, TextoTicket.centavos(total - esperado),
                            msg.toString(), sospechosos);
                }
            }
            double falta = TextoTicket.centavos(total - suma - imp - cargos + desc);
            double faltaSimple = TextoTicket.centavos(total - suma);
            double dif = Math.abs(falta) < Math.abs(faltaSimple) ? falta : faltaSimple;
            // ¿Un renglón con el punto decimal perdido o mal leído explica la diferencia?
            for (int i = 0; i < t.getLineas().size(); i++) {
                LineaTicket l = t.getLineas().get(i);
                if (l.getPrecioUnitario() != null) {
                    double debido = l.getCantidad() * l.getPrecioUnitario() - (l.getDescuento() == null ? 0 : l.getDescuento());
                    if (Math.abs((debido - l.getImporte()) - dif) <= tol && !sospechosos.contains(i)) sospechosos.add(i);
                }
            }
            String msg = dif > 0
                    ? "Faltan " + dinero(dif) + " para llegar al total del ticket (" + dinero(total) + "): revisa los renglones en amarillo o si falta alguno."
                    : "Sobran " + dinero(-dif) + " contra el total del ticket (" + dinero(total) + "): revisa los renglones en amarillo o si alguno está repetido.";
            return new Cuadre(suma, total, false, true, 1.0, dif, msg, sospechosos);
        }
        if (t.getSubtotal() != null && t.getSubtotal() > 0) {
            double st = t.getSubtotal();
            boolean ok = Math.abs(st - suma) <= 0.05 + 0.002 * st;
            return new Cuadre(suma, st, ok, true, 1.0, TextoTicket.centavos(st - suma), ok
                    ? "La suma cuadra con el subtotal del ticket (no se ve el total)."
                    : "No se ve el total y la suma no da el subtotal: revisa los importes.", sospechosos);
        }
        return new Cuadre(suma, null, false, true, 1.0, 0, "El ticket no muestra el total: revisa que los importes estén bien.", sospechosos);
    }

    private static String dinero(double v) {
        return String.format(Locale.ROOT, "$%,.2f", v);
    }

    /** Para pruebas: lectura de una fecha suelta */
    static LocalDate fechaParaPruebas(String texto, Formato f) {
        Matcher m = P_FECHA.matcher(texto);
        return m.find() ? fechaDe(m, f) : null;
    }
}
