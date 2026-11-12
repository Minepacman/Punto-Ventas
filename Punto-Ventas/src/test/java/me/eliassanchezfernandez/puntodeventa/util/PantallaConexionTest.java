package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/** Lógica sin interfaz de la pantalla "sin conexión": causa raíz y contraseña del administrador contra el hash local. */
class PantallaConexionTest {

    @Test
    void laCausaRaizEsElMensajeDelDriverYNoLaCadenaDeSpring() {
        Exception driver = new java.sql.SQLException("Connection to 192.168.1.10:5432 refused");
        Exception spring = new IllegalStateException("Failed to load", new RuntimeException("pool", driver));
        assertThat(PantallaConexion.causaRaiz(spring)).isEqualTo("Connection to 192.168.1.10:5432 refused");
        assertThat(PantallaConexion.causaRaiz(null)).isEqualTo("sin detalle");
        assertThat(PantallaConexion.causaRaiz(new RuntimeException())).isEqualTo("RuntimeException");
        assertThat(PantallaConexion.causaRaiz(new RuntimeException("x".repeat(500)))).hasSize(301);
    }

    @Test
    void laClaveDelAdministradorSeCompruebaContraElHashGuardadoLocalmente() {
        String hash = new BCryptPasswordEncoder().encode("Secreta123");
        assertThat(PantallaConexion.esClaveAdminValida(hash, "Secreta123")).isTrue();
        assertThat(PantallaConexion.esClaveAdminValida(hash, "otra")).isFalse();
        assertThat(PantallaConexion.esClaveAdminValida(hash, "")).isFalse();
        assertThat(PantallaConexion.esClaveAdminValida("no-es-un-hash", "Secreta123")).isFalse();
        // Sin hash (ningún administrador ha entrado en esta caja) no hay nada que proteger
        assertThat(PantallaConexion.esClaveAdminValida(null, "lo que sea")).isTrue();
    }
}
