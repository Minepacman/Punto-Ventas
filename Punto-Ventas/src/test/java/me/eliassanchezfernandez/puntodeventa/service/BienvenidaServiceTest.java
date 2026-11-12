package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;

import static me.eliassanchezfernandez.puntodeventa.service.BienvenidaService.Paso.ASISTENTE_INICIAL;
import static me.eliassanchezfernandez.puntodeventa.service.BienvenidaService.Paso.DONACION;
import static me.eliassanchezfernandez.puntodeventa.service.BienvenidaService.Paso.TUTORIAL_GENERAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flujo tras iniciar sesión: orden de los pasos, asistente inicial por
 * instalación (solo ADMIN, auditado), tutorial general por usuario y donación
 * solo para el administrador.
 */
class BienvenidaServiceTest extends BaseServicioTest {

    @Autowired private BienvenidaService     bienvenidaService;
    @Autowired private ConfiguracionService  configuracionService;
    @Autowired private AuditoriaService      auditoriaService;

    // ── Orden del flujo (Java puro) ───────────────────────────────────────

    @Test
    void elAdminLaPrimeraVezPasaPorAsistenteTutorialYDonacionEnEseOrden() {
        assertThat(BienvenidaService.pasos(Cajero.Rol.ADMIN, false, false))
                .containsExactly(ASISTENTE_INICIAL, TUTORIAL_GENERAL, DONACION);
    }

    @Test
    void elAdminConTodoHechoSoloVeLaDonacion() {
        assertThat(BienvenidaService.pasos(Cajero.Rol.ADMIN, true, true)).containsExactly(DONACION);
        assertThat(BienvenidaService.pasos(Cajero.Rol.ADMIN, true, false)).containsExactly(TUTORIAL_GENERAL, DONACION);
        assertThat(BienvenidaService.pasos(Cajero.Rol.ADMIN, false, true)).containsExactly(ASISTENTE_INICIAL, DONACION);
    }

    @Test
    void cajeroYGerenteSoloVenElTutorialLaPrimeraVezYNuncaLaDonacionNiElAsistente() {
        assertThat(BienvenidaService.pasos(Cajero.Rol.CAJERO, false, false)).containsExactly(TUTORIAL_GENERAL);
        assertThat(BienvenidaService.pasos(Cajero.Rol.GERENTE, false, false)).containsExactly(TUTORIAL_GENERAL);
        assertThat(BienvenidaService.pasos(Cajero.Rol.CAJERO, false, true)).isEmpty();
        assertThat(BienvenidaService.pasos(Cajero.Rol.GERENTE, true, true)).isEmpty();
    }

    @Test
    void sinSesionNoHayPasosPendientes() {
        sesionService.cerrarSesion();
        assertThat(bienvenidaService.pasosPendientes()).isEmpty();
    }

    @Test
    void losPasosPendientesSalenDeLaBaseDeDatos() throws Exception {
        iniciarComoAdmin();
        assertThat(bienvenidaService.pasosPendientes()).containsExactly(ASISTENTE_INICIAL, TUTORIAL_GENERAL, DONACION);

        bienvenidaService.completarAsistenteInicial("Abarrotes Lupita");
        bienvenidaService.marcarTutorialGeneralVisto();

        assertThat(bienvenidaService.pasosPendientes()).containsExactly(DONACION);
    }

    // ── Asistente inicial ────────────────────────────────────────────────

    @Test
    void completarElAsistenteGuardaElNombreLoMarcaHechoYLoAudita() throws Exception {
        Cajero admin = iniciarComoAdmin();
        assertThat(bienvenidaService.asistenteInicialPendiente()).isTrue();

        Configuracion g = bienvenidaService.completarAsistenteInicial("  Abarrotes Lupita  ");

        assertThat(g.getNombreTienda()).isEqualTo("Abarrotes Lupita");
        assertThat(g.isAsistenteInicialHecho()).isTrue();
        assertThat(configuracionService.getNombreTienda()).isEqualTo("Abarrotes Lupita");
        assertThat(bienvenidaService.asistenteInicialPendiente()).isFalse();
        assertThat(auditoriaService.ultimas())
                .anySatisfy(a -> {
                    assertThat(a.getAccion()).isEqualTo("ASISTENTE_INICIAL");
                    assertThat(a.getDetalle()).contains("Abarrotes Lupita");
                    assertThat(a.getUsuarioLogin()).isEqualTo(admin.getUsuario());
                });
    }

    @Test
    void elNombreDelNegocioEsObligatorioYConLargoMaximo() throws Exception {
        iniciarComoAdmin();
        assertThatThrownBy(() -> bienvenidaService.completarAsistenteInicial("   "))
                .isInstanceOf(NegocioException.class).hasMessageContaining("nombre del negocio");
        assertThatThrownBy(() -> bienvenidaService.completarAsistenteInicial("x".repeat(151)))
                .isInstanceOf(NegocioException.class).hasMessageContaining("150");
        assertThat(bienvenidaService.asistenteInicialPendiente()).isTrue();
    }

    @Test
    void omitirElAsistenteNoCambiaElNombrePeroYaNoSeVuelveAPreguntar() throws Exception {
        iniciarComoAdmin();
        String nombreAntes = configuracionService.getNombreTienda();

        bienvenidaService.omitirAsistenteInicial();

        assertThat(configuracionService.getNombreTienda()).isEqualTo(nombreAntes);
        assertThat(bienvenidaService.asistenteInicialPendiente()).isFalse();
        assertThat(bienvenidaService.pasosPendientes()).doesNotContain(ASISTENTE_INICIAL);
        assertThat(auditoriaService.ultimas()).extracting(Auditoria::getAccion).contains("ASISTENTE_INICIAL_OMITIDO");
    }

    @Test
    void soloElAdministradorPuedeCompletarUOmitirElAsistente() throws Exception {
        iniciarComoCajero("caja1", Set.of());
        assertThat(bienvenidaService.asistenteInicialPendiente()).isFalse();
        assertThatThrownBy(() -> bienvenidaService.completarAsistenteInicial("Tienda"))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> bienvenidaService.omitirAsistenteInicial())
                .isInstanceOf(NegocioException.class);
    }

    // ── Tutorial general por usuario ─────────────────────────────────────

    @Test
    void elTutorialSeMarcaPorUsuarioYSePuedeReiniciar() throws Exception {
        iniciarComoAdmin();
        assertThat(bienvenidaService.tutorialGeneralVisto()).isFalse();

        bienvenidaService.marcarTutorialGeneralVisto();
        bienvenidaService.marcarTutorialGeneralVisto();   // idempotente
        assertThat(bienvenidaService.tutorialGeneralVisto()).isTrue();
        assertThat(cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow().isTutorialGeneralVisto()).isTrue();

        bienvenidaService.reiniciarTutorialGeneral();
        assertThat(bienvenidaService.tutorialGeneralVisto()).isFalse();
    }

    @Test
    void laMarcaDelTutorialDeUnUsuarioNoAfectaAOtro() throws Exception {
        iniciarComoAdmin();
        bienvenidaService.marcarTutorialGeneralVisto();
        crearUsuario("caja2", Cajero.Rol.CAJERO, Set.of());

        iniciarComo("caja2", CLAVE_PRUEBA);
        assertThat(bienvenidaService.tutorialGeneralVisto()).isFalse();
        assertThat(bienvenidaService.pasosPendientes()).containsExactly(TUTORIAL_GENERAL);
    }

    @Test
    void elTutorialGeneralEsIndependienteDelDeEstadisticas() throws Exception {
        iniciarComoAdmin();
        bienvenidaService.marcarTutorialGeneralVisto();
        Cajero admin = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();
        assertThat(admin.isTutorialGeneralVisto()).isTrue();
        assertThat(admin.isTutorialEstadisticasVisto()).isFalse();
    }

    @Test
    void elTutorialRequiereSesion() {
        sesionService.cerrarSesion();
        assertThatThrownBy(() -> bienvenidaService.marcarTutorialGeneralVisto()).isInstanceOf(NegocioException.class);
    }

    // ── Donación ─────────────────────────────────────────────────────────

    @Test
    void laDonacionSoloLaVeElAdministrador() throws Exception {
        iniciarComoAdmin();
        assertThat(bienvenidaService.mostrarDonacion()).isTrue();

        iniciarComoCajero("caja3", Set.of());
        assertThat(bienvenidaService.mostrarDonacion()).isFalse();

        sesionService.cerrarSesion();
        assertThat(bienvenidaService.mostrarDonacion()).isFalse();
    }
}
