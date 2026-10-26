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

/** Administración de usuarios: solo ADMIN, hash BCrypt, último admin protegido. */
@SpringBootTest
@Transactional
class CajeroServiceTest {

    @Autowired private CajeroService    cajeroService;
    @Autowired private SesionService    sesionService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private PasswordEncoder  passwordEncoder;

    @BeforeEach
    void iniciarComoAdmin() throws Exception {
        sesionService.autenticar("admin", "admin123");
    }

    @AfterEach
    void cerrar() {
        sesionService.cerrarSesion();
    }

    @Test
    void elAdminCreaUsuariosConContrasenaHasheada() {
        Cajero c = cajeroService.crear("maria", "María López", "clave1234", Cajero.Rol.CAJERO);

        assertThat(c.getId()).isNotNull();
        assertThat(c.isActivo()).isTrue();
        assertThat(c.getContrasenaHash()).isNotEqualTo("clave1234");
        assertThat(passwordEncoder.matches("clave1234", c.getContrasenaHash())).isTrue();
        assertThat(cajeroService.listar()).extracting(Cajero::getUsuario).contains("maria");
    }

    @Test
    void unCajeroNoPuedeAdministrarUsuarios() throws Exception {
        cajeroService.crear("pedro", "Pedro Pérez", "clave1234", Cajero.Rol.CAJERO);
        sesionService.cerrarSesion();
        sesionService.autenticar("pedro", "clave1234");

        assertThatThrownBy(() -> cajeroService.crear("otro", "Otro", "clave1234", Cajero.Rol.CAJERO))
                .isInstanceOf(NegocioException.class)
                .hasMessageContaining("administrador");
        assertThatThrownBy(() -> cajeroService.listar()).isInstanceOf(NegocioException.class);
        assertThat(cajeroRepo.findByUsuarioIgnoreCase("otro")).isEmpty();
    }

    @Test
    void sinSesionNoSePuedenAdministrarUsuarios() {
        sesionService.cerrarSesion();
        assertThatThrownBy(() -> cajeroService.crear("x", "X", "clave1234", Cajero.Rol.CAJERO))
                .isInstanceOf(NegocioException.class);
    }

    @Test
    void rechazaUsuarioDuplicadoSinImportarMayusculas() {
        cajeroService.crear("ana", "Ana", "clave1234", Cajero.Rol.CAJERO);

        assertThatThrownBy(() -> cajeroService.crear("ANA", "Ana 2", "clave1234", Cajero.Rol.CAJERO))
                .isInstanceOf(NegocioException.class)
                .hasMessageContaining("Ya existe");
    }

    @Test
    void rechazaDatosInvalidos() {
        assertThatThrownBy(() -> cajeroService.crear("", "Nombre", "clave1234", Cajero.Rol.CAJERO))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> cajeroService.crear("con espacio", "Nombre", "clave1234", Cajero.Rol.CAJERO))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> cajeroService.crear("luis", "", "clave1234", Cajero.Rol.CAJERO))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> cajeroService.crear("luis", "Luis", "123", Cajero.Rol.CAJERO))
                .isInstanceOf(NegocioException.class)
                .hasMessageContaining("contraseña");
    }

    @Test
    void cambiarContrasenaPermiteAutenticarConLaNueva() throws Exception {
        Cajero c = cajeroService.crear("rosa", "Rosa", "vieja1234", Cajero.Rol.CAJERO);

        cajeroService.cambiarContrasena(c.getId(), "nueva5678");

        sesionService.cerrarSesion();
        assertThatThrownBy(() -> sesionService.autenticar("rosa", "vieja1234"))
                .isInstanceOf(SesionService.CredencialesInvalidasException.class);
        assertThat(sesionService.autenticar("rosa", "nueva5678").getUsuario()).isEqualTo("rosa");
    }

    @Test
    void desactivarImpideIniciarSesion() throws Exception {
        Cajero c = cajeroService.crear("juan", "Juan", "clave1234", Cajero.Rol.CAJERO);

        cajeroService.cambiarEstado(c.getId(), false);

        sesionService.cerrarSesion();
        assertThatThrownBy(() -> sesionService.autenticar("juan", "clave1234"))
                .isInstanceOf(SesionService.CredencialesInvalidasException.class);
    }

    @Test
    void noSePuedeEliminarNiDesactivarAlUltimoAdmin() {
        Cajero admin = sesionService.getCajeroActual();

        assertThatThrownBy(() -> cajeroService.eliminar(admin.getId()))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> cajeroService.cambiarEstado(admin.getId(), false))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> cajeroService.actualizar(admin.getId(), "admin", "Administrador",
                Cajero.Rol.CAJERO, true))
                .isInstanceOf(NegocioException.class);

        assertThat(cajeroRepo.countByRolAndActivoTrue(Cajero.Rol.ADMIN)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void unSegundoAdminPermiteDegradarAlOtroPeroNoASiMismo() throws Exception {
        Cajero admin2 = cajeroService.crear("admin2", "Segundo Admin", "clave1234", Cajero.Rol.ADMIN);
        Cajero admin1 = sesionService.getCajeroActual();

        // admin1 no puede degradarse a sí mismo aunque haya otro admin
        assertThatThrownBy(() -> cajeroService.actualizar(admin1.getId(), "admin", "Administrador",
                Cajero.Rol.CAJERO, true))
                .isInstanceOf(NegocioException.class);

        // pero sí puede degradar a admin2
        Cajero degradado = cajeroService.actualizar(admin2.getId(), "admin2", "Segundo Admin",
                Cajero.Rol.CAJERO, true);
        assertThat(degradado.getRol()).isEqualTo(Cajero.Rol.CAJERO);
    }

    @Test
    void eliminarUsuarioFunciona() {
        Cajero c = cajeroService.crear("temporal", "Temporal", "clave1234", Cajero.Rol.CAJERO);

        cajeroService.eliminar(c.getId());

        assertThat(cajeroRepo.findById(c.getId())).isEmpty();
    }
}
