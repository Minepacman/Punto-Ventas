package me.eliassanchezfernandez.puntodeventa.util;

import javafx.application.ConditionalFeature;
import javafx.application.Platform;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * "Modo ligero" para computadoras lentas o sin tarjeta de video que JavaFX pueda usar.
 *
 * <p>Qué hace (ver {@code css/ligero.css} y {@link me.eliassanchezfernandez.puntodeventa.util.apariencia.AplicadorApariencia}):
 * quita todas las sombras ({@code -fx-effect}), apaga el efecto vidrio (fondos degradados enormes que se
 * repintan en cada cuadro) y las animaciones de paneles y pestañas. Los colores, la letra y los iconos se
 * quedan como estén. Con el dibujo por software (sin tarjeta de video o con un controlador que JavaFX no
 * acepta, típico de Intel GMA/HD viejas) una sombra gaussiana se calcula con el procesador en cada repintado.</p>
 *
 * <p>Es de ESTA computadora (no de la tienda ni del usuario): se guarda en {@code equipo.properties} en la
 * carpeta de datos. Automático (de fábrica) = encendido solo si JavaFX dibuja por software.
 * {@code -Dapp.modo-ligero=true|false} manda sobre todo (pruebas y soporte).</p>
 */
public final class ModoLigero {

    private ModoLigero() { }

    public enum Preferencia {
        AUTOMATICO("automatico", "Automático"),
        ENCENDIDO("encendido", "Siempre"),
        APAGADO("apagado", "Nunca");

        private final String clave;
        private final String nombre;

        Preferencia(String clave, String nombre) {
            this.clave = clave;
            this.nombre = nombre;
        }

        public String clave() { return clave; }
        public String nombre() { return nombre; }

        public static Preferencia deClave(String c) {
            for (Preferencia p : values()) if (p.clave.equalsIgnoreCase(String.valueOf(c).trim())) return p;
            return AUTOMATICO;
        }
    }

    public static final String ARCHIVO = "equipo.properties";
    static final String CLAVE = "modo.ligero";
    static final String CLAVE_SUGERIDO = "modo.ligero.sugerido";
    /** -Dapp.modo-ligero=true|false: fuerza el modo sin importar lo guardado */
    public static final String PROPIEDAD = "app.modo-ligero";
    /** Hoja que se agrega a cada escena con el modo encendido */
    public static final String HOJA = "/css/ligero.css";

    private static volatile Preferencia preferencia;
    private static volatile Boolean software;
    private static volatile String motivoLentitud;

    // ── Decisión (pura, probada aparte) ──────────────────────────────────

    /** ¿Encendido? forzado (-D) > lo elegido en esta computadora > automático (dibujo por software) */
    static boolean decidir(Preferencia p, boolean dibujoPorSoftware, String forzado) {
        if ("true".equalsIgnoreCase(forzado)) return true;
        if ("false".equalsIgnoreCase(forzado)) return false;
        return switch (p == null ? Preferencia.AUTOMATICO : p) {
            case ENCENDIDO -> true;
            case APAGADO -> false;
            case AUTOMATICO -> dibujoPorSoftware;
        };
    }

    /** ¿Ofrecer el modo ligero? Solo en automático, si no está ya encendido y no se ofreció antes */
    static boolean decidirSugerir(Preferencia p, boolean encendido, boolean yaSugerido, String lentitud) {
        return p == Preferencia.AUTOMATICO && !encendido && !yaSugerido && lentitud != null;
    }

    // ── Estado de esta computadora ───────────────────────────────────────

    public static boolean activo() {
        return decidir(preferencia(), dibujoPorSoftware(), System.getProperty(PROPIEDAD));
    }

    public static Preferencia preferencia() {
        Preferencia p = preferencia;
        if (p == null) {
            p = Preferencia.deClave(leer(archivo()).getProperty(CLAVE, "automatico"));
            preferencia = p;
        }
        return p;
    }

    /** Guarda la elección (solo en esta computadora). No aplica nada: eso lo hace AplicadorApariencia.reaplicar() */
    public static void guardarPreferencia(Preferencia p) {
        preferencia = p == null ? Preferencia.AUTOMATICO : p;
        escribir(archivo(), CLAVE, preferencia.clave());
        marcarSugerido();
    }

    /**
     * ¿JavaFX dibuja con el procesador? (pipeline "sw": sin aceleración 3D). Se pregunta una sola vez y solo
     * con JavaFX ya arrancado; antes de eso responde false sin recordar.
     */
    public static boolean dibujoPorSoftware() {
        Boolean s = software;
        if (s != null) return s;
        try {
            String nombre = nombreTuberia();
            if (nombre != null) {
                s = nombre.toLowerCase(Locale.ROOT).contains(".sw.");
            } else {
                // Respaldo con API pública: el dibujo por software no ofrece 3D
                s = !Platform.isSupported(ConditionalFeature.SCENE3D);
            }
        } catch (IllegalStateException e) {
            return false;   // JavaFX aún no arranca
        } catch (RuntimeException | LinkageError e) {
            s = false;
        }
        software = s;
        return s;
    }

    /** Nombre de la clase de la tubería de dibujo de JavaFX (p. ej. com.sun.prism.d3d.D3DPipeline), o null */
    public static String nombreTuberia() {
        try {
            Class<?> c = Class.forName("com.sun.prism.GraphicsPipeline");
            Object p = c.getMethod("getPipeline").invoke(null);
            return p == null ? null : p.getClass().getName();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Explicación corta del estado actual, para Configuración → Apariencia */
    public static String descripcionEstado() {
        String forzado = System.getProperty(PROPIEDAD);
        if (forzado != null && !forzado.isBlank()) return activo() ? "Encendido (forzado al arrancar)." : "Apagado (forzado al arrancar).";
        return switch (preferencia()) {
            case ENCENDIDO -> "Encendido en esta computadora.";
            case APAGADO -> "Apagado en esta computadora.";
            case AUTOMATICO -> dibujoPorSoftware()
                    ? "Encendido: esta computadora dibuja la pantalla sin tarjeta de video."
                    : "Apagado: esta computadora dibuja con su tarjeta de video.";
        };
    }

    // ── Sugerencia cuando el equipo resulta lento ────────────────────────

    /** Anota que el equipo se vio lento (arranque o pantalla); la barra de estado ofrecerá el modo ligero */
    public static void anotarLentitud(String motivo) {
        if (motivo != null && motivoLentitud == null) motivoLentitud = motivo;
    }

    public static String motivoLentitud() {
        return motivoLentitud;
    }

    public static boolean debeSugerir() {
        return decidirSugerir(preferencia(), activo(), "true".equals(leer(archivo()).getProperty(CLAVE_SUGERIDO)), motivoLentitud);
    }

    /** Ya se ofreció (se haya aceptado o no): no se vuelve a ofrecer */
    public static void marcarSugerido() {
        escribir(archivo(), CLAVE_SUGERIDO, "true");
    }

    // ── Archivo ──────────────────────────────────────────────────────────

    static Path archivo() {
        String propio = System.getProperty("app.equipo.archivo");
        return propio != null ? Path.of(propio) : RutasApp.carpetaAplicacion().resolve(ARCHIVO);
    }

    static Properties leer(Path f) {
        Properties p = new Properties();
        try {
            if (Files.isRegularFile(f) && Files.size(f) < 16_384) {
                try (InputStream in = Files.newInputStream(f)) {
                    p.load(in);
                }
            }
        } catch (Exception e) {
            // archivo dañado: de fábrica
        }
        return p;
    }

    static void escribir(Path f, String clave, String valor) {
        try {
            Properties p = leer(f);
            if (valor.equals(p.getProperty(clave))) return;
            p.setProperty(clave, valor);
            Files.createDirectories(f.getParent());
            try (OutputStream out = Files.newOutputStream(f)) {
                p.store(out, "Ajustes de esta computadora (Caja Libre)");
            }
        } catch (Exception e) {
            System.err.println("No se pudo guardar " + f + ": " + e.getMessage());
        }
    }

    /** Solo pruebas: olvida lo leído */
    static void reiniciarParaPruebas() {
        preferencia = null;
        software = null;
        motivoLentitud = null;
    }
}
