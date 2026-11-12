package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El límite de fallos de la caja (10 en 5 min) no se reinicia con cualquier
 * inicio de sesión correcto: solo si todos los fallos fueron del mismo usuario
 * que acaba de entrar. Así un cajero no puede "limpiar" el contador entrando
 * con su clave entre tandas de intentos contra la del administrador.
 */
class LimiteTerminalTest extends BaseServicioTest {

    @Test
    void entrarConOtroUsuarioNoBorraLosFallosContraElAdmin() throws Exception {
        iniciarComoAdmin();
        crearUsuario("cajero_lim", Cajero.Rol.CAJERO, Set.of());
        sesionService.cerrarSesion();

        // 4 intentos contra admin (menos de 5: el usuario no se bloquea)
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> sesionService.autenticar("admin", "adivino" + 1)).isInstanceOf(SesionService.CredencialesInvalidasException.class);
        }
        // El cajero entra con su clave: la caja NO olvida esos fallos
        assertThat(iniciarComo("cajero_lim", CLAVE_PRUEBA).getUsuario()).isEqualTo("cajero_lim");
        sesionService.cerrarSesion();
        // 6 fallos más contra usuarios inexistentes completan los 10 de la caja
        for (int i = 0; i < SesionService.MAX_FALLOS_TERMINAL - 4; i++) {
            String u = "nadie" + i;
            assertThatThrownBy(() -> sesionService.autenticar(u, "x")).isInstanceOf(SesionService.CredencialesInvalidasException.class);
        }
        assertThat(sesionService.segundosDeEsperaTerminal()).isPositive();
        assertThatThrownBy(() -> sesionService.autenticar("cajero_lim", CLAVE_PRUEBA))
                .isInstanceOf(SesionService.CredencialesInvalidasException.class).hasMessageContaining("Demasiados intentos");
    }

    @Test
    void losFallosPropiosSiSeOlvidanAlEntrarBien() throws Exception {
        iniciarComoAdmin();
        crearUsuario("cajero_ok", Cajero.Rol.CAJERO, Set.of());
        sesionService.cerrarSesion();
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> sesionService.autenticar("cajero_ok", "mal")).isInstanceOf(SesionService.CredencialesInvalidasException.class);
        }
        // Se equivocó de tecla tres veces y luego entró: la caja vuelve a cero
        assertThat(iniciarComo("cajero_ok", CLAVE_PRUEBA)).isNotNull();
        sesionService.cerrarSesion();
        for (int i = 0; i < SesionService.MAX_FALLOS_TERMINAL - 1; i++) {
            String u = "nadie" + i;
            assertThatThrownBy(() -> sesionService.autenticar(u, "x")).isInstanceOf(SesionService.CredencialesInvalidasException.class);
        }
        // 9 fallos nuevos (no 12): todavía no hay espera
        assertThat(sesionService.segundosDeEsperaTerminal()).isZero();
    }
}
