package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** QA v1.0.0 — defectos (ya corregidos) en la administración de usuarios (Configuración → Cajeros). */
class QaUsuariosTest extends BaseServicioTest {

    /**
     * A-2 (alto). El administrador editaba SU propio usuario con contraseña
     * nueva: se guardaba con debeCambiarContrasena=true y se refrescaba la
     * sesión, así que todo lo que hiciera después fallaba con "Debes cambiar tu
     * contraseña" y al volver a entrar se le obligaba a cambiar la contraseña que
     * él mismo acababa de elegir. Ahora: la eligió él, su sesión sigue y la nueva
     * contraseña sirve para entrar sin cambio obligatorio. Para OTRO usuario se
     * conserva el cambio obligatorio al entrar.
     */
    @Test
    void elAdminQueCambiaSuPropiaContrasenaDesdeUsuariosNoDebeQuedarBloqueadoEnSuSesion() throws Exception {
        Cajero admin = iniciarComoAdmin();

        cajeroService.actualizar(admin.getId(), admin.getUsuario(), admin.getNombreCompleto(),
                Cajero.Rol.ADMIN, true, "NuevaClave2026", null);

        assertThat(sesionService.haySesionActiva())
                .as("la sesión del administrador debe seguir siendo utilizable tras cambiar su propia contraseña")
                .isTrue();
        assertThatCode(() -> sesionService.verificarPermiso(Permiso.EDITAR_PRODUCTOS))
                .as("no debe exigírsele cambiar la contraseña que él mismo acaba de definir")
                .doesNotThrowAnyException();
        assertThat(cajeroRepo.findById(admin.getId()).orElseThrow().isDebeCambiarContrasena()).isFalse();

        // La contraseña nueva entra sin cambio obligatorio
        sesionService.cerrarSesion();
        Cajero deNuevo = sesionService.autenticar(admin.getUsuario(), "NuevaClave2026");
        assertThat(deNuevo.isDebeCambiarContrasena()).isFalse();

        // Para otro usuario sí: la definió el admin, debe cambiarla al entrar
        Cajero cajera = crearUsuario("cajeraQA", Cajero.Rol.CAJERO, java.util.EnumSet.noneOf(Permiso.class));
        cajeroService.actualizar(cajera.getId(), cajera.getUsuario(), cajera.getNombreCompleto(),
                Cajero.Rol.CAJERO, true, "OtraClave2026", null);
        assertThat(cajeroRepo.findById(cajera.getId()).orElseThrow().isDebeCambiarContrasena()).isTrue();
        // (la transacción de la prueba se revierte: la contraseña del admin vuelve a ser la sembrada)
    }
}
