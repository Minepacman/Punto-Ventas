package me.eliassanchezfernandez.puntodeventa.service;

/**
 * Error de regla de negocio (validación, permisos, stock, etc.).
 *
 * Es RuntimeException para que, si ocurre dentro de un método
 * @Transactional, Spring revierta la transacción completa.
 * El mensaje siempre está en español y es apto para mostrarse
 * directamente al usuario en un Alert.
 */
public class NegocioException extends RuntimeException {

    public NegocioException(String mensaje) {
        super(mensaje);
    }
}
