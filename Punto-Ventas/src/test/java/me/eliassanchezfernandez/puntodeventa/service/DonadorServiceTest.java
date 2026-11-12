package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.util.ClaveDonador;
import me.eliassanchezfernandez.puntodeventa.util.RevocacionesApoyo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static me.eliassanchezfernandez.puntodeventa.service.BienvenidaService.Paso.DONACION;
import static me.eliassanchezfernandez.puntodeventa.service.BienvenidaService.Paso.TUTORIAL_GENERAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Estado de donador: la clave la activa solo el ADMIN, queda en la bitácora,
 * aplica a todo el negocio, quita la ventana de donación del flujo de
 * bienvenida, vence sin bloquear nada y respeta la lista de revocación con
 * fail-open. Se firma con un par de llaves de prueba.
 */
class DonadorServiceTest extends BaseServicioTest {

    @Autowired private DonadorService       donadorService;
    @Autowired private BienvenidaService    bienvenidaService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private AuditoriaService     auditoriaService;
    @Autowired private me.eliassanchezfernandez.puntodeventa.repository.ConfiguracionRepository configuracionRepo;

    private static KeyPair par;
    private static String kid;

    @BeforeAll
    static void llaves() throws Exception {
        par = KeyPairGenerator.getInstance(ClaveDonador.ALGORITMO).generateKeyPair();
        kid = ClaveDonador.kidDe(par.getPublic());
    }

    @BeforeEach
    void verificadorDePruebas() {
        donadorService.usarVerificadorParaPruebas(new ClaveDonador(par.getPublic()));
        donadorService.usarRelojParaPruebas(null);
        BienvenidaService.clabeConfiguradaParaPruebas = true;   // el flujo de donación supone CLABE configurada
    }

    @AfterEach
    void verificadorOficial() {
        donadorService.usarVerificadorParaPruebas(null);
        donadorService.usarRelojParaPruebas(null);
        BienvenidaService.clabeConfiguradaParaPruebas = null;
    }

    /** PDV1 (formato anterior, sin vencimiento) */
    private static String claveV1(String nombre) {
        return ClaveDonador.emitir(new ClaveDonador.Datos(ClaveDonador.nuevoId(), nombre, LocalDate.of(2026, 11, 12), "Oro"),
                par.getPrivate());
    }

    private static String claveV2(String id, String nombre, LocalDate vence, List<String> flags) {
        return ClaveDonador.emitir(new ClaveDonador.Datos(kid, id, nombre, LocalDate.now().minusDays(1), vence, "plus", flags),
                par.getPrivate());
    }

    private static Clock dia(LocalDate f) {
        return Clock.fixed(Instant.from(f.atStartOfDay(ZoneId.systemDefault())), ZoneId.systemDefault());
    }

    @Test
    void sinClaveEsVersionGratuitaYElAdminVeLaDonacion() throws Exception {
        iniciarComoAdmin();
        assertThat(donadorService.esDonador()).isFalse();
        assertThat(donadorService.donador()).isEmpty();
        assertThat(donadorService.apoyo()).isEmpty();
        assertThat(donadorService.permiteBeta()).isFalse();
        assertThat(bienvenidaService.mostrarDonacion()).isTrue();
        assertThat(bienvenidaService.pasosPendientes()).contains(DONACION);
    }

    @Test
    void elAdminActivaLaClaveQuedaEnBitacoraYDesapareceLaDonacion() throws Exception {
        iniciarComoAdmin();
        ClaveDonador.Datos d = donadorService.activar("  " + claveV1("Abarrotes Lupita") + "\n");
        assertThat(d.nombre()).isEqualTo("Abarrotes Lupita");

        assertThat(donadorService.esDonador()).isTrue();
        assertThat(donadorService.donador()).map(ClaveDonador.Datos::nombre).contains("Abarrotes Lupita");
        assertThat(donadorService.permiteBeta()).isTrue();   // PDV1: sin banderas = todo
        assertThat(configuracionService.obtener().tieneClaveDonador()).isTrue();
        assertThat(configuracionService.obtener().getClaveDonador()).doesNotContain(" ", "\n");
        assertThat(bienvenidaService.mostrarDonacion()).isFalse();
        assertThat(bienvenidaService.pasosPendientes()).doesNotContain(DONACION);
        assertThat(auditoriaService.ultimas()).anyMatch(a -> "CLAVE_DONADOR_ACTIVADA".equals(a.getAccion())
                && a.getDetalle().contains("Abarrotes Lupita") && a.getDetalle().contains(d.id()));
    }

    @Test
    void unaClaveInvalidaNoSeGuarda() throws Exception {
        iniciarComoAdmin();
        assertThatThrownBy(() -> donadorService.activar("PDV1.basura.basura")).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> donadorService.activar("PDV2.basura.basura")).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> donadorService.activar("")).isInstanceOf(NegocioException.class);
        assertThat(donadorService.esDonador()).isFalse();
        assertThat(configuracionService.obtener().tieneClaveDonador()).isFalse();
        assertThat(auditoriaService.ultimas()).noneMatch(a -> "CLAVE_DONADOR_ACTIVADA".equals(a.getAccion()));
    }

    @Test
    void soloElAdministradorActivaOQuitaLaClave() throws Exception {
        iniciarComoCajero("caja9", Set.of());
        assertThatThrownBy(() -> donadorService.activar(claveV1("Tienda X"))).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> donadorService.desactivar()).isInstanceOf(NegocioException.class);
        // validar (sin guardar) sí puede cualquiera: la pantalla avisa antes de pedir al admin
        assertThat(donadorService.validar(claveV1("Tienda X")).nombre()).isEqualTo("Tienda X");
        assertThat(donadorService.esDonador()).isFalse();
    }

    @Test
    void quitarLaClaveRegresaALaVersionGratuita() throws Exception {
        iniciarComoAdmin();
        donadorService.activar(claveV1("Abarrotes Lupita"));
        donadorService.desactivar();
        assertThat(donadorService.esDonador()).isFalse();
        assertThat(configuracionService.obtener().tieneClaveDonador()).isFalse();
        assertThat(auditoriaService.ultimas()).anyMatch(a -> "CLAVE_DONADOR_QUITADA".equals(a.getAccion()));
        donadorService.desactivar();   // idempotente
    }

    @Test
    void laFirmaSeVuelveAComprobarEnCadaLecturaNoHayBooleanoGuardado() throws Exception {
        iniciarComoAdmin();
        donadorService.activar(claveV1("Abarrotes Lupita"));
        // Cambia la llave pública (p. ej. el desarrollador rotó el par): la clave guardada deja de valer sin romper nada
        donadorService.usarVerificadorParaPruebas(ClaveDonador.oficial());
        assertThat(donadorService.esDonador()).isFalse();
        assertThat(donadorService.apoyo()).isEmpty();
        assertThat(bienvenidaService.mostrarDonacion()).isTrue();
        // Alguien edita el texto en la base "a mano": tampoco
        donadorService.usarVerificadorParaPruebas(new ClaveDonador(par.getPublic()));
        var c = configuracionService.obtenerParaEditar();
        c.setClaveDonador(c.getClaveDonador().substring(0, c.getClaveDonador().length() - 3) + "AAA");
        configuracionRepo.save(c);
        configuracionService.invalidarCache();
        assertThat(donadorService.esDonador()).isFalse();
    }

    @Test
    void alVencerTerminanLosExtrasPeroNadaSeBloquea() throws Exception {
        iniciarComoAdmin();
        String id = ClaveDonador.nuevoId();
        donadorService.usarRelojParaPruebas(dia(LocalDate.of(2027, 6, 1)));
        donadorService.activar(claveV2(id, "Abarrotes Lupita", LocalDate.of(2027, 11, 12), List.of()));
        assertThat(donadorService.esDonador()).isTrue();
        assertThat(donadorService.apoyo()).get().extracting(DonadorService.Apoyo::resumen).isEqualTo("Abarrotes Lupita · vigente hasta 12/11/2027");

        // El tiempo pasa: la clave sigue guardada y válida, pero ya no es donador vigente
        donadorService.usarRelojParaPruebas(dia(LocalDate.of(2027, 11, 13)));
        assertThat(donadorService.esDonador()).isFalse();
        assertThat(donadorService.permiteBeta()).isFalse();
        assertThat(donadorService.apoyo()).isPresent();
        assertThat(donadorService.apoyo().get().estado()).isEqualTo(ClaveDonador.Estado.VENCIDA);
        assertThat(donadorService.apoyo().get().resumen()).contains("venció el 12/11/2027");
        assertThat(bienvenidaService.mostrarDonacion()).isTrue();
        // y una clave ya vencida no se puede activar: hay que renovarla
        assertThatThrownBy(() -> donadorService.activar(claveV2(ClaveDonador.nuevoId(), "Otra", LocalDate.of(2027, 1, 1), List.of())))
                .isInstanceOf(NegocioException.class).hasMessageContaining("venció");
        // Vender, cobrar, etc. no consultan esta clase (ApoyoNoBloqueaTest); aquí basta ver que sigue todo accesible
        assertThat(configuracionService.obtener().tieneClaveDonador()).isTrue();
    }

    @Test
    void lasBanderasDeLaClaveLimitanLasBetas() throws Exception {
        iniciarComoAdmin();
        donadorService.activar(claveV2(ClaveDonador.nuevoId(), "Solo soporte", null, List.of("soporte")));
        assertThat(donadorService.esDonador()).isTrue();
        assertThat(donadorService.permiteBeta()).isFalse();
    }

    @Test
    void losDatosDeDonacionFirmadosSeCotejanConLosIncrustados() throws Exception {
        me.eliassanchezfernandez.puntodeventa.util.Donacion.Datos incrustados = me.eliassanchezfernandez.puntodeventa.util.Donacion.cargar();
        // Sin copia local: sin verificar (fail-open), nunca alerta
        DonadorService.EstadoDonacion e = donadorService.verificarDatosDonacion(incrustados);
        assertThat(e.verificacion()).isEqualTo(DonadorService.VerificacionDonacion.SIN_VERIFICAR);
        assertThat(e.alerta()).isFalse();
        assertThat(e.texto()).contains("Sin verificar");

        // Basura o firma ajena: se ignora
        assertThat(donadorService.actualizarDatosDonacion("hola")).isFalse();
        KeyPair otro = KeyPairGenerator.getInstance(ClaveDonador.ALGORITMO).generateKeyPair();
        var propios = me.eliassanchezfernandez.puntodeventa.util.DatosDonacionFirmados.Datos.de(incrustados, LocalDate.now());
        assertThat(donadorService.actualizarDatosDonacion(
                me.eliassanchezfernandez.puntodeventa.util.DatosDonacionFirmados.emitir(propios, otro.getPrivate()))).isFalse();
        assertThat(donadorService.verificarDatosDonacion(incrustados).verificacion()).isEqualTo(DonadorService.VerificacionDonacion.SIN_VERIFICAR);

        // Los mismos datos del programa, firmados: verificados (o pendientes mientras no haya CLABE)
        String firmados = me.eliassanchezfernandez.puntodeventa.util.DatosDonacionFirmados.emitir(propios, par.getPrivate());
        assertThat(donadorService.actualizarDatosDonacion(firmados)).isTrue();
        assertThat(donadorService.actualizarDatosDonacion(firmados)).isFalse();
        assertThat(configuracionService.obtener().getDatosDonacionFirmados()).startsWith("PDVD1.");
        e = donadorService.verificarDatosDonacion(incrustados);
        assertThat(e.verificacion()).isEqualTo(incrustados.clabeConfigurada()
                ? DonadorService.VerificacionDonacion.VERIFICADOS : DonadorService.VerificacionDonacion.PENDIENTES);
        assertThat(e.alerta()).isFalse();
        assertThat(auditoriaService.ultimas()).anyMatch(a -> "DATOS_DONACION_ACTUALIZADOS".equals(a.getAccion()));

        // El autor publica otra cuenta: lo incrustado ya no coincide → alerta
        var otraCuenta = new me.eliassanchezfernandez.puntodeventa.util.DatosDonacionFirmados.Datos(LocalDate.now(), false,
                "032180000118359719", "BBVA", "Elias Oziel Sánchez Fernández", "", "", "");
        assertThat(donadorService.actualizarDatosDonacion(
                me.eliassanchezfernandez.puntodeventa.util.DatosDonacionFirmados.emitir(otraCuenta, par.getPrivate()))).isTrue();
        e = donadorService.verificarDatosDonacion(incrustados);
        assertThat(e.verificacion()).isEqualTo(DonadorService.VerificacionDonacion.NO_COINCIDEN);
        assertThat(e.alerta()).isTrue();
        assertThat(e.texto()).contains("NO coinciden");
        // Un programa cuyo donacion.properties sí trae esa cuenta queda verificado
        var coincide = new me.eliassanchezfernandez.puntodeventa.util.Donacion.Datos("032 180 00011835971 9", "bbva", "ELIAS OZIEL SÁNCHEZ FERNÁNDEZ");
        assertThat(donadorService.verificarDatosDonacion(coincide).verificacion()).isEqualTo(DonadorService.VerificacionDonacion.VERIFICADOS);

        // Anti-retroceso y fecha del futuro: no se aceptan
        var vieja = new me.eliassanchezfernandez.puntodeventa.util.DatosDonacionFirmados.Datos(LocalDate.now().minusDays(10), true, "", "", "", "", "", "");
        assertThat(donadorService.actualizarDatosDonacion(
                me.eliassanchezfernandez.puntodeventa.util.DatosDonacionFirmados.emitir(vieja, par.getPrivate()))).isFalse();
        var futura = new me.eliassanchezfernandez.puntodeventa.util.DatosDonacionFirmados.Datos(LocalDate.now().plusDays(30), true, "", "", "", "", "", "");
        assertThat(donadorService.actualizarDatosDonacion(
                me.eliassanchezfernandez.puntodeventa.util.DatosDonacionFirmados.emitir(futura, par.getPrivate()))).isFalse();
        assertThat(donadorService.verificarDatosDonacion(incrustados).alerta()).isTrue();

        // El mensaje "ya doné" lleva el beneficiario que vio el donador
        assertThat(donadorService.mensajeDonacion("Lupita", "300", "12/11/2026", null, "Fulano Pérez"))
                .contains("Vi como beneficiario: Fulano Pérez");
    }

    @Test
    void laListaDeRevocacionSeAplicaConFailOpenYAntiRetroceso() throws Exception {
        iniciarComoAdmin();
        String id = ClaveDonador.nuevoId();
        donadorService.activar(claveV2(id, "Abarrotes Lupita", null, List.of()));
        assertThat(donadorService.esDonador()).isTrue();
        assertThat(donadorService.fechaRevocaciones()).isEmpty();

        // Basura o firma ajena: se ignora y no cambia nada
        assertThat(donadorService.actualizarRevocaciones("hola")).isFalse();
        KeyPair otro = KeyPairGenerator.getInstance(ClaveDonador.ALGORITMO).generateKeyPair();
        String ajena = RevocacionesApoyo.emitir(new RevocacionesApoyo.Lista(LocalDate.now(), Set.of(id), Set.of()), otro.getPrivate());
        assertThat(donadorService.actualizarRevocaciones(ajena)).isFalse();
        assertThat(donadorService.esDonador()).isTrue();

        // Lista auténtica que revoca esta clave
        String lista = RevocacionesApoyo.emitir(new RevocacionesApoyo.Lista(LocalDate.now(), Set.of(id), Set.of()), par.getPrivate());
        assertThat(donadorService.actualizarRevocaciones(lista)).isTrue();
        assertThat(donadorService.actualizarRevocaciones(lista)).isFalse();   // la misma otra vez: nada que hacer
        assertThat(donadorService.esDonador()).isFalse();
        assertThat(donadorService.apoyo().get().estado()).isEqualTo(ClaveDonador.Estado.REVOCADA);
        assertThat(donadorService.fechaRevocaciones()).contains(LocalDate.now());
        assertThat(configuracionService.obtener().getRevocacionesApoyo()).startsWith("PDVR1.");
        assertThat(auditoriaService.ultimas()).anyMatch(a -> "REVOCACIONES_APOYO_ACTUALIZADAS".equals(a.getAccion()));
        // Activar una clave revocada se rechaza
        assertThatThrownBy(() -> donadorService.activar(claveV2(id, "Abarrotes Lupita", null, List.of())))
                .isInstanceOf(NegocioException.class).hasMessageContaining("revocada");

        // Anti-retroceso: una lista más vieja (vacía) no "desrevoca"
        String vieja = RevocacionesApoyo.emitir(RevocacionesApoyo.Lista.vacia(LocalDate.now().minusDays(10)), par.getPrivate());
        assertThat(donadorService.actualizarRevocaciones(vieja)).isFalse();
        assertThat(donadorService.esDonador()).isFalse();
        // Una del futuro lejano tampoco
        String futura = RevocacionesApoyo.emitir(RevocacionesApoyo.Lista.vacia(LocalDate.now().plusDays(30)), par.getPrivate());
        assertThat(donadorService.actualizarRevocaciones(futura)).isFalse();

        // Una nueva del mismo día que ya no la lista: vuelve a ser donador
        String limpia = RevocacionesApoyo.emitir(RevocacionesApoyo.Lista.vacia(LocalDate.now()), par.getPrivate());
        assertThat(donadorService.actualizarRevocaciones(limpia)).isTrue();
        assertThat(donadorService.esDonador()).isTrue();

        // Retirar la llave firmante revoca todas sus claves PDV2
        String retiro = RevocacionesApoyo.emitir(new RevocacionesApoyo.Lista(LocalDate.now(), Set.of(), Set.of(kid)), par.getPrivate());
        assertThat(donadorService.actualizarRevocaciones(retiro)).isTrue();
        assertThat(donadorService.esDonador()).isFalse();
    }

    @Test
    void unaCopiaLocalCorruptaCuentaComoSinLista() throws Exception {
        iniciarComoAdmin();
        donadorService.activar(claveV2(ClaveDonador.nuevoId(), "Abarrotes Lupita", null, List.of()));
        var c = configuracionService.obtenerParaEditar();
        c.setRevocacionesApoyo("PDVR1.basura.basura");
        configuracionRepo.save(c);
        configuracionService.invalidarCache();
        assertThat(donadorService.esDonador()).isTrue();     // fail-open
        assertThat(donadorService.fechaRevocaciones()).isEmpty();
    }

    @Test
    void elMensajeDeSoporteTraeLoQueElDesarrolladorNecesita() throws Exception {
        iniciarComoAdmin();
        String sin = donadorService.mensajeSoporte("no imprime");
        assertThat(sin).startsWith("Soporte Caja Libre\n").contains("Versión: ", "Clave de apoyo: ninguna", "Instalación: ", "Problema: no imprime");

        String id = ClaveDonador.nuevoId();
        String clave = claveV2(id, "Abarrotes Lupita", LocalDate.of(2027, 11, 12), List.of());
        donadorService.activar(clave);
        String con = donadorService.mensajeSoporte(null);
        assertThat(con).contains("Clave de apoyo: " + id + " · Abarrotes Lupita · vigente hasta 12/11/2027 · firma " + ClaveDonador.huella(clave))
                .contains("una caja").endsWith("Problema: ");
        assertThat(donadorService.idInstalacion()).matches("[0-9A-F]{4}-[0-9A-F]{4}|sin id");

        String don = donadorService.mensajeDonacion(" Abarrotes Lupita ", "300", "12/11/2026", "");
        assertThat(don).contains("Negocio: Abarrotes Lupita", "Monto: 300", "Fecha: 12/11/2026", "Instalación: ").doesNotContain("rastreo");
    }

    @Test
    void elOrdenDeBienvenidaOmiteLaDonacionParaDonadores() {
        assertThat(BienvenidaService.pasos(Cajero.Rol.ADMIN, true, true, true, true)).isEmpty();
        assertThat(BienvenidaService.pasos(Cajero.Rol.ADMIN, true, false, true, true)).containsExactly(TUTORIAL_GENERAL);
        assertThat(BienvenidaService.pasos(Cajero.Rol.ADMIN, true, true, false, true)).containsExactly(DONACION);
        assertThat(BienvenidaService.pasos(Cajero.Rol.CAJERO, true, true, true, true)).isEmpty();
        // Sin CLABE configurada no hay a dónde donar: la ventana no se muestra aunque no sea donador
        assertThat(BienvenidaService.pasos(Cajero.Rol.ADMIN, true, true, false, false)).isEmpty();
    }

    @Test
    void laOpcionDeActualizacionesAutomaticasEsSoloAdminYSeAudita() throws Exception {
        iniciarComoAdmin();
        assertThat(configuracionService.isActualizacionesAuto()).isTrue();
        configuracionService.guardarActualizacionesAutomaticas(false);
        assertThat(configuracionService.isActualizacionesAuto()).isFalse();
        assertThat(auditoriaService.ultimas()).anyMatch(a -> "CONFIGURACION_MODIFICADA".equals(a.getAccion())
                && a.getDetalle().contains("[Actualizaciones]") && a.getDetalle().contains("automáticas=false"));

        iniciarComoCajero("caja8", Set.of());
        assertThatThrownBy(() -> configuracionService.guardarActualizacionesAutomaticas(true)).isInstanceOf(NegocioException.class);
        assertThat(configuracionService.isActualizacionesAuto()).isFalse();
    }
}
