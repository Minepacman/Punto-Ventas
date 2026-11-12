package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.repository.ConfiguracionRepository;
import me.eliassanchezfernandez.puntodeventa.util.ClaveDonador;
import me.eliassanchezfernandez.puntodeventa.util.DatosDonacionFirmados;
import me.eliassanchezfernandez.puntodeventa.util.Donacion;
import me.eliassanchezfernandez.puntodeventa.util.IdInstalacion;
import me.eliassanchezfernandez.puntodeventa.util.RevocacionesApoyo;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import me.eliassanchezfernandez.puntodeventa.util.VersionApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

/**
 * Estado de donador (constancia de apoyo) del negocio. La clave —texto firmado
 * por el desarrollador, ver {@link ClaveDonador}— se guarda tal cual en
 * {@code configuracion.clave_donador}, así que con varias cajas aplica a todas.
 * Nunca se guarda un "sí/no": la firma se vuelve a comprobar cada vez que se
 * consulta (microsegundos) y el estado (vigente, vencida, revocada) sale de la
 * carga firmada, de la fecha de hoy y de la lista de revocación local.
 *
 * Lo que cambia para un donador VIGENTE: no ve la ventana de donación al entrar,
 * tiene "Pedir soporte" con los contactos del desarrollador y el buscador de
 * actualizaciones le ofrece también las versiones de prueba. Al vencer, esos
 * extras terminan ("renueva tu apoyo"); NADA del programa se bloquea nunca:
 * ningún servicio de ventas, corte, inventario, impresión, respaldo o
 * facturación consulta esta clase (lo vigila ApoyoNoBloqueaTest).
 * Activar o quitar la clave es solo ADMIN y queda en la bitácora.
 */
@Service
public class DonadorService {

    @Autowired private ConfiguracionRepository configuracionRepo;
    @Autowired private ConfiguracionService    configuracionService;
    @Autowired private SesionService           sesionService;
    @Autowired private AuditoriaService        auditoriaService;
    @Autowired private IdentidadCaja           identidadCaja;

    private volatile ClaveDonador verificador = ClaveDonador.oficial();
    private volatile Clock reloj = Clock.systemDefaultZone();

    private record Resultado(String clave, ClaveDonador.Datos datos) { }

    private volatile Resultado ultimo;

    /** Lista de revocación ya verificada (null = no hay copia local: nada revocado) */
    private volatile RevocacionesApoyo.Lista revocaciones;
    private volatile String revocacionesTexto;

    /** Clave guardada + lo que el programa dice de ella hoy */
    public record Apoyo(ClaveDonador.Datos datos, ClaveDonador.Estado estado, String huella) {
        public boolean vigente() { return estado == ClaveDonador.Estado.VIGENTE; }

        /** "Abarrotes Lupita · vigente hasta 12/11/2027" / "…· venció el 12/11/2026" / "…· revocada" */
        public String resumen() {
            return switch (estado) {
                case VIGENTE -> datos.nombre() + " · " + datos.vigenciaTexto();
                case VENCIDA -> datos.nombre() + " · venció el " + ClaveDonador.Datos.fecha(datos.vence());
                case REVOCADA -> datos.nombre() + " · clave revocada";
            };
        }
    }

    /** Datos de donación del autor ya verificados (null = no hay copia local: «sin verificar») */
    private volatile DatosDonacionFirmados.Datos datosDonacion;
    private volatile String datosDonacionTexto;

    /** Lo que la ventana de donación y «Acerca de» dicen de la cuenta que muestran */
    public enum VerificacionDonacion {
        /** Los datos incrustados coinciden con los que publicó (firmó) el autor */
        VERIFICADOS,
        /** El autor todavía no publica su CLABE (el archivo firmado dice pendiente) */
        PENDIENTES,
        /** Nunca se pudo bajar el archivo firmado: se muestran los datos incrustados con la invitación a cotejar en el sitio */
        SIN_VERIFICAR,
        /** Firma válida del autor pero la cuenta NO es la suya: copia modificada; no donar a esa cuenta */
        NO_COINCIDEN
    }

    public record EstadoDonacion(VerificacionDonacion verificacion, Optional<DatosDonacionFirmados.Datos> publicados) {
        public boolean alerta() { return verificacion == VerificacionDonacion.NO_COINCIDEN; }

        /** Texto corto para la pantalla */
        public String texto() {
            return switch (verificacion) {
                case VERIFICADOS -> "Datos verificados ✔ (firmados por el autor el "
                        + ClaveDonador.Datos.fecha(publicados.map(DatosDonacionFirmados.Datos::emitido).orElse(null)) + ")";
                case PENDIENTES -> "Datos de donación aún no publicados por el autor.";
                case SIN_VERIFICAR -> "Sin verificar (no se ha podido consultar el sitio oficial): compara estos datos con los del sitio antes de donar.";
                case NO_COINCIDEN -> "⚠ Estos datos NO coinciden con los publicados por el autor. No dones a esta cuenta; consulta el sitio oficial.";
            };
        }
    }

    /** Las pruebas firman con su propio par de llaves (la privada oficial no está en el repositorio) */
    void usarVerificadorParaPruebas(ClaveDonador v) {
        verificador = v == null ? ClaveDonador.oficial() : v;
        ultimo = null;
        revocaciones = null;
        revocacionesTexto = null;
        datosDonacion = null;
        datosDonacionTexto = null;
    }

    void usarRelojParaPruebas(Clock c) {
        reloj = c == null ? Clock.systemDefaultZone() : c;
    }

    // ── Consulta ──────────────────────────────────────────────────────────

    /** Clave guardada con firma válida (en cualquier estado); vacío en la versión gratuita */
    public Optional<Apoyo> apoyo() {
        String clave;
        try {
            clave = configuracionService.obtener().getClaveDonador();
        } catch (RuntimeException baseNoDisponible) {
            return Optional.empty();
        }
        return validarGuardada(clave).map(d -> new Apoyo(d, estadoDe(d), ClaveDonador.huella(clave)));
    }

    /** Datos del donador si hay una clave válida Y VIGENTE; vacío si no hay, venció o fue revocada */
    public Optional<ClaveDonador.Datos> donador() {
        return apoyo().filter(Apoyo::vigente).map(Apoyo::datos);
    }

    public boolean esDonador() {
        return donador().isPresent();
    }

    /** Versiones de prueba: donador vigente cuya clave lo permite */
    public boolean permiteBeta() {
        return donador().map(ClaveDonador.Datos::permiteBeta).orElse(false);
    }

    private ClaveDonador.Estado estadoDe(ClaveDonador.Datos d) {
        RevocacionesApoyo.Lista r = revocacionesVigentes();
        return d.estado(LocalDate.now(reloj),
                r == null ? Set.of() : r.revocadas(), r == null ? Set.of() : r.clavesRetiradas());
    }

    private Optional<ClaveDonador.Datos> validarGuardada(String clave) {
        if (clave == null || clave.isBlank()) return Optional.empty();
        Resultado r = ultimo;
        if (r != null && r.clave().equals(clave)) return Optional.ofNullable(r.datos());
        ClaveDonador.Datos datos;
        try {
            datos = verificador.validar(clave);
        } catch (ClaveDonador.ClaveInvalidaException e) {
            datos = null;    // clave guardada que ya no pasa (p. ej. cambió la pública): versión gratuita
        }
        ultimo = new Resultado(clave, datos);
        return Optional.ofNullable(datos);
    }

    /** Solo valida (sin guardar): para que la pantalla avise antes de activar. Una clave vencida o revocada también se rechaza. */
    public ClaveDonador.Datos validar(String clave) {
        ClaveDonador.Datos d;
        try {
            d = verificador.validar(clave);
        } catch (ClaveDonador.ClaveInvalidaException e) {
            throw new NegocioException(e.getMessage());
        }
        switch (estadoDe(d)) {
            case VENCIDA -> throw new NegocioException("Esta clave venció el " + ClaveDonador.Datos.fecha(d.vence())
                    + ". Pide al desarrollador una clave renovada.");
            case REVOCADA -> throw new NegocioException("Esta clave fue revocada por el desarrollador.");
            default -> { }
        }
        return d;
    }

    // ── Cambios (solo ADMIN) ──────────────────────────────────────────────

    /** Guarda la clave si es válida y la deja activa para todo el negocio */
    @Transactional
    public ClaveDonador.Datos activar(String clave) {
        sesionService.verificarAdmin();
        ClaveDonador.Datos datos = validar(clave);
        Configuracion c = configuracionService.obtenerParaEditar();
        Optional<ClaveDonador.Datos> anterior = validarGuardada(c.getClaveDonador());
        c.setClaveDonador(ClaveDonador.limpiar(clave));
        guardar(c);
        auditoriaService.registrar(sesionService.getCajeroActual(), "CLAVE_DONADOR_ACTIVADA",
                datos.nombre() + " (id " + datos.id() + ", emitida " + datos.emision()
                + (datos.vence() != null ? ", vence " + datos.vence() : "")
                + (datos.tieneNivel() ? ", nivel " + datos.nivel() : "")
                + anterior.map(a -> "; reemplaza a " + a.nombre() + " (id " + a.id() + ")").orElse("") + ")",
                (Cajero) null);
        return datos;
    }

    /** Vuelve a la versión gratuita (p. ej. al vender la computadora) */
    @Transactional
    public void desactivar() {
        sesionService.verificarAdmin();
        Configuracion c = configuracionService.obtenerParaEditar();
        if (!c.tieneClaveDonador()) return;
        String detalle = validarGuardada(c.getClaveDonador()).map(d -> d.nombre() + " (id " + d.id() + ")").orElse("clave inválida");
        c.setClaveDonador(null);
        guardar(c);
        auditoriaService.registrar(sesionService.getCajeroActual(), "CLAVE_DONADOR_QUITADA", detalle, (Cajero) null);
    }

    private void guardar(Configuracion c) {
        configuracionRepo.save(c);
        configuracionService.invalidarCache();
        ultimo = null;
    }

    // ── Lista de revocación (la baja ActualizacionService, sin sesión) ───

    /** Copia local verificada; se relee de la configuración si cambió (otra caja pudo actualizarla) */
    private RevocacionesApoyo.Lista revocacionesVigentes() {
        String texto;
        try {
            texto = configuracionService.obtener().getRevocacionesApoyo();
        } catch (RuntimeException baseNoDisponible) {
            return revocaciones;
        }
        if (texto == null || texto.isBlank()) return null;
        if (texto.equals(revocacionesTexto)) return revocaciones;
        try {
            RevocacionesApoyo.Lista l = RevocacionesApoyo.verificar(texto, verificador);
            revocaciones = l;
            revocacionesTexto = texto;
            return l;
        } catch (RevocacionesApoyo.ListaInvalidaException e) {
            return null;    // copia local corrupta o de otra llave: como si no hubiera (fail-open)
        }
    }

    /**
     * Recibe el texto descargado del repositorio. Se guarda solo si la firma es
     * del desarrollador y la fecha de emisión no es anterior a la de la copia
     * local (anti-retroceso). No exige sesión: corre en segundo plano.
     * @return true si se guardó una lista nueva
     */
    @Transactional
    public boolean actualizarRevocaciones(String textoDescargado) {
        if (textoDescargado == null || textoDescargado.isBlank()) return false;
        RevocacionesApoyo.Lista nueva;
        try {
            nueva = RevocacionesApoyo.verificar(textoDescargado, verificador);
        } catch (RevocacionesApoyo.ListaInvalidaException e) {
            return false;
        }
        if (nueva.emitido().isAfter(LocalDate.now(reloj).plusDays(1))) return false;   // fecha del futuro: no se acepta
        RevocacionesApoyo.Lista actual = revocacionesVigentes();
        if (actual != null && nueva.emitido().isBefore(actual.emitido())) return false;
        String limpio = ClaveDonador.limpiar(textoDescargado);
        if (limpio.equals(revocacionesTexto)) return false;
        Configuracion c = configuracionService.obtenerParaEditar();
        if (limpio.equals(c.getRevocacionesApoyo())) return false;
        c.setRevocacionesApoyo(limpio);
        guardar(c);
        revocaciones = nueva;
        revocacionesTexto = limpio;
        auditoriaService.registrarSistema("REVOCACIONES_APOYO_ACTUALIZADAS",
                "emitida " + nueva.emitido() + ", " + nueva.revocadas().size() + " clave(s) revocada(s)");
        return true;
    }

    /** Fecha de la lista de revocación local, si hay */
    public Optional<LocalDate> fechaRevocaciones() {
        RevocacionesApoyo.Lista l = revocacionesVigentes();
        return Optional.ofNullable(l).map(RevocacionesApoyo.Lista::emitido);
    }

    // ── Datos de donación firmados (los baja ActualizacionService, sin sesión) ──

    /** Copia local verificada de los datos de donación del autor; se relee si otra caja la cambió */
    private DatosDonacionFirmados.Datos datosDonacionVigentes() {
        String texto;
        try {
            texto = configuracionService.obtener().getDatosDonacionFirmados();
        } catch (RuntimeException baseNoDisponible) {
            return datosDonacion;
        }
        if (texto == null || texto.isBlank()) return null;
        if (texto.equals(datosDonacionTexto)) return datosDonacion;
        try {
            DatosDonacionFirmados.Datos d = DatosDonacionFirmados.verificar(texto, verificador);
            datosDonacion = d;
            datosDonacionTexto = texto;
            return d;
        } catch (DatosDonacionFirmados.DatosInvalidosException e) {
            return null;    // copia corrupta o de otra llave: como si no hubiera (fail-open)
        }
    }

    /**
     * Recibe el texto descargado del repositorio ({@code claves/donacion.pdvd}). Se guarda solo
     * si la firma es del desarrollador y la fecha no es anterior a la de la copia local.
     * @return true si se guardaron datos nuevos
     */
    @Transactional
    public boolean actualizarDatosDonacion(String textoDescargado) {
        if (textoDescargado == null || textoDescargado.isBlank()) return false;
        DatosDonacionFirmados.Datos nuevos;
        try {
            nuevos = DatosDonacionFirmados.verificar(textoDescargado, verificador);
        } catch (DatosDonacionFirmados.DatosInvalidosException e) {
            return false;
        }
        if (nuevos.emitido().isAfter(LocalDate.now(reloj).plusDays(1))) return false;   // fecha del futuro: no se acepta
        DatosDonacionFirmados.Datos actuales = datosDonacionVigentes();
        if (actuales != null && nuevos.emitido().isBefore(actuales.emitido())) return false;
        String limpio = ClaveDonador.limpiar(textoDescargado);
        if (limpio.equals(datosDonacionTexto)) return false;
        Configuracion c = configuracionService.obtenerParaEditar();
        if (limpio.equals(c.getDatosDonacionFirmados())) return false;
        c.setDatosDonacionFirmados(limpio);
        guardar(c);
        datosDonacion = nuevos;
        datosDonacionTexto = limpio;
        auditoriaService.registrarSistema("DATOS_DONACION_ACTUALIZADOS",
                "emitidos " + nuevos.emitido() + (nuevos.pendiente() ? ", pendientes" : ", CLABE publicada"));
        return true;
    }

    /**
     * ¿Los datos de donación que muestra este programa son los del autor? Compara los
     * incrustados ({@code donacion.properties}) con la copia firmada. Sin copia: sin verificar.
     */
    public EstadoDonacion verificarDatosDonacion(Donacion.Datos incrustados) {
        DatosDonacionFirmados.Datos publicados = datosDonacionVigentes();
        if (publicados == null) return new EstadoDonacion(VerificacionDonacion.SIN_VERIFICAR, Optional.empty());
        if (!publicados.coincideCon(incrustados)) return new EstadoDonacion(VerificacionDonacion.NO_COINCIDEN, Optional.of(publicados));
        return new EstadoDonacion(publicados.pendiente() ? VerificacionDonacion.PENDIENTES : VerificacionDonacion.VERIFICADOS,
                Optional.of(publicados));
    }

    // ── Mensajes para el desarrollador ────────────────────────────────────

    /** Id aleatorio de esta instalación (solo para el libro del desarrollador) */
    public String idInstalacion() {
        return IdInstalacion.obtener(RutasApp.carpetaAplicacion());
    }

    /**
     * Mensaje de soporte prellenado que el usuario puede leer antes de enviar:
     * versión y sistema, clave (id, negocio, vigencia y huella de la firma),
     * id de instalación y modo de caja. Sin clave se dice y ya.
     */
    public String mensajeSoporte(String problema) {
        StringBuilder sb = new StringBuilder("Soporte Caja Libre\n");
        sb.append("Versión: ").append(VersionApp.etiqueta()).append(" (").append(sistema()).append(")\n");
        Optional<Apoyo> a = apoyo();
        if (a.isPresent()) {
            Apoyo ap = a.get();
            sb.append("Clave de apoyo: ").append(ap.datos().id()).append(" · ").append(ap.resumen())
                    .append(" · firma ").append(ap.huella()).append('\n');
        } else {
            sb.append("Clave de apoyo: ninguna\n");
        }
        sb.append("Instalación: ").append(idInstalacion()).append(" · ").append(modoCaja()).append('\n');
        sb.append("Problema: ").append(problema == null || problema.isBlank() ? "" : problema.trim());
        return sb.toString();
    }

    /** Mensaje "ya doné" con lo que el desarrollador necesita para identificar el abono y emitir la clave */
    public String mensajeDonacion(String negocio, String monto, String fecha, String rastreo) {
        return mensajeDonacion(negocio, monto, fecha, rastreo, null);
    }

    /**
     * Igual, con el nombre del beneficiario que el donador vio en su banco: si no es el del autor,
     * el desarrollador detecta de inmediato que pagó a una cuenta ajena (copia modificada).
     */
    public String mensajeDonacion(String negocio, String monto, String fecha, String rastreo, String beneficiarioVisto) {
        StringBuilder sb = new StringBuilder("Hola, apoyé a Caja Libre.\n");
        sb.append("Negocio: ").append(negocio == null || negocio.isBlank() ? "(escríbelo aquí)" : negocio.trim()).append('\n');
        if (monto != null && !monto.isBlank()) sb.append("Monto: ").append(monto.trim()).append('\n');
        if (fecha != null && !fecha.isBlank()) sb.append("Fecha: ").append(fecha.trim()).append('\n');
        if (rastreo != null && !rastreo.isBlank()) sb.append("Clave de rastreo: ").append(rastreo.trim()).append('\n');
        if (beneficiarioVisto != null && !beneficiarioVisto.isBlank()) sb.append("Vi como beneficiario: ").append(beneficiarioVisto.trim()).append('\n');
        sb.append("Instalación: ").append(idInstalacion()).append(" · Versión: ").append(VersionApp.etiqueta());
        return sb.toString();
    }

    private String modoCaja() {
        try {
            return identidadCaja.esMultiCaja() ? "varias cajas (caja " + identidadCaja.numero() + ")" : "una caja";
        } catch (RuntimeException e) {
            return "una caja";
        }
    }

    static String sistema() {
        String os = System.getProperty("os.name", "?");
        String arch = System.getProperty("os.arch", "");
        return os + (arch.isBlank() ? "" : " " + arch);
    }
}
