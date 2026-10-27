package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.SesionMovil;
import me.eliassanchezfernandez.puntodeventa.repository.SesionMovilRepository;
import me.eliassanchezfernandez.puntodeventa.servidor.RelojMutable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tokens de sesión móvil: emisión, caducidad (48 h / permanente), revocación y administración. */
class SesionMovilServiceTest extends BaseServicioTest {

    @Autowired private SesionMovilService sesionMovilService;
    @Autowired private SesionMovilRepository sesionRepo;
    @Autowired private AccesoMovilService accesoMovilService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private AuditoriaService auditoriaService;

    private Clock relojOriginal;
    private RelojMutable reloj;

    @BeforeEach
    void fijarReloj() {
        relojOriginal = (Clock) ReflectionTestUtils.getField(sesionMovilService, "reloj");
        reloj = new RelojMutable(Instant.parse("2026-03-01T12:00:00Z"));
        ReflectionTestUtils.setField(sesionMovilService, "reloj", reloj);
    }

    @AfterEach
    void restaurarReloj() {
        ReflectionTestUtils.setField(sesionMovilService, "reloj", relojOriginal);
    }

    private Cajero usuarioListo(String login, Cajero.Rol rol, Set<Permiso> permisos) throws Exception {
        iniciarComoAdmin();
        Cajero c = crearUsuario(login, rol, permisos);
        c.setDebeCambiarContrasena(false);
        return cajeroRepo.save(c);
    }

    // ── Emisión y validación ──────────────────────────────────────────────

    @Test
    void elTokenTiene256BitsYSoloSeGuardaSuHashSha256() throws Exception {
        Cajero c = usuarioListo("m1", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));

        var e = sesionMovilService.emitir(c, false, "10.0.0.5", "UA-prueba");

        assertThat(java.util.Base64.getUrlDecoder().decode(e.token())).hasSize(32);
        SesionMovil guardada = sesionRepo.findById(e.sesion().getId()).orElseThrow();
        assertThat(guardada.getTokenHash()).hasSize(64).isNotEqualTo(e.token())
                .isEqualTo(SesionMovilService.hash(e.token()));
        assertThat(guardada.getIp()).isEqualTo("10.0.0.5");
        assertThat(guardada.getUserAgent()).isEqualTo("UA-prueba");
        assertThat(sesionMovilService.validar(e.token())).isPresent();
        assertThat(sesionMovilService.emitir(c, false, null, null).token()).isNotEqualTo(e.token());
    }

    @Test
    void tokensInvalidosNoValidan() {
        assertThat(sesionMovilService.validar(null)).isEmpty();
        assertThat(sesionMovilService.validar("")).isEmpty();
        assertThat(sesionMovilService.validar("inventado")).isEmpty();
        assertThat(sesionMovilService.validar("x".repeat(500))).isEmpty();
    }

    // ── Caducidad ─────────────────────────────────────────────────────────

    @Test
    void sinMantenerAbiertaDuraExactamente48Horas() throws Exception {
        Cajero c = usuarioListo("m2", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        var e = sesionMovilService.emitir(c, false, "ip", "ua");

        assertThat(e.sesion().getExpira()).isEqualTo(e.sesion().getCreada().plusHours(48));

        reloj.avanzar(Duration.ofHours(48).minusSeconds(1));
        assertThat(sesionMovilService.validar(e.token())).as("1 s antes de las 48 h").isPresent();

        reloj.avanzar(Duration.ofSeconds(1));
        assertThat(sesionMovilService.validar(e.token())).as("a las 48 h exactas").isEmpty();

        reloj.avanzar(Duration.ofDays(5));
        assertThat(sesionMovilService.validar(e.token())).isEmpty();
    }

    @Test
    void laSesionPermanenteCaducaSoloPorInactividadDe60Dias() throws Exception {
        Cajero c = usuarioListo("m3", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        var e = sesionMovilService.emitir(c, true, "ip", "ua");

        assertThat(e.sesion().getExpira()).isNull();
        assertThat(e.sesion().isPermanente()).isTrue();

        // Usándola al menos cada 59 días nunca caduca (vencimiento deslizante)
        for (int i = 0; i < 5; i++) {
            reloj.avanzar(Duration.ofDays(59));
            assertThat(sesionMovilService.validar(e.token())).as("uso " + i).isPresent();
        }
        // 60 días exactos sin usarla: vence
        reloj.avanzar(Duration.ofDays(60).minusSeconds(1));
        assertThat(sesionMovilService.validar(e.token())).as("1 s antes de los 60 días").isPresent();
        reloj.avanzar(Duration.ofDays(60));
        assertThat(sesionMovilService.validar(e.token())).as("60 días sin uso").isEmpty();
    }

    @Test
    void unaSesionPermanenteSinUsoDesde60DiasYaNoValida() throws Exception {
        Cajero c = usuarioListo("m3b", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        var e = sesionMovilService.emitir(c, true, "ip", "ua");
        reloj.avanzar(Duration.ofDays(60));
        assertThat(sesionMovilService.validar(e.token())).isEmpty();
    }

    @Test
    void hayComoMaximo5SesionesAbiertasPorUsuarioYSeCierraLaMenosReciente() throws Exception {
        Cajero c = usuarioListo("m3c", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        Cajero otro = usuarioListo("m3d", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        var ajena = sesionMovilService.emitir(otro, false, "ip", "ua");
        java.util.List<SesionMovilService.Emitida> emitidas = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            emitidas.add(sesionMovilService.emitir(c, false, "ip" + i, "ua"));
            reloj.avanzar(Duration.ofMinutes(2));
        }
        // Se usa la más vieja: ya no es la menos reciente
        sesionMovilService.validar(emitidas.get(0).token());
        reloj.avanzar(Duration.ofMinutes(2));

        var sexta = sesionMovilService.emitir(c, false, "ip5", "ua");

        assertThat(sesionMovilService.validar(emitidas.get(1).token())).as("la menos reciente se cierra").isEmpty();
        assertThat(sesionMovilService.validar(emitidas.get(0).token())).isPresent();
        for (int i = 2; i < 5; i++) assertThat(sesionMovilService.validar(emitidas.get(i).token())).isPresent();
        assertThat(sesionMovilService.validar(sexta.token())).isPresent();
        assertThat(sesionMovilService.validar(ajena.token())).as("otros usuarios no se tocan").isPresent();
        assertThat(sesionRepo.findByCajeroIdAndRevocadaFalse(c.getId())).hasSize(5);
    }

    @Test
    void lasSesionesVencidasORevocadasHaceMasDe30DiasSeBorran() throws Exception {
        Cajero c = usuarioListo("m3e", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        var vencida = sesionMovilService.emitir(c, false, "ip", "ua");        // 48 h
        var revocada = sesionMovilService.emitir(c, false, "ip", "ua");
        sesionMovilService.cerrar(revocada.token());
        var viva = sesionMovilService.emitir(c, true, "ip", "ua");

        reloj.avanzar(Duration.ofDays(20));
        sesionMovilService.trabajoDeLimpieza();
        assertThat(sesionRepo.findById(vencida.sesion().getId())).as("aún dentro de los 30 días de retención").isPresent();

        reloj.avanzar(Duration.ofDays(13));            // 33 días: la de 48 h venció hace 31 y la revocada hace 33
        sesionMovilService.trabajoDeLimpieza();
        assertThat(sesionRepo.findById(vencida.sesion().getId())).isEmpty();
        assertThat(sesionRepo.findById(revocada.sesion().getId())).isEmpty();
        // La permanente (sin uso 33 días) todavía vale: se conserva
        assertThat(sesionRepo.findById(viva.sesion().getId())).isPresent();
        assertThat(sesionMovilService.validar(viva.token())).isPresent();
    }

    @Test
    void cambiarLaPreferenciaDespuesDeEntrarMuevelaCaducidad() throws Exception {
        Cajero c = usuarioListo("m4", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        var e = sesionMovilService.emitir(c, false, "ip", "ua");
        Long id = e.sesion().getId();

        assertThat(sesionMovilService.fijarPermanente(id, true).getExpira()).isNull();
        reloj.avanzar(Duration.ofDays(30));
        assertThat(sesionMovilService.validar(e.token())).isPresent();

        // Volver a "No": 48 h contadas desde que se creó la sesión (ya pasaron), así que vence ya
        sesionMovilService.fijarPermanente(id, false);
        assertThat(sesionRepo.findById(id).orElseThrow().getExpira()).isEqualTo(e.sesion().getCreada().plusHours(48));
        assertThat(sesionMovilService.validar(e.token())).isEmpty();
    }

    @Test
    void ultimoUsoSeActualizaSoloCadaMinuto() throws Exception {
        Cajero c = usuarioListo("m5", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        var e = sesionMovilService.emitir(c, false, "ip", "ua");
        LocalDateTime inicio = e.sesion().getUltimoUso();

        reloj.avanzar(Duration.ofSeconds(30));
        sesionMovilService.validar(e.token());
        assertThat(sesionRepo.findById(e.sesion().getId()).orElseThrow().getUltimoUso()).isEqualTo(inicio);

        reloj.avanzar(Duration.ofSeconds(31));
        sesionMovilService.validar(e.token());
        assertThat(sesionRepo.findById(e.sesion().getId()).orElseThrow().getUltimoUso()).isAfter(inicio);
    }

    // ── Revocación ────────────────────────────────────────────────────────

    @Test
    void cerrarRevocaElToken() throws Exception {
        Cajero c = usuarioListo("m6", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        var e = sesionMovilService.emitir(c, true, "ip", "ua");

        assertThat(sesionMovilService.cerrar(e.token())).isTrue();
        assertThat(sesionMovilService.validar(e.token())).isEmpty();
        assertThat(sesionMovilService.cerrar(e.token())).as("ya estaba cerrada").isFalse();
    }

    @Test
    void desactivarAlUsuarioRevocaSusSesionesMoviles() throws Exception {
        Cajero c = usuarioListo("m7", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        var a = sesionMovilService.emitir(c, true, "ip", "ua");
        var b = sesionMovilService.emitir(c, false, "ip", "ua");

        cajeroService.cambiarEstado(c.getId(), false);

        assertThat(sesionRepo.findById(a.sesion().getId()).orElseThrow().isRevocada()).isTrue();
        assertThat(sesionMovilService.validar(a.token())).isEmpty();
        assertThat(sesionMovilService.validar(b.token())).isEmpty();
        // Reactivarlo NO revive tokens viejos
        cajeroService.cambiarEstado(c.getId(), true);
        assertThat(sesionMovilService.validar(a.token())).isEmpty();
    }

    @Test
    void editarAlUsuarioComoInactivoORestablecerSuContrasenaRevocaSesiones() throws Exception {
        Cajero c = usuarioListo("m8", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        var a = sesionMovilService.emitir(c, true, "ip", "ua");
        cajeroService.actualizar(c.getId(), c.getUsuario(), c.getNombreCompleto(), c.getRol(), false);
        assertThat(sesionMovilService.validar(a.token())).isEmpty();

        cajeroService.cambiarEstado(c.getId(), true);
        var b = sesionMovilService.emitir(cajeroRepo.findById(c.getId()).orElseThrow(), true, "ip", "ua");
        cajeroService.actualizar(c.getId(), c.getUsuario(), c.getNombreCompleto(), c.getRol(), true, "otraClave99");
        assertThat(sesionMovilService.validar(b.token())).isEmpty();
    }

    @Test
    void elAdminRestableceLaContrasenaRevocaSesiones() throws Exception {
        Cajero c = usuarioListo("m9", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        var e = sesionMovilService.emitir(c, true, "ip", "ua");

        cajeroService.cambiarContrasena(c.getId(), "nuevaClave55");

        assertThat(sesionMovilService.validar(e.token())).isEmpty();
    }

    @Test
    void cambiarLaContrasenaPropiaRevocaSesiones() throws Exception {
        Cajero c = usuarioListo("m10", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        var e = sesionMovilService.emitir(c, true, "ip", "ua");
        iniciarComo("m10", CLAVE_PRUEBA);

        sesionService.cambiarContrasenaPropia(CLAVE_PRUEBA, "claveNueva77");

        assertThat(sesionMovilService.validar(e.token())).isEmpty();
    }

    @Test
    void siElUsuarioDebeCambiarSuContrasenaElTokenNoSirve() throws Exception {
        Cajero c = usuarioListo("m11", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        var e = sesionMovilService.emitir(c, true, "ip", "ua");
        c.setDebeCambiarContrasena(true);
        cajeroRepo.save(c);

        assertThat(sesionMovilService.validar(e.token())).isEmpty();
    }

    @Test
    void lasSesionesDeOtrosUsuariosNoSeTocan() throws Exception {
        Cajero a = usuarioListo("m12", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        Cajero b = crearUsuario("m13", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        b.setDebeCambiarContrasena(false);
        cajeroRepo.save(b);
        var ta = sesionMovilService.emitir(a, true, "ip", "ua");
        var tb = sesionMovilService.emitir(b, true, "ip", "ua");

        cajeroService.cambiarEstado(a.getId(), false);

        assertThat(sesionMovilService.validar(ta.token())).isEmpty();
        assertThat(sesionMovilService.validar(tb.token())).isPresent();
    }

    @Test
    void losPermisosSeLeenEnVivoDelUsuario() throws Exception {
        Cajero c = usuarioListo("m14", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        var e = sesionMovilService.emitir(c, true, "ip", "ua");
        assertThat(sesionMovilService.validar(e.token()).orElseThrow().cajero().tienePermiso(Permiso.VER_HISTORIAL)).isFalse();

        cajeroService.actualizar(c.getId(), c.getUsuario(), c.getNombreCompleto(), c.getRol(), true, null,
                EnumSet.of(Permiso.VER_HISTORIAL));

        assertThat(sesionMovilService.validar(e.token()).orElseThrow().cajero().tienePermiso(Permiso.VER_HISTORIAL)).isTrue();
    }

    // ── Administración (Configuración → Acceso móvil) ─────────────────────

    @Test
    void soloElAdminAdministraElAccesoMovil() throws Exception {
        usuarioListo("g1", Cajero.Rol.GERENTE, EnumSet.noneOf(Permiso.class));
        usuarioListo("c1", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));

        for (String login : new String[]{"g1", "c1"}) {
            iniciarComo(login, CLAVE_PRUEBA);
            assertThatThrownBy(() -> configuracionService.guardarAccesoMovil(true, 8085))
                    .isInstanceOf(NegocioException.class);
            assertThatThrownBy(() -> accesoMovilService.configurar(false, 8085)).isInstanceOf(NegocioException.class);
            assertThatThrownBy(() -> accesoMovilService.sesionesActivas()).isInstanceOf(NegocioException.class);
            assertThatThrownBy(() -> accesoMovilService.revocarTodas()).isInstanceOf(NegocioException.class);
            assertThatThrownBy(() -> accesoMovilService.revocar(1L)).isInstanceOf(NegocioException.class);
        }
    }

    @Test
    void elAccesoMovilViveApagadoPorDefectoYGuardarloQuedaAuditado() throws Exception {
        Configuracion inicial = configuracionService.obtener();
        assertThat(inicial.isMovilActivo()).isFalse();
        assertThat(inicial.getMovilPuerto()).isEqualTo(8085);

        iniciarComoAdmin();
        configuracionService.guardarAccesoMovil(true, 9191);

        Configuracion c = configuracionService.obtener();
        assertThat(c.isMovilActivo()).isTrue();
        assertThat(c.getMovilPuerto()).isEqualTo(9191);
        assertThat(auditoriaService.ultimas()).anySatisfy(a -> {
            assertThat(a.getAccion()).isEqualTo("CONFIGURACION_MODIFICADA");
            assertThat(a.getDetalle()).contains("Acceso móvil").contains("puerto=9191").contains("activo=false");
        });
    }

    @Test
    void elPuertoDebeEstarEnRango() throws Exception {
        iniciarComoAdmin();
        assertThatThrownBy(() -> configuracionService.guardarAccesoMovil(true, 80)).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> configuracionService.guardarAccesoMovil(true, 70000)).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> accesoMovilService.configurar(true, 0)).isInstanceOf(NegocioException.class);
    }

    @Test
    void elAdminListaYCierraSesionesConAuditoria() throws Exception {
        Cajero c = usuarioListo("m15", Cajero.Rol.CAJERO, EnumSet.noneOf(Permiso.class));
        var uno = sesionMovilService.emitir(c, true, "ip1", "ua");
        var dos = sesionMovilService.emitir(c, false, "ip2", "ua");

        assertThat(accesoMovilService.sesionesActivas()).extracting(i -> i.sesion().getId())
                .contains(uno.sesion().getId(), dos.sesion().getId());

        accesoMovilService.revocar(uno.sesion().getId());
        assertThat(sesionMovilService.validar(uno.token())).isEmpty();
        assertThat(sesionMovilService.validar(dos.token())).isPresent();
        assertThat(accesoMovilService.sesionesActivas()).extracting(i -> i.sesion().getId())
                .doesNotContain(uno.sesion().getId());

        // Una sesión vencida tampoco aparece como activa
        reloj.avanzar(Duration.ofHours(49));
        assertThat(accesoMovilService.sesionesActivas()).extracting(i -> i.sesion().getId())
                .doesNotContain(dos.sesion().getId());

        var tres = sesionMovilService.emitir(c, true, "ip3", "ua");
        assertThat(accesoMovilService.revocarTodas()).isGreaterThanOrEqualTo(1);
        assertThat(sesionMovilService.validar(tres.token())).isEmpty();
        assertThat(auditoriaService.ultimas()).extracting(a -> a.getAccion()).contains("SESION_MOVIL_REVOCADA");
    }
}
