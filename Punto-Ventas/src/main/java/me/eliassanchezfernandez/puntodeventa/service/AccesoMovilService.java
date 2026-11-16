package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.model.SesionMovil;
import me.eliassanchezfernandez.puntodeventa.servidor.AnunciadorMdns;
import me.eliassanchezfernandez.puntodeventa.servidor.ServidorMovilService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Administración del acceso móvil desde el escritorio (Configuración → Acceso
 * móvil). Todo es solo ADMIN y queda en la bitácora. No es transaccional a
 * propósito: arrancar o detener el servidor no debe ocurrir dentro de una
 * transacción de BD.
 */
@Service
public class AccesoMovilService {

    @Autowired private SesionService        sesionService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private SesionMovilService   sesionMovilService;
    @Autowired private ServidorMovilService servidor;
    @Autowired private AnunciadorMdns       anunciador;
    @Autowired private AuditoriaService     auditoriaService;

    /**
     * Enciende o apaga el servidor EN VIVO y guarda la preferencia. Si el
     * puerto está ocupado lanza NegocioException y no se guarda "activo".
     */
    public Configuracion configurar(boolean activo, int puerto) {
        sesionService.verificarAdmin();
        ConfiguracionService.validarPuertoMovil(puerto);

        int puertoAnterior = servidor.estaActivo() ? servidor.getPuerto() : -1;
        if (activo) {
            if (servidor.estaActivo() && puertoAnterior != puerto) servidor.detener();
            if (!servidor.estaActivo()) {
                try {
                    servidor.iniciar(puerto);
                } catch (NegocioException e) {
                    if (puertoAnterior > 0 && puertoAnterior != puerto) {
                        try { servidor.iniciar(puertoAnterior); } catch (NegocioException ignorada) { /* queda apagado */ }
                    }
                    throw e;
                }
            }
        } else {
            servidor.detener();
        }
        try {
            return configuracionService.guardarAccesoMovil(activo, puerto);
        } catch (RuntimeException e) {
            if (activo) servidor.detener();
            throw e;
        }
    }

    /**
     * Enciende (puerto automático) o apaga el servidor EN VIVO. Al encender prueba el último puerto
     * que funcionó, luego los de respaldo y por último uno asignado por el sistema; guarda el que
     * realmente quedó abierto. Si el administrador fijó un puerto a mano, se usa solo ese.
     */
    public Configuracion configurarAutomatico(boolean activo) {
        sesionService.verificarAdmin();
        Configuracion c = configuracionService.obtener();
        if (activo && c.isMovilPuertoManual()) return configurar(true, c.getMovilPuerto());
        if (activo && !servidor.estaActivo()) servidor.iniciarAutomatico(c.getMovilPuerto());
        if (!activo) servidor.detener();
        try {
            return configuracionService.guardarAccesoMovil(activo, activo ? servidor.getPuerto() : c.getMovilPuerto());
        } catch (RuntimeException e) {
            if (activo) servidor.detener();
            throw e;
        }
    }

    /** Guarda puerto fijo/automático, nombre local y QR con nombre; aplica el nombre en vivo si el servidor está encendido */
    public Configuracion guardarOpciones(boolean puertoManual, String nombreLocal, boolean qrLocal) {
        sesionService.verificarAdmin();
        Configuracion c = configuracionService.guardarOpcionesMovil(puertoManual, nombreLocal, qrLocal);
        servidor.aplicarNombreLocal();
        return c;
    }

    /** El administrador vio el aviso "La dirección cambió" */
    public void confirmarCambioDireccion() {
        configuracionService.confirmarCambioDireccionMovil();
    }

    /** Estado del nombre local para mostrarlo */
    public record NombreLocalInfo(AnunciadorMdns.Estado estado, String host) { }

    public NombreLocalInfo nombreLocal() {
        return new NombreLocalInfo(anunciador.estado(), anunciador.hostEfectivo());
    }

    /** Sugerencia a partir del nombre de la tienda */
    public String nombreLocalSugerido() {
        return ConfiguracionService.sugerirNombreLocal(configuracionService.obtener().getNombreTienda());
    }

    /**
     * Sesión abierta con el nombre de su usuario, lista para mostrar, y los avisos de ESE teléfono
     * (la suscripción push ligada a la sesión; null = sin avisos).
     */
    public record SesionInfo(SesionMovil sesion, String usuario, me.eliassanchezfernandez.puntodeventa.model.SuscripcionPush avisos) {
        public SesionInfo(SesionMovil sesion, String usuario) { this(sesion, usuario, null); }
    }

    public List<SesionInfo> sesionesActivas() {
        sesionService.verificarAdmin();
        java.util.Map<Long, me.eliassanchezfernandez.puntodeventa.model.SuscripcionPush> porSesion = new java.util.HashMap<>();
        for (var s : pushService.listarTodas()) {
            if (s.getSesionMovilId() == null) continue;
            var otra = porSesion.get(s.getSesionMovilId());
            if (otra == null || (!otra.isActiva() && s.isActiva())) porSesion.put(s.getSesionMovilId(), s);
        }
        return sesionMovilService.listarActivas().stream()
                .map(s -> new SesionInfo(s, sesionMovilService.cajeroDe(s)
                        .map(c -> c.getUsuario() + " (" + c.getNombreCompleto() + ")").orElse("(usuario eliminado)"),
                        porSesion.get(s.getId())))
                .toList();
    }

    /** Línea de estado de los avisos de un teléfono, en palabras sencillas */
    public static String estadoAvisos(me.eliassanchezfernandez.puntodeventa.model.SuscripcionPush s) {
        if (s == null) return "Sin avisos";
        if (!s.isActiva()) return "Avisos en pausa";
        if (s.getUltimoError() != null && (s.getUltimoOk() == null || s.getUltimoError().isAfter(s.getUltimoOk()))) {
            return "Avisos activos (el último no llegó)";
        }
        return "Avisos activos";
    }

    /**
     * Manda un aviso de prueba al teléfono (ADMIN). Sale a internet (servicio de Google/Apple/Mozilla):
     * llamarlo fuera del hilo de la pantalla.
     */
    public NotificacionesPushService.Envio enviarPrueba(Long suscripcionId) {
        sesionService.verificarAdmin();
        NotificacionesPushService.Envio e = pushService.enviarPruebaASuscripcion(suscripcionId);
        auditoriaService.registrar(sesionService.getCajeroActual(), "PUSH_PRUEBA_ESCRITORIO",
                "Aviso de prueba a la suscripción id " + suscripcionId + ": " + e.detalle(),
                (me.eliassanchezfernandez.puntodeventa.model.Cajero) null);
        return e;
    }

    /** Direcciones (HTTP) que abren directo el asistente de avisos del teléfono */
    public List<String> urlsAvisos(int puertoHttp) { return servidor.urlsAvisos(puertoHttp); }

    // ── HTTPS, certificado local y notificaciones ─────────────────────────

    @Autowired private me.eliassanchezfernandez.puntodeventa.servidor.CertificadosLocales certificados;
    @Autowired private NotificacionesPushService pushService;

    public boolean httpsActivo() { return servidor.httpsActivo(); }

    public int puertoHttps() { return servidor.getPuertoHttps(); }

    public List<String> urlsHttps() { return servidor.urlsHttps(); }

    /** Direcciones (HTTP) de la guía para instalar el certificado en el teléfono */
    public List<String> urlsCertificado(int puertoHttp) { return servidor.urlsCertificado(puertoHttp); }

    /** Huella corta de la CA local, para compararla con la que muestra el teléfono */
    public String huellaCorta() { return certificados.huellaCorta(); }

    /** Huella SHA-256 completa con dos puntos */
    public String huellaCompleta() { return me.eliassanchezfernandez.puntodeventa.servidor.CertificadosLocales.huellaConDosPuntos(certificados.huellaCa()); }

    /**
     * CA y certificado nuevos (ADMIN). Los teléfonos que instalaron la anterior tendrán que instalar
     * la nueva; mientras no lo hagan, la página segura no les abrirá y no recibirán notificaciones.
     */
    public void regenerarCertificado() {
        sesionService.verificarAdmin();
        certificados.regenerarCa();
        servidor.reiniciarHttps();
        auditoriaService.registrar(sesionService.getCajeroActual(), "CERTIFICADO_MOVIL_REGENERADO",
                "Nueva CA local del acceso móvil; huella " + certificados.huellaCorta(),
                (me.eliassanchezfernandez.puntodeventa.model.Cajero) null);
    }

    /** Puerto HTTPS fijo (null = automático: HTTP + 1); reabre el HTTPS si cambió */
    public Configuracion guardarPuertoHttps(Integer puerto) {
        sesionService.verificarAdmin();
        Integer actual = configuracionService.obtener().getMovilPuertoHttps();
        Configuracion c = configuracionService.guardarPuertoHttpsMovil(puerto);
        if (!java.util.Objects.equals(actual, puerto) && servidor.estaActivo()) servidor.reiniciarHttps();
        return c;
    }

    /** Teléfono con notificaciones, listo para la tabla del escritorio */
    public record TelefonoPush(me.eliassanchezfernandez.puntodeventa.model.SuscripcionPush suscripcion, String usuario, String dispositivo) { }

    public List<TelefonoPush> telefonosConNotificaciones() {
        sesionService.verificarAdmin();
        return pushService.listarTodas().stream()
                .map(s -> new TelefonoPush(s,
                        cajeroRepo.findById(s.getCajeroId()).map(c -> c.getUsuario() + " (" + c.getNombreCompleto() + ")").orElse("(usuario eliminado)"),
                        NotificacionesPushService.resumenAgente(s.getUserAgent())))
                .toList();
    }

    @Autowired private me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository cajeroRepo;

    public void quitarTelefono(Long suscripcionId) {
        sesionService.verificarAdmin();
        pushService.quitarPorId(suscripcionId).ifPresent(s ->
                auditoriaService.registrar(sesionService.getCajeroActual(), "PUSH_SUSCRIPCION_QUITADA",
                        "Suscripción de notificaciones id " + s.getId() + " (usuario id " + s.getCajeroId() + ", "
                                + NotificacionesPushService.resumenAgente(s.getUserAgent()) + ") quitada por el administrador",
                        (me.eliassanchezfernandez.puntodeventa.model.Cajero) null));
    }

    public Configuracion obtenerConfiguracion() {
        return configuracionService.obtener();
    }

    public boolean servidorActivo() { return servidor.estaActivo(); }

    public int puertoEnUso() { return servidor.getPuerto(); }

    public List<String> urlsLan(int puerto) { return servidor.urlsLan(puerto); }

    public void revocar(Long sesionId) {
        sesionService.verificarAdmin();
        sesionMovilService.revocarPorId(sesionId).ifPresent(s ->
                auditoriaService.registrar(sesionService.getCajeroActual(), "SESION_MOVIL_REVOCADA",
                        "Sesión móvil id " + s.getId() + " (usuario id " + s.getCajeroId() + ", ip " + s.getIp() + ")",
                        (me.eliassanchezfernandez.puntodeventa.model.Cajero) null));
    }

    public int revocarTodas() {
        sesionService.verificarAdmin();
        int n = sesionMovilService.revocarTodas();
        auditoriaService.registrar(sesionService.getCajeroActual(), "SESION_MOVIL_REVOCADA",
                "Se cerraron todas las sesiones móviles (" + n + ")",
                (me.eliassanchezfernandez.puntodeventa.model.Cajero) null);
        return n;
    }
}
