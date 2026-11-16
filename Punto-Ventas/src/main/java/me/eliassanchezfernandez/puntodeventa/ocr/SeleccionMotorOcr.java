package me.eliassanchezfernandez.puntodeventa.ocr;

import java.util.Locale;

/**
 * Decide qué lector de tickets usar en ESTA computadora (lógica pura, sin E/S: se prueba sola).
 * Reglas del plan aprobado (docs/investigacion-ocr-tickets.md):
 * <ul>
 *   <li><b>Rápido</b>: siempre Tesseract.</li>
 *   <li><b>Preciso</b>: PaddleOCR si el equipo puede (no en Windows de 32 bits, ONNX Runtime carga y los
 *       modelos ya se bajaron); mientras se bajan, Tesseract.</li>
 *   <li><b>Automático</b>: Windows de 32 bits → Tesseract; menos de ~6 GB de RAM → Tesseract; si
 *       PaddleOCR no carga → Tesseract sin mostrar error; si no, se mide PaddleOCR una vez en segundo
 *       plano con un ticket de muestra (≤ 5 s → PaddleOCR, si no Tesseract). La medida se guarda con
 *       una huella del equipo y se repite solo si cambia el hardware.</li>
 * </ul>
 * Si el motor elegido no está, se usa el otro; si no hay ninguno, {@link Motor#NINGUNO}.
 */
public final class SeleccionMotorOcr {

    public enum Preferencia {
        AUTOMATICO("Automático"), RAPIDO("Rápido (Tesseract)"), PRECISO("Preciso (PaddleOCR)");

        private final String etiqueta;

        Preferencia(String etiqueta) { this.etiqueta = etiqueta; }

        public String etiqueta() { return etiqueta; }

        public static Preferencia de(String texto) {
            if (texto != null) {
                for (Preferencia p : values()) if (p.name().equalsIgnoreCase(texto.trim())) return p;
            }
            return AUTOMATICO;
        }
    }

    public enum Motor {
        TESSERACT("Tesseract"), PPOCR("PaddleOCR"), NINGUNO("ninguno");

        private final String etiqueta;

        Motor(String etiqueta) { this.etiqueta = etiqueta; }

        public String etiqueta() { return etiqueta; }
    }

    /**
     * RAM mínima (total del equipo) para considerar PaddleOCR en automático: ~6 GB. ONNX Runtime usa hasta ~1.2 GB
     * de memoria nativa fuera del heap mientras lee; en una PC de 4 GB con la caja abierta eso la deja paginando
     * (medido en PC de 2 núcleos simulada). Los equipos reportan algo menos de la cifra redonda: se descuenta medio GB.
     */
    public static final long RAM_MINIMA_PPOCR = 6L * 1024 * 1024 * 1024 - 512L * 1024 * 1024;
    /** Tiempo máximo del ticket de muestra para elegir PaddleOCR en automático */
    public static final double SEGUNDOS_MAXIMOS_PPOCR = 5.0;

    /** Datos del equipo */
    public record Equipo(String sistema, String arquitectura, long ramBytes, int nucleos) {

        public static Equipo actual() {
            long ram = 0;
            try {
                java.lang.management.OperatingSystemMXBean os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                if (os instanceof com.sun.management.OperatingSystemMXBean sun) ram = sun.getTotalMemorySize();
            } catch (RuntimeException | LinkageError e) {
                ram = 0;
            }
            return new Equipo(System.getProperty("os.name", ""), System.getProperty("os.arch", ""), ram,
                    Runtime.getRuntime().availableProcessors());
        }

        public boolean esWindows() { return sistema.toLowerCase(Locale.ROOT).contains("win"); }

        /** x86 de 32 bits (ONNX Runtime ya no publica binarios para esa arquitectura) */
        public boolean es32Bits() {
            String a = arquitectura.toLowerCase(Locale.ROOT);
            return a.equals("x86") || a.equals("i386") || a.equals("i486") || a.equals("i586") || a.equals("i686");
        }

        public double ramGb() { return ramBytes / (1024.0 * 1024 * 1024); }

        /** Cambia si cambia el hardware que importa para la velocidad (o el sistema) */
        public String huella() {
            return sistema.toLowerCase(Locale.ROOT).replace(' ', '_') + "|" + arquitectura + "|" + nucleos + "|"
                    + Math.round(ramGb());
        }
    }

    /**
     * Estado de PaddleOCR en este equipo.
     *
     * @param incluido            esta versión del programa trae el motor PaddleOCR
     * @param cargaOnnx           ONNX Runtime cargó (null = aún no se ha intentado)
     * @param modelosListos       los modelos ya se bajaron y su SHA-256 coincide
     * @param segundosMedidos     resultado del ticket de muestra (null = sin medir)
     * @param huellaMedicion      huella del equipo cuando se midió
     */
    public record EstadoPpOcr(boolean incluido, Boolean cargaOnnx, boolean modelosListos, Double segundosMedidos,
                              String huellaMedicion) {
        public static EstadoPpOcr noIncluido() {
            return new EstadoPpOcr(false, null, false, null, null);
        }
    }

    /**
     * @param motor            el que se usa ahora
     * @param motivo           una línea para Configuración: qué se eligió y por qué
     * @param medirPpOcr       conviene medir PaddleOCR en segundo plano (automático, sin medida vigente)
     * @param descargarModelos conviene bajar los modelos de PaddleOCR (Preciso o para medir)
     */
    public record Decision(Motor motor, String motivo, boolean medirPpOcr, boolean descargarModelos) {}

    private SeleccionMotorOcr() { }

    public static Decision decidir(Preferencia preferencia, Equipo equipo, boolean hayTesseract, EstadoPpOcr pp) {
        Decision d = decidirSinRespaldo(preferencia, equipo, pp);
        // respaldo: si el elegido no está disponible, el otro
        if (d.motor() == Motor.TESSERACT && !hayTesseract) {
            if (ppListo(equipo, pp)) {
                return new Decision(Motor.PPOCR, "Se usa PaddleOCR porque Tesseract no está instalado en esta computadora.", false, false);
            }
            return new Decision(Motor.NINGUNO, "No hay lector de tickets: no se encontró Tesseract en esta computadora"
                    + " (reinstala el programa o instala Tesseract).", false, d.descargarModelos());
        }
        return d;
    }

    private static Decision decidirSinRespaldo(Preferencia preferencia, Equipo equipo, EstadoPpOcr pp) {
        switch (preferencia) {
            case RAPIDO:
                return tesseract("Elegiste el modo rápido.", false, false);
            case PRECISO: {
                String impedimento = impedimentoPpOcr(equipo, pp, false);
                if (impedimento != null) return tesseract("Elegiste el modo preciso, pero " + impedimento + ".", false, false);
                if (!pp.modelosListos()) {
                    return tesseract("Elegiste el modo preciso: mientras se descargan los modelos de PaddleOCR (~20 MB) se usa Tesseract.", false, true);
                }
                return new Decision(Motor.PPOCR, "Elegiste el modo preciso.", false, false);
            }
            default: {
                String impedimento = impedimentoPpOcr(equipo, pp, true);
                if (impedimento != null) return tesseract("Automático: " + impedimento + ".", false, false);
                boolean medidaVigente = pp.segundosMedidos() != null && equipo.huella().equals(pp.huellaMedicion());
                if (!medidaVigente) {
                    return tesseract("Automático: se está midiendo qué tan rápido es PaddleOCR en esta computadora; mientras, Tesseract.",
                            true, !pp.modelosListos());
                }
                double s = pp.segundosMedidos();
                String seg = String.format(Locale.ROOT, "%.1f", s);
                if (s > SEGUNDOS_MAXIMOS_PPOCR) {
                    return tesseract("Automático: PaddleOCR tardó " + seg + " s con el ticket de muestra (más de "
                            + (int) SEGUNDOS_MAXIMOS_PPOCR + " s); Tesseract es más rápido aquí.", false, false);
                }
                if (!pp.modelosListos()) {
                    return tesseract("Automático: PaddleOCR conviene aquí (" + seg + " s), pero faltan sus modelos; se están descargando.", false, true);
                }
                return new Decision(Motor.PPOCR, "Automático: PaddleOCR leyó el ticket de muestra en " + seg
                        + " s; es el más preciso y aquí es rápido.", false, false);
            }
        }
    }

    /** Por qué PaddleOCR no puede usarse aquí (null = sí puede) */
    private static String impedimentoPpOcr(Equipo equipo, EstadoPpOcr pp, boolean automatico) {
        if (!pp.incluido()) return "esta versión del programa aún no trae PaddleOCR";
        if (equipo.es32Bits()) return "PaddleOCR no funciona en " + (equipo.esWindows() ? "Windows" : "sistemas") + " de 32 bits";
        if (automatico && equipo.ramBytes() > 0 && equipo.ramBytes() < RAM_MINIMA_PPOCR) {
            return String.format(Locale.ROOT, "la computadora tiene %.1f GB de memoria (PaddleOCR pide unos 6 GB)", equipo.ramGb());
        }
        if (Boolean.FALSE.equals(pp.cargaOnnx())) return "PaddleOCR no pudo arrancar en este procesador";
        return null;
    }

    private static boolean ppListo(Equipo equipo, EstadoPpOcr pp) {
        return pp.incluido() && !equipo.es32Bits() && !Boolean.FALSE.equals(pp.cargaOnnx()) && pp.modelosListos();
    }

    private static Decision tesseract(String motivo, boolean medir, boolean descargar) {
        return new Decision(Motor.TESSERACT, motivo.contains("Tesseract") ? motivo : motivo + " Se usa Tesseract.", medir, descargar);
    }
}
