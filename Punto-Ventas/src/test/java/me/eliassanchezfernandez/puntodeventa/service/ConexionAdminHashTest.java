package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.config.PerfilBaseDatos;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.util.ConexionLocal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El hash del último administrador que entró en la caja (para la pantalla
 * "sin conexión") se borra cuando a ese administrador lo desactivan, lo
 * eliminan, lo degradan o le restablecen la contraseña: su contraseña vieja
 * ya no debe poder cambiar el servidor de esta caja. Todo sobre una carpeta
 * temporal: nunca se toca la configuración real del usuario.
 */
class ConexionAdminHashTest extends BaseServicioTest {

    @Autowired private ConexionService conexionService;

    @TempDir Path carpeta;

    @BeforeEach
    void carpetaTemporal() throws Exception {
        conexionService.usarCarpetaParaPruebas(carpeta);
        // Simula una caja ya configurada (el archivo existe)
        ConexionLocal c = new ConexionLocal();
        c.setModo(PerfilBaseDatos.SQLITE);
        c.guardar(carpeta);
    }

    @AfterEach
    void carpetaReal() {
        conexionService.usarCarpetaParaPruebas(null);
    }

    private String hashGuardado() {
        return ConexionLocal.cargar(carpeta).getAdminHash();
    }

    @Test
    void recordarYOlvidarSoloCuandoCoincide() throws Exception {
        Cajero admin = iniciarComoAdmin();
        conexionService.recordarAdministrador(admin);
        assertThat(hashGuardado()).isEqualTo(admin.getContrasenaHash());
        // Otro hash no borra nada
        assertThat(conexionService.olvidarAdministradorSi("$2a$10$otro")).isFalse();
        assertThat(hashGuardado()).isEqualTo(admin.getContrasenaHash());
        assertThat(conexionService.olvidarAdministradorSi(null)).isFalse();
        // El suyo sí
        assertThat(conexionService.olvidarAdministradorSi(admin.getContrasenaHash())).isTrue();
        assertThat(hashGuardado()).isNull();
        assertThat(conexionService.olvidarAdministradorSi(admin.getContrasenaHash())).isFalse();
    }

    @Test
    void desactivarDegradarRestablecerOEliminarAlAdminBorraSuHash() throws Exception {
        iniciarComoAdmin();
        Cajero segundo = crearUsuario("admin2", Cajero.Rol.ADMIN, Set.of());
        conexionService.recordarAdministrador(segundo);
        assertThat(hashGuardado()).isEqualTo(segundo.getContrasenaHash());

        // Restablecer su contraseña
        cajeroService.cambiarContrasena(segundo.getId(), "NuevaClave9");
        assertThat(hashGuardado()).isNull();

        Cajero releido = cajeroRepo.findById(segundo.getId()).orElseThrow();
        conexionService.recordarAdministrador(releido);
        assertThat(hashGuardado()).isNotNull();
        // Desactivarlo
        cajeroService.cambiarEstado(segundo.getId(), false);
        assertThat(hashGuardado()).isNull();

        cajeroService.cambiarEstado(segundo.getId(), true);
        releido = cajeroRepo.findById(segundo.getId()).orElseThrow();
        conexionService.recordarAdministrador(releido);
        assertThat(hashGuardado()).isNotNull();
        // Degradarlo a cajero por "actualizar"
        cajeroService.actualizar(segundo.getId(), "admin2", "Usuario admin2", Cajero.Rol.CAJERO, true, null, null);
        assertThat(hashGuardado()).isNull();

        // Un cajero que no era admin no toca el hash del admin recordado
        Cajero admin = sesionService.getCajeroActual();
        conexionService.recordarAdministrador(admin);
        cajeroService.cambiarContrasena(segundo.getId(), "OtraClave9");
        assertThat(hashGuardado()).isEqualTo(admin.getContrasenaHash());

        // Eliminar a un admin sin historial
        Cajero tercero = crearUsuario("admin3", Cajero.Rol.ADMIN, Set.of());
        conexionService.recordarAdministrador(tercero);
        cajeroService.eliminar(tercero.getId());
        assertThat(hashGuardado()).isNull();
    }
}
