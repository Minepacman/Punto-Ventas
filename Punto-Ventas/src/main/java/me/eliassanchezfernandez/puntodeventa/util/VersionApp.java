package me.eliassanchezfernandez.puntodeventa.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Versión del programa. La única fuente de verdad es {@code version} en
 * build.gradle: Gradle la escribe en {@code version.properties} al compilar
 * (y en el manifiesto del jar), así que vale igual con {@code ./gradlew run},
 * en las pruebas y en el instalador. Si por lo que sea no se rellenó (p. ej.
 * al ejecutar desde un IDE sin pasar por Gradle) se informa "desarrollo" en
 * vez de inventar un número.
 */
public final class VersionApp {

    public static final String RECURSO = "/version.properties";
    public static final String DESCONOCIDA = "desarrollo";

    private static final String VERSION = cargar();

    private VersionApp() { }

    /** "1.0.0", "1.1.0-beta.1" o "desarrollo" */
    public static String version() {
        return VERSION;
    }

    /** Lo que se muestra en pantalla: "v1.0.0" (o "desarrollo") */
    public static String etiqueta() {
        return VERSION.equals(DESCONOCIDA) ? VERSION : "v" + VERSION;
    }

    /** User-Agent de las consultas HTTP propias ("PuntoDeVenta/1.0.0") */
    public static String agenteUsuario() {
        return "PuntoDeVenta/" + VERSION;
    }

    private static String cargar() {
        Properties p = new Properties();
        try (InputStream in = VersionApp.class.getResourceAsStream(RECURSO)) {
            if (in != null) p.load(in);
        } catch (IOException e) {
            // se cae a los valores de abajo
        }
        Package paquete = VersionApp.class.getPackage();
        return desde(p.getProperty("version"), paquete != null ? paquete.getImplementationVersion() : null);
    }

    /** Primero el recurso expandido por Gradle; si quedó sin expandir, el manifiesto; si no, "desarrollo" */
    static String desde(String recurso, String manifiesto) {
        if (esUtil(recurso)) return recurso.trim();
        if (esUtil(manifiesto)) return manifiesto.trim();
        return DESCONOCIDA;
    }

    private static boolean esUtil(String v) {
        return v != null && !v.isBlank() && !v.contains("${") && !v.contains("unspecified");
    }
}
