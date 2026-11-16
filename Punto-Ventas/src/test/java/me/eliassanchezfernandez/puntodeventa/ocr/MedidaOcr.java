package me.eliassanchezfernandez.puntodeventa.ocr;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Medidas de precisión de una lectura contra la transcripción a mano (solo renglones de
 * artículos y totales, que es lo que le importa al intérprete):
 * <ul>
 *   <li><b>CER por renglón</b>: para cada renglón esperado, la distancia de edición mínima contra
 *       cualquier tramo del texto leído (alineación semiglobal; los saltos de renglón del OCR cuestan
 *       1 como cualquier carácter). Se ignoran mayúsculas, acentos y espacios. Así el encabezado,
 *       la basura del fondo y el orden de los renglones no cuentan: solo si lo esperado está.</li>
 *   <li><b>Importes</b>: de las cifras con dos decimales del ticket ("15.56", "$1,220" no), cuántas
 *       aparecen idénticas en lo leído (multiconjunto).</li>
 * </ul>
 */
final class MedidaOcr {

    record Medida(int errores, int caracteres, int importesEsperados, int importesLeidos) {
        double cer() { return caracteres == 0 ? 0 : errores / (double) caracteres; }
        double importes() { return importesEsperados == 0 ? 1 : importesLeidos / (double) importesEsperados; }
        Medida mas(Medida o) {
            return new Medida(errores + o.errores, caracteres + o.caracteres,
                    importesEsperados + o.importesEsperados, importesLeidos + o.importesLeidos);
        }
        static Medida cero() { return new Medida(0, 0, 0, 0); }
    }

    private static final Pattern IMPORTE = Pattern.compile("\\d+[.,]\\d{2}(?!\\d)");

    private MedidaOcr() { }

    static String normalizar(String s) {
        String sinAcentos = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinAcentos.toUpperCase().replaceAll("\\s+", "");
    }

    static Medida medir(List<String> esperados, String leido) {
        StringBuilder sb = new StringBuilder();
        for (String l : leido.split("\n")) {
            String n = normalizar(l);
            if (n.isEmpty()) continue;
            if (sb.length() > 0) sb.append('|');
            sb.append(n);
        }
        String texto = sb.toString();
        int errores = 0, caracteres = 0;
        for (String e : esperados) {
            String n = normalizar(e);
            if (n.isEmpty()) continue;
            caracteres += n.length();
            errores += semiglobal(n, texto);
        }
        Map<String, Integer> leidos = contarImportes(leido);
        int esperadosImp = 0, encontrados = 0;
        for (String e : esperados) {
            Matcher m = IMPORTE.matcher(e);
            while (m.find()) {
                esperadosImp++;
                String imp = m.group().replace(',', '.');
                Integer n = leidos.get(imp);
                if (n != null && n > 0) { encontrados++; leidos.put(imp, n - 1); }
            }
        }
        return new Medida(errores, caracteres, esperadosImp, encontrados);
    }

    private static Map<String, Integer> contarImportes(String texto) {
        Map<String, Integer> m = new HashMap<>();
        Matcher mt = IMPORTE.matcher(texto);
        while (mt.find()) m.merge(mt.group().replace(',', '.'), 1, Integer::sum);
        return m;
    }

    /** Distancia de edición mínima de {@code patron} contra cualquier subcadena de {@code texto} */
    static int semiglobal(String patron, String texto) {
        int m = patron.length(), n = texto.length();
        int[] prev = new int[n + 1], cur = new int[n + 1];
        // fila 0: empezar en cualquier posición del texto sin costo
        for (int i = 1; i <= m; i++) {
            cur[0] = i;
            char c = patron.charAt(i - 1);
            for (int j = 1; j <= n; j++) {
                int sust = prev[j - 1] + (c == texto.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(sust, Math.min(prev[j] + 1, cur[j - 1] + 1));
            }
            int[] t = prev; prev = cur; cur = t;
        }
        int mejor = m;
        for (int j = 0; j <= n; j++) mejor = Math.min(mejor, prev[j]);
        return mejor;
    }

    /**
     * Transcripciones: secciones "== nombre-de-archivo" seguidas de los renglones esperados.
     * Renglones que empiezan con "#" son comentarios; "#MANUSCRITO" marca una nota a mano.
     */
    static Map<String, List<String>> leerVerdad(String contenido) {
        Map<String, List<String>> m = new HashMap<>();
        List<String> actual = null;
        for (String l : contenido.split("\r?\n")) {
            if (l.startsWith("== ")) {
                actual = new ArrayList<>();
                m.put(l.substring(3).trim(), actual);
            } else if (actual != null && l.startsWith("#MANUSCRITO")) {
                actual.add("#MANUSCRITO");
            } else if (actual != null && !l.startsWith("#") && !l.isBlank()) {
                actual.add(l.trim());
            }
        }
        return m;
    }
}
