package me.eliassanchezfernandez.puntodeventa.service;

import java.util.Locale;
import java.util.Set;

/**
 * Política de contraseñas del negocio (USR-07):
 * mínimo 5 caracteres y al menos 1 número. Además (SEG-73) se rechazan las
 * contraseñas triviales más comunes y la que sea igual al nombre de usuario;
 * nada más, para no volverla molesta en una tienda.
 */
public final class PoliticaContrasena {

    public static final int LONGITUD_MINIMA = 5;
    public static final int LONGITUD_MAXIMA = 72;   // límite útil de BCrypt
    public static final String DESCRIPCION =
            "Mínimo " + LONGITUD_MINIMA + " caracteres y al menos 1 número. No uses tu usuario ni una contraseña obvia.";

    /** Contraseñas que cumplen la regla de forma pero que cualquiera prueba primero */
    static final Set<String> TRIVIALES = Set.of(
            "12345", "123456", "1234567", "12345678", "123456789", "1234567890",
            "54321", "654321", "112233", "123123", "111111", "000000",
            "admin1", "admin123", "admin1234", "administrador1",
            "tienda1", "tienda123", "caja123", "cajero1", "cajero123", "venta123", "ventas1",
            "password1", "contrasena1", "contraseña1",
            "qwerty1", "qwerty123", "abc123", "abcd1234", "a12345", "12345a", "hola123", "mexico1");

    private PoliticaContrasena() { }

    /** Lanza NegocioException con el motivo si la contraseña no cumple (sin comparar con el usuario) */
    public static void validar(String contrasena) {
        validar(contrasena, null);
    }

    /**
     * Lanza NegocioException con el motivo si la contraseña no cumple.
     * @param usuario nombre de usuario (puede ser null): la contraseña no puede ser igual a él
     */
    public static void validar(String contrasena, String usuario) {
        if (contrasena == null || contrasena.length() < LONGITUD_MINIMA) {
            throw new NegocioException("La contraseña debe tener al menos "
                    + LONGITUD_MINIMA + " caracteres.");
        }
        // BCrypt cuenta bytes UTF-8, no caracteres: con acentos o ñ cada letra ocupa 2 bytes
        if (contrasena.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > LONGITUD_MAXIMA) {
            throw new NegocioException("La contraseña es demasiado larga (máximo " + LONGITUD_MAXIMA + " letras sin acentos).");
        }
        if (contrasena.isBlank()) {
            throw new NegocioException("La contraseña no puede ser solo espacios.");
        }
        if (contrasena.chars().noneMatch(Character::isDigit)) {
            throw new NegocioException("La contraseña debe incluir al menos un número.");
        }
        String normalizada = contrasena.trim().toLowerCase(Locale.ROOT);
        if (TRIVIALES.contains(normalizada) || normalizada.chars().distinct().count() == 1) {
            throw new NegocioException("Esa contraseña es demasiado fácil de adivinar. Elige otra.");
        }
        if (usuario != null && !usuario.isBlank()
                && normalizada.equals(usuario.trim().toLowerCase(Locale.ROOT))) {
            throw new NegocioException("La contraseña no puede ser igual al nombre de usuario.");
        }
    }

    public static boolean cumple(String contrasena) {
        return cumple(contrasena, null);
    }

    public static boolean cumple(String contrasena, String usuario) {
        try {
            validar(contrasena, usuario);
            return true;
        } catch (NegocioException e) {
            return false;
        }
    }
}
