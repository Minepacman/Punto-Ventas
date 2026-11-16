package me.eliassanchezfernandez.puntodeventa.ocr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Convierte la salida TSV de Tesseract (una fila por palabra con caja y confianza) en renglones
 * del ticket. Tesseract a veces parte un mismo renglón impreso en dos "líneas" (la descripción a
 * la izquierda y los importes a la derecha quedan en bloques distintos): aquí se vuelven a unir
 * las líneas que comparten altura y no se enciman a lo ancho, y se ordenan de arriba abajo.
 */
public final class TsvTesseract {

    /** Una palabra con su caja (en píxeles de la imagen que se le dio a Tesseract) */
    record Palabra(String texto, double confianza, int x, int y, int ancho, int alto) {
        int derecha() { return x + ancho; }
        int abajo() { return y + alto; }
    }

    private static final class Fila {
        final List<Palabra> palabras = new ArrayList<>();
        int arriba = Integer.MAX_VALUE, abajo = Integer.MIN_VALUE;

        void agregar(List<Palabra> ps) {
            for (Palabra p : ps) {
                palabras.add(p);
                arriba = Math.min(arriba, p.y());
                abajo = Math.max(abajo, p.abajo());
            }
        }

        int alto() { return Math.max(1, abajo - arriba); }

        boolean seEncimaALoAncho(List<Palabra> otras) {
            for (Palabra a : otras) {
                for (Palabra b : palabras) {
                    int encima = Math.min(a.derecha(), b.derecha()) - Math.max(a.x(), b.x());
                    if (encima > Math.min(a.ancho(), b.ancho()) * 0.3) return true;
                }
            }
            return false;
        }
    }

    private TsvTesseract() { }

    /**
     * Renglones a partir del TSV. {@code escala} es la ampliación que aplicó el preproceso: la y
     * del renglón se devuelve en píxeles de la foto enderezada sin ampliar.
     */
    public static List<Renglon> renglones(String tsv, double escala) {
        // 1) palabras agrupadas en las líneas de Tesseract (bloque, párrafo, línea), en su orden
        Map<String, List<Palabra>> lineas = new LinkedHashMap<>();
        if (tsv != null) {
            for (String linea : tsv.split("\r?\n")) {
                String[] c = linea.split("\t", -1);
                if (c.length < 12 || !"5".equals(c[0])) continue;
                String texto = c[11].trim();
                if (texto.isEmpty()) continue;
                try {
                    double conf = Double.parseDouble(c[10]);
                    if (conf < 0) continue;
                    String clave = c[1] + "/" + c[2] + "/" + c[3] + "/" + c[4];
                    lineas.computeIfAbsent(clave, k -> new ArrayList<>()).add(new Palabra(texto, conf / 100.0,
                            Integer.parseInt(c[6]), Integer.parseInt(c[7]), Integer.parseInt(c[8]), Integer.parseInt(c[9])));
                } catch (NumberFormatException e) {
                    // fila dañada
                }
            }
        }
        // 2) unir líneas a la misma altura que no se enciman a lo ancho
        List<List<Palabra>> porArriba = new ArrayList<>(lineas.values());
        porArriba.sort(Comparator.comparingInt(ps -> centro(ps)));
        List<Fila> filas = new ArrayList<>();
        for (List<Palabra> ps : porArriba) {
            int arriba = ps.stream().mapToInt(Palabra::y).min().orElse(0);
            int abajo = ps.stream().mapToInt(Palabra::abajo).max().orElse(0);
            int alto = Math.max(1, abajo - arriba);
            Fila destino = null;
            for (int i = filas.size() - 1; i >= 0 && i >= filas.size() - 4; i--) {
                Fila f = filas.get(i);
                int comun = Math.min(abajo, f.abajo) - Math.max(arriba, f.arriba);
                if (comun >= 0.5 * Math.min(alto, f.alto()) && !f.seEncimaALoAncho(ps)) { destino = f; break; }
            }
            if (destino == null) { destino = new Fila(); filas.add(destino); }
            destino.agregar(ps);
        }
        filas.sort(Comparator.comparingInt(f -> (f.arriba + f.abajo) / 2));
        // 3) texto y confianza (promedio ponderado por caracteres) de cada renglón
        List<Renglon> salida = new ArrayList<>();
        for (Fila f : filas) {
            f.palabras.sort(Comparator.comparingInt(Palabra::x));
            StringBuilder sb = new StringBuilder();
            double suma = 0;
            int caracteres = 0;
            for (Palabra p : f.palabras) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(p.texto());
                suma += p.confianza() * p.texto().length();
                caracteres += p.texto().length();
            }
            if (caracteres == 0) continue;
            salida.add(new Renglon(sb.toString(), suma / caracteres, (int) Math.round(f.arriba / escala)));
        }
        return salida;
    }

    private static int centro(List<Palabra> ps) {
        int a = ps.stream().mapToInt(Palabra::y).min().orElse(0);
        int b = ps.stream().mapToInt(Palabra::abajo).max().orElse(0);
        return (a + b) / 2;
    }

    /** Confianza media de un resultado (ponderada por caracteres): sirve para elegir entre giros */
    public static double confianzaMedia(List<Renglon> renglones) {
        double suma = 0;
        int n = 0;
        for (Renglon r : renglones) {
            int c = r.texto().replace(" ", "").length();
            suma += r.confianza() * c;
            n += c;
        }
        return n == 0 ? 0 : suma / n;
    }

    public static ResultadoOcr resultado(List<Renglon> renglones) {
        StringBuilder sb = new StringBuilder();
        for (Renglon r : renglones) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(r.texto());
        }
        return new ResultadoOcr(List.copyOf(renglones), sb.toString());
    }
}
