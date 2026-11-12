package me.eliassanchezfernandez.puntodeventa.util;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Catálogo de atajos de teclado del punto de venta (una sola fuente de verdad:
 * de aquí salen los aceleradores, los textos de los botones, la barra de ayuda
 * y el diálogo "Atajos" de F10).
 *
 * Cada atajo tiene una tecla principal (normalmente una tecla F, como en los
 * POS de abarrotes) y, cuando tiene sentido, una alternativa con Ctrl (Cmd en
 * macOS) para laptops donde las teclas F exigen Fn. El permiso es solo
 * informativo: quien lo exige de verdad es el servicio correspondiente (o la
 * pantalla pide la autorización de un administrador).
 *
 * La comparación con lo tecleado ({@link #coincide}) es Java puro: se le pasan
 * las banderas del evento y no necesita el toolkit de JavaFX, para poder
 * probarse sin interfaz.
 */
public enum Atajo {

    // ── Globales (ventana principal) ────────────────────────────────────
    IR_VENTAS      (Ambito.GLOBAL, Tecla.de(KeyCode.F1), Tecla.atajo(KeyCode.DIGIT1), "Ir a Ventas", null),
    IR_CLIENTES    (Ambito.GLOBAL, Tecla.de(KeyCode.F2), Tecla.atajo(KeyCode.DIGIT2), "Ir a Clientes", null),
    IR_PRODUCTOS   (Ambito.GLOBAL, Tecla.de(KeyCode.F3), Tecla.atajo(KeyCode.DIGIT3), "Ir a Productos", null),
    IR_INVENTARIO  (Ambito.GLOBAL, Tecla.de(KeyCode.F4), Tecla.atajo(KeyCode.DIGIT4), "Ir a Inventario", null),
    IR_ESTADISTICAS(Ambito.GLOBAL, Tecla.de(KeyCode.F5), Tecla.atajo(KeyCode.DIGIT5), "Ir a Estadísticas", Permiso.VER_HISTORIAL),
    AYUDA_ATAJOS   (Ambito.GLOBAL, Tecla.de(KeyCode.F10), null, "Ver la lista de atajos", null),
    BLOQUEAR       (Ambito.GLOBAL, Tecla.atajo(KeyCode.L), null, "Bloquear la pantalla / cambiar de cajero", null),

    // ── Ventas ──────────────────────────────────────────────────────────
    BUSCAR_PRODUCTO (Ambito.VENTAS, Tecla.de(KeyCode.F6), Tecla.atajo(KeyCode.B), "Buscar producto por nombre o código", null),
    CANTIDAD        (Ambito.VENTAS, Tecla.de(KeyCode.F7), null, "Cambiar la cantidad del renglón seleccionado", null,
                     "También: Enter o doble clic sobre el renglón"),
    DESCUENTO_MANUAL(Ambito.VENTAS, Tecla.de(KeyCode.F8), Tecla.atajo(KeyCode.D), "Descuento manual al renglón", Permiso.DESCUENTO_MANUAL),
    MAYOREO         (Ambito.VENTAS, Tecla.de(KeyCode.F9), Tecla.atajo(KeyCode.M), "Precio de mayoreo al renglón (quitar/poner)", Permiso.APLICAR_MAYOREO),
    PONER_EN_ESPERA (Ambito.VENTAS, Tecla.de(KeyCode.F11), Tecla.atajo(KeyCode.G), "Poner el ticket en espera (otro cliente mientras tanto)", null),
    RECUPERAR_ESPERA(Ambito.VENTAS, Tecla.mayus(KeyCode.F11), Tecla.atajo(KeyCode.R), "Recuperar un ticket en espera", null),
    COBRAR          (Ambito.VENTAS, Tecla.de(KeyCode.F12), Tecla.atajo(KeyCode.ENTER), "Cobrar el ticket", null,
                     "En la ventana de cobro: Enter cobra, Esc regresa"),
    REIMPRIMIR_ULTIMO(Ambito.VENTAS, Tecla.atajo(KeyCode.I), null, "Reimprimir el último ticket cobrado en esta caja", Permiso.VER_HISTORIAL),
    ABRIR_CAJON     (Ambito.VENTAS, Tecla.atajo(KeyCode.J), null, "Abrir el cajón de dinero (solo con impresora térmica)", Permiso.ABRIR_CAJON),
    LEER_PESO       (Ambito.VENTAS, Tecla.atajoMayus(KeyCode.P), null, "Leer el peso de la báscula en el renglón a granel", null),
    CANCELAR_TICKET (Ambito.VENTAS, Tecla.atajoMayus(KeyCode.C), Tecla.atajo(KeyCode.DELETE), "Cancelar el ticket en captura (pide confirmación)", null),
    QUITAR_RENGLON  (Ambito.VENTAS, Tecla.de(KeyCode.DELETE), Tecla.de(KeyCode.BACK_SPACE), "Quitar el renglón seleccionado", null,
                     "Con el cursor en el ticket (no en un campo de texto)"),
    MAS_UNO         (Ambito.VENTAS, Tecla.de(KeyCode.ADD), Tecla.de(KeyCode.PLUS), "Una pieza más al renglón seleccionado", null,
                     "Tecla + en el ticket o con el campo de código vacío"),
    MENOS_UNO       (Ambito.VENTAS, Tecla.de(KeyCode.SUBTRACT), Tecla.de(KeyCode.MINUS), "Una pieza menos al renglón seleccionado", null,
                     "Tecla − en el ticket o con el campo de código vacío");

    /** Dónde aplica el atajo */
    public enum Ambito { GLOBAL, VENTAS }

    /**
     * Una tecla con sus modificadores. {@code atajo} es Ctrl en Windows/Linux y
     * Cmd en macOS (el modificador "Shortcut" de JavaFX).
     */
    public record Tecla(KeyCode codigo, boolean atajo, boolean mayus) {
        static Tecla de(KeyCode c)         { return new Tecla(c, false, false); }
        static Tecla mayus(KeyCode c)      { return new Tecla(c, false, true); }
        static Tecla atajo(KeyCode c)      { return new Tecla(c, true, false); }
        static Tecla atajoMayus(KeyCode c) { return new Tecla(c, true, true); }

        /** ¿Coincide con lo tecleado? (sin toolkit: las banderas vienen del evento) */
        public boolean coincide(KeyCode tecleado, boolean atajoPulsado, boolean mayusPulsado, boolean altPulsado) {
            return codigo == tecleado && atajo == atajoPulsado && mayus == mayusPulsado && !altPulsado;
        }

        /** Combinación para {@code scene.getAccelerators()} */
        public KeyCombination aCombinacion() {
            // Los modificadores que no se mencionan deben estar SUELTOS (valor por defecto de JavaFX)
            if (atajo && mayus) return new KeyCodeCombination(codigo, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
            if (atajo)          return new KeyCodeCombination(codigo, KeyCombination.SHORTCUT_DOWN);
            if (mayus)          return new KeyCodeCombination(codigo, KeyCombination.SHIFT_DOWN);
            return new KeyCodeCombination(codigo);
        }

        /** Texto para mostrar: "F12", "Ctrl+Enter" / "Cmd+Enter", "Shift+F11" */
        public String etiqueta(boolean mac) {
            StringBuilder sb = new StringBuilder();
            if (atajo) sb.append(mac ? "Cmd+" : "Ctrl+");
            if (mayus) sb.append(mac ? "Shift+" : "Mayús+");
            sb.append(nombreTecla(codigo, mac));
            return sb.toString();
        }

        private static String nombreTecla(KeyCode c, boolean mac) {
            return switch (c) {
                case ENTER      -> "Enter";
                case ESCAPE     -> "Esc";
                case DELETE     -> mac ? "Supr (fn+⌫)" : "Supr";
                case BACK_SPACE -> "Retroceso";
                case ADD, PLUS  -> "+";
                case SUBTRACT, MINUS -> "−";
                case DIGIT1 -> "1"; case DIGIT2 -> "2"; case DIGIT3 -> "3"; case DIGIT4 -> "4"; case DIGIT5 -> "5";
                default -> c.getName();
            };
        }
    }

    private final Ambito ambito;
    private final Tecla principal;
    private final Tecla alternativa;
    private final String descripcion;
    private final Permiso permiso;
    private final String nota;

    Atajo(Ambito ambito, Tecla principal, Tecla alternativa, String descripcion, Permiso permiso) {
        this(ambito, principal, alternativa, descripcion, permiso, null);
    }

    Atajo(Ambito ambito, Tecla principal, Tecla alternativa, String descripcion, Permiso permiso, String nota) {
        this.ambito = ambito;
        this.principal = principal;
        this.alternativa = alternativa;
        this.descripcion = descripcion;
        this.permiso = permiso;
        this.nota = nota;
    }

    public Ambito getAmbito()        { return ambito; }
    public Tecla getPrincipal()      { return principal; }
    public Tecla getAlternativa()    { return alternativa; }
    public String getDescripcion()   { return descripcion; }
    /** Permiso que exige la acción (o autorización de administrador); null si no requiere ninguno */
    public Permiso getPermiso()      { return permiso; }
    public String getNota()          { return nota; }

    /** Teclas registrables como aceleradores (principal y alternativa) */
    public List<Tecla> teclas() {
        return alternativa == null ? List.of(principal) : List.of(principal, alternativa);
    }

    /** "F12 · Ctrl+Enter" (para tooltips y la ayuda) */
    public String etiquetaTeclas(boolean mac) {
        String p = principal.etiqueta(mac);
        return alternativa == null ? p : p + "  ·  " + alternativa.etiqueta(mac);
    }

    /** Solo la tecla principal: "F12" (para el texto de un botón) */
    public String etiquetaCorta(boolean mac) {
        return principal.etiqueta(mac);
    }

    /** ¿Lo tecleado dispara este atajo? */
    public boolean coincide(KeyCode tecleado, boolean atajoPulsado, boolean mayusPulsado, boolean altPulsado) {
        return teclas().stream().anyMatch(t -> t.coincide(tecleado, atajoPulsado, mayusPulsado, altPulsado));
    }

    /** Atajo de un ámbito que coincide con lo tecleado (vacío si ninguno) */
    public static Optional<Atajo> resolver(Ambito ambito, KeyCode tecleado, boolean atajoPulsado,
                                           boolean mayusPulsado, boolean altPulsado) {
        if (tecleado == null) return Optional.empty();
        return Arrays.stream(values())
                .filter(a -> a.ambito == ambito)
                .filter(a -> a.coincide(tecleado, atajoPulsado, mayusPulsado, altPulsado))
                .findFirst();
    }

    public static List<Atajo> de(Ambito ambito) {
        return Arrays.stream(values()).filter(a -> a.ambito == ambito).toList();
    }

    /**
     * Atajos que, por caer sobre teclas que un campo de texto usa para escribir
     * (Supr, Retroceso, +, −), NO deben dispararse mientras el cursor está en un
     * campo de texto con contenido. Los demás (teclas F y combinaciones con
     * Ctrl/Cmd) se disparan siempre dentro de Ventas.
     */
    public boolean compiteConEscritura() {
        return this == QUITAR_RENGLON || this == MAS_UNO || this == MENOS_UNO;
    }

    public static boolean esMac() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase().contains("mac");
    }
}
