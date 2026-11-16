package me.eliassanchezfernandez.puntodeventa.servidor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.model.SuscripcionPush;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.service.AvisosService;
import me.eliassanchezfernandez.puntodeventa.service.CajeroService;
import me.eliassanchezfernandez.puntodeventa.service.NotificacionesPushService;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.service.ProgramadorAvisosPush;
import me.eliassanchezfernandez.puntodeventa.service.ProveedorService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Programador de notificaciones: horarios por tipo, nunca el mismo aviso dos veces el mismo día,
 * preferencias y horas de silencio por teléfono, importes solo con permiso, suscripciones muertas
 * y usuarios desactivados. El envío real se sustituye por un enviador falso cuyo cuerpo cifrado se
 * descifra con la clave privada del teléfono del RFC 8291 para leer el mensaje.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProgramadorAvisosPushTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String UA_PUBLICA = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    static final String UA_PRIVADA = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";

    @Autowired private ProgramadorAvisosPush programador;
    @Autowired private NotificacionesPushService pushService;
    @Autowired private AvisosService avisosService;
    @Autowired private SesionService sesionService;
    @Autowired private CajeroService cajeroService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private ProveedorService proveedorService;
    @Autowired private ProductoService productoService;

    /** Enviador falso que recuerda los mensajes (ya descifrados) por endpoint */
    static class EnviadorFalso implements EnviadorPush {
        record Envio(String endpoint, JsonNode mensaje, boolean urgente) { }
        final List<Envio> envios = new ArrayList<>();
        int estado = 201;
        @Override public Resultado enviar(String endpoint, byte[] cuerpo, String authorization, int ttl, boolean urgente) throws java.io.IOException {
            try {
                envios.add(new Envio(endpoint, JSON.readTree(descifrar(cuerpo)), urgente));
            } catch (java.security.GeneralSecurityException e) {
                throw new java.io.IOException(e);
            }
            return new Resultado(estado, "");
        }
        List<String> titulos() { return envios.stream().map(e -> e.mensaje().get("titulo").asText()).toList(); }
        void limpiar() { envios.clear(); }
    }

    /** Lo que haría el navegador del teléfono (RFC 8291 §3) con la clave privada del ejemplo del RFC */
    static byte[] descifrar(byte[] cuerpo) throws java.security.GeneralSecurityException {
        byte[] sal = Arrays.copyOfRange(cuerpo, 0, 16);
        byte[] asPublica = Arrays.copyOfRange(cuerpo, 21, 86);
        byte[] cifrado = Arrays.copyOfRange(cuerpo, 86, cuerpo.length);
        Base64.Decoder b64 = Base64.getUrlDecoder();
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(WebPushCripto.privadaDeCruda(b64.decode(UA_PRIVADA)));
        ka.doPhase(WebPushCripto.publicaDeCruda(asPublica), true);
        byte[] prkClave = WebPushCripto.hkdfExtraer(b64.decode(AUTH), ka.generateSecret());
        byte[] ikm = WebPushCripto.hkdfExpandir(prkClave, WebPushCripto.bytes("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), b64.decode(UA_PUBLICA), asPublica), 32);
        byte[] prk = WebPushCripto.hkdfExtraer(sal, ikm);
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(WebPushCripto.hkdfExpandir(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16), "AES"),
                new GCMParameterSpec(128, WebPushCripto.hkdfExpandir(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12)));
        byte[] registro = aes.doFinal(cifrado);
        return Arrays.copyOf(registro, registro.length - 1);   // quita el delimitador 0x02
    }

    private final EnviadorFalso enviador = new EnviadorFalso();
    private final LocalDate hoy = LocalDate.now();

    @BeforeEach
    void preparar() throws Exception {
        Cajero a = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();
        a.setDebeCambiarContrasena(false);
        a.setBloqueadoHasta(null);
        a.setIntentosFallidos(0);
        cajeroRepo.save(a);
        sesionService.cerrarSesion();
        sesionService.autenticar("admin", "admin123");
        pushService.usarEnviador(enviador);
        for (SuscripcionPush s : pushService.listarTodas()) pushService.quitarPorId(s.getId());   // cada prueba parte sin teléfonos
        avisosService.invalidarCache();
    }

    @AfterEach
    void limpiar() {
        pushService.usarEnviador(null);
        sesionService.cerrarSesion();
    }

    // ── Ayudas ────────────────────────────────────────────────────────────

    private Cajero cajero(Permiso... permisos) {
        String login = "pp" + UUID.randomUUID().toString().substring(0, 8);
        Set<Permiso> set = permisos.length == 0 ? EnumSet.noneOf(Permiso.class) : EnumSet.copyOf(List.of(permisos));
        Cajero c = cajeroService.crear(login, "Push " + login, "clave123", Cajero.Rol.CAJERO, set);
        c.setDebeCambiarContrasena(false);
        return cajeroRepo.save(c);
    }

    private SuscripcionPush telefono(Cajero c) {
        return pushService.suscribir(c, null, "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID(), UA_PUBLICA, AUTH, "Mozilla/5.0 (Linux; Android 14) Chrome");
    }

    private Proveedor proveedor(String nombre, Set<DayOfWeek> entrega, Set<DayOfWeek> preventa) {
        Proveedor p = proveedorService.guardar(null, nombre + " " + UUID.randomUUID().toString().substring(0, 6), null, null, null, entrega, preventa, null);
        avisosService.invalidarCache();
        return p;
    }

    private Producto producto(String nombre, double stock, double minimo) {
        Producto p = new Producto();
        p.setCodigoBarras("PP-" + UUID.randomUUID().toString().substring(0, 8));
        p.setDescripcion(nombre + " " + UUID.randomUUID().toString().substring(0, 6));
        p.setPrecioCosto(5);
        p.setPrecioVenta(10);
        p.setTipoVenta(Producto.TipoVenta.UNIDAD);
        p.setUsaInventario(true);
        p.setCantidadActual(stock);
        p.setCantidadMinima(minimo);
        Producto g = productoService.guardar(p);
        avisosService.invalidarCache();
        return g;
    }

    private LocalDateTime a(int hora, int minuto) { return hoy.atTime(hora, minuto); }

    private List<String> titulosDe(SuscripcionPush s) {
        return enviador.envios.stream().filter(e -> e.endpoint().equals(s.getEndpoint())).map(e -> e.mensaje().get("titulo").asText()).toList();
    }

    // ── Pruebas ───────────────────────────────────────────────────────────

    @Test
    void losProveedoresDeHoySalenALas8LosDeMananaALas19YNuncaSeRepitenElMismoDia() {
        DayOfWeek dHoy = hoy.getDayOfWeek(), dManana = hoy.plusDays(1).getDayOfWeek();
        Proveedor bimbo = proveedor("Bimbo", EnumSet.of(dHoy), Set.of());
        Proveedor sabritas = proveedor("Sabritas", EnumSet.of(dManana), Set.of());
        Cajero c = cajero();
        SuscripcionPush s = telefono(c);
        s = pushService.preferencias(c, s.getEndpoint(), true, true, false, false, 0, 0);   // solo proveedores, sin silencio

        programador.evaluar(a(7, 59));
        assertThat(enviador.envios).as("antes de las 8 no sale nada").isEmpty();

        ProgramadorAvisosPush.Resumen r = programador.evaluar(a(8, 0));
        assertThat(r.suscripcionesRevisadas()).isEqualTo(1);
        assertThat(r.enviados()).isPositive();
        String hoyBimbo = "Hoy entrega " + bimbo.getNombre(), mananaSabritas = "Mañana viene " + sabritas.getNombre();
        assertThat(titulosDe(s)).contains(hoyBimbo).doesNotContain(mananaSabritas).allMatch(t -> t.startsWith("Hoy "));
        EnviadorFalso.Envio e = enviador.envios.stream().filter(x -> x.mensaje().get("titulo").asText().equals(hoyBimbo)).findFirst().orElseThrow();
        assertThat(e.urgente()).as("hoy = prioridad 1 = Urgency high").isTrue();
        assertThat(e.mensaje().get("url").asText()).isEqualTo("/#/proveedores?proveedorId=" + bimbo.getId());
        assertThat(e.mensaje().get("etiqueta").asText()).isEqualTo("prov-e-" + bimbo.getId() + "-hoy");
        assertThat(e.mensaje().get("cuerpo").asText()).contains("Llega la mercancía");

        // Mismo día, más tarde: no se repite; a las 19:00 sale el de mañana (una sola vez)
        assertThat(programador.evaluar(a(8, 5)).enviados()).isZero();
        assertThat(programador.evaluar(a(12, 0)).enviados()).isZero();
        int antes = enviador.envios.size();
        assertThat(programador.evaluar(a(19, 0)).enviados()).isPositive();
        assertThat(titulosDe(s)).contains(mananaSabritas);
        assertThat(titulosDe(s).stream().filter(t -> t.equals(hoyBimbo) || t.equals(mananaSabritas)).count()).as("cada aviso una sola vez").isEqualTo(2);
        assertThat(enviador.envios.stream().filter(x -> x.mensaje().get("titulo").asText().equals(mananaSabritas)).findFirst().orElseThrow().urgente()).isFalse();
        assertThat(enviador.envios.subList(antes, enviador.envios.size())).allMatch(x -> x.mensaje().get("titulo").asText().startsWith("Mañana "));
        assertThat(programador.evaluar(a(19, 30)).enviados()).isZero();

        // Lo enviado queda guardado en la suscripción (sobrevive a un reinicio)
        SuscripcionPush guardada = pushService.propia(c, s.getEndpoint()).orElseThrow();
        assertThat(guardada.getEnviadosDia()).isEqualTo(hoy);
        assertThat(guardada.enviados(hoy)).contains("prov-e-" + bimbo.getId() + "-hoy", "prov-e-" + sabritas.getId() + "-manana");
        assertThat(guardada.getUltimoOk()).isNotNull();
        // Otro día la lista empieza de cero
        assertThat(guardada.enviados(hoy.plusDays(1))).isEmpty();
    }

    @Test
    void respetaLasHorasDeSilencioYLosTiposElegidosYElInterruptorGeneral() {
        DayOfWeek dHoy = hoy.getDayOfWeek();
        Proveedor bimbo = proveedor("Bimbo", EnumSet.of(dHoy), Set.of());
        producto("AA-Aceite", 0, 5);
        Cajero c = cajero();
        SuscripcionPush s = telefono(c);

        // Silencio por defecto 21:00-7:00: a las 22:00 no sale nada aunque los avisos estén en hora
        assertThat(programador.evaluar(a(22, 0)).enviados()).isZero();
        assertThat(enviador.envios).isEmpty();
        assertThat(s.enSilencio(22)).isTrue();
        assertThat(s.enSilencio(6)).isTrue();
        assertThat(s.enSilencio(7)).isFalse();
        assertThat(s.enSilencio(20)).isFalse();

        // Solo stock: a las 9:00 sale el producto, no el proveedor
        pushService.preferencias(c, s.getEndpoint(), true, false, true, true, 21, 7);
        assertThat(programador.evaluar(a(9, 0)).enviados()).isPositive();
        assertThat(titulosDe(s)).anyMatch(t -> t.startsWith("AA-Aceite")).noneMatch(t -> t.startsWith("Hoy ") || t.startsWith("Mañana "));
        assertThat(enviador.envios.stream().filter(x -> x.mensaje().get("titulo").asText().startsWith("AA-Aceite")).findFirst().orElseThrow()
                .mensaje().get("url").asText()).startsWith("/#/inventario?buscar=AA-Aceite");

        // Interruptor general apagado: nada, aunque haya pendientes
        enviador.limpiar();
        pushService.preferencias(c, s.getEndpoint(), false, true, true, true, 21, 7);
        assertThat(programador.evaluar(a(10, 0)).enviados()).isZero();
        assertThat(programador.evaluar(a(10, 0)).suscripcionesRevisadas()).as("las inactivas ni se revisan").isZero();

        // Lo vuelve a encender: el proveedor de hoy sale ahora (no se había mandado)
        pushService.preferencias(c, s.getEndpoint(), true, true, true, true, 21, 7);
        assertThat(programador.evaluar(a(10, 0)).enviados()).isPositive();
        assertThat(titulosDe(s)).contains("Hoy entrega " + bimbo.getNombre()).noneMatch(t -> t.startsWith("AA-Aceite"));
    }

    @Test
    void seEstaAcabandoSaleComoMaximoDosVecesAlDiaYSoloConLoNuevo() {
        Producto aceite = producto("AA-Aceite", 0, 5);   // "AA-": queda entre los 5 que se enumeran aunque haya otros productos
        Cajero c = cajero();
        SuscripcionPush s = telefono(c);
        pushService.preferencias(c, s.getEndpoint(), true, false, true, false, 0, 0);

        assertThat(programador.evaluar(a(8, 30)).enviados()).as("antes del primer corte").isZero();
        assertThat(programador.evaluar(a(9, 0)).enviados()).isPositive();
        assertThat(titulosDe(s)).contains(aceite.getDescripcion());
        int trasPrimerCorte = enviador.envios.size();

        // Entra otro producto a las 11: espera al corte de las 17
        Producto arroz = producto("AB-Arroz", 0, 3);
        assertThat(programador.evaluar(a(11, 0)).enviados()).isZero();
        assertThat(programador.evaluar(a(16, 59)).enviados()).isZero();
        assertThat(enviador.envios).hasSize(trasPrimerCorte);
        assertThat(programador.evaluar(a(17, 0)).enviados()).isEqualTo(1);
        assertThat(titulosDe(s)).contains(aceite.getDescripcion(), arroz.getDescripcion());
        assertThat(titulosDe(s).stream().filter(t -> t.equals(aceite.getDescripcion())).count()).isEqualTo(1);
        // Otro más a las 18: ya no hay más cortes hoy
        producto("AC-Frijol", 0, 2);
        assertThat(programador.evaluar(a(18, 0)).enviados()).isZero();
        assertThat(programador.evaluar(a(20, 0)).enviados()).isZero();
        assertThat(ProgramadorAvisosPush.corteStock(8)).isNull();
        assertThat(ProgramadorAvisosPush.corteStock(9)).isEqualTo("stock@9");
        assertThat(ProgramadorAvisosPush.corteStock(23)).isEqualTo("stock@17");
    }

    @Test
    void losImportesSoloLleganAQuienPuedeVerCostosYCadaTelefonoRecibeSuVersion() {
        DayOfWeek dHoy = hoy.getDayOfWeek();
        Proveedor sabritas = proveedor("Sabritas", Set.of(), EnumSet.of(dHoy));   // preventa hoy
        Producto papas = producto("Papas", 0, 1);
        productoService.registrarEntradaDeProveedor(papas.getId(), 10, sabritas.getId(), null, 12.5, null);   // nota de $125
        avisosService.invalidarCache();
        Cajero cajero = cajero();
        Cajero gestor = cajero(Permiso.GESTIONAR_PROVEEDORES);
        SuscripcionPush sc = telefono(cajero), sg = telefono(gestor);
        pushService.preferencias(cajero, sc.getEndpoint(), true, true, false, false, 0, 0);
        pushService.preferencias(gestor, sg.getEndpoint(), true, true, false, false, 0, 0);

        assertThat(programador.evaluar(a(8, 0)).enviados()).isGreaterThanOrEqualTo(2);
        String titulo = "Hoy pasa el preventa de " + sabritas.getNombre();
        EnviadorFalso.Envio delCajero = enviador.envios.stream().filter(e -> e.endpoint().equals(sc.getEndpoint()) && e.mensaje().get("titulo").asText().equals(titulo)).findFirst().orElseThrow();
        EnviadorFalso.Envio delGestor = enviador.envios.stream().filter(e -> e.endpoint().equals(sg.getEndpoint()) && e.mensaje().get("titulo").asText().equals(titulo)).findFirst().orElseThrow();
        assertThat(delCajero.mensaje().get("titulo").asText()).isEqualTo(titulo);
        assertThat(delCajero.mensaje().get("cuerpo").asText()).doesNotContain("$");
        assertThat(delGestor.mensaje().get("cuerpo").asText()).contains("$125.00");
    }

    @Test
    void unaSuscripcionMuertaSeOlvidaYUnUsuarioDesactivadoPierdeSusTelefonos() {
        proveedor("Bimbo", EnumSet.of(hoy.getDayOfWeek()), Set.of());
        Cajero c1 = cajero(), c2 = cajero();
        SuscripcionPush s1 = telefono(c1), s2 = telefono(c2);
        pushService.preferencias(c1, s1.getEndpoint(), true, true, false, false, 0, 0);
        pushService.preferencias(c2, s2.getEndpoint(), true, true, false, false, 0, 0);

        enviador.estado = 410;
        ProgramadorAvisosPush.Resumen r = programador.evaluar(a(8, 0));
        assertThat(r.fallidos()).isGreaterThanOrEqualTo(2);
        assertThat(pushService.listarTodas()).as("el servicio dijo 410: se borran").isEmpty();

        enviador.estado = 201;
        SuscripcionPush s3 = telefono(c1);
        pushService.preferencias(c1, s3.getEndpoint(), true, true, false, false, 0, 0);
        cajeroService.cambiarEstado(c1.getId(), false);
        assertThat(programador.evaluar(a(8, 0)).enviados()).isZero();
        assertThat(pushService.listarTodas()).as("usuario desactivado: sus teléfonos se quitan").isEmpty();
    }

    @Test
    void elMensajeLlevaLaSeccionDelAvisoYLaCurvaDelCorte() {
        var m = ProgramadorAvisosPush.mensaje(new AvisosService.Aviso("stock-7", AvisosService.Tipo.STOCK, 1, "paquete", "Coca Cola 600 ml",
                "Quedan 2 y vendes ~5 al día.", AvisosService.Accion.inventario(null, "Coca Cola 600 ml & más")));
        assertThat(m.titulo()).isEqualTo("Coca Cola 600 ml");
        assertThat(m.etiqueta()).isEqualTo("stock-7");
        assertThat(m.url()).isEqualTo("/#/inventario?buscar=Coca+Cola+600+ml+%26+m%C3%A1s");
        var p = ProgramadorAvisosPush.mensaje(new AvisosService.Aviso("pico-3", AvisosService.Tipo.HORA_PICO, 3, "reloj", "Los miércoles vendes más de 18:00 a 20:00", "x", null));
        assertThat(p.url()).isEqualTo("/");
        var f = ProgramadorAvisosPush.mensaje(new AvisosService.Aviso("stock-mas", AvisosService.Tipo.STOCK, 2, "paquete", "Otros 3", "x", AvisosService.Accion.inventario("bajo", null)));
        assertThat(f.url()).isEqualTo("/#/inventario?filtro=bajo");
    }
}
