package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Autenticación con BCrypt y creación del administrador por defecto.
 * Corre contra la BD desechable de src/test/resources/application.properties.
 */
@SpringBootTest
@Transactional
class SesionServiceTest {

    @Autowired private SesionService    sesionService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private PasswordEncoder  passwordEncoder;

    @BeforeEach
    @AfterEach
    void limpiarSesion() {
        sesionService.cerrarSesion();
    }

    // ── Admin por defecto ─────────────────────────────────────────────────

    @Test
    void alArrancarExisteElAdminPorDefectoConContrasenaBCrypt() {
        Cajero admin = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();

        assertThat(admin.getRol()).isEqualTo(Cajero.Rol.ADMIN);
        assertThat(admin.isActivo()).isTrue();
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
        assertThat(passwordEncoder.matches("admin123", admin.getContrasenaHash())).isTrue();
    }

    @Test
    void laSiembraNuncaSobrescribeUnUsuarioExistente() {
        // El usuario "admin" existe pero con otra contraseña y degradado a cajero
        Cajero admin = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();
        String hashPersonal = passwordEncoder.encode("mi-clave-secreta");
        admin.setContrasenaHash(hashPersonal);
        admin.setRol(Cajero.Rol.CAJERO);
        admin.setActivo(false);
        cajeroRepo.save(admin);

        sesionService.crearAdminPorDefectoSiNecesario();

        Cajero despues = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();
        assertThat(despues.getContrasenaHash()).isEqualTo(hashPersonal);
        assertThat(despues.getRol()).isEqualTo(Cajero.Rol.CAJERO);
        assertThat(despues.isActivo()).isFalse();
        assertThat(cajeroRepo.count()).isEqualTo(1);
    }

    // ── Autenticación ─────────────────────────────────────────────────────

    @Test
    void autenticaConCredencialesCorrectas() throws Exception {
        Cajero c = sesionService.autenticar("admin", "admin123");

        assertThat(c.getUsuario()).isEqualTo("admin");
        assertThat(sesionService.haySesionActiva()).isTrue();
        assertThat(sesionService.esAdmin()).isTrue();
        assertThat(sesionService.getCajeroActual().getId()).isEqualTo(c.getId());
    }

    @Test
    void elUsuarioNoDistingueMayusculas() throws Exception {
        assertThat(sesionService.autenticar("ADMIN", "admin123").getUsuario()).isEqualTo("admin");
    }

    @Test
    void rechazaContrasenaIncorrecta() {
        assertThatThrownBy(() -> sesionService.autenticar("admin", "incorrecta"))
                .isInstanceOf(SesionService.CredencialesInvalidasException.class);
        assertThat(sesionService.haySesionActiva()).isFalse();
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
    void cerrarSesionLimpiaElEstado() throws Exception {
        sesionService.autenticar("admin", "admin123");
        sesionService.cerrarSesion();

        assertThat(sesionService.haySesionActiva()).isFalse();
        assertThat(sesionService.esAdmin()).isFalse();
        assertThatThrownBy(() -> sesionService.verificarAdmin())
                .isInstanceOf(NegocioException.class);
    }
}
