package me.eliassanchezfernandez.puntodeventa.util;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Versión semántica MAYOR.MENOR.PARCHE[-prelanzamiento][+metadatos] con el
 * orden de semver.org: 1.10.0 > 1.9.0 y una versión final (1.0.0) es MAYOR que
 * sus prelanzamientos (1.0.0-beta.2, 1.0.0-rc.1). Acepta el prefijo "v" de las
 * etiquetas de GitHub. Los metadatos (+algo) no cuentan para comparar.
 */
public record VersionSemantica(int mayor, int menor, int parche, String prelanzamiento)
        implements Comparable<VersionSemantica> {

    private static final Pattern FORMATO = Pattern.compile(
            "^[vV]?(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$");

    /** "v1.2.3-beta.1" → versión; vacío si el texto no es una versión ("desarrollo", "latest", "") */
    public static Optional<VersionSemantica> parsear(String texto) {
        if (texto == null) return Optional.empty();
        Matcher m = FORMATO.matcher(texto.trim());
        if (!m.matches()) return Optional.empty();
        try {
            return Optional.of(new VersionSemantica(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    m.group(3) == null ? 0 : Integer.parseInt(m.group(3)), m.group(4)));
        } catch (NumberFormatException demasiadoGrande) {
            return Optional.empty();
        }
    }

    public boolean esPrelanzamiento() {
        return prelanzamiento != null && !prelanzamiento.isEmpty();
    }

    public boolean esMayorQue(VersionSemantica otra) {
        return compareTo(otra) > 0;
    }

    @Override
    public int compareTo(VersionSemantica o) {
        if (mayor != o.mayor) return Integer.compare(mayor, o.mayor);
        if (menor != o.menor) return Integer.compare(menor, o.menor);
        if (parche != o.parche) return Integer.compare(parche, o.parche);
        boolean a = esPrelanzamiento(), b = o.esPrelanzamiento();
        if (a != b) return a ? -1 : 1;           // la final gana al prelanzamiento
        if (!a) return 0;
        return compararPrelanzamiento(prelanzamiento, o.prelanzamiento);
    }

    /** Identificadores separados por punto: numéricos entre sí por valor, numérico < alfanumérico, el más largo gana */
    private static int compararPrelanzamiento(String p, String q) {
        String[] a = p.split("\\."), b = q.split("\\.");
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            boolean na = a[i].chars().allMatch(Character::isDigit), nb = b[i].chars().allMatch(Character::isDigit);
            int c;
            if (na && nb) c = Long.compare(Long.parseLong(a[i]), Long.parseLong(b[i]));
            else if (na != nb) c = na ? -1 : 1;
            else c = a[i].compareTo(b[i]);
            if (c != 0) return c;
        }
        return Integer.compare(a.length, b.length);
    }

    /** Solo los tres números, como los exige jpackage: "1.2.3" */
    public String numerica() {
        return mayor + "." + menor + "." + parche;
    }

    @Override
    public String toString() {
        return numerica() + (esPrelanzamiento() ? "-" + prelanzamiento : "");
    }

    /** Texto en minúsculas para buscar en nombres de archivo */
    static String minusculas(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
