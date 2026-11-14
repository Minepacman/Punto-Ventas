package me.eliassanchezfernandez.puntodeventa.util;

import java.util.Optional;

/**
 * Distingue lo que escribe un lector de códigos de barras de lo que teclea una
 * persona, solo por el ritmo: un lector manda los caracteres del código con
 * pocos milisegundos entre uno y otro y remata con Enter de inmediato; una
 * persona no baja de ~60 ms entre teclas ni aun escribiendo rápido.
 *
 * Sin interfaz: se le pasan los caracteres y los Enter con su instante
 * ({@code System.nanoTime()}) y responde si forman una ráfaga. Lo usan las
 * ventanas modales (para no dejar que el Enter del lector "confirme" un aviso y
 * se pierda el artículo) y la ventana de cobro (para no tomar un código corto
 * como efectivo).
 */
public final class RafagaLector {

    /** Separación máxima entre teclas para seguir considerándolas una ráfaga */
    public static final long UMBRAL_MS = 50;
    /** Un código tiene al menos esta cantidad de caracteres (un solo carácter y Enter puede ser una persona) */
    public static final int MINIMO_CARACTERES = 2;

    private final long umbralNanos;
    private final StringBuilder buffer = new StringBuilder();
    private long ultimoNanos;
    private boolean rota;   // alguna separación dentro del texto acumulado fue "humana"

    public RafagaLector() {
        this(UMBRAL_MS);
    }

    public RafagaLector(long umbralMs) {
        this.umbralNanos = umbralMs * 1_000_000L;
    }

    /**
     * Registra un carácter escrito (no de control).
     * @return true si llega "pegado" al anterior, es decir, ya forma parte de una ráfaga
     */
    public boolean caracter(char c, long ahoraNanos) {
        if (Character.isISOControl(c)) return false;
        boolean pegado = buffer.length() > 0 && ahoraNanos - ultimoNanos <= umbralNanos;
        if (buffer.length() > 0 && !pegado) rota = true;
        buffer.append(c);
        ultimoNanos = ahoraNanos;
        return pegado && !rota;
    }

    /**
     * Registra un Enter. Si cierra una ráfaga (≥ {@link #MINIMO_CARACTERES} caracteres
     * todos pegados entre sí y el Enter pegado al último) devuelve el código leído y
     * se vacía; si no (Enter de una persona), devuelve vacío y también se vacía: lo
     * tecleado antes ya no cuenta como código.
     */
    public Optional<String> enter(long ahoraNanos) {
        boolean esRafaga = !rota && buffer.length() >= MINIMO_CARACTERES
                && ahoraNanos - ultimoNanos <= umbralNanos;
        String codigo = buffer.toString().trim();
        limpiar();
        return esRafaga && !codigo.isEmpty() ? Optional.of(codigo) : Optional.empty();
    }

    /** ¿Lo acumulado hasta ahora (sin Enter todavía) parece venir de un lector? */
    public boolean enRafaga() {
        return !rota && buffer.length() >= MINIMO_CARACTERES;
    }

    /** Texto acumulado desde el último Enter o limpieza */
    public String texto() {
        return buffer.toString();
    }

    public void limpiar() {
        buffer.setLength(0);
        rota = false;
        ultimoNanos = 0;
    }
}
