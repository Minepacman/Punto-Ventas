package me.eliassanchezfernandez.puntodeventa.facturacion;

/**
 * Credenciales del PAC ya descifradas. Vive solo en memoria mientras se usa;
 * toString() NO revela nada para que nunca acabe en un log o una excepción.
 */
public record CredencialesPac(String usuario, String contrasena, boolean sandbox) {

    @Override
    public String toString() {
        return "CredencialesPac{usuario=" + BovedaCredenciales.enmascarar(usuario) + ", sandbox=" + sandbox + "}";
    }
}
