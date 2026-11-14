package me.eliassanchezfernandez.puntodeventa.importacion;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lector mínimo de libros de Excel (.xlsx) con el XML del JDK (StAX), sin
 * Apache POI (~10 MB) ni dependencias nuevas: a este programa le basta leer
 * texto y números de la primera hoja con datos.
 *
 * Soporta cadenas compartidas y en línea, texto enriquecido, números (se
 * escriben sin notación científica para que los códigos de barras largos no se
 * conviertan en "7.5E12"), booleanos y celdas vacías. Las fechas llegan como su
 * número de serie (no hay fechas en un catálogo de productos). Los .xls binarios
 * (Excel 97-2003) no se leen: hay que guardarlos como .xlsx o CSV.
 *
 * Protección: entidades externas deshabilitadas (XXE) y tope de bytes
 * descomprimidos por entrada (bombas zip).
 */
public final class LectorXlsx {

    /** Tope de bytes descomprimidos por parte del libro */
    static final long MAXIMO_DESCOMPRIMIDO = 256L * 1024 * 1024;

    private LectorXlsx() { }

    public static TablaImportada leer(Path archivo) throws IOException {
        try (ZipFile zip = new ZipFile(archivo.toFile())) {
            if (zip.getEntry("xl/workbook.xml") == null) {
                throw new IOException("El archivo no es un libro de Excel (.xlsx) válido. "
                        + "Si es un .xls antiguo, ábrelo en Excel y guárdalo como .xlsx o CSV.");
            }
            Map<String, String> hojas = hojas(zip);                 // nombre → ruta dentro del zip
            List<String> compartidas = cadenasCompartidas(zip);
            for (Map.Entry<String, String> hoja : hojas.entrySet()) {
                ZipEntry entrada = zip.getEntry(hoja.getValue());
                if (entrada == null) continue;
                List<List<String>> filas = leerHoja(zip, entrada, compartidas);
                if (filas.isEmpty()) continue;
                int columnas = 0;
                for (List<String> f : filas) columnas = Math.max(columnas, f.size());
                return new TablaImportada(archivo.getFileName().toString(), filas, columnas,
                        "Excel, hoja \"" + hoja.getKey() + "\"");
            }
            return new TablaImportada(archivo.getFileName().toString(), List.of(), 0, "Excel, sin datos");
        } catch (XMLStreamException e) {
            throw new IOException("No se pudo leer el libro de Excel: " + e.getMessage(), e);
        }
    }

    // ── Partes del libro ─────────────────────────────────────────────────

    private static Map<String, String> hojas(ZipFile zip) throws IOException, XMLStreamException {
        Map<String, String> relaciones = new LinkedHashMap<>();   // rId → ruta
        ZipEntry rels = zip.getEntry("xl/_rels/workbook.xml.rels");
        if (rels != null) {
            try (InputStream in = abrir(zip, rels)) {
                XMLStreamReader r = lector(in);
                while (r.hasNext()) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(r.getLocalName())) {
                        String id = r.getAttributeValue(null, "Id");
                        String destino = r.getAttributeValue(null, "Target");
                        if (id != null && destino != null) relaciones.put(id, normalizarRuta(destino));
                    }
                }
            }
        }
        Map<String, String> hojas = new LinkedHashMap<>();
        try (InputStream in = abrir(zip, zip.getEntry("xl/workbook.xml"))) {
            XMLStreamReader r = lector(in);
            int sinNombre = 0;
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && "sheet".equals(r.getLocalName())) {
                    String nombre = r.getAttributeValue(null, "name");
                    String rId = atributoRel(r, "id");
                    String ruta = rId != null ? relaciones.get(rId) : null;
                    if (ruta == null) ruta = "xl/worksheets/sheet" + (hojas.size() + 1) + ".xml";
                    hojas.put(nombre != null ? nombre : "Hoja" + (++sinNombre), ruta);
                }
            }
        }
        if (hojas.isEmpty()) hojas.put("Hoja1", "xl/worksheets/sheet1.xml");
        return hojas;
    }

    /** El atributo r:id puede venir con cualquier prefijo: se busca por nombre local */
    private static String atributoRel(XMLStreamReader r, String local) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (local.equals(r.getAttributeLocalName(i))) return r.getAttributeValue(i);
        }
        return null;
    }

    private static String normalizarRuta(String destino) {
        String d = destino.startsWith("/") ? destino.substring(1) : "xl/" + destino;
        return d.replace("xl/xl/", "xl/");
    }

    private static List<String> cadenasCompartidas(ZipFile zip) throws IOException, XMLStreamException {
        List<String> cadenas = new ArrayList<>();
        ZipEntry entrada = zip.getEntry("xl/sharedStrings.xml");
        if (entrada == null) return cadenas;
        try (InputStream in = abrir(zip, entrada)) {
            XMLStreamReader r = lector(in);
            StringBuilder actual = null;
            boolean enTexto = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    if ("si".equals(r.getLocalName())) actual = new StringBuilder();
                    else if ("t".equals(r.getLocalName()) && actual != null) enTexto = true;
                } else if ((ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) && enTexto) {
                    actual.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if ("t".equals(r.getLocalName())) enTexto = false;
                    else if ("si".equals(r.getLocalName()) && actual != null) {
                        cadenas.add(actual.toString());
                        actual = null;
                    }
                }
            }
        }
        return cadenas;
    }

    private static List<List<String>> leerHoja(ZipFile zip, ZipEntry entrada, List<String> compartidas)
            throws IOException, XMLStreamException {
        List<List<String>> filas = new ArrayList<>();
        try (InputStream in = abrir(zip, entrada)) {
            XMLStreamReader r = lector(in);
            List<String> fila = null;
            int columnaActual = -1;
            String tipo = null;
            StringBuilder valor = null;
            boolean enValor = false;
            boolean enTextoEnLinea = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "row" -> fila = new ArrayList<>();
                        case "c" -> {
                            String ref = r.getAttributeValue(null, "r");
                            columnaActual = ref != null ? indiceColumna(ref) : (fila == null ? 0 : fila.size());
                            tipo = r.getAttributeValue(null, "t");
                            valor = new StringBuilder();
                        }
                        case "v" -> enValor = valor != null;
                        case "t" -> enTextoEnLinea = valor != null && "inlineStr".equals(tipo);
                        default -> { }
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                    if ((enValor || enTextoEnLinea) && valor != null) valor.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "v" -> enValor = false;
                        case "t" -> enTextoEnLinea = false;
                        case "c" -> {
                            if (fila != null && valor != null) {
                                colocar(fila, columnaActual, textoCelda(tipo, valor.toString(), compartidas));
                            }
                            valor = null;
                        }
                        case "row" -> {
                            if (fila != null && tieneTexto(fila)) filas.add(fila);
                            fila = null;
                        }
                        default -> { }
                    }
                }
            }
        }
        return filas;
    }

    // ── Celdas ───────────────────────────────────────────────────────────

    static String textoCelda(String tipo, String crudo, List<String> compartidas) {
        if (tipo == null || tipo.equals("n")) return numeroATexto(crudo);
        return switch (tipo) {
            case "s" -> {
                try {
                    yield compartidas.get(Integer.parseInt(crudo.trim()));
                } catch (RuntimeException e) {
                    yield "";
                }
            }
            case "b" -> "1".equals(crudo.trim()) ? "Sí" : "No";
            case "e" -> "";                       // error de fórmula (#N/A): celda vacía
            default -> crudo;                     // "str" (resultado de fórmula) e "inlineStr"
        };
    }

    /** "7.501E12" → "7501000000000"; "12.50" → "12.5"; "3.0" → "3"; otra cosa → tal cual */
    static String numeroATexto(String crudo) {
        String t = crudo.trim();
        if (t.isEmpty()) return "";
        try {
            BigDecimal bd = new BigDecimal(t).stripTrailingZeros();
            if (bd.scale() < 0) bd = bd.setScale(0);
            return bd.toPlainString();
        } catch (NumberFormatException e) {
            return t;
        }
    }

    /** "A1" → 0, "AB12" → 27 */
    static int indiceColumna(String referencia) {
        int n = 0;
        for (int i = 0; i < referencia.length(); i++) {
            char c = referencia.charAt(i);
            if (c >= 'A' && c <= 'Z') n = n * 26 + (c - 'A' + 1);
            else if (c >= 'a' && c <= 'z') n = n * 26 + (c - 'a' + 1);
            else break;
        }
        return Math.max(0, n - 1);
    }

    private static void colocar(List<String> fila, int columna, String texto) {
        while (fila.size() <= columna) fila.add("");
        fila.set(columna, texto);
    }

    private static boolean tieneTexto(List<String> fila) {
        for (String s : fila) if (s != null && !s.isBlank()) return true;
        return false;
    }

    // ── Infraestructura ──────────────────────────────────────────────────

    private static XMLStreamReader lector(InputStream in) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_COALESCING, true);
        return f.createXMLStreamReader(in);
    }

    private static InputStream abrir(ZipFile zip, ZipEntry entrada) throws IOException {
        return new FilterInputStream(zip.getInputStream(entrada)) {
            private long leidos;

            @Override public int read() throws IOException {
                int b = super.read();
                if (b >= 0) contar(1);
                return b;
            }

            @Override public int read(byte[] buf, int off, int len) throws IOException {
                int n = super.read(buf, off, len);
                if (n > 0) contar(n);
                return n;
            }

            private void contar(int n) throws IOException {
                leidos += n;
                if (leidos > MAXIMO_DESCOMPRIMIDO) throw new IOException("El libro de Excel es demasiado grande para leerlo.");
            }
        };
    }
}
