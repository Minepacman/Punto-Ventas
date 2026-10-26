package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Roles, permisos por usuario (USR-13/16/17), gerente (USR-09) y autorización con clave (USR-19). */
class PermisosYAutorizacionTest extends BaseServicioTest {

    @org.springframework.beans.factory.annotation.Autowired
    private AutorizacionService autorizacionService;

    @Test
    void losPermisosPorDefectoDeCadaRol() {
        assertThat(Permiso.porDefecto(Cajero.Rol.ADMIN)).containsExactlyInAnyOrder(Permiso.values());
        assertThat(Permiso.porDefecto(Cajero.Rol.GERENTE))
                .containsExactlyInAnyOrder(Permiso.EDITAR_PRODUCTOS, Permiso.CAMBIAR_PRECIOS);
        assertThat(Permiso.porDefecto(Cajero.Rol.CAJERO)).isEmpty();
    }

    @Test
    void elAdminConcedeYRevocaPermisosPorUsuario() throws Exception {
        iniciarComoAdmin();
        Cajero cajera = crearUsuario("cajera", Cajero.Rol.CAJERO, EnumSet.of(Permiso.VER_HISTORIAL));
        Cajero gerente = crearUsuario("gerente", Cajero.Rol.GERENTE, EnumSet.of(Permiso.EDITAR_PRODUCTOS)); // sin CAMBIAR_PRECIOS

        assertThat(cajera.tienePermiso(Permiso.VER_HISTORIAL)).isTrue();
        assertThat(cajera.tienePermiso(Permiso.CANCELAR_VENTA)).isFalse();
        assertThat(cajera.getPermisosConcedidos()).containsExactly(Permiso.VER_HISTORIAL);
        assertThat(gerente.tienePermiso(Permiso.EDITAR_PRODUCTOS)).isTrue();
        assertThat(gerente.tienePermiso(Permiso.CAMBIAR_PRECIOS)).isFalse();
        assertThat(gerente.getPermisosRevocados()).containsExactly(Permiso.CAMBIAR_PRECIOS);

        // Cambio posterior: ahora la cajera también puede aplicar mayoreo y ya no ver historial
        cajeroService.actualizar(cajera.getId(), "cajera", "Cajera", Cajero.Rol.CAJERO, true, null,
                EnumSet.of(Permiso.APLICAR_MAYOREO));
        Cajero recargada = cajeroRepo.findById(cajera.getId()).orElseThrow();
        assertThat(recargada.getPermisosEfectivos()).containsExactly(Permiso.APLICAR_MAYOREO);

        // El rol ADMIN ignora revocaciones
        Cajero admin2 = crearUsuario("admin2", Cajero.Rol.ADMIN, EnumSet.noneOf(Permiso.class));
        assertThat(admin2.getPermisosEfectivos()).containsExactlyInAnyOrder(Permiso.values());
    }

    @Test
    void elServicioRechazaAccionesSinPermiso() throws Exception {
        iniciarComoCajero("sinpermisos", null);

        assertThatThrownBy(() -> sesionService.verificarPermiso(Permiso.CANCELAR_VENTA))
                .isInstanceOf(PermisoRequeridoException.class)
                .hasMessageContaining("administrador");
        assertThatThrownBy(() -> productoService.guardar(new me.eliassanchezfernandez.puntodeventa.model.Producto()))
                .isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> ventaService.ventasEntre(null, null))
                .isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> ventaService.resumenDelDia())
                .isInstanceOf(PermisoRequeridoException.class);
    }

    @Test
    void unaAutorizacionDeAdminCubreUnaAccionYSeConsume() throws Exception {
        iniciarComoCajero("cajero", null);

        assertThatThrownBy(() -> autorizacionService.autorizar("admin", "incorrecta", Permiso.CANCELAR_VENTA))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> autorizacionService.autorizar("cajero", CLAVE_PRUEBA, Permiso.CANCELAR_VENTA))
                .isInstanceOf(NegocioException.class);   // no es admin

        Autorizacion aut = autorizacionService.autorizar("admin", "admin123", Permiso.CANCELAR_VENTA);
        assertThat(aut.vigente()).isTrue();
        assertThat(aut.nombreAdministrador()).isEqualTo("Administrador");

        sesionService.verificarPermiso(Permiso.CANCELAR_VENTA, aut);          // no lanza
        assertThatThrownBy(() -> sesionService.verificarPermiso(Permiso.VENDER_SIN_STOCK, aut))
                .isInstanceOf(PermisoRequeridoException.class);               // otro permiso: no sirve

        aut.consumir();
        assertThatThrownBy(() -> sesionService.verificarPermiso(Permiso.CANCELAR_VENTA, List.of(aut)))
                .isInstanceOf(PermisoRequeridoException.class);               // consumida: ya no sirve
    }

    @Test
    void elDialogoDeAutorizacionTambienCuentaIntentosYBloquea() throws Exception {
        iniciarComoCajero("insistente", null);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> autorizacionService.autorizar("admin", "mal", Permiso.CANCELAR_VENTA))
                    .isInstanceOf(NegocioException.class);
        }
        assertThat(cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow().isBloqueado()).isTrue();
        assertThatThrownBy(() -> autorizacionService.autorizar("admin", "admin123", Permiso.CANCELAR_VENTA))
                .isInstanceOf(NegocioException.class).hasMessageContaining("bloqueado");
    }

    @Test
    void unAdminQueDebeCambiarContrasenaNoPuedeAutorizar() throws Exception {
        iniciarComoCajero("cajero2", null);
        Cajero admin = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();
        admin.setDebeCambiarContrasena(true);
        cajeroRepo.save(admin);

        assertThatThrownBy(() -> autorizacionService.autorizar("admin", "admin123", Permiso.CANCELAR_VENTA))
                .isInstanceOf(NegocioException.class).hasMessageContaining("cambiar su contraseña");
    }

    @Test
    void elGerenteSoloDesactivaUsuariosNoAdmin() throws Exception {
        iniciarComoAdmin();
        Cajero cajero = crearUsuario("caj", Cajero.Rol.CAJERO, null);
        Cajero gerente = crearUsuario("ger", Cajero.Rol.GERENTE, null);
        Cajero admin = sesionService.getCajeroActual();

        iniciarComo("ger", CLAVE_PRUEBA);
        assertThat(cajeroService.listar()).isNotEmpty();
        assertThatThrownBy(() -> cajeroService.crear("otro", "Otro", CLAVE_PRUEBA, Cajero.Rol.CAJERO))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> cajeroService.eliminar(cajero.getId()))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> cajeroService.actualizar(cajero.getId(), "caj", "Caj", Cajero.Rol.ADMIN, true))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> cajeroService.cambiarEstado(admin.getId(), false))
                .isInstanceOf(NegocioException.class).hasMessageContaining("administrador");
        assertThatThrownBy(() -> cajeroService.cambiarEstado(gerente.getId(), false))
                .isInstanceOf(NegocioException.class).hasMessageContaining("propio");

        cajeroService.cambiarEstado(cajero.getId(), false);
        assertThat(cajeroRepo.findById(cajero.getId()).orElseThrow().isActivo()).isFalse();

        assertThatThrownBy(() -> cajeroService.cambiarEstado(cajero.getId(), true))
                .isInstanceOf(NegocioException.class);   // reactivar: solo ADMIN
        assertThatThrownBy(() -> cajeroService.desbloquear(cajero.getId()))
                .isInstanceOf(NegocioException.class);   // desbloquear: solo ADMIN
    }

    @Test
    void elGerentePuedeEditarProductosPeroNoVerHistorialNiCorte() throws Exception {
        iniciarComoAdmin();
        crearUsuario("encargado", Cajero.Rol.GERENTE, null);
        iniciarComo("encargado", CLAVE_PRUEBA);

        assertThat(producto("Creado por gerente", 10, 1).getId()).isNotNull();
        assertThatThrownBy(() -> ventaService.ventasEntre(null, null)).isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> ventaService.resumenDelDia()).isInstanceOf(PermisoRequeridoException.class);
        Set<Permiso> efectivos = sesionService.getCajeroActual().getPermisosEfectivos();
        assertThat(efectivos).doesNotContain(Permiso.VER_GANANCIAS, Permiso.CANCELAR_VENTA, Permiso.VENDER_SIN_STOCK);
    }
}
