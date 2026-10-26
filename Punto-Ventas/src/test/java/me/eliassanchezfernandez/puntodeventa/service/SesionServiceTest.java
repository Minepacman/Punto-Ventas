package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Autenticación con BCrypt, administrador por defecto y bloqueo por intentos. */
class SesionServiceTest extends BaseServicioTest {

    // ── Admin por defecto ─────────────────────────────────────────────────

    @Test
    void alArrancarExisteElAdminPorDefectoConContrasenaBCryptYDebeCambiarla() {
        Cajero admin = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();

        assertThat(admin.getRol()).isEqualTo(Cajero.Rol.ADMIN);
        assertThat(admin.isActivo()).isTrue();
        assertThat(admin.isDebeCambiarContrasena()).isTrue();
        assertThat(admin.getContrasenaHash()).startsWith("$2").isNotEqualTo("admin123");
        assertThat(passwordEncoder.matches("admin123", admin.getContrasenaHash())).isTrue();
    }

    @Test
    void laSiembraDelAdminEsIdempotente() {
        long antes = cajeroRepo.count();

        sesionService.crearAdminPorDefectoSiNecesario();
        sesionService.crearAdminPorDefectoSiNecesario();

        assertThat(cajeroRepo.count()).isEqualTo(antes);
        assertThat(cajeroRepo.countByRolAndActivoTrue(Cajero.Rol.ADMIN)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void laSiembraCreaElAdminSiLaTablaEstaVacia() {
        cajeroRepo.deleteAll();
        assertThat(cajeroRepo.count()).isZero();

        sesionService.crearAdminPorDefectoSiNecesario();

        Cajero admin = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();
        assertThat(admin.getRol()).isEqualTo(Cajero.Rol.ADMIN);
        assertThat(admin.isDebeCambiarContrasena()).isTrue();
        assertThat(passwordEncoder.matches("admin123", admin.getContrasenaHash())).isTrue();
    }

    @Test
    void laSiembraNuncaSobrescribeUnUsuarioExistente() {
        Cajero admin = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();
        String hashPersonal = passwordEncoder.encode("mi-clave-secreta1");
        admin.setContrasenaHash(hashPersonal);
        admin.setRol(Cajero.Rol.CAJERO);
        admin.setActivo(false);
        admin.setDebeCambiarContrasena(false);
        cajeroRepo.save(admin);

        sesionService.crearAdminPorDefectoSiNecesario();

        Cajero despues = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();
        assertThat(despues.getContrasenaHash()).isEqualTo(hashPersonal);
        assertThat(despues.getRol()).isEqualTo(Cajero.Rol.CAJERO);
        assertThat(despues.isActivo()).isFalse();
        assertThat(despues.isDebeCambiarContrasena()).isFalse();
        assertThat(cajeroRepo.count()).isEqualTo(1);
    }

    @Test
    void unAdminAntiguoConLaClavePorDefectoQuedaObligadoACambiarla() {
        Cajero admin = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();
        admin.setDebeCambiarContrasena(null);   // fila anterior a este campo
        cajeroRepo.save(admin);

        sesionService.crearAdminPorDefectoSiNecesario();

        assertThat(cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow().isDebeCambiarContrasena()).isTrue();
    }

    // ── Autenticación ─────────────────────────────────────────────────────

    @Test
    void autenticaConCredencialesCorrectas() throws Exception {
        Cajero c = iniciarComoAdmin();

        assertThat(c.getUsuario()).isEqualTo("admin");
        assertThat(sesionService.haySesionActiva()).isTrue();
        assertThat(sesionService.esAdmin()).isTrue();
        assertThat(sesionService.getCajeroActual().getId()).isEqualTo(c.getId());
    }

    @Test
    void elUsuarioNoDistingueMayusculas() throws Exception {
        assertThat(iniciarComo("ADMIN", "admin123").getUsuario()).isEqualTo("admin");
    }

    @Test
    void rechazaContrasenaIncorrectaYCuentaElIntento() {
        assertThatThrownBy(() -> sesionService.autenticar("admin", "incorrecta"))
                .isInstanceOf(SesionService.CredencialesInvalidasException.class);
        assertThat(sesionService.haySesionActiva()).isFalse();
        assertThat(cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow().getIntentosFallidosOCero()).isEqualTo(1);
    }

    @Test
    void rechazaUsuarioInexistente() {
        assertThatThrownBy(() -> sesionService.autenticar("nadie", "admin123"))
                .isInstanceOf(SesionService.CredencialesInvalidasException.class);
    }

    @Test
    void rechazaUsuarioInactivo() {
        Cajero inactivo = new Cajero();
        inactivo.setUsuario("inactivo");
        inactivo.setNombreCompleto("Usuario Inactivo");
        inactivo.setContrasenaHash(passwordEncoder.encode("clave1234"));
        inactivo.setRol(Cajero.Rol.CAJERO);
        inactivo.setActivo(false);
        cajeroRepo.save(inactivo);

        assertThatThrownBy(() -> sesionService.autenticar("inactivo", "clave1234"))
                .isInstanceOf(SesionService.CredencialesInvalidasException.class);
    }

    @Test
    void alQuintoIntentoFallidoElUsuarioSeBloqueaTemporalmente() throws Exception {
        iniciarComoCajero("torpe", null);
        sesionService.cerrarSesion();

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> sesionService.autenticar("torpe", "mal"))
                    .isInstanceOf(SesionService.CredencialesInvalidasException.class);
        }
        Cajero c = cajeroRepo.findByUsuarioIgnoreCase("torpe").orElseThrow();
        assertThat(c.isBloqueado()).isTrue();
        assertThat(c.getBloqueadoHasta()).isAfter(LocalDateTime.now().plusMinutes(10));

        // Con la clave correcta tampoco entra mientras dure el bloqueo
        assertThatThrownBy(() -> sesionService.autenticar("torpe", CLAVE_PRUEBA))
                .isInstanceOf(SesionService.CredencialesInvalidasException.class)
                .hasMessageContaining("bloqueado");

        // El bloqueo vence solo
        c.setBloqueadoHasta(LocalDateTime.now().minusMinutes(1));
        cajeroRepo.save(c);
        assertThat(sesionService.autenticar("torpe", CLAVE_PRUEBA).getUsuario()).isEqualTo("torpe");
        assertThat(cajeroRepo.findByUsuarioIgnoreCase("torpe").orElseThrow().getIntentosFallidosOCero()).isZero();
    }

    @Test
    void elUnicoAdminTambienSeBloqueaPeroSoloTemporalmente() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> sesionService.autenticar("admin", "mal"))
                    .isInstanceOf(SesionService.CredencialesInvalidasException.class);
        }
        Cajero admin = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();
        assertThat(admin.isBloqueado()).isTrue();
        assertThat(admin.getBloqueadoHasta()).isBefore(LocalDateTime.now().plusHours(25));
        assertThat(SesionService.minutosDeBloqueo(5)).isEqualTo(15);
        assertThat(SesionService.minutosDeBloqueo(10)).isEqualTo(60);
        assertThat(SesionService.minutosDeBloqueo(15)).isEqualTo(120);
    }

    @Test
    void cerrarSesionLimpiaElEstado() throws Exception {
        iniciarComoAdmin();
        sesionService.cerrarSesion();

        assertThat(sesionService.haySesionActiva()).isFalse();
        assertThat(sesionService.esAdmin()).isFalse();
        assertThatThrownBy(() -> sesionService.verificarAdmin())
                .isInstanceOf(NegocioException.class);
    }

    @Test
    void unUsuarioDesactivadoMientrasOperaPierdeLaSesion() throws Exception {
        Cajero c = iniciarComoCajero("fugaz", null);
        Cajero enBd = cajeroRepo.findById(c.getId()).orElseThrow();
        enBd.setActivo(false);
        cajeroRepo.save(enBd);

        assertThatThrownBy(() -> sesionService.requerirSesion())
                .isInstanceOf(NegocioException.class).hasMessageContaining("invalidada");
        assertThat(sesionService.haySesionActiva()).isFalse();
    }
}
