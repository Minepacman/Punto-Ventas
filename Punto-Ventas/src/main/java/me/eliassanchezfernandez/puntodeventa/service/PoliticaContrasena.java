package me.eliassanchezfernandez.puntodeventa.service;

/**
 * Política de contraseñas del negocio (USR-07):
 * mínimo 5 caracteres y al menos 1 número.
 */
public final class PoliticaContrasena {

    public static final int LONGITUD_MINIMA = 5;
    public static final String DESCRIPCION =
            "Mínimo " + LONGITUD_MINIMA + " caracteres y al menos 1 número.";

    private PoliticaContrasena() { }

    /** Lanza NegocioException con el motivo si la contraseña no cumple */
    public static void validar(String contrasena) {
        if (contrasena == null || contrasena.length() < LONGITUD_MINIMA) {
            throw new NegocioException("La contraseña debe tener al menos "
                    + LONGITUD_MINIMA + " caracteres.");
        }
        if (contrasena.chars().noneMatch(Character::isDigit)) {
            throw new NegocioException("La contraseña debe incluir al menos un número.");
        }
        if (contrasena.isBlank()) {
            throw new NegocioException("La contraseña no puede ser solo espacios.");
        }
    }

    public static boolean cumple(String contrasena) {
        try {
            validar(contrasena);
            return true;
        } catch (NegocioException e) {
            return false;
        }
    }
}
